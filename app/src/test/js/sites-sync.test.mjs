// サイトの名前と色は、JS (core/siteinfo.js の SITE_DEFS) と Kotlin (SiteConfig.SITES・BarTheme) の両方にある。食い違っていないかを確かめる。
// 実行: node app/src/test/js/sites-sync.test.mjs
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const SRC = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const read = (p) => fs.readFileSync(path.join(SRC, p), 'utf8');
const KT = 'main/java/com/tonbo2339/yomikura/';

// JS: [{key, name, color}]
const defs = [...read('main/assets/js/core/siteinfo.js').matchAll(/\{ key: '([a-z0-9]+)', name: '([^']+)'(?:, color: '(#[0-9A-F]{6})')?/g)]
  .map((m) => ({ key: m[1], name: m[2], color: m[3] }));
// Kotlin: SiteConfig.SITES の Site("キー", "名前", ...)
const kotlinSites = [...read(KT + 'site/SiteConfig.kt').matchAll(/Site\("([a-z0-9]+)", "([^"]+)"/g)].map((m) => ({ key: m[1], name: m[2] }));
// Kotlin: BarTheme の色 (const val NAME = 0xFFRRGGBB.toInt())
const bar = read(KT + 'screen/BarTheme.kt');
const hex = (name) => '#' + bar.match(new RegExp(`const val ${name} = 0xFF([0-9A-F]{6})\.toInt\(\)`))[1];
const kotlinColor = { narou: hex('NAROU'), hameln: hex('HAMELN'), kakuyomu: hex('KAKUYOMU'), noc: hex('R18'), mnlt: hex('R18'), mid: hex('R18') };

const tests = [];
const t = (name, fn) => tests.push([name, fn]);

t('Kotlin のサイト (SiteConfig.SITES) の名前は、JS の SITE_DEFS と同じ', () => {
  assert.ok(kotlinSites.length >= 6);
  for (const k of kotlinSites) {
    const d = defs.find((x) => x.key === k.key);
    assert.ok(d, `${k.key} が SITE_DEFS にありません`);
    assert.equal(d.name, k.name, k.key);
  }
});

t('タブの点の色は、JS (SITE_DEFS) と Kotlin (BarTheme) で同じ', () => {
  for (const [key, color] of Object.entries(kotlinColor)) {
    const d = defs.find((x) => x.key === key);
    assert.equal(d.color, color, key);
  }
});

t('SITE_DEFS の色は、設定画面の色の選択肢 (TAB_COLORS) に入っている', () => {
  const tabColors = read('main/assets/web/settings/toggles-and-actions.js').match(/const TAB_COLORS = \[([^\]]*)\]/)[1];
  for (const d of defs.filter((x) => x.color)) assert.ok(tabColors.includes(d.color), `${d.key} の ${d.color} が TAB_COLORS にありません`);
});

let failed = 0;
for (const [name, fn] of tests) {
  try { fn(); console.log('ok  ', name); } catch (e) { failed++; console.log('FAIL', name, '\n    ', e.message); }
}
console.log(failed ? `${failed} 件失敗` : `${tests.length} 件すべて成功`);
process.exit(failed ? 1 : 0);
