// アダプターの DOM を読む部分 (rankingItems / statusOf / authorOf / tagsOf / parseHistory / workInfo / workTitle) の単体テスト。
// 実行: node app/src/test/js/dom.test.mjs。HTML の見本は fixtures/*.html (実サイトの形を小さく写したもの)。
// DOM は linkedom を使う。無ければこのテストは飛ばす (npm i --no-save linkedom。置き場所は NH_TEST_DEPS (node_modules のある場所) でも指定できる)。
// innerText は linkedom に無いので textContent で代用する。見本では、改行が要る所 (ハーメルンの「作者：」の行など) に実際の改行を入れてある。
import fs from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { runGroup } from './scripts.mjs';

const JS = (name) => fileURLToPath(new URL('../../main/assets/js/' + name, import.meta.url));
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
  console.log('skip dom.test.mjs: linkedom が見つかりません (npm i --no-save linkedom)');
  process.exit(0);
}

// html のページを開いた状態でアダプターを読み込む
function page(href, html) {
  const u = new URL(href);
  const { document, window } = linkedom.parseHTML(html);
  Object.defineProperty(window.Element.prototype, 'innerText', { get() { return this.textContent; }, configurable: true });
  // linkedom の <a> には pathname が無いので補う (kakuyomu の rankingItems が重複の判定に使う)
  if (document.createElement('a').pathname === undefined) {
    Object.defineProperty(window.HTMLAnchorElement.prototype, 'pathname', { get() { return new URL(this.getAttribute('href'), href).pathname; }, configurable: true });
  }
  const ctx = {
    NHStore: undefined,
    localStorage: { getItem: () => null, setItem: () => {} },
    location: { href, hostname: u.hostname, pathname: u.pathname, search: u.search, hash: u.hash, origin: u.origin },
    document, DOMParser: window.DOMParser, setTimeout, setInterval, URL, console,
  };
  ctx.window = ctx;
  vm.createContext(ctx);
  runGroup(vm, ctx, 'adapters');
  return { NH: ctx.NH, adapter: ctx.NH.adapter(), document };
}
const docOf = (html) => linkedom.parseHTML(html).document;

const tests = [];
const test = (name, fn) => tests.push([name, fn]);
const eq = (a, b) => assert.deepEqual(JSON.parse(JSON.stringify(a)), b);

test('なろう rankingItems / authorOf / tagsOf / statusOf: ランキング', () => {
  const { adapter, document } = page('https://yomou.syosetu.com/rank/daily/', FIX('narou_ranking.html'));
  const items = adapter.rankingItems();
  eq(items.map((i) => [i.title, i.href]), [
    ['異世界で畑を耕す', 'https://ncode.syosetu.com/n1234ab/'],
    ['【完結済】タイトルに状態が入る短編', 'https://ncode.syosetu.com/n5678cd/'],
  ]);
  const box1 = document.getElementById('r1'), box2 = document.getElementById('r2');
  assert.equal(adapter.authorOf(box1), '山田 太郎'); // 作者ページへのリンク
  assert.equal(adapter.authorOf(box2), '鈴木 花子'); // 「作者：」の文字
  eq(adapter.tagsOf(box1), ['TS', '異世界']); // タグ検索のリンク
  eq(adapter.tagsOf(box2), ['恋愛', 'ほのぼの']); // 「キーワード：」の文字
  assert.equal(adapter.statusOf(box1), 'serial');
  assert.equal(adapter.statusOf(box2), 'short'); // タイトルの【完結済】には反応しない
});

test('なろう rankingItems: 検索結果 (smpnovel_list) はタイトルをリンクにし、R18 のランキングトップも拾う', () => {
  const { adapter, document } = page('https://yomou.syosetu.com/search.php', FIX('narou_search_legacy.html'));
  const items = adapter.rankingItems();
  eq(items.map((i) => i.title), ['R18ランキングの作品', '検索結果の作品']);
  const link = document.querySelector('.novel_h a');
  assert.ok(link, 'タイトルが作品トップへのリンクになる');
  assert.equal(link.getAttribute('href'), 'https://ncode.syosetu.com/n9999zz/');
  adapter.rankingItems(); // 2 回目で a を二重に作らない
  assert.equal(document.querySelectorAll('.novel_h a').length, 1);
});

