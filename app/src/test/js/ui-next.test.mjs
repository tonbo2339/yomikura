// ui/ui-next.js (話ページ最後の「読了 → 次の小説へ」のカード) の単体テスト。実行: node app/src/test/js/ui-next.test.mjs
// DOM は linkedom (無ければ飛ばす。ui-list.test.mjs と同じ)。
import vm from 'node:vm';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { runGroup, readJs } from './scripts.mjs';

function findLinkedom() {
  const bases = [process.env.NH_TEST_DEPS, fileURLToPath(new URL('../../../../', import.meta.url)), process.cwd()].filter(Boolean);
  for (const b of bases) {
    try { return createRequire(b.replace(/\/?$/, '/') + 'x.js')('linkedom'); } catch (e) { /* 次へ */ }
  }
  return null;
}
const linkedom = findLinkedom();
if (!linkedom) {
  console.log('skip ui-next.test.mjs: linkedom が見つかりません (npm i --no-save linkedom)');
  process.exit(0);
}

function makeStore(initial) {
  const s = { raw: JSON.stringify(initial), v: 0 };
  s.api = {
    get: () => s.raw, set: (json) => { s.raw = json; s.v++; }, version: () => s.v,
    setIf: (json, v) => { if (v !== s.v) return false; s.raw = json; s.v++; return true; },
  };
  return s;
}

const item = (n, extra = {}) => ({ key: 'narou:n' + n, site: 'narou', id: 'n' + n, title: 'T' + n, url: 'https://ncode.syosetu.com/n' + n + '/', done: false, kind: 'update', ...extra });

// 最終話 (次の話が無い) を開いた状態で、カードを作る。next = リストの次の作品 (n2)
function setup(data) {
  const { document, window } = linkedom.parseHTML('<html><body><div id="end"></div></body></html>');
  const store = makeStore(Object.assign({ readLater: [item(1), item(2)] }, data));
  const href = 'https://ncode.syosetu.com/n1/5/';
  const ctx = {
    __nhT: 'tok', NHStore: store.api, localStorage: { getItem: () => null, setItem: () => {} },
    location: { href, hostname: 'ncode.syosetu.com', pathname: '/n1/5/', search: '', hash: '', origin: 'https://ncode.syosetu.com' },
    document, setTimeout, clearTimeout, URL, console,
    MutationObserver: class { observe() {} },
  };
  ctx.window = ctx;
  vm.createContext(ctx);
  runGroup(vm, ctx, 'core');
  vm.runInContext(readJs('ui/ui-next.js'), ctx, { filename: 'ui/ui-next.js' });
  const NH = ctx.NH;
  const adapter = { id: 'narou', nextUrl: () => null, workTitle: () => '作品 1', pageStatus: () => 'ongoing', endCardAfter: () => document.getElementById('end') };
  ctx.NH_UI_NEXT({ adapter, mk: NH.el, cur: { site: 'narou', id: 'n1', episode: 5 } });
  const click = (el) => el.dispatchEvent(new window.Event('click', { bubbles: true, cancelable: true }));
  return { document, ctx, click, data: () => JSON.parse(store.raw) };
}

const tests = [];
const test = (name, fn) => tests.push([name, fn]);

test('お気に入りを使う設定で、まだ入れていない作品: 「この作品をお気に入りに追加」を出す (次へのボタンの文は、これまでどおり)', () => {
  const { document } = setup({});
  const fav = document.querySelector('.nh-next-fav');
  assert.ok(fav);
  assert.match(fav.textContent, /この作品をお気に入りに追加/);
  assert.match(document.querySelector('.nh-next-btn .t1').textContent, /^次の小説へ/);
  assert.equal(document.querySelector('.nh-next-btn .t3 .n').textContent, '1'); // 残りの数は、span に分けてある (縦書きで数字を縦に起こすため)
  assert.ok(document.querySelector('.nh-next-btn .t3 .n').classList.contains('tcy')); // 2 桁までは、縦書きで 1 つにまとめる
});

test('「この作品をお気に入りに追加」: お気に入りに入るだけ。次の作品へは移らず、リストにも残り、「追加しました」になる', () => {
  const { document, ctx, click, data } = setup({});
  const href = ctx.location.href;
  const fav = document.querySelector('.nh-next-fav');
  click(fav);
  const d = data();
  assert.equal(d.favorites.length, 1);
  assert.equal(d.favorites[0].key, 'narou:n1');
  assert.ok(d.readLater.find((x) => x.key === 'narou:n1')); // リストに残る (消すかどうかは、そのあとの「次へ」で決める)
  assert.equal(ctx.location.href, href); // ページはそのまま
  assert.match(fav.textContent, /お気に入りに追加しました/);
  assert.equal(fav.disabled, true);
  click(fav); // 押し直しても、二重に入らない
  assert.equal(data().favorites.length, 1);
});

