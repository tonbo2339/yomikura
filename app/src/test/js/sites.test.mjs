// sites.js (サイトごとの CSS のセレクター) の単体テスト。実行: node app/src/test/js/sites.test.mjs (Node 18 以上)
import fs from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';

const SITES = fileURLToPath(new URL('../../main/assets/js/site/sites.js', import.meta.url));
const code = fs.readFileSync(SITES, 'utf8');

// sites.js を、版・ホスト・パスを決めて読み込む (location の代わりをこのファイルで用意する)
function load(mode, hostname, pathname) {
  const sandbox = { window: {}, location: { hostname, pathname } };
  vm.createContext(sandbox);
  vm.runInContext(code, sandbox);
  return sandbox.window.NH_SITES;
}
// CSS のルール列から、隠す対象のセレクターの集合を取り出す
const selectors = (css) => new Set(css.split('}').map((r) => r.split('{')[0].trim()).filter(Boolean).flatMap((r) => r.split(',').map((x) => x.trim())));

const tests = [];
const test = (name, fn) => tests.push([name, fn]);

test('ヘッダー・フッター (bars) とアプリ宣伝 (hide) を分けて持つ。ヘッダーは常に隠す側に入れない', () => {
  const s = load(null, 'ncode.syosetu.com', '/n9669bk/1/').narou;
  for (const x of ['.l-header', '.l-footer']) { assert.ok(selectors(s.bars).has(x), x); assert.ok(!selectors(s.hide).has(x), x + ' が hide にある'); }
  assert.ok(selectors(s.hide).has('[class*="c-applink"]'));
});
test('bars の対象は、どのサイトも読書ページ (episode) の形を持つ', () => {
  const all = load(null, 'ncode.syosetu.com', '/');
  for (const [k, s] of Object.entries(all)) if (s.bars) assert.equal(typeof (s.episode && s.episode.test), 'function', k); // vm をまたぐので instanceof RegExp は使えない
  assert.ok(all.narou.episode.test('/n9669bk/1/') && !all.narou.episode.test('/n9669bk/'));
  assert.ok(all.hameln.episode.test('/novel/123/4.html') && !all.hameln.episode.test('/novel/123/'));
  assert.ok(all.kakuyomu.episode.test('/works/1/episodes/2') && !all.kakuyomu.episode.test('/works/1'));
});

test('畳む欄の表 (NH_FOLDS): どのサイトの fold のキーも表にあり、設定の項目 (flag) は設定画面にある', () => {
  const sandbox = { window: {}, location: { hostname: 'ncode.syosetu.com', pathname: '/' } };
  vm.createContext(sandbox);
  vm.runInContext(code, sandbox);
  const folds = sandbox.window.NH_FOLDS;
  const keys = new Set(folds.map((f) => f.key));
  for (const [site, s] of Object.entries(sandbox.window.NH_SITES)) for (const k of Object.keys(s.fold || {})) assert.ok(keys.has(k), `${site} の fold.${k} が NH_FOLDS にありません`);
  const page = fs.readFileSync(fileURLToPath(new URL('../../main/assets/web/settings/page-fold.html', import.meta.url)), 'utf8');
  for (const f of folds) {
    if (f.key === 'ads') continue; // 広告は版だけの項目 (その版の設定画面にある)
    assert.ok(page.includes('data-setting="' + f.flag + '"'), `設定画面に ${f.flag} の項目がありません`);
  }
});

let failed = 0;
for (const [name, fn] of tests) {
  try { await fn(); console.log('ok   ' + name); } catch (e) { failed++; console.log('FAIL ' + name + '\n     ' + e.message); }
}
console.log(failed ? failed + ' 件失敗' : tests.length + ' 件すべて成功');
process.exit(failed ? 1 : 0);
