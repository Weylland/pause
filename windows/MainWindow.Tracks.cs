using System.Text.Json.Nodes;
using System.Windows;
using System.Windows.Automation;
using System.Windows.Controls;
using System.Windows.Media;

namespace Pause;

public partial class MainWindow
{
    private DateOnly month = new(DateTime.Today.Year, DateTime.Today.Month, 1);

    private void RefreshTracks(JournalState state)
    {
        Goal? goal = state.Goal;
        trackers.Refresh(state);
        PauseTrack.Visibility = MonthCard.Visibility = Shown(goal is not null);
        StartPause.Visibility = Shown(goal is null || goal.End < Today);
        StartPause.Content = goal is null ? "Démarrer une pause" : "Démarrer une nouvelle pause";
        ExtendPause.Visibility = Shown(goal is not null && goal.Duration < Goal.MaxDuration);
        if (goal is null)
        {
            PauseValue.Text = "Prêt quand tu l’es";
            PauseDetail.Text = "Choisis une date et une durée.";
            PauseEnd.Text = "";
            return;
        }
        var (due, kept, notKept) = state.PauseProgress(goal, Today);
        PauseValue.Text = $"{kept} jour{(kept > 1 ? "s" : "")} tenu{(kept > 1 ? "s" : "")}";
        int waiting = due - kept - notKept;
        PauseDetail.Text = $"Jour {due} sur {goal.Duration}" + (notKept > 0 ? $" · {notKept} non tenu{(notKept > 1 ? "s" : "")}" : "")
            + (waiting > 0 ? $" · {waiting} sans réponse" : "");
        PauseDone.Width = new GridLength(kept, GridUnitType.Star);
        PauseRest.Width = new GridLength(goal.Duration - kept, GridUnitType.Star);
        PauseFill.Visibility = Shown(kept > 0);
        PauseEnd.Text = $"Du {goal.Start.ToString("d MMM", French)} au {goal.End.ToString("d MMM", French)}";
        RefreshMonth(state, goal);
    }

    private void RefreshMonth(JournalState state, Goal goal)
    {
        DateOnly first = new(goal.Start.Year, goal.Start.Month, 1), last = new(goal.End.Year, goal.End.Month, 1);
        if (month < first) month = first;
        if (month > last) month = last;
        string title = month.ToString(month.Year == Today.Year ? "MMMM" : "MMMM yyyy", French);
        MonthTitle.Text = char.ToUpper(title[0], French) + title[1..];
        PreviousMonthButton.IsEnabled = month > first;
        NextMonthButton.IsEnabled = month < last;
        MonthGrid.Children.Clear();
        foreach (char day in "LMMJVSD")
            MonthGrid.Children.Add(new TextBlock { Text = day.ToString(), Style = (Style)FindResource("MutedText"), FontSize = 13,
                HorizontalAlignment = HorizontalAlignment.Center, Margin = new Thickness(0, 2, 0, 4) });
        for (DateOnly blank = Monday(month); blank < month; blank = blank.AddDays(1)) MonthGrid.Children.Add(new Border());
        for (DateOnly date = month; date.Month == month.Month; date = date.AddDays(1)) MonthGrid.Children.Add(MonthDay(state, goal, date));
    }

    private Button MonthDay(JournalState state, Goal goal, DateOnly date)
    {
        bool inside = date >= goal.Start && date <= goal.End;
        string? status = inside ? PauseStatus(state, date) : null;
        var day = new Button { Content = date.Day.ToString(French), MinHeight = 40, Padding = new Thickness(0), Margin = new Thickness(2),
            Background = Brushes.Transparent, BorderBrush = Brushes.Transparent, BorderThickness = new Thickness(2),
            Foreground = (Brush)FindResource(inside ? "Muted" : "Line"), IsEnabled = inside && date <= Today };
        if (status is not null)
        {
            day.Background = (Brush)FindResource(status == "tenu" ? "Mint" : "Apricot");
            day.Foreground = (Brush)FindResource(status == "tenu" ? "GreenInk" : "ApricotInk");
        }
        else if (inside && date < Today) day.BorderBrush = (Brush)FindResource("SoftLine");
        if (date == Today) day.BorderBrush = (Brush)FindResource("Brand");
        AutomationProperties.SetName(day, $"{date.ToString("d MMMM", French)} · {(date > Today ? "À venir" : Status(status, goal, date))}");
        day.ToolTip = inside && date <= Today ? Status(status, goal, date) : null;
        day.Click += (_, _) => ShowDate(date);
        return day;
    }

    private void PreviousMonth(object sender, RoutedEventArgs args) => ShowMonth(-1);
    private void NextMonth(object sender, RoutedEventArgs args) => ShowMonth(1);

    private void ShowMonth(int offset)
    {
        month = month.AddMonths(offset);
        Refresh();
    }

    private void NewTracker(object sender, RoutedEventArgs args) => trackers.Edit(null);

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
}
