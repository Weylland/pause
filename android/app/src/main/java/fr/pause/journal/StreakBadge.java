package fr.pause.journal;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Typeface;
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
            case 3 -> new int[]{0xFFFFD992, 0xFFFFEDB3};
            case 2 -> new int[]{0xFFBEDA87, 0xFFE8EFAE};
            case 1 -> new int[]{0xFFABCDB5, 0xFFDDEBCE};
            default -> new int[]{0xFF375D45, 0xFF375D45};
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
        setTextColor(tier == 0 ? 0xFFE4EFE4 : 0xFF22382B);
        setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        GradientDrawable background = new GradientDrawable(GradientDrawable.Orientation.BL_TR, colors);
        background.setCornerRadius(dp(12));
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
