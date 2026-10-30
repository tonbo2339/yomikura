// 設定の「話ページの下の欄」の文が、畳むボタンの名前 (sites.js の NH_FOLDS の label) を 1 か所から引いているかを確かめる。
// 実行: node app/src/test/js/fold-labels.test.mjs
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { JS_DIR, scriptFiles } from './scripts.mjs';

const WEB = path.join(JS_DIR, '..', 'web');
const html = fs.readFileSync(path.join(WEB, 'settings', 'page-fold.html'), 'utf8');
const settingsHtml = fs.readFileSync(path.join(WEB, 'settings.html'), 'utf8');
const ctx = vm.createContext({ window: {} });
ctx.window = ctx;
vm.runInContext(fs.readFileSync(path.join(JS_DIR, 'site', 'sites.js'), 'utf8'), ctx);
const folds = ctx.NH_FOLDS.filter((f) => f.key !== 'ads'); // 広告の行は版によって有無が違うので除く

const tests = [];
const t = (name, fn) => tests.push([name, fn]);

t('畳む欄の種類ごとに、スイッチと説明の両方で名前の枠 (data-fold-label) を使っている', () => {
  for (const f of folds) {
    const n = html.split(`data-fold-label="${f.key}"`).length - 1;
    assert.ok(n >= 2, `${f.key}: 枠が ${n} 個 (スイッチと説明で 2 個以上)`);
  }
});

t('ボタンの名前を HTML に直接書かない (名前の定義は NH_FOLDS の label だけ)', () => {
  for (const f of folds) assert.ok(!html.includes(f.label), `page-fold.html に「${f.label}」が直接書かれています`);
});

t('設定画面は sites.js (fold-labels グループ) を読み込む', () => {
  assert.ok(settingsHtml.includes('<!--#scripts fold-labels-->'));
  assert.deepEqual(scriptFiles('fold-labels'), ['site/sites.js']);
});

let failed = 0;
for (const [name, fn] of tests) {
  try { fn(); console.log('ok  ', name); } catch (e) { failed++; console.log('FAIL', name, '\n    ', e.message); }
}
if (failed) process.exitCode = 1;
