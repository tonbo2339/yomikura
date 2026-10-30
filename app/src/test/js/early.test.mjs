// early.js (描画前に入れる CSS) の、ヘッダー・フッター (bars) の切り替えの単体テスト。実行: node app/src/test/js/early.test.mjs (Node 18 以上)
// DOM は使わず、<style> を作って付ける部分だけの見せかけを用意する。
import fs from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';

const JS = (name) => fileURLToPath(new URL('../../main/assets/js/' + name, import.meta.url));
const read = (name) => fs.readFileSync(JS(name), 'utf8');

// host / path のページで early.js を動かす。戻り値: {styles: 付いた <style>, nav(path): history.pushState で移る, back(path): 戻る操作}
function open(host, path, { settings, ratio, mode, dom } = {}) {
  const styles = [];
  const store = {};
  if (ratio) store['nh-size-ratio'] = String(ratio);
  if (mode) store['nh-size-mode'] = mode;
  const listeners = {};
  const location = { hostname: host, pathname: path, hash: '', search: '' };
  const documentElement = { appendChild: (el) => styles.push(el) };
  const history = {
    pushState(_s, _t, url) { location.pathname = url; },
    replaceState(_s, _t, url) { location.pathname = url; },
  };
  // dom = {std, base, honors}: ページの DOM の見せかけ。std = 標準の文字の大きさ (px)、base = 本文の囲みの親の大きさ、
  // honors = 本文の囲みに付けた font-size が効くか (なろうのように、サイトが !important で決めていると効かない)
  const probe = { style: {}, remove() {} };
  const parent = {};
  const box = { parentElement: parent };
  const nhSize = () => styles.find((x) => x.id === 'nh-size');
  let observed = null; // MutationObserver の見せかけ: 本文の囲みが変わったことを知らせる (mutate)
  class FakeObserver { constructor(cb) { observed = cb; } observe() {} }
  const ctx = {
    location, history, console,
    document: {
      documentElement, head: null, readyState: 'complete', addEventListener() {},
      createElement: (tag) => (tag === 'style' ? {} : probe),
      getElementById: (id) => styles.find((x) => x.id === id) || null,
      querySelector: () => (dom ? box : null),
      body: dom ? { appendChild() {} } : null,
    },
    getComputedStyle: (el) => {
      if (el === probe) return { fontSize: dom.std + 'px' };
      if (el === parent) return { fontSize: dom.base + 'px' };
      const m = /font-size:calc\(1em \* ([0-9.]+)\)/.exec((nhSize() || {}).textContent || '');
      return { fontSize: (m && dom.honors ? dom.base * Number(m[1]) : dom.base) + 'px' };
    },
    localStorage: { getItem: (k) => (k in store ? store[k] : null), setItem: (k, v) => { store[k] = String(v); } },
    NHStore: settings ? { get: () => JSON.stringify({ settings }) } : undefined,
    MutationObserver: FakeObserver,
    addEventListener: (type, fn) => { listeners[type] = fn; },
  };
  ctx.window = ctx;
  vm.createContext(ctx);
  vm.runInContext(read('site/fonts.js'), ctx); // 書体の表 (scripts.json の early の先頭)
  vm.runInContext(read('site/sites.js'), ctx);
  vm.runInContext(read('site/early.js'), ctx);
  return {
    styles, store,
    mutate: () => observed && observed(), // 本文の囲みの style が変わった
    load: () => listeners.load && listeners.load(), // ページの読み込みが終わった
    bars: () => styles.find((s) => s.id === 'nh-bars'),
    nav: (p) => ctx.history.pushState(null, '', p),
    back: (p) => { location.pathname = p; listeners.popstate(); },
  };
}

const tests = [];
const test = (name, fn) => tests.push([name, fn]);
const sizeOf = (p) => (p.styles.find((s) => s.id === 'nh-size') || {}).textContent;

