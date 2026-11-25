using System.Globalization;
using System.IO;
using System.Net.Http;
using System.Net.Sockets;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Windows;
using System.Windows.Automation;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Threading;
using IOPath = System.IO.Path;

namespace Pause;

public partial class MainWindow : Window
{
    private static readonly CultureInfo French = CultureInfo.GetCultureInfo("fr-FR");
    private readonly JournalStore store;
    private readonly FileSystemWatcher journalChanges;
    private readonly DispatcherTimer refreshTimer = new() { Interval = TimeSpan.FromMilliseconds(120) };
    private readonly DispatcherTimer driveTimer = new() { Interval = TimeSpan.FromMinutes(2) };
    private readonly DispatcherTimer driveAfterEdit = new() { Interval = TimeSpan.FromSeconds(4) };
    private readonly DriveSync drive;
    private readonly ChatGptPlan chatgpt = new();
    private readonly TrackerList trackers;
    private SyncServer? server;
    private string? serverError;
    private DateOnly selected = DateOnly.FromDateTime(DateTime.Today);
    private bool rendering;
    private readonly CancellationTokenSource estimateLifetime = new();
    private bool estimating, estimateAgain;
    private string? estimateError;
    private event Action? EstimatesChanged;

    internal MainWindow(JournalStore store)
    {
        this.store = store;
        drive = new DriveSync(store);
        InitializeComponent();
        trackers = new TrackerList(this, store, Refresh);
        TrackersHost.Content = trackers;
        DayCalendar.DisplayDateEnd = DateTime.Today;
        refreshTimer.Tick += (_, _) =>
        {
            refreshTimer.Stop();
            Refresh();
        };
        journalChanges = new FileSystemWatcher(JournalStore.DirectoryPath, IOPath.GetFileName(JournalStore.FilePath))
        {
            NotifyFilter = NotifyFilters.FileName | NotifyFilters.LastWrite | NotifyFilters.Size
        };
        journalChanges.Changed += (_, _) => QueueJournalChange();
        journalChanges.Created += (_, _) => QueueJournalChange();
        journalChanges.Renamed += (_, _) => QueueJournalChange();
        journalChanges.EnableRaisingEvents = true;
        if (File.Exists(IOPath.Combine(JournalStore.DirectoryPath, "pairing.key"))) StartSync();
        Closed += (_, _) =>
        {
            journalChanges.Dispose();
            refreshTimer.Stop();
            driveTimer.Stop();
            driveAfterEdit.Stop();
            server?.Dispose();
            estimateLifetime.Cancel();
        };
        Activated += (_, _) => QueueRefresh();
        driveTimer.Tick += async (_, _) => await SyncDriveAsync();
        driveAfterEdit.Tick += async (_, _) => { driveAfterEdit.Stop(); await SyncDriveAsync(); };
        Loaded += async (_, _) => { driveTimer.Start(); await SyncDriveAsync(); };
        Refresh();
    }

    private void QueueJournalChange()
    {
        QueueRefresh();
        if (Dispatcher.HasShutdownStarted) return;
        Dispatcher.BeginInvoke(() =>
        {
            if (!drive.Connected) return;
            driveAfterEdit.Stop();
            driveAfterEdit.Start();
        });
    }

    private void QueueRefresh()
    {
        if (Dispatcher.HasShutdownStarted) return;
        Dispatcher.BeginInvoke(() =>
        {
            refreshTimer.Stop();
            refreshTimer.Start();
        });
    }

    private bool StartSync()
    {
        if (server is not null) return true;
        try
        {
            server = new SyncServer(store);
            server.Start();
            server.Synced += () => Dispatcher.BeginInvoke(() =>
            {
                QueueRefresh();
                _ = PrepareEstimatesAsync();
            });
            serverError = null;
            return true;
        }
        catch (Exception error) when (JournalStore.IsFailure(error) || error is SocketException or System.Security.Cryptography.CryptographicException)
        {
            server?.Dispose();
            server = null;
            serverError = "Synchro Wi-Fi indisponible sur ce PC. " + error.Message;
            return false;
        }
    }