test('なろう rankingItems: トップのカード (a.c-novel-item)・作者ページの一覧・横の一覧。ボタンはカードの中に置く', () => {
  const { adapter, document } = page('https://syosetu.com/', FIX('narou_top.html'));
  const items = adapter.rankingItems();
  eq(items.map((i) => [i.title, i.href]), [
    ['トップのランキングの作品', 'https://ncode.syosetu.com/n1111aa/'],
    ['新着の作品', 'https://ncode.syosetu.com/n2222bb/'],
    ['作者ページの作品', 'https://ncode.syosetu.com/n3333cc/'],
    ['横の一覧の作品', 'https://ncode.syosetu.com/n4444dd/'], // 「[全9エピソード] 作者名」は題名に入れない
  ]); // 作品でないカード (n.syosetu.com/info/) は拾わない
  // カード全体がリンクの 2 種類は、ボタンをリンクの中 (anchor の子孫) に置く。置き場所が外だと、ミュートでカードを隠してもボタンが残る
  items.filter((i) => i.after).forEach((i) => assert.ok(i.anchor.contains(i.after), i.title));
  assert.equal(items[0].after.className, 'c-novel-item__title');
  assert.equal(items[2].after, undefined); // 作者ページの一覧は見出しの親ブロックの後ろ (既定)
  assert.equal(adapter.authorOf(document.getElementById('t1')), '作者 甲');
  assert.equal(adapter.authorOf(document.getElementById('t2')), '作者 乙');
});

test('なろう (R18) rankingItems: 古い作りのトップの PickUp・イチオシレビュー。メニュー・話・小説情報のリンクは除く', () => {
  const { adapter, document } = page('https://noc.syosetu.com/top/top/', FIX('narou_r18_top.html'));
  const items = adapter.rankingItems();
  eq(items.map((i) => [i.title, i.href]), [
    ['ピックアップの作品', 'https://novel18.syosetu.com/n1111aa/'],
    ['題名に「・」が付く作品', 'https://novel18.syosetu.com/n2222bb/'], // 題名の前の「・」は除く
    ['ミッドナイトの作品', 'https://novel18.syosetu.com/n4444dd/'],
    ['レビューされた作品', 'https://novel18.syosetu.com/n5555ee/'],
  ]);
  items.forEach((i) => assert.equal(i.after, i.anchor)); // ボタンはリンクのすぐ後ろ = 囲みの中 (ミュートで一緒に隠れる)
  assert.equal(document.getElementById('menu1') && items.some((i) => i.anchor.id === 'menu1'), false);
});

test('なろう parseHistory: 続きのリンクと全エピソード数。作品でないカードは除く', () => {
  const { adapter } = page('https://yomou.syosetu.com/rireki/list/', FIX('narou_history.html'));
  const items = adapter.parseHistory(adapter && docOf(FIX('narou_history.html')));
  eq(items.map(({ anchor, ...rest }) => rest), [
    { id: 'n1234ab', title: '履歴の作品A', url: 'https://ncode.syosetu.com/n1234ab/4/', total: 12 },
    { id: 'n5678cd', title: '履歴の作品B', url: 'https://ncode.syosetu.com/n5678cd/', total: null },
  ]);
  assert.equal(adapter.isHistoryPage({ hostname: 'yomou.syosetu.com', pathname: '/rireki/list/' }), true);
});

test('なろう workInfo: 連載 (全話数・状態・掲載サイト) / 短編 / 読めない', async () => {
  const { NH, adapter } = page('https://ncode.syosetu.com/n1234ab/', '<html><body></body></html>');
  const w = { id: 'n1234ab', url: 'https://ncode.syosetu.com/n1234ab/' };
  NH.fetchListDoc = async () => docOf(FIX('narou_infotop_serial.html'));
  eq(await adapter.workInfo(w), { total: 45, status: 'serial', sub: 'mnlt' });
  NH.fetchListDoc = async () => docOf(FIX('narou_infotop_short.html'));
  eq(await adapter.workInfo(w), { total: 1, status: 'short', sub: '' });
  NH.fetchListDoc = async () => docOf('<html><body>何もない</body></html>');
  eq(await adapter.workInfo(w), { total: null, status: '', sub: '' });
});

