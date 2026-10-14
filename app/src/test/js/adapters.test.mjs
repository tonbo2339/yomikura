// サイトごとのアダプター (narou / hameln / kakuyomu) の、DOM に依存しない部分の単体テスト。実行: node app/src/test/js/adapters.test.mjs
import fs from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';

const JS = (name) => fileURLToPath(new URL('../../main/assets/js/' + name, import.meta.url));

function load(href, localStorage) {
  const u = new URL(href);
  const style = { textContent: '' };
  const ctx = {
    NHStore: undefined,
    localStorage: localStorage || { getItem: () => null, setItem: () => {} },
    location: { href, hostname: u.hostname, pathname: u.pathname, search: u.search, hash: u.hash, origin: u.origin },
    document: { createElement: () => style, head: { appendChild: () => {} }, documentElement: { appendChild: () => {} }, querySelectorAll: () => [] },
    setTimeout, URL, console,
  };
  ctx.window = ctx;
  vm.createContext(ctx);
  for (const f of ['sites.js', 'core.js', 'narou.js', 'hameln.js', 'kakuyomu.js']) vm.runInContext(fs.readFileSync(JS(f), 'utf8'), ctx);
  return ctx.NH;
}

const tests = [];
const test = (name, fn) => tests.push([name, fn]);
const eq = (a, b) => assert.deepEqual(JSON.parse(JSON.stringify(a)), b);

test('parseWork: なろう (作品トップ・話・R18)', () => {
  const NH = load('https://ncode.syosetu.com/n1234ab/');
  eq(NH.parseWork('https://ncode.syosetu.com/n1234ab/'), { site: 'narou', id: 'n1234ab', url: 'https://ncode.syosetu.com/n1234ab/', episode: null });
  eq(NH.parseWork('https://ncode.syosetu.com/n1234ab/12/').episode, 12);
  eq(NH.parseWork('https://novel18.syosetu.com/n1234ab/3/').site, 'narou18');
  assert.equal(NH.parseWork('https://yomou.syosetu.com/rank/top/'), null);
  assert.equal(NH.parseWork('https://example.com/n1234ab/'), null);
});

test('parseWork: ハーメルン・カクヨム', () => {
  const NH = load('https://syosetu.org/');
  eq(NH.parseWork('https://syosetu.org/novel/12345/7.html'), { site: 'hameln', id: '12345', url: 'https://syosetu.org/novel/12345/', episode: 7 });
  // R-18 の作品のページ (h.syosetu.org) も同じ作品 (キー hameln:ID) として扱う。作品の URL はそのホストのまま (保存の作業がそのホストで話を取るため)
  eq(NH.parseWork('https://h.syosetu.org/novel/12345/7.html'), { site: 'hameln', id: '12345', url: 'https://h.syosetu.org/novel/12345/', episode: 7 });
  assert.equal(NH.workUrl('hameln', '12345', 'https://h.syosetu.org/novel/12345/7.html'), 'https://h.syosetu.org/novel/12345/');
  assert.equal(NH.workUrl('hameln', '12345'), 'https://syosetu.org/novel/12345/');
  eq(NH.parseWork('https://kakuyomu.jp/works/99/episodes/5'), { site: 'kakuyomu', id: '99', url: 'https://kakuyomu.jp/works/99', episode: '5' });
  assert.equal(NH.parseWork('https://kakuyomu.jp/rankings/all/daily'), null);
  assert.equal(NH.parseWork('::::'), null);
});

test('adapter: ホストの判定は末尾が似ているだけのホストを通さない', () => {
  assert.equal(load('https://evilsyosetu.com/').adapter(), null);
  assert.equal(load('https://yomou.syosetu.com/').adapter().id, 'narou');
  assert.equal(load('https://novel18.syosetu.com/n1234ab/').adapter().id, 'narou18');
});

