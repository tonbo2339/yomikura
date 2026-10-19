// ページの下の欄 (感想・おすすめなど) を畳む開閉のボタン (サイト上の画面。ui.js が呼ぶ)。
// ctx = ui.js の共通の部品 {adapter, mk}。
window.NH_UI_FOLD = function (ctx) {
  const { adapter, mk } = ctx;
  // 畳むのは early.js の CSS (描画前)。ここは開閉のボタンを置く。設定 foldComments などの fold* (既定オン)、adapter.foldGroups を持つサイトだけ。
  // 開いたかどうかは、設定 foldRemember (既定オン) のときサイトごとに settings.foldOpen に覚える (開いたまま次の話へ)。サイトの「感想を書く」を押したときは、畳んだ感想欄も出す
  const FOLD = (window.NH_FOLDS || []).map((f) => [f.key, f.flag, f.label]); // 種類の表は sites.js (NH_FOLDS)
  const openers = {};
  const repaints = {};
  const foldSite = /^(h\.)?syosetu\.org$/.test(location.hostname) ? 'hameln' : location.hostname === 'kakuyomu.jp' ? 'kakuyomu' : 'narou';
  const rememberFold = (k, open) => {
    if (!NH.flag(NH.load(), 'foldRemember')) return;
    NH.update((d) => {
      const s = (d.settings = d.settings || {});
      const o = ((s.foldOpen = s.foldOpen || {})[foldSite] = s.foldOpen[foldSite] || {});
      if (open) o[k] = true; else delete o[k];
    });
  };
  // 畳む CSS (early.js が描画の前に入れる)。開いたままの欄 (foldRemember) は early.js が入れないので、閉じるときにここで入れる。
  // 同じ欄に 2 回入れない (id で見分ける)
  function ensureFoldHide(k, sel) {
    const id = 'nh-fold-hide-' + k;
    if (document.getElementById(id)) return;
    const style = mk('style');
    style.id = id;
    style.textContent = sel.split(',').map((x) => x + ':not([data-nh-open])').join(',') + '{display:none!important}';
    (document.head || document.documentElement).appendChild(style);
  }
  function setupFold() {
    const groups = adapter.foldGroups;
    if (!groups) return;
    const st = NH.load().settings || {};
    FOLD.forEach(([k, flag, label]) => {
      if (!groups[k] || !NH.flagOf(st, flag)) return;
      // 置いたあとに増えた枠 (広告のスクリプトが後から差し込む) も、開閉の対象に入れる
      if (repaints[k]) { repaints[k](); return; }
      // ボタンは、ページ上部のヘッダー・ナビの中にある枠 (ログインの案内など) の手前には置かず、本文側の最初の枠の手前に置く
      const first = [...document.querySelectorAll(groups[k])].find((e) => !e.closest('header, nav'));
      if (!first) return;
      const btn = mk('button', 'nh-fold');
      btn.id = 'nh-fold-' + k;
      let open = NH.flagOf(st, 'foldRemember') && !!((st.foldOpen || {})[foldSite] || {})[k];
      const paint = () => {
        ensureFoldHide(k, groups[k]);
        document.querySelectorAll(groups[k]).forEach((b) => (open ? b.setAttribute('data-nh-open', '') : b.removeAttribute('data-nh-open')));
        // 同じ文字なら代入しない (代入すると childList の監視が反応し、探し直しが続いてしまう)
        const text = (open ? '▲ ' : '▼ ') + label + (open ? 'を隠す' : 'を表示');
        if (btn.textContent !== text) btn.textContent = text;
      };
      btn.onclick = () => { open = !open; paint(); rememberFold(k, open); };
      openers[k] = () => { if (!open) { open = true; paint(); rememberFold(k, true); } };
      repaints[k] = paint;
      first.insertAdjacentElement('beforebegin', btn);
      paint();
    });
  }
  if (adapter.foldGroups) {
    setupFold();
    setTimeout(setupFold, 1500); // 後から描かれるページ (カクヨムなど) 用
    setTimeout(setupFold, 4000);
    // 広告の枠などは広告のスクリプトが後から差し込む。読み込みから 20 秒は、DOM が増えたところで探し直す (置いたボタンは二重に作らない)
    let foldTimer = null;
    const foldWatch = new MutationObserver(() => { if (!foldTimer) foldTimer = setTimeout(() => { foldTimer = null; setupFold(); }, 400); });
    foldWatch.observe(document.documentElement, { childList: true, subtree: true });
    setTimeout(() => foldWatch.disconnect(), 20000);
    document.addEventListener('click', (e) => {
      const a = e.target.closest && e.target.closest('a');
      if (a && /感想を書く/.test(a.textContent) && openers.comments) openers.comments();
    }, true);
  }
};
