// core.js の単体テスト。実行: node app/src/test/js/core.test.mjs (Node 18 以上)
// アプリの窓口 (NHStore) の代わりに、版付きの置き場をこのファイルで用意する
import fs from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';

const CORE = fileURLToPath(new URL('../../main/assets/js/core.js', import.meta.url));

// NHStore の代わり。onSetIf を差し込むと、書く直前に「ほかの WebView が先に書いた」状態を作れる
function makeStore(initial) {
  const s = { raw: JSON.stringify(initial), v: 0, onSetIf: null, setIfCalls: 0 };
  s.api = {
    get: (t) => (t === 'tok' ? s.raw : ''),
    set: (json, t) => { if (t === 'tok') { s.raw = json; s.v++; } },
    version: () => s.v,
    setIf: (json, v, t) => {
      s.setIfCalls++;
      if (s.onSetIf) { const f = s.onSetIf; s.onSetIf = null; f(); }
      if (t !== 'tok' || v !== s.v) return false;
      s.raw = json; s.v++;
      return true;
    },
  };
  return s;
}

function loadCore(store, extra = {}) {
  const style = { textContent: '' };
  const ctx = {
    __nhT: 'tok',
    NHStore: store.api,
    localStorage: { getItem: () => null, setItem: () => {} },
    location: { href: 'https://yomikura.local/list.html', hostname: 'yomikura.local', pathname: '/list.html' },
    document: { createElement: () => style, head: { appendChild: () => {} }, documentElement: { appendChild: () => {} }, querySelectorAll: () => [] },
    setTimeout, URL, console,
    ...extra,
  };
  ctx.window = ctx;
  vm.createContext(ctx);
  vm.runInContext(fs.readFileSync(CORE, 'utf8'), ctx);
  return ctx.NH;
}

const tests = [];
const test = (name, fn) => tests.push([name, fn]);

test('合言葉はページから消える', () => {
  const st = makeStore({ readLater: [] });
  const NH = loadCore(st);
  assert.equal(NH.load().readLater.length, 0);
  assert.equal(st.api.get(undefined), ''); // 合言葉なしでは読めない
});

test('NH.update: ほかが先に書いていたら読み直してやり直す (書き込みが消えない)', () => {
  const st = makeStore({ readLater: [], progress: {} });
  const NH = loadCore(st);
  // 1 回目の setIf の直前に、ほかの WebView が progress を書いた
  st.onSetIf = () => {
    const other = JSON.parse(st.raw);
    other.progress['narou:n1111aa'] = { url: 'u', ep: '1', y: 0.5, at: 1 };
    st.raw = JSON.stringify(other); st.v++;
  };
  let calls = 0;
  NH.update((d) => { calls++; d.readLater.push({ key: 'hameln:1', site: 'hameln', id: '1', done: false }); });
  const d = JSON.parse(st.raw);
  assert.equal(calls, 2);
  assert.equal(d.readLater.length, 1);
  assert.ok(d.progress['narou:n1111aa'], 'ほかの書き込みが残っている');
});

test('mergeUpdates: やり直しても数が二重にならない', () => {
  const st = makeStore({ readLater: [{ key: 'kakuyomu:1', site: 'kakuyomu', id: '1', title: 'A', done: false, kind: 'update', unread: 1, lastEp: 5 }] });
  const NH = loadCore(st);
  st.onSetIf = () => { st.v++; }; // 1 回目は必ず失敗させる
  const r = NH.mergeUpdates('kakuyomu', [
    { id: '1', title: 'A', url: 'u1', unread: 2, lastEp: 6 }, // 話が増えた
    { id: '2', title: 'B', url: 'u2', unread: 3, lastEp: 3 }, // 新しい作品
  ]);
  assert.deepEqual({ added: r.added, updates: r.updates, fresh: [...r.fresh] }, { added: 1, updates: 2, fresh: ['A', 'B'] });
  const d = JSON.parse(st.raw);
  assert.equal(d.readLater.length, 2);
});

test('mergeUpdates: 「後で読む」の作品は未読にしない・今回無い更新は読了にする', () => {
  const st = makeStore({ readLater: [
    { key: 'hameln:1', site: 'hameln', id: '1', title: 'L', done: false, kind: 'later' },
    { key: 'hameln:2', site: 'hameln', id: '2', title: 'U', done: false, kind: 'update', unread: 1, lastEp: 3 },
  ] });
  const NH = loadCore(st);
  const r = NH.mergeUpdates('hameln', [{ id: '1', title: 'L', url: 'x', unread: 2, lastEp: 9 }]);
  assert.equal(r.updates, 0);
  const d = JSON.parse(st.raw);
  assert.equal(d.readLater[0].unread, undefined);
  assert.equal(d.readLater[0].lastEp, 9);
  assert.equal(d.readLater[1].done, true);
});

test('mergeUpdates: 読了にした作品は、新しい話が来たら未読に戻す (最終話が分からなくても、未読が増えれば)', () => {
  const st = makeStore({ readLater: [
    { key: 'kakuyomu:1', site: 'kakuyomu', id: '1', title: 'A', done: true, doneAt: 1, kind: 'update', unread: 0, lastEp: null },
    { key: 'narou:n1', site: 'narou', id: 'n1', title: 'B', done: true, doneAt: 1, kind: 'update', unread: 0, lastEp: 10 },
    { key: 'kakuyomu:2', site: 'kakuyomu', id: '2', title: 'C', done: true, doneAt: 1, kind: 'update', unread: 2, lastEp: null },
  ] });
  const NH = loadCore(st);
  NH.mergeUpdates('kakuyomu', [{ id: '1', title: 'A', url: 'u', unread: 1, lastEp: null }, { id: '2', title: 'C', url: 'u', unread: 2, lastEp: null }]);
  NH.mergeUpdates('narou', [{ id: 'n1', title: 'B', url: 'u', unread: 1, lastEp: 11 }]);
  const d = JSON.parse(st.raw);
  const by = (k) => d.readLater.find((x) => x.key === k);
  assert.equal(by('kakuyomu:1').done, false); // 最終話が分からず、未読が 0 → 1
  assert.equal(by('narou:n1').done, false); // 最終話が 10 → 11
  assert.equal(by('kakuyomu:2').done, true); // 未読が増えていない (前と同じ 2) なら、読了のまま
});

