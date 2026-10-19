// ランチャーのアイコンの選択肢が、4 か所で食い違っていないかを確かめる。
//   マニフェストの activity-alias / Kotlin の LauncherIcon.ICONS / 設定画面の LAUNCHER (state.js) / プレビューの SVG と、各リソース
// 食い違うと、選んだアイコンが効かない・消える・入口が増える、などの不具合になる。
// 実行: node app/src/test/js/icons.test.mjs
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const MAIN = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../main');
const read = (p) => fs.readFileSync(path.join(MAIN, p), 'utf8');
const exists = (p) => fs.existsSync(path.join(MAIN, p));

const manifest = read('AndroidManifest.xml');
const kotlin = read('java/com/tonbo2339/yomikura/screen/LauncherIcon.kt');
const stateJs = read('assets/web/settings/state.js');

// マニフェスト: [{name, icon, round, enabled}]
const aliases = [...manifest.matchAll(/<activity-alias\s+([\s\S]*?)>/g)].map((m) => {
  const attr = (n) => (m[1].match(new RegExp(`android:${n}="([^"]*)"`)) || [])[1];
  return { name: attr('name').replace(/^\./, ''), icon: attr('icon'), round: attr('roundIcon'), enabled: attr('enabled') };
});
// Kotlin: キー → 別名の並び (linkedMapOf の中)
const mapBlock = kotlin.match(/linkedMapOf\(([\s\S]*?)\n\s*\)/)[1];
const kotlinPairs = [...mapBlock.matchAll(/"([a-z]+)" to "(Icon[A-Za-z]+)"/g)].map((m) => [m[1], m[2]]);
const kotlinDefault = kotlin.match(/DEFAULT_ICON = "([a-z]+)"/)[1];
// 設定画面のキーの並び
const launcher = [...stateJs.match(/const LAUNCHER = \[([^\]]*)\]/)[1].matchAll(/'([a-z]+)'/g)].map((m) => m[1]);

const tests = [];
const t = (name, fn) => tests.push([name, fn]);

t('設定画面の並びと Kotlin の並びが同じ (キーと順番)', () => {
  assert.ok(launcher.length > 0);
  assert.deepEqual(kotlinPairs.map(([k]) => k), launcher);
});

t('Kotlin の別名が、マニフェストの別名と過不足なく同じ', () => {
  assert.deepEqual(kotlinPairs.map(([, a]) => a).sort(), aliases.map((a) => a.name).sort());
});

t('キーが重複していない', () => {
  assert.equal(new Set(launcher).size, launcher.length);
});

t('最初から有効なのは 1 つだけで、Kotlin の既定と同じ', () => {
  const on = aliases.filter((a) => a.enabled === 'true');
  assert.equal(on.length, 1, '有効な別名: ' + on.map((a) => a.name).join(', '));
  const def = kotlinPairs.find(([k]) => k === kotlinDefault);
  assert.ok(def, 'DEFAULT_ICON が ICONS に無い: ' + kotlinDefault);
  assert.equal(on[0].name, def[1]);
});

t('既定のアイコンは @mipmap/ic_launcher で、アプリ全体のアイコンと同じ', () => {
  const def = aliases.find((a) => a.enabled === 'true');
  assert.equal(def.icon, '@mipmap/ic_launcher');
  assert.equal(def.round, '@mipmap/ic_launcher_round');
  assert.match(manifest, /<application[\s\S]*?android:icon="@mipmap\/ic_launcher"[\s\S]*?android:roundIcon="@mipmap\/ic_launcher_round"/);
});

t('別名のアイコンと丸いアイコンのファイルがあり、中の背景・前景・単色のリソースも揃っている', () => {
  for (const a of aliases) {
    for (const ref of [a.icon, a.round]) {
      const f = `res/mipmap-anydpi-v26/${ref.replace('@mipmap/', '')}.xml`;
      assert.ok(exists(f), `${a.name}: ${f} が無い`);
      const xml = read(f);
      for (const d of xml.matchAll(/@drawable\/([a-z_]+)/g)) assert.ok(exists(`res/drawable/${d[1]}.xml`), `${f}: drawable ${d[1]} が無い`);
    }
  }
});

t('設定画面のプレビュー (assets/web/icons/<キー>.svg) が、全部のキーにある', () => {
  for (const k of launcher) assert.ok(exists(`assets/web/icons/${k}.svg`), `${k}.svg が無い`);
  const extra = fs.readdirSync(path.join(MAIN, 'assets/web/icons')).filter((f) => !launcher.includes(f.replace(/\.svg$/, '')));
  assert.deepEqual(extra, [], '使われないプレビュー: ' + extra.join(', '));
});

t('どの別名からも使われない背景・前景のリソースが残っていない', () => {
  const used = new Set();
  for (const f of fs.readdirSync(path.join(MAIN, 'res/mipmap-anydpi-v26'))) {
    for (const d of read(`res/mipmap-anydpi-v26/${f}`).matchAll(/@drawable\/([a-z_]+)/g)) used.add(d[1]);
  }
  const files = fs.readdirSync(path.join(MAIN, 'res/drawable')).filter((f) => /^ic_launcher_(bg|fg)_/.test(f));
  const unused = files.map((f) => f.replace(/\.xml$/, '')).filter((n) => !used.has(n));
  assert.deepEqual(unused, []);
});

t('別名の名前を、LauncherIcon 自身のパッケージから作っていない (リリース版は R8 でパッケージ名が変わり、別名が見つからず例外になるため)', () => {
  const code = kotlin.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/.*$/gm, ''); // 説明のコメントは除く
  assert.ok(!/LauncherIcon::class/.test(code), 'LauncherIcon::class を使っています');
  assert.ok(!/javaClass|`package`/.test(code), 'クラスのパッケージを使っています');
  assert.match(code, /MainActivity::class\.java\.name\.substringBeforeLast/);
});

t('起動時に適用する「後で」の覚えは、適用の前に消す (失敗しても、次の起動で繰り返さない)', () => {
  const body = kotlin.slice(kotlin.indexOf('fun applyPending()'));
  const remove = body.indexOf('remove(PENDING)');
  const apply = body.indexOf('set(key, restartNow = false)');
  assert.ok(remove > 0 && apply > 0 && remove < apply, '覚えを消す前に適用しています');
});

let failed = 0;
for (const [name, fn] of tests) {
  try { fn(); console.log('ok  ', name); } catch (e) { failed++; console.log('FAIL', name, '\n    ', e.message); }
}
console.log(failed ? `${failed} 件失敗` : `${tests.length} 件すべて成功`);
process.exit(failed ? 1 : 0);
