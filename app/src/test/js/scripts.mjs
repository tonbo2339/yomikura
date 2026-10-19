// JS のテストが、アプリと同じ一覧 (app/src/main/assets/js/scripts.json) から、読み込むファイルを決めるための道具。
// ファイルを足す・順番を変えるときは scripts.json だけを直す (Kotlin の ScriptManifest と同じ決まり: '@名前' はそのグループの展開)。
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const JS_DIR = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../main/assets/js');
const manifest = JSON.parse(fs.readFileSync(path.join(JS_DIR, 'scripts.json'), 'utf8'));

/** assets/js からの相対パス → 絶対パス */
export const jsPath = (rel) => path.join(JS_DIR, rel);

/** assets/js の中のファイルの中身 */
export const readJs = (rel) => fs.readFileSync(jsPath(rel), 'utf8');

/** グループ (core / adapters / site / early) のファイルの相対パスを、順番どおりに */
export function scriptFiles(group, depth = 0) {
  if (depth >= 4) throw new Error('scripts.json: 展開が深すぎます: ' + group);
  const list = manifest[group];
  if (!Array.isArray(list)) throw new Error('scripts.json: グループがありません: ' + group);
  return list.flatMap((name) => (name.startsWith('@') ? scriptFiles(name.slice(1), depth + 1) : [name]));
}

/** vm のコンテキストで、グループのファイルを順に実行する */
export function runGroup(vm, ctx, group) {
  for (const rel of scriptFiles(group)) vm.runInContext(readJs(rel), ctx, { filename: rel });
}
