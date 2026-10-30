// offline.js (保存のループ NH.offlineRun) の単体テスト。実行: node app/src/test/js/offline.test.mjs (Node 18 以上)
// 端末の保存 (NHOffline) と通信 (fetch) の代わりを、このファイルで用意する
import fs from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';

const OFFLINE = fileURLToPath(new URL('../../main/assets/js/offline/offline.js', import.meta.url));

// 1 回の取り込み (fetch) の結果を決める。status を省くと 200 (本文あり)。challenge なら確認画面
function makeEnv({ status = 200, meta = null, fetchFor = null, imageFor = null } = {}) {
  const log = { stored: [], meta: [], rest: [], fetched: 0, urls: [], images: [], data: [], imageFor };
  const NH = {
    CHALLENGE: 'challenge',
    sleep: async () => {},
    pause: async () => {}, // 本物は 1.5〜3 秒待つ (core/fetch.js の NH.pause)。テストでは待たない
    withSub: (x) => x,
    cleanNode: () => '<p>本文</p>',
    markRest: (id) => log.rest.push(id),
    isChallenge: () => false,
    // core/fetch.js の NH.fetchDoc と同じ働き (その検査は core.test.mjs)。ここでは fetch の代わりを通す
    fetchDoc: async (url) => {
      const res = await ctx.fetch(url, { credentials: 'include' });
      const doc = new ctx.DOMParser().parseFromString(await res.text(), 'text/html');
      if ([403, 429, 503].includes(res.status)) throw new Error('challenge');
      if (!res.ok) throw new Error('http ' + res.status);
      return doc;
    },
    // 挿絵: 保存した名前を記録する (実際の保存は OfflineStore が確かめる)

    off: {
      putEpisode: (site, id, ep, rec) => { log.stored.push(ep); return true; },
      putMeta: (site, id, meta) => { log.meta.push(meta); return true; },
      meta: () => meta,
      // ページの中で取った挿絵 (base64) の保存。名前は saveImage と同じ決め方
      saveImageData: (site, id, url, base64) => {
        const bytes = Buffer.from(base64, 'base64');
        if (bytes[0] !== 0xff) return {}; // JPEG だけ通す (本物は ImageSaver.kindOf が種類を確かめる)
        const name = 'i' + url.length.toString(16).padStart(8, '0') + '.jpg';
        log.images.push(name);
        log.data.push({ url, bytes: bytes.length });
        return { name, bytes: bytes.length };
      },
      saveImage: (site, id, url, referer) => {
        const r = log.imageFor ? log.imageFor(url) : { status: 200, ok: true, type: 'image/jpeg', bytes: 50 };
        if (r.status === 403 || r.status === 429 || r.status === 503) return { error: 'challenge' };
        if (!r.ok || r.type !== 'image/jpeg' || r.bytes > 8 * 1048576) return {};
        const name = 'i' + url.length.toString(16).padStart(8, '0') + '.jpg';
        log.images.push(name);
        return { name, bytes: r.bytes };
      },
    },
  };
  const ctx = {
    window: null,
    NH,
    DOMParser: class { parseFromString() { return { querySelectorAll: () => [{}], title: '' }; } },
    Blob,
    URL,
    JSON,
    Date,
    Math,
    console,
    document: { querySelectorAll: () => [], body: null },
    location: { href: 'https://example.test/work/1/', origin: 'https://example.test' },
    btoa: (x) => Buffer.from(x, 'binary').toString('base64'),
    Uint8Array,
    String,
    fetch: async (url) => {
      log.fetched++;
      log.urls.push(url);
      const special = fetchFor && fetchFor(url);
      if (special) return special;
      return { status, ok: status < 400, text: async () => '<html></html>' };
    },
  };
  ctx.window = ctx;
  vm.createContext(ctx);
  vm.runInContext(fs.readFileSync(OFFLINE, 'utf8'), ctx);
  return { NH, log };
}

const adapter = { id: 'narou', bodySel: '.body' };
const w = { id: '1', url: 'https://example.test/work/1/' };
// 取り込み済みの話 (doc を持つ) を並べる。doc があれば通信しない
const emptyBody = () => ({ nodeType: 1, tagName: 'DIV', childNodes: [], querySelectorAll: () => [] });
const eps = (n) => Array.from({ length: n }, (_, i) => ({ ep: String(i + 1), url: 'https://example.test/work/1/' + (i + 1), title: '第' + (i + 1) + '話', doc: { querySelectorAll: () => [emptyBody()] } }));
const planOf = (NH, list) => ({ title: '作品', workUrl: w.url, eps: list, saved: new Map(), todo: list, meta: null });
// 通信が必要な話 (doc なし) は、fetch の結果で成功・失敗が決まる
const netEps = (n) => eps(n).map((e) => ({ ...e, doc: undefined }));

