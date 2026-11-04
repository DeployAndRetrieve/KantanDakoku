package jp.example.kantandakkoku;

import android.content.Context;
import android.content.SharedPreferences;

public final class AppPrefs {
    public static final String IN = "IN";
    public static final String LOGIN = "LOGIN";
    public static final String OUT = "OUT";
    private final SharedPreferences pref;

    public AppPrefs(Context context) {
        pref = context.getSharedPreferences("clock_settings", Context.MODE_PRIVATE);
    }

    public void saveSelector(String kind, String selector, String label) {
        pref.edit().putString(kind + "_selector", selector)
                .putString(kind + "_label", label).apply();
    }

    public boolean backgroundEnabled() { return pref.getBoolean("background_punch", false); }
    public void setBackgroundEnabled(boolean enabled) { pref.edit().putBoolean("background_punch", enabled).apply(); }

    public void saveLastResult(String detail) {
        pref.edit().putString("last_punch_result", detail).apply();
    }
    public String lastResult() { return pref.getString("last_punch_result", "まだありません"); }

    /** elapsedRealtime survives app restarts but not device reboots. */
    public boolean punchedRecently(String kind, long uptime) {
        long last = pref.getLong(kind + "_last_punch_uptime", -1L);
        return last >= 0 && uptime >= last && uptime - last < 90000L;
    }
    public void markPunchAttempt(String kind, long uptime) {
        pref.edit().putLong(kind + "_last_punch_uptime", uptime).apply();
    }

    public String selector(String kind) { return pref.getString(kind + "_selector", ""); }
    public String label(String kind) { return pref.getString(kind + "_label", ""); }
}
