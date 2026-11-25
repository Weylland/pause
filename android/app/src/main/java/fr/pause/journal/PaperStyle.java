package fr.pause.journal;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

final class PaperStyle {
    static final int PAPER = 0xFFF3E6CF, SURFACE = 0xFFFFF8EA, FIELD = 0xFFFFFDF6;
    static final int INK = 0xFF3B2A1E, MUTED = 0xFF6B5440, LINE = 0xFFD6BF98;
    static final int BRAND = 0xFFB8431F, ACCENT = 0xFF9A3412, BLUE_INK = 0xFF1F526D, BLUE_EDGE = 0xFF9CC3DC;
    private static Typeface title, body;

    static synchronized Typeface title(Context context) {
        if (title == null) title = new Typeface.Builder(context.getAssets(), "fonts/Fredoka.ttf")
                .setFontVariationSettings("'wght' 600").setWeight(600).build();
        return title;
    }

    static synchronized Typeface body(Context context) {
        if (body == null) body = new Typeface.Builder(context.getAssets(), "fonts/Nunito.ttf")
                .setFontVariationSettings("'wght' 450").setWeight(450).build();
        return body;
    }

    static void field(EditText input) {
        Context context = input.getContext();
        int spacing = dp(context, 11);
        input.setTypeface(body(context));
        input.setTextColor(INK);
        input.setHintTextColor(MUTED);
        input.setTextSize(16);
        input.setBackgroundTintList(null);
        input.setBackground(rounded(context, FIELD, 12, LINE));
        input.setPadding(spacing, spacing, spacing, spacing);
        input.setMinHeight(dp(context, 46));
        input.setOnFocusChangeListener((view, focus) ->
                input.setBackground(rounded(context, FIELD, 12, focus ? ACCENT : LINE)));
    }

    static final class DialogBuilder extends AlertDialog.Builder {
        DialogBuilder(Context context) { super(context); }

        @Override public AlertDialog create() {
            AlertDialog dialog = super.create();
            dialog.setOnShowListener(ignored -> {
                Context context = dialog.getContext();
                if (dialog.getWindow() != null) {
                    int inset = dp(context, 12);
                    dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.InsetDrawable(
                            rounded(context, SURFACE, 22, LINE), inset));
                }
                int titleId = context.getResources().getIdentifier("alertTitle", "id", "android");
                TextView heading = dialog.findViewById(titleId);
                if (heading != null) {
                    heading.setTypeface(title(context));
                    heading.setTextColor(INK);
                }
                TextView message = dialog.findViewById(android.R.id.message);
                if (message != null) {
                    message.setTypeface(body(context));
                    message.setTextColor(MUTED);
                }
                ListView choices = dialog.getListView();
                if (choices != null) {
                    for (int i = 0; i < choices.getChildCount(); i++) choice(choices.getChildAt(i));
                    choices.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
                        @Override public void onChildViewAdded(View parent, View child) { choice(child); }
                        @Override public void onChildViewRemoved(View parent, View child) {}
                    });
                }
                for (int id : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
                    TextView button = dialog.getButton(id);
                    if (button == null) continue;
                    button.setTypeface(title(context));
                    button.setTextColor(id == AlertDialog.BUTTON_POSITIVE ? ACCENT : INK);
                    button.setAllCaps(false);
                }
            });
            return dialog;
        }

        private static void choice(View view) {
            if (!(view instanceof TextView)) return;
            ((TextView) view).setTypeface(body(view.getContext()));
            ((TextView) view).setTextColor(INK);
        }
    }

    private static GradientDrawable rounded(Context context, int fill, int radius, int stroke) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(context, radius));
        shape.setStroke(dp(context, 1), stroke);
        return shape;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static final class Backdrop extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float scale;

        Backdrop(Context context) {
            scale = context.getResources().getDisplayMetrics().density;
        }

        @Override public void draw(Canvas canvas) {
            canvas.drawColor(PAPER);
            float width = getBounds().width(), bottom = getBounds().height();
            paint.setColor(0x08674625);
            canvas.drawRect(0, 0, 2 * scale, bottom, paint);
            for (float y = 8 * scale; y < bottom; y += 6 * scale) {
                float margin = 4 * scale + (y % (5 * scale));
                canvas.drawCircle(margin, y, .65f * scale, paint);
                canvas.drawCircle(width - margin, y, .65f * scale, paint);
            }
        }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter filter) {}
        @Override public int getOpacity() { return PixelFormat.OPAQUE; }
    }

    private PaperStyle() {}
}
