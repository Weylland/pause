using System.Globalization;
using System.IO;
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
    private SyncServer? server;
    private string? serverError;
    private DateOnly selected = DateOnly.FromDateTime(DateTime.Today);
    private int range = 30;
    private bool rendering;
    private DayRow[] chartRows = [];
    private DayRow[] historyRows = [];
    private Goal? chartGoal;
    private double? chartCalorieTarget;
    private DateOnly? streakStart;
    private int streakTier;

    internal MainWindow(JournalStore store)
    {
        this.store = store;
        InitializeComponent();
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
        journalChanges.Changed += (_, _) => QueueRefresh();
        journalChanges.Created += (_, _) => QueueRefresh();
        journalChanges.Renamed += (_, _) => QueueRefresh();
        journalChanges.EnableRaisingEvents = true;
        if (File.Exists(IOPath.Combine(JournalStore.DirectoryPath, "pairing.key"))) StartSync();
        Closed += (_, _) =>
        {
            journalChanges.Dispose();
            refreshTimer.Stop();
            server?.Dispose();
        };
        Activated += (_, _) => QueueRefresh();
        Refresh();
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
            server.Synced += QueueRefresh;
            serverError = null;
            return true;
        }
        catch (Exception error) when (error is IOException or SocketException or System.Security.Cryptography.CryptographicException)
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
            DateOnly first = Today.AddDays(1 - range);
            JournalReport report = JournalReport.Build(state, first, Today, Today);
            historyRows = report.Rows;
            chartGoal = report.Goal;
            chartCalorieTarget = state.CaloriesTarget;
            PendingMealsButton.Visibility = state.MealDrafts().Length > 0 ? Visibility.Visible : Visibility.Collapsed;
            RangeText.Text = $"Du {first.ToString("d MMMM", French)} au {Today.ToString("d MMMM yyyy", French)}";
            foreach (Button button in new[] { Days7, Days30, Days90 })
            {
                bool active = button.Tag.ToString() == range.ToString(CultureInfo.InvariantCulture);
                button.Style = (Style)FindResource(active ? "Primary" : "QuietButton");
            }

            PauseValue.Text = report.Goal is null ? "À définir" : $"{report.Kept}/{report.Due} jour{(report.Due > 1 ? "s" : "")} tenu{(report.Kept > 1 ? "s" : "")}";
            PauseDetail.Text = report.Goal is null ? "Choisis une date et une durée."
                : $"{report.NotKept} non tenu{(report.NotKept > 1 ? "s" : "")} · {report.Due - report.Kept - report.NotKept} sans réponse";
            PauseTrack.Visibility = report.Goal is null ? Visibility.Collapsed : Visibility.Visible;
            PauseDone.Width = new GridLength(report.Kept, GridUnitType.Star);
            PauseRest.Width = new GridLength(Math.Max(1, report.Due) - report.Kept, GridUnitType.Star);
            PauseFill.Visibility = report.Kept > 0 ? Visibility.Visible : Visibility.Collapsed;
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
            SyncStatus.Text = serverError ?? (state.LastSync is DateTimeOffset lastSync
                ? $"Dernière synchro Android · {lastSync.ToLocalTime().ToString("d MMM à HH:mm", French)} · Journal local sur ce PC"
                : "Aucune synchro Android · Ouvre Associer Android pour relier ton téléphone");
            SyncDot.Fill = serverError is not null ? (Brush)FindResource("ApricotInk")
                : (Brush)FindResource(state.LastSync.HasValue ? "Accent" : "Line");
        }
        finally
        {
            rendering = false;
        }
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
        StreakLabel.Foreground = new SolidColorBrush(tier == 0 ? Color.FromRgb(228, 239, 228) : Color.FromRgb(34, 56, 43));
        StreakBadge.Background = tier switch
        {
            3 => new LinearGradientBrush(Color.FromRgb(255, 217, 146), Color.FromRgb(255, 237, 179), -30),
            2 => new LinearGradientBrush(Color.FromRgb(190, 218, 135), Color.FromRgb(232, 239, 174), -30),
            1 => new LinearGradientBrush(Color.FromRgb(171, 205, 181), Color.FromRgb(221, 235, 206), -30),
            _ => new SolidColorBrush(Color.FromRgb(55, 93, 69))
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
    private void ChangeChart(object sender, SelectionChangedEventArgs args) => DrawChart();

    private void DrawChart()
    {
        if (WeightChart is null || NoChart is null || ChartTitle is null) return;
        WeightChart.Children.Clear();
        if (ChartKind.SelectedIndex == 1) { DrawDailyCalories(); return; }
        if (ChartKind.SelectedIndex == 2) { DrawPauseDays(); return; }
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
        Brush accent = (Brush)FindResource("Accent");
        for (int i = 0; i <= 2; i++)
        {
            double y = top + (bottom - top) * i / 2;
            WeightChart.Children.Add(new Line { X1 = left, X2 = right, Y1 = y, Y2 = y, Stroke = (Brush)FindResource("Line") });
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
        var shade = new LinearGradientBrush(Color.FromArgb(78, 91, 144, 94), Color.FromArgb(8, 91, 144, 94), 90);
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
            WeightChart.Children.Add(new Line { X1 = left, X2 = right, Y1 = y, Y2 = y, Stroke = (Brush)FindResource("Line") });
            AddChartLabel(Format(scale * (2 - i) / 2, "#,##0"), 0, y - 7);
        }
        double slot = (right - left) / historyRows.Length;
        for (int i = 0; i < historyRows.Length; i++)
        {
            DayRow day = historyRows[i];
            if (day.Kcal is not double kcal) continue;
            double height = Math.Max(2, kcal / scale * (bottom - top));
            var bar = new Rectangle { RadiusX = 2, RadiusY = 2, Width = slot * .65, Height = height,
                Fill = (Brush)FindResource("Accent"), Opacity = i == historyRows.Length - 1 ? 1 : .62,
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
            string color = day.Status == "tenu" ? "Accent" : day.Status == JournalState.NotKeptStatus ? "ApricotInk" : "Line";
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
        AddChartLabel("Vert · tenu     Brun · non tenu     Gris · sans réponse", 6, 40);
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
            catch (IOException error)
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

    private void OpenMealDrafts(object sender, RoutedEventArgs args)
    {
        MealDraft[] drafts = store.Snapshot().MealDrafts();
        var dialog = new Editor(this, "Repas à estimer", 615);
        dialog.Note("Dans Claude connecté à Pause, demande : « Affiche mes repas à estimer et propose les calories. »");
        var list = new ListBox { MaxHeight = 270,
            ItemsSource = drafts.Select(draft => $"{draft.Date.ToString("d MMM yyyy", French)} · {draft.Description}") };
        dialog.Content.Children.Add(list);
        dialog.Show();
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
        catch (Exception error) when (error is IOException or InvalidOperationException or ArgumentException)
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
        dialog.AddAction("Copier le code", () => Clipboard.SetText(active.PairingCode((string)chooser.SelectedItem)));
        dialog.AddAction("Révoquer l’ancien code", () =>
        {
            if (MessageBox.Show(this, "Générer un nouveau code ? Le téléphone devra être associé à nouveau.",
                "Pause", MessageBoxButton.YesNo, MessageBoxImage.Question) != MessageBoxResult.Yes) return;
            try { active.Revoke(); UpdateQr(); }
            catch (IOException error) { MessageBox.Show(this, error.Message, "Pause"); }
        });
        dialog.Show();
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
        dialog.AddAction("Copier la configuration", () => Clipboard.SetText(configuration.Text));

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
