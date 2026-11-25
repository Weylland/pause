using System.Text.Json.Nodes;
using System.Windows;
using System.Windows.Automation;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Shapes;

namespace Pause;

public partial class MainWindow
{
    private bool pauseEditing, weightEditing;
    private DateOnly? streakStart;
    private int streakTier;

    private static DateOnly Monday(DateOnly date) => date.AddDays(-(((int)date.DayOfWeek + 6) % 7));

    private void RefreshDay(JournalState state)
    {
        string label = selected.ToString("dddd d MMMM", French);
        DayTitle.Text = char.ToUpper(label[0], French) + label[1..];
        DayToday.Visibility = Shown(selected != Today);
        DayCalendar.SelectedDate = DayCalendar.DisplayDate = selected.ToDateTime(TimeOnly.MinValue);
        RefreshWeek(state);
        RefreshMeals(state);
        RefreshPause(state);
        RefreshHabits(state);
        RefreshWeight(state);
    }

    private void RefreshWeek(JournalState state)
    {
        WeekStrip.Children.Clear();
        DateOnly monday = Monday(selected);
        for (int i = 0; i < 7; i++)
        {
            DateOnly date = monday.AddDays(i);
            string? status = PauseStatus(state, date);
            bool waiting = status is null && date <= Today && state.Goal is Goal goal && date >= goal.Start && date <= goal.End;
            var content = new StackPanel { Margin = new Thickness(0, 5, 0, 6) };
            content.Children.Add(new TextBlock { Text = date.ToString("ddd", French)[..1].ToUpper(French), FontFamily = FontFamily, FontSize = 13,
                FontWeight = FontWeights.Medium, Foreground = (Brush)FindResource("Muted"), HorizontalAlignment = HorizontalAlignment.Center });
            content.Children.Add(new TextBlock { Text = date.Day.ToString(French), FontSize = 17, HorizontalAlignment = HorizontalAlignment.Center });
            content.Children.Add(new Ellipse { Width = 7, Height = 7, Margin = new Thickness(0, 3, 0, 0), StrokeThickness = 1.5,
                Fill = status is null ? null : (Brush)FindResource(status == "tenu" ? "GreenMid" : "ApricotInk"),
                Stroke = waiting ? (Brush)FindResource("Brand") : null });
            var day = new Button { Content = content, Style = (Style)FindResource("DayButton"), IsEnabled = date <= Today };
            if (date == selected)
            {
                day.Background = (Brush)FindResource("Surface");
                day.BorderBrush = (Brush)FindResource("Brand");
            }
            AutomationProperties.SetName(day, date.ToString("dddd d MMMM", French) + (status is null ? "" : " · " + Status(status, state.Goal, date)));
            day.Click += (_, _) => ShowDate(date);
            WeekStrip.Children.Add(day);
        }
        NextWeekButton.IsEnabled = monday.AddDays(7) <= Today;
    }

    private void RefreshMeals(JournalState state)
    {
        Meal[] meals = state.Meals(selected);
        MealDraft[] drafts = state.MealDrafts();
        MealRows.Children.Clear();
        foreach (Meal meal in meals)
            AddRow(MealRows, Row(meal.Name, null, new TextBlock { Text = Format(meal.Kcal, "#,##0") + " kcal", Style = (Style)FindResource("MutedText") }, () => ShowMealEditor(meal)));
        foreach (MealDraft draft in drafts.Where(draft => draft.Date == selected))
            AddRow(MealRows, Row(draft.Description, null, Chip(MealEstimate.Read(draft)?.Foods.Length > 0 ? "à vérifier" : "à estimer"), () => ShowMealDrafts(draft)));
        int elsewhere = drafts.Count(draft => draft.Date != selected);
        if (elsewhere > 0)
            AddRow(MealRows, Row(elsewhere == 1 ? "1 repas à estimer un autre jour" : $"{elsewhere} repas à estimer d’autres jours", null,
                IconMark("IconRight"), () => ShowMealDrafts(null)));
        NoMeals.Visibility = Shown(meals.Length == 0 && !drafts.Any(draft => draft.Date == selected));
        double total = meals.Sum(meal => meal.Kcal);
        DayCalories.Text = Format(total, "#,##0");
        DayTarget.Text = state.CaloriesTarget is double target ? $"sur {Kcal(target)}" : "kcal";
        CaloriesTrack.Visibility = Shown(state.CaloriesTarget.HasValue);
        double ratio = state.CaloriesTarget is double daily ? Math.Min(1, total / daily) : 0;
        CaloriesDone.Width = new GridLength(ratio, GridUnitType.Star);
        CaloriesRest.Width = new GridLength(1 - ratio, GridUnitType.Star);
    }