test('pruneProgress: リストの作品と、それ以外の最近 10 作品だけ残す', () => {
  const st = makeStore({ readLater: [] });
  const NH = loadCore(st);
  const progress = {};
  for (let i = 0; i < 15; i++) progress['narou:n' + (1000 + i) + 'aa'] = { at: i };
  progress['hameln:9'] = { at: -1 }; // 古いがリストにある
  progress['hameln:8'] = { at: 100 }; // 新しいが読了済み (リストでは残す対象でない)
  const d = { readLater: [{ key: 'hameln:9', done: false }, { key: 'hameln:8', done: true }], progress };
  NH.pruneProgress(d);
  const keys = Object.keys(d.progress);
  assert.ok(keys.includes('hameln:9'));
  assert.ok(keys.includes('hameln:8'));
  assert.equal(keys.length, 11); // hameln:9 + (hameln:8 を含む新しい 10 件)
  assert.ok(!keys.includes('narou:n1000aa'));
});

test('siteKey / siteLabel: R18 はサイトごと、タブの名前を優先', () => {
  const st = makeStore({ readLater: [], settings: { siteNames: { noc: 'ノク' } } });
  const NH = loadCore(st);
  assert.equal(NH.siteKey({ site: 'narou18', sub: 'mnlt' }), 'mnlt');
  assert.equal(NH.siteKey({ site: 'narou18' }), 'narou18');
  assert.equal(NH.siteLabel({ site: 'narou18', sub: 'noc' }), 'ノク');
  assert.equal(NH.siteLabel({ site: 'narou18' }), 'R18');
  assert.equal(NH.siteLabel({ site: 'kakuyomu' }), 'カクヨム');
});

tests.push(['collectOn / collectAny: 表示するサイトで、collectSites がオフでないもの', () => {
  const NH = loadCore(makeStore({ readLater: [] }));
  assert.equal(NH.collectAny({}), true); // 未設定は全部対象
  const off = { settings: { collectSites: { hameln: false } } };
  assert.equal(NH.collectOn(off, 'hameln'), false);
  assert.equal(NH.collectOn(off, 'narou'), true);
  // 表示しないサイトは、collectSites がオンでも対象外。R18 は 3 サイトのどれかが表示中なら対象
  const hidden = { settings: { sites: { noc: false, mnlt: false, mid: true, kakuyomu: false } } };
  assert.equal(NH.collectOn(hidden, 'kakuyomu'), false);
  assert.equal(NH.collectOn(hidden, 'narou18'), true);
  assert.equal(NH.collectOn({ settings: { sites: { noc: false, mnlt: false, mid: false } } }, 'narou18'), false);
  // 全部オフなら collectAny は false
  const none = { settings: { collectSites: { narou: false, narou18: false, hameln: false, kakuyomu: false } } };
  assert.equal(NH.collectAny(none), false);
}]);

tests.push(['NSFW モード: オンなら R18 の作品を隠し、R18 は更新確認の対象外 (オフなら何も変わらない)', () => {
  const NH = loadCore(makeStore({ readLater: [] }));
  const on = { settings: { nsfw: true }, readLater: [{ key: 'hameln:1', site: 'hameln', r18: true }, { key: 'narou:n1', site: 'narou' }, { key: 'narou18:n2', site: 'narou18' }] };
  assert.equal(NH.nsfwOn(on), true);
  assert.equal(NH.nsfwHides(on, on.readLater[0]), true); // ハーメルンの R-18
  assert.equal(NH.nsfwHides(on, on.readLater[1]), false); // 通常の作品
  assert.equal(NH.nsfwHides(on, on.readLater[2]), true); // R18 のサイトの作品
  assert.equal(NH.nsfwHides(on, { key: 'hameln:1', site: 'hameln' }), true); // 履歴: 同じキーのリストの作品の印を見る
  assert.equal(NH.collectOn(on, 'narou18'), false);
  assert.equal(NH.collectOn(on, 'narou'), true);
  const off = { settings: {}, readLater: on.readLater };
  assert.equal(NH.nsfwOn(off), false);
  assert.equal(NH.nsfwHides(off, off.readLater[2]), false);
  assert.equal(NH.collectOn(off, 'narou18'), true);
}]);

tests.push(['seqRest: 絞り込みの並びの続きで、読んでいない作品だけを順に返す', () => {
  const NH = loadCore(makeStore({ readLater: [] }));
  const item = (key, done = false) => ({ key, done });
  const d = { readLater: [item('a'), item('b'), item('c', true), item('d'), item('e')] };
  // 並びは a → c → e → b (並べ替え後の順)。c は読了なので飛ばす
  NH.setReadSeq(d, ['a', 'c', 'e', 'b']);
  assert.deepEqual(NH.seqRest(d, d.readLater[0]).map((x) => x.key), ['e', 'b']);
  assert.deepEqual(NH.seqRest(d, d.readLater[4]).map((x) => x.key), ['b']);
  assert.deepEqual(NH.seqRest(d, d.readLater[1]), []); // b は並びの最後 (続きは無い)
  assert.deepEqual(NH.seqRest(d, d.readLater[3]), null); // d は並びに無い (通常の次の作品を使う)
  // 絞り込みなし (null) や 1 日以上前の並びは使わない
  NH.setReadSeq(d, null);
  assert.equal(NH.seqRest(d, d.readLater[0]), null);
  NH.setReadSeq(d, ['a', 'b']);
  d.readSeq.at -= NH.READ_SEQ_TTL + 1;
  assert.equal(NH.seqRest(d, d.readLater[0]), null);
}]);

tests.push(['pruneHistory: 作品数と日数の上限で古い履歴を消す', () => {
  const NH = loadCore(makeStore({ readLater: [] }));
  const DAY = 86400000, now = Date.now();
  const mk = () => ({ history: [
    { key: 'a', at: now - 1 * DAY }, { key: 'b', at: now - 10 * DAY }, { key: 'c', at: now - 40 * DAY },
    { key: 'd', at: -1, siteAt: now - 2 * DAY }, // 公式にしか無い作品は、公式で見かけた時刻で数える
    { key: 'e', at: -2, siteAt: now - 100 * DAY },
  ] });
  // 未設定 = 300 作品・無期限なので何も消えない
  let d = mk(); assert.equal(NH.pruneHistory(d), 0); assert.equal(d.history.length, 5);
  // 日数 30: c と e が消える
  d = mk(); d.settings = { historyDays: 30 };
  assert.equal(NH.pruneHistory(d), 2); assert.deepEqual(d.history.map((e) => e.key), ['a', 'b', 'd']);
  // 作品数 2: 新しい順の先頭 2 件だけ残す
  d = mk(); d.settings = { historyMax: 2 };
  assert.equal(NH.pruneHistory(d), 3); assert.deepEqual(d.history.map((e) => e.key), ['a', 'b']);
  // 作品数 0 は無制限
  d = mk(); d.settings = { historyMax: 0 }; assert.equal(NH.pruneHistory(d), 0);
  // 履歴が無いデータでも落ちない
  assert.equal(NH.pruneHistory({ readLater: [] }), 0);
}]);

