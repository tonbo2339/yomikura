// site/readbg.js (読書ページの背景色の表) の単体テスト。実行: node app/src/test/js/readbg.test.mjs (Node 18 以上)
import fs from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';

const src = fs.readFileSync(fileURLToPath(new URL('../../main/assets/js/site/readbg.js', import.meta.url)), 'utf8');
const ctx = {};
ctx.window = ctx;
vm.createContext(ctx);
vm.runInContext(src, ctx);
const R = ctx.NH_READBG;
const BODY = { box: '.b', text: '.t,.t p' };

const tests = [];
const test = (name, fn) => tests.push([name, fn]);

test('サイト標準・不明な値・本文の設定が無いときは、何も指定しない', () => {
  for (const v of ['site', undefined, null, '', 'x']) assert.equal(R.css(v, BODY), '', String(v));
  assert.equal(R.css('black', null), '');
  assert.equal(R.css('black', { box: '.b' }), '');
  assert.equal(R.colors('site'), null);
});
test('選んだ組の背景を html, body に、文字色を本文に、白い面と文字の印にも付ける', () => {
  const css = R.css('black', BODY);
  assert.ok(css.startsWith('html,body{background:#000000!important}.t,.t p{color:#c8c8c8!important}'), css);
  assert.ok(css.includes('[data-nh-surface]{background-color:#000000!important}') && css.includes('[data-nh-ink]{color:#c8c8c8!important}') && css.includes('[data-nh-link]{color:#8ab4f8!important}'), css);
});
test('選択肢は「サイト標準」が先頭で、key が重ならず、全部が css になる', () => {
  const c = R.choices();
  assert.equal(c[0][0], R.SITE);
  assert.equal(new Set(c.map((x) => x[0])).size, c.length);
  c.slice(1).forEach(([k]) => { assert.ok(R.css(k, BODY), k); assert.ok(R.colors(k).bg && R.colors(k).fg, k); });
});
test('背景と文字の明るさが十分離れている (読めない組を入れない)', () => {
  const lum = (h) => { const n = parseInt(h.slice(1), 16); return (0.299 * (n >> 16) + 0.587 * ((n >> 8) & 255) + 0.114 * (n & 255)); };
  R.choices().slice(1).forEach(([k]) => { const c = R.colors(k); assert.ok(Math.abs(lum(c.bg) - lum(c.fg)) > 110, k); });
});

test('リンクの色は、暗い組だけ付ける (背景が明るい組は、サイトの色のまま)', () => {
  assert.ok(!/data-nh-link/.test(R.css('cream', BODY)));
  assert.ok(/data-nh-link/.test(R.css('dark', BODY)));
});

let failed = 0;
for (const [name, fn] of tests) {
  try { fn(); console.log('ok   ' + name); } catch (e) { failed++; console.log('FAIL ' + name + '\n  ' + e.message); }
}
console.log(failed ? 'FAIL ' + failed + ' 件' : 'すべて通りました');
