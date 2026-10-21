package id.hifaki.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

final class Ui {
    static final int NAVY = Color.rgb(16, 32, 66);
    static final int TEAL = Color.rgb(18, 128, 136);
    static final int RED = Color.rgb(181, 18, 38);
    static final int MUTED = Color.rgb(91, 108, 127);
    static final int BG = Color.rgb(247, 249, 252);
    private Ui() { }

    static int dp(Context c, int d) { return (int) (d * c.getResources().getDisplayMetrics().density + .5f); }

    static GradientDrawable bg(Context c, int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radius));
        return d;
    }

    static TextView text(Context c, String s, int size, boolean bold, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setLineSpacing(dp(c, 3), 1f);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static LinearLayout card(Context c) {
        LinearLayout l = column(c);
        l.setPadding(dp(c, 18), dp(c, 18), dp(c, 18), dp(c, 18));
        l.setBackground(bg(c, Color.WHITE, 20));
        l.setElevation(dp(c, 2));
        return l;
    }

    static void add(LinearLayout parent, View child, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = dp(parent.getContext(), bottom);
        parent.addView(child, p);
    }

    static Button button(Context c, String label, int color) {
        Button b = new Button(c);
        b.setText(label);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setTextColor(Color.WHITE);
        b.setBackground(bg(c, color, 14));
        b.setMinHeight(dp(c, 50));
        b.setGravity(Gravity.CENTER);
        return b;
    }

    static EditText input(Context c, String hint) {
        EditText t = new EditText(c);
        t.setSingleLine(true);
        t.setTextSize(15);
        t.setHint(hint);
        t.setTextColor(NAVY);
        t.setPadding(dp(c, 14), dp(c, 8), dp(c, 14), dp(c, 8));
        t.setBackground(bg(c, BG, 12));
        t.setMinHeight(dp(c, 52));
        return t;
    }
}
