package id.hifaki.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

public final class SplashActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable open = () -> {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = Ui.column(this);
        root.setGravity(Gravity.CENTER);
        root.setPadding(Ui.dp(this, 28), 0, Ui.dp(this, 28), 0);
        root.setBackgroundColor(android.graphics.Color.WHITE);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.hifaki_logo);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(logo, new LinearLayout.LayoutParams(Ui.dp(this, 238), Ui.dp(this, 280)));
        TextView subtitle = Ui.text(this, "Skrining hipotermia di lapangan", 16, false, Ui.MUTED);
        subtitle.setGravity(Gravity.CENTER);
        Ui.add(root, subtitle, 28);
        ProgressBar progress = new ProgressBar(this);
        progress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Ui.TEAL));
        root.addView(progress, new LinearLayout.LayoutParams(Ui.dp(this, 32), Ui.dp(this, 32)));
        setContentView(root);
        handler.postDelayed(open, 850);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(open);
        super.onDestroy();
    }
}