    private static DateOnly Today => DateOnly.FromDateTime(DateTime.Today);

    private void Refresh()
    {
        rendering = true;
        try
        {
            JournalState state = store.Snapshot();
            RefreshDay(state);
            RefreshTracks(state);
            RefreshTrends(state);
            RefreshSettings(state);
            ShowSync(state.LastSync);
        }
        catch (Exception error) when (JournalStore.IsFailure(error))
        {
            SyncStatus.Text = "Lecture du journal impossible · " + error.Message;
            SyncDot.Fill = (Brush)FindResource("ApricotInk");
        }
        finally
        {
            rendering = false;
        }
    }

    private void ShowSync(DateTimeOffset? lastSync)
    {
        bool trouble = drive.Connected ? drive.Conflicts.Count > 0 : serverError is not null;
        SyncStatus.Text = drive.Connected ? drive.Status : serverError ?? (lastSync is DateTimeOffset last
            ? "Téléphone · " + last.ToLocalTime().ToString("d MMM à HH:mm", French) : "Journal sur ce PC seulement");
        SyncStatus.ToolTip = SyncStatus.Text;
        SyncDot.Fill = (Brush)FindResource(trouble ? "ApricotInk" : drive.Connected || lastSync.HasValue ? "GreenMid" : "Line");
    }

    private void ChangePage(object sender, RoutedEventArgs args)
    {
        if (!IsInitialized) return;
        TodayPage.Visibility = Shown(sender == TodayTab);
        TracksPage.Visibility = Shown(sender == TracksTab);
        TrendsPage.Visibility = Shown(sender == TrendsTab);
        SettingsPage.Visibility = Shown(sender == SettingsTab);
    }

    private static Visibility Shown(bool visible) => visible ? Visibility.Visible : Visibility.Collapsed;

    private System.Windows.Shapes.Path IconMark(string key) => new() { Data = (Geometry)FindResource(key), Style = (Style)FindResource("Icon"),
        VerticalAlignment = VerticalAlignment.Center };

    private Border Chip(string text) => new()
    {
        Background = (Brush)FindResource("Violet"), CornerRadius = new CornerRadius(12), Padding = new Thickness(10, 3, 10, 4),
        Child = new TextBlock { Text = text, FontFamily = (FontFamily)FindResource("TitleFont"), FontSize = 12.5, FontWeight = FontWeights.SemiBold,
            Foreground = (Brush)FindResource("VioletInk") }
    };

    private void AddRow(Panel rows, UIElement row)
    {
        if (rows.Children.Count > 0) rows.Children.Add(new Border { Style = (Style)FindResource("Separator") });
        rows.Children.Add(row);
    }

    private Button Row(string title, string? detail, UIElement? end, RoutedEventHandler open, string? icon = null) =>
        Row(title, detail, end, () => open(this, new RoutedEventArgs()), icon);

    private Button Row(string title, string? detail, UIElement? end, Action open, string? icon = null)
    {
        var line = new DockPanel();
        if (icon is not null)
        {
            System.Windows.Shapes.Path mark = IconMark(icon);
            mark.Stroke = (Brush)FindResource("Accent");
            mark.Margin = new Thickness(0, 0, 12, 0);
            line.Children.Add(mark);
        }
        if (end is FrameworkElement side)
        {
            side.Margin = new Thickness(12, 0, 0, 0);
            side.VerticalAlignment = VerticalAlignment.Center;
            DockPanel.SetDock(side, Dock.Right);
            line.Children.Add(side);
        }
        var text = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
        var name = new TextBlock { Text = title, TextWrapping = TextWrapping.Wrap };
        text.Children.Add(name);
        if (detail is not null)
        {
            name.FontFamily = (FontFamily)FindResource("TitleFont");
            name.FontSize = 16;
            text.Children.Add(new TextBlock { Text = detail, Style = (Style)FindResource("MutedText"), FontSize = 13.5 });
        }
        line.Children.Add(text);
        var button = new Button { Content = line, Style = (Style)FindResource("RowButton") };
        AutomationProperties.SetName(button, detail is not null ? $"{title}, {detail}"
            : end is TextBlock value ? $"{title}, {value.Text}" : title);
        button.Click += (_, _) => open();
        return button;
    }

