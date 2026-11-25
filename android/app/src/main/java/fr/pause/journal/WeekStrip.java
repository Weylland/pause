package fr.pause.journal;

import android.content.Context;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.LinearLayout;

import java.util.function.IntConsumer;

final class WeekStrip extends LinearLayout {
    private final IntConsumer swipe;
    private final int slop;
    private float startX, startY;

    WeekStrip(Context context, IntConsumer swipe) {
        super(context);
        this.swipe = swipe;
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
        setBaselineAligned(false);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            startX = event.getX();
            startY = event.getY();
        }
        float moved = Math.abs(event.getX() - startX);
        boolean dragging = event.getActionMasked() == MotionEvent.ACTION_MOVE && moved > slop && moved > Math.abs(event.getY() - startY);
        if (dragging) getParent().requestDisallowInterceptTouchEvent(true);
        return dragging;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            float moved = event.getX() - startX;
            if (Math.abs(moved) > getWidth() / 6f) swipe.accept(moved < 0 ? 1 : -1);
            else performClick();
        }
        return true;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }
}
