using System.Globalization;
using System.IO;
using System.Text.Json.Nodes;
using System.Windows;
using System.Windows.Automation;
using System.Windows.Controls;
using System.Windows.Media;

namespace Pause;

internal sealed class TrackerWindow : Window
{
    private static readonly CultureInfo French = CultureInfo.GetCultureInfo("fr-FR");
    private readonly JournalStore store;
    private readonly StackPanel cards = new();
    private static DateOnly Today => DateOnly.FromDateTime(DateTime.Today);

    internal TrackerWindow(Window owner, JournalStore store)
    {
        this.store = store;
        Owner = owner;
        Title = "Mes suivis";
        WindowStartupLocation = WindowStartupLocation.CenterOwner;
        ShowInTaskbar = false;
        Width = 690;
        Height = 680;
        MinWidth = 495;
        MinHeight = 425;
        FontSize = 14;
        FontFamily = (FontFamily)FindResource("BodyFont");
        Background = (Brush)FindResource("PaperGrain");
        Foreground = (Brush)FindResource("Ink");
        var root = new DockPanel { Margin = new Thickness(22) };
        var header = new DockPanel { Margin = new Thickness(0, 0, 0, 13) };
        var add = Action("Nouveau suivi", () => Edit(null), "Primary");
        DockPanel.SetDock(add, Dock.Right);
        header.Children.Add(add);
        header.Children.Add(new TextBlock { Text = Title, FontSize = 30, FontFamily = (FontFamily)FindResource("TitleFont"),
            VerticalAlignment = VerticalAlignment.Center });
        DockPanel.SetDock(header, Dock.Top);
        root.Children.Add(header);
        root.Children.Add(new ScrollViewer { Content = cards, VerticalScrollBarVisibility = ScrollBarVisibility.Auto });
        Content = root;
        Refresh(store.Snapshot());
    }

    internal void Refresh(JournalState state)
    {
        cards.Children.Clear();
        JournalTracker[] trackers = JournalTracker.List(state);
        if (trackers.Length == 0)
            cards.Children.Add(new TextBlock { Text = "Ajoute un suivi pour une habitude ou une pause. Par exemple, pratiquer l’anglais ou faire du sport.",
                TextWrapping = TextWrapping.Wrap, Margin = new Thickness(3, 10, 0, 0) });
        foreach (JournalTracker tracker in trackers) AddCard(state, tracker);
    }

