// 読書の補助 (自動スクロール・読み上げ用の段落)。⋮ メニューの「自動スクロール」「読み上げ」(Kotlin の PageMenu / ReadingAids / ReadAloud) が、
// ページの中で呼ぶ。本文の囲みは sites.js の body.box (sitecheck.js のサイト判定を使う)。
//
// - 自動スクロール: 設定 autoScrollSpeed (1〜10。既定 4) の速さで、ゆっくり下へ。画面を触る・スクロールする・キーを押す・ページを離れる・いちばん下に着く、で止まる。
//   画面の下に、速さを変える・止めるボタンを出す。スクロールの位置は、ふつうの読書と同じく記録される (ui.js の読書位置)
// - 読み上げ: 本文を段落に分けて Kotlin へ渡す (ルビは読まない)。読んでいる段落は nhTtsMark(番号) で色をつけて、画面の中ほどに出す
//
// 計算 (速さの表・文の整え・段落の取り出し) は DOM を渡せば動く関数 (window.NH_READER)。
(function () {
  if (window.NH_READER) return;

  // 自動スクロールの速さ (1 画面の文字数が多い小説を、ゆっくり〜速めに読む速さ。1 秒あたりの px)
  const SPEEDS = [10, 18, 28, 40, 55, 72, 92, 115, 140, 170];
  const DEFAULT_LEVEL = 4;
  const clampLevel = (n) => {
    if (n === null || n === undefined || n === '') return DEFAULT_LEVEL; // 未設定 (Number(null) は 0 になり、最小の 1 になってしまうため)
    const v = Math.round(Number(n));
    return Number.isFinite(v) ? Math.min(SPEEDS.length, Math.max(1, v)) : DEFAULT_LEVEL;
  };
  const speedOf = (level) => SPEEDS[clampLevel(level) - 1];

  // 読み上げる文: ゼロ幅の文字を除き、空白をそろえる
  const clean = (s) => String(s || '').replace(/[​-‍﻿]/g, '').replace(/\s+/g, ' ').trim();
  // 要素の文字。ルビ (rt / rp) は読まない (本文の字を読み上げるため)。<br> は改行に
  const plain = (el) => {
    const c = el.cloneNode(true);
    c.querySelectorAll('rt,rp,script,style').forEach((n) => n.remove());
    c.querySelectorAll('br').forEach((b) => b.replaceWith('\n'));
    return c.textContent || '';
  };

  // 本文の囲み (sel) の中の段落。<p> があれば 1 つずつ、無ければ囲みの文を改行で分ける。空の段落 (挿絵だけなど) は飛ばす。
  // 戻り値: {els: 段落の要素, texts: 読む文} (どちらも同じ長さ・同じ順)
  function paragraphs(doc, sel) {
    const els = [], texts = [];
    const add = (el, text) => { const t = clean(text); if (t) { els.push(el); texts.push(t); } };
    for (const box of doc.querySelectorAll(sel)) {
      const ps = [...box.querySelectorAll('p')];
      if (ps.length) ps.forEach((p) => add(p, plain(p)));
      else plain(box).split(/\n+/).forEach((line) => add(box, line));
    }
    return { els, texts };
  }

  window.NH_READER = { SPEEDS, clampLevel, speedOf, clean, paragraphs };

  const bodySel = () => {
    const key = window.NH_SITECHECK && NH_SITECHECK.siteKeyOf(location.hostname);
    const s = key && window.NH_SITES && NH_SITES[key];
    return s && s.body && s.body.box;
  };
  const settings = () => (window.NH && NH.load && NH.load().settings) || {};
  // ui.js の読書位置の記録は、画面を触るまで始まらない (前回の位置の復元で上書きしないため)。メニューから始めたときも記録されるよう、触ったことにする
  const arm = () => window.dispatchEvent(new Event('wheel'));

  // ---- 読み上げ ----
  let cache = { els: [], texts: [] }, marked = null;
  const style = document.createElement('style');
  style.textContent = '.nh-tts{background:rgba(255,204,0,.3)!important;border-radius:4px}';
  (document.head || document.documentElement).appendChild(style);
  window.nhTtsParagraphs = () => {
    const sel = bodySel();
    cache = sel ? paragraphs(document, sel) : { els: [], texts: [] };
    if (cache.texts.length) arm();
    return JSON.stringify(cache.texts);
  };
  window.nhTtsMark = (i) => {
    if (marked) marked.classList.remove('nh-tts');
    marked = i >= 0 ? cache.els[i] || null : null;
    if (marked) { marked.classList.add('nh-tts'); marked.scrollIntoView({ block: 'center', behavior: 'smooth' }); }
  };

  // ---- 自動スクロール ----
  // level = 今の速さ。始めるときに設定から読み、ボタンで変えたら設定にも書く (毎フレーム保存データを読まない)
  let on = false, raf = 0, last = 0, acc = 0, pill = null, speedLabel = null, level = DEFAULT_LEVEL;
  const atBottom = () => window.innerHeight + window.scrollY >= document.documentElement.scrollHeight - 2;
  function stop() {
    if (!on) return;
    on = false;
    cancelAnimationFrame(raf);
    ['touchstart', 'wheel', 'keydown'].forEach((ev) => window.removeEventListener(ev, onUser, true));
    document.removeEventListener('visibilitychange', onHide);
    window.removeEventListener('pagehide', stop);
    if (pill) { pill.remove(); pill = null; }
  }
  // ほんとうの操作 (isTrusted) だけで止める。arm() の作りものの wheel では止めない
  const onUser = (e) => { if (e.isTrusted && !(pill && pill.contains(e.target))) stop(); };
  const onHide = () => { if (document.hidden) stop(); };
  function tick(t) {
    if (!on) return;
    if (!last) last = t;
    const dt = Math.min(100, t - last);
    last = t;
    acc += speedOf(level) * dt / 1000;
    const px = Math.floor(acc);
    if (px > 0) { acc -= px; window.scrollBy(0, px); }
    if (atBottom()) return stop(); // いちばん下に着いた
    raf = requestAnimationFrame(tick);
  }
  function setLevel(n) {
    const v = level = clampLevel(n);
    if (window.NH && NH.setSetting) NH.setSetting('autoScrollSpeed', v);
    if (speedLabel) speedLabel.textContent = '速さ ' + v;
  }
  function makePill() {
    const mk = (tag, text, css) => { const e = document.createElement(tag); if (text) e.textContent = text; if (css) e.style.cssText = css; return e; };
    const btn = 'flex:none;white-space:nowrap;border:0;border-radius:999px;min-width:44px;height:44px;font:600 16px sans-serif;color:#fff;background:rgba(255,255,255,.18);padding:0 14px';
    const p = mk('div', '', 'position:fixed;left:50%;transform:translateX(-50%);bottom:max(16px,env(safe-area-inset-bottom));z-index:2147483647;display:flex;align-items:center;gap:6px;padding:6px 8px;border-radius:999px;background:rgba(30,30,32,.92);box-shadow:0 2px 12px rgba(0,0,0,.35)');
    p.setAttribute('role', 'group'); p.setAttribute('aria-label', '自動スクロール');
    const slower = mk('button', '−', btn), faster = mk('button', '＋', btn), end = mk('button', '止める', btn + ';background:#c4211a');
    slower.setAttribute('aria-label', '遅く'); faster.setAttribute('aria-label', '速く');
    speedLabel = mk('span', '速さ ' + level, 'color:#fff;font:14px sans-serif;min-width:4.2em;text-align:center');
    slower.onclick = () => setLevel(level - 1);
    faster.onclick = () => setLevel(level + 1);
    end.onclick = stop;
    p.append(slower, speedLabel, faster, end);
    (document.body || document.documentElement).appendChild(p);
    return p;
  }
  function start() {
    if (on) return;
    on = true; last = 0; acc = 0;
    level = clampLevel(settings().autoScrollSpeed);
    arm();
    pill = makePill();
    ['touchstart', 'wheel', 'keydown'].forEach((ev) => window.addEventListener(ev, onUser, { capture: true, passive: true }));
    document.addEventListener('visibilitychange', onHide);
    window.addEventListener('pagehide', stop);
    raf = requestAnimationFrame(tick);
  }
  // 始める / 止める。戻り値: いま動いているか
  window.nhAutoScroll = () => { if (on) stop(); else start(); return on; };
  window.nhAutoScrollOn = () => on;
})();
