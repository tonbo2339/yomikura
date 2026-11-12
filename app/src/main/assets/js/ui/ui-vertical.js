// サイトの話ページの縦書き (サイト上の画面。ui.js が呼ぶ)。アプリの設定 siteVertical (TT の「組み方向」) に従う。
//  - なろう・ハーメルン: 本文の箱 (題名を含む囲み) を、画面いっぱいの縦書きの箱 (#nh-vwrap。右から左へ横にスクロール) に移し、サイトのほかの部分は隠す。
//    前後の話・目次は、箱の両端にアプリのボタンで置く (保存した作品の読む画面と同じ並び)。数字・英字は立てる (NH.uprightRuns)。
//  - カクヨム: サイト自身の縦組み (<body> の class writingDirection-vertical) を、設定に合わせて切り替える (サイトの組み方向の欄を押す)。
// ctx = {adapter, mk, cur, isShort (短編。話番号が無く、本文がある)}。短編も縦書きにする (前の話・次の話・目次のボタンは出さない)。戻り値: 読書位置が読み書きに使う窓口 V (横書きのままなら、V.active() が false)
//   V = {active(), state() → {y, done} (0〜1)、seek(y)、pos() (いまのスクロール量。動いたかの比べ用)、onScroll(fn)}
window.NH_UI_VERTICAL = function (ctx) {
  const { adapter, mk, cur, isShort } = ctx;
  if (!(cur && adapter.bodySel && (cur.episode != null || isShort))) return null;
  const want = NH.flagOf(NH.load().settings, 'siteVertical', false);
  const ratio = (pos, max) => (max <= 8 ? 1 : Math.max(0, Math.min(1, pos / max)));
  // 縦書きは本文が横にスクロールする。アプリの「先頭へ・末尾へ」「音量キーのページ送り」と、自動スクロール (ui/reader.js) は、縦のスクロールを前提にしているので、
  // この窓口 (window.nhVerticalScroll) に頼む。V = 下の窓口、el = スクロールする要素を返す関数。進む = 左へ (pos が増える)
  const expose = (V, el) => {
    V.by = (px, smooth) => { el().scrollBy({ left: -px, behavior: smooth ? 'smooth' : 'auto' }); };
    V.page = (dir) => V.by(dir * el().clientWidth * 0.9, true);
    V.atEnd = () => V.state().y >= 0.999;
    window.nhVerticalScroll = V;
    return V;
  };


  // ---- 上のバー (アプリのタブバー + 上の列) の出し入れ: 保存した作品の縦書きと同じ ----
  // 始めは隠して本文だけを見せる。出すのは本文のタップだけ (戻す向きのスクロールでは出さない)。出ているバーは、読み進める向き (縦書きは左へ = pos が増える) に動かすと隠す
  // (本文の上に重ねるので、本文の広さは変わらない)
  const bars = (() => {
    const MUTE_MS = 450, HIDE_AFTER = 24, RETRY_MS = 700; // 切り替え直後に拾う動きを無視する時間・隠すまでに進める量 (px)・アプリ側へもう一度頼むまで
    let on = false, hidden = false, mute = 0, lastPos = 0, acc = 0, V = null, listening = false;
    const row = () => document.getElementById('nh-toc-top');
    const apply = (h) => {
      hidden = h; mute = Date.now() + MUTE_MS; lastPos = V ? V.pos() : 0; acc = 0;
      document.documentElement.classList.toggle('nh-bars-hidden', h);
      const r = row();
      if (r) r.classList.toggle('nh-bar-hidden', h);
      NH.off.siteBar(h);
    };
    document.addEventListener('click', (e) => {
      if (!on || !e.target.closest) return;
      if (e.target.closest('a,button,input,select,textarea,label,#nh-toc-top,.nh-sheet,.nh-scrim,.nh-vnav')) return;
      apply(!hidden);
    });
    return {
      // 縦書きの窓口 V を渡して始める (カクヨムは、サイトが縦組みになったとき)。false なら、バーを元に戻す
      set(v) {
        if (v && !on) {
          on = true; V = v;
          document.documentElement.classList.add('nh-vbars');
          if (!listening) { // 縦組みを切り替え直しても、聞き手は 1 つだけ (V は同じ窓口)
            listening = true;
            V.onScroll(() => {
              if (!on) return;
              const p = V.pos(), dy = p - lastPos; lastPos = p;
              if (Date.now() < mute) return;
              // 進む向きに動かすと隠す。戻す向きでは出さない (読み返しのたびにバーが出ると、読みづらい)。出すのは、本文のタップだけ
              if (dy > 0) { acc = Math.max(0, acc) + dy; if (!hidden && acc > HIDE_AFTER) apply(true); }
              else if (dy < 0) acc = Math.min(0, acc) + dy;
            });
          }
          apply(true);
          setTimeout(() => { if (hidden) NH.off.siteBar(true); }, RETRY_MS); // アプリ側がまだ読書ページと見ていないことがあるので、もう一度頼む
        } else if (!v && on) {
          on = false;
          document.documentElement.classList.remove('nh-vbars');
          apply(false);
        }
      },
    };
  })();
  // ---- カクヨム: サイトの縦組みに合わせる ----
  if (adapter.id === 'kakuyomu') {
    const is = () => document.body.classList.contains('writingDirection-vertical');
    const se = () => document.scrollingElement || document.documentElement;
    const V = {
      active: is,
      pos: () => -se().scrollLeft,
      state() { const e = se(), y = ratio(-e.scrollLeft, e.scrollWidth - e.clientWidth); return { y, done: y >= 0.98 }; },
      seek(y) { const e = se(); e.scrollLeft = -y * (e.scrollWidth - e.clientWidth); }, // 縦書きは右から左へ進む (scrollLeft は 0 から負へ)
      onScroll: (fn) => window.addEventListener('scroll', fn, { passive: true }),
    };
    // サイトの設定の欄 (隠しているパネルの中) を押して切り替える。サイトがその組み方向を覚えるので、次からは最初から合う
    const sync = () => {
      if (is() !== want) {
        const input = document.getElementById('input-displaySetting-writingDirection-' + (want ? 'vertical' : 'horizontal'));
        if (input) input.click();
      }
      bars.set(is() ? V : null); // 縦組みのあいだは、上のバーを本文のタップで出し入れする (保存した作品の縦書きと同じ)
    };
    [800, 2500, 4500].forEach((ms) => setTimeout(sync, ms));
    return expose(V, se);
  }

  // ---- なろう・ハーメルン: 縦書きの箱へ移す ----
  if (!want) return null;
  const first = document.querySelector(adapter.bodySel);
  if (!first) return null;
  const unit = adapter.verticalUnit ? first.closest(adapter.verticalUnit) : first.parentElement;
  if (!unit || unit === document.body || unit === document.documentElement) return null;
  const prev = adapter.prevUrl ? adapter.prevUrl() : null;
  const next = adapter.nextUrl ? adapter.nextUrl() : null;
  const go = (url) => { location.href = url; };
  const btn = (label, url) => {
    const b = mk('button', 'nh-vbtn', label);
    b.type = 'button';
    if (url) b.addEventListener('click', () => go(url)); else b.disabled = true;
    return b;
  };
  const nav = (...btns) => { const d = mk('div', 'nh-vnav'); d.append(...btns); return d; };

  const wrap = mk('div', 'nh-vwrap');
  wrap.id = 'nh-vwrap';
  // 縦書きは右から左へ進む: 右の端 (読み始め) に「前の話・目次」、左の端 (読み終わり) に「目次・次の話」
  // 短編は、前後の話も目次も無い (作品のページが、この本文のページ)
  wrap.append(...[isShort ? null : nav(btn('前の話', prev), btn('目次', cur.url)), unit, isShort ? null : nav(btn('目次', cur.url), btn('次の話', next))].filter(Boolean));
  (document.body || document.documentElement).appendChild(wrap);
  document.documentElement.classList.add('nh-v');
  NH.uprightRuns(unit);

  const V = {
    active: () => true,
    pos: () => -wrap.scrollLeft,
    state() { const y = ratio(-wrap.scrollLeft, wrap.scrollWidth - wrap.clientWidth); return { y, done: y >= 0.98 }; },
    seek(y) { wrap.scrollLeft = -y * (wrap.scrollWidth - wrap.clientWidth); },
    onScroll: (fn) => wrap.addEventListener('scroll', fn, { passive: true }),
  };
  bars.set(V); // 保存した作品の縦書きと同じ: 始めは上のバーを隠し、本文のタップで出し入れする
  return expose(V, () => wrap);
};
