package jp.example.kantandakkoku;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Experimental, user-initiated attendance operation without a visible Activity.
 * A foreground service is required to keep the process alive while the user
 * returns to the launcher. This does NOT guarantee server-side acceptance.
 *
 * WebView is created and used exclusively on Android's main thread. A WebView
 * without an Activity/window may not work for sites that rely on a visible
 * viewport, geolocation, dialogs, captcha or certain JS frameworks. In those
 * cases the operation fails safely and notifies the user to use the UI.
 */
public final class BackgroundPunchService extends Service {
    public static final String EXTRA_KIND = "punch_kind";
    private static final String HOST = "kintai.ccbizmate.biz";
    private static final String LOGIN_URL = "https://kintai.ccbizmate.biz/C01/C0101Login?ReturnUrl=%2FC03%2FC0301Top";
    private static final String PROGRESS_CHANNEL = "punch_progress_v1";
    private static final String RESULT_CHANNEL = "punch_result_v1";
    private static final int PROGRESS_ID = 3701;
    private static final int RESULT_ID = 3702;
    private static final long MAX_DURATION_MS = 60000L;
    private static final long AFTER_CLICK_DELAY_MS = 9000L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WebView browser;
    private String kind;
    private AppPrefs prefs;
    private boolean finished, loginAttempted, punchAttempted, probing;
    private int probeCount, timeoutRecoveries;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        // START_NOT_STICKY: never reissue an attendance request after process death.
        if (kind != null) {
            updateProgress("処理中です。連続タップによる重複実行は受け付けません。");
            return START_NOT_STICKY;
        }
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        String requestedKind = intent.getStringExtra(EXTRA_KIND);
        if (!AppPrefs.IN.equals(requestedKind) && !AppPrefs.OUT.equals(requestedKind)) {
            stopSelf(); return START_NOT_STICKY;
        }
        kind = requestedKind;
        prefs = new AppPrefs(this);
        createChannels();
        try {
            startForegroundNow("ログイン処理を開始しています…");
        } catch (RuntimeException ex) {
            prefs.setBackgroundEnabled(false);
            prefs.saveLastResult("バックグラウンドサービスを起動できませんでした。通常画面方式へ戻しました。");
            stopSelf();
            return START_NOT_STICKY;
        }
        handler.postDelayed(() -> fail(punchAttempted
                        ? "時間切れです。送信済みの可能性があります。勤怠記録を手動確認してください。"
                        : "処理が時間切れになりました。打刻は確認できません。通常画面で確認してください。"), MAX_DURATION_MS);
        if (prefs.punchedRecently(kind, SystemClock.elapsedRealtime())) {
            fail("直近90秒以内に同じ打刻の送信操作がありました。重複を避けるため自動操作をしません。");
            return START_NOT_STICKY;
        }
        if (prefs.selector(kind).isEmpty() || prefs.label(kind).isEmpty()) {
            fail("打刻ボタンが未登録です。設定画面から登録してください。");
            return START_NOT_STICKY;
        }
        if (new SecureStore(this).read() == null) {
            fail("認証情報が未登録です。設定画面から保存してください。");
            return START_NOT_STICKY;
        }
        try { initializeBrowser(); }
        catch (RuntimeException ex) { fail("画面非表示でのWebView初期化に失敗しました。通常画面で操作してください。"); }
        return START_NOT_STICKY;
    }

    private String actionLabel() { return AppPrefs.OUT.equals(kind) ? "退勤" : "出勤"; }
    private int icon() { return AppPrefs.OUT.equals(kind) ? R.drawable.ic_clockout : R.drawable.ic_clockin; }

    private void createChannels() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null || Build.VERSION.SDK_INT < 26) return;
        NotificationChannel progress = new NotificationChannel(PROGRESS_CHANNEL, "打刻処理中", NotificationManager.IMPORTANCE_LOW);
        progress.setDescription("バックグラウンド打刻の処理状況");
        NotificationChannel result = new NotificationChannel(RESULT_CHANNEL, "打刻処理結果", NotificationManager.IMPORTANCE_DEFAULT);
        result.setDescription("打刻操作の成否・確認が必要な状態");
        manager.createNotificationChannel(progress);
        manager.createNotificationChannel(result);
    }

    private Notification notification(String channel, String message, boolean ongoing) {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pending = PendingIntent.getActivity(this, 8, open, piFlags);
        return new Notification.Builder(this, channel)
                .setSmallIcon(icon())
                .setContentTitle("KantanDakoku - " + actionLabel())
                .setContentText(message)
                .setStyle(new Notification.BigTextStyle().bigText(message))
                .setContentIntent(pending)
                .setAutoCancel(!ongoing)
                .setOngoing(ongoing)
                .setCategory(Notification.CATEGORY_STATUS)
                .build();
    }

    private void startForegroundNow(String detail) {
        Notification progress = notification(PROGRESS_CHANNEL, detail, true);
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(PROGRESS_ID, progress, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(PROGRESS_ID, progress);
        }
    }

    private void updateProgress(String detail) {
        if (finished) return;
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            try { manager.notify(PROGRESS_ID, notification(PROGRESS_CHANNEL, detail, true)); }
            catch (RuntimeException ignored) { /* Android notification settings can change mid-run. */ }
        }
    }

    private void end(String message) {
        if (finished) return;
        finished = true;
        handler.removeCallbacksAndMessages(null);
        if (browser != null) {
            browser.stopLoading();
            browser.setWebViewClient(null);
            browser.setWebChromeClient(null);
            browser.destroy();
            browser = null;
        }
        // Clear local cookies only AFTER allowing enough time for the click's
        // navigation/XHR. No claim is made that this logs out the server session.
        try { CookieManager.getInstance().removeAllCookies(result -> CookieManager.getInstance().flush()); }
        catch (RuntimeException ignored) { }
        prefs.saveLastResult(actionLabel() + "：" + message);
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            try { manager.notify(RESULT_ID, notification(RESULT_CHANNEL, message, false)); }
            catch (RuntimeException ignored) { /* Result remains in local settings. */ }
        }
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
        stopSelf();
    }

    private void fail(String message) { end("確認が必要です: " + message); }

    private void initializeBrowser() {
        browser = new WebView(this);
        WebSettings settings = browser.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        // Give layout-based selectors a viewport even though no Activity is shown.
        int width = getResources().getDisplayMetrics().widthPixels;
        int height = getResources().getDisplayMetrics().heightPixels;
        browser.measure(android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(height, android.view.View.MeasureSpec.EXACTLY));
        browser.layout(0, 0, width, height);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(browser, false);
        browser.setWebChromeClient(new WebChromeClient() {
            @Override public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                callback.invoke(origin, false, false);
                handler.post(() -> fail("このサイトは位置情報を要求しました。通常画面から操作してください。"));
            }
            @Override public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                result.cancel();
                handler.post(() -> fail("サイトが確認ダイアログを表示しました。通常画面で操作してください。"));
                return true;
            }
            @Override public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                result.cancel();
                handler.post(() -> fail("サイトが確認ダイアログを表示しました。通常画面で操作してください。"));
                return true;
            }
            @Override public boolean onJsPrompt(WebView view, String url, String message, String defaultValue, JsPromptResult result) {
                result.cancel();
                handler.post(() -> fail("サイトが入力ダイアログを表示しました。通常画面で操作してください。"));
                return true;
            }
        });
        browser.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                if (!trusted(request.getUrl())) {
                    handler.post(() -> fail("外部サイトへの遷移が必要です。通常画面で操作してください。"));
                    return true;
                }
                return false;
            }
            @Override public void onReceivedSslError(WebView view, SslErrorHandler callback, SslError error) {
                callback.cancel(); handler.post(() -> fail("SSL証明書エラーで中止しました。"));
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) handler.post(() -> fail("通信エラーが発生しました。通常画面で確認してください。"));
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (finished || !trustedUrl(url)) return;
                if (punchAttempted) {
                    // A navigation after clicking does not prove the server accepted it.
                    handler.postDelayed(() -> end("送信操作を試みました。サーバーの打刻記録は未確認です。"), 1500L);
                    return;
                }
                handler.postDelayed(() -> inspectPage(url), 500L);
            }
        });
        CookieManager cookies = CookieManager.getInstance();
        cookies.removeAllCookies(removed -> {
            if (finished || browser == null) return;
            cookies.flush();
            updateProgress("新しいセッションでログインしています…");
            browser.loadUrl(LOGIN_URL);
        });
    }

    private boolean trusted(Uri uri) {
        return uri != null && "https".equalsIgnoreCase(uri.getScheme())
                && HOST.equalsIgnoreCase(uri.getHost());
    }
    private boolean trustedUrl(String url) { return url != null && trusted(Uri.parse(url)); }
    private String unpack(String raw) {
        try { return new JSONArray("[" + raw + "]").getString(0); }
        catch (Exception ignored) { return ""; }
    }

    private String readAsset(String assetName) throws Exception {
        try (InputStream stream = getAssets().open(assetName)) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] part = new byte[4096];
            int len;
            while ((len = stream.read(part)) != -1) buffer.write(part, 0, len);
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private void inspectPage(String url) {
        if (finished || browser == null || !url.equals(browser.getUrl())) return;
        String script = "(function(){const t=(document.body?document.body.innerText:'').slice(0,7000);"
                + "if((/セッション\\s*(?:の)?\\s*タイムアウト|セッションアウト/i.test(t)"
                + "&&/ログインページ|再度ログイン|再ログイン/i.test(t))"
                + "||/session\\s*(?:has\\s*)?(?:timed\\s*out|timeout|expired)/i.test(t)) return 'TIMEOUT';"
                + "if(location.pathname.toLowerCase().includes('/c01/c0101login'))return 'LOGIN';"
                + "const pwd=Array.from(document.querySelectorAll('input[type=password]')).some(e=>!e.disabled&&e.getClientRects().length>0);"
                + "return pwd?'LOGIN':'OTHER';})();";
        browser.evaluateJavascript(script, raw -> {
            if (finished || browser == null || !url.equals(browser.getUrl())) return;
            String result = unpack(raw);
            if ("TIMEOUT".equals(result)) {
                if (punchAttempted) { fail("打刻後にセッションが切れました。記録済みの可能性があるため再送しません。"); return; }
                if (timeoutRecoveries++ >= 1) { fail("再ログインしてもセッションが切れます。"); return; }
                loginAttempted = false;
                probing = false;
                probeCount = 0;
                updateProgress("セッション切れのため再ログイン中です…");
                browser.loadUrl(LOGIN_URL);
            } else if ("LOGIN".equals(result)) {
                attemptLogin();
            } else {
                probePunch();
            }
        });
    }

    private void attemptLogin() {
        if (finished || browser == null) return;
        if (loginAttempted) { fail("ログイン画面から進めませんでした。認証情報と画面構造を確認してください。"); return; }
        SecureStore.Credentials data = new SecureStore(this).read();
        if (data == null) { fail("認証情報が未登録です。"); return; }
        loginAttempted = true;
        try {
            String js = readAsset("login_autosubmit.js")
                    .replace("__COMPANY_JSON__", JSONObject.quote(data.company))
                    .replace("__ACCOUNT_JSON__", JSONObject.quote(data.account))
                    .replace("__PASSWORD_JSON__", JSONObject.quote(data.password))
                    .replace("__LOGIN_SELECTOR_JSON__", JSONObject.quote(prefs.selector(AppPrefs.LOGIN)))
                    .replace("__LOGIN_LABEL_JSON__", JSONObject.quote(prefs.label(AppPrefs.LOGIN)));
            browser.evaluateJavascript(js, raw -> {
                if (finished) return;
                String result = unpack(raw);
                if (result.startsWith("SUBMITTED:")) updateProgress("ログイン結果を確認しています…");
                else if (result.startsWith("NEED_MANUAL:") || result.startsWith("FILLED_ONLY:") || result.startsWith("ERROR:"))
                    fail("ログインを自動送信できませんでした。通常画面で操作してください。");
                // Navigation can interrupt evaluateJavascript's callback; the
                // page-finished handler or overall timeout handles that case.
            });
        } catch (Exception ignored) { fail("ログイン処理の読み込みに失敗しました。"); }
    }

    private void probePunch() {
        if (finished || punchAttempted || probing || browser == null) return;
        if (++probeCount > 8) { fail("打刻ボタンが見つかりません。通常画面で操作してください。"); return; }
        String url = browser.getUrl();
        probing = true;
        String selector = JSONObject.quote(prefs.selector(kind));
        String label = JSONObject.quote(prefs.label(kind));
        String check = "(function(){const e=document.querySelector(" + selector + ");"
                + "if(!e)return 'NOT_FOUND';if(e.disabled||e.getClientRects().length===0)return 'HIDDEN';"
                + "const t=(e.innerText||e.value||e.getAttribute('aria-label')||'').replace(/\\s+/g,' ').trim();"
                + "return t===" + label + "?'READY':'LABEL_CHANGED';})();";
        browser.evaluateJavascript(check, raw -> {
            probing = false;
            if (finished || punchAttempted || browser == null || !url.equals(browser.getUrl())) return;
            String result = unpack(raw);
            if ("READY".equals(result)) {
                punchAttempted = true; // Arm one-shot BEFORE dispatching the click.
                prefs.markPunchAttempt(kind, SystemClock.elapsedRealtime());
                updateProgress(actionLabel() + "ボタンを1回だけ操作しています…");
                String click = "(function(){const e=document.querySelector(" + selector + ");"
                        + "if(!e||e.disabled||e.getClientRects().length===0)return 'NOT_CLICKED';"
                        + "const t=(e.innerText||e.value||e.getAttribute('aria-label')||'').replace(/\\s+/g,' ').trim();"
                        + "if(t!==" + label + ")return 'NOT_CLICKED';e.click();return 'CLICKED';})();";
                browser.evaluateJavascript(click, response -> {
                    if (finished) return;
                    if ("NOT_CLICKED".equals(unpack(response))) {
                        fail("ボタンが変更され操作できませんでした。自動再送はしません。");
                    } else {
                        handler.postDelayed(() -> end("送信操作を試みました。サーバーの打刻記録は未確認です。"), AFTER_CLICK_DELAY_MS);
                    }
                });
            } else if ("LABEL_CHANGED".equals(result)) {
                fail("登録した打刻ボタンと表示が違います。安全のため停止しました。");
            } else if (probeCount < 8) {
                handler.postDelayed(this::probePunch, 1000L);
            } else {
                fail("打刻ボタンを発見できませんでした。通常画面で操作してください。");
            }
        });
    }

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (browser != null) {
            browser.stopLoading();
            browser.setWebViewClient(null);
            browser.setWebChromeClient(null);
            browser.destroy(); browser = null;
        }
        super.onDestroy();
    }
}
