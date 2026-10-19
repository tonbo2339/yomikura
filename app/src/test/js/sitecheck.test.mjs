// sitecheck.js (サイトの表示の診断) の計算部分の単体テスト。実行: node app/src/test/js/sitecheck.test.mjs
import fs from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';

const JS = (name) => fileURLToPath(new URL('../../main/assets/js/' + name, import.meta.url));
const ctx = { window: {}, location: { hostname: 'ncode.syosetu.com', pathname: '/n1234ab/5/' }, document: { querySelectorAll: () => [] }, console };
ctx.window = ctx;
vm.createContext(ctx);
for (const f of ['site/sites.js', 'site/sitecheck.js']) vm.runInContext(fs.readFileSync(JS(f), 'utf8'), ctx);
const C = ctx.NH_SITECHECK, SITES = ctx.NH_SITES;

const eq = (a, b) => assert.deepEqual(JSON.parse(JSON.stringify(a)), b); // vm の中の配列は、そのままでは比べられない
const tests = [];
const test = (name, fn) => tests.push([name, fn]);

test('splitSelectors: 最上位のカンマだけで分ける', () => {
  eq(C.splitSelectors('#a, .b,[class*="x,y"] , #c > div:has(> a.t, > a.u)'), ['#a', '.b', '[class*="x,y"]', '#c > div:has(> a.t, > a.u)']);
  eq(C.splitSelectors(''), []);
  eq(C.splitSelectors(' , ,'), []);
});

test('hideSelectors: CSS のルールから { の前のセレクターを取り出す', () => {
  eq(C.hideSelectors('#header,#footer,#nav2+br{display:none!important}'), ['#header', '#footer', '#nav2+br']);
  eq(C.hideSelectors('.a{display:none!important}.b,.c{padding-top:0!important}'), ['.a', '.b', '.c']);
  eq(C.hideSelectors(undefined), []);
});

test('hideSelectors: 実際の sites.js のどのセレクターも、空でなく { や } を含まない', () => {
  for (const [k, s] of Object.entries(SITES)) {
    for (const sel of [...C.hideSelectors(s.hide), ...C.hideSelectors(s.bars)]) {
      assert.ok(sel && !/[{}]/.test(sel), `${k}: ${JSON.stringify(sel)}`);
    }
  }
});

test('siteKeyOf: ホストからサイトを決める', () => {
  assert.equal(C.siteKeyOf('ncode.syosetu.com'), 'narou');
  assert.equal(C.siteKeyOf('novel18.syosetu.com'), 'narou');
  assert.equal(C.siteKeyOf('syosetu.org'), 'hameln');
  assert.equal(C.siteKeyOf('kakuyomu.jp'), 'kakuyomu');
  assert.equal(C.siteKeyOf('example.com'), null);
  assert.equal(C.siteKeyOf('notsyosetu.org'), null);
});

test('shapeOf: 数字と ncode を置き換える (作品の ID を載せない)', () => {
  assert.equal(C.shapeOf('/n1234ab/5/'), '/nXXXX/9/');
  assert.equal(C.shapeOf('/works/1177354054/episodes/9'), '/works/9/episodes/9');
  assert.equal(C.shapeOf('/rank/daily/'), '/rank/daily/');
  assert.equal(C.shapeOf(''), '/');
  assert.equal(C.shapeOf('/novel/123456/7.html'), '/novel/9/9.html'); // /novel は ncode ではない
});

test('plan: 話ページは本文と欄と隠す対象、ほかは隠す対象だけ', () => {
  const ep = C.plan('ncode.syosetu.com', '/n1234ab/5/', SITES);
  assert.equal(ep.key, 'narou'); assert.ok(ep.episode);
  eq(ep.items.filter((i) => i.critical).map((i) => i.selector), ['.p-novel__text', '.p-novel__text,.p-novel__text p']);
  assert.ok(ep.items.some((i) => i.selector === '.l-header'));
  const rank = C.plan('yomou.syosetu.com', '/rank/daily/', SITES);
  assert.ok(!rank.episode); assert.ok(rank.items.every((i) => !i.critical && !i.label.startsWith('畳む')));
  assert.ok(!rank.items.some((i) => i.selector === '.l-header'), 'ヘッダーは話ページ以外では隠さないので、調べない');
  assert.equal(C.plan('example.com', '/', SITES), null);
});

test('run: 本文が 0 件なら NG、欄が 0 件でも NG にしない', () => {
  const p = C.plan('ncode.syosetu.com', '/n1234ab/5/', SITES);
  const ok = C.run(p, (sel) => (sel.includes('p-novel__text') ? 1 : 0));
  assert.ok(ok.verdict.startsWith('OK'), ok.verdict);
  const ng = C.run(p, () => 0);
  assert.ok(ng.verdict.startsWith('NG: 本文'), ng.verdict);
  const bad = C.run(p, (sel) => (sel.startsWith('.l-footer') ? -1 : 1));
  assert.ok(bad.verdict.startsWith('NG: 使えない'), bad.verdict);
});

test('text: 作品の ID・URL を載せず、判定とセレクターを載せる', () => {
  const p = C.plan('ncode.syosetu.com', '/n1234ab/5/', SITES);
  const t = C.text('ncode.syosetu.com', '/n1234ab/5/', p, C.run(p, () => 1), ['注意: x']);
  assert.ok(t.startsWith('# サイトの表示の診断\nページ: ncode.syosetu.com /nXXXX/9/ (話ページ)\nサイト: narou\n判定: OK'));
  assert.ok(t.includes('注意: x') && t.includes('.p-novel__text'));
  assert.ok(!t.includes('1234'), '作品の ID が入っています');
});

test('nhSiteCheck: ページの中で呼ぶ。対応サイトでなければ null、不正なセレクターは -1', () => {
  const c2 = { window: {}, location: { hostname: 'ncode.syosetu.com', pathname: '/n1234ab/5/' }, document: { querySelectorAll: (s) => { if (s.startsWith('.l-footer')) throw new Error('bad'); return [1]; } }, console };
  c2.window = c2; vm.createContext(c2);
  for (const f of ['site/sites.js', 'site/sitecheck.js']) vm.runInContext(fs.readFileSync(JS(f), 'utf8'), c2);
  assert.ok(c2.nhSiteCheck().includes('NG: 使えない'));
  const c3 = { window: {}, location: { hostname: 'example.com', pathname: '/' }, document: { querySelectorAll: () => [] }, console };
  c3.window = c3; vm.createContext(c3);
  for (const f of ['site/sites.js', 'site/sitecheck.js']) vm.runInContext(fs.readFileSync(JS(f), 'utf8'), c3);
  assert.equal(c3.nhSiteCheck(), null);
});

let failed = 0;
for (const [name, fn] of tests) {
  try { fn(); console.log('ok  ', name); } catch (e) { failed++; console.log('FAIL', name, '\n    ', e.message); }
}
console.log(failed ? `${failed} 件失敗` : `${tests.length} 件すべて成功`);
process.exit(failed ? 1 : 0);
