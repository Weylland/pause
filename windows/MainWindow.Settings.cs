using System.IO;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using QRCoder;

namespace Pause;

public partial class MainWindow
{
    private void RefreshSettings(JournalState state)
    {
        SettingGroups.Children.Clear();
        string wifi = serverError is not null ? "Indisponible sur ce PC"
            : state.LastSync is DateTimeOffset last ? "Dernier échange le " + last.ToLocalTime().ToString("d MMM à HH:mm", French) : "Associer un téléphone avec un QR code";
        string targets = string.Join(" · ", new[] { state.WeightTarget is double kg ? Kg(kg) : null,
            state.CaloriesTarget is double kcal ? Kcal(kcal) + " par jour" : null }.Where(text => text is not null));
        SettingGroup("Synchronisation",
            Row("Google Drive", drive.Connected ? drive.Status : "Non connecté", IconMark("IconRight"), OpenDrive, "IconCloud"),
            Row("Wi-Fi local", wifi, IconMark("IconRight"), OpenPairing, "IconPhone"));
        SettingGroup("Objectifs",
            Row("Poids et calories", targets.Length == 0 ? "Aucun objectif" : targets, IconMark("IconRight"), EditTargets, "IconScale"),
            Row("Ma pause", state.Goal is Goal goal ? $"Du {goal.Start.ToString("d MMMM", French)} au {goal.End.ToString("d MMMM", French)}" : "Aucune pause",
                IconMark("IconRight"), EditGoal, "IconFlag"));
        SettingGroup("IA",
            Row("Connecteur pour Claude et Codex", "Lire et compléter le journal", IconMark("IconRight"), OpenMcp, "IconPlug"));
    }

    private void SettingGroup(string title, params Button[] rows)
    {
        var list = new StackPanel();
        foreach (Button row in rows) AddRow(list, row);
        var group = new StackPanel { Margin = new Thickness(8, 18, 8, 0) };
        group.Children.Add(new TextBlock { Text = title, Style = (Style)FindResource("ColumnHeader"), FontSize = 15, Margin = new Thickness(4, 0, 0, 8) });
        group.Children.Add(new Border { Style = (Style)FindResource("Card"), Padding = new Thickness(18, 5, 18, 5), Child = list });
        SettingGroups.Children.Add(group);
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

    private void OpenPairing(object sender, RoutedEventArgs args)
    {
        string[] addresses = SyncServer.Addresses();
        if (addresses.Length == 0)
        {
            MessageBox.Show(this, "Connecte le PC au même réseau local que le téléphone, puis réessaie.", "Fil des jours",
                MessageBoxButton.OK, MessageBoxImage.Information);
            return;
        }
        if (!StartSync() || server is not SyncServer active)
        {
            MessageBox.Show(this, serverError ?? "Synchro indisponible.", "Fil des jours", MessageBoxButton.OK, MessageBoxImage.Warning);
            return;
        }
        var chooser = new ComboBox { ItemsSource = addresses, SelectedIndex = 0 };
        var image = new Image { Width = 240, Height = 240, Margin = new Thickness(0, 7, 0, 7) };
        var dialog = new Editor(this, "Wi-Fi local", 435);
        dialog.Note("Sur le téléphone, ouvre Réglages puis Wi-Fi local et scanne ce QR code. Garde Fil des jours ouvert sur le PC pendant l’échange.");
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
                "Fil des jours", MessageBoxButton.YesNo, MessageBoxImage.Question) != MessageBoxResult.Yes) return;
            try { active.Revoke(); UpdateQr(); }
            catch (Exception error) when (JournalStore.IsFailure(error)) { MessageBox.Show(this, error.Message, "Fil des jours"); }
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
        string executable = Environment.ProcessPath ?? Path.Combine(AppContext.BaseDirectory, "Pause.exe");
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
        var dialog = new Editor(this, "Connecteur pour Claude et Codex", 560);
        dialog.Field("Application", client);
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
}