const tests = [];
const test = (name, fn) => tests.push([name, fn]);

test('全部取り込めたら、話の数と容量を数えて保存する', async () => {
  const { NH, log } = makeEnv();
  const list = eps(3);
  const st = await NH.offlineRun(adapter, w, planOf(NH, list), list);
  assert.equal(st.ok, 3);
  assert.equal(st.failedTotal, 0);
  assert.equal(st.blocked, false);
  assert.deepEqual(log.stored, ['1', '2', '3']);
  assert.ok(st.bytes > 0);
});

test('shouldStop で、次の話から先へ進まない (保存済みはそのまま)', async () => {
  const { NH, log } = makeEnv();
  const list = eps(5);
  let n = 0;
  const st = await NH.offlineRun(adapter, w, planOf(NH, list), list, {
    shouldStop: () => n >= 2,
    onEpisode: () => { n++; },
  });
  assert.equal(st.ok, 2);
  assert.deepEqual(log.stored, ['1', '2']);
});

test('onEpisode は話ごとに呼ばれ、進み具合 (ok) が増えていく', async () => {
  const { NH } = makeEnv();
  const list = eps(3);
  const seen = [];
  await NH.offlineRun(adapter, w, planOf(NH, list), list, { onEpisode: (e, s) => seen.push([e.ep, s.ok]) });
  assert.deepEqual(seen, [['1', 1], ['2', 2], ['3', 3]]);
});

test('確認画面 (403) は休止して止める。確認画面の話は保存しない', async () => {
  const { NH, log } = makeEnv({ status: 403 });
  const list = netEps(3);
  const st = await NH.offlineRun(adapter, w, planOf(NH, list), list);
  assert.equal(st.blocked, true);
  assert.equal(st.ok, 0);
  assert.equal(st.lastErr, 'challenge');
  assert.deepEqual(log.rest, ['narou']);
  assert.equal(log.fetched, 1); // 止まったら次の話は取りに行かない
});

test('回線が切れている間の失敗は数えず、つながってから同じ話を取り直す', async () => {
  let down = 4; // はじめの 4 回は、回線が切れていて失敗する
  let offline = false; // 窓口の offline() が返す値 (切れて失敗したあと、取り直す前に、つながったことにする)
  const { NH, log } = makeEnv({ fetchFor: () => {
    if (down > 0) { down--; offline = true; return { status: 500, ok: false, text: async () => '' }; }
    return null;
  } });
  // つながったあとの取り込みは、本文のある話にする (この試験の DOMParser の代わりは本文を持たないため)
  const fetchDoc = NH.fetchDoc;
  NH.fetchDoc = async (url) => { await fetchDoc(url); return { querySelectorAll: () => [emptyBody()] }; };
  const list = netEps(2);
  const st = await NH.offlineRun(adapter, w, planOf(NH, list), list, {
    offline: () => offline,
    wait: async () => { offline = false; }, // つながるまで待つ
  });
  assert.equal(st.ok, 2);
  assert.equal(st.failedTotal, 0);
  assert.equal(log.fetched, 6);
  assert.deepEqual(log.stored, ['1', '2']);
});

test('続けて 3 回失敗したら止める (1 回目の失敗では止まらない)', async () => {
  const { NH, log } = makeEnv({ status: 500 });
  const list = netEps(5);
  const st = await NH.offlineRun(adapter, w, planOf(NH, list), list);
  assert.equal(st.fails, 3);
  assert.equal(st.failedTotal, 3);
  assert.equal(st.blocked, false);
  assert.equal(log.fetched, 3);
  assert.deepEqual(log.rest, []); // 確認画面ではないので休止はしない
});

test('最後に目次 (meta) を書く。途中で止まっても、保存済みの話は残る', async () => {
  const { NH, log } = makeEnv();
  const list = eps(2);
  await NH.offlineRun(adapter, w, planOf(NH, list), list, { shouldStop: () => true });
  assert.equal(log.meta.length, 1);
  assert.deepEqual(log.stored, []);
});

test('wait は話ごとに呼ばれ、止まっている間は次の話へ進まない', async () => {
  const { NH, log } = makeEnv();
  const list = eps(3);
  let waits = 0;
  const st = await NH.offlineRun(adapter, w, planOf(NH, list), list, { wait: async () => { waits++; } });
  assert.equal(waits, 3);
  assert.equal(st.ok, 3);
  assert.deepEqual(log.stored, ['1', '2', '3']);
});

