using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;

namespace Pause;

internal sealed class Editor
{
    private readonly Window window;
    private readonly StackPanel footer = new() { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right };
    private readonly TextBlock error = new() { TextWrapping = TextWrapping.Wrap };
    private readonly Border errorCard = new() { Visibility = Visibility.Collapsed, CornerRadius = new CornerRadius(10),
        Padding = new Thickness(10, 7, 10, 7), Margin = new Thickness(0, 9, 0, 0) };
    internal StackPanel Content { get; } = new();

    internal Editor(Window owner, string title, int width = 410)
    {
        window = new Window
        {
            Owner = owner, Title = title, ShowInTaskbar = false, WindowStartupLocation = WindowStartupLocation.CenterOwner,
            Width = width, SizeToContent = SizeToContent.Height, ResizeMode = ResizeMode.NoResize,
            FontSize = 15, Background = (Brush)Application.Current.FindResource("PaperGrain"),
            FontFamily = (FontFamily)Application.Current.FindResource("BodyFont"),
            Foreground = (Brush)Application.Current.FindResource("Ink")
        };
        var root = new StackPanel { Margin = new Thickness(20) };
        root.Children.Add(new TextBlock { Text = title, Style = (Style)Application.Current.FindResource("SectionTitle"),
            FontSize = 24, TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 0, 0, 12) });
        root.Children.Add(new Border { Style = (Style)Application.Current.FindResource("Card"), Child = Content });
        error.Foreground = (Brush)Application.Current.FindResource("ApricotInk");
        errorCard.Background = (Brush)Application.Current.FindResource("Apricot");
        errorCard.Child = error;
        root.Children.Add(errorCard);
        root.Children.Add(footer);
        window.Content = root;
    }

    internal StackPanel Field(string title, Control control)
    {
        var field = new StackPanel { Margin = new Thickness(0, 8, 0, 0) };
        field.Children.Add(new Label { Content = title, Target = control, Padding = new Thickness(0, 0, 0, 4) });
        field.Children.Add(control);
        Content.Children.Add(field);
        return field;
    }

    internal void Note(string text) => Content.Children.Add(new TextBlock
    {
        Text = text, Style = (Style)Application.Current.FindResource("MutedText"), Margin = new Thickness(0, 6, 0, 4)
    });

    internal void AddAction(string label, Action click)
    {
        var button = new Button { Content = label, Margin = new Thickness(0, 4, 0, 4) };
        button.Click += (_, _) => click();
        Content.Children.Add(button);
    }

    internal void Show(Func<string?>? save = null, Func<string?>? remove = null, string saveLabel = "Enregistrer")
    {
        if (remove is not null) FooterButton("Supprimer", () => Run(remove));
        Button close = FooterButton(save is null ? "Fermer" : "Annuler", () => window.Close());
        close.IsCancel = true;
        if (save is not null)
        {
            Button confirm = FooterButton(saveLabel, () => Run(save));
            confirm.IsDefault = true;
            confirm.Style = (Style)Application.Current.FindResource("Primary");
        }
        window.ShowDialog();
    }

    private void Run(Func<string?> operation)
    {
        string? message = operation();
        if (message is null) window.Close();
        else
        {
            error.Text = message;
            errorCard.Visibility = Visibility.Visible;
        }
    }

    private Button FooterButton(string label, Action operation)
    {
        var button = new Button { Content = label, Margin = new Thickness(5, 6, 0, 0) };
        button.Click += (_, _) => operation();
        footer.Children.Add(button);
        return button;
    }
}
