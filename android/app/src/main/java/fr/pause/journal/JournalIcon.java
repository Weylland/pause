package fr.pause.journal;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

final class JournalIcon extends View {
    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int kind;

    JournalIcon(Context context, int kind, int color) {
        super(context);
        this.kind = kind;
        ink.setColor(color);
        ink.setStrokeWidth(1.65f);
        ink.setStrokeCap(Paint.Cap.ROUND);
        ink.setStrokeJoin(Paint.Join.ROUND);
        ink.setStyle(Paint.Style.STROKE);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.save();
        canvas.scale(getWidth() / 24f, getHeight() / 24f);
        switch (kind) {
            case 0 -> {
                canvas.drawRoundRect(5, 3, 9, 21, 1.3f, 1.3f, ink);
                canvas.drawRoundRect(15, 3, 19, 21, 1.3f, 1.3f, ink);
            }
            case 1 -> {
                canvas.drawRoundRect(3, 3, 21, 21, 4, 4, ink);
                canvas.drawArc(7, 7, 17, 17, 195, 150, false, ink);
                canvas.drawLine(12, 13, 14.5f, 9.2f, ink);
            }
            case 2 -> {
                Path fork = new Path();
                fork.moveTo(4, 3); fork.lineTo(4, 8); fork.quadTo(4, 11, 8, 11);
                fork.quadTo(12, 11, 12, 8); fork.lineTo(12, 3);
                canvas.drawPath(fork, ink);
                canvas.drawLine(8, 3, 8, 21, ink);
                Path knife = new Path();
                knife.moveTo(19, 21); knife.lineTo(19, 3);
                knife.quadTo(15, 5, 15, 13); knife.lineTo(19, 13);
                canvas.drawPath(knife, ink);
            }
            case 3 -> {
                canvas.drawRoundRect(4, 4, 20, 16, 1.7f, 1.7f, ink);
                Path base = new Path();
                base.moveTo(4, 16); base.lineTo(2, 20); base.lineTo(22, 20); base.lineTo(20, 16);
                canvas.drawPath(base, ink);
            }
            case 4 -> {
                Path bubble = new Path();
                bubble.moveTo(5, 4); bubble.lineTo(19, 4); bubble.quadTo(21, 4, 21, 6);
                bubble.lineTo(21, 14); bubble.quadTo(21, 16, 19, 16); bubble.lineTo(11, 16);
                bubble.lineTo(6, 20); bubble.lineTo(6, 16); bubble.lineTo(5, 16); bubble.quadTo(3, 16, 3, 14);
                bubble.lineTo(3, 6); bubble.quadTo(3, 4, 5, 4);
                canvas.drawPath(bubble, ink);
                canvas.drawLine(8, 10, 16, 10, ink);
                canvas.drawLine(12, 7, 12, 13, ink);
            }
        }
        canvas.restore();
    }
}
