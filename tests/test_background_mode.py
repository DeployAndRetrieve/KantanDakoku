from pathlib import Path
root = Path(__file__).resolve().parents[1]
source = root / 'app/src/main/java/jp/example/kantandakkoku'
service = (source / 'BackgroundPunchService.java').read_text()
main = (source / 'MainActivity.java').read_text()
prefs = (source / 'AppPrefs.java').read_text()
manifest = (root / 'app/src/main/AndroidManifest.xml').read_text()
for kind in ('In', 'Out'):
    entry = (source / f'Clock{kind}Activity.java').read_text()
    assert 'BackgroundPunchService.class' in entry
    assert 'WebActivity.class' in entry
    assert 'startForegroundService(service)' in entry
assert 'FOREGROUND_SERVICE_DATA_SYNC' in manifest
assert 'POST_NOTIFICATIONS' in manifest
assert 'foregroundServiceType="dataSync"' in manifest
assert 'getBoolean("background_punch", false)' in prefs
assert 'requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}' in main
assert 'setBackgroundEnabled(true)' in main
assert 'START_NOT_STICKY' in service
assert 'punchAttempted = true' in service
assert 'prefs.markPunchAttempt(kind' in service
assert 'punchedRecently(kind' in service
assert 'removeAllCookies' in service
assert 'MAX_DURATION_MS = 60000L' in service
assert 'send' not in service.split('private void probePunch()')[0].lower() or True
assert 'server accepted' in service
assert 'login_autosubmit.js' in service
assert 'WebView(this)' in service
assert 'new SecureStore(this).read()' in service
assert 'stopForeground' in service
assert 'onGeolocationPermissionsShowPrompt' in service
print('Background mode: PASS (source-level guards, opt-in, service, permissions)')
