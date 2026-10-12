#!/usr/bin/env node
// アプリ内の画面 (app/src/main/assets/web) を、版ごと・ライト / ダークごとに描いて、axe-core でアクセシビリティを検査する。
// 違反があれば一覧を出して、終了コード 1 で終わる。画面を足したり、色を変えたりしたあとに流す。
//
//   npm i --no-save playwright axe-core      (最初の 1 回。Chromium は、環境の物を使うか、npx playwright install chromium)
//   node tools/a11y-check.mjs                (CHROMIUM=/path/to/chrome で、使う Chromium を指定できる)
//
// 版は app/src の main とテスト以外のフォルダを全部 (その版にだけある画面の差し込み = <!--#include--> も、アプリと同じに展開する)。
// 画面の読み込み中に JS の例外が出たときも失敗にする (版によってだけある画面の差し込みが、ほかの版で壊れていないかの確認にもなる)。
// 設定の画面は、ページごとに開いて検査する。ネイティブの窓口 (NHStore など) は無いので、中身が空の状態の画面を検査することになる。
import fs from 'node:fs';
import http from 'node:http';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const SRC = path.join(ROOT, 'app/src');
const require = createRequire(path.join(process.cwd(), 'x.js'));
let checked = 0;
let playwright, axeSource;
try {
  playwright = require('playwright');
  axeSource = fs.readFileSync(require.resolve('axe-core/axe.min.js'), 'utf8');
} catch (e) {
  console.error('playwright と axe-core が見つかりません: npm i --no-save playwright axe-core');
  process.exit(2);
}

// 版 = app/src の main とテスト以外のフォルダ (画面の部品が無い版もある)。1 つも無ければ main だけを検査する
const found0 = fs.readdirSync(SRC).filter((d) => d !== 'main' && !/^test/i.test(d) && fs.statSync(path.join(SRC, d)).isDirectory());
const editions = found0.length ? found0 : ['main'];
const rootsOf = (ed) => [path.join(SRC, ed, 'assets/web'), path.join(SRC, 'main/assets/web')];
const read = (ed, name) => {
  if (!name || name.startsWith('/') || name.includes('..') || name.includes('//')) return null; // Kotlin の Includes と同じ決まり
  for (const r of rootsOf(ed)) { const p = path.join(r, name); if (fs.existsSync(p) && fs.statSync(p).isFile()) return fs.readFileSync(p, 'utf8'); }
  return null;
};
const INCLUDE = /<!--#include ([A-Za-z0-9._/-]+)-->/g;
const expand = (ed, html, depth = 0) => depth >= 4 ? html.replace(INCLUDE, '') : html.replace(INCLUDE, (_, n) => { const b = read(ed, n); return b === null ? '' : expand(ed, b, depth + 1); });
const EDITION_JS = '<script>window.NH_EDITION={"ads":"hide","r18OptIn":true,"appName":"x","backupApp":"x"};</script>';

function serve(ed) {
  return http.createServer((q, r) => {
    const u = q.url.split('?')[0], name = u === '/' ? 'settings.html' : u.slice(1);
    const asset = u.startsWith('/js/') ? path.join(SRC, 'main/assets', u) : null;
    if (asset && fs.existsSync(asset)) { r.setHeader('Content-Type', 'text/javascript'); return r.end(fs.readFileSync(asset)); }
    const text = read(ed, name);
    if (text === null) { r.statusCode = 404; return r.end(); }
    r.setHeader('Content-Type', name.endsWith('.html') ? 'text/html; charset=utf-8' : name.endsWith('.css') ? 'text/css' : name.endsWith('.js') ? 'text/javascript' : 'image/svg+xml');
    r.end(name.endsWith('.html') ? expand(ed, text).replace('<head>', '<head>' + EDITION_JS) : text);
  }).listen(0);
}

const browser = await playwright.chromium.launch(process.env.CHROMIUM ? { executablePath: process.env.CHROMIUM } : {});
const found = new Map();
const jsErrors = [];
const watch = (label, page) => page.on('pageerror', (e) => jsErrors.push(`${label}: ${e.message}`));
async function audit(label, page) {
  checked++;
  await page.evaluate(axeSource);
  const vs = await page.evaluate(async () => (await axe.run(document, { resultTypes: ['violations'] })).violations.map((v) => ({
    id: v.id, impact: v.impact, help: v.help,
    nodes: v.nodes.map((n) => n.target.join(' ') + ' :: ' + (n.html || '').slice(0, 90)),
  })));
  for (const v of vs) {
    const e = found.get(v.id) || { impact: v.impact, help: v.help, hits: [] };
    v.nodes.forEach((n) => e.hits.push(`${label} | ${n}`));
    found.set(v.id, e);
  }
}
for (const ed of editions) {
  const srv = serve(ed), base = `http://localhost:${srv.address().port}`;
  for (const scheme of ['light', 'dark']) {
    const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    for (const name of ['list.html', 'offline.html', 'queue.html', 'welcome.html', 'error.html']) {
      if (read(ed, name) === null) continue;
      const p = await ctx.newPage(); watch(`${ed}/${scheme}/${name}`, p);
      await p.goto(`${base}/${name}`); await p.waitForTimeout(300);
      await audit(`${ed}/${scheme}/${name}`, p); await p.close();
    }
    const p = await ctx.newPage(); watch(`${ed}/${scheme}/settings`, p);
    await p.goto(`${base}/`); await p.waitForTimeout(300);
    const ids = await p.evaluate(() => [...document.querySelectorAll('section.page')].map((s) => s.id.slice(2)));
    for (const id of ids) { await p.evaluate((i) => { try { show(i); } catch (e) { /* 描けなかった画面は次へ */ } }, id); await p.waitForTimeout(60); await audit(`${ed}/${scheme}/settings:${id}`, p); }
    await ctx.close();
  }
  srv.close();
}
await browser.close();

for (const [id, v] of found) {
  console.log(`\n[${v.impact}] ${id} (${v.hits.length}): ${v.help}`);
  [...new Set(v.hits)].slice(0, 8).forEach((h) => console.log('   ', h));
}
if (jsErrors.length) { console.log('\n画面の JS で例外が出ました:'); [...new Set(jsErrors)].slice(0, 10).forEach((e) => console.log('   ', e)); }
if (!checked) { console.error('検査した画面が 0 です (画面が見つかりません)'); process.exit(2); }
console.log(found.size ? `\n違反 ${found.size} 種類 (${checked} 画面を検査)` : `違反なし (版: ${editions.join(', ')} / ライト・ダーク / ${checked} 画面を検査)`);
process.exit(found.size || jsErrors.length ? 1 : 0);
