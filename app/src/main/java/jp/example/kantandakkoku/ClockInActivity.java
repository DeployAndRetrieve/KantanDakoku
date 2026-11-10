package jp.example.kantandakkoku;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Launcher entry: always use the visible WebView for a verifiable punch. */
public final class ClockInActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        // Migrate any experimental background setting from v0.3.4.
        new AppPrefs(this).setBackgroundEnabled(false);
        Intent visible = new Intent(this, WebActivity.class);
        visible.putExtra(WebActivity.EXTRA_MODE, AppPrefs.IN);
        startActivity(visible);
        finish();
    }
}
