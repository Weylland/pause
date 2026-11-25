using System.Globalization;
using System.IO;
using System.Text.Json.Nodes;
using System.Windows;
using System.Windows.Automation;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Media;

namespace Pause;

internal sealed class TrackerList(Window owner, JournalStore store, Action changed) : StackPanel
{
    private static readonly CultureInfo French = CultureInfo.GetCultureInfo("fr-FR");
    private static DateOnly Today => DateOnly.FromDateTime(DateTime.Today);

    internal void Refresh(JournalState state)
    {
        Children.Clear();
        JournalTracker[] trackers = JournalTracker.List(state);
        if (trackers.Length == 0)
            Children.Add(new Border { Style = (Style)owner.FindResource("Card"), Child = new TextBlock { TextWrapping = TextWrapping.Wrap,
                Text = "Ajoute un suivi pour une habitude ou une pause. Par exemple, pratiquer l’anglais ou faire du sport." } });
        foreach (JournalTracker tracker in trackers) AddCard(state, tracker);
    }

    private void AddCard(JournalState state, JournalTracker tracker)
    {
        DateOnly today = Today;
        var layout = new StackPanel();
        Children.Add(new Border { Child = layout, Style = (Style)owner.FindResource("Card"), Margin = new Thickness(0, 0, 0, 14) });
        var header = new DockPanel { Margin = new Thickness(0, -5, -6, 0) };
        Button modify = TextAction("Modifier", () => Edit(tracker));
        AutomationProperties.SetName(modify, "Modifier " + tracker.Name);
        DockPanel.SetDock(modify, Dock.Right);
        header.Children.Add(modify);
        DateOnly last = tracker.End is DateOnly completed && completed < today ? completed : today;
        if (last >= tracker.Start)
        {
            Button other = TextAction("Autre date", () => EditDay(tracker, last));
            AutomationProperties.SetName(other, $"{tracker.Name}, choisir une autre date");
            DockPanel.SetDock(other, Dock.Right);
            header.Children.Add(other);
        }
        header.Children.Add(new TextBlock { Text = tracker.Name, Style = (Style)owner.FindResource("SectionTitle"), TextWrapping = TextWrapping.Wrap,
            VerticalAlignment = VerticalAlignment.Center });
        layout.Children.Add(header);
        JournalTracker.Progress progress = tracker.GetProgress(state, today);
        var facts = new List<string>();
        if (tracker.Pause) facts.Add("Pause");
        facts.Add(tracker.Duration == 0 ? $"{progress.Done} jour{(progress.Done > 1 ? "s" : "")}" : $"{progress.Done} jours sur {tracker.Duration - tracker.RestDays}");
        if (progress.Streak > 0) facts.Add($"série de {progress.Streak}");
        if (tracker.RestDays > 0 || progress.Rests > 0)
        {
            int extra = progress.Rests - tracker.RestDays;
            facts.Add($"repos {progress.Rests} sur {tracker.RestDays}" + (extra > 0 ? $", {extra} hors quota" : ""));
        }
        facts.Add(today < tracker.Start ? $"débute le {tracker.Start.ToString("d MMM", French)}"
            : tracker.End is DateOnly end ? $"jusqu’au {end.ToString("d MMM", French)}" : $"depuis le {tracker.Start.ToString("d MMM", French)}");
        layout.Children.Add(new TextBlock { Text = string.Join(" · ", facts), Style = (Style)owner.FindResource("MutedText"), FontSize = 14 });

        if (last < tracker.Start) return;
        var history = new UniformGrid { Columns = 7, Margin = new Thickness(-2, 10, -2, 0) };
        for (int offset = 6; offset >= 0; offset--) history.Children.Add(Day(state, tracker, last.AddDays(-offset)));
        layout.Children.Add(history);
    }

