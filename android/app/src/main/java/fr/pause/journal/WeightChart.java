package fr.pause.journal;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.view.View;

import java.text.NumberFormat;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;

final class WeightChart extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Journal.Weight> values;
    private final float scale;
    private final NumberFormat number = NumberFormat.getNumberInstance(Locale.FRANCE);

    WeightChart(Context context, List<Journal.Weight> values) {
        super(context);
        this.values = values;
        scale = getResources().getDisplayMetrics().density;
        number.setMaximumFractionDigits(1);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float left = 42 * scale;
        float right = getWidth() - 7 * scale;
        float top = 10 * scale;
        float bottom = getHeight() - top;
        double low = Double.MAX_VALUE;
        double high = Double.MIN_VALUE;
        for (Journal.Weight value : values) {
            low = Math.min(low, value.kg());
            high = Math.max(high, value.kg());
        }
        double padding = Math.max((high - low) * 0.18, 0.25);
        low -= padding;
        high += padding;

        paint.setShader(null);
        paint.setColor(0xFFDEE4D8);
        paint.setStrokeWidth(scale);
        paint.setTextSize(10 * scale);
        for (int i = 0; i < 3; i++) {
            float y = top + (bottom - top) * i / 2;
            paint.setColor(0xFFDEE4D8);
            canvas.drawLine(left, y, right, y, paint);
            paint.setColor(0xFF52675A);
            canvas.drawText(number.format(high - (high - low) * i / 2), 0, y + 3 * scale, paint);
        }
        Path path = new Path();
        float[] xs = new float[values.size()];
        float[] ys = new float[values.size()];
        long span = ChronoUnit.DAYS.between(values.get(0).date(), values.get(values.size() - 1).date());
        for (int i = 0; i < values.size(); i++) {
            Journal.Weight value = values.get(i);
            long offset = ChronoUnit.DAYS.between(values.get(0).date(), value.date());
            xs[i] = span == 0 ? (left + right) / 2 : left + (right - left) * offset / span;
            ys[i] = (float) (bottom - (bottom - top) * (value.kg() - low) / (high - low));
            if (i == 0) path.moveTo(xs[i], ys[i]);
            else path.lineTo(xs[i], ys[i]);
        }
        Path area = new Path(path);
        area.lineTo(xs[xs.length - 1], bottom);
        area.lineTo(xs[0], bottom);
        area.close();
        paint.setShader(new LinearGradient(0, top, 0, bottom,
                0x664D8A5E, 0x084D8A5E, Shader.TileMode.CLAMP));
        canvas.drawPath(area, paint);
        paint.setShader(null);
        paint.setColor(0xFF285C44);
        paint.setStrokeWidth(2.5f * scale);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStyle(Paint.Style.STROKE);
        canvas.drawPath(path, paint);
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < values.size(); i++) {
            float radius = (i == values.size() - 1 ? 4.5f : 3) * scale;
            paint.setColor(0xFF285C44);
            canvas.drawCircle(xs[i], ys[i], radius, paint);
            paint.setColor(0xFFFFFEFA);
            canvas.drawCircle(xs[i], ys[i], radius - 1.3f * scale, paint);
        }
    }
}