    private void AddCard(JournalState state, JournalTracker tracker)
    {
        DateOnly today = Today;
        var layout = new StackPanel();
        var card = new Border { Child = layout, Style = (Style)FindResource("Card"), Margin = new Thickness(0, 0, 8, 10) };
        if (tracker.Pause) { card.Background = (Brush)FindResource("Gold"); card.BorderBrush = (Brush)FindResource("Line"); }
        cards.Children.Add(card);
        layout.Children.Add(new TextBlock { Text = tracker.Name, FontSize = 23, FontFamily = (FontFamily)FindResource("TitleFont"), TextWrapping = TextWrapping.Wrap });
        string dates = tracker.End is DateOnly end ? $"Du {tracker.Start.ToString("d MMM", French)} au {end.ToString("d MMM", French)}"
            : $"Depuis le {tracker.Start.ToString("d MMM", French)}";
        if (today < tracker.Start) dates = $"Débute le {tracker.Start.ToString("d MMM", French)}";
        layout.Children.Add(new TextBlock { Text = (tracker.Pause ? "Pause" : "Habitude") + " · " + dates,
            Style = (Style)FindResource("MutedText") });
        JournalTracker.Progress progress = tracker.GetProgress(state, today);
        string count = tracker.Duration == 0 ? progress.Done.ToString(French) : $"{progress.Done}/{tracker.Duration - tracker.RestDays}";
        string labelCount = tracker.Duration == 0 && progress.Done == 1 ? "jour coché" : "jours cochés";
        layout.Children.Add(new TextBlock { Text = $"{count} {labelCount} · Série {progress.Streak} j",
            Foreground = (Brush)FindResource("Accent"), FontWeight = FontWeights.SemiBold, Margin = new Thickness(0, 7, 0, 4) });
        if (tracker.RestDays > 0 || progress.Rests > 0)
        {
            int extra = progress.Rests - tracker.RestDays;
            layout.Children.Add(new TextBlock { Text = $"Repos · {progress.Rests}/{tracker.RestDays}" + (extra > 0 ? $" · {extra} hors quota" : ""),
                Style = (Style)FindResource("MutedText") });
        }
        if (tracker.Includes(today))
        {
            JournalTracker.Day? status = tracker.Status(state, today);
            var quick = new WrapPanel { Margin = new Thickness(0, 4, 0, 0) };
            foreach (JournalTracker.Day choice in new[] { JournalTracker.Day.Done, JournalTracker.Day.Rest, JournalTracker.Day.Missed })
            {
                if (choice == JournalTracker.Day.Rest && tracker.RestDays == 0 && status != choice) continue;
                string label = tracker.DayLabel(choice);
                var button = Action(label, () => RecordToday(tracker, choice), status == choice ? "Primary"
                    : choice == JournalTracker.Day.Done ? null : "QuietButton");
                AutomationProperties.SetName(button, $"{tracker.Name} · {label} aujourd’hui");
                quick.Children.Add(button);
            }
            layout.Children.Add(quick);
        }

        DateOnly last = tracker.End is DateOnly completed && completed < today ? completed : today;
        var history = new WrapPanel { Margin = new Thickness(0, 8, 0, 0) };
        for (int offset = 6; offset >= 0; offset--)
        {
            DateOnly date = last.AddDays(-offset);
            if (date < tracker.Start) continue;
            JournalTracker.Day? status = tracker.Status(state, date);
            string label = tracker.DayLabel(status);
            string symbol = status switch { JournalTracker.Day.Done => "✓", JournalTracker.Day.Missed => "×", JournalTracker.Day.Rest => "−", _ => "·" };
            var day = Action($"{date.Day} {symbol}", () => EditDay(tracker, date),
                status is null or JournalTracker.Day.Rest ? "QuietButton" : null);
            day.Padding = new Thickness(8, 4, 8, 4);
            if (status == JournalTracker.Day.Missed) { day.Background = (Brush)FindResource("Apricot"); day.Foreground = (Brush)FindResource("ApricotInk"); }
            day.ToolTip = $"{date.ToString("d MMMM", French)} · {label}";
            AutomationProperties.SetName(day, $"{tracker.Name} · {day.ToolTip}");
            history.Children.Add(day);
        }
        if (history.Children.Count > 0) layout.Children.Add(history);
        var settings = new WrapPanel { Margin = new Thickness(0, 8, 0, 0) };
        settings.Children.Add(Action("Modifier le suivi", () => Edit(tracker), "QuietButton"));
        if (last >= tracker.Start) settings.Children.Add(Action("Autre date", () => EditDay(tracker, last), "QuietButton"));
        layout.Children.Add(settings);
    }

    private Button Action(string text, Action run, string? style = null)
    {
        var button = new Button { Content = text, Margin = new Thickness(0, 0, 6, 3) };
        if (style is not null) button.Style = (Style)FindResource(style);
        button.Click += (_, _) => run();
        return button;
    }

