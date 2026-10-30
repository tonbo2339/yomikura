// site/fonts.js (本文の書体の表) の単体テスト。実行: node app/src/test/js/fonts.test.mjs (Node 18 以上)
import fs from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';

const src = fs.readFileSync(fileURLToPath(new URL('../../main/assets/js/site/fonts.js', import.meta.url)), 'utf8');
const ctx = {};
ctx.window = ctx;
vm.createContext(ctx);
vm.runInContext(src, ctx);
const F = ctx.NH_FONTS;

const tests = [];
const test = (name, fn) => tests.push([name, fn]);

test('サイト標準・不明な値は、何も指定しない (サイトのまま)', () => {
  for (const v of ['site', undefined, null, '', 'custom:', 'custom:xyz', 'custom:0123456789ABCDEF', 'x']) {
    assert.equal(F.family(v), '', String(v));
    assert.equal(F.face(v), '', String(v));
  }
});
test('ゴシック・明朝は端末の Noto の字体 (@font-face は無し)', () => {
  assert.match(F.family('gothic'), /Noto Sans CJK JP/);
  assert.match(F.family('mincho'), /Noto Serif CJK JP/);
  assert.equal(F.face('gothic'), '');
});
test('入れたフォント: @font-face はアプリの配信の URL を指し、字が無いときはゴシックで補う', () => {
  const v = 'custom:0123456789ab';
  assert.equal(F.face(v), '@font-face{font-family:"nh-custom";src:url("https://yomikura.local/font/0123456789ab");font-display:swap}');
  assert.equal(F.family(v), '"nh-custom",' + F.PRESETS.gothic);
});
test('サイトのページ用 (forSite) は、端末ごとの id を含めず /font/current を指す', () => {
  const css = F.face('custom:0123456789ab', true);
  assert.equal(css, '@font-face{font-family:"nh-custom";src:url("https://yomikura.local/font/current");font-display:swap}');
  assert.ok(!css.includes('0123456789ab'));
  assert.equal(F.face('gothic', true), '');
});
test('idOf と valueOf は往復できる。形の違う id は空', () => {
  assert.equal(F.idOf(F.valueOf('0123456789ab')), '0123456789ab');
  assert.equal(F.idOf('custom:../../x'), '');
  assert.equal(F.idOf('gothic'), '');
});
test('URL や CSS を壊す値は通さない (id は 16 進 12 桁だけ)', () => {
  assert.equal(F.face('custom:0123456789ab");}body{x:y'), '');
  assert.equal(F.family('custom:0123456789ab;color:red'), '');
});

let fail = 0;
for (const [name, fn] of tests) {
  try { fn(); console.log('ok   ' + name); } catch (e) { fail++; console.log('FAIL ' + name + '\n  ' + e.message); }
}
console.log(fail ? fail + ' 件失敗' : tests.length + ' 件 OK');
