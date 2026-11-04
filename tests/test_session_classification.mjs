// Zero-dependency smoke test for Java-embedded JavaScript session classifier.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
const source = fs.readFileSync(new URL('../app/src/main/java/jp/example/kantandakkoku/WebActivity.java', import.meta.url), 'utf8');
function scriptIn(methodName, declaration) {
  const start = source.indexOf(`private void ${methodName}(`);
  assert.ok(start >= 0, `${methodName} exists`);
  const body = source.slice(start);
  const match = body.match(new RegExp(`${declaration} = """([\\s\\S]*?)"""`));
  assert.ok(match, `JS text block in ${methodName}`);
  // Java literal \\ -> JavaScript literal \; test scripts do not use other escapes.
  return match[1].replace(/\\\\/g, '\\');
}
const inspect = scriptIn('inspectPage', 'String js');
const probe = scriptIn('probePunch', 'String preflight')
  .replace('%s', JSON.stringify('#checkin')).replace('%s', JSON.stringify('出勤'));
function run(script, path, body, element = null) {
  const elementList = [];
  const document = {body:{innerText:body},
    querySelectorAll: () => elementList, querySelector: () => element};
  return vm.runInNewContext(script, {document,location:{pathname:path}});
}
const timeout = 'CC-BizMate セッションタイムアウト\nセッションのタイムアウトが発生しました。\nログインページより再度ログインしてください';
assert.equal(run(inspect, '/C01/Unknown', timeout), 'TIMEOUT');
assert.equal(run(probe, '/C01/Unknown', timeout), 'TIMEOUT');
assert.equal(run(inspect, '/C01/C0101Login', 'ログイン'), 'LOGIN');
assert.equal(run(inspect, '/C03/C0301Top', '勤怠情報'), 'OTHER');
assert.equal(run(probe, '/C03/C0301Top', '勤怠情報'), 'NOT_FOUND');
console.log('PASS: timeout/login/normal/unknown-button classifications');