    private void Edit(JournalTracker? tracker)
    {
        JournalState before = store.Snapshot();
        if (tracker is not null && JournalTracker.Find(before, tracker.Id) != tracker) { Refresh(before); return; }
        var name = new TextBox { Text = tracker?.Name ?? "", MaxLength = JournalTracker.MaxName };
        var kind = new ComboBox { ItemsSource = new[] { "Habitude", "Pause" }, SelectedIndex = tracker?.Pause == true ? 1 : 0 };
        var start = new DatePicker { SelectedDate = (tracker?.Start ?? Today).ToDateTime(TimeOnly.MinValue) };
        var duration = new TextBox { MaxLength = 3, Text = tracker?.Duration is int length && length > 0 ? length.ToString(French) : "" };
        var rests = new TextBox { MaxLength = 3, Text = tracker?.RestDays is int rest && rest > 0 ? rest.ToString(French) : "" };
        var editor = new Editor(this, tracker is null ? "Nouveau suivi" : "Modifier le suivi");
        editor.Field("Nom", name);
        editor.Field("Type", kind);
        editor.Field("Début", start);
        editor.Field("Durée en jours (facultatif)", duration);
        editor.Field("Jours de repos autorisés", rests);
        string id = tracker?.Id ?? Guid.NewGuid().ToString("D");
        editor.Show(() =>
        {
            if (string.IsNullOrWhiteSpace(name.Text)) return "Donne un nom au suivi.";
            if (start.SelectedDate is not DateTime date || date.Year is < 1900 or > 9998) return "Choisis une date valide.";
            int days = 0;
            if (!string.IsNullOrWhiteSpace(duration.Text)
                && (!int.TryParse(duration.Text, out days) || days is < 1 or > Goal.MaxDuration))
                return $"Indique entre 1 et {Goal.MaxDuration} jours, ou laisse vide.";
            int allowance = 0, maximum = days == 0 ? Goal.MaxDuration : days;
            if (!string.IsNullOrWhiteSpace(rests.Text) && (!int.TryParse(rests.Text, out allowance) || allowance < 0 || allowance > maximum))
                return $"Indique entre 0 et {maximum} jours de repos.";
            var updated = new JournalTracker(id, name.Text.Trim(), kind.SelectedIndex == 1, DateOnly.FromDateTime(date), days, allowance);
            return Save(() =>
            {
                if (!store.TryUpdate([new Change(updated.Key, before.Entries.GetValueOrDefault(updated.Key), updated.ToJson())]))
                    throw new InvalidDataException("Ce suivi a changé. Rouvre ses paramètres.");
            });
        });
    }

    private void RecordToday(JournalTracker tracker, JournalTracker.Day status)
    {
        JournalState state = store.Snapshot();
        JournalTracker? current = JournalTracker.Find(state, tracker.Id);
        string? error = current is null ? "Ce suivi a changé." : Save(() => current.Record(store, state, Today, status));
        if (error is not null) MessageBox.Show(this, error, Title, MessageBoxButton.OK, MessageBoxImage.Information);
    }

    private void EditDay(JournalTracker tracker, DateOnly initial)
    {
        JournalState state = store.Snapshot();
        if (JournalTracker.Find(state, tracker.Id) != tracker) { Refresh(state); return; }
        var date = new DatePicker { SelectedDate = initial.ToDateTime(TimeOnly.MinValue),
            DisplayDateStart = tracker.Start.ToDateTime(TimeOnly.MinValue),
            DisplayDateEnd = (tracker.End is DateOnly end && end < Today ? end : Today).ToDateTime(TimeOnly.MinValue) };
        JournalTracker.Day?[] options = [null, JournalTracker.Day.Done, JournalTracker.Day.Rest, JournalTracker.Day.Missed];
        var choice = new ComboBox { ItemsSource = options.Select(tracker.DayLabel).ToArray() };
        void SetStatus() => choice.SelectedIndex = date.SelectedDate is DateTime value
            ? Array.IndexOf(options, tracker.Status(state, DateOnly.FromDateTime(value))) : 0;
        date.SelectedDateChanged += (_, _) => SetStatus();
        SetStatus();
        var editor = new Editor(this, tracker.Name);
        editor.Field("Date", date);
        editor.Field("Réponse", choice);
        editor.Show(() => date.SelectedDate is DateTime day && choice.SelectedIndex >= 0
            ? Save(() => tracker.Record(store, state, DateOnly.FromDateTime(day), options[choice.SelectedIndex]))
            : "Choisis une date.");
    }

    private string? Save(Action update)
    {
        try { update(); Refresh(store.Snapshot()); return null; }
        catch (Exception error) when (JournalStore.IsFailure(error) || error is ArgumentException) { return error.Message; }
    }
}
