import fs from 'node:fs';
import assert from 'node:assert/strict';
import path from 'node:path';
const base = path.resolve(import.meta.dirname, '..');
const src = 'app/src/main/java/jp/example/kantandakkoku/';
const read = file => fs.readFileSync(path.join(base, file), 'utf8');
const main = read(src + 'MainActivity.java');
const web = read(src + 'WebActivity.java');
const manifest = read('app/src/main/AndroidManifest.xml');
const theme = read('app/src/main/res/values-v31/styles.xml');
const prefs = read(src + 'AppPrefs.java');

assert.match(prefs, /OUT = "OUT"/);
assert.match(prefs, /IN = "IN"/);
assert.match(main, /new Intent\(this, isIn \? ClockInActivity\.class : ClockOutActivity\.class\)/);
assert.match(main, /isIn \? R\.drawable\.ic_clockin : R\.drawable\.ic_clockout/);
assert.match(main, /setIntent\(launch\)/);
assert.match(main, /mgr\.updateShortcuts\(updates\)/);
for (const [type, constant, icon, label] of [
  ['In','IN','ic_clockin','出勤'],['Out','OUT','ic_clockout','退勤']
]) {
  const cls = read(src + `Clock${type}Activity.java`);
  assert.ok(cls.includes(`AppPrefs.${constant}`));
  assert.ok(cls.includes('super.onCreate(state)'));
  assert.match(manifest, new RegExp(`android:name="\\.Clock${type}Activity"[\\s\\S]*?android:label="${label}" android:icon="@drawable/${icon}"`));
  assert.match(theme, new RegExp(`name="Clock${type}Theme"[\\s\\S]*?name="android:windowSplashScreenAnimatedIcon">@drawable/${icon}`));
}
assert.match(web, /AppPrefs\.OUT\.equals\(kind\)\s*\? R\.drawable\.ic_clockout : R\.drawable\.ic_clockin/);
assert.notDeepEqual(
 fs.readFileSync(path.join(base,'app/src/main/res/drawable-nodpi/ic_clockin.png')),
 fs.readFileSync(path.join(base,'app/src/main/res/drawable-nodpi/ic_clockout.png'))
);
console.log('PASS: IN and OUT activity, splash, icon, view, intent mappings; old pin migration');
