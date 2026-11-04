package jp.example.kantandakkoku;

import android.os.Build;
import android.view.View;
import android.view.WindowInsets;

final class ScreenInsets {
    static void apply(View root, int left, int top, int right, int bottom) {
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                v.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + bars.bottom);
            } else {
                v.setPadding(left + insets.getSystemWindowInsetLeft(),
                        top + insets.getSystemWindowInsetTop(),
                        right + insets.getSystemWindowInsetRight(),
                        bottom + insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        root.requestApplyInsets();
    }
}