test('読書ページ: ヘッダー・フッターの CSS が有効 (media = all)', () => {
  const p = open('ncode.syosetu.com', '/n9669bk/1/');
  assert.equal(p.bars().media, 'all');
  assert.ok(p.bars().textContent.includes('.l-header'));
});
test('ほかのページ (ランキング・目次・トップ): 無効 (media = not all)。常に隠すものとは別の <style>', () => {
  for (const [h, path] of [['yomou.syosetu.com', '/rank/daily/'], ['ncode.syosetu.com', '/n9669bk/'], ['syosetu.com', '/']]) {
    const p = open(h, path);
    assert.equal(p.bars().media, 'not all', h + path);
    assert.ok(p.styles.some((s) => !s.id && s.textContent.includes('c-applink')), 'アプリ宣伝は常に隠す');
    assert.ok(!p.styles.some((s) => !s.id && s.textContent.includes('.l-header')), 'ヘッダーが常に隠す側に入っている');
  }
});
test('カクヨムのページ内の移動: 目次 → 話で隠し、話 → 目次で出す (pushState と戻る操作)', () => {
  const p = open('kakuyomu.jp', '/works/1');
  assert.equal(p.bars().media, 'not all');
  p.nav('/works/1/episodes/2');
  assert.equal(p.bars().media, 'all');
  p.nav('/works/1');
  assert.equal(p.bars().media, 'not all');
  p.nav('/works/1/episodes/3');
  p.back('/works/1');
  assert.equal(p.bars().media, 'not all');
});
test('ハーメルン: 話ページ (/novel/ID/N.html) だけ隠す', () => {
  assert.equal(open('syosetu.org', '/novel/123/4.html').bars().media, 'all');
  assert.equal(open('h.syosetu.org', '/novel/123/4.html').bars().media, 'all'); // ハーメルンの R-18 も同じ扱い (サイトを見分けられず、何も効かなかった)
  assert.equal(open('syosetu.org', '/novel/123/').bars().media, 'not all');
  assert.equal(open('syosetu.org', '/').bars().media, 'not all');
});
test('文字サイズ: どのサイトも、はじめは font-size で大きくする (zoom は本文の中の位置の計算をずらす)', () => {
  const opts = { settings: { size: 100 }, ratio: 1.067 };
  for (const [h, path, box] of [['syosetu.org', '/novel/1/1.html', '#honbun,#maegaki,#atogaki'], ['ncode.syosetu.com', '/n1/1/', '.p-novel__text'], ['kakuyomu.jp', '/works/1/episodes/2', '.widget-episodeBody']]) {
    assert.equal(sizeOf(open(h, path, opts)), box + '{font-size:calc(1em * 1.067)}', h);
  }
  assert.equal(sizeOf(open('syosetu.org', '/novel/1/1.html', { settings: { size: 100 }, ratio: 1 })), undefined); // 倍率が 1 なら何も付けない
});

test('文字サイズ: font-size が効くサイトは、そのまま (ハーメルン)', () => {
  const p = open('syosetu.org', '/novel/1/1.html', { settings: { size: 100 }, ratio: 1.067, dom: { std: 13.6, base: 12.75, honors: true } });
  assert.equal(sizeOf(p), '#honbun,#maegaki,#atogaki{font-size:calc(1em * 1.067)}');
  assert.equal(p.store['nh-size-mode'], undefined); // 切り替えない
});

test('文字サイズ: サイトが本文の囲みの大きさを決めていて font-size が効かないサイト (なろうの表示調整) は、zoom に切り替えて控える', () => {
  const p = open('ncode.syosetu.com', '/n1/1/', { settings: { size: 100 }, ratio: 1.143, dom: { std: 13.6, base: 11.9, honors: false } });
  assert.equal(sizeOf(p), '.p-novel__text{zoom:1.143}');
  assert.equal(p.store['nh-size-mode'], 'zoom');
});