test('wait の途中で中止されたら、その話は保存しない', async () => {
  const { NH, log } = makeEnv();
  const list = eps(3);
  let stop = false;
  const st = await NH.offlineRun(adapter, w, planOf(NH, list), list, {
    shouldStop: () => stop,
    wait: async () => { stop = true; }, // 待っている間に中止された
  });
  assert.equal(st.ok, 0);
  assert.deepEqual(log.stored, []);
});

test('offlinePlanFrom: 保存済みの話を除き、全話数 (total) を渡せる', () => {
  const { NH } = makeEnv({ meta: { eps: [{ ep: '1' }, { ep: '2' }], title: '保存済みの題名' } });
  const list = eps(4);
  const plan = NH.offlinePlanFrom(adapter, w, list, '', 10);
  assert.deepEqual(plan.todo.map((e) => e.ep), ['3', '4']);
  assert.equal(plan.total, 10);
  assert.equal(plan.title, '保存済みの題名'); // 題名が空なら保存済みの題名
  assert.equal(NH.offlinePlanFrom(adapter, w, list, '題', undefined).total, 4); // 省けば話の数
});


// 挿絵のある話: 挿絵はアプリの中で取って保存し、本文は保存した名前で指す。文字だけの話は挿絵を取りに行かない
const imgEl = (src) => ({ nodeType: 1, tagName: 'IMG', childNodes: [], getAttribute: (k) => (k === 'src' ? src : null), setAttribute() {} });
const bodyWith = (imgs) => {
  const body = { nodeType: 1, tagName: 'DIV', childNodes: [{ nodeType: 3, nodeValue: '本文' }, ...imgs] };
  body.querySelectorAll = (sel) => (sel === 'img' ? imgs : []);
  return body;
};
const docWith = (imgs) => ({ querySelectorAll: (sel) => (sel === '.body' ? [bodyWith(imgs)] : []) });
const epWith = (imgs) => ({ ep: '1', url: 'https://example.test/work/1/1/', title: '第1話', doc: docWith(imgs) });
const ok = (bytes = 100, type = 'image/jpeg') => ({ status: 200, ok: true, type, bytes });

test('挿絵あり: 取って保存し、本文は保存した名前で指す', async () => {
  const { NH, log } = makeEnv({ imageFor: () => ok() });
  const e = epWith([imgEl('https://img.example.net/a.jpg'), imgEl('https://img.example.net/b.jpg')]);
  const rec = await NH.offlineGet(adapter, w, e, true);
  assert.equal(rec.withImages, true);
  assert.equal(rec.imgSaved, 2);
  assert.equal(log.images.length, 2);
  assert.ok(log.images.every((n) => /^i[0-9a-f]{8}\.jpg$/.test(n)));
  assert.ok(e.bytes > 200); // 挿絵の大きさも容量に入る
});

test('挿絵あり: 同じ挿絵は 1 回だけ取る。画像でない物・大きすぎる物は保存しない', async () => {
  const { NH, log } = makeEnv({ imageFor: (url) => {
    if (url.endsWith('/svg.svg')) return ok(10, 'image/svg+xml');
    if (url.endsWith('/big.jpg')) return ok(9 * 1048576);
    if (url.endsWith('/html.jpg')) return ok(10, 'text/html');
    return ok(50);
  } });
  const e = epWith([imgEl('https://img.example.net/a.jpg'), imgEl('https://img.example.net/a.jpg'), imgEl('https://img.example.net/svg.svg'), imgEl('https://img.example.net/big.jpg'), imgEl('https://img.example.net/html.jpg')]);
  const rec = await NH.offlineGet(adapter, w, e, true);
  assert.equal(rec.imgSaved, 1);
  assert.equal(log.images.length, 1);
});

test('挿絵の取得で確認画面 (403) が出たら、話ごと止める', async () => {
  const { NH } = makeEnv({ imageFor: () => ({ status: 403, ok: false }) });
  await assert.rejects(NH.offlineGet(adapter, w, epWith([imgEl('https://img.example.net/a.jpg')]), true), (err) => err.message === NH.CHALLENGE);
});

