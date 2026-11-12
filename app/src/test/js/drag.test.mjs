// web/list/drag.js の dragTarget (長押しで持ち上げた作品が入る位置の判定) の単体テスト。実行: node app/src/test/js/drag.test.mjs
import vm from 'node:vm';
import fs from 'node:fs';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';

const src = fs.readFileSync(fileURLToPath(new URL('../../main/assets/web/list/drag.js', import.meta.url)), 'utf8');
const ctx = {};
vm.createContext(ctx);
vm.runInContext(src, ctx); // 関数と定数の宣言だけ (画面には触らない)
const dragTarget = vm.runInContext('dragTarget', ctx);

const tests = [];
const test = (name, fn) => tests.push([name, fn]);

// A (高さ 100) → B (100) → C (200)。間は 10。上端は 0 / 110 / 220
const tops = [0, 110, 220];
const hs = [100, 100, 200];

test('動かさなければ、そのまま', () => {
  for (let i = 0; i < 3; i++) assert.equal(dragTarget(tops, hs, i, 0), i);
});

test('背の高い作品 (C) を、いちばん上まで動かすと、先頭に入る (中心どうしで比べると 1 つ手前で止まっていた)', () => {
  assert.equal(dragTarget(tops, hs, 2, -220), 0); // C の上端が A の上端に着いた = いちばん上
});

test('背の低い作品 (A) を、いちばん下まで動かすと、末尾に入る', () => {
  assert.equal(dragTarget(tops, hs, 0, 320), 2); // A の下端が C の下端に着いた = いちばん下
});

test('小さな動きでは動かない。半分を越えたら 1 つ入れ替わる', () => {
  assert.equal(dragTarget(tops, hs, 2, -40), 2); // C の上端 180 > B の中心 160
  assert.equal(dragTarget(tops, hs, 2, -70), 1); // 150 < 160: B の前
});

test('下へ動かすとき、下端が下の作品の中心を越えたら、その作品の後に入る', () => {
  assert.equal(dragTarget(tops, hs, 0, 50), 0); // 下端 150 < B の中心 160
  assert.equal(dragTarget(tops, hs, 0, 70), 1); // 下端 170 > 160
});

test('作品が 1 つ・2 つでも壊れない', () => {
  assert.equal(dragTarget([0], [100], 0, 500), 0);
  assert.equal(dragTarget([0, 110], [100, 100], 1, -110), 0);
  assert.equal(dragTarget([0, 110], [100, 100], 0, 110), 1);
});

const orderedList = vm.runInContext('orderedList', ctx);
const restoreOrder = vm.runInContext('restoreOrder', ctx);
const mk = (...ks) => ks.map((k) => ({ key: k }));
const keysOf = (l) => Array.from(l, (x) => x.key); // vm の中で作った配列は、deepEqual で別物扱いになるので、こちら側の配列にする

test('orderedList: 表示中の作品の並びを新しい順にし、見えていない作品の位置は変えない', () => {
  const list = mk('a', 'x', 'b', 'y', 'c'); // x・y は絞り込みで見えていない
  assert.deepEqual(keysOf(orderedList(list, ['c', 'a', 'b'])), ['c', 'x', 'a', 'y', 'b']);
});

test('orderedList: keys に同じ作品が 2 回あっても、作品を増やさず並びを壊さない', () => {
  const out = orderedList(mk('a', 'b', 'c'), ['c', 'a', 'a', 'b']);
  assert.deepEqual(keysOf(out), ['c', 'a', 'b']);
  assert.equal(out.length, 3);
});

test('orderedList: 動かしているあいだに消えた作品が keys にあっても、空の項目を入れず、ほかの作品を消さない', () => {
  const list = mk('a', 'c'); // b は消えた
  const out = orderedList(list, ['c', 'b', 'a']);
  assert.deepEqual(keysOf(out), ['c', 'a']);
  assert.ok(out.every((x) => x && typeof x.key === 'string'));
});

test('orderedList: 動かしているあいだに増えた作品 (keys に無い) は、そのまま残る', () => {
  const list = mk('a', 'b', 'new');
  assert.deepEqual(keysOf(orderedList(list, ['b', 'a'])), ['b', 'a', 'new']);
});

test('restoreOrder: 元の並びへ戻し、そのあとに増えた作品を続ける (消えた作品は飛ばす)', () => {
  assert.deepEqual(keysOf(restoreOrder(mk('c', 'a', 'b'), ['a', 'b', 'c'])), ['a', 'b', 'c']);
  assert.deepEqual(keysOf(restoreOrder(mk('c', 'a', 'new'), ['a', 'b', 'c'])), ['a', 'c', 'new']); // b は消えた・new は増えた
});

test('sortItems: 並べ替えの値は作品ごとに 1 回だけ求め、保存データの読み込みは 1 回だけ (比べるたびに読み込まない)', () => {
  let loads = 0;
  const sctx = { NH: { readState: (d, x) => ({ unread: x.u }), siteLabel: (x) => x.site, flag: () => true }, load: () => { loads++; return {}; } };
  vm.createContext(sctx);
  vm.runInContext(fs.readFileSync(fileURLToPath(new URL('../../main/assets/web/list/sort.js', import.meta.url)), 'utf8'), sctx);
  const items = Array.from({ length: 50 }, (_, i) => ({ key: 'k' + i, u: i % 7, title: 't' + (50 - i) }));
  const out = vm.runInContext('sortItems', sctx)(items, 'unread');
  assert.equal(loads, 1);
  assert.equal(out[0].u, 6); // 未読が多い順
  assert.equal(out[out.length - 1].u, 0);
  assert.deepEqual(vm.runInContext('sortItems', sctx)(items, 'manual'), items); // 手動の並びはそのまま
});

let failed = 0;
for (const [name, fn] of tests) {
  try { fn(); console.log('ok  ', name); } catch (e) { failed++; console.log('FAIL', name, '\n    ', e.message); }
}
console.log(failed ? `${failed} 件失敗` : `${tests.length} 件すべて成功`);
process.exit(failed ? 1 : 0);
