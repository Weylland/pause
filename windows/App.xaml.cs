using System.IO;
using System.Windows;

namespace Pause;

public partial class App : Application
{
    private Mutex? instance;

    protected override void OnStartup(StartupEventArgs args)
    {
        FrameworkElement.LanguageProperty.OverrideMetadata(typeof(FrameworkElement),
            new FrameworkPropertyMetadata(System.Windows.Markup.XmlLanguage.GetLanguage("fr-FR")));
        base.OnStartup(args);
        if (args.Args.SequenceEqual(new[] { "--mcp" }))
        {
            try { Mcp.RunAsync(Console.OpenStandardInput(), Console.OpenStandardOutput()).GetAwaiter().GetResult(); }
            catch (IOException) { }
            Shutdown();
            return;
        }

        string pathId = Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(
            System.Text.Encoding.UTF8.GetBytes(JournalStore.DirectoryPath.ToUpperInvariant())));
        instance = new Mutex(true, @"Local\Pause." + pathId, out bool firstInstance);
        if (!firstInstance)
        {
            MessageBox.Show("Le journal Pause est déjà ouvert.", "Pause", MessageBoxButton.OK, MessageBoxImage.Information);
            Shutdown();
            return;
        }
        try
        {
            MainWindow = new MainWindow(new JournalStore());
            MainWindow.Show();
        }
        catch (Exception error)
        {
            MessageBox.Show("Impossible d’ouvrir le journal. " + error.Message, "Pause", MessageBoxButton.OK, MessageBoxImage.Error);
            Shutdown(1);
        }
    }

    protected override void OnExit(ExitEventArgs args)
    {
        instance?.Dispose();
        base.OnExit(args);
    }
}