test('お気に入りに追加したあと「次の小説へ」: 読み終えた作品をリストから外して次へ (お気に入りには残る)', () => {
  const { document, ctx, click, data } = setup({});
  click(document.querySelector('.nh-next-fav'));
  click(document.querySelector('.nh-next-btn'));
  const d = data();
  assert.equal(d.favorites.length, 1);
  assert.equal(d.readLater.find((x) => x.key === 'narou:n1'), undefined); // 読み終えた作品はリストから外れる (履歴に残る)
  assert.ok(d.readLater.find((x) => x.key === 'narou:n2')); // 次の作品はそのまま
  assert.match(ctx.location.href, /\/n2\//);
});

test('お気に入りに追加せずに「次の小説へ」(これまでの処理): お気に入りには入れず、リストから外して次へ', () => {
  const { document, ctx, click, data } = setup({});
  click(document.querySelector('.nh-next-btn'));
  const d = data();
  assert.equal((d.favorites || []).length, 0);
  assert.equal(d.readLater.find((x) => x.key === 'narou:n1'), undefined); // 読み終えた作品はリストから外れる (履歴に残る)
  assert.match(ctx.location.href, /\/n2\//);
});

test('すでにお気に入りの作品・お気に入りを使わない設定では、お気に入りのボタンを出さない (これまでどおりの「読了して」)', () => {
  const a = setup({ favorites: [{ key: 'narou:n1', site: 'narou', id: 'n1', title: 'T1' }] });
  assert.equal(a.document.querySelector('.nh-next-fav'), null);
  assert.match(a.document.querySelector('.nh-next-btn .t1').textContent, /^次の小説へ/);
  const b = setup({ settings: { favOn: false } });
  assert.equal(b.document.querySelector('.nh-next-fav'), null);
});

test('設定「次へ進むとき消す」をオフにすると、次へ進んでもリストから外れず (リストに残る)。お気に入りに追加しても同じ', () => {
  const a = setup({ settings: { removeOnNext: false } });
  assert.match(a.document.querySelector('.nh-next-btn .t1').textContent, /^次の小説へ/);
  a.click(a.document.querySelector('.nh-next-fav'));
  a.click(a.document.querySelector('.nh-next-btn'));
  assert.equal(a.data().favorites.length, 1);
  assert.ok(a.data().readLater.find((x) => x.key === 'narou:n1')); // リストに残る
  assert.match(a.ctx.location.href, /\/n2\//);
});

test('「消す」設定 (既定): 左は「読み終えてリストへ」(リストから外してリストへ戻る)。「残す」設定: 左は「リストから削除」(その場で削除し、「削除しました」になる)', () => {
  const on = setup({});
  on.click(on.document.querySelector('.nh-next-leave'));
  assert.equal(on.data().readLater.find((x) => x.key === 'narou:n1'), undefined); // 読み終えたのでリストから外れる
  assert.match(on.ctx.location.href, /list.html/);
  const off = setup({ settings: { removeOnNext: false } });
  const del = off.document.querySelector('.nh-next-leave');
  assert.match(del.textContent, /リストから削除/);
  const href = off.ctx.location.href;
  off.click(del);
  assert.equal(off.data().readLater.find((x) => x.key === 'narou:n1'), undefined); // リストから外れた
  assert.equal(off.ctx.location.href, href); // ページは動かない
  assert.equal(del.textContent, '削除しました');
  assert.equal(del.disabled, true);
});

test('「次の小説へ」: 作品をリストから外す前に、読書位置を書き出す (外したあとは書き戻さない)', () => {
  const { document, ctx, click, data } = setup({});
  const seen = [];
  ctx.__nhFlushPos = () => seen.push(!!data().readLater.find((x) => x.key === 'narou:n1')); // 呼ばれた時点では、まだリストにある
  click(document.querySelector('.nh-next-btn'));
  assert.deepEqual(seen, [true]);
  assert.equal(data().readLater.find((x) => x.key === 'narou:n1'), undefined);
  assert.equal(ctx.__nhNoPosWrite, true); // このあとの書き込みは止める
});

let failed = 0;
for (const [name, fn] of tests) {
  try { fn(); console.log('ok  ', name); } catch (e) { failed++; console.log('FAIL', name, '\n    ', e.message); }
}
console.log(failed ? `${failed} 件失敗` : `${tests.length} 件すべて成功`);
process.exit(failed ? 1 : 0);