    private void RefreshPause(JournalState state)
    {
        PauseCard.Visibility = Shown(state.Goal is not null);
        RefreshStreak(state.Streak(Today), state.Goal);
        if (state.Goal is not Goal goal) return;
        string? status = PauseStatus(state, selected);
        bool inside = selected >= goal.Start && selected <= goal.End;
        bool asking = inside && (status is null || pauseEditing);
        string day = selected == Today ? "aujourd’hui" : "ce jour-là";
        DayPause.Text = selected < goal.Start ? $"Elle débute le {goal.Start.ToString("d MMMM", French)}."
            : selected > goal.End ? $"Terminée le {goal.End.ToString("d MMMM", French)}."
            : asking ? $"Jour {selected.DayNumber - goal.Start.DayNumber + 1} sur {goal.Duration}. Tu as tenu {day} ?"
            : $"{Status(status, goal, selected)} {day}";
        PauseChoices.Visibility = Shown(asking);
        ChangePause.Visibility = Shown(inside && !asking);
        ClearPause.Visibility = Shown(asking && status is not null);
        Paint(KeptButton, status == "tenu", "Mint", "GreenInk");
        Paint(NotKeptButton, status == JournalState.NotKeptStatus, "Apricot", "ApricotInk");
    }

    private void Paint(Button button, bool chosen, string fill, string ink)
    {
        button.Background = (Brush)FindResource(chosen ? ink : fill);
        button.Foreground = chosen ? Brushes.White : (Brush)FindResource(ink);
        AutomationProperties.SetItemStatus(button, chosen ? "Choisi" : "");
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
        AutomationProperties.SetName(StreakLabel, label);
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

    private void RefreshHabits(JournalState state)
    {
        HabitRows.Children.Clear();
        JournalTracker[] active = JournalTracker.List(state).Where(tracker => tracker.Includes(selected)).ToArray();
        HabitsCard.Visibility = Shown(active.Length > 0);
        foreach (JournalTracker tracker in active)
        {
            JournalTracker.Day? status = tracker.Status(state, selected);
            JournalTracker.Progress progress = tracker.GetProgress(state, Today);
            string detail = status is JournalTracker.Day.Missed or JournalTracker.Day.Rest ? tracker.DayLabel(status)
                : tracker.Duration > 0 ? $"{progress.Done} sur {tracker.Duration - tracker.RestDays}"
                : progress.Streak > 0 ? $"{progress.Streak} jour{(progress.Streak > 1 ? "s" : "")} d’affilée" : $"{progress.Done} jour{(progress.Done > 1 ? "s" : "")}";
            var tick = new Button { Width = 40, Height = 40, MinHeight = 40, Padding = new Thickness(0), BorderThickness = new Thickness(2), Margin = new Thickness(10, 0, 0, 0) };
            if (status is not null)
            {
                string fill = status switch { JournalTracker.Day.Done => "GreenMid", JournalTracker.Day.Missed => "Apricot", _ => "Side" };
                tick.Background = tick.BorderBrush = (Brush)FindResource(fill);
                tick.Foreground = status == JournalTracker.Day.Done ? Brushes.White : (Brush)FindResource("Ink");
                tick.Content = IconMark(status switch { JournalTracker.Day.Done => "IconCheck", JournalTracker.Day.Missed => "IconCross", _ => "IconMinus" });
            }
            AutomationProperties.SetName(tick, $"{tracker.Name} · {(status is null ? tracker.Positive : "Effacer " + tracker.DayLabel(status))}");
            tick.Click += (_, _) => trackers.Record(tracker, selected, status is null ? JournalTracker.Day.Done : null);
            var line = new DockPanel();
            DockPanel.SetDock(tick, Dock.Right);
            line.Children.Add(tick);
            line.Children.Add(Row(tracker.Name, detail, null, () => trackers.EditDay(tracker, selected)));
            AddRow(HabitRows, line);
        }
    }

    private void RefreshWeight(JournalState state)
    {
        double? weight = state.Entries.GetValueOrDefault("weight/" + JournalState.Iso(selected))?.GetValue<double>();
        bool entry = weight is null || weightEditing;
        WeightShown.Visibility = Shown(!entry);
        WeightEntry.Visibility = Shown(entry);
        DayWeight.Text = weight is double kg ? Kg(kg) : "";
        string last = state.Entries.Keys.Where(key => key.StartsWith("weight/", StringComparison.Ordinal)
            && string.CompareOrdinal(key, "weight/" + JournalState.Iso(selected)) < 0).Max(StringComparer.Ordinal) ?? "";
        LastWeight.Text = last.Length == 0 ? "" : $"{Kg(state.Entries[last].GetValue<double>())} le {JournalState.ParseDate(last[7..]).ToString("d MMM", French)}";
    }

    private static string? PauseStatus(JournalState state, DateOnly date) =>
        state.Entries.GetValueOrDefault("pause/" + JournalState.Iso(date))?.GetValue<string>();

    private void ShowDate(DateOnly date)
    {
        selected = date;
        pauseEditing = weightEditing = false;
        WeightInput.Text = "";
        WeightNote.Visibility = Visibility.Collapsed;
        TodayTab.IsChecked = true;
        Refresh();
    }

    private void GoToToday(object sender, RoutedEventArgs args) => ShowDate(Today);
    private void PreviousWeek(object sender, RoutedEventArgs args) => ShowDate(selected.AddDays(-7));
    private void NextWeek(object sender, RoutedEventArgs args) =>
        ShowDate(selected.AddDays(7) > Today ? Today : selected.AddDays(7));
    private void OpenCalendar(object sender, RoutedEventArgs args) => CalendarPopup.IsOpen = true;

    private void ChooseDate(object sender, SelectionChangedEventArgs args)
    {
        if (rendering || DayCalendar.SelectedDate is not DateTime date) return;
        CalendarPopup.IsOpen = false;
        ShowDate(DateOnly.FromDateTime(date));
    }

    private void ComposerChanged(object sender, TextChangedEventArgs args)
    {
        ComposerHint.Visibility = Shown(Composer.Text.Length == 0);
        if (Composer.Text.Length > 0) ComposerNote.Visibility = Visibility.Collapsed;
    }

    private void ComposerKey(object sender, KeyEventArgs args)
    {
        if (args.Key == Key.Enter) AddFoods(sender, args);
    }

    private void AddFoods(object sender, RoutedEventArgs args)
    {
        if (string.IsNullOrWhiteSpace(Composer.Text)) { Composer.Focus(); return; }
        EstimatedFood[] foods = MealEstimate.ParseLocal(Composer.Text);
        string? error = AddEstimated(store.Snapshot(), foods);
        if (error is null)
        {
            double total = foods.Sum(food => food.Grams!.Value * (food.Per100 ?? MealEstimate.UnknownPer100) / 100);
            string unknown = string.Join(", ", foods.Where(food => food.Per100 is null).Select(food => food.Name));
            Composer.Clear();
            ComposerNote.Text = $"Ajouté, environ {Format(total, "#,##0")} kcal. "
                + (unknown.Length == 0 ? "Clique sur un aliment pour le corriger." : $"Valeur moyenne pour : {unknown}.");
        }
        else ComposerNote.Text = error;
        ComposerNote.Visibility = Shown(ComposerNote.Text.Length > 0);
        Composer.Focus();
    }

    private string? AddEstimated(JournalState before, EstimatedFood[] foods)
    {
        if (foods.Length == 0) return "Indique entre 1 et 20 aliments.";
        if (foods.FirstOrDefault(food => food.Grams is null) is EstimatedFood missing) return $"Vérifie la quantité pour {missing.Name}.";
        return SaveEdits(before, foods.Select(food => ($"meal/{JournalState.Iso(selected)}/{Guid.NewGuid():D}", (JsonNode?)new JsonObject
        {
            ["name"] = food.Name, ["kcal"] = food.Grams!.Value * (food.Per100 ?? MealEstimate.UnknownPer100) / 100,
            ["kcalPer100"] = food.Per100 ?? MealEstimate.UnknownPer100, ["amount"] = food.Grams, ["source"] = food.Source ?? "Moyenne"
        })).ToArray());
    }

    private void EditPause(object sender, RoutedEventArgs args)
    {
        pauseEditing = true;
        Refresh();
    }

    private void MarkPause(object sender, RoutedEventArgs args)
    {
        string status = (string)((Button)sender).Tag;
        pauseEditing = false;
        Report(SaveEdits(store.Snapshot(), ("pause/" + JournalState.Iso(selected), status.Length == 0 ? null : JsonValue.Create(status))));
        Refresh();
    }

    private void Report(string? error)
    {
        if (!string.IsNullOrEmpty(error)) MessageBox.Show(this, error, "Fil des jours", MessageBoxButton.OK, MessageBoxImage.Information);
    }

    private void EditWeight(object sender, RoutedEventArgs args)
    {
        weightEditing = true;
        Refresh();
        WeightInput.Text = store.Snapshot().Entries.GetValueOrDefault("weight/" + JournalState.Iso(selected)) is JsonNode kg ? Format(kg.GetValue<double>()) : "";
        WeightInput.Focus();
        WeightInput.SelectAll();
    }

    private void WeightKey(object sender, KeyEventArgs args)
    {
        if (args.Key == Key.Enter) SaveWeight(sender, args);
    }

    private void SaveWeight(object sender, RoutedEventArgs args)
    {
        JsonNode? value = null;
        string? error = null;
        if (!string.IsNullOrWhiteSpace(WeightInput.Text) || !weightEditing)
        {
            if (TryNumber(WeightInput.Text, out double kg) && kg is > 0 and <= 1000) value = JsonValue.Create(kg);
            else error = "Indique un poids en kg, par exemple 74,2.";
        }
        error ??= SaveEdits(store.Snapshot(), ("weight/" + JournalState.Iso(selected), value));
        WeightNote.Text = error ?? "";
        WeightNote.Visibility = Shown(!string.IsNullOrEmpty(error));
        if (error is not null) { WeightInput.Focus(); return; }
        weightEditing = false;
        WeightInput.Text = "";
        Refresh();
    }
}