tests.push(['backupJson: 選んだ項目だけ、無い項目は空で入れる', () => {
  const NH = loadCore(makeStore({ readLater: [] }));
  const d = { readLater: [{ key: 'a' }], settings: { theme: 'dark' }, collectLog: { x: 1 } };
  const o = JSON.parse(NH.backupJson(d, ['list', 'history', 'settings'], '1.2.3'));
  assert.equal(o.app, 'yomikura'); // NH_EDITION が無いときの既定 assert.equal(o.format, 1); assert.equal(o.appVersion, '1.2.3');
  assert.deepEqual(Object.keys(o.parts), ['list', 'history', 'settings']);
  assert.deepEqual(o.parts.list, [{ key: 'a' }]);
  assert.deepEqual(o.parts.history, []); // 無い項目は空
  assert.equal(JSON.stringify(o).includes('collectLog'), false); // 一時的なものは入れない
}]);

tests.push(['ブロック: 作品・作者・キーワードで判定し、ミュートとは別に持つ', () => {
  const st = makeStore({ readLater: [], mute: { words: ['追放'], authors: [], mode: 'hide' } });
  const NH = loadCore(st);
  NH.addBlock('works', { key: 'narou:n1', title: 'A' });
  NH.addBlock('works', { key: 'narou:n1', title: 'A' }); // 重複は足さない
  NH.addBlock('authors', '作者X');
  NH.addBlock('words', 'ハーレム');
  let d = NH.load();
  assert.equal(JSON.stringify(NH.blockOf(d).works), JSON.stringify([{ key: 'narou:n1', title: 'A' }]));
  assert.equal(JSON.stringify(NH.muteOf(d).words), '["追放"]'); // ミュートはそのまま
  assert.equal(NH.blockReason(d, { key: 'narou:n1', title: 'なにか' }), 'この作品');
  assert.equal(NH.blockReason(d, { key: 'narou:n2', title: 't', author: '作者Ｘ' }), '作者: 作者Ｘ'); // 全角半角はそろえる
  assert.equal(NH.blockReason(d, { key: 'narou:n3', title: 't', tags: ['ハーレム'] }), 'キーワード: ハーレム');
  assert.equal(NH.blockReason(d, { key: 'narou:n4', title: '追放された' }), null); // ミュートのキーワードはブロックではない
  assert.equal(NH.muteReason(d, { title: '追放された' }), 'キーワード: 追放');
  NH.removeBlock('works', 'narou:n1');
  NH.removeBlock('words', 'ハーレム');
  d = NH.load();
  assert.equal(NH.blockReason(d, { key: 'narou:n1', title: 'x' }), null);
  assert.equal(NH.hasBlock(d), true); // 作者が残っている
  NH.removeBlock('authors', '作者X');
  assert.equal(NH.hasBlock(NH.load()), false);
  assert.equal(NH.hasBlock({ readLater: [] }), false);
}]);

test('nextPool: 並びの続き > 同じ種類 > 残り全体の順で次の作品を選ぶ', () => {
  const NH = loadCore(makeStore({ readLater: [] }));
  const mk = (id, kind, done) => ({ key: 'narou:' + id, kind, done: !!done, title: id });
  const a = mk('a', 'update'), b = mk('b', 'later'), c = mk('c', 'update'), z = mk('z', 'update', true);
  let d = { readLater: [a, b, c, z] };
  let r = NH.nextPool(d, a);
  assert.equal(r.next.key, 'narou:c'); // 同じ種類 (update) を優先
  assert.equal(r.remaining, 1);
  assert.equal(r.seq, false);
  d = { readLater: [a, b] };
  assert.equal(NH.nextPool(d, a).next.key, 'narou:b'); // 同じ種類が無ければ残り全体
  d = { readLater: [a], readSeq: { keys: ['narou:a'], at: Date.now() } };
  r = NH.nextPool(d, a);
  assert.equal(r.seq, true); // 並びの最後: 次は無い
  assert.equal(r.next, undefined);
});

test('selectEpisodes: すべて / 範囲 / えらぶ', () => {
  const NH = loadCore(makeStore({ readLater: [] }));
  const eps = [1, 2, 3, 4].map((n) => ({ ep: String(n) }));
  const plan = { eps, todo: eps.filter((e) => e.ep !== '2') }; // 2 話目は保存済み
  const ids = (l) => l.map((e) => e.ep).join(',');
  assert.equal(ids(NH.selectEpisodes(plan, { mode: 'all' })), '1,3,4');
  assert.equal(ids(NH.selectEpisodes(plan, { mode: 'range', from: 2, to: 3 })), '3'); // 目次の順番で数え、保存済みは除く
  assert.equal(ids(NH.selectEpisodes(plan, { mode: 'pick', picked: new Set(['4', '2']) })), '4');
});

test('scrollState: 本文の位置と、読み終えた判定', () => {
  const NH = loadCore(makeStore({ readLater: [] }));
  let r = NH.scrollState(100, 100, 1000, 500);
  assert.equal(r.y, 0);
  assert.equal(r.done, false);
  r = NH.scrollState(600, 100, 1000, 500);
  assert.equal(r.y, 0.5);
  assert.equal(r.done, true); // 画面の下端が本文の 100%
});

test('sanitize: 型が違う項目と、壊れた作品を捨てて、画面が白くならないようにする', () => {
  const st = makeStore({ readLater: [] });
  const NH = loadCore(st);
  const d = NH.sanitize({ readLater: [null, 5, {}, { key: 'narou:a1', url: 'u' }], settings: 'x', progress: null, history: [1] });
  assert.deepEqual(d.readLater.map((e) => e.key), ['narou:a1']);
  assert.equal('settings' in d, false);
  assert.equal('progress' in d, false);
  assert.equal(JSON.stringify(d.history), '[1]'); // 閲覧履歴は配列なので残す (オブジェクト扱いで消した不具合の再発防止)
  assert.equal('history' in NH.sanitize({ readLater: [], history: {} }), false);
  assert.equal(JSON.stringify(NH.sanitize('5')), '{"readLater":[]}'); // 数字や文字なら空のデータ (vm の別の環境のオブジェクトなので JSON で比べる)
  assert.equal(JSON.stringify(NH.sanitize(null)), '{"readLater":[]}');
});

