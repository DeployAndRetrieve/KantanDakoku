package jp.example.kantandakkoku;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.widget.Toast;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import android.Manifest;
import android.content.pm.PackageManager;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.GeolocationPermissions;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebSettings;
import android.net.http.SslError;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.view.View;
import org.json.JSONObject;

/** Web automation operates ONLY on the known CC-BizMate origin. */
public class WebActivity extends Activity {
    public static final String EXTRA_MODE = "mode";
    private static final String HOST = "kintai.ccbizmate.biz";
    private static final String LOGIN_URL = "https://kintai.ccbizmate.biz/C01/C0101Login?ReturnUrl=%2FC03%2FC0301Top";
    private static final String TOP_URL = "https://kintai.ccbizmate.biz/C03/C0301Top";
    private static final String MANUAL = "MANUAL";
    private static final String ANALYZE = "ANALYZE";
    private static final String LEARN_PREFIX = "LEARN_";
    private WebView browser;
    private TextView status;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private AppPrefs preferences;
    private SecureStore secure;
    private String mode, kind;
    private boolean learning, stopped, loginAttempted, punchAttempted, probing, showingCapture;
    private boolean inspectOnly;
    private int probeCount, timeoutRecoveries;
    private GeolocationPermissions.Callback locationCallback;
    private String pendingLocationOrigin;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = new AppPrefs(this);
        secure = new SecureStore(this);
        mode = getIntent().getStringExtra(EXTRA_MODE);
        if (mode == null) mode = MANUAL;
        learning = mode.startsWith(LEARN_PREFIX);
        inspectOnly = ANALYZE.equals(mode);
        kind = learning ? mode.substring(LEARN_PREFIX.length()) : mode;
        buildScreen();
        if (MANUAL.equals(mode)) {
            browser.loadUrl(LOGIN_URL);
        } else if (learning) {
            // For login-button learning, force a clean login page instead of an
            // authenticated redirect. For punch-button learning, use the session.
            if (AppPrefs.LOGIN.equals(kind)) beginFreshSession();
            else browser.loadUrl(TOP_URL);
        } else {
            if (!inspectOnly && preferences.selector(kind).isEmpty()) {
                setStatus("打刻ボタンが未登録です。設定画面で登録してください。");
            }
            // A direct visit to TOP with an expired session displays CC-BizMate's
            // timeout screen. Always start automatic punches with fresh cookies
            // and a real login flow instead of following an old WebView session.
            beginFreshSession();
        }
    }

    private int dp(int size) { return (int) (getResources().getDisplayMetrics().density * size + 0.5f); }
    private String actionLabel() { return inspectOnly ? "画面解析" : (AppPrefs.LOGIN.equals(kind) ? "ログイン" : AppPrefs.IN.equals(kind) ? "出勤" : "退勤"); }

    private void buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        ScreenInsets.apply(root, 0, 0, 0, 0);

        LinearLayout tools = new LinearLayout(this);
        tools.setPadding(dp(8), dp(7), dp(8), dp(4));
        tools.setOrientation(LinearLayout.VERTICAL);
        if (AppPrefs.IN.equals(kind) || AppPrefs.OUT.equals(kind)) {
            // Make the intended action explicit even when the OS/launcher
            // displays the generic application icon in the task switcher.
            LinearLayout actionHeader = new LinearLayout(this);
            actionHeader.setOrientation(LinearLayout.HORIZONTAL);
            actionHeader.setGravity(android.view.Gravity.CENTER_VERTICAL);
            ImageView actionIcon = new ImageView(this);
            actionIcon.setImageResource(AppPrefs.OUT.equals(kind)
                    ? R.drawable.ic_clockout : R.drawable.ic_clockin);
            actionHeader.addView(actionIcon, new LinearLayout.LayoutParams(dp(38), dp(38)));
            TextView actionTitle = new TextView(this);
            actionTitle.setText((AppPrefs.OUT.equals(kind) ? "退勤" : "出勤")
                    + (learning ? "ボタンの登録" : "打刻処理"));
            actionTitle.setTextSize(19);
            actionTitle.setTextColor(Color.BLACK);
            actionTitle.setPadding(dp(10), 0, 0, 0);
            actionHeader.addView(actionTitle);
            tools.addView(actionHeader);
        }
        status = new TextView(this);
        status.setTextSize(14);
        status.setTextColor(Color.BLACK);
        setStatus(learning ? "学習モード：ログイン後、" + actionLabel() + "ボタンを一度タップしてください。" :
                MANUAL.equals(mode) ? "手動確認：普段どおりログイン・操作できます。" :
                inspectOnly ? "参照用の画面解析：ログイン後、勤務実績画面を開き「画面構造を取得」を押してください。実績の登録・更新・削除は行いません。" :
                        actionLabel() + "：サイト接続中です。二重打刻防止のため自動クリックは最大1回です。");
        tools.addView(status);

        LinearLayout actions = new LinearLayout(this);
        Button stop = new Button(this);
        stop.setAllCaps(false);
        stop.setText("自動操作を停止");
        stop.setOnClickListener(v -> {
            stopped = true;
            setStatus("自動操作を停止しました。以下は手動操作のみです。");
        });
        actions.addView(stop, new LinearLayout.LayoutParams(0, dp(47), 1));
        Button login = new Button(this);
        login.setAllCaps(false);
        login.setText("ログイン画面へ");
        login.setOnClickListener(v -> {
            if (punchAttempted) {
                stopped = true; // Never re-arm an uncertain punch.
                setStatus("打刻処理後です。結果を確認するまで再打刻しないでください。以下は手動操作です。");
            } else {
                stopped = true; // Manual recovery only: no surprise automatic click.
                setStatus("ログイン画面を開きました。手動でログインしてください。自動操作は停止しています。");
            }
            browser.loadUrl(LOGIN_URL);
        });
        Button reload = new Button(this);
        reload.setAllCaps(false);
        reload.setText("再読込");
        reload.setOnClickListener(v -> {
            // This does not re-arm an already fired punch or login attempt.
            browser.reload();
        });
        actions.addView(reload, new LinearLayout.LayoutParams(0, dp(47), 1));
        tools.addView(actions);
        tools.addView(login, new LinearLayout.LayoutParams(-1, dp(45)));
        if (inspectOnly || AppPrefs.OUT.equals(kind)) {
            Button inspect = new Button(this);
            inspect.setAllCaps(false);
            inspect.setText("画面構造を取得（入力値は取得しない）");
            inspect.setOnClickListener(v -> inspectCurrentScreen());
            tools.addView(inspect, new LinearLayout.LayoutParams(-1, dp(48)));
        }
        root.addView(tools);

        browser = new WebView(this);
        root.addView(browser, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        browser.getSettings().setJavaScriptEnabled(true);
        browser.getSettings().setGeolocationEnabled(true);
        browser.getSettings().setDomStorageEnabled(true);
        browser.getSettings().setCacheMode(WebSettings.LOAD_NO_CACHE);
        browser.getSettings().setAllowFileAccess(false);
        browser.getSettings().setAllowContentAccess(false);
        browser.getSettings().setJavaScriptCanOpenWindowsAutomatically(false);
        browser.getSettings().setSupportMultipleWindows(false);
        browser.getSettings().setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(browser, false);
        browser.setWebChromeClient(new WebChromeClient() {
            @Override public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                if (!trustedUrl(origin) || stopped) {
                    callback.invoke(origin, false, false);
                    return;
                }
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                        checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    callback.invoke(origin, true, false);
                } else {
                    locationCallback = callback;
                    pendingLocationOrigin = origin;
                    new AlertDialog.Builder(WebActivity.this)
                            .setTitle("位置情報の利用")
                            .setMessage("CC-BizMateが打刻時に位置情報を要求しました。端末の位置情報へのアクセスを許可しますか？")
                            .setNegativeButton("許可しない", (d, w) -> {
                                callback.invoke(origin, false, false);
                                locationCallback = null;
                            })
                            .setPositiveButton("許可する", (d, w) -> requestPermissions(new String[] {
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION
                            }, 22)).show();
                }
            }
        });
        if (learning) browser.addJavascriptInterface(new LearningBridge(), "KantanDakokuCapture");
        browser.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                Uri uri = request.getUrl();
                if (trusted(uri)) return false;
                if ("https".equalsIgnoreCase(uri.getScheme())) {
                    // External links are opened in an ordinary browser, not in this credential-bearing WebView.
                    try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); }
                    catch (Exception ignored) { setStatus("外部ページを開けませんでした。"); }
                } else {
                    setStatus("安全でないリンクへの遷移を防止しました。");
                }
                return true;
            }

            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (!trusted(Uri.parse(url)) || stopped) return;
                handler.postDelayed(() -> {
                    if (stopped || browser == null || !url.equals(browser.getUrl())) return;
                    inspectPage(url);
                }, 500);
            }

            @Override public void onReceivedSslError(WebView view, SslErrorHandler callback, SslError error) {
                callback.cancel();
                stopped = true;
                setStatus("SSL証明書エラー：接続を中止しました。手動で証明書を確認してください。");
            }

            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) setStatus("ページ読込に失敗しました。通信状態を確認してください。");
            }
        });
    }

    /**
     * removeAllCookies is asynchronous. Do not load the login form until
     * deletion completes or a stale ASP.NET/session cookie may race the request.
     */
    private void beginFreshSession() {
        setStatus(actionLabel() + "：古いセッションを破棄し、ログインから開始します…");
        CookieManager cookies = CookieManager.getInstance();
        cookies.removeAllCookies(removed -> {
            if (stopped || browser == null || isFinishing() || isDestroyed()) return;
            cookies.flush();
            setStatus(actionLabel() + "：ログインページを開いています…");
            browser.loadUrl(LOGIN_URL);
        });
    }

    /** Detect a timed-out document by what it displays, not just its URL.
     * The timeout path is installation/version-dependent.
     */
    private void inspectPage(String loadedUrl) {
        if (stopped || browser == null || !trustedUrl(loadedUrl)) return;
        String js = """
                (function() {
                  const t = (document.body ? document.body.innerText : '').slice(0, 7000);
                  const timeout = /セッション\\s*(?:の)?\\s*タイムアウト|セッションアウト/i.test(t)
                      && /ログインページ|再度ログイン|再ログイン/i.test(t);
                  if (timeout || /session\\s*(?:has\\s*)?(?:timed\\s*out|timeout|expired)/i.test(t))
                    return 'TIMEOUT';
                  if (location.pathname.toLowerCase().includes('/c01/c0101login')) return 'LOGIN';
                  const pwd = Array.from(document.querySelectorAll('input[type=password]'))
                      .some(e => !e.disabled && e.getClientRects().length > 0);
                  const login = Array.from(document.querySelectorAll('button,input[type=submit],input[type=button],a'))
                      .some(e => /^(?:ログイン|login)$/i.test((e.innerText || e.value || '').trim())
                                 && e.getClientRects().length > 0);
                  return pwd && login ? 'LOGIN' : 'OTHER';
                })();
                """;
        browser.evaluateJavascript(js, raw -> {
            if (stopped || browser == null || !loadedUrl.equals(browser.getUrl())) return;
            String result = unpack(raw);
            if ("TIMEOUT".equals(result)) {
                handleSessionTimeout();
            } else if ("LOGIN".equals(result) || isLoginPage(browser.getUrl())) {
                probeCount = 0;
                if (learning && AppPrefs.LOGIN.equals(kind)) attachCapture();
                else if (!MANUAL.equals(mode)) attemptLogin();
            } else if (learning) {
                attachCapture();
            } else if (inspectOnly) {
                setStatus("ログイン済みです。勤務実績画面を手動で開き、「画面構造を取得」を押してください。取得するのは構造だけです。");
            } else if (!MANUAL.equals(mode)) {
                probePunch();
            }
        });
    }

    private void handleSessionTimeout() {
        if (stopped) return;
        if (punchAttempted) {
            // A server may have accepted the punch before rendering an error.
            stopped = true;
            setStatus("打刻ボタン操作後にセッション切れを検出しました。記録済みの可能性があるため再送しません。勤怠記録を手動確認してください。");
            return;
        }
        if (MANUAL.equals(mode)) {
            if (timeoutRecoveries++ >= 1) {
                stopped = true;
                setStatus("ログイン画面でもセッションエラーが続いています。接続先・認証設定を確認してください。");
                return;
            }
            setStatus("セッションが切れました。ログイン画面に戻ります。");
            browser.loadUrl(LOGIN_URL);
            return;
        }
        if (timeoutRecoveries >= 1) {
            stopped = true;
            setStatus("再ログイン後もセッションタイムアウトが発生しました。自動処理を停止します。「ログイン画面へ」から手動確認してください。");
            return;
        }
        timeoutRecoveries++;
        loginAttempted = false; // Only a confirmed TIMEOUT can re-arm login.
        probing = false;
        probeCount = 0;
        setStatus("セッションタイムアウトを検出しました。ログイン画面へ戻り、1回だけ再ログインします…");
        browser.loadUrl(LOGIN_URL);
    }

    private boolean trusted(Uri uri) {
        return uri != null && "https".equalsIgnoreCase(uri.getScheme()) && HOST.equalsIgnoreCase(uri.getHost());
    }
    private boolean trustedUrl(String url) { return url != null && trusted(Uri.parse(url)); }
    private boolean isLoginPage(String url) { return url != null && url.contains("/C01/C0101Login"); }
    private void setStatus(String message) { if (status != null) status.setText(message); }

    private String readAsset(String assetName) throws Exception {
        try (InputStream in = getAssets().open(assetName)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = in.read(chunk)) != -1) bytes.write(chunk, 0, read);
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private void attemptLogin() {
        if (stopped || browser == null) return;
        if (loginAttempted) {
            setStatus("ログイン後もログイン画面です。認証情報や画面を確認してください。再送信はしません。");
            return;
        }
        SecureStore.Credentials data = secure.read();
        if (data == null) {
            setStatus("認証情報が未登録です。アプリの設定画面で保存してください。");
            return;
        }
        // Once per activity. A second submit could lock an account or produce duplicate requests.
        loginAttempted = true;
        try {
            String js = readAsset("login_autosubmit.js")
                    .replace("__COMPANY_JSON__", JSONObject.quote(data.company))
                    .replace("__ACCOUNT_JSON__", JSONObject.quote(data.account))
                    .replace("__PASSWORD_JSON__", JSONObject.quote(data.password))
                    .replace("__LOGIN_SELECTOR_JSON__", JSONObject.quote(preferences.selector(AppPrefs.LOGIN)))
                    .replace("__LOGIN_LABEL_JSON__", JSONObject.quote(preferences.label(AppPrefs.LOGIN)));
            browser.evaluateJavascript(js, raw -> {
                if (stopped || browser == null) return;
                String result = unpack(raw);
                if (result.startsWith("SUBMITTED:")) {
                    setStatus("ログインボタンの自動送信を実行しました（" + result.substring(10) + "）。ログイン結果を確認中です。");
                } else if (result.startsWith("NEED_MANUAL:")) {
                    setStatus("ログイン入力欄を安全に特定できませんでした（" + result + "）。通常画面から手動ログインしてください。");
                } else if (result.startsWith("FILLED_ONLY:")) {
                    setStatus("3項目を入力しましたが、送信先が曖昧なため自動送信を停止しました。画面の解析結果をお知らせください。");
                } else {
                    setStatus("ログイン自動送信に失敗しました（" + result + "）。手動でログインしてください。");
                }
            });
        } catch (Exception ex) {
            setStatus("ログインスクリプトを読み込めませんでした。アプリを再インストールしてください。");
        }
    }

    /** Read-only inspection: no DOM values or site requests are included in the report. */
    private void inspectCurrentScreen() {
        if (browser == null || !trustedUrl(browser.getUrl())) {
            setStatus("CC-BizMateの画面でのみ解析できます。");
            return;
        }
        try {
            browser.evaluateJavascript(readAsset("inspect_dom.js"), raw -> {
                if (isFinishing() || isDestroyed()) return;
                String report = unpack(raw);
                if (!report.startsWith("{")) {
                    setStatus("解析できませんでした。ページを読み込んでから再試行してください。");
                    return;
                }
                android.widget.ScrollView scrolling = new android.widget.ScrollView(this);
                TextView content = new TextView(this);
                content.setText(report);
                content.setTextSize(11);
                content.setTextIsSelectable(true);
                content.setPadding(dp(14), dp(14), dp(14), dp(14));
                scrolling.addView(content);
                new AlertDialog.Builder(this)
                        .setTitle("画面構造のみ（読み取り専用）")
                        .setView(scrolling)
                        .setNegativeButton("閉じる", null)
                        .setPositiveButton("構造情報をコピー", (d, which) -> {
                            ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                            if (cb != null) cb.setPrimaryClip(ClipData.newPlainText("画面構造（入力値なし）", report));
                            Toast.makeText(this, "項目名・ボタン名だけをコピーしました。共有前に確認してください。", Toast.LENGTH_LONG).show();
                        }).show();
            });
        } catch (Exception ex) {
            setStatus("構造解析のプログラムを読み込めませんでした。");
        }
    }

    private void attachCapture() {
        if (stopped) return;
        setStatus("学習モード：" + actionLabel() + "ボタンをタップしてください。タップ時の通常クリックは抑止します。");
        String js = """
                (function () {
                  if (window.top !== window.self) return;
                  if (window.__kantanCaptureAttached) return;
                  window.__kantanCaptureAttached = true;
                  const css = e => {
                    if (e.id) return '#' + CSS.escape(e.id);
                    const path = [];
                    while (e && e.nodeType === 1 && e !== document.documentElement) {
                      if (e.id) { path.unshift('#' + CSS.escape(e.id)); break; }
                      let n = 1;
                      for (let s = e.previousElementSibling; s; s = s.previousElementSibling)
                        if (s.tagName === e.tagName) n++;
                      path.unshift(e.tagName.toLowerCase() + ':nth-of-type(' + n + ')');
                      e = e.parentElement;
                    }
                    return path.join(' > ');
                  };
                  const intercept = function(ev) {
                    const raw = ev.target.nodeType === 1 ? ev.target : ev.target.parentElement;
                    const el = raw.closest('button,input,a,[role=button],[onclick]');
                    if (!el) return;
                    ev.preventDefault();
                    ev.stopPropagation();
                    ev.stopImmediatePropagation();
                    if (Date.now() - (window.__kantanLastCapture || 0) < 600) return;
                    window.__kantanLastCapture = Date.now();
                    const label = (el.innerText || el.value || el.getAttribute('aria-label') || el.getAttribute('title') || el.id || el.getAttribute('name') || '').replace(/\\s+/g, ' ').trim();
                    window.KantanDakokuCapture.record(JSON.stringify({selector:css(el),label:label}));
                  };
                  document.addEventListener('pointerdown', intercept, true);
                  document.addEventListener('touchstart', intercept, {capture:true, passive:false});
                  document.addEventListener('mousedown', intercept, true);
                  document.addEventListener('click', intercept, true);
                })();
                """;
        browser.evaluateJavascript(js, null);
    }

    private final class LearningBridge {
        @JavascriptInterface public void record(String json) {
            if (json == null || json.length() > 4096) return;
            runOnUiThread(() -> {
                if (!learning || stopped || showingCapture || browser == null || !trustedUrl(browser.getUrl())) return;
                try {
                    JSONObject obj = new JSONObject(json);
                    String selector = obj.getString("selector");
                    String label = obj.optString("label", "");
                    if (selector.length() < 1 || selector.length() > 1024 || label.length() < 1 || label.length() > 160) {
                        setStatus("ボタンを識別できませんでした。別の箇所を選択してください。");
                        return;
                    }
                    showingCapture = true;
                    new AlertDialog.Builder(WebActivity.this)
                            .setTitle(actionLabel() + "ボタンとして登録")
                            .setMessage("選択されたボタン：" + label + "\n\n学習中のクリックは抑止されています。登録しますか？")
                            .setNegativeButton("選び直す", (d, w) -> showingCapture = false)
                            .setPositiveButton("登録する", (d, w) -> {
                                preferences.saveSelector(kind, selector, label);
                                showingCapture = false;
                                setStatus(actionLabel() + "ボタンを登録しました。設定画面に戻ります。");
                                finish();
                            }).show();
                } catch (Exception ignored) { setStatus("ボタン情報を取得できませんでした。"); }
            });
        }
    }

    private String unpack(String jsonResult) {
        try { return new org.json.JSONArray("[" + jsonResult + "]").getString(0); }
        catch (Exception ex) { return ""; }
    }

    private void probePunch() {
        if (punchAttempted || probing || stopped) return;
        String selector = preferences.selector(kind);
        String label = preferences.label(kind);
        if (selector.isEmpty() || label.isEmpty()) return;
        if (++probeCount > 7) {
            setStatus("登録ボタンが見つかりません。画面を確認し、必要ならボタンを再登録してください。自動打刻はしていません。");
            return;
        }
        probing = true;
        final String probeUrl = browser.getUrl();
        String preflight = """
                (function() {
                  const t = (document.body ? document.body.innerText : '').slice(0, 7000);
                  if ((/セッション\\s*(?:の)?\\s*タイムアウト|セッションアウト/i.test(t)
                       && /ログインページ|再度ログイン|再ログイン/i.test(t))
                      || /session\\s*(?:has\\s*)?(?:timed\\s*out|timeout|expired)/i.test(t)) return 'TIMEOUT';
                  if (location.pathname.toLowerCase().includes('/c01/c0101login')) return 'LOGIN';
                  const node = document.querySelector(%s);
                  if (!node) return 'NOT_FOUND';
                  if (node.disabled || node.getClientRects().length === 0) return 'HIDDEN';
                  const getLabel = e => (e.innerText || e.value || e.getAttribute('aria-label') || '').replace(/\\s+/g,' ').trim();
                  if (getLabel(node) !== %s) return 'LABEL_CHANGED';
                  return 'READY';
                })();
                """.formatted(JSONObject.quote(selector), JSONObject.quote(label));
        browser.evaluateJavascript(preflight, value -> {
            probing = false;
            if (stopped || punchAttempted || browser == null || !probeUrl.equals(browser.getUrl())) return;
            String answer = unpack(value);
            if ("READY".equals(answer)) {
                // Arm one-shot guard BEFORE clicking: even a fast navigation cannot fire twice.
                punchAttempted = true;
                setStatus(actionLabel() + "ボタンを1回押す処理を実行します。サーバーへの記録結果はサイトで確認してください。");
                String click = """
                        (function() {
                          const e = document.querySelector(%s);
                          if (!e || e.disabled || e.getClientRects().length === 0) return 'NOT_CLICKED';
                          const label = (e.innerText || e.value || e.getAttribute('aria-label') || '').replace(/\\s+/g,' ').trim();
                          if (label !== %s) return 'NOT_CLICKED';
                          e.click();
                          return 'CLICKED';
                        })();
                        """.formatted(JSONObject.quote(selector), JSONObject.quote(label));
                browser.evaluateJavascript(click, result -> {
                    if (stopped) return;
                    if ("NOT_CLICKED".equals(unpack(result))) {
                        setStatus("画面が変更されたためクリックを中断しました。打刻していません。画面を確認してください。");
                    } else {
                        setStatus("打刻ボタンのクリックを試みました。サイト上の出退勤記録を確認してください（完了未検証）。");
                    }
                });
            } else if ("TIMEOUT".equals(answer)) {
                handleSessionTimeout();
            } else if ("LOGIN".equals(answer)) {
                attemptLogin();
            } else if ("LABEL_CHANGED".equals(answer)) {
                setStatus("登録時とボタン表示が違います。誤打刻防止のため自動クリックしません。画面を確認してください。");
            } else if (probeCount < 7) {
                setStatus("打刻画面を確認中です…（" + probeCount + "/7）");
                handler.postDelayed(this::probePunch, 850);
            } else {
                setStatus("打刻ボタンが見つかりませんでした。手動確認または再登録してください。");
            }
        });
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 22 && locationCallback != null && pendingLocationOrigin != null) {
            boolean granted = false;
            for (int result : grantResults) if (result == PackageManager.PERMISSION_GRANTED) granted = true;
            locationCallback.invoke(pendingLocationOrigin, granted, false);
            locationCallback = null;
            pendingLocationOrigin = null;
        }
    }

    @Override public void onBackPressed() {
        if (browser != null && browser.canGoBack()) browser.goBack();
        else super.onBackPressed();
    }

    @Override protected void onPause() {
        super.onPause();
        if (browser != null) CookieManager.getInstance().flush();
    }

    @Override protected void onDestroy() {
        stopped = true;
        handler.removeCallbacksAndMessages(null);
        if (locationCallback != null && pendingLocationOrigin != null) {
            locationCallback.invoke(pendingLocationOrigin, false, false);
            locationCallback = null;
        }
        if (browser != null) {
            browser.removeJavascriptInterface("KantanDakokuCapture");
            browser.stopLoading();
            browser.setWebChromeClient(null);
            browser.setWebViewClient(null);
            browser.destroy();
            browser = null;
        }
        super.onDestroy();
    }
}
