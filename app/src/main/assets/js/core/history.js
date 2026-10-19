// Yomikura 共通基盤 (15): 閲覧履歴
window.NH.define('history', function (NH) {
  NH.need('store');
  NH.need('siteinfo');
  NH.need('list-model');
  // ---- 閲覧履歴 ----
  // history: [{key, site, id, title, url, ep, at, appAt, siteAt, siteUrl, total, totalAt, status, r18, sub, epNo, epNoEp, pr, reads}] 新しい順。
  //   appAt  = アプリで最後に開いた時刻 (url / ep はそのときの話。短編・目次は ep = null)
  //   siteAt = 公式の閲覧履歴ページで最後に見かけた時刻 (siteUrl は公式の「続きから」)
  //   total / totalAt / status / r18 = 作品情報から (ui-work.js) / epNo = 何話目か (epNoEp = そのときの話) / pr = 読み具合 {y, done, ep} (ui-readpos.js。読むのは NH.historyPos)
  //   reads = 日ごとに読んだ話 (NH.noteEpisodeRead)
  // 公式の履歴はユーザーがそのページを開いたときだけ取り込む (こちらから取りに行かない)。
  NH.HISTORY_MAX = 300; // 保存する作品数の既定 (settings.historyMax。0 = 無制限)
  NH.historyOn = (d) => NH.flag(d, 'history');
  // 古い履歴を消す: 保存する日数 (settings.historyDays。0 = 無期限、既定) を過ぎたものと、保存する作品数 (settings.historyMax) を超えた分。
  // 日数は最後に開いた時刻 (アプリ at / 公式 siteAt の新しい方) で見る。戻り値: 消した数
  NH.pruneHistory = function (d) {
    if (!Array.isArray(d.history)) return 0;
    const st = d.settings || {};
    const max = st.historyMax == null ? NH.HISTORY_MAX : st.historyMax;
    const days = st.historyDays || 0;
    const before = d.history.length;
    if (days > 0) {
      const limit = Date.now() - days * 86400000;
      d.history = d.history.filter((e) => Math.max(e.at || 0, e.siteAt || 0) >= limit);
    }
    if (max > 0 && d.history.length > max) d.history.length = max;
    return before - d.history.length;
  };

  // アプリで作品 / 話のページを開いた
  // 作品名がどこからも分からないときは記録しない (作品の URL のまま出るエラーや確認の途中のページで、作品を開いたことにしない)
  // short: 短編 (作品ページそのものが本文) なら、目次でなく話を開いたのと同じに扱う
  NH.recordVisit = function (w, title, url, short) {
    const key = NH.workKey(w);
    // R18 はサイトをリストの項目に書き足す (更新の取得で入った作品はブックマーク一覧からは分からず、開いて初めて分かる)
    const sub = w.site === 'narou18' ? NH.r18Sub() : '';
    const d = NH.load();
    const fixSub = !!sub && d.readLater.some((x) => x.key === key && x.sub !== sub);
    // 作品名の分からないまま入れた作品 (外から来た R18 のリンクなど。ID を仮の名前にしている) は、開いたときの作品名に直す
    const pageTitle = title;
    const fixTitle = !!pageTitle && d.readLater.some((x) => x.key === key && (!x.title || x.title === x.id));
    const h = d.history || [];
    const i = h.findIndex((x) => x.key === key);
    title = title || (i >= 0 && h[i].title) || (NH.findItem(d, w) || {}).title || '';
    const track = NH.historyOn(d) && !!title;
    if (!fixSub && !fixTitle && !track) return;
    NH.update((d) => {
      if (fixSub) d.readLater.forEach((x) => { if (x.key === key) x.sub = sub; });
      if (fixTitle) d.readLater.forEach((x) => { if (x.key === key && (!x.title || x.title === x.id)) x.title = pageTitle; });
      if (!track || !NH.historyOn(d)) return;
      const h = (d.history = d.history || []);
      const i = h.findIndex((x) => x.key === key);
      const e = i >= 0 ? h.splice(i, 1)[0] : { key, site: w.site, id: w.id };
      e.title = title;
      if (i >= 0 && e.url && w.episode == null && !short) { h.splice(i, 0, e); return; } // 目次を開いただけなら、読んだ時刻も順番も変えない
      if (w.episode != null || short || !e.url) {
        e.url = url; e.ep = w.episode != null ? String(w.episode) : null; // 目次を開いただけなら、読んだ話は残す
      }
      e.at = e.appAt = Date.now();
      if (sub) e.sub = sub;
      const listed = d.readLater.find((x) => x.key === key);
      if (listed && listed.r18 !== undefined) e.r18 = listed.r18; // ハーメルンの R-18 (リストで調べた結果)。NSFW モードで履歴から隠すため
      h.unshift(e);
      NH.pruneHistory(d);
    });
  };

  // ---- 日ごとに読んだ話の記録 (履歴の行に「今日 3〜5・7話」と出す)。history[].reads = {'2026-10-17': [3, 5, ...]} (話番号の昇順) ----
  NH.READ_LOG_DAYS = 30; // 何日前の日まで残すか
  NH.dayKey = (t) => { const x = new Date(t); return x.getFullYear() + '-' + String(x.getMonth() + 1).padStart(2, '0') + '-' + String(x.getDate()).padStart(2, '0'); };
  // key の履歴に、その日読んだ話 n を足す。足したら true (履歴に無い作品・すでに足した話は false)。NH.update の fn の中で呼ぶ
  NH.noteEpisodeRead = (d, key, n, now = Date.now()) => {
    const h = (d.history || []).find((x) => x.key === key);
    if (!h || !n) return false;
    const reads = (h.reads = h.reads || {});
    const day = (reads[NH.dayKey(now)] = reads[NH.dayKey(now)] || []);
    if (day.includes(n)) return false;
    day.push(n);
    day.sort((a, b) => a - b);
    const limit = NH.dayKey(now - NH.READ_LOG_DAYS * 86400000);
    Object.keys(reads).forEach((k) => { if (k < limit) delete reads[k]; }); // 日付の文字列は、そのまま大小で比べられる
    return true;
  };
  // 話番号の並び (昇順) を「1〜3・5・7〜8」の形にする。まとまりが max 個を超えるときは、最後の max 個だけを「…・」付きで出す (1 話おきに読む人の行が数字で埋まらないように)
  NH.fmtEpisodes = (list, max = Infinity) => {
    const out = [];
    for (let i = 0; i < list.length;) {
      let j = i;
      while (j + 1 < list.length && list[j + 1] === list[j] + 1) j++;
      out.push(j > i ? list[i] + '〜' + list[j] : String(list[i]));
      i = j + 1;
    }
    return (out.length > max ? '…・' : '') + out.slice(-max).join('・');
  };

  // 公式の閲覧履歴ページの内容 ([{id, title, url, total}] 新しい順) を突き合わせて取り込む。戻り値: 新しく増えた件数
  NH.importSiteHistory = function (site, items) {
    let added = 0;
    if (!NH.historyOn(NH.load())) return added;
    NH.update((d) => {
      added = 0; // NH.update がやり直したときのために、ここで数え直す
      const h = (d.history = d.history || []);
      const now = Date.now();
      items.forEach((u, idx) => {
        const key = site + ':' + u.id;
        let e = h.find((x) => x.key === key);
        if (!e) {
          e = { key, site, id: u.id, title: u.title };
          h.push(e);
          added++;
        }
        // 公式にしか無い作品 (別の端末で読んだものなど) は時刻が分からない。
        // at を負の値 (公式の並び順) にして、アプリで読んだ作品より後ろに並べる
        if (!e.appAt) e.at = -(idx + 1);
        Object.assign(e, { siteAt: now, siteUrl: u.url, title: e.title || u.title });
        if (u.total) e.total = u.total;
      });
      h.sort((a, b) => b.at - a.at);
      NH.pruneHistory(d);
    });
    return added;
  };
});