test('なろう workTitle: 話ページは上の作品リンク、目次はタイトル。どちらも無ければ空', () => {
  assert.equal(page('https://ncode.syosetu.com/n1234ab/3/', FIX('narou_episode.html')).adapter.workTitle(), '作品の 名前');
  assert.equal(page('https://ncode.syosetu.com/n1234ab/', FIX('narou_episode.html')).adapter.workTitle(), '第3話 サブタイトル');
  assert.equal(page('https://ncode.syosetu.com/n1234ab/', '<html><head><title>エラー</title></head><body></body></html>').adapter.workTitle(), '');
});

test('なろう workTitle: ログインしていない話ページ (上の案内が「ログイン」のリンク) でも、作品名を返す', () => {
  const html = '<html><head><title>無職転生　- 異世界行ったら本気だす -</title></head><body>'
    + '<div class="c-announce c-announce--note">ブックマーク機能を使うには<a href="https://syosetu.com/login/input/?redirectlink=x" class="c-announce__emphasis">ログイン</a>してください。</div>'
    + '<div class="p-novel"><a href="/n1234ab/">無職転生　- 異世界行ったら本気だす -</a><h1 class="p-novel__title">第二話</h1></div></body></html>';
  assert.equal(page('https://ncode.syosetu.com/n1234ab/3/', html).adapter.workTitle(), '無職転生 - 異世界行ったら本気だす -');
});

test('ハーメルン rankingItems / authorOf / tagsOf / statusOf: 検索結果', () => {
  const { adapter, document } = page('https://syosetu.org/search/', FIX('hameln_search.html'));
  const items = adapter.rankingItems();
  eq(items.map((i) => i.title), ['ハーメルンの作品', '完結した作品']); // 「1位 」は除く
  const b1 = document.getElementById('h1'), b2 = document.getElementById('h2');
  assert.equal(adapter.authorOf(b1), '佐藤 次郎');
  eq(adapter.tagsOf(b1), ['オリジナル', 'R-18', 'ガールズラブ', '日常']); // 原作 + タグ
  eq(adapter.tagsOf(b2), []);
  assert.equal(adapter.statusOf(b1), 'serial');
  assert.equal(adapter.statusOf(b2), 'complete');
});

test('ハーメルン rankingItems / authorOf / tagsOf / statusOf: トップ・作者ページ (ul.entry > li > a)。話・お知らせのリンクは除く', () => {
  const { adapter, document } = page('https://syosetu.org/', FIX('hameln_top.html'));
  const items = adapter.rankingItems();
  eq(items.map((i) => [i.title, i.href]), [
    ['トップの作品', 'https://syosetu.org/novel/405603/'], // 題名のあとの .date の欄は入れない
    ['特殊タグの例', 'https://syosetu.org/novel/94624/'],
  ]);
  items.forEach((i) => assert.ok(i.anchor.contains(i.after) && i.after.className === 'date', i.title)); // ボタンはカードの中 (.date の後ろ)
  const h1 = document.getElementById('h1'), h2 = document.getElementById('h2');
  assert.equal(adapter.authorOf(h1), '作者 甲'); // 「連載：117話　作：作者 甲」
  assert.equal(adapter.authorOf(h2), ''); // 作者名の無いカード。「原作：」を作者にしない
  eq(adapter.tagsOf(h2), ['ポケットモンスター']);
  assert.equal(adapter.statusOf(h1), 'serial');
  assert.equal(adapter.statusOf(h2), 'short');
});

test('ハーメルン parseHistory / workInfo', async () => {
  const { NH, adapter } = page('https://syosetu.org/?mode=rireki', '<html><body></body></html>');
  const items = adapter.parseHistory(docOf(FIX('hameln_history.html')));
  eq(items.map(({ anchor, ...rest }) => rest), [
    { id: '12345', title: '履歴の作品', url: 'https://syosetu.org/novel/12345/2.html', total: 16 },
    { id: '777', title: 'しおりなし', url: 'https://syosetu.org/novel/777/', total: null },
  ]);
  NH.fetchListDoc = async () => docOf(FIX('hameln_ssdetail.html'));
  eq(await adapter.workInfo({ id: '12345' }), { total: 8, status: 'serial', sub: '', r18: true });
  const detail = (v) => docOf('<html><body><table><tr><th>話数</th><td>' + v + '</td></tr></table></body></html>');
  NH.fetchListDoc = async () => detail('連載(完結) 16話');
  eq((await adapter.workInfo({ id: '2' })).status, 'complete');
  NH.fetchListDoc = async () => detail('短編 1話');
  eq((await adapter.workInfo({ id: '3' })).status, 'short');
  NH.fetchListDoc = async () => docOf('<html><body>空</body></html>');
  eq(await adapter.workInfo({ id: '1' }), { total: null, status: '', sub: '', r18: false });
});

