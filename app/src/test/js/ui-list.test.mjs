// ui/ui-list*.js (ランキング・検索の一覧に付ける「後で読む」「ミュート」のボタンと、ミュート・ブロックの反映) の単体テスト。
// 実行: node app/src/test/js/ui-list.test.mjs。HTML の見本は fixtures/narou_ranking.html。DOM は linkedom (無ければ飛ばす。dom.test.mjs と同じ)。
import fs from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { runGroup, readJs } from './scripts.mjs';

const FIX = (name) => fs.readFileSync(fileURLToPath(new URL('./fixtures/' + name, import.meta.url)), 'utf8');

function findLinkedom() {
  const bases = [process.env.NH_TEST_DEPS, fileURLToPath(new URL('../../../../', import.meta.url)), process.cwd()].filter(Boolean);
  for (const b of bases) {
    try { return createRequire(b.replace(/\/?$/, '/') + 'x.js')('linkedom'); } catch (e) { /* 次へ */ }
  }
  return null;
}
const linkedom = findLinkedom();
if (!linkedom) {
  console.log('skip ui-list.test.mjs: linkedom が見つかりません (npm i --no-save linkedom)');
  process.exit(0);
}

// 保存データの窓口の代わり
function makeStore(initial) {
  const s = { raw: JSON.stringify(initial), v: 0 };
  s.api = {
    get: () => s.raw, set: (json) => { s.raw = json; s.v++; }, version: () => s.v,
    setIf: (json, v) => { if (v !== s.v) return false; s.raw = json; s.v++; return true; },
  };
  return s;
}

// なろうのランキングのページを開いた状態で、アダプターと ui-list の部品を読み込み、NH_UI_LIST を動かす
function setup(initial = { readLater: [] }) {
  const href = 'https://yomou.syosetu.com/rank/daily/';
  const u = new URL(href);
  const { document, window } = linkedom.parseHTML(FIX('narou_ranking.html'));
  Object.defineProperty(window.Element.prototype, 'innerText', { get() { return this.textContent; }, configurable: true });
  if (document.createElement('a').pathname === undefined) {
    Object.defineProperty(window.HTMLAnchorElement.prototype, 'pathname', { get() { return new URL(this.getAttribute('href'), href).pathname; }, configurable: true });
  }
  // linkedom に無い、レイアウトの計算 (囲みの大きさ) は、小さな囲みとして答える
  window.Element.prototype.getBoundingClientRect = () => ({ height: 100 });
  const store = makeStore(initial);
  const observers = [];
  const ctx = {
    __nhT: 'tok', NHStore: store.api, localStorage: { getItem: () => null, setItem: () => {} },
    location: { href, hostname: u.hostname, pathname: u.pathname, search: u.search, hash: '', origin: u.origin },
    document, DOMParser: window.DOMParser, setTimeout, clearTimeout, setInterval, URL, console, innerHeight: 800,
    MutationObserver: class { constructor(fn) { observers.push(fn); } observe() {} },
  };
  ctx.window = ctx;
  vm.createContext(ctx);
  runGroup(vm, ctx, 'adapters');
  for (const f of ['ui/ui-list-later.js', 'ui/ui-list-block.js', 'ui/ui-list-view.js', 'ui/ui-list-sheet.js', 'ui/ui-list.js']) vm.runInContext(readJs(f), ctx, { filename: f });
  const NH = ctx.NH;
  ctx.NH_UI_LIST({ adapter: NH.adapter(), mk: NH.el });
  const click = (el) => el.dispatchEvent(new window.Event('click', { bubbles: true, cancelable: true }));
  const data = () => JSON.parse(store.raw);
  return { NH, document, click, data };
}

const tests = [];
const test = (name, fn) => tests.push([name, fn]);
const rows = (document) => [...document.querySelectorAll('.nh-chips')];

test('一覧の作品ごとに「後で読む」「ミュート」のボタンと、読んだ印の欄が付く', () => {
  const { document } = setup();
  const r = rows(document);
  assert.equal(r.length, 2);
  assert.deepEqual([...r[0].children].map((c) => c.textContent), ['＋ 後で読む', 'ミュート', '']);
  assert.equal(r[0].children[2].hidden, true);
});