    private Task SyncDriveAsync(DriveConflict? conflict = null, JsonNode? choice = null) =>
        RunDriveAsync(() => drive.SyncAsync(estimateLifetime.Token, conflict, choice));

    private async Task RunDriveAsync(Func<Task> exchange)
    {
        if (!drive.Connected || drive.Busy) return;
        try { await exchange(); }
        catch (OperationCanceledException) when (estimateLifetime.IsCancellationRequested) { return; }
        QueueRefresh();
        if (drive.ReceivedDraft && !estimateLifetime.IsCancellationRequested) _ = PrepareEstimatesAsync();
    }

    private void OpenDrive(object sender, RoutedEventArgs args)
    {
        var editor = new Editor(this, "Google Drive");
        if (drive.Busy) { editor.Note("Une connexion ou une synchro est en cours."); editor.Show(); return; }
        var status = new TextBlock { Text = drive.Status, Style = (Style)FindResource("MutedText"), Margin = new Thickness(0, 0, 0, 8) };
        editor.Content.Children.Add(status);
        async Task AuthorizeAsync(string? path)
        {
            if (drive.Busy) return;
            status.Text = "Autorisation dans ton navigateur…";
            try
            {
                await drive.ConnectAsync(path, estimateLifetime.Token);
                await SyncDriveAsync();
                status.Text = drive.Status;
            }
            catch (Exception error) when (JournalStore.IsFailure(error) || error is OperationCanceledException or HttpRequestException or
                System.ComponentModel.Win32Exception or InvalidOperationException or JsonException or System.Security.Cryptography.CryptographicException)
            {
                if (!estimateLifetime.IsCancellationRequested) status.Text = error switch
                {
                    OperationCanceledException => "La connexion Google a expiré. Réessaie.",
                    HttpRequestException => "Google est injoignable. Vérifie ta connexion Internet.",
                    InvalidDataException => error.Message,
                    _ => "Connexion Google impossible. Vérifie le fichier et l’autorisation Drive."
                };
            }
        }
        if (!drive.Connected)
        {
            editor.Note("Relie ce PC et ton téléphone au même compte Google. Les saisies sont synchronisées dans l’espace privé de Fil des jours.");
            editor.Note("La première connexion sur Windows demande le fichier JSON du client de bureau Fil des jours, téléchargé depuis Google Cloud.");
            editor.AddAction("Ouvrir les clients dans Google Cloud", () =>
                System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo("https://console.cloud.google.com/auth/clients") { UseShellExecute = true }));
            editor.AddAction("Importer le fichier Google (.json)", async () =>
            {
                string downloads = IOPath.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads");
                var open = new Microsoft.Win32.OpenFileDialog { Filter = "Configuration Google (*.json)|*.json", Title = "Importer le fichier JSON du client Google Windows",
                    InitialDirectory = Directory.Exists(downloads) ? downloads : "" };
                if (open.ShowDialog(this) == true) await AuthorizeAsync(open.FileName);
            });
        }
        else
        {
            editor.AddAction("Synchroniser maintenant", async () => { await SyncDriveAsync(); status.Text = drive.Status; });
            foreach (DriveConflict conflict in drive.Conflicts)
                editor.AddAction("Vérifier · " + DriveEntryLabel(conflict.Key), () => ResolveDriveConflict(conflict));
            editor.AddAction("Reconnecter le compte", async () => await AuthorizeAsync(null));
            editor.AddAction("Remplacer le contenu de Drive par ce PC", async () =>
            {
                if (MessageBox.Show(Window.GetWindow(editor.Content)!, "Drive repartira du journal de ce PC. Tes autres appareils garderont le leur : les différences y seront à vérifier. Continuer ?",
                    "Fil des jours", MessageBoxButton.YesNo, MessageBoxImage.Warning) != MessageBoxResult.Yes) return;
                await RunDriveAsync(() => drive.ReplaceAsync(estimateLifetime.Token));
                status.Text = drive.Status;
            });
            editor.AddAction("Déconnecter ce PC", () =>
            {
                drive.Disconnect();
                status.Text = drive.Status;
                QueueRefresh();
            });
        }
        editor.Show();
    }

    private static string DriveEntryLabel(string key)
    {
        string[] parts = key.Split('/');
        return parts[0] switch
        {
            "goal" => "Objectif de pause", "pause" => "Pause du " + parts[1], "weight" => "Poids du " + parts[1],
            "meal" => "Aliment du " + parts[1], "track" => "Suivi", "target" => "Objectif", "report" => "Bilan", _ => "Repas à estimer"
        };
    }

    private void ResolveDriveConflict(DriveConflict conflict)
    {
        var editor = new Editor(this, DriveEntryLabel(conflict.Key));
        foreach (var (label, value) in new[] { ("Ce PC", conflict.Local), ("Drive", conflict.Cloud) }
            .Concat(conflict.Alternatives.Select(value => ("Autre version", value))))
        {
            string text = DriveEntryValue(conflict.Key, value);
            if (text.Length > 52) text = text[..49] + "…";
            editor.AddAction($"Garder {label} · {text}", async () => { await SyncDriveAsync(conflict, value); editor.Note(drive.Status); });
        }
        editor.Show();
    }

    private static string DriveEntryValue(string key, JsonNode? value)
    {
        if (value is null) return "entrée vide";
        if (value is JsonObject row)
        {
            if (key == "goal") return $"{row["duration"]} jours dès le {row["start"]}";
            if (key.StartsWith("meal/", StringComparison.Ordinal)) return $"{row["name"]} · {Kcal(row["kcal"]!.GetValue<double>())}";
            if (key.StartsWith("track/", StringComparison.Ordinal)) return $"{row["name"]} · {row["duration"]} j · {row["restDays"] ?? JsonValue.Create(0)} repos";
            if (key.StartsWith("report/", StringComparison.Ordinal)) return $"{row["title"]} · {row["text"]}";
            return row["description"]?.ToString() ?? "";
        }
        if (key == "target/calories") return Kcal(value.GetValue<double>());
        if (key == "target/weight" || key.StartsWith("weight/", StringComparison.Ordinal)) return Kg(value.GetValue<double>());
        string text = value.ToString();
        return text switch { "true" => "Fait", "false" => "Non fait", "tenu" => "Tenu", "rest" => "Repos",
            JournalState.NotKeptStatus => "Non tenu", _ => text };
    }

    private async Task PrepareEstimatesAsync()
    {
        if (estimating) { estimateAgain = true; return; }
        estimating = true;
        estimateError = null;
        try
        {
            do
            {
                estimateAgain = false;
                foreach (MealDraft draft in store.Snapshot().MealDrafts())
                {
                    estimateLifetime.Token.ThrowIfCancellationRequested();
                    if (MealEstimate.Read(draft) is not null) continue;
                    try
                    {
                        MealEstimate? estimate = await MealEstimate.MakeAsync(draft, estimateLifetime.Token);
                        if (estimate is null || !store.Snapshot().MealDrafts().Contains(draft) || MealEstimate.Read(draft) is not null) continue;
                        estimate.Remember(draft);
                        EstimatesChanged?.Invoke();
                    }
                    catch (Exception error) when (!estimateLifetime.IsCancellationRequested && error is
                        HttpRequestException or OperationCanceledException or JsonException or InvalidDataException or
                        InvalidOperationException or ArgumentException or KeyNotFoundException)
                    {
                        estimateError = "Certaines estimations sont indisponibles. Pause réessaiera à la prochaine synchro.";
                    }
                }
            } while (estimateAgain);
        }
        catch (OperationCanceledException) when (estimateLifetime.IsCancellationRequested) { }
        catch (Exception error) when (JournalStore.IsFailure(error))
        {
            estimateError = "Certaines estimations sont indisponibles. Pause réessaiera à la prochaine synchro.";
        }
        finally
        {
            estimating = false;
            if (!estimateLifetime.IsCancellationRequested) QueueRefresh();
            EstimatesChanged?.Invoke();
        }
    }

    private void ShowMealDrafts(MealDraft? first)
    {
        MealDraft[] drafts = store.Snapshot().MealDrafts();
        if (drafts.Length == 0) return;
        var dialog = new Editor(this, "Repas à estimer", 615);
        var list = new ListBox { MaxHeight = 160,
            ItemsSource = drafts.Select(draft => $"{draft.Date.ToString("d MMM yyyy", French)} · {draft.Description}") };
        dialog.Content.Children.Add(list);
        var details = new TextBlock { TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 10, 0, 6),
            Style = (Style)FindResource("MutedText") };
        dialog.Content.Children.Add(details);
        var form = new StackPanel();
        dialog.Content.Children.Add(new ScrollViewer { MaxHeight = 305, Content = form,
            VerticalScrollBarVisibility = ScrollBarVisibility.Auto });
        var fields = new List<(TextBox Name, TextBox Grams, TextBox Energy, ComboBox? Choice, EstimatedFood Food)>();
        MealDraft? shown = null;
        MealEstimate? proposal = null;
        bool edited = false, updating = false, asking = false;
        using var estimation = CancellationTokenSource.CreateLinkedTokenSource(estimateLifetime.Token);

        void ShowTotal()
        {
            if (proposal is null || fields.Count == 0) return;
            double total = 0;
            foreach (var (_, grams, energy, _, _) in fields)
            {
                if (!TryNumber(grams.Text, out double amount) || amount is not (>= .0001 and <= 10000)
                    || !TryNumber(energy.Text, out double per100) || per100 is not (>= 0 and <= 1000))
                {
                    details.Text = proposal.Source == "Saisie" && estimateError is not null ? estimateError
                        : proposal.Source + " · Vérifie les quantités et choisis les références. ChatGPT ou Claude peuvent chercher les calories manquantes.";
                    return;
                }
                total += amount * per100 / 100;
            }
            details.Text = $"{proposal.Source} · Total estimé {Format(total, "0.#")} kcal";
        }

        static string? SourceOf(double per100, ComboBox? choice, EstimatedFood food) =>
            choice?.SelectedItem is FoodReference.Item item && Math.Abs(per100 - item.Per100) < .0001 ? "Ciqual"
            : food.Per100 is double proposed && Math.Abs(per100 - proposed) < .0001 ? food.Source : "Saisie";

        void ShowEstimate(bool reload = false)
        {
            if (list.SelectedIndex < 0) return;
            MealDraft draft = drafts[list.SelectedIndex];
            if (shown != draft) { shown = draft; proposal = null; edited = false; }
            MealEstimate next = MealEstimate.Read(draft) ?? MealEstimate.Manual(draft);
            if (!reload && proposal is not null && (edited || (proposal.Source == next.Source && proposal.Foods.SequenceEqual(next.Foods)))) return;
            proposal = next;
            fields.Clear();
            form.Children.Clear();
            updating = true;
            foreach (EstimatedFood food in next.Foods)
            {
                var name = new TextBox { Text = food.Name, MaxLength = 90, Margin = new Thickness(0, 4, 0, 5) };
                AutomationProperties.SetName(name, "Nom de l’aliment");
                form.Children.Add(name);
                var quantities = new Grid { Margin = new Thickness(0, 0, 0, 6) };
                quantities.ColumnDefinitions.Add(new ColumnDefinition());
                quantities.ColumnDefinitions.Add(new ColumnDefinition());
                bool barcode = next.Source == "Open Food Facts";
                TextBox grams = AddEstimateField(quantities, 0, barcode ? "Quantité (g ou ml)"
                    : food.Usual ? "Quantité estimée (g)" : "Quantité (g)", food.Grams);
                TextBox energy = AddEstimateField(quantities, 1, barcode ? "kcal pour 100 g ou 100 ml" : "kcal pour 100 g", food.Per100);
                foreach (TextBox field in new[] { name, grams, energy })
                    field.TextChanged += (_, _) => { if (!updating) edited = true; ShowTotal(); };
                FoodReference.Result match = FoodReference.Match(food.Name);
                FoodReference.Item[] options = food.Per100.HasValue && (food.Source != "Ciqual" || match.Reference is not null) ? [] : match.Options;
                ComboBox? choice = null;
                if (options.Length > 0)
                {
                    choice = new ComboBox { ItemsSource = options, DisplayMemberPath = nameof(FoodReference.Item.Name), Margin = new Thickness(0, 0, 0, 6) };
                    ComboBox references = choice;
                    references.SelectedItem = options.FirstOrDefault(item => item.Per100 == food.Per100);
                    references.SelectionChanged += (_, _) =>
                    {
                        if (references.SelectedItem is not FoodReference.Item item) return;
                        name.Text = item.Name.Length <= 90 ? item.Name : item.Name[..90];
                        energy.Text = Format(item.Per100, "0.####");
                    };
                    form.Children.Add(new Label { Content = "Référence Ciqual", Target = choice, FontSize = 12, Padding = new Thickness(0, 0, 0, 2) });
                    form.Children.Add(choice);
                }
                form.Children.Add(quantities);
                fields.Add((name, grams, energy, choice, food));
            }
            updating = false;
            edited = false;
            ShowTotal();
        }

        var sources = new WrapPanel { Margin = new Thickness(0, 10, 0, 2) };
        dialog.Content.Children.Add(sources);
        Button SourceButton(string label)
        {
            var button = new Button { Content = label, Margin = new Thickness(0, 0, 6, 4) };
            sources.Children.Add(button);
            return button;
        }
        Button useChatGpt = SourceButton("Compléter avec ChatGPT");
        SourceButton("Claude via MCP").Click += (_, _) =>
        {
            var help = new Editor(this, "Estimer avec Claude");
            help.Note("Dans Claude Desktop ou Claude Code avec le connecteur Fil des jours, demande : estime mon repas en attente et dépose une proposition à vérifier. Elle sera disponible ici.");
            help.Show();
            ShowEstimate();
        };
        SourceButton("Recharger la proposition").Click += (_, _) => ShowEstimate(reload: true);
        useChatGpt.Click += async (_, _) =>
        {
            if (asking || shown is not MealDraft draft) return;
            EstimatedFood[] foods = fields.Select(field =>
            {
                double? grams = TryNumber(field.Grams.Text, out double amount) ? amount : null;
                double? energy = TryNumber(field.Energy.Text, out double per100) ? per100 : null;
                return new EstimatedFood(field.Name.Text.Trim(), grams, energy,
                    energy is double known ? SourceOf(known, field.Choice, field.Food) : null, field.Food.Usual && grams == field.Food.Grams);
            }).ToArray();
            if (!foods.Any(food => !food.Per100.HasValue)) { details.Text = "Les calories sont déjà renseignées."; return; }
            if (foods.Any(food => !food.Per100.HasValue && food.Name.Length == 0)) { details.Text = "Indique le nom du produit pour que ChatGPT puisse l’identifier."; return; }
            asking = true;
            useChatGpt.IsEnabled = false;
            try
            {
                list.IsEnabled = false;
                form.IsEnabled = false;
                details.Text = chatgpt.Connected ? "Estimation ChatGPT en cours…" : "Autorise ton abonnement ChatGPT dans le navigateur…";
                if (!chatgpt.Connected) await chatgpt.SignInAsync(estimation.Token);
                details.Text = "Estimation ChatGPT en cours…";
                EstimatedFood[] completed = await chatgpt.CompleteAsync(foods, estimation.Token);
                if (completed.SequenceEqual(foods)) { details.Text = "ChatGPT n’a pas proposé de valeur pour ces aliments."; return; }
                if (store.Snapshot().MealDrafts().Contains(draft)) new MealEstimate("ChatGPT", completed).Remember(draft);
                if (shown == draft) ShowEstimate(reload: true);
            }
            catch (Exception error) when (JournalStore.IsFailure(error) || error is HttpRequestException or JsonException or InvalidOperationException or
                OperationCanceledException or System.ComponentModel.Win32Exception or System.Security.Cryptography.CryptographicException or FormatException)
            {
                if (!estimation.IsCancellationRequested) details.Text = error is IOException or InvalidDataException ? error.Message : "ChatGPT n’a pas terminé cette estimation. Les valeurs sont conservées.";
            }
            finally { asking = false; useChatGpt.IsEnabled = true; list.IsEnabled = true; form.IsEnabled = true; }
        };

        void RefreshProposal() => ShowEstimate();
        list.SelectionChanged += (_, _) => ShowEstimate();
        list.SelectedIndex = Math.Max(0, Array.FindIndex(drafts, draft => draft.Id == first?.Id));
        EstimatesChanged += RefreshProposal;
        _ = PrepareEstimatesAsync();
        try
        {
            dialog.Show(() =>
            {
                if (shown is null || proposal is null || fields.Count == 0) return "Aucune estimation à ajouter.";
                var completed = new List<EstimatedFood>();
                if (asking) return "Attends la fin de l’estimation ChatGPT.";
                foreach (var (name, grams, energy, choice, proposed) in fields)
                {
                    string food = name.Text.Trim();
                    if (food.Length == 0) { name.Focus(); return "Indique le nom de l’aliment."; }
                    if (!TryNumber(grams.Text, out double amount) || amount is not (>= .0001 and <= 10000))
                    { grams.Focus(); return $"Indique la quantité pour {food}."; }
                    bool unknown = string.IsNullOrWhiteSpace(energy.Text);
                    double per100 = MealEstimate.UnknownPer100;
                    if (!unknown && (!TryNumber(energy.Text, out per100) || per100 is not (>= 0 and <= 1000)))
                    { energy.Focus(); return $"Vérifie les calories pour 100 g de {food}."; }
                    completed.Add(new(food, amount, per100, unknown ? "Moyenne" : SourceOf(per100, choice, proposed)));
                }
                try { store.SaveEstimate(shown, completed, proposal.Source); }
                catch (Exception error) when (JournalStore.IsFailure(error) || error is InvalidOperationException) { return error.Message; }
                Refresh();
                return null;
            }, saveLabel: "Ajouter au journal");
        }
        finally { estimation.Cancel(); EstimatesChanged -= RefreshProposal; }
    }

    private static TextBox AddEstimateField(Grid grid, int column, string title, double? value)
    {
        var input = new TextBox { Text = value is double number ? Format(number, "0.####") : "", MaxLength = 12 };
        var field = new StackPanel { Margin = new Thickness(column == 0 ? 0 : 6, 0, 0, 0) };
        field.Children.Add(new Label { Content = title, Target = input, FontSize = 12, Padding = new Thickness(0, 0, 0, 2) });
        field.Children.Add(input);
        Grid.SetColumn(field, column);
        grid.Children.Add(field);
        return input;
    }

    private void AddMeal(object sender, RoutedEventArgs args) => ShowMealEditor(null);

    private void ShowMealEditor(Meal? meal)
    {
        JournalState before = store.Snapshot();
        string key = meal?.Key ?? $"meal/{JournalState.Iso(selected)}/{Guid.NewGuid():D}";
        var name = new TextBox { Text = meal?.Name ?? "", MaxLength = meal is null ? 700 : 90 };
        var modes = new ComboBox
        {
            ItemsSource = new[] { "Total de la portion", "Calculer avec la quantité" },
            SelectedIndex = meal?.Amount is null ? 0 : 1
        };
        var calories = new TextBox { Text = meal is null ? "" : Format(meal.Kcal), MaxLength = 12 };
        var amount = new TextBox { Text = meal?.Amount is double portion ? Format(portion) : "", MaxLength = 12 };
        var per100 = new TextBox { Text = meal?.Per100 is double energy ? Format(energy) : "", MaxLength = 12 };
        var dialog = new Editor(this, meal is null ? "Ajouter un aliment" : "Modifier l’aliment");
        dialog.Field(meal is null ? "Ce que tu as mangé" : "Nom", name);
        if (meal is null)
        {
            dialog.Note("Exemple : un bol de soupe et deux tartines. Laisse les calories vides pour une estimation.");
            name.Loaded += (_, _) => name.Focus();
        }
        dialog.Field("Saisie", modes);
        StackPanel totalField = dialog.Field("Calories de la portion (kcal)", calories);
        StackPanel energyField = dialog.Field("Calories pour 100 g ou 100 ml", per100);
        StackPanel quantityField = dialog.Field("Quantité consommée (g ou ml)", amount);
        void ChangeMode()
        {
            totalField.Visibility = modes.SelectedIndex == 0 ? Visibility.Visible : Visibility.Collapsed;
            energyField.Visibility = quantityField.Visibility = modes.SelectedIndex == 1 ? Visibility.Visible : Visibility.Collapsed;
        }
        modes.SelectionChanged += (_, _) => ChangeMode();
        ChangeMode();
        dialog.Show(() =>
        {
            if (string.IsNullOrWhiteSpace(name.Text)) return "Saisis le nom de l’aliment.";
            if (meal is null && modes.SelectedIndex == 0 && string.IsNullOrWhiteSpace(calories.Text))
                return AddEstimated(before, MealEstimate.ParseLocal(name.Text));
            if (name.Text.Trim().Length > 90) return "Raccourcis le nom de l’aliment.";
            var value = new JsonObject { ["name"] = name.Text.Trim(), ["source"] = meal?.Source ?? "Manuel" };
            if (modes.SelectedIndex == 1)
            {
                if (!TryNumber(per100.Text, out double density) || density < 0 || density > 1000
                    || !TryNumber(amount.Text, out double quantity) || quantity <= 0 || quantity > 10000)
                    return "Vérifie les calories pour 100 et la quantité consommée.";
                value["kcal"] = density * quantity / 100;
                value["kcalPer100"] = density;
                value["amount"] = quantity;
            }
            else
            {
                if (!TryNumber(calories.Text, out double kcal) || kcal < 0 || kcal > 100000)
                    return "Saisis les calories de la portion.";
                value["kcal"] = kcal;
            }
            return SaveEdits(before, (key, value));
        }, meal is null ? null : () =>
            MessageBox.Show(this, "Supprimer cet aliment ?", "Fil des jours", MessageBoxButton.YesNo, MessageBoxImage.Question) == MessageBoxResult.Yes
                ? SaveEdits(before, (key, null)) : "");
    }

    private string? SaveEdits(JournalState before, params (string Key, JsonNode? Value)[] edits)
    {
        try
        {
            var changed = edits.Select(edit =>
                new Change(edit.Key, before.Entries.GetValueOrDefault(edit.Key), JournalStore.Normalize(edit.Key, edit.Value)))
                .Where(edit => !JsonNode.DeepEquals(edit.Before, edit.After)).ToArray();
            if (changed.Length == 0) return null;
            if (!store.TryUpdate(changed))
            {
                if (MessageBox.Show(this, "Cette entrée a changé pendant la synchro. Appliquer ta modification du PC ?",
                    "Fil des jours", MessageBoxButton.YesNo, MessageBoxImage.Question) != MessageBoxResult.Yes) return "";
                store.Update(changed.Select(edit => (edit.Key, edit.After)).ToArray());
            }
            Refresh();
            return null;
        }
        catch (Exception error) when (JournalStore.IsFailure(error) || error is InvalidOperationException or ArgumentException)
        {
            return "Impossible d’enregistrer. " + error.Message;
        }
    }

    private static string Status(string? status, Goal? goal, DateOnly date) => status switch
    {
        "tenu" => "Tenu",
        JournalState.NotKeptStatus => "Non tenu",
        _ => goal is not null && date >= goal.Start && date <= goal.End ? "Sans réponse" : "—"
    };

    private static string Format(double value, string format = "0.##########") => value.ToString(format, French);
    private static string Kg(double value) => Format(value, "0.##") + " kg";
    private static string Kcal(double value) => Format(value, "#,##0.#") + " kcal";

    private static bool TryNumber(string input, out double number) =>
        double.TryParse(input.Trim().Replace(',', '.'), NumberStyles.AllowDecimalPoint, CultureInfo.InvariantCulture, out number)
        && double.IsFinite(number);

}
