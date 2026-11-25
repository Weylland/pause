package fr.pause.journal;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.widget.TextView;

final class StreakBadge extends TextView {
    static int tier(int days) {
        if (days >= 30) return 3;
        if (days >= 14) return 2;
        return days >= 7 ? 1 : 0;
    }

    StreakBadge(Context context, int days, boolean reached) {
        super(context);
        int tier = tier(days);
        int[] colors = switch (tier) {
            case 3 -> new int[]{0xFFFBE3A0, 0xFFFFF8EA};
            case 2 -> new int[]{0xFFE3DDF5, 0xFFF2EDF9};
            case 1 -> new int[]{0xFFD5EBC6, 0xFFE3F1D8};
            default -> new int[]{0xFFFFF8EA, 0xFFFFF8EA};
        };
        String label = "Série · " + days + (days == 1 ? " jour" : " jours");
        String stars = switch (tier) {
            case 3 -> "✦✦✦  ";
            case 2 -> "✦✦  ";
            case 1 -> "✦  ";
            default -> "";
        };
        setText(stars + label);
        setContentDescription(label);
        setTextSize(12);
        setTextColor(0xFF3B2A1E);
        setTypeface(PaperStyle.title(context));
        GradientDrawable background = new GradientDrawable(GradientDrawable.Orientation.BL_TR, colors);
        background.setCornerRadius(dp(12));
        background.setStroke(dp(1), 0xFFD6BF98);
        setBackground(background);
        setPadding(dp(9), dp(4), dp(9), dp(4));
        if (reached && ValueAnimator.areAnimatorsEnabled()) post(this::celebrate);
    }

    private void celebrate() {
        if (!isAttachedToWindow()) return;
        ObjectAnimator pulse = ObjectAnimator.ofPropertyValuesHolder(this,
                PropertyValuesHolder.ofFloat(SCALE_X, 1f, 1.12f, 1f),
                PropertyValuesHolder.ofFloat(SCALE_Y, 1f, 1.12f, 1f));
        pulse.setDuration(620);
        pulse.start();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