test('cleanNode: 挿絵は端末に保存した物 (img/...) だけ残す。サイトの画像の URL は残さない', () => {
  const st = makeStore({ readLater: [] });
  const NH = loadCore(st);
  const el = (tag, attrs, kids) => ({ nodeType: 1, tagName: tag, childNodes: kids || [], getAttribute: (k) => (attrs && k in attrs ? attrs[k] : null) });
  const txt = (v) => ({ nodeType: 3, nodeValue: v });
  const root = el('DIV', {}, [
    el('IMG', { 'data-nh-local': 'img/i0a1b2c3d.jpg' }),
    el('IMG', { src: 'img/i0a1b2c3d.png' }),              // 保存したあとの本文 (読む画面で使う)
    el('IMG', { src: 'https://evil.example/x.jpg' }),      // サイトの URL: 残さない
    el('IMG', { src: 'img/../../x.jpg' }),                 // 形が違う: 残さない
    el('IMG', { 'data-nh-local': 'img/i0a1b2c3d.svg' }),   // SVG は対象外: 残さない
    el('P', {}, [txt('本文 & <b>')]),
  ]);
  assert.equal(NH.cleanNode(root), '<img src="img/i0a1b2c3d.jpg" alt=""><img src="img/i0a1b2c3d.png" alt=""><p>本文 &amp; &lt;b&gt;</p>');
});

test('epNumber: なろう・ハーメルンは ep、カクヨムは no (目次の並びの番号)。短編・分からないものは null', () => {
  const NH = loadCore(makeStore({}));
  assert.equal(NH.epNumber({ ep: '18' }), 18);
  assert.equal(NH.epNumber({ ep: '119', no: 120 }), 120); // no があれば優先
  assert.equal(NH.epNumber({ ep: '822139839701948462', no: 37 }), 37); // カクヨムの話 ID は連番でない
  assert.equal(NH.epNumber({ ep: '822139839701948462' }), null); // no が未記録の読書位置
  assert.equal(NH.epNumber({ ep: '0' }), null); // 短編
  assert.equal(NH.epNumber({ ep: '3', no: 0 }), 3); // 不正な no は使わない
  assert.equal(NH.epNumber({ ep: '3', no: 2.5 }), 3);
  assert.equal(NH.epNumber({ ep: '3', no: 100000 }), 3);
  assert.equal(NH.epNumber(null), null);
  assert.equal(NH.epNumber(undefined), null);
});

test('collectActive: 設定で対象 かつ ログインしているサイトだけ。logins が未記録なら、ログイン済みとみなす。R18 はなろうと同じログイン', () => {
  const NH = loadCore(makeStore({}));
  const d = (settings, logins) => ({ settings, logins });
  assert.equal(NH.collectActive(d({}), 'narou'), true); // logins が無い (まだ写していない) = ログイン済み扱い
  assert.equal(NH.collectAnyActive(d({})), true);
  const none = { narou: false, hameln: false, kakuyomu: false };
  assert.equal(NH.collectAnyActive(d({}, none)), false); // どこにもログインしていない
  assert.equal(NH.collectActive(d({}, { narou: true, hameln: false, kakuyomu: false }), 'hameln'), false); // ログインしていないサイトは飛ばす
  assert.equal(NH.collectActive(d({}, { narou: true }), 'narou'), true);
  assert.equal(NH.collectActive(d({}, { narou: true }), 'narou18'), true); // R18 はなろうのログイン
  assert.equal(NH.collectActive(d({}, { narou: false, kakuyomu: true }), 'narou18'), false);
  // ログインしていても、設定で対象でなければ確認しない
  assert.equal(NH.collectActive(d({ collectSites: { hameln: false } }, { hameln: true }), 'hameln'), false);
  assert.equal(NH.collectActive(d({ sites: { kakuyomu: false } }, { kakuyomu: true }), 'kakuyomu'), false);
  assert.equal(NH.collectActive(d({ nsfw: true }, { narou: true }), 'narou18'), false); // NSFW モードで R18 の確認はオフ
  assert.equal(NH.collectAnyActive(d({ collectSites: { narou: false, hameln: false } }, { kakuyomu: true })), true);
  assert.equal(NH.loginOf(d({}, { hameln: false }), 'hameln'), false);
  assert.equal(NH.loginOf(null, 'hameln'), true);
});

test('裏での確認の時刻: ほかの時刻と 30 分以上あける (0:00 をまたぐ差も測る)', () => {
  const NH = loadCore(makeStore({}));
  assert.equal(NH.BG_MIN_GAP, 30);
  assert.equal(NH.timeGap('07:00', '07:30'), 30);
  assert.equal(NH.timeGap('23:50', '00:10'), 20); // 0:00 をまたぐ
  assert.equal(NH.timeGap('07:00', 'abc'), Infinity); // 読めない値は比べない
  const times = ['07:30', '21:00'];
  assert.equal(NH.timeSlotOk(times, -1, '07:59'), false); // 追加: 29 分差
  assert.equal(NH.timeSlotOk(times, -1, '08:00'), true); // 30 分ちょうどは OK
  assert.equal(NH.timeSlotOk(times, -1, '07:00'), true);
  assert.equal(NH.timeSlotOk(times, -1, '07:01'), false); // 07:30 の 29 分前
  assert.equal(NH.timeSlotOk(times, -1, '06:45'), true); // 45 分前
  assert.equal(NH.timeSlotOk(times, 0, '07:45'), true); // 自分自身 (0 番目) は比べない: 時刻 1 を 07:30 → 07:45 に直せる
  assert.equal(NH.timeSlotOk(times, 0, '20:45'), false); // 21:00 に 15 分差
  assert.equal(NH.timeSlotOk(['00:10'], -1, '23:50'), false);
  assert.equal(NH.freeTimeSlot(['07:30', '21:00']), '12:00');
  assert.equal(NH.freeTimeSlot(['12:00']), '12:30'); // 12:00 は使えない。次の空き (30 分刻み)
  const full = []; for (let m = 0; m < 1440; m += 30) full.push(String(Math.floor(m / 60)).padStart(2, '0') + ':' + String(m % 60).padStart(2, '0'));
  assert.equal(NH.freeTimeSlot(full), null); // 30 分刻みで埋まっている: 追加できない
});

test('NH.load: 版が変わらない間は読み直さず、書き込みのあとは新しい内容を返す', () => {
  const st = makeStore({ readLater: [] });
  let gets = 0;
  const get = st.api.get;
  st.api.get = (t) => { gets++; return get(t); };
  const NH = loadCore(st);
  assert.equal(NH.load(), NH.load());
  assert.equal(gets, 1);
  NH.update((d) => d.readLater.push({ key: 'narou:n1' }));
  assert.equal(NH.load().readLater.length, 1); // 版が進んだので読み直す
  assert.equal(NH.load(), NH.load());
});

