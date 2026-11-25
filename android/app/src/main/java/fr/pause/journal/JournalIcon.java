package fr.pause.journal;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import androidx.core.graphics.PathParser;

final class JournalIcon extends View {
    enum Kind {
        SUN("M12,8 A4,4 0 1 1 12,16 A4,4 0 1 1 12,8 M12,3 V5 M12,19 V21 M3,12 H5 M19,12 H21 M5.6,5.6 L7,7 M17,17 L18.4,18.4 M5.6,18.4 L7,17 M17,7 L18.4,5.6"),
        CHECKS("M4,6.5 L5.5,8 L8.5,5 M4,16.5 L5.5,18 L8.5,15 M12,6.5 H20 M12,16.5 H20"),
        CHART("M4,4 V20 H20 M8,14 L11.5,10 L14.5,12.5 L20,7"),
        SLIDERS("M4,7 H13 M17,7 H20 M4,17 H7 M11,17 H20 M15,5 A2,2 0 1 1 15,9 A2,2 0 1 1 15,5 M9,15 A2,2 0 1 1 9,19 A2,2 0 1 1 9,15"),
        CHECK("M5,12.5 L10,17 L19,7.5"),
        CROSS("M7,7 L17,17 M17,7 L7,17"),
        MINUS("M6,12 H18"),
        PLUS("M12,5 V19 M5,12 H19"),
        LEFT("M14.5,6 L8.5,12 L14.5,18"),
        RIGHT("M9.5,6 L15.5,12 L9.5,18"),
        DOWN("M6,9.5 L12,15.5 L18,9.5"),
        SEND("M12,19 V5 M6,11 L12,5 L18,11"),
        BARCODE("M4,8 V5 H7 M17,5 H20 V8 M20,16 V19 H17 M7,19 H4 V16 M8,9 V15 M10.7,9 V15 M13.3,9 V15 M16,9 V15"),
        CLOUD("M7,18 A4,4 0 0 1 6.6,10 A5.5,5.5 0 0 1 17,9.2 A4.4,4.4 0 0 1 17,18 Z"),
        LAPTOP("M6,5 H18 V15 H6 Z M3,19 H21"),
        SCALE("M8,4 H16 Q20,4 20,8 V16 Q20,20 16,20 H8 Q4,20 4,16 V8 Q4,4 8,4 M8,10 A5.5,5.5 0 0 1 16,10 M12,12.5 L13.6,9.9"),
        FLAG("M6,21 V4 H17 L15,8 L17,12 H6"),
        BELL("M6,16 V11 A6,6 0 0 1 18,11 V16 L20,18 H4 Z M10,21 H14"),
        SPARK("M12,4 L14,10 L20,12 L14,14 L12,20 L10,14 L4,12 L10,10 Z"),
        DOC("M7,3 H14 L18,7 V21 H7 Z M10,12 H15 M10,16 H15"),
        EXPORT("M12,4 V14 M7.5,10 L12,14.5 L16.5,10 M5,19 H19"),
        PLUG("M9,4 V9 M15,4 V9 M7,9 H17 V12 A5,5 0 0 1 7,12 Z M12,17 V21");

        private final String outline;

        Kind(String outline) { this.outline = outline; }
    }

    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path outline;

    JournalIcon(Context context, Kind kind, int color) {
        super(context);
        outline = PathParser.createPathFromPathData(kind.outline);
        ink.setColor(color);
        ink.setStrokeWidth(1.8f);
        ink.setStrokeCap(Paint.Cap.ROUND);
        ink.setStrokeJoin(Paint.Join.ROUND);
        ink.setStyle(Paint.Style.STROKE);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.save();
        canvas.scale(getWidth() / 24f, getHeight() / 24f);
        canvas.drawPath(outline, ink);
        canvas.restore();
    }
}
