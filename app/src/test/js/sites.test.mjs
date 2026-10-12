// sites.js (サイトごとの CSS のセレクター) の単体テスト。実行: node app/src/test/js/sites.test.mjs (Node 18 以上)
import fs from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';

const SITES = fileURLToPath(new URL('../../main/assets/js/sites.js', import.meta.url));
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

test('ヘッダー・フッター・アプリ宣伝を隠す', () => {
  const n = selectors(load(null, 'ncode.syosetu.com', '/n9669bk/1/').narou.hide);
  for (const x of ['.l-header', '.l-footer', '[class*="c-applink"]']) assert.ok(n.has(x), x);
});

let failed = 0;
for (const [name, fn] of tests) {
  try { await fn(); console.log('ok   ' + name); } catch (e) { failed++; console.log('FAIL ' + name + '\n     ' + e.message); }
}
console.log(failed ? failed + ' 件失敗' : tests.length + ' 件すべて成功');
process.exit(failed ? 1 : 0);