// ---- 追加: 履歴・読書位置・ミュート・バックアップなど ----
const D = 86400000;
const now = () => Date.now();
const NO_TITLE_DOC = { createElement: () => ({ textContent: '' }), head: { appendChild: () => {} }, documentElement: { appendChild: () => {} }, querySelectorAll: () => [] };

test('recordVisit: 履歴に新しい順で入り、目次を開いただけなら読んだ話は残す', () => {
  const st = makeStore({ readLater: [] });
  const NH = loadCore(st);
  const w = { site: 'narou', id: 'n1111aa' };
  NH.recordVisit({ ...w, episode: 3 }, '作品A', 'https://x/n1111aa/3/', false);
  NH.recordVisit({ site: 'narou', id: 'n2222bb', episode: null }, '作品B', 'https://x/n2222bb/', false);
  NH.recordVisit({ ...w, episode: null }, '作品A', 'https://x/n1111aa/', false); // 目次だけ
  const h = JSON.parse(st.raw).history;
  assert.deepEqual(h.map((e) => e.key), ['narou:n1111aa', 'narou:n2222bb']);
  assert.equal(h[0].url, 'https://x/n1111aa/3/');
  assert.equal(h[0].ep, '3');
  assert.ok(h[0].at === h[0].appAt && h[0].at > 0);
  NH.recordVisit({ ...w, episode: null }, '作品A', 'https://x/n1111aa/', true); // 短編扱いなら url / ep も更新
  assert.equal(JSON.parse(st.raw).history[0].url, 'https://x/n1111aa/');
});

test('recordVisit: 作品名が分からなければ記録しない。履歴オフなら記録しない。名前は履歴 / リストから補う', () => {
  const st = makeStore({ readLater: [{ key: 'narou:n1111aa', site: 'narou', id: 'n1111aa', title: 'リストの名前' }] });
  const NH = loadCore(st);
  NH.recordVisit({ site: 'narou', id: 'n9999zz', episode: 1 }, '', 'u', false);
  assert.equal(JSON.parse(st.raw).history, undefined);
  NH.recordVisit({ site: 'narou', id: 'n1111aa', episode: 1 }, '', 'u', false);
  assert.equal(JSON.parse(st.raw).history[0].title, 'リストの名前');
  const off = makeStore({ readLater: [], settings: { history: false } });
  const NH2 = loadCore(off);
  NH2.recordVisit({ site: 'narou', id: 'n1111aa', episode: 1 }, 'A', 'u', false);
  assert.equal(JSON.parse(off.raw).history, undefined);
});

test('recordVisit: 履歴オフでも R18 のサイト (sub) はリストに書き足す。書き込みは 1 回で済む', () => {
  const st = makeStore({ readLater: [{ key: 'narou18:n1111aa', site: 'narou18', id: 'n1111aa', title: 'R' }], settings: { history: false } });
  const NH = loadCore(st, { location: { href: 'https://noc.syosetu.com/n1111aa/', hostname: 'noc.syosetu.com', pathname: '/n1111aa/' } });
  const v0 = st.v;
  NH.recordVisit({ site: 'narou18', id: 'n1111aa', episode: null }, 'R', 'u', false);
  assert.equal(JSON.parse(st.raw).readLater[0].sub, 'noc');
  assert.equal(st.v - v0, 1);
  const st2 = makeStore({ readLater: [{ key: 'narou18:n1111aa', site: 'narou18', id: 'n1111aa', title: 'R' }] });
  const NH2 = loadCore(st2, { location: { href: 'https://noc.syosetu.com/n1111aa/', hostname: 'noc.syosetu.com', pathname: '/n1111aa/' } });
  NH2.recordVisit({ site: 'narou18', id: 'n1111aa', episode: 2 }, 'R', 'u', false);
  const d2 = JSON.parse(st2.raw);
  assert.equal(d2.readLater[0].sub, 'noc');
  assert.equal(d2.history[0].sub, 'noc');
  assert.equal(st2.v, 1); // リストの sub と履歴を 1 回の書き込みで
});

test('pruneHistory: 日数と作品数の両方で古いものを消す (既定は 300 作品・無期限)', () => {
  const NH = loadCore(makeStore({ readLater: [] }));
  const mk = (n, ago) => Array.from({ length: n }, (_, i) => ({ key: 'narou:n' + i, at: now() - ago - i }));
  const d = { readLater: [], history: mk(305, 0) };
  assert.equal(NH.pruneHistory(d), 5);
  assert.equal(d.history.length, 300);
  const d2 = { readLater: [], settings: { historyDays: 7, historyMax: 0 }, history: [{ key: 'a', at: now() - 10 * D }, { key: 'b', at: 1, siteAt: now() - D }, { key: 'c', at: now() - D }] };
  assert.equal(NH.pruneHistory(d2), 1); // 公式で見かけた時刻 (siteAt) が新しければ残る
  assert.deepEqual(d2.history.map((e) => e.key), ['b', 'c']);
  const d3 = { readLater: [], settings: { historyMax: 2 }, history: mk(5, 0) };
  NH.pruneHistory(d3);
  assert.equal(d3.history.length, 2);
  assert.equal(NH.pruneHistory({ readLater: [] }), 0);
});

test('importSiteHistory: 新しい作品だけ数え、公式にしか無い作品はアプリで読んだ作品より後ろに並べる', () => {
  const st = makeStore({ readLater: [], history: [{ key: 'hameln:1', site: 'hameln', id: '1', title: 'A', at: now() - 1000, appAt: now() - 1000 }] });
  const NH = loadCore(st);
  const items = [{ id: '2', title: 'B', url: 'u2', total: 7 }, { id: '1', title: 'A', url: 'u1', total: null }];
  assert.equal(NH.importSiteHistory('hameln', items), 1);
  const h = JSON.parse(st.raw).history;
  assert.deepEqual(h.map((e) => e.id), ['1', '2']);
  assert.equal(h[1].at, -1); // 公式の並びの 1 番目
  assert.equal(h[1].total, 7);
  assert.equal(h[0].siteUrl, 'u1');
  assert.ok(h[0].appAt, 'アプリで読んだ印は残る');
  assert.equal(NH.importSiteHistory('hameln', items), 0); // 2 回目は増えない
  const off = makeStore({ readLater: [], settings: { history: false } });
  assert.equal(loadCore(off).importSiteHistory('hameln', items), 0);
  assert.equal(JSON.parse(off.raw).history, undefined);
});

