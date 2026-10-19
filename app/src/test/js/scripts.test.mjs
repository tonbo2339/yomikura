// JS の一覧 (app/src/main/assets/js/scripts.json) が、実際のファイルと食い違っていないかを確かめる。
// 実行: node app/src/test/js/scripts.test.mjs
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { JS_DIR, scriptFiles } from './scripts.mjs';

const manifest = JSON.parse(fs.readFileSync(path.join(JS_DIR, 'scripts.json'), 'utf8'));
const groups = Object.keys(manifest).filter((k) => !k.startsWith('_'));

// assets/js の下の .js を、相対パスで全部集める
function walk(dir, rel = '') {
  return fs.readdirSync(path.join(dir, rel), { withFileTypes: true }).flatMap((e) =>
    e.isDirectory() ? walk(dir, rel + e.name + '/') : e.name.endsWith('.js') ? [rel + e.name] : []);
}
const files = walk(JS_DIR);

const tests = [];
const t = (name, fn) => tests.push([name, fn]);

t('一覧のファイルは、全部ある', () => {
  for (const g of groups) for (const f of scriptFiles(g)) assert.ok(fs.existsSync(path.join(JS_DIR, f)), `${g}: ${f} がありません`);
});

t('assets/js の JS は、どれかのグループで読み込まれている (使われないファイルを残さない)', () => {
  const used = new Set(groups.flatMap((g) => scriptFiles(g)));
  for (const f of files) assert.ok(used.has(f), `${f} はどのグループにも入っていません`);
});

t('1 つのグループに同じファイルを 2 度入れない', () => {
  for (const g of groups) {
    const list = scriptFiles(g);
    assert.equal(new Set(list).size, list.length, `${g} に重複があります`);
  }
});

t('core のファイルは、先頭の base.js のあとは NH.define で包まれている (二重注入で 2 度動かないため)', () => {
  const core = scriptFiles('core');
  assert.equal(core[0], 'core/base.js');
  for (const f of core.slice(1)) {
    const text = fs.readFileSync(path.join(JS_DIR, f), 'utf8');
    if (!text.replace(/^\s*\/\/.*$/gm, '').trim()) continue; // 公開版では、版だけの部分が取り除かれて、コメントだけのファイルになる (core/ads.js)
    assert.match(text, /window\.NH\.define\('[a-z-]+', function \(NH(?:, T)?\) \{/, `${f} が NH.define で包まれていません`);
    const name = text.match(/NH\.define\('([a-z-]+)'/)[1];
    assert.equal(name, path.basename(f, '.js'), `${f} の NH.define の名前がファイル名と違います`);
  }
});

t('NH.need の参照先は、そのファイルより前に読み込まれる', () => {
  const core = scriptFiles('core');
  core.forEach((f, i) => {
    const text = fs.readFileSync(path.join(JS_DIR, f), 'utf8');
    for (const m of text.matchAll(/NH\.need\('([a-z-]+)'\)/g)) {
      const at = core.findIndex((x) => path.basename(x, '.js') === m[1]);
      assert.ok(at >= 0 && at < i, `${f} が先に要る ${m[1]} は、それより前にありません`);
    }
  });
});

let failed = 0;
for (const [name, fn] of tests) {
  try { fn(); console.log('ok  ', name); } catch (e) { failed++; console.log('FAIL', name, '\n    ', e.message); }
}
console.log(failed ? `${failed} 件失敗` : `${tests.length} 件すべて成功`);
process.exit(failed ? 1 : 0);