test('ハーメルン workTitle: 短編・1 話の作品の作品トップ (本文つき。ページの題は「作品名 - 話名」) でも、見出しの作品名を返す', () => {
  const html = '<html><head><title>作品A - 話の題 - ハーメルン</title></head><body><h1 id="pagetitle"><a href="./">作品A</a></h1><div id="maind"><p>本文</p></div></body></html>';
  assert.equal(page('https://syosetu.org/novel/430424/', html).adapter.workTitle(), '作品A');
});

test('ハーメルン statusOf: トップの「新着短編小説」の見出しの下の作品は、作品の欄に「短編」の字が無くても短編。連載の見出しの下は読み取らない', () => {
  const html = '<html><body><ul class="entry"><li class="id5"><strong>新着短編小説</strong></li>'
    + '<li><a id="s1" href="https://syosetu.org/novel/1/" class="id1">短編の作品<br><span class="date">オリジナル：恋愛<br>作：甲</span></a></li></ul>'
    + '<ul class="entry"><li class="id5"><strong>新着投稿作品</strong></li>'
    + '<li><a id="s2" href="https://syosetu.org/novel/2/" class="id1">別の作品<br><span class="date">オリジナル：恋愛<br>作：乙</span></a></li></ul></body></html>';
  const { adapter, document } = page('https://syosetu.org/', html);
  assert.equal(adapter.statusOf(document.getElementById('s1')), 'short');
  assert.equal(adapter.statusOf(document.getElementById('s2')), '');
});

test('カクヨム rankingItems / authorOf / statusOf: 重複・話へのリンク・今読んでいる作品の欄は除く', () => {
  const { adapter, document } = page('https://kakuyomu.jp/rankings/all/weekly', FIX('kakuyomu_ranking.html'));
  const items = adapter.rankingItems();
  eq(items.map((i) => i.title), ['カクヨムの作品', '完結作品']);
  assert.equal(adapter.authorOf(document.getElementById('r1')), '作者 一郎');
  assert.equal(adapter.statusOf(document.getElementById('r1')), 'serial');
  assert.equal(adapter.statusOf(document.getElementById('r3')), 'complete');
  assert.equal(adapter.statusOf(document.getElementById('r2')), '');
});

test('カクヨム workTitle / workInfo / pageStatus / parseHistory', () => {
  const { adapter } = page('https://kakuyomu.jp/works/111', FIX('kakuyomu_work.html'));
  assert.equal(adapter.workTitle(), '作品名');
  eq(adapter.workInfo(), { total: 44, status: 'serial', sub: '' });
  assert.equal(adapter.pageStatus(), 'serial');
  // 見出しが無いときはページ名「作品名（作者） - カクヨム」から。エラーのページ名は作品名にしない
  const t = page('https://kakuyomu.jp/works/111', '<html><head><title>話 - 別作品（誰か） - カクヨム</title></head><body></body></html>').adapter;
  assert.equal(t.workTitle(), '別作品');
  assert.equal(page('https://kakuyomu.jp/works/1', '<html><head><title>お探しのページは見つかりません</title></head><body></body></html>').adapter.workTitle(), '');
  const items = adapter.parseHistory(docOf(FIX('kakuyomu_history.html')));
  eq(items.map(({ anchor, ...rest }) => rest), [{ id: '111', title: '履歴の作品', url: 'https://kakuyomu.jp/works/111/resume_reading', total: 3 }]);
});

let failed = 0;
for (const [name, fn] of tests) {
  try { await fn(); console.log('ok   ' + name); } catch (e) { failed++; console.log('FAIL ' + name + '\n     ' + e.message); }
}
console.log(failed ? failed + ' 件失敗' : tests.length + ' 件すべて成功');
process.exit(failed ? 1 : 0);