// カクヨムの作品ページ (埋め込みデータ __NEXT_DATA__) の代わり。章 → 話の並び
function kakuyomuWorkDoc(workId, chapters) {
  const st = { ['Work:' + workId]: { tableOfContentsV2: chapters.map((_, i) => ({ __ref: 'C:' + i })) } };
  chapters.forEach((eps, i) => {
    st['C:' + i] = { episodeUnions: eps.map((id) => ({ __ref: 'E:' + id })) };
    eps.forEach((id) => { st['E:' + id] = { id, title: 't' + id }; });
  });
  const json = JSON.stringify({ props: { pageProps: { __APOLLO_STATE__: st } } });
  return { title: 'work', getElementById: (id) => (id === '__NEXT_DATA__' ? { textContent: json } : null) };
}
function makeLocalStorage(initial = {}) {
  const m = new Map(Object.entries(initial));
  return { getItem: (k) => (m.has(k) ? m.get(k) : null), setItem: (k, v) => m.set(k, String(v)), m };
}

test('カクヨム episodeNo: 章をまたいだ目次の並びで何話目かを数え、控えると通信しない', async () => {
  const ls = makeLocalStorage();
  const NH = load('https://kakuyomu.jp/works/7/episodes/300', ls);
  let fetched = 0;
  NH.fetchListDoc = async () => { fetched++; return kakuyomuWorkDoc('7', [['100', '200'], ['300', '400', '500']]); };
  const a = NH.adapter();
  assert.equal(await a.episodeNo({ id: '7', episode: '300' }), 3); // 2 章目の最初 = 3 話目
  assert.equal(fetched, 1);
  assert.equal(await a.episodeNo({ id: '7', episode: '500' }), 5); // 控えにある: 通信しない
  assert.equal(await a.episodeNo({ id: '7', episode: '100' }), 1);
  assert.equal(fetched, 1);
  assert.deepEqual(JSON.parse(ls.getItem('nh-toc:7')).ids, ['100', '200', '300', '400', '500']);
});

test('カクヨム episodeNo: 控えに今の話が無ければ (新しい話) 取り直す。さっき取り直したなら取りに行かない', async () => {
  const old = { at: Date.now() - 2 * 86400000, ids: ['100', '200'] };
  const NH = load('https://kakuyomu.jp/works/7/episodes/300', makeLocalStorage({ 'nh-toc:7': JSON.stringify(old) }));
  let fetched = 0;
  NH.fetchListDoc = async () => { fetched++; return kakuyomuWorkDoc('7', [['100', '200', '300']]); };
  const a = NH.adapter();
  assert.equal(await a.episodeNo({ id: '7', episode: '300' }), 3); // 1 日以上前の控えに無い → 取り直す
  assert.equal(fetched, 1);
  // 目次に無い話 (非公開など): 取り直したばかりなので、続けて取りに行かない
  assert.equal(await a.episodeNo({ id: '7', episode: '999' }), null);
  assert.equal(fetched, 1);
});

test('カクヨム episodeNo: 確認画面などで取れなければ例外 (呼び出し側が休止する)。目次が読めなければ null で控えない', async () => {
  const ls = makeLocalStorage();
  const NH = load('https://kakuyomu.jp/works/7/episodes/300', ls);
  const a = NH.adapter();
  NH.fetchListDoc = async () => { throw new Error(NH.CHALLENGE); };
  await assert.rejects(() => a.episodeNo({ id: '7', episode: '300' }), /challenge/);
  NH.fetchListDoc = async () => ({ title: 'x', getElementById: () => null });
  assert.equal(await a.episodeNo({ id: '7', episode: '300' }), null);
  assert.equal(ls.getItem('nh-toc:7'), null);
});

let failed = 0;
for (const [name, fn] of tests) {
  try { await fn(); console.log('ok   ' + name); } catch (e) { failed++; console.log('FAIL ' + name + '\n     ' + e.message); }
}
console.log(failed ? failed + ' 件失敗' : tests.length + ' 件すべて成功');
process.exit(failed ? 1 : 0);