// 画像へのリンク (ハーメルンの「【挿絵表示】」): アダプターの imageLinkSel で見つけて、画像として取る
const linkEl = (href, withImg = false) => {
  const marks = {};
  return { nodeType: 1, tagName: 'A', childNodes: [], marks, getAttribute: (k) => (k === 'href' ? href : null), setAttribute: (k, v) => { marks[k] = v; }, querySelector: () => (withImg ? {} : null) };
};
const linkDoc = (imgs, links) => ({ querySelectorAll: (sel) => (sel === '.body' ? [{ nodeType: 1, tagName: 'DIV', childNodes: [{ nodeType: 3, nodeValue: '本文' }], querySelectorAll: (s) => (s === 'img' ? imgs : s === 'a.pic' ? links : []) }] : []) });
const linkAdapter = { id: 'hameln', bodySel: '.body', imageLinkSel: 'a.pic' };

test('挿絵のリンク: リンク先の画像を取り (http は https にして)、保存した印を付ける', async () => {
  const { NH, log } = makeEnv({ imageFor: () => ok() });
  const a = linkEl('http://syosetu.org/img/user/v2/1/2/3.jpg');
  const e = { ep: '1', url: 'https://syosetu.org/novel/1/1.html', title: '第1話', doc: linkDoc([], [a]) };
  const rec = await NH.offlineGet(linkAdapter, w, e, true);
  assert.equal(rec.imgCount, 1);
  assert.equal(rec.imgSaved, 1);
  assert.match(a.marks['data-nh-local'], /^img\/i[0-9a-f]{8}\.jpg$/);
  assert.equal(log.images.length, 1);
});

test('挿絵のリンク: 取るのは https だけ (http のリンクは https で取りに行く)', async () => {
  const urls = [];
  const { NH } = makeEnv({ imageFor: (u) => { urls.push(u); return ok(); } });
  const e = { ep: '1', url: 'https://syosetu.org/novel/1/1.html', title: '第1話', doc: linkDoc([], [linkEl('http://syosetu.org/img/user/a.jpg'), linkEl('//syosetu.org/img/user/b.jpg')]) };
  await NH.offlineGet(linkAdapter, w, e, true);
  assert.deepEqual(urls, ['https://syosetu.org/img/user/a.jpg', 'https://syosetu.org/img/user/b.jpg']);
});

test('挿絵のリンク: アダプターの imageUrl で、取りに行く URL を変えられる (名前も変えた URL で決まる)', async () => {
  const urls = [];
  const { NH } = makeEnv({ imageFor: (u) => { urls.push(u); return ok(); } });
  const a = { ...linkAdapter, imageUrl: (u) => u.replace('//syosetu.org/img/', '//img.syosetu.org/img/') };
  await NH.offlineGet(a, w, { ep: '1', url: 'https://syosetu.org/novel/1/1.html', title: '第1話', doc: linkDoc([], [linkEl('http://syosetu.org/img/user/a.jpg')]) }, true);
  assert.deepEqual(urls, ['https://img.syosetu.org/img/user/a.jpg']);
});

test('挿絵のリンク: 画像を含むリンクは、画像の方だけ数える。挿絵なしの取り込みでは取りに行かない', async () => {
  const { NH, log } = makeEnv({ imageFor: () => ok() });
  const e = { ep: '1', url: 'https://syosetu.org/novel/1/1.html', title: '第1話', doc: linkDoc([imgEl('https://img.example.net/a.jpg')], [linkEl('http://syosetu.org/img/user/a.jpg', true)]) };
  const plain = await NH.offlineGet(linkAdapter, w, e, false);
  assert.equal(plain.imgCount, 1); // 画像を含むリンクは数えない
  assert.equal(log.images.length, 0);
});

test('挿絵のリンク: imageLinkSel を持たないサイトは、リンクを画像として扱わない', async () => {
  const { NH, log } = makeEnv({ imageFor: () => ok() });
  const e = { ep: '1', url: 'https://example.test/work/1/1/', title: '第1話', doc: linkDoc([], [linkEl('http://example.test/x.jpg')]) };
  const rec = await NH.offlineGet({ id: 'narou', bodySel: '.body' }, w, e, true);
  assert.equal(rec.imgCount, 0);
  assert.equal(log.images.length, 0);
});

// 本文と同じサイトの挿絵: ページの fetch (WebView の通信) で取り、中身を窓口へ渡して保存する。アプリ自身の通信 (saveImage) は使わない
const jpegBlob = (n = 4) => ({ size: n, arrayBuffer: async () => Uint8Array.from([0xff, 0xd8, 0xff, ...new Array(n - 3).fill(1)]).buffer });
const here = (url) => (url.startsWith('https://example.test/img/') ? { status: 200, ok: true, blob: async () => jpegBlob() } : null);

