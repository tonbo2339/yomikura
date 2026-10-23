// ui/ui-collect.js (更新の巡回: サイト上で 1 サイトを取り込み、次のサイトへ移り、最後に結果をアプリへ返す) の単体テスト。
// 実行: node app/src/test/js/ui-collect.test.mjs (Node 18 以上)。アダプターの collectUpdates だけを差し替えて、流れ (進み・取り込み・移動・結果) を確かめる。
import vm from 'node:vm';
import assert from 'node:assert/strict';
import { runGroup, readJs } from './scripts.mjs';

// NHStore の代わり (版付きの置き場)。呼ばれた窓口を calls に控える
function makeStore() {
  const s = { raw: JSON.stringify({ readLater: [] }), v: 0, calls: [] };
  s.api = {
    get: () => s.raw,
    set: (json) => { s.raw = json; s.v++; },
    version: () => s.v,
    setIf: (json, v) => { if (v !== s.v) return false; s.raw = json; s.v++; return true; },
    progress: (i, n) => s.calls.push(['progress', i, n]),
    bgDone: (log) => s.calls.push(['bgDone', JSON.parse(log)]),
    isSplit: () => false,
    challenge: (site, next) => { s.calls.push(['challenge', site, next]); return ''; },
  };
  return s;
}

// 巡回のページ (hostname / pathname / hash) を開いた状態で、アダプターを読み込む。timers = setTimeout に渡された処理 (手で進める)
function load(href, store, { title = 'ブックマーク' } = {}) {
  const u = new URL(href);
  const timers = [];
  const replaced = [];
  const ctx = {
    __nhT: 'tok',
    NHStore: store.api,
    localStorage: { getItem: () => null, setItem: () => {} },
    location: { href, hostname: u.hostname, pathname: u.pathname, search: u.search, hash: u.hash, origin: u.origin, replace: (x) => replaced.push(x) },
    document: { title, createElement: () => ({ textContent: '' }), head: { appendChild: () => {} }, documentElement: { appendChild: () => {} }, querySelectorAll: () => [] },
    setTimeout: (fn, ms) => { timers.push([fn, ms]); return timers.length; },
    setInterval: () => 0, URL, console,
  };
  ctx.window = ctx;
  vm.createContext(ctx);
  runGroup(vm, ctx, 'adapters');
  vm.runInContext(readJs('ui/ui-collect.js'), ctx);
  return { ctx, timers, replaced };
}
const flush = async () => { for (let i = 0; i < 5; i++) await Promise.resolve(); };

const tests = [];
const test = (name, fn) => tests.push([name, fn]);

test('巡回のページでなければ何もしない (false)', () => {
  const st = makeStore();
  const { ctx } = load('https://syosetu.com/favnovelmain/list/', st); // hash が無い
  const adapter = ctx.NH.adapter();
  assert.equal(ctx.NH_UI_COLLECT({ adapter, challenge: false }), false);
  assert.deepEqual(st.calls, []);
});

test('巡回のページ (#nh-collect-fg): 取り込んで、次のサイトへ移る (履歴に残さない replace)。結果はまだ返さない', async () => {
  const st = makeStore();
  const { ctx, timers, replaced } = load('https://syosetu.com/favnovelmain/list/?nowcategory=1#nh-collect-fg', st);
  const adapter = ctx.NH.adapter();
  assert.equal(adapter.id, 'narou');
  adapter.collectUpdates = async () => ({ ok: true, items: [{ id: 'n0001aa', url: 'https://ncode.syosetu.com/n0001aa/2/', title: 'テスト作品', unread: 2, lastEp: 5 }] });
  assert.equal(ctx.NH_UI_COLLECT({ adapter, challenge: false }), true);
  assert.deepEqual(st.calls[0].slice(0, 1), ['progress']); // 「何サイト目か」を知らせる
  await flush();
  const d = JSON.parse(st.raw);
  assert.equal(d.readLater.length, 1);
  assert.equal(d.readLater[0].key, 'narou:n0001aa');
  assert.deepEqual([d.collectLog.narou.n, d.collectLog.narou.added], [1, 1]);
  assert.equal(timers.length, 1); // 次へ進む処理 (間をあける)
  assert.ok(timers[0][1] >= 1500 && timers[0][1] <= 2500, 'サイトからサイトへ移るときも間をあける: ' + timers[0][1]);
  timers[0][0]();
  assert.equal(replaced.length, 1);
  assert.match(replaced[0], /nh-collect-fg$/);
  assert.ok(!st.calls.some((c) => c[0] === 'bgDone'));
});

test('最後のサイト: lastCollect を書き、結果 (collectLog) をアプリへ返す。裏 (bg) は lastCollectFg を付けない', async () => {
  for (const [mode, hasFg] of [['fg', true], ['bg', false]]) {
    const st = makeStore();
    // 最後のサイトはカクヨム (巡回の順の最後)。ほかのサイトは設定で確認しない
    st.raw = JSON.stringify({ readLater: [], settings: { collectSites: { hameln: false, narou: false, narou18: false } } });
    const { ctx, timers } = load('https://kakuyomu.jp/my/antenna/works#nh-collect-' + mode, st);
    const adapter = ctx.NH.adapter();
    assert.equal(adapter.id, 'kakuyomu');
    adapter.collectUpdates = async () => ({ ok: true, items: [] });
    assert.equal(ctx.NH_UI_COLLECT({ adapter, challenge: false }), true);
    await flush();
    timers[0][0]();
    const d = JSON.parse(st.raw);
    assert.ok(d.lastCollect > 0, mode);
    assert.equal(!!d.lastCollectFg, hasFg, mode);
    const done = st.calls.find((c) => c[0] === 'bgDone');
    assert.ok(done, mode + ': bgDone が呼ばれていません');
    assert.ok('kakuyomu' in done[1]);
  }
});