test('「後で読む」を押すと足され、もう一度押すと外れ (元に戻すで復活)、同じ作品のボタンの見た目もそろう', () => {
  const { document, click, data } = setup();
  const btn = () => rows(document)[0].children[0];
  click(btn());
  assert.equal(data().readLater.length, 1);
  assert.equal(data().readLater[0].key, 'narou:n1234ab');
  assert.equal(data().readLater[0].status, 'serial'); // 一覧に出ている連載状態も一緒に入る
  assert.equal(btn().textContent, '✓ リストに追加済み');
  click(btn());
  assert.equal(data().readLater.length, 0);
  assert.equal(btn().textContent, '＋ 後で読む');
  const undo = document.querySelector('.nh-toast button'); // 外したときは「元に戻す」が出る
  assert.equal(undo.textContent, '元に戻す');
  click(undo);
  assert.equal(data().readLater.length, 1);
  assert.equal(btn().textContent, '✓ リストに追加済み');
});

test('読了の作品は「追加済み」とは出ず、押すと読了を外してリストに戻る', () => {
  const key = 'narou:n1234ab';
  const { document, click, data } = setup({ readLater: [{ key, site: 'narou', id: 'n1234ab', url: 'https://ncode.syosetu.com/n1234ab/', title: 't', done: true, doneAt: 1 }] });
  const btn = rows(document)[0].children[0];
  assert.equal(btn.textContent, '＋ 後で読む');
  click(btn);
  assert.equal(data().readLater.length, 1);
  assert.equal(data().readLater[0].done, false);
  assert.equal(btn.textContent, '✓ リストに追加済み');
});

test('ミュート: 作者をミュートすると、その作品が隠れ (nh-muted)、件数のボタンが出る', () => {
  const { NH, document, click, data } = setup();
  click(rows(document)[0].children[1]); // 「ミュート」
  const sheet = document.querySelector('.nh-sheet');
  assert.ok(sheet);
  const byAuthor = [...sheet.querySelectorAll('button')].find((b) => b.textContent.startsWith('作者「'));
  assert.ok(byAuthor);
  click(byAuthor);
  assert.deepEqual(data().mute.authors, ['山田 太郎']);
  assert.equal(document.querySelector('.nh-sheet'), null); // シートは閉じる
  const boxes = [...document.querySelectorAll('.p-ranklist-item')];
  assert.ok(boxes[0].classList.contains('nh-muted'));
  assert.ok(!boxes[1].classList.contains('nh-muted'));
  const pill = document.querySelector('.nh-mute-pill');
  assert.equal(pill.hidden, false);
  assert.equal(pill.textContent, '1 件をミュート中 ・ 表示する');
  assert.equal(NH.muteOf(NH.load()).authors.length, 1);
});

test('ブロック: 作品をブロックすると nh-blocked が付き、ミュートの件数に入らない', () => {
  const { document, click, data } = setup();
  click(rows(document)[1].children[1]);
  const sheet = document.querySelector('.nh-sheet');
  click([...sheet.querySelectorAll('.nh-sheet-seg button')].find((b) => b.textContent === 'ブロック'));
  const sheet2 = document.querySelector('.nh-sheet'); // 描き直される
  click([...sheet2.querySelectorAll('button')].find((b) => b.textContent === 'この作品をブロック'));
  assert.deepEqual(data().mute.block.works.map((w) => w.key), ['narou:n5678cd']);
  const boxes = [...document.querySelectorAll('.p-ranklist-item')];
  assert.ok(boxes[1].classList.contains('nh-blocked'));
  assert.ok(!boxes[1].classList.contains('nh-muted'));
  assert.equal(document.querySelector('.nh-mute-pill').hidden, true);
});

let failed = 0;
for (const [name, fn] of tests) {
  try { fn(); console.log('ok  ', name); } catch (e) { failed++; console.log('FAIL', name, '\n    ', e.stack.split('\n').slice(0, 3).join('\n     ')); }
}
console.log(failed ? `${failed} 件失敗` : `${tests.length} 件すべて成功`);
process.exit(failed ? 1 : 0);
