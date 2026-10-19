// 設定画面の確かめ: 復元の取り消し (settings/backup.js の applyUndo) と、版ごとの設定画面の JS (src/<版>/assets/web/settings/*.js) が
// $('ID') で使う要素が、設定画面の HTML (共通とその版) にあるか。版の名前はフォルダを見て決める (公開版の書き出しに版の名前を残さない)。
// 実行: node app/src/test/js/settings-backup.test.mjs
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const SRC = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const read = (rel) => fs.readFileSync(path.join(SRC, rel), 'utf8');

const tests = [];
const t = (name, fn) => tests.push([name, fn]);

// backup.js から関数を 1 つだけ取り出す (画面の部品に触る部分は動かさない)
function extractFunction(file, name) {
  const text = read(file);
  const start = text.indexOf('function ' + name + '(');
  assert.ok(start >= 0, `${file} に ${name} がありません`);
  let depth = 0;
  for (let i = text.indexOf('{', start); i < text.length; i++) {
    if (text[i] === '{') depth++;
    else if (text[i] === '}' && --depth === 0) return new Function('return ' + text.slice(start, i + 1))();
  }
  throw new Error('関数の終わりが見つかりません');
}

t('復元の取り消し: 前にあった項目は元の中身に、前に無かった項目は消す。控えに無い項目には触らない', () => {
  const applyUndo = extractFunction('main/assets/web/settings/backup.js', 'applyUndo');
  const d = { settings: { a: 2 }, mute: { words: ['x'] }, readLater: [1] };
  applyUndo(d, { keys: ['settings', 'mute'], data: { settings: { a: 1 } } });
  assert.deepEqual(d, { settings: { a: 1 }, readLater: [1] });
});

t('復元の取り消し: keys の無い古い形の控えも戻せる', () => {
  const applyUndo = extractFunction('main/assets/web/settings/backup.js', 'applyUndo');
  const d = { settings: { a: 2 } };
  applyUndo(d, { data: { settings: { a: 1 } } });
  assert.deepEqual(d, { settings: { a: 1 } });
});

const htmlIn = (dir) => (fs.existsSync(path.join(SRC, dir)) ? fs.readdirSync(path.join(SRC, dir)).filter((f) => f.endsWith('.html')).map((f) => read(dir + '/' + f)) : []);
const idsIn = (texts) => new Set(texts.flatMap((h) => [...h.matchAll(/\bid="([^"]+)"/g)].map((m) => m[1])));

t('版ごとの設定画面の JS が使う要素の ID は、設定画面の HTML (共通とその版) にある', () => {
  const common = [...htmlIn('main/assets/web'), ...htmlIn('main/assets/web/settings')];
  for (const ed of fs.readdirSync(SRC)) {
    const dir = ed + '/assets/web/settings';
    if (ed === 'main' || !fs.existsSync(path.join(SRC, dir))) continue;
    const ids = idsIn([...common, ...htmlIn(dir)]);
    for (const f of fs.readdirSync(path.join(SRC, dir)).filter((n) => n.endsWith('.js'))) {
      const used = new Set([...read(dir + '/' + f).matchAll(/\$\('([^']+)'\)/g)].map((m) => m[1]));
      for (const id of used) assert.ok(ids.has(id), `${dir}/${f} の $('${id}') に当たる要素がありません`);
    }
  }
});

let failed = 0;
for (const [name, fn] of tests) {
  try { fn(); console.log('ok  ', name); } catch (e) { failed++; console.log('FAIL', name, '\n    ', e.message); }
}
console.log(failed ? `${failed} 件失敗` : `${tests.length} 件すべて成功`);
process.exit(failed ? 1 : 0);
