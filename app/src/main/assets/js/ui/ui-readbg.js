// 読書ページの背景色 (site/readbg.js) の仕上げ。サイト上の画面 (ui.js が呼ぶ)。
// early.js の CSS が、ページの地 (html, body) と本文の色を描画前に変える。ここは、そのほかの「白に近い面」(パネル・メニュー・下の欄・ボタンの帯) と、
// その上の文字・リンクを、選んだ色に合わせる (サイトの白い面に、明るい文字が乗って読めなくなるのを防ぐ)。
//  - 面 = 無彩色に近い (白・灰色・黒の) 背景色を持つ要素 → data-nh-surface (背景を選んだ色に)。サイト自身のダーク表示で暗い面も含む
//  - 文字 = 実際の背景が、ページの地か、上の面である文字 → data-nh-ink (文字色を合わせる)。リンクは data-nh-link
//  - 色つきのボタン (緑・青など) と、その中の文字は、サイトの色のまま
// 印の色は readbg.js の css() が決める。読書ページにいる間だけ (early.js が html に data-nh-readbg を付ける)。ページが描き替わったら、少し待ってやり直す。
window.NH_UI_READBG = function () {
  const R = window.NH_READBG;
  const root = document.documentElement;
  if (!R || !R.colors((NH.load().settings || {}).readBg)) return;
  const NEUTRAL = 40; // R・G・B の最大と最小の差がこれ未満の背景は、無彩色に近い (色つきのボタンの緑・青などは、これ以上)
  const RE = /rgba?\(\s*(\d+)[ ,]+(\d+)[ ,]+(\d+)(?:[ ,/]+([\d.]+))?/;
  const OWN = '[id^="nh-"],[class^="nh-"],[class*=" nh-"]'; // このアプリの部品 (と、その中) は触らない
  const bgOf = (e) => { // {opaque, neutral}
    const m = RE.exec(getComputedStyle(e).backgroundColor);
    if (!m || (m[4] !== undefined && Number(m[4]) < 0.9)) return { opaque: false, neutral: false };
    return { opaque: true, neutral: Math.max(m[1], m[2], m[3]) - Math.min(m[1], m[2], m[3]) < NEUTRAL };
  };
  // 1 回の paint のあいだは、同じ要素の背景色を使い回す (getComputedStyle は重い。文字の判定は祖先を何度もたどるので、要素数 × 階層の深さの回数になる)
  let memo = null;
  const bgc = (e) => {
    if (!memo) return bgOf(e);
    let r = memo.get(e);
    if (!r) { r = bgOf(e); memo.set(e, r); }
    return r;
  };
  const pageLike = (e) => e === root || e === document.body || e.hasAttribute('data-nh-surface');
  const hasText = (e) => { if (e.matches('input[type=submit],input[type=button],input[type=reset]')) return true; for (const n of e.childNodes) if (n.nodeType === 3 && n.nodeValue.trim()) return true; return false; };
  // 文字の実際の背景が、ページの地か上の面なら true (途中に色つきの面があれば false)
  const onPage = (e) => {
    for (let p = e; p && p !== root; p = p.parentElement) {
      if (pageLike(p)) return true;
      if (bgc(p).opaque) return false;
    }
    return true;
  };
  const clear = () => document.querySelectorAll('[data-nh-surface],[data-nh-ink],[data-nh-link]').forEach((e) => {
    e.removeAttribute('data-nh-surface'); e.removeAttribute('data-nh-ink'); e.removeAttribute('data-nh-link');
  });
  const paint = () => {
    if (!root.hasAttribute('data-nh-readbg') || !document.body) { clear(); return; }
    memo = new Map();
    try {
      const all = [...document.body.querySelectorAll('*')].filter((e) => !e.closest(OWN));
      // 1 回目: 無彩色の面に印を付ける (外側から。文字の判定は、全部の面に印が付いてから)
      all.forEach((e) => { const b = bgc(e); if (b.opaque && b.neutral) e.setAttribute('data-nh-surface', ''); });
      // 2 回目: 文字
      all.forEach((e) => {
        if (!hasText(e)) return;
        if (!onPage(e)) { e.removeAttribute('data-nh-ink'); e.removeAttribute('data-nh-link'); return; }
        e.setAttribute('data-nh-ink', '');
        if (e.closest('a') || e.tagName === 'INPUT') e.setAttribute('data-nh-link', ''); else e.removeAttribute('data-nh-link');
      });
    } finally { memo = null; }
  };
  let timer = 0, stale = false;
  // 画面が見えていない間 (別のアプリ・画面オフ) は描かず、見えたときにやり直す (広告などの DOM の変化のたびに、全要素を調べ直さない)
  const later = () => {
    clearTimeout(timer);
    if (document.hidden) { stale = true; return; }
    timer = setTimeout(paint, 400);
  };
  document.addEventListener('visibilitychange', () => { if (!document.hidden && stale) { stale = false; later(); } });
  paint();
  if (typeof MutationObserver === 'function') {
    new MutationObserver(later).observe(document.body, { childList: true, subtree: true });
    new MutationObserver(later).observe(root, { attributes: true, attributeFilter: ['data-nh-readbg'] }); // カクヨムの話 ⇄ 目次の移動
  }
  setTimeout(paint, 1500); // 後から描かれるページ (カクヨムなど) 用
  setTimeout(paint, 4000);
};
