using System.Globalization;
using System.IO;
using System.Net.Http;
using System.Net.Sockets;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Media.Imaging;
using System.Windows.Shapes;
using System.Windows.Threading;
using QRCoder;
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
    private SyncServer? server;
    private string? serverError;
    private DateOnly selected = DateOnly.FromDateTime(DateTime.Today);
    private int range = 30;
    private int chartSection;
    private bool rendering;
    private DayRow[] chartRows = [];
    private DayRow[] historyRows = [];
    private Goal? chartGoal;
    private double? chartCalorieTarget;
    private DateOnly? streakStart;
    private int streakTier;
    private TrackerWindow? trackerWindow;
    private readonly CancellationTokenSource estimateLifetime = new();
    private bool estimating, estimateAgain;
    private string? estimateError;
    private event Action? EstimatesChanged;

    internal MainWindow(JournalStore store)
    {
        this.store = store;
        drive = new DriveSync(store);
        InitializeComponent();
        MarkCurrentChart();
        DayPicker.DisplayDateEnd = DateTime.Today;
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
            trackerWindow?.Refresh(state);
            DateOnly first = Today.AddDays(1 - range);
            JournalReport report = JournalReport.Build(state, first, Today, Today);
            historyRows = report.Rows;
            chartGoal = report.Goal;
            chartCalorieTarget = state.CaloriesTarget;
            MealDraft[] pending = state.MealDrafts();
            PendingMealsButton.Visibility = pending.Length > 0 ? Visibility.Visible : Visibility.Collapsed;
            int ready = pending.Count(draft => MealEstimate.Read(draft)?.Foods.Length > 0);
            PendingMealsButton.Content = ready == 0 ? "Repas à estimer" : $"Propositions · {ready}";
            RangeText.Text = $"Du {first.ToString("d MMMM", French)} au {Today.ToString("d MMMM yyyy", French)}";
            foreach (Button button in new[] { Days7, Days30, Days90 })
            {
                bool active = button.Tag.ToString() == range.ToString(CultureInfo.InvariantCulture);
                button.Style = (Style)FindResource(active ? "Primary" : "QuietButton");
            }

            Goal? activeGoal = report.Goal;
            var (due, kept, notKept) = activeGoal is null ? (0, 0, 0) : state.PauseProgress(activeGoal, Today);
            PauseValue.Text = activeGoal is null ? "À définir" : $"{kept}/{activeGoal.Duration} jour{(activeGoal.Duration == 1 ? "" : "s")} tenus";
            PauseDetail.Text = report.Goal is null ? "Choisis une date et une durée."
                : $"{due} écoulé{(due > 1 ? "s" : "")} · {notKept} non tenu{(notKept > 1 ? "s" : "")} · {due - kept - notKept} sans réponse";
            PauseTrack.Visibility = report.Goal is null ? Visibility.Collapsed : Visibility.Visible;
            PauseDone.Width = new GridLength(kept, GridUnitType.Star);
            PauseRest.Width = new GridLength((activeGoal?.Duration ?? 1) - kept, GridUnitType.Star);
            PauseFill.Visibility = kept > 0 ? Visibility.Visible : Visibility.Collapsed;
            RefreshStreak(state.Streak(Today), report.Goal);
            PauseDates.Visibility = report.Goal is null ? Visibility.Collapsed : Visibility.Visible;
            if (report.Goal is Goal goal)
            {
                ExtendPause.Visibility = goal.Duration < Goal.MaxDuration ? Visibility.Visible : Visibility.Collapsed;
                string end = goal.End.ToString("d MMM", French);
                PauseEnd.Text = goal.End < Today ? $"Terminée le {end}" : $"Fin prévue le {end}";
            }

            chartRows = report.Rows.Where(row => row.Weight.HasValue).ToArray();
            WeightValue.Text = chartRows.Length == 0 ? "—" : Kg(chartRows[^1].Weight!.Value);
            WeightDetail.Text = chartRows.Length switch
            {
                0 => "Aucune pesée sur cette période",
                1 => "Mesuré le " + chartRows[^1].Date.ToString("d MMMM", French),
                _ => Format(chartRows[^1].Weight!.Value - chartRows[0].Weight!.Value, "+0.##;-0.##;0") + " kg sur la période"
            };
            WeightTargetNote.Visibility = state.WeightTarget.HasValue ? Visibility.Visible : Visibility.Collapsed;
            WeightTargetNote.Text = state.WeightTarget is double target ? "Objectif · " + Kg(target) : "";

            DayRow[] logged = report.Rows.Where(row => row.Kcal.HasValue).ToArray();
            CaloriesValue.Text = logged.Length == 0 ? "—" : Kcal(logged.Average(row => row.Kcal!.Value));
            CaloriesDetail.Text = logged.Length == 0 ? "Aucune saisie sur cette période"
                : $"Moyenne sur {logged.Length} jour{(logged.Length > 1 ? "s" : "")} avec saisie";
            CaloriesTargetNote.Visibility = state.CaloriesTarget.HasValue ? Visibility.Visible : Visibility.Collapsed;
            CaloriesTargetNote.Text = state.CaloriesTarget is double daily ? "Objectif quotidien · " + Kcal(daily) : "";
            DrawChart();

            DayItem[] rows = report.Rows.Reverse().Select(row => new DayItem(row.Date,
                row.Date.ToString("ddd d MMM", French), Status(row.Status, report.Goal, row.Date),
                row.Weight is double kg ? Kg(kg) : "—", row.Kcal is double kcal ? Kcal(kcal) : "—")).ToArray();
            DaysGrid.ItemsSource = rows;
            DaysGrid.SelectedItem = rows.FirstOrDefault(row => row.Date == selected);
            DayPicker.SelectedDate = selected.ToDateTime(TimeOnly.MinValue);
            RefreshDay(state);
            SyncStatus.Text = drive.Connected ? drive.Status : serverError ?? (state.LastSync is DateTimeOffset lastSync
                ? $"Dernière synchro Android · {lastSync.ToLocalTime().ToString("d MMM à HH:mm", French)} · Journal local sur ce PC"
                : "Aucune synchro Android · Ouvre Associer Android pour relier ton téléphone");
            SyncDot.Fill = serverError is not null ? (Brush)FindResource("ApricotInk")
                : (Brush)FindResource(state.LastSync.HasValue ? "Accent" : "Line");
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

    private async Task SyncDriveAsync(DriveConflict? conflict = null, JsonNode? choice = null)
    {
        if (!drive.Connected || drive.Busy) return;
        try { await drive.SyncAsync(estimateLifetime.Token, conflict, choice); }
        catch (OperationCanceledException) when (estimateLifetime.IsCancellationRequested) { return; }
        SyncStatus.Text = drive.Status;
        SyncDot.Fill = (Brush)FindResource(drive.Conflicts.Count == 0 ? "BlueInk" : "ApricotInk");
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

    private void RefreshStreak(int days, Goal? goal)
    {
        int tier = days switch { >= 30 => 3, >= 14 => 2, >= 7 => 1, _ => 0 };
        bool reached = goal is not null && goal.Start == streakStart && tier > streakTier;
        streakStart = goal?.Start;
        if (tier != streakTier)
        {
            StreakScale.BeginAnimation(ScaleTransform.ScaleXProperty, null);
            StreakScale.BeginAnimation(ScaleTransform.ScaleYProperty, null);
        }
        streakTier = tier;
        StreakBadge.Visibility = goal is null ? Visibility.Collapsed : Visibility.Visible;
        if (goal is null) return;

        string label = $"Série · {days} jour{(days == 1 ? "" : "s")}";
        StreakLabel.Text = tier == 0 ? label : new string('✦', tier) + "  " + label;
        System.Windows.Automation.AutomationProperties.SetName(StreakLabel, label);
        StreakLabel.Foreground = (Brush)FindResource("Ink");
        StreakBadge.Background = tier switch
        {
            3 => new LinearGradientBrush(Color.FromRgb(251, 227, 160), Color.FromRgb(255, 248, 234), -30),
            2 => new LinearGradientBrush(Color.FromRgb(227, 221, 245), Color.FromRgb(242, 237, 249), -30),
            1 => new LinearGradientBrush(Color.FromRgb(213, 235, 198), Color.FromRgb(227, 241, 216), -30),
            _ => (Brush)FindResource("Surface")
        };
        if (!reached || !IsLoaded || !SystemParameters.ClientAreaAnimation) return;

        var pulse = new DoubleAnimationUsingKeyFrames { FillBehavior = FillBehavior.Stop };
        pulse.KeyFrames.Add(new EasingDoubleKeyFrame(1, KeyTime.FromTimeSpan(TimeSpan.Zero)));
        pulse.KeyFrames.Add(new EasingDoubleKeyFrame(1.12, KeyTime.FromTimeSpan(TimeSpan.FromMilliseconds(200))));
        pulse.KeyFrames.Add(new EasingDoubleKeyFrame(1, KeyTime.FromTimeSpan(TimeSpan.FromMilliseconds(620))));
        StreakScale.BeginAnimation(ScaleTransform.ScaleXProperty, pulse);
        StreakScale.BeginAnimation(ScaleTransform.ScaleYProperty, pulse);
    }

    private void RefreshDay(JournalState state)
    {
        string date = JournalState.Iso(selected);
        string label = selected.ToString("dddd d MMMM", French);
        DayTitle.Text = char.ToUpper(label[0], French) + label[1..];
        DayToday.Visibility = selected == Today ? Visibility.Collapsed : Visibility.Visible;
        string? status = state.Entries.GetValueOrDefault("pause/" + date)?.GetValue<string>();
        double? weight = state.Entries.GetValueOrDefault("weight/" + date)?.GetValue<double>();
        DayPause.Text = "Pause · " + Status(status, state.Goal, selected);
        DayWeight.Text = "Poids · " + (weight is double kg ? Kg(kg) : "Aucune mesure");
        Meal[] meals = state.Meals(selected);
        string? previous = (MealsList.SelectedItem as MealItem)?.Meal.Key;
        MealItem[] list = meals.Select(meal => new MealItem(meal, meal.Name, Kcal(meal.Kcal))).ToArray();
        MealsList.ItemsSource = list;
        MealsList.SelectedItem = list.FirstOrDefault(item => item.Meal.Key == previous);
        DayCalories.Text = meals.Length == 0 ? ""
            : state.CaloriesTarget is double target
                ? Format(meals.Sum(meal => meal.Kcal), "#,##0.#") + " / " + Kcal(target)
                : Kcal(meals.Sum(meal => meal.Kcal));
        NoMeals.Text = meals.Length == 0 ? "Aucun aliment saisi." : "";
        EditMealButton.IsEnabled = MealsList.SelectedItem is not null;
    }

    private void SelectRange(object sender, RoutedEventArgs args)
    {
        range = int.Parse(((Button)sender).Tag.ToString()!, CultureInfo.InvariantCulture);
        Refresh();
    }

    private void ChooseDay(object sender, SelectionChangedEventArgs args)
    {
        if (rendering || DaysGrid.SelectedItem is not DayItem item) return;
        selected = item.Date;
        rendering = true;
        DayPicker.SelectedDate = selected.ToDateTime(TimeOnly.MinValue);
        rendering = false;
        RefreshDay(store.Snapshot());
    }

    private void ChooseDate(object sender, SelectionChangedEventArgs args)
    {
        if (rendering || DayPicker.SelectedDate is not DateTime date) return;
        ShowDate(DateOnly.FromDateTime(date));
    }

    private void GoToToday(object sender, RoutedEventArgs args) => ShowDate(Today);

    private void ShowDate(DateOnly date)
    {
        selected = date;
        Refresh();
        if (DaysGrid.SelectedItem is not null) DaysGrid.ScrollIntoView(DaysGrid.SelectedItem);
    }

    private void MealSelected(object sender, SelectionChangedEventArgs args) =>
        EditMealButton.IsEnabled = MealsList.SelectedItem is not null;

    private void ChartSizeChanged(object sender, SizeChangedEventArgs args) => DrawChart();
    private void ChangeChart(object sender, RoutedEventArgs args)
    {
        if (sender is not Button { Tag: string selectedSection }
            || !int.TryParse(selectedSection, CultureInfo.InvariantCulture, out int index) || index == chartSection) return;
        chartSection = index;
        MarkCurrentChart();
        DrawChart();
    }

    private void MarkCurrentChart()
    {
        (Button Button, string Color)[] charts = [(WeightGraph, "Blue"), (CalorieGraph, "Apricot"), (PauseGraph, "Gold")];
        for (int i = 0; i < charts.Length; i++)
        {
            var (button, color) = charts[i];
            bool active = i == chartSection;
            button.Background = (Brush)FindResource(active ? color : "Surface");
            System.Windows.Automation.AutomationProperties.SetItemStatus(button, active ? "Affiché" : "");
        }
    }

    private void DrawChart()
    {
        if (WeightChart is null || NoChart is null || ChartTitle is null) return;
        WeightChart.Children.Clear();
        if (chartSection == 1) { DrawDailyCalories(); return; }
        if (chartSection == 2) { DrawPauseDays(); return; }
        ChartTitle.Text = "Évolution du poids";
        bool available = chartRows.Length >= 2;
        NoChart.Text = available ? "" : "Deux pesées suffisent pour afficher la courbe.";
        if (!available || WeightChart.ActualWidth < 30) return;

        double width = WeightChart.ActualWidth, height = WeightChart.ActualHeight;
        const double left = 48, top = 9;
        double right = width - 6, bottom = height - 18;
        if (right <= left || bottom <= top) return;
        double low = chartRows.Min(row => row.Weight!.Value);
        double high = chartRows.Max(row => row.Weight!.Value);
        double padding = Math.Max(.25, (high - low) / 5);
        low -= padding;
        high += padding;
        int span = Math.Max(1, chartRows[^1].Date.DayNumber - chartRows[0].Date.DayNumber);
        Brush accent = (Brush)FindResource("BlueInk");
        for (int i = 0; i <= 2; i++)
        {
            double y = top + (bottom - top) * i / 2;
            WeightChart.Children.Add(new Line { X1 = left, X2 = right, Y1 = y, Y2 = y,
                Stroke = (Brush)FindResource("Line"), StrokeDashArray = new DoubleCollection([3, 4]) });
            AddChartLabel(Kg(high - (high - low) * i / 2), 0, y - 7);
        }
        var curve = new Polyline { Stroke = accent, StrokeThickness = 2.5, StrokeLineJoin = PenLineJoin.Round };
        foreach (DayRow row in chartRows)
        {
            double x = left + (right - left) * (row.Date.DayNumber - chartRows[0].Date.DayNumber) / span;
            double y = bottom - (bottom - top) * (row.Weight!.Value - low) / (high - low);
            curve.Points.Add(new Point(x, y));
        }
        var areaPoints = new PointCollection(curve.Points);
        areaPoints.Insert(0, new Point(left, bottom));
        areaPoints.Add(new Point(right, bottom));
        var shade = new LinearGradientBrush(Color.FromArgb(179, 156, 195, 220), Color.FromArgb(16, 156, 195, 220), 90);
        WeightChart.Children.Add(new Polygon { Points = areaPoints, Fill = shade });
        WeightChart.Children.Add(curve);
        for (int i = 0; i < curve.Points.Count; i++)
        {
            Point point = curve.Points[i];
            int size = i == curve.Points.Count - 1 ? 8 : 5;
            var dot = new Ellipse { Width = size, Height = size, Fill = (Brush)FindResource("Surface"), Stroke = accent, StrokeThickness = 1.5 };
            Canvas.SetLeft(dot, point.X - size / 2.0);
            Canvas.SetTop(dot, point.Y - size / 2.0);
            WeightChart.Children.Add(dot);
        }
        AddChartLabel(chartRows[0].Date.ToString("d MMM", French), left, bottom + 3);
        AddChartLabel(chartRows[^1].Date.ToString("d MMM", French), right, bottom + 3, alignRight: true);
    }

    private void DrawDailyCalories()
    {
        ChartTitle.Text = "Calories par jour";
        NoChart.Text = historyRows.Any(day => day.Kcal.HasValue) ? "" : "Les barres apparaissent dès la première saisie.";
        double right = WeightChart.ActualWidth - 6, bottom = WeightChart.ActualHeight - 17;
        const double left = 43, top = 9;
        if (right <= left || bottom <= top || NoChart.Text.Length > 0) return;
        double maximum = Math.Max(chartCalorieTarget ?? 0, historyRows.Max(day => day.Kcal ?? 0));
        double scale = Math.Max(100, Math.Ceiling(maximum * 1.1 / 100) * 100);
        for (int i = 0; i <= 2; i++)
        {
            double y = top + (bottom - top) * i / 2;
            WeightChart.Children.Add(new Line { X1 = left, X2 = right, Y1 = y, Y2 = y,
                Stroke = (Brush)FindResource("Line"), StrokeDashArray = new DoubleCollection([3, 4]) });
            AddChartLabel(Format(scale * (2 - i) / 2, "#,##0"), 0, y - 7);
        }
        double slot = (right - left) / historyRows.Length;
        for (int i = 0; i < historyRows.Length; i++)
        {
            DayRow day = historyRows[i];
            if (day.Kcal is not double kcal) continue;
            double height = Math.Max(2, kcal / scale * (bottom - top));
            var bar = new Rectangle { RadiusX = 2, RadiusY = 2, Width = slot * .65, Height = height,
                Fill = (Brush)FindResource(i == historyRows.Length - 1 ? "Brand" : "Apricot"),
                ToolTip = $"{day.Date.ToString("d MMM", French)} · {Kcal(kcal)}" };
            Canvas.SetLeft(bar, left + slot * (i + .175));
            Canvas.SetTop(bar, bottom - height);
            WeightChart.Children.Add(bar);
        }
        if (chartCalorieTarget is double target)
        {
            double y = bottom - target / scale * (bottom - top);
            WeightChart.Children.Add(new Line { X1 = left, X2 = right, Y1 = y, Y2 = y, Stroke = (Brush)FindResource("ApricotInk"),
                StrokeDashArray = new DoubleCollection([5, 3]), ToolTip = "Objectif quotidien · " + Kcal(target) });
        }
        AddChartLabel(historyRows[0].Date.ToString("d MMM", French), left, bottom + 3);
        AddChartLabel(historyRows[^1].Date.ToString("d MMM", French), right, bottom + 3, alignRight: true);
    }

    private void DrawPauseDays()
    {
        ChartTitle.Text = "Jours de pause";
        DayRow[] days = chartGoal is Goal goal
            ? historyRows.Where(day => day.Date >= goal.Start && day.Date <= goal.End).ToArray() : [];
        NoChart.Text = days.Length == 0 ? "Les jours de pause apparaissent sur la période de ton objectif." : "";
        double width = WeightChart.ActualWidth;
        if (days.Length == 0 || width < 60) return;
        double slot = (width - 12) / days.Length;
        for (int i = 0; i < days.Length; i++)
        {
            DayRow day = days[i];
            string color = day.Status == "tenu" ? "GreenInk" : day.Status == JournalState.NotKeptStatus ? "ApricotInk" : "Line";
            var chip = new Rectangle
            {
                RadiusX = 3, RadiusY = 3, Height = 22, Width = Math.Min(32, Math.Max(2, slot - 3)),
                Fill = (Brush)FindResource(color),
                ToolTip = $"{day.Date.ToString("d MMM", French)} · {Status(day.Status, chartGoal, day.Date)}"
            };
            Canvas.SetTop(chip, 12);
            Canvas.SetLeft(chip, 6 + slot * i);
            WeightChart.Children.Add(chip);
        }
        AddChartLabel("Vert · tenu     Brun · non tenu     Papier · sans réponse", 6, 40);
    }

    private void AddChartLabel(string value, double x, double y, bool alignRight = false)
    {
        var label = new TextBlock { Text = value, FontSize = 10, Foreground = (Brush)FindResource("Muted") };
        label.Measure(new Size(double.PositiveInfinity, double.PositiveInfinity));
        Canvas.SetLeft(label, alignRight ? x - label.DesiredSize.Width : x);
        Canvas.SetTop(label, y);
        WeightChart.Children.Add(label);
    }

    private void EditGoal(object sender, RoutedEventArgs args)
    {
        JournalState before = store.Snapshot();
        var date = new DatePicker { SelectedDate = (before.Goal?.Start ?? Today).ToDateTime(TimeOnly.MinValue) };
        var duration = new TextBox { Text = (before.Goal?.Duration ?? 30).ToString(French), MaxLength = 3 };
        var dialog = new Editor(this, "Objectif de pause");
        dialog.Field("Date de début", date);
        dialog.Field("Durée en jours", duration);
        dialog.Show(() =>
        {
            if (date.SelectedDate is not DateTime start || !int.TryParse(duration.Text, out int days) || days is < 1 or > Goal.MaxDuration)
                return $"Choisis une date et une durée entre 1 et {Goal.MaxDuration} jours.";
            var value = new JsonObject { ["start"] = JournalState.Iso(DateOnly.FromDateTime(start)), ["duration"] = days };
            return SaveEdits(before, ("goal", value));
        });
    }

    private void ExtendGoal(object sender, RoutedEventArgs args)
    {
        JournalState before = store.Snapshot();
        if (before.Goal is not Goal goal || goal.Duration >= Goal.MaxDuration) return;
        int remaining = Goal.MaxDuration - goal.Duration;
        var extra = new TextBox { Text = Math.Min(30, remaining).ToString(French), MaxLength = 3 };
        var dialog = new Editor(this, "Prolonger la pause");
        dialog.Note("Fin actuelle · " + goal.End.ToString("d MMMM yyyy", French));
        dialog.Field("Jours à ajouter", extra);
        dialog.Show(() =>
        {
            if (!int.TryParse(extra.Text, out int days) || days < 1 || days > remaining)
                return remaining == 1 ? "Il reste 1 jour à ajouter." : $"Choisis entre 1 et {remaining} jours.";
            var value = new JsonObject { ["start"] = JournalState.Iso(goal.Start), ["duration"] = goal.Duration + days };
            try
            {
                if (!store.TryUpdate([new Change("goal", before.Entries["goal"], value)]))
                    return "La pause a changé. Rouvre la prolongation.";
                Refresh();
                return null;
            }
            catch (Exception error) when (JournalStore.IsFailure(error))
            {
                return "Impossible d’enregistrer. " + error.Message;
            }
        });
    }

    private void EditDay(object sender, RoutedEventArgs args)
    {
        JournalState before = store.Snapshot();
        string day = JournalState.Iso(selected), pauseKey = "pause/" + day, weightKey = "weight/" + day;
        string? currentStatus = before.Entries.GetValueOrDefault(pauseKey)?.GetValue<string>();
        var status = new ComboBox
        {
            ItemsSource = new[] { "Sans réponse", "Tenu", "Non tenu" },
            SelectedIndex = currentStatus == "tenu" ? 1 : currentStatus == JournalState.NotKeptStatus ? 2 : 0
        };
        double? previousWeight = before.Entries.GetValueOrDefault(weightKey)?.GetValue<double>();
        var weight = new TextBox { Text = previousWeight is double kg ? Format(kg) : "", MaxLength = 12 };
        var dialog = new Editor(this, "Modifier le " + selected.ToString("d MMMM", French));
        dialog.Field("Pause", status);
        dialog.Field("Poids (kg)", weight);
        dialog.Note("Vide la valeur du poids pour effacer la pesée.");
        dialog.Show(() =>
        {
            string? next = status.SelectedIndex == 1 ? "tenu" : status.SelectedIndex == 2 ? JournalState.NotKeptStatus : null;
            JsonNode? kgValue = null;
            if (!string.IsNullOrWhiteSpace(weight.Text))
            {
                if (!TryNumber(weight.Text, out double entered) || entered <= 0 || entered > 1000)
                    return "Indique un poids valide en kg.";
                kgValue = JsonValue.Create(entered);
            }
            return SaveEdits(before, (pauseKey, JsonValue.Create(next)), (weightKey, kgValue));
        });
    }

    private void EditTargets(object sender, RoutedEventArgs args)
    {
        JournalState before = store.Snapshot();
        var weight = new TextBox { Text = before.WeightTarget is double kg ? Format(kg) : "", MaxLength = 12 };
        var calories = new TextBox { Text = before.CaloriesTarget is double kcal ? Format(kcal) : "", MaxLength = 12 };
        var dialog = new Editor(this, "Objectifs personnels");
        dialog.Field("Poids cible (kg)", weight);
        dialog.Field("Calories quotidiennes (kcal)", calories);
        dialog.Note("Choisis tes propres valeurs. Laisse un champ vide pour retirer son objectif.");
        dialog.Show(() =>
        {
            if (!TryTarget(weight.Text, 1000, out JsonNode? weightValue)) return "Indique un poids cible valide.";
            if (!TryTarget(calories.Text, 100000, out JsonNode? caloriesValue)) return "Indique un objectif calorique valide.";
            return SaveEdits(before, ("target/weight", weightValue), ("target/calories", caloriesValue));
        });
    }

    private static bool TryTarget(string input, double maximum, out JsonNode? value)
    {
        value = null;
        if (string.IsNullOrWhiteSpace(input)) return true;
        if (!TryNumber(input, out double number) || number <= 0 || number > maximum) return false;
        value = JsonValue.Create(number);
        return true;
    }

    private void OpenSavedReports(object sender, RoutedEventArgs args)
    {
        SavedReport[] reports = store.Snapshot().Reports();
        var dialog = new Editor(this, "Bilans enregistrés", 690);
        if (reports.Length == 0)
        {
            dialog.Note("Demande à ton IA d’enregistrer un bilan dans Pause pour le retrouver ici.");
            dialog.Show();
            return;
        }
        var list = new ListBox
        {
            MaxHeight = 150,
            ItemsSource = reports.Select(report => $"{report.Title} · {report.Start.ToString("d MMM yyyy", French)} – {report.End.ToString("d MMM yyyy", French)}"),
            SelectedIndex = 0
        };
        var content = new TextBox
        {
            Text = reports[0].Text, IsReadOnly = true, AcceptsReturn = true,
            TextWrapping = TextWrapping.Wrap, VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Height = 260
        };
        list.SelectionChanged += (_, _) =>
        {
            if (list.SelectedIndex >= 0) content.Text = reports[list.SelectedIndex].Text;
        };
        dialog.Field("Historique", list);
        dialog.Field("Contenu du bilan", content);
        dialog.Show();
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

    private void OpenTrackers(object sender, RoutedEventArgs args)
    {
        trackerWindow = new TrackerWindow(this, store);
        try { trackerWindow.ShowDialog(); }
        finally { trackerWindow = null; }
    }

    private void OpenMealDrafts(object sender, RoutedEventArgs args)
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
                System.Windows.Automation.AutomationProperties.SetName(name, "Nom de l’aliment");
                form.Children.Add(name);
                var quantities = new Grid { Margin = new Thickness(0, 0, 0, 6) };
                quantities.ColumnDefinitions.Add(new ColumnDefinition());
                quantities.ColumnDefinitions.Add(new ColumnDefinition());
                bool barcode = next.Source == "Open Food Facts";
                TextBox grams = AddEstimateField(quantities, 0, barcode ? "Quantité (g ou ml)"
                    : food.Usual ? "Quantité (g) · poids usuel à vérifier" : "Quantité (g)", food.Grams);
                TextBox energy = AddEstimateField(quantities, 1, barcode ? "kcal pour 100 g ou 100 ml" : "kcal pour 100 g", food.Per100);
                foreach (TextBox field in new[] { name, grams, energy })
                    field.TextChanged += (_, _) => { if (!updating) edited = true; ShowTotal(); };
                FoodReference.Item[] options = food.Per100.HasValue ? [] : FoodReference.Match(food.Name).Options;
                ComboBox? choice = null;
                if (options.Length > 0)
                {
                    choice = new ComboBox { ItemsSource = options, DisplayMemberPath = nameof(FoodReference.Item.Name), Margin = new Thickness(0, 0, 0, 6) };
                    ComboBox references = choice;
                    references.SelectionChanged += (_, _) =>
                    {
                        if (references.SelectedItem is not FoodReference.Item item) return;
                        name.Text = item.Name.Length <= 90 ? item.Name : item.Name[..90];
                        energy.Text = Format(item.Per100, "0.####");
                    };
                    form.Children.Add(new Label { Content = "Choisir une référence Ciqual", Target = choice, FontSize = 12, Padding = new Thickness(0, 0, 0, 2) });
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
        list.SelectedIndex = 0;
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
                    if (!TryNumber(energy.Text, out double per100) || per100 is not (>= 0 and <= 1000))
                    { energy.Focus(); return $"Indique les calories pour 100 g de {food} ou choisis ChatGPT ou Claude."; }
                    completed.Add(new(food, amount, per100, SourceOf(per100, choice, proposed)));
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

    private void EditMeal(object sender, RoutedEventArgs args)
    {
        if (MealsList.SelectedItem is MealItem item) ShowMealEditor(item.Meal);
    }

    private void ShowMealEditor(Meal? meal)
    {
        JournalState before = store.Snapshot();
        string key = meal?.Key ?? $"meal/{JournalState.Iso(selected)}/{Guid.NewGuid():D}";
        var name = new TextBox { Text = meal?.Name ?? "", MaxLength = 90 };
        var modes = new ComboBox
        {
            ItemsSource = new[] { "Total de la portion", "Calculer avec la quantité" },
            SelectedIndex = meal?.Amount is null ? 0 : 1
        };
        var calories = new TextBox { Text = meal is null ? "" : Format(meal.Kcal), MaxLength = 12 };
        var amount = new TextBox { Text = meal?.Amount is double portion ? Format(portion) : "", MaxLength = 12 };
        var per100 = new TextBox { Text = meal?.Per100 is double energy ? Format(energy) : "", MaxLength = 12 };
        var dialog = new Editor(this, meal is null ? "Ajouter un aliment" : "Modifier l’aliment");
        dialog.Field("Nom", name);
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
            MessageBox.Show(this, "Supprimer cet aliment ?", "Pause", MessageBoxButton.YesNo, MessageBoxImage.Question) == MessageBoxResult.Yes
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
                    "Pause", MessageBoxButton.YesNo, MessageBoxImage.Question) != MessageBoxResult.Yes) return "";
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

    private void OpenPairing(object sender, RoutedEventArgs args)
    {
        string[] addresses = SyncServer.Addresses();
        if (addresses.Length == 0)
        {
            MessageBox.Show(this, "Connecte le PC au même réseau local que le téléphone, puis réessaie.", "Pause",
                MessageBoxButton.OK, MessageBoxImage.Information);
            return;
        }
        if (!StartSync() || server is not SyncServer active)
        {
            MessageBox.Show(this, serverError ?? "Synchro indisponible.", "Pause", MessageBoxButton.OK, MessageBoxImage.Warning);
            return;
        }
        var chooser = new ComboBox { ItemsSource = addresses, SelectedIndex = 0 };
        var image = new Image { Width = 240, Height = 240, Margin = new Thickness(0, 7, 0, 7) };
        var dialog = new Editor(this, "Associer Android", 435);
        dialog.Note("Sur Android, ouvre PC puis Scanne le QR code. Garde Pause ouvert sur le PC pendant la synchro.");
        dialog.Field("Adresse du PC sur le réseau", chooser);
        dialog.Content.Children.Add(image);
        void UpdateQr()
        {
            if (chooser.SelectedItem is not string address) return;
            using var generator = new QRCodeGenerator();
            using var data = generator.CreateQrCode(active.PairingCode(address), QRCodeGenerator.ECCLevel.M);
            using var qr = new PngByteQRCode(data);
            using var bytes = new MemoryStream(qr.GetGraphic(8));
            var bitmap = new BitmapImage();
            bitmap.BeginInit();
            bitmap.CacheOption = BitmapCacheOption.OnLoad;
            bitmap.StreamSource = bytes;
            bitmap.EndInit();
            image.Source = bitmap;
        }
        chooser.SelectionChanged += (_, _) => UpdateQr();
        UpdateQr();
        dialog.AddAction("Copier le code", () => Copy(active.PairingCode((string)chooser.SelectedItem)));
        dialog.AddAction("Révoquer l’ancien code", () =>
        {
            if (MessageBox.Show(this, "Générer un nouveau code ? Le téléphone devra être associé à nouveau.",
                "Pause", MessageBoxButton.YesNo, MessageBoxImage.Question) != MessageBoxResult.Yes) return;
            try { active.Revoke(); UpdateQr(); }
            catch (Exception error) when (JournalStore.IsFailure(error)) { MessageBox.Show(this, error.Message, "Pause"); }
        });
        dialog.Show();
    }

    private void Copy(string text)
    {
        try { Clipboard.SetText(text); }
        catch (System.Runtime.InteropServices.ExternalException)
        {
            MessageBox.Show(this, "Le presse-papiers est utilisé par une autre application. Réessaie.", "Fil des jours",
                MessageBoxButton.OK, MessageBoxImage.Information);
        }
    }

    private void OpenMcp(object sender, RoutedEventArgs args)
    {
        string executable = Environment.ProcessPath ?? IOPath.Combine(AppContext.BaseDirectory, "Pause.exe");
        string quoted = "'" + executable.Replace("'", "''") + "'";
        var client = new ComboBox { ItemsSource = new[] { "Claude Desktop", "Claude Code", "Codex" }, SelectedIndex = 0 };
        var steps = new TextBlock
        {
            Style = (Style)FindResource("MutedText"), Margin = new Thickness(0, 9, 0, 2)
        };
        var configuration = new TextBox
        {
            IsReadOnly = true, TextWrapping = TextWrapping.Wrap, FontFamily = new FontFamily("Consolas")
        };
        var dialog = new Editor(this, "Connecteur MCP", 560);
        dialog.Field("Client MCP", client);
        dialog.Content.Children.Add(steps);
        dialog.Field("Configuration à copier", configuration);
        dialog.Note("Le connecteur lit le journal du PC. Il peut aussi modifier une entrée ou enregistrer un bilan à ta demande. "
            + "Exemple « Ajoute 82,4 kg pour aujourd’hui ».");
        dialog.AddAction("Copier la configuration", () => Copy(configuration.Text));

        void SelectClient()
        {
            switch (client.SelectedIndex)
            {
                case 0:
                    steps.Text = "Ajoute cette configuration à %APPDATA%\\Claude\\claude_desktop_config.json. "
                        + "Si mcpServers existe déjà, ajoute seulement l’entrée pause_pc sous cette clé. Ferme complètement Claude Desktop, puis relance-le.";
                    configuration.Text = JsonSerializer.Serialize(new
                    {
                        mcpServers = new Dictionary<string, object>
                        {
                            ["pause_pc"] = new { command = executable, args = new[] { "--mcp" } }
                        }
                    }, new JsonSerializerOptions { WriteIndented = true });
                    break;
                case 1:
                    steps.Text = "Exécute cette commande dans PowerShell, puis ouvre une nouvelle session Claude Code.";
                    string entry = JsonSerializer.Serialize(new { type = "stdio", command = executable, args = new[] { "--mcp" } });
                    configuration.Text = "claude mcp add-json --scope user pause_pc '" + entry.Replace("'", "''") + "'";
                    break;
                default:
                    steps.Text = "Exécute cette commande dans PowerShell, puis ouvre une nouvelle conversation Codex.";
                    configuration.Text = $"codex mcp add pause_pc -- {quoted} --mcp";
                    break;
            }
        }

        client.SelectionChanged += (_, _) => SelectClient();
        SelectClient();
        dialog.Show();
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

    private sealed record DayItem(DateOnly Date, string DateLabel, string StatusLabel, string WeightLabel, string CaloriesLabel);
    private sealed record MealItem(Meal Meal, string Name, string CaloriesLabel);
}