test('pruneDone: 読了から日数がたった作品と、その読書位置を消す。pruneDone=false なら消さない', () => {
  const NH = loadCore(makeStore({ readLater: [] }));
  const mk = () => ({
    readLater: [
      { key: 'a', done: true, doneAt: now() - 40 * D },
      { key: 'b', done: true, doneAt: now() - 5 * D },
      { key: 'c', done: true }, // doneAt なし: 今から数える
      { key: 'd', done: false },
    ],
    progress: { a: {}, b: {} },
  });
  const d = mk();
  assert.equal(NH.pruneDone(d), 1);
  assert.deepEqual(d.readLater.map((x) => x.key), ['b', 'c', 'd']);
  assert.deepEqual(Object.keys(d.progress), ['b']);
  assert.ok(d.readLater[1].doneAt > 0);
  const d2 = mk(); d2.settings = { pruneDays: 3 };
  assert.equal(NH.pruneDone(d2), 2); // a と b
  const d3 = mk(); d3.settings = { pruneDone: false };
  assert.equal(NH.pruneDone(d3), 0);
  assert.equal(d3.readLater.length, 4);
});

test('blockReason / muteReason: 作者は完全一致、キーワードはタイトルとタグに含まれれば一致 (全角半角・大小は区別しない)', () => {
  const NH = loadCore(makeStore({ readLater: [] }));
  const d = { readLater: [], mute: { authors: ['山田 太郎'], words: ['ＴＳ'], block: { works: [{ key: 'narou:n1', title: 'x' }], authors: ['悪者'], words: ['ng'] } } };
  assert.equal(NH.muteReason(d, { title: 'a', author: '山田 太郎', tags: [] }), '作者: 山田 太郎');
  assert.equal(NH.muteReason(d, { title: 'a', author: '山田', tags: [] }), null);
  assert.equal(NH.muteReason(d, { title: 'ts もの', author: '', tags: [] }), 'キーワード: ＴＳ');
  assert.equal(NH.muteReason(d, { title: 'a', author: '', tags: ['TS転生'] }), 'キーワード: ＴＳ');
  assert.equal(NH.blockReason(d, { key: 'narou:n1', title: 'q' }), 'この作品');
  assert.equal(NH.blockReason(d, { key: 'narou:n2', title: 'q', author: '悪者' }), '作者: 悪者');
  assert.equal(NH.blockReason(d, { key: 'narou:n2', title: 'NG集', tags: [] }), 'キーワード: ng');
  assert.equal(NH.blockReason(d, { key: 'narou:n2', title: 'ok' }), null);
  assert.equal(NH.blockReason({ readLater: [] }, { key: 'k', title: 't' }), null);
  assert.equal(NH.hasBlock(d), true);
  assert.equal(NH.hasBlock({ readLater: [] }), false);
});

test('addBlock / addMute: 重複を足さず、removeBlock で外せる', () => {
  const st = makeStore({ readLater: [] });
  const NH = loadCore(st);
  NH.addBlock('works', { key: 'narou:n1', title: 'T' });
  NH.addBlock('works', { key: 'narou:n1', title: 'T' });
  NH.addBlock('words', ' ＮＧ ');
  NH.addBlock('words', 'ng');
  NH.addMute('authors', 'A'); NH.addMute('authors', 'a');
  let m = JSON.parse(st.raw).mute;
  assert.equal(m.block.works.length, 1);
  assert.deepEqual(m.block.words, ['ＮＧ']);
  assert.deepEqual(m.authors, ['A']); // 大小違いは同じ扱い
  NH.removeBlock('works', 'narou:n1');
  assert.equal(JSON.parse(st.raw).mute.block.works.length, 0);
});

test('resumeUrl: 読書位置が新しければそれ。読了なら次の話。更新取得の方が新しければ url', () => {
  const NH = loadCore(makeStore({ readLater: [] }));
  const it = { key: 'k', url: 'upd', urlAt: 1000 };
  assert.equal(NH.resumeUrl({ progress: {} }, it), 'upd');
  assert.equal(NH.resumeUrl({ progress: { k: { url: 'ep3', at: 2000, done: false } } }, it), 'ep3');
  assert.equal(NH.resumeUrl({ progress: { k: { url: 'ep3', at: 2000, done: true, next: 'ep4' } } }, it), 'ep4');
  assert.equal(NH.resumeUrl({ progress: { k: { url: 'ep3', at: 2000, done: true, next: null } } }, it), 'upd'); // 次が無い
  assert.equal(NH.resumeUrl({ progress: { k: { url: 'old', at: 500 } } }, it), 'upd'); // 更新取得の方が新しい
  assert.equal(NH.resumeUrl({}, it), 'upd');
});

// 読書の統計。日付は端末の時刻 (ローカル) なので、時刻は new Date(年, 月, 日, 時) で作る (どのタイムゾーンでも同じ結果になる)
const at = (m, d, h = 12) => new Date(2026, m - 1, d, h).getTime();

test('noteRead / statsSummary: 話数・文字数・作品数・連続日数', () => {
  const NH = loadCore(makeStore({ readLater: [] }));
  const d = { readLater: [] };
  NH.noteRead(d, 'narou:n1', 1000, at(10, 9));
  NH.noteRead(d, 'narou:n1', 2000, at(10, 10, 9));
  NH.noteRead(d, 'hameln:5', 500, at(10, 10, 21));
  NH.noteRead(d, 'narou:n1', 1500, at(10, 11));
  const s = NH.statsSummary(d, at(10, 11, 23));
  assert.deepEqual(JSON.parse(JSON.stringify(s.today)), { e: 1, c: 1500, works: 1 });
  assert.deepEqual(JSON.parse(JSON.stringify(s.week)), { e: 4, c: 5000, works: 2 });
  assert.equal(s.all.e, 4); assert.equal(s.all.c, 5000);
  assert.equal(s.streak.now, 3); assert.equal(s.streak.best, 3);
  assert.equal(s.last14.length, 14); assert.equal(s.last14[13].day, '2026-10-11'); assert.equal(s.last14[13].e, 1);
  assert.deepEqual(JSON.parse(JSON.stringify(s.top)), [{ key: 'narou:n1', n: 3 }, { key: 'hameln:5', n: 1 }]);
});

