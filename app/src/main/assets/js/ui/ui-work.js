// 作品ページ・公式の閲覧履歴ページを開いたときの、リスト・履歴データの補い (サイト上の画面。ui.js が呼ぶ)。
//  - 作品の小説情報 (全話数・連載状態・R18 の掲載サイト) を、リストの作品に覚える
//  - 公式の閲覧履歴ページ: 開いたときだけ読み取り、アプリの履歴と突き合わせる
// ctx = ui.js の共通の部品 {adapter, mk, cur (いまの作品。作品のページでなければ null)}。
window.NH_UI_WORK = function (ctx) {
  const { adapter, mk, cur } = ctx;
  // 作品の小説情報 (全話数・連載状態・R18 の掲載サイト) を、リストの作品に覚える (続きから読むの「第N話/全N話」などに使う)。
  // 覚えてから 1 日以内なら読み直さない (サイトへの通信を減らす)。確認画面が出たら、そのサイトを休止する
  async function rememberInfo(w) {
    const DAY = 86400000;
    const d0 = NH.load();
    // 覚える先: リストの作品と、履歴の作品 (どちらにも覚える。履歴の「第N / 全N話」にも使うため)
    const histOf = (d) => (d.history || []).find((x) => x.key === NH.workKey(w));
    const triedAt = (d, at) => [NH.findItem(d, w), histOf(d)].forEach((t) => { if (t) t.totalTry = at; }); // 読み直しを 1 日待つ印 (リスト・履歴の両方)
    const it = NH.findItem(d0, w) || histOf(d0);
    // 読めたが全話数が分からなかったとき (作品情報に無い作品) も totalTry を残して、1 日は読み直さない。止められて休止中のサイトにも取りに行かない
    if (!it || NH.isResting(d0, adapter.id, true) || (it.totalAt && Date.now() - it.totalAt < DAY) || (it.totalTry && Date.now() - it.totalTry < DAY)) return;
    let info = null;
    try {
      info = await adapter.workInfo(w);
    } catch (e) {
      const msg = (e && e.message) || '';
      if (msg === NH.CHALLENGE) NH.markRest(adapter.id);
      // 通信の失敗は記録しない (次に開いたとき読み直す)。ただし 4xx (削除された作品など) は読み直しても同じなので、1 日あける
      else if (/^http 4/.test(msg)) NH.update((d) => triedAt(d, Date.now()));
      return;
    }
    const total = info && info.total;
    NH.update((d) => {
      const x = NH.findItem(d, w);
      const h = histOf(d);
      const at = Date.now();
      triedAt(d, at);
      if (!total) return;
      [x, h].filter(Boolean).forEach((t) => { Object.assign(t, { total, totalAt: at }); delete t.totalTry; });
      if (h && info.status) h.status = info.status; // 履歴にも連載状態 (連載中・完結・短編) を覚える。連載中 → 完結の変わりも拾う
      if (h && info.r18 !== undefined) h.r18 = !!info.r18; // NSFW モードで履歴から隠すため、履歴にも覚える
      if (!x) return;
      x.r18 = !!info.r18; // R-18 のタグがあるか (NSFW モードで隠す)。未確認の作品は undefined のまま
      if (info.status) x.status = info.status; // 作品情報が正。連載中 → 完結の変わりも拾う
      if (info.sub) x.sub = info.sub;
    });
  }
  if (cur && adapter.workInfo && (cur.episode == null || !adapter.workInfoOnPage)) rememberInfo(cur); // 作品ページでも話ページでも (話ページから続きを読んだ作品も、全話数を覚える)。1 日に 1 回まで

  // ---- 公式の閲覧履歴ページ: 開いたときだけ読み取り、アプリの履歴と突き合わせる ----
  if (adapter.isHistoryPage && adapter.isHistoryPage(location) && NH.historyOn(NH.load())) {
    const items = adapter.parseHistory(document);
    if (items.length) {
      const added = NH.importSiteHistory(adapter.id, items);
      const d = NH.load();
      // 各作品に、アプリでの読み具合を添える
      items.forEach((u) => {
        const key = adapter.id + ':' + u.id;
        const p = (d.progress || {})[key];
        const inList = d.readLater.some((x) => x.key === key && !x.done);
        const label = [p ? (NH.epNumber(p) ? 'アプリ: 第' + NH.epNumber(p) + '話' : 'アプリで読書中') + (p.done ? ' 読了' : p.y >= 0.01 ? ' ' + Math.round(p.y * 100) + '%' : '') : '', inList ? 'リスト' : '']
          .filter(Boolean).join(' ・ ');
        if (label && u.anchor && !u.anchor.dataset.nhHist) {
          u.anchor.dataset.nhHist = '1';
          u.anchor.insertAdjacentElement('afterend', mk('div', 'nh-hist', label));
        }
      });
      const bn = mk('button', 'nh-banner', '閲覧履歴をアプリと照合しました' + (added ? ' (新しく ' + added + ' 件)' : '') + ' ・ アプリの履歴を開く');
      bn.addEventListener('click', () => (location.href = NH.HISTORY_URL));
      (document.body || document.documentElement).appendChild(bn);
      setTimeout(() => bn.remove(), 8000);
    }
  }
};