    private Button Day(JournalState state, JournalTracker tracker, DateOnly date)
    {
        JournalTracker.Day? status = tracker.Status(state, date);
        var (fill, ink, symbol) = status switch
        {
            JournalTracker.Day.Done => ("Mint", "GreenInk", "✓"),
            JournalTracker.Day.Missed => ("Apricot", "ApricotInk", "×"),
            JournalTracker.Day.Rest => ("Side", "Muted", "−"),
            _ => ("Surface", "Muted", "")
        };
        var content = new StackPanel();
        content.Children.Add(new TextBlock { Text = date.ToString("ddd", French)[..1].ToUpper(French) + " " + date.Day, HorizontalAlignment = HorizontalAlignment.Center });
        content.Children.Add(new TextBlock { Text = symbol, FontSize = 13, HorizontalAlignment = HorizontalAlignment.Center });
        var day = new Button { Content = content, Padding = new Thickness(0, 3, 0, 3), Margin = new Thickness(2), MinHeight = 48, FontSize = 14,
            Background = (Brush)owner.FindResource(fill), Foreground = (Brush)owner.FindResource(ink), BorderThickness = new Thickness(1.5),
            BorderBrush = date == Today ? (Brush)owner.FindResource("Brand") : status is null ? (Brush)owner.FindResource("SoftLine") : Brushes.Transparent,
            ToolTip = $"{date.ToString("d MMMM", French)} · {tracker.DayLabel(status)}", IsEnabled = tracker.Includes(date) };
        AutomationProperties.SetName(day, $"{tracker.Name} · {day.ToolTip}");
        day.Click += (_, _) => EditDay(tracker, date);
        return day;
    }

    private Button TextAction(string text, Action run)
    {
        var button = new Button { Content = text, Style = (Style)owner.FindResource("TextButton") };
        button.Click += (_, _) => run();
        return button;
    }

    internal void Edit(JournalTracker? tracker)
    {
        JournalState before = store.Snapshot();
        if (tracker is not null && JournalTracker.Find(before, tracker.Id) != tracker) { changed(); return; }
        var name = new TextBox { Text = tracker?.Name ?? "", MaxLength = JournalTracker.MaxName };
        var kind = new ComboBox { ItemsSource = new[] { "Habitude", "Pause" }, SelectedIndex = tracker?.Pause == true ? 1 : 0 };
        var start = new DatePicker { SelectedDate = (tracker?.Start ?? Today).ToDateTime(TimeOnly.MinValue) };
        var duration = new TextBox { MaxLength = 3, Text = tracker?.Duration is int length && length > 0 ? length.ToString(French) : "" };
        var rests = new TextBox { MaxLength = 3, Text = tracker?.RestDays is int rest && rest > 0 ? rest.ToString(French) : "" };
        var editor = new Editor(owner, tracker is null ? "Nouveau suivi" : "Modifier le suivi");
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

    internal void Record(JournalTracker tracker, DateOnly date, JournalTracker.Day? status)
    {
        JournalState state = store.Snapshot();
        JournalTracker? current = JournalTracker.Find(state, tracker.Id);
        string? error = current is null ? "Ce suivi a changé." : Save(() => current.Record(store, state, date, status));
        if (error is not null) MessageBox.Show(owner, error, "Suivis", MessageBoxButton.OK, MessageBoxImage.Information);
    }

    internal void EditDay(JournalTracker tracker, DateOnly initial)
    {
        JournalState state = store.Snapshot();
        if (JournalTracker.Find(state, tracker.Id) != tracker) { changed(); return; }
        var date = new DatePicker { SelectedDate = initial.ToDateTime(TimeOnly.MinValue),
            DisplayDateStart = tracker.Start.ToDateTime(TimeOnly.MinValue),
            DisplayDateEnd = (tracker.End is DateOnly end && end < Today ? end : Today).ToDateTime(TimeOnly.MinValue) };
        JournalTracker.Day?[] options = [null, JournalTracker.Day.Done, JournalTracker.Day.Rest, JournalTracker.Day.Missed];
        var choice = new ComboBox { ItemsSource = options.Select(tracker.DayLabel).ToArray() };
        void SetStatus() => choice.SelectedIndex = date.SelectedDate is DateTime value
            ? Array.IndexOf(options, tracker.Status(state, DateOnly.FromDateTime(value))) : 0;
        date.SelectedDateChanged += (_, _) => SetStatus();
        SetStatus();
        var editor = new Editor(owner, tracker.Name);
        editor.Field("Date", date);
        editor.Field("Réponse", choice);
        editor.Show(() => date.SelectedDate is DateTime day && choice.SelectedIndex >= 0
            ? Save(() => tracker.Record(store, state, DateOnly.FromDateTime(day), options[choice.SelectedIndex]))
            : "Choisis une date.");
    }

    private string? Save(Action update)
    {
        try { update(); changed(); return null; }
        catch (Exception error) when (JournalStore.IsFailure(error) || error is ArgumentException) { return error.Message; }
    }
}