test('文字サイズ: 読み込みのあとでサイトの JS が本文の囲みに大きさを付けて font-size が効かなくなっても、zoom に切り替える', () => {
  const dom = { std: 13.6, base: 11.9, honors: true }; // はじめは効いて見える
  const p = open('ncode.syosetu.com', '/n1/1/', { settings: { size: 100 }, ratio: 1.143, dom });
  assert.equal(sizeOf(p), '.p-novel__text{font-size:calc(1em * 1.143)}');
  assert.equal(p.store['nh-size-mode'], undefined);
  dom.honors = false; // サイトの JS が style 属性に font-size を付けた
  p.mutate();
  assert.equal(sizeOf(p), '.p-novel__text{zoom:1.143}');
  assert.equal(p.store['nh-size-mode'], 'zoom');
});

test('文字サイズ: 読み込みが終わったときにも確かめる', () => {
  const dom = { std: 13.6, base: 11.9, honors: true };
  const p = open('ncode.syosetu.com', '/n1/1/', { settings: { size: 100 }, ratio: 1.143, dom });
  dom.honors = false;
  p.load();
  assert.equal(sizeOf(p), '.p-novel__text{zoom:1.143}');
});

test('文字サイズ: 控えが zoom なら、次のページは描画前から zoom', () => {
  const p = open('ncode.syosetu.com', '/n1/2/', { settings: { size: 100 }, ratio: 1.143, mode: 'zoom' });
  assert.equal(sizeOf(p), '.p-novel__text{zoom:1.143}');
});

test('文字サイズ: 比が控えと違っていたら (初めてのサイト・サイトの変更) 測り直して直す', () => {
  const p = open('syosetu.org', '/novel/1/1.html', { settings: { size: 100 }, ratio: 1, dom: { std: 13.6, base: 12.75, honors: true } });
  assert.equal(sizeOf(p), '#honbun,#maegaki,#atogaki{font-size:calc(1em * 1.067)}');
  assert.equal(p.store['nh-size-ratio'], '1.067');
});

const bodyCss = (p) => p.styles.filter((s) => !s.id).map((s) => s.textContent).join('\n');

test('書体: ゴシックは本文の font-family に付き、@font-face は付かない', () => {
  const css = bodyCss(open('ncode.syosetu.com', '/n1/1/', { settings: { font: 'gothic' } }));
  assert.match(css, /\.p-novel__text p\{font-family:"Noto Sans CJK JP"[^}]*!important\}/);
  assert.ok(!css.includes('@font-face'));
});

test('書体: 入れたフォントは、アプリの配信の URL (/font/current) の @font-face と、本文の font-family で付く (どのサイトも同じ。端末ごとの id はページに出さない)', () => {
  for (const [h, path, box] of [['ncode.syosetu.com', '/n1/1/', '.p-novel__text'], ['syosetu.org', '/novel/1/1.html', '#honbun'], ['kakuyomu.jp', '/works/1/episodes/2', '.widget-episodeBody']]) {
    const css = bodyCss(open(h, path, { settings: { font: 'custom:0123456789ab' } }));
    assert.ok(css.includes('@font-face{font-family:"nh-custom";src:url("https://yomikura.local/font/current")'), h);
    assert.ok(!css.includes('0123456789ab'), h + ': id がページの CSS に出ている');
    assert.ok(css.includes(box) && /font-family:"nh-custom","Noto Sans CJK JP"/.test(css), h);
  }
});

test('書体: サイト標準・形の違う値は何も付けない', () => {
  for (const font of ['site', 'custom:zzz', undefined]) {
    assert.ok(!bodyCss(open('ncode.syosetu.com', '/n1/1/', { settings: { font } })).includes('font-family'), String(font));
  }
});

test('対応サイトでなければ何も付けない', () => {
  assert.equal(open('example.com', '/').styles.length, 0);
});

let failed = 0;
for (const [name, fn] of tests) {
  try { fn(); console.log('ok  ', name); } catch (e) { failed++; console.log('FAIL', name, '\n    ', e.message); }
}
console.log(failed ? `${failed} 件失敗` : `${tests.length} 件すべて成功`);
process.exit(failed ? 1 : 0);