test('statsSummary: 今日まだ読んでいなければ、昨日までの連続日数。途切れたら 0、最長は残る', () => {
  const NH = loadCore(makeStore({ readLater: [] }));
  const d = { readLater: [] };
  [1, 2, 3, 4, 8, 9].forEach((day) => NH.noteRead(d, 'a:1', 10, at(10, day)));
  assert.equal(NH.statsSummary(d, at(10, 9, 8)).streak.now, 2);  // 8・9 日。今日 (9 日) は読んだ
  assert.equal(NH.statsSummary(d, at(10, 10, 8)).streak.now, 2); // 10 日はまだ。昨日 (9 日) まで
  assert.equal(NH.statsSummary(d, at(10, 11, 8)).streak.now, 0); // 10 日に読んでいなければ途切れ
  assert.equal(NH.statsSummary(d, at(10, 11, 8)).streak.best, 4);
});

test('statsSummary: 月をまたぐ連続・今日・記録なし', () => {
  const NH = loadCore(makeStore({ readLater: [] }));
  const d = { readLater: [] };
  NH.noteRead(d, 'a:1', 1, at(9, 30)); NH.noteRead(d, 'a:1', 1, at(10, 1));
  assert.equal(NH.statsSummary(d, at(10, 1, 20)).streak.now, 2);
  const empty = NH.statsSummary({ readLater: [] }, at(10, 1));
  assert.equal(empty.all.e, 0); assert.equal(empty.streak.now, 0); assert.equal(empty.last14.length, 14); assert.equal(empty.top.length, 0);
});

test('noteRead: 設定でオフなら記録しない。古い日は old に足して捨てる。型の違うデータでも落ちない', () => {
  const NH = loadCore(makeStore({ readLater: [] }));
  const off = { readLater: [], settings: { stats: false } };
  NH.noteRead(off, 'a:1', 10, at(10, 1));
  assert.equal(off.readStats, undefined);
  const d = { readLater: [], readStats: { days: 'こわれた', old: null } }; // 型の違う保存データでも、数え直して続けられる
  for (let i = 0; i < NH.STATS_DAYS + 5; i++) NH.noteRead(d, 'a:1', 10, new Date(2025, 0, 1 + i, 12).getTime()); // 400 日を 5 日超える
  assert.equal(Object.keys(d.readStats.days).length, NH.STATS_DAYS);
  assert.equal(d.readStats.old.e, 5); assert.equal(d.readStats.old.c, 50); // 古い 5 日は合計に足して捨てた
  assert.equal(NH.statsSummary(d, new Date(2025, 0, 1 + NH.STATS_DAYS + 4, 12).getTime()).all.e, NH.STATS_DAYS + 5); // 全期間の合計は減らない
});

test('pauseMs: 1.5〜3 秒。範囲外の乱数でも、短くならない', () => {
  const NH = loadCore(makeStore({ readLater: [] }));
  assert.equal(NH.pauseMs(0), 1500);
  assert.ok(NH.pauseMs(0.5) > 2200 && NH.pauseMs(0.5) < 2300);
  assert.ok(NH.pauseMs(1) < 3000);
  for (const r of [-5, 7, NaN, Infinity, undefined === 0 ? 0 : -Infinity]) assert.ok(NH.pauseMs(r) >= 1500 && NH.pauseMs(r) < 3000, String(r));
});

test('isBackupApp: 版が決めた形式名だけ読み込める (ほかの版の形式も読めるかは、版が決める)', () => {
  const single = loadCore(makeStore({ readLater: [] }));
  assert.ok(single.isBackupApp('yomikura'));
  assert.ok(!single.isBackupApp('old-name')); // 何も決めていなければ、自分の形式だけ
  const multi = loadCore(makeStore({ readLater: [] }), { NH_EDITION: { backupApp: 'old-name', backupApps: ['old-name', 'yomikura'] } });
  assert.equal(multi.BACKUP_APP, 'old-name');
  assert.ok(multi.isBackupApp('old-name'));
  assert.ok(multi.isBackupApp('yomikura'));
  for (const bad of ['', 'other', undefined, null, 'Yomikura']) assert.ok(!single.isBackupApp(bad) && !multi.isBackupApp(bad), String(bad));
});

test('backupJson: 形式・項目ごとの出し分け・無い項目は空', () => {
  const NH = loadCore(makeStore({ readLater: [] }));
  const d = { readLater: [{ key: 'a' }], settings: { size: 110 }, history: [{ key: 'h' }] };
  const j = JSON.parse(NH.backupJson(d, ['list', 'progress', 'history'], '1.2.3'));
  assert.equal(j.format, 1);
  assert.equal(j.app, NH.BACKUP_APP);
  assert.equal(j.appVersion, '1.2.3');
  assert.deepEqual(Object.keys(j.parts), ['list', 'progress', 'history']);
  assert.deepEqual(j.parts.list, [{ key: 'a' }]);
  assert.deepEqual(j.parts.progress, {}); // 無い項目は空 (オブジェクト)
  assert.ok(!Number.isNaN(Date.parse(j.exportedAt)));
  assert.deepEqual(JSON.parse(NH.backupJson({ readLater: [] }, ['history'])).parts.history, []);
  assert.equal(JSON.parse(NH.backupJson(d, ['settings'])).appVersion, '');
});

test('SITE_DEFS: 名前・掲載サイト・既定が 1 つの定義から導かれる (従来の形のまま)', () => {
  const NH = loadCore(makeStore({ readLater: [] }));
  assert.deepEqual({ ...NH.SITE_NAMES }, { narou: 'なろう', narou18: 'R18', hameln: 'ハーメルン', kakuyomu: 'カクヨム' });
  assert.deepEqual({ ...NH.SUB_NAMES }, { noc: 'ノクターン', mnlt: 'ムーンライト', mid: 'ミッドナイト' });
  assert.deepEqual({ ...NH.SITE_DEFAULT }, { narou: true, hameln: true, kakuyomu: true, noc: true, mnlt: true, mid: true });
  assert.deepEqual([...NH.R18_SITES], ['noc', 'mnlt', 'mid']);
  assert.deepEqual([...NH.COLLECT_IDS], ['narou', 'narou18', 'hameln', 'kakuyomu']);
  assert.equal(NH.siteLabel({ site: 'narou18', sub: 'mnlt' }), 'ムーンライト');
});