test('同じサイトの挿絵: ページの fetch で取って、中身を渡して保存する (アプリの通信は使わない)', async () => {
  let native = 0;
  const { NH, log } = makeEnv({ fetchFor: here, imageFor: () => { native++; return ok(); } });
  const a = linkEl('http://example.test/img/user/1.jpg');
  const rec = await NH.offlineGet({ id: 'hameln', bodySel: '.body', imageLinkSel: 'a.pic' }, w, { ep: '1', url: 'https://example.test/novel/1/1.html', title: '第1話', doc: linkDoc([], [a]) }, true);
  assert.equal(rec.imgSaved, 1);
  assert.deepEqual(log.urls, ['https://example.test/img/user/1.jpg']); // https で取る
  assert.equal(log.data.length, 1);
  assert.equal(log.data[0].bytes, 4);
  assert.equal(native, 0);
  assert.match(a.marks['data-nh-local'], /^img\/i[0-9a-f]{8}\.jpg$/);
});

test('同じサイトの挿絵: 確認画面 (403) なら話ごと止める。404 なら挿絵だけ飛ばす。大きすぎる物は取らない', async () => {
  const hameln = { id: 'hameln', bodySel: '.body', imageLinkSel: 'a.pic' };
  const epOf = (href) => ({ ep: '1', url: 'https://example.test/novel/1/1.html', title: '第1話', doc: linkDoc([], [linkEl(href)]) });
  let r = makeEnv({ fetchFor: (u) => (u.endsWith('/403.jpg') ? { status: 403, ok: false } : null) });
  await assert.rejects(r.NH.offlineGet(hameln, w, epOf('https://example.test/img/user/403.jpg'), true), (err) => err.message === r.NH.CHALLENGE);
  r = makeEnv({ fetchFor: (u) => (u.endsWith('/gone.jpg') ? { status: 404, ok: false } : null) });
  const rec = await r.NH.offlineGet(hameln, w, epOf('https://example.test/img/user/gone.jpg'), true);
  assert.equal(rec.imgSaved, 0);
  assert.equal(r.log.data.length, 0);
  r = makeEnv({ fetchFor: () => ({ status: 200, ok: true, blob: async () => ({ size: 9 * 1048576, arrayBuffer: async () => { throw new Error('読まない'); } }) }) });
  assert.equal((await r.NH.offlineGet(hameln, w, epOf('https://example.test/img/user/big.jpg'), true)).imgSaved, 0);
});

test('別のサイトの挿絵 (CORS でページから読めない物) は、アプリが取る', async () => {
  let page = 0;
  const { NH, log } = makeEnv({ fetchFor: () => { page++; return null; }, imageFor: () => ok() });
  const rec = await NH.offlineGet({ id: 'narou', bodySel: '.body' }, w, epWith([imgEl('https://img1.mitemin.net/x/y.jpg')]), true);
  assert.equal(rec.imgSaved, 1);
  assert.equal(page, 0); // ページの fetch は使わない
  assert.equal(log.images.length, 1);
  assert.equal(log.data.length, 0);
});

test('挿絵が取れなくても、話は保存できる (挿絵だけ飛ばす)', async () => {
  const { NH, log } = makeEnv({ imageFor: () => ({ status: 404, ok: false }) });
  const rec = await NH.offlineGet(adapter, w, epWith([imgEl('https://img.example.net/gone.jpg')]), true);
  assert.equal(rec.imgSaved, 0);
  assert.equal(log.images.length, 0);
  assert.ok(rec.html.includes('本文'));
});

test('挿絵なし: 挿絵を取りに行かない。挿絵ありの取り込みは使い回さず作り直す', async () => {
  const { NH, log } = makeEnv({ imageFor: () => ok() });
  const e = epWith([imgEl('https://img.example.net/a.jpg')]);
  const plain = await NH.offlineGet(adapter, w, e, false);
  assert.equal(plain.withImages, false);
  assert.equal(log.images.length, 0);
  const withImg = await NH.offlineGet(adapter, w, e, true);
  assert.equal(withImg.withImages, true);
  assert.equal(log.images.length, 1);
});

let failed = 0;
for (const [name, fn] of tests) {
  try { await fn(); console.log('ok   ' + name); } catch (e) { failed++; console.log('FAIL ' + name + '\n     ' + e.message); }
}
console.log(failed ? failed + ' 件失敗' : tests.length + ' 件すべて成功');
process.exit(failed ? 1 : 0);
