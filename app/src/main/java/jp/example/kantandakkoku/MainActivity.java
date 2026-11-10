package jp.example.kantandakkoku;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Color;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    public static final String EXTRA_ACTION = "shortcut_action";
    private EditText companyField, accountField, passwordField;
    private TextView selectorStatus;
    private SecureStore secure;
    private AppPrefs prefs;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        secure = new SecureStore(this);
        prefs = new AppPrefs(this);
        // The headless WebView approach is disabled in v0.3.5, even for users
        // who opted into it in v0.3.4.
        prefs.setBackgroundEnabled(false);
        refreshPinnedShortcuts();
        String shortAction = getIntent().getStringExtra(EXTRA_ACTION);
        if (AppPrefs.IN.equals(shortAction) || AppPrefs.OUT.equals(shortAction)) {
            startActivity(new Intent(this, AppPrefs.IN.equals(shortAction)
                    ? ClockInActivity.class : ClockOutActivity.class));
            finish();
            return;
        }
        render();
    }

    private int dp(int value) { return (int) (getResources().getDisplayMetrics().density * value + 0.5f); }

    private String installedVersion() {
        try {
            String value = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            return value == null || value.trim().isEmpty() ? "不明" : value;
        } catch (PackageManager.NameNotFoundException ex) {
            return "不明";
        }
    }

    private void render() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(18), dp(18), dp(18), dp(20));
        scroll.addView(layout);
        ScreenInsets.apply(scroll, 0, 0, 0, 0);
        setContentView(scroll);

        TextView title = text("設定", 25, Color.BLACK);
        layout.addView(title);
        // Read the actual installed package version; do not hardcode the label.
        TextView appInfo = text(getString(R.string.app_name) + " v" + installedVersion(), 16, Color.DKGRAY);
        layout.addView(appInfo);
        layout.addView(text("CC-BizMate ワンタップ打刻（試作版）", 13, Color.DKGRAY));
        addSpace(layout, 15);
        layout.addView(text("① ログイン情報を登録", 18, Color.BLACK));
        companyField = input("会社ID", false);
        accountField = input("アカウント", false);
        passwordField = input("パスワード（端末に暗号化保存）", true);
        layout.addView(companyField);
        layout.addView(accountField);
        layout.addView(passwordField);
        SecureStore.Credentials stored = secure.read();
        if (stored != null) {
            companyField.setText(stored.company);
            accountField.setText(stored.account);
            passwordField.setText(stored.password);
        }
        layout.addView(button("認証情報を保存", () -> {
            String company = companyField.getText().toString().trim();
            String account = accountField.getText().toString().trim();
            String password = passwordField.getText().toString();
            if (company.isEmpty() || account.isEmpty() || password.isEmpty()) {
                toast("3項目すべて入力してください");
                return;
            }
            try {
                secure.save(company, account, password);
                toast("端末のKeystoreで暗号化して保存しました");
            } catch (Exception ex) {
                toast("保存できませんでした。端末のロック設定を確認してください");
            }
        }));
        layout.addView(button("ログイン画面を開く（手動確認）", () -> openWeb("MANUAL", false)));
        addSpace(layout, 18);
        layout.addView(text("② 打刻ボタンを学習", 18, Color.BLACK));
        layout.addView(text("ログイン後のサイト画面で該当ボタンを1回タップして登録します。学習中のタップは通常のクリックを抑止します。", 13, Color.DKGRAY));
        layout.addView(button("ログインボタンを登録（自動ログインに失敗する場合）", () -> openWeb(AppPrefs.LOGIN, true)));
        layout.addView(button("出勤ボタンを登録", () -> openWeb(AppPrefs.IN, true)));
        layout.addView(button("退勤ボタンを登録", () -> openWeb(AppPrefs.OUT, true)));
        selectorStatus = text("", 13, Color.DKGRAY);
        layout.addView(selectorStatus);
        addSpace(layout, 18);
        layout.addView(text("③ 勤務実績画面の構造を調べる（参照のみ）", 18, Color.BLACK));
        layout.addView(text("ログインを自動で行い、勤務実績の画面を手動で開いて「画面構造を取得」を押してください。入力欄の値やパスワードは取得しません。画面上の保存・登録・削除ボタンは押さないでください。", 13, Color.DKGRAY));
        layout.addView(button("画面解析モードで開く", () -> openWeb("ANALYZE", false)));
        addSpace(layout, 18);
        layout.addView(text("④ ホーム画面にショートカットを作成", 18, Color.BLACK));
        layout.addView(button("ホーム画面に「出勤」を追加", () -> pinShortcut(AppPrefs.IN)));
        layout.addView(button("ホーム画面に「退勤」を追加", () -> pinShortcut(AppPrefs.OUT)));
        addSpace(layout, 10);
        layout.addView(text("⑤ 打刻方式", 18, Color.BLACK));
        layout.addView(text("通常の画面表示方式で動作します。v0.3.4のバックグラウンド打刻は、画面非表示WebViewの動作が不安定なため停止しました。以前ONにした設定は自動的にOFFへ戻ります。", 13, Color.DKGRAY));
        layout.addView(text("前回のバックグラウンド処理履歴（参考）：" + prefs.lastResult(), 13, Color.DKGRAY));
        addSpace(layout, 10);
        layout.addView(text("ショートカット起動ごとに古いCookieを消して再ログインします。タイムアウトを検出した場合はログイン画面に戻ります。打刻は1回しか試みません。完了記録はサイトで確認してください。", 13, Color.DKGRAY));
        layout.addView(button("保存した認証情報を削除", () -> new AlertDialog.Builder(this)
                .setTitle("認証情報を削除しますか？")
                .setMessage("出勤・退勤ボタンの登録情報は残します。")
                .setNegativeButton("キャンセル", null)
                .setPositiveButton("削除", (d, w) -> {
                    secure.clear();
                    companyField.setText(""); accountField.setText(""); passwordField.setText("");
                    toast("認証情報を削除しました");
                }).show()));
        updateSelectorStatus();
    }

    @Override protected void onResume() {
        super.onResume();
        if (selectorStatus != null) updateSelectorStatus();
    }

    private void updateSelectorStatus() {
        selectorStatus.setText("ログイン：" + (prefs.selector(AppPrefs.LOGIN).isEmpty() ? "未登録（自動判定）" : "登録済：" + prefs.label(AppPrefs.LOGIN))
                + "\n出勤：" + (prefs.selector(AppPrefs.IN).isEmpty() ? "未登録" : "登録済：" + prefs.label(AppPrefs.IN))
                + "\n退勤：" + (prefs.selector(AppPrefs.OUT).isEmpty() ? "未登録" : "登録済：" + prefs.label(AppPrefs.OUT)));
    }

    private EditText input(String hint, boolean password) {
        EditText edit = new EditText(this);
        edit.setSingleLine(true);
        edit.setHint(hint);
        edit.setTextSize(16);
        edit.setInputType(password ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_CLASS_TEXT);
        return edit;
    }
    private TextView text(String text, int size, int color) {
        TextView view = new TextView(this);
        view.setText(text); view.setTextSize(size); view.setTextColor(color);
        view.setPadding(0, dp(5), 0, dp(5));
        return view;
    }
    private Button button(String title, Runnable action) {
        Button btn = new Button(this);
        btn.setAllCaps(false);
        btn.setText(title);
        btn.setOnClickListener(v -> action.run());
        return btn;
    }
    private void addSpace(LinearLayout layout, int dp) {
        View spacer = new View(this);
        layout.addView(spacer, new LinearLayout.LayoutParams(1, dp(dp)));
    }
    private void toast(String msg) { Toast.makeText(this, msg, Toast.LENGTH_LONG).show(); }
    private void openWeb(String kind, boolean learn) {
        Intent intent = new Intent(this, WebActivity.class);
        intent.putExtra(WebActivity.EXTRA_MODE, learn ? "LEARN_" + kind : kind);
        startActivity(intent);
    }
    private void pinShortcut(String kind) {
        if (prefs.selector(kind).isEmpty()) {
            toast("先に" + (AppPrefs.IN.equals(kind) ? "出勤" : "退勤") + "ボタンを登録してください");
            return;
        }
        ShortcutManager mgr = getSystemService(ShortcutManager.class);
        if (mgr == null || !mgr.isRequestPinShortcutSupported()) {
            toast("ホーム画面へのピン留めに対応していません");
            return;
        }
        ShortcutInfo info = shortcutInfo(kind);
        // Launchers may cache an older pin. Update the existing pin instead of
        // requesting another one with the same ID and leaving a stale icon.
        for (ShortcutInfo pinned : mgr.getPinnedShortcuts()) {
            if (info.getId().equals(pinned.getId())) {
                mgr.updateShortcuts(Collections.singletonList(info));
                toast("既存の" + (AppPrefs.IN.equals(kind) ? "出勤" : "退勤") + "ショートカットを更新しました");
                return;
            }
        }
        mgr.requestPinShortcut(info, null);
        toast("ホーム画面でショートカット追加を承認してください");
    }

    private ShortcutInfo shortcutInfo(String kind) {
        final boolean isIn = AppPrefs.IN.equals(kind);
        String title = isIn ? "出勤" : "退勤";
        // Separate launcher activities ensure the launched activity itself has
        // the correct icon and Android 12+ system splash image. Do not use the
        // generic MainActivity launcher for pinned actions.
        Intent launch = new Intent(this, isIn ? ClockInActivity.class : ClockOutActivity.class);
        launch.setAction(Intent.ACTION_VIEW);
        launch.putExtra(WebActivity.EXTRA_MODE, kind);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int icon = isIn ? R.drawable.ic_clockin : R.drawable.ic_clockout;
        return new ShortcutInfo.Builder(this, "punch_" + kind)
                .setShortLabel(title)
                .setLongLabel("CC-BizMate " + title)
                .setIcon(Icon.createWithResource(this, icon))
                .setIntent(launch)
                .build();
    }

    private void refreshPinnedShortcuts() {
        ShortcutManager mgr = getSystemService(ShortcutManager.class);
        if (mgr == null) return;
        List<ShortcutInfo> updates = new ArrayList<>();
        for (ShortcutInfo pinned : mgr.getPinnedShortcuts()) {
            if ("punch_IN".equals(pinned.getId())) updates.add(shortcutInfo(AppPrefs.IN));
            if ("punch_OUT".equals(pinned.getId())) updates.add(shortcutInfo(AppPrefs.OUT));
        }
        if (!updates.isEmpty()) {
            try { mgr.updateShortcuts(updates); }
            catch (RuntimeException ignored) {
                // If launcher refused the refresh, the user can delete and
                // recreate the pins from the settings screen.
            }
        }
    }
}
