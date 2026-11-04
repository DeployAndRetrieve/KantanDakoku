package jp.example.kantandakkoku;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;

/** Stable launcher entry point: preserves the existing pinned shortcut identity. */
public final class ClockInActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        String kind = AppPrefs.IN;
        NotificationManager notifications = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (new AppPrefs(this).backgroundEnabled() && notifications != null && notifications.areNotificationsEnabled()) {
            try {
                Intent service = new Intent(this, BackgroundPunchService.class);
                service.putExtra(BackgroundPunchService.EXTRA_KIND, kind);
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
                else startService(service);
                finish();
                return;
            } catch (RuntimeException ex) {
                // Android may reject the service launch; use the visible fallback.
            }
        }
        Intent visible = new Intent(this, WebActivity.class);
        visible.putExtra(WebActivity.EXTRA_MODE, kind);
        startActivity(visible);
        finish();
    }
}
