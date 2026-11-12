// 読書の補助 (自動スクロール・読み上げ用の段落)。⋮ メニューの「自動スクロール」「読み上げ」(Kotlin の PageMenu / ReadingAids / ReadAloud) が、
// ページの中で呼ぶ。本文の囲みは sites.js の body.box (sitecheck.js のサイト判定を使う)。
//
// - 自動スクロール: 設定 autoScrollSpeed (1〜10。既定 4) の速さで、ゆっくり下へ (縦書きは、左へ。window.nhVerticalScroll に頼む)。画面を触る・スクロールする・キーを押す・ページを離れる・いちばん下に着く、で止まる。
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
  const vertical = () => (window.nhVerticalScroll && nhVerticalScroll.active() ? nhVerticalScroll : null); // 縦書きのページ (本文が横にスクロール。ui/ui-vertical.js)
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
    const v = vertical();
    if (px > 0) { acc -= px; if (v) v.by(px); else window.scrollBy(0, px); }
    if (v ? v.atEnd() : atBottom()) { // いちばん下 (縦書きは、いちばん左) に着いた
      stop();
      if (settings().autoNextEpisode) window.nhGoNext('scroll'); // 設定 autoNextEpisode: 次の話があれば開いて、続けて自動スクロール
      return;
    }
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

  // ---- 次の話へ自動で進む (設定 autoNextEpisode。読み上げは Kotlin の ReadingAids が、自動スクロールは上の tick が呼ぶ) ----
  // 次の話の URL は、サイトごとの nextUrl (最終話には無い)。開いたあとも続けるため、自動スクロールのときは印 (sessionStorage) を残し、
  // 開いたページで、本文があれば、少し待ってから自動スクロールを始める。読み上げは、Kotlin が、ページを開き終えたあとに始める
  const CONT_KEY = 'nhAutoCont';
  const CONT_MAX_AGE_MS = 60000; // 次の話が開くまで待つ長さ (これより古い印は、無視する)
  const nextUrlOf = () => { try { const a = window.NH && NH.adapter && NH.adapter(); return (a && a.nextUrl && a.nextUrl()) || null; } catch (e) { return null; } };
  // 戻り値: 開き始めたか (次の話が無ければ false)
  window.nhGoNext = (mode) => {
    const u = nextUrlOf();
    if (!u) return false;
    if (mode === 'scroll') { try { sessionStorage.setItem(CONT_KEY, 'scroll:' + Date.now()); } catch (e) { /* 続けられないだけ */ } }
    location.href = u;
    return true;
  };
  (function resumeAfterGoNext() {
    let mode = null;
    try { mode = sessionStorage.getItem(CONT_KEY); sessionStorage.removeItem(CONT_KEY); } catch (e) { /* 無い */ }
    // 印が古い (次の話が開けなかったあと、別の作品を開いたとき) ときは、始めない
    const m = /^scroll:(\d+)$/.exec(mode || '');
    if (!m || Date.now() - Number(m[1]) > CONT_MAX_AGE_MS) return;
    // 本文のある話のページだけ (ほかのページへ移っていたら始めない)
    const go = () => { const s = bodySel(); if (s && document.querySelector(s)) start(); };
    if (document.readyState === 'complete') setTimeout(go, 1200);
    else window.addEventListener('load', () => setTimeout(go, 1200), { once: true });
  })();
})();
