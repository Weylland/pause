package fr.pause.journal;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.view.View;

import java.util.List;

final class DailyCaloriesChart extends View {
    private final List<String> dates;
    private final List<Double> amounts;
    private final Double target;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    DailyCaloriesChart(Context context, List<String> dates, List<Double> amounts, Double target) {
        super(context);
        this.dates = dates;
        this.amounts = amounts;
        this.target = target;
        paint.setTypeface(PaperStyle.body(context));
        StringBuilder description = new StringBuilder("Calories par jour. ");
        for (int i = 0; i < amounts.size(); i++) {
            Double kcal = amounts.get(i);
            description.append(dates.get(i)).append(" : ")
                    .append(kcal == null ? "sans saisie" : Math.round(kcal) + " kcal").append(". ");
        }
        setContentDescription(description);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float density = getResources().getDisplayMetrics().density;
        float left = 33 * density, right = getWidth() - 4 * density;
        float top = 10 * density, bottom = getHeight() - 24 * density;
        if (right <= left || bottom <= top) return;
        double highest = target == null ? 0 : target;
        for (Double amount : amounts) if (amount != null) highest = Math.max(highest, amount);
        double scale = Math.max(100, Math.ceil(highest * 1.1 / 100) * 100);
        paint.setTextSize(10 * density);
        paint.setStrokeWidth(density);
        paint.setTextAlign(Paint.Align.LEFT);
        for (int i = 0; i <= 2; i++) {
            float y = top + (bottom - top) * i / 2;
            paint.setColor(PaperStyle.LINE);
            paint.setPathEffect(new DashPathEffect(new float[]{3 * density, 4 * density}, 0));
            canvas.drawLine(left, y, right, y, paint);
            paint.setPathEffect(null);
            paint.setColor(PaperStyle.MUTED);
            canvas.drawText(Long.toString(Math.round(scale * (2 - i) / 2)), 0, y + 3 * density, paint);
        }
        float slot = (right - left) / amounts.size();
        for (int i = 0; i < amounts.size(); i++) {
            float x = left + slot * (i + .5f);
            Double value = amounts.get(i);
            if (value != null) {
                float height = Math.max(2 * density, (float) (value / scale * (bottom - top)));
                paint.setColor(i == amounts.size() - 1 ? 0xFFB8431F : 0xFFD9A28C);
                canvas.drawRoundRect(x - slot * .25f, bottom - height, x + slot * .25f, bottom,
                        3 * density, 3 * density, paint);
            }
            paint.setColor(PaperStyle.MUTED);
            paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(dates.get(i), x, bottom + 17 * density, paint);
        }
        if (target != null) {
            float y = bottom - (float) (target / scale * (bottom - top));
            paint.setColor(0xFF88481E);
            paint.setPathEffect(new DashPathEffect(new float[]{5 * density, 3 * density}, 0));
            canvas.drawLine(left, y, right, y, paint);
            paint.setPathEffect(null);
        }
    }
}
