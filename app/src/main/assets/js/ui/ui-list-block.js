// ブロック: 一覧の外 (おすすめ・作者の作品一覧など) の作品へのリンクも隠す (サイト上の画面。ui-list.js が呼ぶ)。
// 見るのは作品のトップへのリンクだけ (話へのリンクは、目次を消さないため対象外)。今開いている作品は対象外。
// 作者はリンクからは分からないので、作品とタイトルのキーワードで判定する。隠すのは、その作品へのリンクだけを含むいちばん外側の囲み。
// ブロックが 1 つも無ければ何もしない (ページのリンクを見て回らない)。
// ctx = {climbBox (囲みを探す。ui-list.js)}。戻り値: {sweepBlocked}
window.NH_UI_LIST_BLOCK = function (ctx) {
  const { climbBox } = ctx;
  const curWork = NH.parseWork(location.href);
  const curKey = curWork ? NH.workKey(curWork) : '';
  // 1 つの作品 (key) へのリンクだけを含む、いちばん外側の囲み
  const linkBox = (anchor, key) => climbBox(anchor, (p) => [...p.querySelectorAll('a[data-nh-wk]')].some((x) => x.dataset.nhWk !== key));
  function sweepBlocked() {
    const d = NH.load();
    const on = NH.hasBlock(d);
    const before = [...document.querySelectorAll('.nh-blocked-link')];
    if (!on && !before.length) return;
    // 読み取り (囲みの大きさを測る) を先にまとめ、隠すのはあとで (交互にすると配置の計算がやり直しになる)
    const links = [];
    if (on) {
      document.querySelectorAll('a[href]').forEach((a) => {
        if (a.closest('.nh-chips, .nh-sheet')) return;
        const w = NH.parseWork(a.href);
        if (!w || w.episode != null) return;
        const key = NH.workKey(w);
        if (key === curKey) return;
        if (a.dataset.nhWk !== key) a.dataset.nhWk = key; // 変わったときだけ書く (書き換えると MutationObserver が反応して繰り返しになるため)
        links.push({ a, key });
      });
    }
    const boxes = new Set(links.filter(({ a, key }) => NH.blockReason(d, { key, title: a.textContent.trim() })).map(({ a, key }) => linkBox(a, key)));
    before.forEach((b) => { if (!boxes.has(b)) b.classList.remove('nh-blocked-link'); });
    boxes.forEach((b) => b.classList.add('nh-blocked-link'));
  }
  return { sweepBlocked };
};
