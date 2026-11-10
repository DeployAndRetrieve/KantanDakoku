from pathlib import Path

root = Path(__file__).resolve().parents[1]
source = root / 'app/src/main/java/jp/example/kantandakkoku'
manifest = (root / 'app/src/main/AndroidManifest.xml').read_text()
main = (source / 'MainActivity.java').read_text()
prefs = (source / 'AppPrefs.java').read_text()
assert not (source / 'BackgroundPunchService.java').exists()
for name in ('ClockInActivity.java', 'ClockOutActivity.java'):
    entry = (source / name).read_text()
    assert 'WebActivity.class' in entry
    assert 'BackgroundPunchService' not in entry
    assert 'setBackgroundEnabled(false)' in entry
assert 'FOREGROUND_SERVICE_DATA_SYNC' not in manifest
assert 'POST_NOTIFICATIONS' not in manifest
assert 'BackgroundPunchService' not in manifest
assert 'android:label="@string/app_name"' in manifest
assert 'setBackgroundEnabled(false)' in main
assert 'toggleBackground' not in main
assert '通常の画面表示方式' in main
assert 'getBoolean("background_punch", false)' in prefs
print('Visible-only safe fallback: PASS')
