import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';

const root = path.resolve(import.meta.dirname, '../app/src/main/assets');
const loginSource = fs.readFileSync(path.join(root, 'login_autosubmit.js'), 'utf8');
const inspectSource = fs.readFileSync(path.join(root, 'inspect_dom.js'), 'utf8');
function fixture(kind) {
  let clicked = 0, submitted = 0, events = 0;
  const makeInput = type => ({
    type,
    tagName:'INPUT', disabled:false, readOnly:false, form:null,
    getClientRects:()=>[{}], dispatchEvent:()=>{events++;}, closest:()=>null,
    getAttribute:()=>null,
    set value(v) {this._value=v;}, get value() {return this._value;}
  });
  const company = makeInput('text'), account = makeInput('text'), password = makeInput('password');
  const form = {
    contains:e=>[company,account,password].includes(e),
    requestSubmit:()=>{submitted++;},
    querySelectorAll:()=>[]
  };
  for (const e of [company,account,password]) {e.form=form;e.closest=()=>form;}
  const button = {
    type:'button', tagName:'BUTTON', id:kind==='id'?'loginButton':'', disabled:false, readOnly:false,
    innerText:kind==='named'?'ログイン':'', form,
    getClientRects:()=>[{}], getAttribute:()=>null,
    click:()=>{clicked++;}
  };
  const submit = {...button,tagName:'BUTTON',type:'submit',innerText:'進む'};
  form.querySelectorAll=()=>kind==='submit'?[submit]:[];
  const document = { querySelectorAll:q=>q==='input'?[company,account,password]:
    q==='button,input[type=submit],input[type=image],input[type=button],a,[role=button],[onclick]'?
       ['named','id'].includes(kind)?[button]:kind==='submit'?[submit]:[] :[]};
  const ctx = {
    document,
    HTMLInputElement:class { },
    Event:class{constructor(name){this.name=name;}},
    console
  };
  const source = loginSource.replace('__COMPANY_JSON__',JSON.stringify('demo-company'))
    .replace('__ACCOUNT_JSON__',JSON.stringify('demo-account'))
    .replace('__PASSWORD_JSON__',JSON.stringify('dummy-test-password'))
    .replace('__LOGIN_SELECTOR_JSON__',JSON.stringify(''))
    .replace('__LOGIN_LABEL_JSON__',JSON.stringify(''));
  const result = vm.runInNewContext(source,ctx);
  return {result,clicked,submitted,events,company,account,password};
}
for (const [kind, expected, isClick] of [
  ['named','SUBMITTED:CLICK',true],
  ['id','SUBMITTED:ID',true],
  ['submit','SUBMITTED:FORM_BUTTON',false],
  ['form','SUBMITTED:FORM',false]
]) {
 const x=fixture(kind);
 assert.equal(x.result,expected,kind);
 assert.equal(x.clicked,isClick?1:0,kind);
 assert.equal(x.submitted,isClick?0:1,kind);
 assert.equal(x.company.value,'demo-company');
 assert.equal(x.account.value,'demo-account');
 assert.equal(x.password.value,'dummy-test-password');
 assert.equal(x.events,6,kind);
}
// A strict static guard: the inspector cannot perform network calls or read input .value.
const inspectorWithoutComments = inspectSource.replace(/\/\*[\s\S]*?\*\/|\/\/[^\n]*/g,'');
assert.doesNotMatch(inspectorWithoutComments,/\.value\b|\.click\s*\(|\.submit\s*\(|requestSubmit\s*\(|fetch\s*\(|XMLHttpRequest\s*\(/);
console.log('PASS: login submit 4 DOM layouts, populated fields, read-only inspector guard');

// The inspector must never read a current field value, even by accident.
const field = {
  tagName:'INPUT',
  getClientRects:()=>[{}],
  getAttribute:k=>({id:'startTime',type:'text',name:'workingTime',placeholder:'開始'}[k]||null),
  hasAttribute:()=>false,
  get value(){throw Error('The DOM inspector must not read values');}
};
const label = {textContent:'開始時刻'};
const readOnlyDocument = {
  querySelectorAll:q=>q==='form'?[{}]:q==='input,select,textarea'?[field]:[],
  querySelector:q=>q==='label[for="startTime"]'?label:null,
};
const reportJSON = vm.runInNewContext(inspectSource, {
  document:readOnlyDocument,location:{pathname:'/test/safe'},CSS:{escape:x=>x},
});
const report = JSON.parse(reportJSON);
assert.equal(report.fields[0].name,'workingTime');
assert.equal(report.fields[0].label,'開始時刻');
assert.ok(!reportJSON.includes('dummy-test-password'));
console.log('PASS: inspector extracts labels without reading any field values');
