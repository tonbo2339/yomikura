// アプリ内ページの <!--#include 名前--> (Kotlin の Includes と同じ決まり) の参照が、切れていないか・使われない部品が残っていないかを確かめる。
// 実行: node app/src/test/js/includes.test.mjs
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../main/assets/web');
const INCLUDE = /<!--#include ([A-Za-z0-9._/-]+)-->/g;
// 版によってだけあるファイル (その版の src/<版>/assets/web にあり、無い版では空になる差し込み口)。版のフォルダを見て決める
const SRC = path.resolve(WEB, '../../..');
const EDITION_ONLY = new Set();
for (const ed of fs.readdirSync(SRC)) {
  const dir = path.join(SRC, ed, 'assets/web');
  if (ed === 'main' || !fs.existsSync(dir)) continue;
  (function walkDir(d, rel) {
    for (const e of fs.readdirSync(d, { withFileTypes: true })) {
      if (e.isDirectory()) walkDir(path.join(d, e.name), rel + e.name + '/');
      else EDITION_ONLY.add(rel + e.name);
    }
  })(dir, '');
}
const validName = (n) => n && !n.startsWith('/') && !n.includes('..') && !n.includes('//') && !n.endsWith('/');

const tests = [];
const t = (name, fn) => tests.push([name, fn]);
const read = (n) => { const p = path.join(WEB, n); return fs.existsSync(p) ? fs.readFileSync(p, 'utf8') : null; };
const includesOf = (text) => [...text.matchAll(INCLUDE)].map((m) => m[1]);

// ページ (HTML) から、入れ子も含めてたどった参照
function walk(page, seen = new Set(), depth = 0) {
  assert.ok(depth < 4, '入れ子が深すぎます: ' + page);
  for (const n of includesOf(read(page) || '')) {
    seen.add(n);
    if (read(n) !== null) walk(n, seen, depth + 1);
  }
  return seen;
}
const pages = fs.readdirSync(WEB).filter((f) => f.endsWith('.html'));

t('include の名前は、assets/web の外へ出ない形', () => {
  for (const p of pages) for (const n of walk(p)) assert.ok(validName(n), `${p}: ${n}`);
});

t('include の参照先がある (版によってだけある物は除く)', () => {
  for (const p of pages) for (const n of walk(p)) {
    if (EDITION_ONLY.has(n)) continue;
    assert.ok(read(n) !== null, `${p} が読み込む ${n} がありません`);
  }
});

t('settings/ の部品は、settings.html から読み込まれている (使われない部品を残さない)', () => {
  const used = walk('settings.html');
  for (const f of fs.readdirSync(path.join(WEB, 'settings'))) {
    assert.ok(used.has('settings/' + f), `settings/${f} はどこからも読み込まれていません`);
  }
});

t('settings.html は骨組みだけ (長い処理を直に書かない)', () => {
  const lines = read('settings.html').split('\n');
  assert.ok(lines.length < 120, `settings.html が ${lines.length} 行あります。ページ・処理は settings/ のファイルに分けてください`);
});

t('ページの部品は 1 ファイル 1 ページ (id が p-<名前>)', () => {
  for (const f of fs.readdirSync(path.join(WEB, 'settings')).filter((x) => x.startsWith('page-'))) {
    const name = f.slice(5, -5);
    const text = read('settings/' + f);
    const ids = [...text.matchAll(/<section class="page"[^>]* id="p-([a-z-]+)"/g)].map((m) => m[1]);
    assert.ok(ids.length > 0, f);
    if (name === 'rank-sites') assert.ok(ids.every((i) => i.startsWith('rank-')), f);
    else assert.deepEqual(ids, [name], f);
  }
});

let failed = 0;
for (const [name, fn] of tests) {
  try { fn(); console.log('ok  ', name); } catch (e) { failed++; console.log('FAIL', name, '\n    ', e.message); }
}
console.log(failed ? `${failed} 件失敗` : `${tests.length} 件すべて成功`);
process.exit(failed ? 1 : 0);
