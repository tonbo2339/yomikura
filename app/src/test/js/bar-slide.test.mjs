// ui-list-view.js の barSlide (下の切り替えの帯を、スクロール量に合わせて動かす純粋な関数) の単体テスト。実行: node app/src/test/js/bar-slide.test.mjs
import fs from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';

const src = fs.readFileSync(fileURLToPath(new URL('../../main/assets/js/ui/ui-list-view.js', import.meta.url)), 'utf8');
const win = {};
vm.runInNewContext(src, { window: win });
const raw = win.NH_UI_LIST_VIEW.barSlide;
const slide = (...a) => JSON.parse(JSON.stringify(raw(...a)));

// 下へ動かした分だけ隠れ、travel で止まる
assert.deepEqual(slide(0, 30, 100, false), { offset: 30, consumed: true, reset: false });
assert.equal(slide(90, 30, 100, false).offset, 100);
// 上へ戻した分だけ出て、0 で止まる
assert.equal(slide(100, -40, 100, false).offset, 60);
assert.equal(slide(10, -40, 100, false).offset, 0);
// 小さな動き (揺れ) では動かさず、基準も進めない
assert.deepEqual(slide(50, 1, 100, false), { offset: 50, consumed: false, reset: false });
assert.deepEqual(slide(50, -1.5, 100, false), { offset: 50, consumed: false, reset: false });
// 小数のまま使う
assert.equal(slide(0, 2.5, 100, false).offset, 2.5);
// 端 (先頭・末尾) では出し直す。すでに出ていれば reset にしない
assert.deepEqual(slide(60, 5, 100, true), { offset: 0, consumed: true, reset: true });
assert.deepEqual(slide(0, 5, 100, true), { offset: 0, consumed: true, reset: false });

// ---- 帯 (filterBarOf / slideWith): 偽の DOM で、表示の出し入れ・測り直し・隠れている間の扱いを確かめる ----
function setup() {
  const listeners = { scroll: [], resize: [] };
  const mkEl = () => {
    const cls = new Set();
    return {
      hidden: false, dataset: {}, style: {}, offsetHeight: 36, textContent: '', children: [],
      classList: { add: (c) => cls.add(c), remove: (c) => cls.delete(c), contains: (c) => cls.has(c), toggle: (c, on) => (on ? cls.add(c) : cls.delete(c)) },
      appendChild(c) { this.children.push(c); }, addEventListener() {}, has: (c) => cls.has(c),
    };
  };
  const root = mkEl();
  const ctx = {
    document: { documentElement: root, body: mkEl() },
    scrollY: 0, innerHeight: 800,
    addEventListener: (t, f) => listeners[t].push(f),
    requestAnimationFrame: (f) => f(),
  };
  ctx.window = ctx;
  ctx.document.documentElement.scrollHeight = 5000;
  vm.createContext(ctx);
  vm.runInContext(src, ctx);
  const picks = [];
  const mk = (tag, cls, text) => { const e = mkEl(); if (cls) e.classList.add(cls); e.textContent = text || ''; return e; };
  const view = ctx.NH_UI_LIST_VIEW.filterBarOf(mk, (k) => picks.push(k));
  const bar = ctx.document.body.children[0];
  const scrollTo = (y) => { ctx.scrollY = y; listeners.scroll.forEach((f) => f()); };
  return { ctx, root, view, bar, listeners, scrollTo, picks };
}
{
  const t = setup();
  // 作った時点で付き、0 件のページでは html の印 (余白・お知らせの避け) も付く
  t.view.paint(true, { serial: 5, complete: 3, unfinished: 2 }, '');
  assert.equal(t.root.has('nh-has-filter'), true);
  assert.equal(t.bar.children.length, 4); // すべて + 3 状態
  // 隠したら、印も外す (下の余白・お知らせの避けが残らない)
  t.view.paint(false, {}, '');
  assert.equal(t.bar.hidden, true);
  assert.equal(t.root.has('nh-has-filter'), false);
  // 隠れている間のスクロールでは動かさない
  t.scrollTo(300);
  assert.ok(!t.bar.style.transform);
  // 出し直したら、また印が付き、出た状態に戻る
  t.view.paint(true, { serial: 5, complete: 3, unfinished: 2 }, '');
  assert.equal(t.bar.hidden, false);
  assert.equal(t.root.has('nh-has-filter'), true);
}
{
  // 下へ動かすと隠れ (高さ + EXTRA まで)、回転 (resize) のあとは高さを測り直す。高さが 0 の間は測らない
  const t = setup();
  t.view.paint(true, { serial: 5, complete: 3, unfinished: 2 }, '');
  t.scrollTo(100); // 先頭でない・末尾でもない。last は 0 → dy=100 (travel = 36 + 40 = 76 で止まる)
  assert.equal(t.bar.style.transform, 'translate3d(0,76px,0)');
  t.bar.offsetHeight = 60; // 回転で帯が高くなった
  t.listeners.resize.forEach((f) => f());
  t.scrollTo(200);
  t.scrollTo(300);
  t.scrollTo(150); // 戻す: 150 だけ戻すと全部出る (offset 76 → 0)。travel が測り直されていれば 100 で止まる
  assert.equal(t.bar.style.transform, '');
  t.scrollTo(160); // 10 下へ
  assert.equal(t.bar.style.transform, 'translate3d(0,10px,0)');
  // 押したら onPick に key が渡る
  assert.equal(t.picks.length, 0);
}
{
  // 高さが 0 (まだ測れない) の間に travel を決めてしまわない
  const t = setup();
  t.view.paint(true, { serial: 5, complete: 3, unfinished: 2 }, '');
  t.bar.offsetHeight = 0;
  t.scrollTo(100);
  assert.ok(!t.bar.style.transform);
  t.bar.offsetHeight = 36;
  t.scrollTo(110);
  assert.equal(t.bar.style.transform, 'translate3d(0,76px,0)'); // 110 - 0 の動きが travel (76) で止まる
}
console.log('bar-slide ok');

// お知らせ (.nh-toast は <html> の直下に付く) を帯の上に避ける CSS は、body の子孫でなく html.nh-has-filter で指す (body:has(...) だと toast に届かない)
{
  const css = fs.readFileSync(fileURLToPath(new URL('../../main/assets/js/ui/ui-style.js', import.meta.url)), 'utf8');
  assert.ok(/html\.nh-has-filter :is\(\.nh-toast,/.test(css));
  assert.ok(!/body:has\(\.nh-filter/.test(css));
}
console.log('filter offset css ok');