test('fetchListDoc: 403/429/503・確認画面は challenge、それ以外の 4xx/5xx は http エラー (ログインなしと誤判定しない)', async () => {
  const mk = (res, title = '') => loadCore(makeStore({ readLater: [] }), {
    location: { href: 'https://syosetu.com/favnovelmain/list/', hostname: 'syosetu.com', pathname: '/favnovelmain/list/', search: '', origin: 'https://syosetu.com' },
    fetch: async () => ({ status: res, ok: res < 400, text: async () => '<html></html>' }),
    DOMParser: class { parseFromString() { return { title, body: {} }; } },
  });
  const NH0 = mk(200);
  NH0.sleep = async () => {};
  assert.equal((await NH0.fetchListDoc('https://syosetu.com/x', 1)).title, '');
  for (const code of [403, 429, 503]) { const NH = mk(code); NH.sleep = async () => {}; await assert.rejects(() => NH.fetchListDoc('https://syosetu.com/x', 1), /challenge/); }
  for (const code of [404, 500, 502]) { const NH = mk(code); NH.sleep = async () => {}; await assert.rejects(() => NH.fetchListDoc('https://syosetu.com/x', 1), new RegExp('http ' + code)); }
  const NH2 = mk(200, 'Just a moment...'); NH2.sleep = async () => {};
  await assert.rejects(() => NH2.fetchListDoc('https://syosetu.com/x', 1), /challenge/);
});

test('watchWork: 作品 / 話が変わったときだけ 1 度呼ぶ (一覧どうしの移動・同じ URL は呼ばない)', () => {
  const loc = { href: 'https://kakuyomu.jp/works/1/episodes/10', hostname: 'kakuyomu.jp', pathname: '/works/1/episodes/10', search: '' };
  const hist = { pushState(_a, _b, url) { loc.href = new URL(url, loc.href).href; }, replaceState(_a, _b, url) { loc.href = new URL(url, loc.href).href; } };
  const listeners = {};
  let intervalFn = null;
  const NH = loadCore(makeStore({ readLater: [] }), {
    location: loc, history: hist, addEventListener: (ev, f) => (listeners[ev] = f), setInterval: (f) => (intervalFn = f),
  });
  NH.register({ id: 'kakuyomu', match: () => true, parseWork: (u) => { const m = new URL(u).pathname.match(/^\/works\/(\d+)(?:\/episodes\/(\d+))?$/); return m ? { id: m[1], episode: m[2] || null } : null; } });
  let n = 0;
  const changed = NH.watchWork(() => n++);
  {
    hist.replaceState(null, '', '/works/1/episodes/10?x=1'); // 同じ話
    assert.equal(n, 0);
    assert.equal(changed(), false);
    hist.pushState(null, '', '/works/1/episodes/11');
    assert.equal(n, 1);
    assert.equal(changed(), true);
    intervalFn(); listeners.popstate();
    assert.equal(n, 1, '1 度だけ');
  }
  // history を使わない移動も、定期確認で気づく
  loc.href = 'https://kakuyomu.jp/works/2';
  const n2 = n;
  intervalFn();
  assert.equal(n, n2); // すでに通知済み
  const NH2 = loadCore(makeStore({ readLater: [] }), { location: { ...loc, href: 'https://kakuyomu.jp/rankings/all/daily' }, history: { pushState() {}, replaceState() {} }, addEventListener() {}, setInterval: (f) => (intervalFn = f) });
  NH2.register({ id: 'kakuyomu', match: () => true, parseWork: () => null });
  let m = 0;
  NH2.watchWork(() => m++);
  intervalFn();
  assert.equal(m, 0); // 作品でないページどうしでは何もしない
});

test('watchWork: 移る直前に beforeChange を呼ぶ (まだ前の話のうちに)。移ったあとの確認は止める', () => {
  const loc = { href: 'https://kakuyomu.jp/works/1/episodes/10', hostname: 'kakuyomu.jp', pathname: '/works/1/episodes/10', search: '' };
  const hist = { pushState(_a, _b, url) { loc.href = new URL(url, loc.href).href; }, replaceState(_a, _b, url) { loc.href = new URL(url, loc.href).href; } };
  let cleared = 0;
  const NH = loadCore(makeStore({ readLater: [] }), {
    location: loc, history: hist, addEventListener() {}, setInterval: () => 7, clearInterval: (t) => { if (t === 7) cleared++; },
  });
  NH.register({ id: 'kakuyomu', match: () => true, parseWork: (u) => { const m = new URL(u).pathname.match(/^\/works\/(\d+)(?:\/episodes\/(\d+))?$/); return m ? { id: m[1], episode: m[2] || null } : null; } });
  const seen = [];
  NH.watchWork(() => seen.push('change'), () => seen.push('before:' + loc.pathname));
  hist.pushState(null, '', '/works/1/episodes/11');
  assert.deepEqual(seen, ['before:/works/1/episodes/10', 'change']);
  assert.equal(cleared, 1);
  hist.pushState(null, '', '/works/1/episodes/12');
  assert.equal(seen.length, 2, '移ったあとは呼ばない');
});

test('NH.load の結果を書き換えると例外 (__NH_READONLY の検査)。NH.update と読み取りの関数は通る', () => {
  const st = makeStore({ readLater: [{ key: 'narou:n1', site: 'narou', id: 'n1', title: 'A', done: false, kind: 'update' }], history: [], progress: { 'narou:n1': { url: 'u', at: 1 } } });
  const NH = loadCore(st, { __NH_READONLY: true });
  const d = NH.load();
  assert.throws(() => { d.readLater.push({}); }, /書き換えられません/);
  assert.throws(() => { d.readLater[0].done = true; }, /書き換えられません/);
  assert.throws(() => { delete d.progress['narou:n1']; }, /書き換えられません/);
  // 読み取りだけの関数
  NH.readStates(d)('narou:n1'); NH.resumeUrl(d, d.readLater[0]); NH.nextPool(d, d.readLater[0]); NH.blockReason(d, { key: 'x', title: 't' });
  NH.muteOf(d); NH.blockOf(d); NH.nsfwOn(d); NH.collectAnyActive(d); NH.findItem(d, { site: 'narou', id: 'n1' });
  // 書き込みの関数は NH.update の中で (update は別に読み直すので通る)
  NH.recordVisit({ site: 'narou', id: 'n1', episode: 1 }, 'A', 'u', false);
  NH.mergeUpdates('narou', [{ id: 'n1', title: 'A', url: 'u', unread: 1, lastEp: 2 }]);
  NH.importSiteHistory('narou', [{ id: 'n2', title: 'B', url: 'u' }]);
  NH.addBlock('words', 'x'); NH.addMute('authors', 'y');
  assert.equal(NH.load().history.length, 2);
});


let failed = 0;
for (const [name, fn] of tests) {
  try { await fn(); console.log('ok   ' + name); } catch (e) { failed++; console.log('FAIL ' + name + '\n     ' + e.message); }
}
console.log(failed ? failed + ' 件失敗' : tests.length + ' 件すべて成功');
process.exit(failed ? 1 : 0);
