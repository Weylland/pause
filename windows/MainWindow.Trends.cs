using System.Globalization;
using System.Windows;
using System.Windows.Automation;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Shapes;

namespace Pause;

public partial class MainWindow
{
    private int range = 30;
    private int chartSection;
    private DayRow[] chartRows = [];
    private DayRow[] historyRows = [];
    private Goal? chartGoal;
    private double? chartCalorieTarget;

    private void RefreshTrends(JournalState state)
    {
        JournalReport report = JournalReport.Build(state, Today.AddDays(1 - range), Today, Today);
        historyRows = report.Rows;
        chartGoal = report.Goal;
        chartCalorieTarget = state.CaloriesTarget;
        chartRows = report.Rows.Where(row => row.Weight.HasValue).ToArray();
        WeightValue.Text = chartRows.Length == 0 ? "—" : Kg(chartRows[^1].Weight!.Value);
        double change = chartRows.Length < 2 ? 0 : chartRows[^1].Weight!.Value - chartRows[0].Weight!.Value;
        WeightDetail.Text = chartRows.Length == 0 ? "Aucune pesée sur cette période"
            : (chartRows.Length == 1 ? "Mesuré le " + chartRows[0].Date.ToString("d MMMM", French)
                : Math.Abs(change) < .05 ? "Stable" : $"{Kg(Math.Round(Math.Abs(change), 1))} de {(change < 0 ? "moins" : "plus")}")
            + (state.WeightTarget is double goal ? $" · objectif {Kg(goal)}" : "");
        DayRow[] logged = report.Rows.Where(row => row.Kcal.HasValue).ToArray();
        CaloriesValue.Text = logged.Length == 0 ? "—" : Format(logged.Average(row => row.Kcal!.Value), "#,##0") + " kcal";
        CaloriesDetail.Text = logged.Length == 0 ? "Aucune saisie sur cette période"
            : "Moyenne par jour noté" + (state.CaloriesTarget is double daily ? $" · objectif {Format(daily, "#,##0")}" : "");
        var (_, kept, _) = chartGoal is Goal pause ? state.PauseProgress(pause, Today) : (0, 0, 0);
        KeptValue.Text = chartGoal is null ? "—" : $"{kept} sur {chartGoal.Duration}";
        KeptDetail.Text = chartGoal is null ? "Aucune pause en cours" : "Jours tenus";
        DrawChart();

        JournalRows.Children.Clear();
        foreach (DayRow row in report.Rows.Reverse()) AddRow(JournalRows, JournalRow(row));
        SavedReport? latest = state.Reports().FirstOrDefault();
        ReportsHost.Children.Clear();
        ReportsHost.Children.Add(Row("Bilans", latest is null ? "Aucun bilan enregistré" : "Dernier : " + latest.Title, IconMark("IconRight"), OpenSavedReports, "IconDoc"));
    }

    private Button JournalRow(DayRow row)
    {
        var line = new Grid();
        foreach (double width in new double[] { 0, 84, 66, 88 })
            line.ColumnDefinitions.Add(new ColumnDefinition { Width = width == 0 ? new GridLength(1, GridUnitType.Star) : new GridLength(width) });
        void Cell(int column, UIElement content)
        {
            Grid.SetColumn(content, column);
            line.Children.Add(content);
        }
        TextBlock Value(string text) => new() { Text = text, TextAlignment = TextAlignment.Right, VerticalAlignment = VerticalAlignment.Center };
        string label = row.Date.ToString("ddd d MMM", French);
        Cell(0, new TextBlock { Text = char.ToUpper(label[0], French) + label[1..], VerticalAlignment = VerticalAlignment.Center });
        if (row.Status is string status)
            Cell(1, new Border
            {
                Background = (Brush)FindResource(status == "tenu" ? "Mint" : "Apricot"), CornerRadius = new CornerRadius(9),
                Padding = new Thickness(9, 2, 9, 3), HorizontalAlignment = HorizontalAlignment.Left,
                Child = new TextBlock { Text = Status(status, chartGoal, row.Date), FontFamily = (FontFamily)FindResource("TitleFont"), FontSize = 13,
                    FontWeight = FontWeights.SemiBold, Foreground = (Brush)FindResource(status == "tenu" ? "GreenInk" : "ApricotInk") }
            });
        Cell(2, Value(row.Weight is double kg ? Kg(kg) : "—"));
        Cell(3, Value(row.Kcal is double kcal ? Format(kcal, "#,##0") + " kcal" : "—"));
        var button = new Button { Content = line, Style = (Style)FindResource("RowButton"), MinHeight = 40 };
        button.Click += (_, _) => ShowDate(row.Date);
        return button;
    }

    private void SelectRange(object sender, RoutedEventArgs args)
    {
        if (!IsInitialized) return;
        range = int.Parse(((RadioButton)sender).Tag.ToString()!, CultureInfo.InvariantCulture);
        Refresh();
    }

    private void ChartSizeChanged(object sender, SizeChangedEventArgs args) => DrawChart();

    private void ChangeChart(object sender, RoutedEventArgs args)
    {
        if (!IsInitialized) return;
        chartSection = int.Parse(((RadioButton)sender).Tag.ToString()!, CultureInfo.InvariantCulture);
        DrawChart();
    }

    private void DrawChart()
    {
        if (ChartCanvas is null || NoChart is null || ChartTitle is null) return;
        ChartCanvas.Children.Clear();
        ChartLegend.Text = "";
        if (chartSection == 1) { DrawDailyCalories(); return; }
        if (chartSection == 2) { DrawPauseDays(); return; }
        ChartTitle.Text = $"Poids sur {range} jours";
        bool available = chartRows.Length >= 2;
        NoChart.Text = available ? "" : "Deux pesées suffisent pour afficher la courbe.";
        if (!available || ChartCanvas.ActualWidth < 30) return;

        double width = ChartCanvas.ActualWidth, height = ChartCanvas.ActualHeight;
        const double left = 60, top = 10;
        double right = width - 6, bottom = height - 24;
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
            ChartCanvas.Children.Add(new Line { X1 = left, X2 = right, Y1 = y, Y2 = y,
                Stroke = (Brush)FindResource("SoftLine"), StrokeDashArray = new DoubleCollection([3, 4]) });
            AddChartLabel(Kg(Math.Round(high - (high - low) * i / 2, 1)), 0, y - 9);
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
        ChartCanvas.Children.Add(new Polygon { Points = areaPoints, Fill = shade });
        ChartCanvas.Children.Add(curve);
        for (int i = 0; i < curve.Points.Count; i++)
        {
            Point point = curve.Points[i];
            int size = i == curve.Points.Count - 1 ? 8 : 5;
            var dot = new Ellipse { Width = size, Height = size, Fill = (Brush)FindResource("Surface"), Stroke = accent, StrokeThickness = 1.5 };
            Canvas.SetLeft(dot, point.X - size / 2.0);
            Canvas.SetTop(dot, point.Y - size / 2.0);
            ChartCanvas.Children.Add(dot);
        }
        AddChartLabel(chartRows[0].Date.ToString("d MMM", French), left, bottom + 6);
        AddChartLabel(chartRows[^1].Date.ToString("d MMM", French), right, bottom + 6, alignRight: true);
    }

    private void DrawDailyCalories()
    {
        ChartTitle.Text = $"Calories sur {range} jours";
        NoChart.Text = historyRows.Any(day => day.Kcal.HasValue) ? "" : "Les barres apparaissent dès la première saisie.";
        double right = ChartCanvas.ActualWidth - 6, bottom = ChartCanvas.ActualHeight - 24;
        const double left = 50, top = 10;
        if (right <= left || bottom <= top || NoChart.Text.Length > 0) return;
        double maximum = Math.Max(chartCalorieTarget ?? 0, historyRows.Max(day => day.Kcal ?? 0));
        double scale = Math.Max(100, Math.Ceiling(maximum * 1.1 / 100) * 100);
        for (int i = 0; i <= 2; i++)
        {
            double y = top + (bottom - top) * i / 2;
            ChartCanvas.Children.Add(new Line { X1 = left, X2 = right, Y1 = y, Y2 = y,
                Stroke = (Brush)FindResource("SoftLine"), StrokeDashArray = new DoubleCollection([3, 4]) });
            AddChartLabel(Format(scale * (2 - i) / 2, "#,##0"), 0, y - 9);
        }
        double slot = (right - left) / historyRows.Length;
        for (int i = 0; i < historyRows.Length; i++)
        {
            DayRow day = historyRows[i];
            if (day.Kcal is not double kcal) continue;
            double height = Math.Max(2, kcal / scale * (bottom - top));
            var bar = new Rectangle { RadiusX = Math.Min(5, slot / 4), RadiusY = Math.Min(5, slot / 4), Width = slot * .65, Height = height,
                Fill = (Brush)FindResource(i == historyRows.Length - 1 ? "Brand" : "ApricotMid"),
                ToolTip = $"{day.Date.ToString("d MMM", French)} · {Kcal(kcal)}" };
            Canvas.SetLeft(bar, left + slot * (i + .175));
            Canvas.SetTop(bar, bottom - height);
            ChartCanvas.Children.Add(bar);
        }
        if (chartCalorieTarget is double target)
        {
            double y = bottom - target / scale * (bottom - top);
            ChartLegend.Text = $"Pointillés : objectif de {Kcal(target)}.";
            ChartCanvas.Children.Add(new Line { X1 = left, X2 = right, Y1 = y, Y2 = y, Stroke = (Brush)FindResource("ApricotInk"),
                StrokeDashArray = new DoubleCollection([5, 3]), ToolTip = "Objectif quotidien · " + Kcal(target) });
        }
        AddChartLabel(historyRows[0].Date.ToString("d MMM", French), left, bottom + 6);
        AddChartLabel(historyRows[^1].Date.ToString("d MMM", French), right, bottom + 6, alignRight: true);
    }

    private void DrawPauseDays()
    {
        ChartTitle.Text = $"Pause sur {range} jours";
        DayRow[] days = chartGoal is Goal goal ? historyRows.Where(day => day.Date >= goal.Start && day.Date <= goal.End).ToArray() : [];
        NoChart.Text = chartGoal is null ? "Démarre une pause dans Suivis pour voir tes jours ici."
            : days.Length == 0 ? "Aucun jour de pause sur cette période." : "";
        double width = ChartCanvas.ActualWidth, height = ChartCanvas.ActualHeight;
        if (days.Length == 0 || width < 60 || height < 60) return;
        ChartLegend.Text = "Vert : tenu. Rose : non tenu. Contour : sans réponse.";
        DateOnly monday = Monday(days[0].Date);
        int weeks = (days[^1].Date.DayNumber - monday.DayNumber) / 7 + 1;
        double slot = Math.Min(62, Math.Min((width - 6) / 7, height / weeks)), size = slot - 5, left = (width - slot * 7) / 2;
        foreach (DayRow day in days)
        {
            int offset = day.Date.DayNumber - monday.DayNumber;
            string? fill = day.Status == "tenu" ? "Mint" : day.Status == JournalState.NotKeptStatus ? "Apricot" : null;
            string ink = fill switch { "Mint" => "GreenInk", "Apricot" => "ApricotInk", _ => "Muted" };
            var cell = new Border
            {
                Width = size, Height = size, CornerRadius = new CornerRadius(Math.Min(12, size / 4)), BorderThickness = new Thickness(1.5),
                Background = fill is null ? null : (Brush)FindResource(fill), BorderBrush = fill is null ? (Brush)FindResource("SoftLine") : null,
                ToolTip = $"{day.Date.ToString("d MMM", French)} · {Status(day.Status, chartGoal, day.Date)}",
                Child = new TextBlock { Text = day.Date.Day.ToString(French), Foreground = (Brush)FindResource(ink), FontSize = Math.Clamp(size / 3, 8, 15),
                    FontFamily = (FontFamily)FindResource("TitleFont"), FontWeight = FontWeights.SemiBold,
                    HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center }
            };
            Canvas.SetLeft(cell, left + slot * (offset % 7));
            Canvas.SetTop(cell, slot * (offset / 7));
            ChartCanvas.Children.Add(cell);
        }
    }

    private void AddChartLabel(string value, double x, double y, bool alignRight = false)
    {
        var label = new TextBlock { Text = value, FontSize = 12.5, Foreground = (Brush)FindResource("Muted") };
        label.Measure(new Size(double.PositiveInfinity, double.PositiveInfinity));
        Canvas.SetLeft(label, alignRight ? x - label.DesiredSize.Width : x);
        Canvas.SetTop(label, y);
        ChartCanvas.Children.Add(label);
    }

    private void OpenSavedReports(object sender, RoutedEventArgs args)
    {
        SavedReport[] reports = store.Snapshot().Reports();
        var dialog = new Editor(this, "Bilans enregistrés", 690);
        if (reports.Length == 0)
        {
            dialog.Note("Demande à ton IA d’enregistrer un bilan dans Fil des jours pour le retrouver ici.");
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
}