test('休んでいるサイトを飛ばして始まった巡回: 最初のページが結果をまっさらにし、休んでいるサイトは rest と残す', async () => {
  const st = makeStore();
  // ハーメルン (巡回の最初) は 5 分前に止められて休んでいる。アプリはなろうから始める (SiteConfig.collectStartUrl)
  st.raw = JSON.stringify({ readLater: [], rest: { hameln: Date.now() - 5 * 60000 }, collectLog: { kakuyomu: { n: 9 } } });
  const { ctx } = load('https://syosetu.com/favnovelmain/list/?nowcategory=1#nh-collect-fg', st);
  const adapter = ctx.NH.adapter();
  adapter.collectUpdates = async () => ({ ok: true, items: [] });
  ctx.NH_UI_COLLECT({ adapter, challenge: false });
  await flush();
  const d = JSON.parse(st.raw);
  assert.deepEqual(d.collectLog.hameln, { err: 'rest' });
  assert.ok(!('kakuyomu' in d.collectLog), '前回の結果が残っています');
  assert.ok(d.rest.hameln > 0); // ほかのサイトのページでは、休みを解かない
});

test('ログインしていない (取れなかった) サイトは結果に login と残し、次へ進む', async () => {
  const st = makeStore();
  const { ctx, timers } = load('https://syosetu.com/favnovelmain/list/?nowcategory=1#nh-collect-fg', st);
  const adapter = ctx.NH.adapter();
  adapter.collectUpdates = async () => ({ ok: false });
  ctx.NH_UI_COLLECT({ adapter, challenge: false });
  await flush();
  assert.deepEqual(JSON.parse(st.raw).collectLog.narou, { err: 'login' });
  assert.equal(timers[0][1], 2500);
});

test('止められた (CHALLENGE) サイトは休止して blocked と残す', async () => {
  const st = makeStore();
  const { ctx } = load('https://syosetu.com/favnovelmain/list/?nowcategory=1#nh-collect-fg', st);
  const adapter = ctx.NH.adapter();
  adapter.collectUpdates = async () => { throw new Error(ctx.NH.CHALLENGE); };
  ctx.NH_UI_COLLECT({ adapter, challenge: false });
  await flush();
  const d = JSON.parse(st.raw);
  assert.deepEqual(d.collectLog.narou, { err: 'blocked' });
  assert.ok(d.rest.narou > 0);
});

test('確認画面のままなら 15 秒待ち、アプリに知らせる (hold でなければ次へ進む)', () => {
  const st = makeStore();
  const { ctx, timers, replaced } = load('https://syosetu.com/favnovelmain/list/?nowcategory=1#nh-collect-fg', st, { title: 'Just a moment...' });
  const adapter = ctx.NH.adapter();
  assert.equal(ctx.NH_UI_COLLECT({ adapter, challenge: true }), true);
  assert.equal(timers[0][1], 15000);
  assert.deepEqual(JSON.parse(st.raw).collectLog.narou, { err: 'challenge' });
  timers[0][0]();
  assert.equal(st.calls.find((c) => c[0] === 'challenge')[1], 'narou');
  assert.equal(timers.length, 2); // hold でない: go(0) で次へ
  timers[1][0]();
  assert.equal(replaced.length, 1);
});

test('確認画面で止まったサイトは、続きのページが結果をまっさらにしても challenge と残る (rest にしない)', () => {
  const st = makeStore();
  // なろうが最初のサイト (ほかは設定で確認しない)。次はカクヨム
  st.raw = JSON.stringify({ readLater: [], settings: { collectSites: { hameln: false, narou18: false } } });
  const first = load('https://syosetu.com/favnovelmain/list/?nowcategory=1#nh-collect-fg', st, { title: 'Just a moment...' });
  first.ctx.NH_UI_COLLECT({ adapter: first.ctx.NH.adapter(), challenge: true });
  first.timers[0][0](); // 15 秒後: アプリに知らせて、hold でなければ次へ
  first.timers[1][0]();
  assert.equal(first.replaced.length, 1);
  // 次のサイト (カクヨム) のページが開く。休止中のなろうを rest と書き直してはいけない (確認画面で止まった、という結果を消さない)
  const next = load(first.replaced[0], st);
  const adapter = next.ctx.NH.adapter();
  assert.equal(adapter.id, 'kakuyomu');
  next.ctx.NH_UI_COLLECT({ adapter, challenge: false });
  assert.deepEqual(JSON.parse(st.raw).collectLog.narou, { err: 'challenge' });
});

test('前の実行で止まって、いまも休んでいるサイトは、新しい実行では rest と残す (古い challenge を引き継がない)', () => {
  const st = makeStore();
  const now = Date.now();
  st.raw = JSON.stringify({ readLater: [], settings: { collectSites: { hameln: false, narou18: false } },
    rest: { narou: now - 5 * 60000 }, collectStartAt: now - 60000, collectLog: { narou: { err: 'challenge' } } });
  const { ctx } = load('https://kakuyomu.jp/my/antenna/works#nh-collect-fg', st);
  ctx.NH_UI_COLLECT({ adapter: ctx.NH.adapter(), challenge: false });
  assert.deepEqual(JSON.parse(st.raw).collectLog.narou, { err: 'rest' });
});

let failed = 0;
for (const [name, fn] of tests) {
  try { await fn(); console.log('ok  ', name); } catch (e) { failed++; console.log('FAIL', name, '\n    ', e.message); }
}
console.log(failed ? `${failed} 件失敗` : `${tests.length} 件すべて成功`);
process.exit(failed ? 1 : 0);
