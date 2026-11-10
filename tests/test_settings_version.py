from pathlib import Path
import re
project = Path(__file__).resolve().parents[1]
main = (project / "app/src/main/java/jp/example/kantandakkoku/MainActivity.java").read_text()
build = (project / "app/build.gradle").read_text()
strings = (project / "app/src/main/res/values/strings.xml").read_text()
web = (project / "app/src/main/java/jp/example/kantandakkoku/WebActivity.java").read_text()
assert 'text("設定", 25' in main
assert 'getString(R.string.app_name) + " v" + installedVersion()' in main
assert 'getPackageManager().getPackageInfo(getPackageName(), 0).versionName' in main
assert "versionName '0.3.5'" in build
assert 'versionCode 8' in build
assert '<string name="app_name">KantanDakoku</string>' in strings
assert '試作版 v0.3.1' not in main
assert '(learning ? "ボタンの登録" : "打刻処理")' in web
assert '" v" + installedVersion()' in web
assert 'getPackageManager().getPackageInfo(getPackageName(), 0).versionName' in web
print('Settings version display: PASS (source-level)')
