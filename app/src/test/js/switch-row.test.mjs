// 設定のスイッチの行 (web/app.js の switchRow) の単体テスト。実行: node app/src/test/js/switch-row.test.mjs
// app.js は読み込むと画面を触るので、switchRow の関数だけを取り出して、core/base.js の NH.el と組み合わせて確かめる。
// DOM は linkedom (無ければ飛ばす。dom.test.mjs と同じ)。
import fs from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const MAIN = (p) => fileURLToPath(new URL('../../main/assets/' + p, import.meta.url));
function findLinkedom() {
  const bases = [process.env.NH_TEST_DEPS, fileURLToPath(new URL('../../../../', import.meta.url)), process.cwd()].filter(Boolean);
  for (const b of bases) {
    try { return createRequire(b.replace(/\/?$/, '/') + 'x.js')('linkedom'); } catch (e) { /* 次へ */ }
  }
  return null;
}
const linkedom = findLinkedom();
if (!linkedom) {
  console.log('skip switch-row.test.mjs: linkedom が見つかりません (npm i --no-save linkedom)');
  process.exit(0);
}

const { document, window } = linkedom.parseHTML('<!doctype html><html><body></body></html>');
const ctx = vm.createContext({ document, window, console, NHStore: undefined, localStorage: { getItem: () => null, setItem: () => {} } });
vm.runInContext(fs.readFileSync(MAIN('js/core/base.js'), 'utf8'), ctx);
const app = fs.readFileSync(MAIN('web/app.js'), 'utf8');
const src = app.match(/function switchRow[\s\S]*?\r?\n}\r?\n/);
assert.ok(src, 'app.js に switchRow が見つからない');
vm.runInContext('const el = window.NH.el;\n' + src[0] + '\nglobalThis.switchRow = switchRow;', ctx);
const switchRow = ctx.switchRow;

// 値・見出し・説明・変更の通知
const calls = [];
const row = switchRow('名前', true, (v) => calls.push(v), '説明');
assert.equal(row.tagName.toLowerCase(), 'label');
assert.ok(row.classList.contains('cell'));
assert.equal(row.querySelector('.text').firstChild.textContent, '名前');
assert.equal(row.querySelector('small').textContent, '説明');
assert.equal(row.cb.checked, true);
assert.equal(row.cb.type, 'checkbox');
assert.ok(row.cb.classList.contains('switch'));
row.cb.checked = false; row.cb.onchange();
assert.deepEqual(calls, [false]);

// 説明なしなら small を作らない。値が undefined / null / 0 ならオフ
const plain = switchRow('名前', undefined, () => {});
assert.equal(plain.querySelector('small'), null);
assert.equal(plain.cb.checked, false);
assert.equal(switchRow('x', 0, () => {}).cb.checked, false);
assert.equal(switchRow('x', null, () => {}).cb.checked, false);
console.log('switch-row.test.mjs: ok');
