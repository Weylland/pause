package fr.pause.journal;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

final class MealComposer extends LinearLayout {
    interface Actions {
        void more();
        void scan();
        void send(String description);
    }

    private final EditText input;

    MealComposer(Context context, Actions actions) {
        super(context);
        setGravity(Gravity.CENTER_VERTICAL);
        setBaselineAligned(false);
        setPadding(dp(4), dp(4), dp(5), dp(4));
        shell(false);
        input = new EditText(context);
        input.setHint("Qu’as-tu mangé ?");
        input.setBackground(null);
        input.setTypeface(PaperStyle.body(context));
        input.setTextColor(PaperStyle.INK);
        input.setHintTextColor(PaperStyle.MUTED);
        input.setTextSize(16);
        input.setPadding(0, dp(8), 0, dp(8));
        input.setMinHeight(dp(46));
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT);
        input.setHorizontallyScrolling(false);
        input.setMaxLines(4);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(700)});
        input.setImeOptions(EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        input.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEND) return false;
            actions.send(text());
            return true;
        });
        input.setOnFocusChangeListener((view, focus) -> shell(focus));
        addView(icon(JournalIcon.Kind.PLUS, "Autres façons d’ajouter", PaperStyle.INK, 0, actions::more));
        addView(input, new LayoutParams(0, -2, 1));
        addView(icon(JournalIcon.Kind.BARCODE, "Scanner un code-barres", PaperStyle.INK, 0, actions::scan));
        FrameLayout send = icon(JournalIcon.Kind.SEND, "Ajouter", 0xFFFFFFFF, PaperStyle.BRAND, () -> actions.send(text()));
        ((LayoutParams) send.getLayoutParams()).setMarginStart(dp(4));
        addView(send);
    }

    String text() { return input.getText().toString().trim(); }

    void setText(String text) {
        input.setText(text);
        input.setSelection(input.length());
    }

    void clear() {
        input.setText("");
        hideKeyboard();
    }

    void hideKeyboard() {
        input.clearFocus();
        getContext().getSystemService(InputMethodManager.class).hideSoftInputFromWindow(getWindowToken(), 0);
    }

    void showError(String message) {
        input.requestFocus();
        input.setError(message);
    }

    private FrameLayout icon(JournalIcon.Kind kind, String label, int color, int fill, Runnable action) {
        FrameLayout button = new FrameLayout(getContext());
        button.setContentDescription(label);
        button.setFocusable(true);
        GradientDrawable shape = rounded(fill, 14);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x229A3412), shape, rounded(0xFFFFFFFF, 14)));
        button.addView(new JournalIcon(getContext(), kind, color), new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
        button.setOnClickListener(view -> action.run());
        button.setLayoutParams(new LayoutParams(dp(46), dp(46)));
        return button;
    }

    private void shell(boolean focused) {
        GradientDrawable shape = rounded(PaperStyle.FIELD, 18);
        shape.setStroke(dp(1.5f), focused ? PaperStyle.ACCENT : PaperStyle.LINE);
        setBackground(shape);
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
