// Yomikura 共通基盤 (8): 「後で読む」リストの作品の扱い・更新の取り込み・次の小説・読了の片付け
window.NH.define('list-model', function (NH) {
  NH.need('store');
  NH.need('nsfw');
  NH.need('read-state');
  NH.need('siteinfo'); // NH.withSub
  // リストの種類: 更新取得で入ったもの = 'update' (未読の更新)、それ以外 = 'later' (後で読む)
  NH.kindOf = (x) => (x.kind === 'update' ? 'update' : 'later');

  // 読了の印。読了にした時刻 (doneAt) も持つ (一定日数で自動的に片付けるため)
  NH.setDone = (x, on) => {
    x.done = !!on;
    if (on) x.doneAt = x.doneAt || Date.now();
    else delete x.doneAt;
  };

  // リストから外した作品の、話ごとの読書位置の記録も消す
  NH.dropProgress = (d, keys) => { if (d.progress) keys.forEach((k) => delete d.progress[k]); };
  // 読了から一定日数 (settings.pruneDays、既定 30 日) たった作品を消す。settings.pruneDone === false なら消さない。
  // doneAt の無い読了 (この機能より前のもの) は、今から数える。戻り値: 消した件数
  NH.PRUNE_DAYS = 30;
  NH.pruneDone = function (d) {
    const st = d.settings || {};
    d.readLater.forEach((x) => { if (x.done && !x.doneAt) x.doneAt = Date.now(); });
    if (!NH.flagOf(st, 'pruneDone')) return 0;
    const limit = Date.now() - (st.pruneDays || NH.PRUNE_DAYS) * 86400000;
    const gone = d.readLater.filter((x) => x.done && x.doneAt < limit).map((x) => x.key);
    if (gone.length) {
      d.readLater = d.readLater.filter((x) => !gone.includes(x.key));
      NH.dropProgress(d, gone);
    }
    return gone.length;
  };

  NH.workKey = (w) => w.site + ':' + w.id;
  NH.findItem = (d, w) => d.readLater.find((x) => x.key === NH.workKey(w));

  // 絞り込み・検索したリストの順で読み進める (readSeq = {keys: [作品のキー], at})。
  // リストで絞り込み中に作品を開いたとき、リストが並べた順を控える。1 日以上前の並びは使わない。
  NH.READ_SEQ_TTL = 24 * 60 * 60 * 1000;
  NH.setReadSeq = (d, keys) => { d.readSeq = keys ? { keys, at: Date.now() } : null; };
  // 今の作品が控えた並びに入っていれば、その後ろで読んでいない作品の列 (順番どおり)。入っていなければ null
  NH.seqRest = function (d, it) {
    const s = d.readSeq;
    if (!it || !s || !Array.isArray(s.keys) || Date.now() - s.at > NH.READ_SEQ_TTL) return null;
    const i = s.keys.indexOf(it.key);
    if (i < 0) return null;
    return s.keys.slice(i + 1).map((k) => d.readLater.find((x) => x.key === k)).filter((x) => x && !x.done && !NH.nsfwHides(d, x));
  };

  // 読了のあとに開く「次の小説」。絞り込みの並び (seqRest) があればその続き、無ければ同じ種類 (未読 / 後で読む) の先頭、それも無ければ残り全体。
  // 戻り値: {seq: 並びの続きか (並びの最後なら pool が空でも true), pool: 候補, next: 次の作品 | undefined, remaining: 残りの作品数}
  NH.nextPool = function (d, it) {
    const seq = NH.seqRest(d, it);
    const active = d.readLater.filter((x) => !x.done && x !== it && !NH.nsfwHides(d, x)); // NSFW モードで隠れている作品は、次に選ばない
    const same = active.filter((x) => NH.kindOf(x) === NH.kindOf(it));
    const pool = seq || (same.length ? same : active);
    return { seq: !!seq, pool, next: pool[0], remaining: pool.length };
  };

  // 取得した更新 (未読あり) をリストに取り込む。順番は既存の並びを保ち、新規は末尾に足す。
  //  - 既存: url / unread / lastEp を更新。読了済みでも lastEp が伸びていれば未読に戻す
  //  - 「後で読む」に置いた作品は、更新があっても未読扱いにしない (続きの URL と最終話だけ更新する)
  //  - 今回の取得に含まれない「更新由来」の項目は、他で読み終えたとみなして読了にする
  // 戻り値: {added: 新しくリストに入った数, updates: 未読のある作品の数, fresh: 前回から話が増えた作品の名前}
  //   fresh は裏での確認の通知に使う (同じ未読で何度も知らせないよう、増えたものだけ)
  // opts (どこまで見たか。今回無い作品を読了にしてよいかを決める):
  //   truncated = 一覧を最後まで見ていない (ページの上限で打ち切った)。そのときは、今回無い作品を読了にしない
  //   readIds = 見たページで未読が無かった作品の ID。打ち切ったときも、これらは読了にする
  //   cats = 見たカテゴリ (なろうで、確認するカテゴリを絞ったとき)。ほかのカテゴリの作品・カテゴリの分からない作品は読了にしない
  //   items の cat (カテゴリ) は作品に覚えておく
  NH.mergeUpdates = function (site, items, opts = {}) {
    const { truncated, readIds, cats } = opts;
    let added = 0;
    let updates = 0;
    let fresh = [];
    NH.update((d) => {
      added = 0; updates = 0; fresh = []; // NH.update がやり直したときのために、ここで数え直す
      const seen = new Set();
      items.forEach((u) => {
        const key = site + ':' + u.id;
        seen.add(key);
        const it = d.readLater.find((x) => x.key === key);
        // 行き先 (url) が変わったか、話が増えたときだけ urlAt を新しくする。毎回新しくすると、アプリで読んだ位置
        // (progress。urlAt より古いものは使わない) が、確認のたびに捨てられてサイトのしおりの位置へ戻ってしまう
        // ただし、アプリで読んだ位置がサイトの行き先と同じか先 (話番号で比べられるとき) なら、アプリの位置を残す
        // (サイトのしおりを更新していないと、新しい話が出るたびに、しおりの古い位置へ戻ってしまうため)
        // 比べるのは、サイトの行き先 (しおり・次の未読の話) と、アプリで「次に読む話」(読み終えていれば、その次)
        const siteEp = NH.epFromUrl(site, u.url);
        const appAhead = (x) => {
          const p = NH.progressOf(d, x);
          if (!p) return false;
          // サイトの行き先から話番号が読めない (カクヨムの続き・しおりの無い作品) ときは、位置の比べようがない。
          // 行き先が前回と同じなら、サイトのしおりは動いていないので、アプリの位置を残す (話が増えただけで捨てない)
          if (siteEp == null) return x.url === u.url;
          const n = NH.epNumber(p);
          return n != null && (p.done && p.next ? n + 1 : n) >= siteEp;
        };
        const moved = (x) => !appAhead(x) && (x.url !== u.url || (u.lastEp != null && (x.lastEp == null || u.lastEp > x.lastEp)));
        if (it && NH.kindOf(it) === 'later') {
          Object.assign(it, { url: u.url, lastEp: u.lastEp, urlAt: moved(it) ? Date.now() : it.urlAt, kind: 'later' });
          delete it.unread;
          return;
        }
        updates++;
        if (it) {
          // 最終話が増えた (最終話が分からないサイトでは未読が増えた) なら新着
          const grew = u.lastEp != null && it.lastEp != null ? u.lastEp > it.lastEp : (u.unread || 0) > (it.unread || 0);
          if (grew) fresh.push(it.title || u.title);
          // 読了にした作品でも、新しい話が来たら未読に戻す (最終話が分からないサイト = カクヨムで取れなかったときは、未読が増えたことで判断)
          if (it.done && (grew || u.lastEp > (it.lastEp || 0))) NH.setDone(it, false);
          Object.assign(it, { url: u.url, unread: u.unread, lastEp: u.lastEp, urlAt: moved(it) || (grew && !appAhead(it)) ? Date.now() : it.urlAt });
          if (u.cat != null) it.cat = u.cat;
          delete it.manualKind; // サイトの一覧に出たので、ふつうの更新の作品として扱う
        } else {
          d.readLater.push({ key, site, id: u.id, url: u.url, title: u.title, addedAt: Date.now(), done: false, kind: 'update', unread: u.unread, lastEp: u.lastEp, urlAt: Date.now(), ...(u.cat != null ? { cat: u.cat } : {}) });
          added++;
          fresh.push(u.title);
        }
      });
      const readKeys = new Set((readIds || []).map((id) => site + ':' + id));
      const scanned = (x) => !cats || (x.cat != null && cats.includes(x.cat));
      d.readLater.forEach((x) => {
        if (x.site !== site || x.kind !== 'update' || seen.has(x.key) || x.manualKind) return; // 手で移した作品は、サイトの一覧に無くて当然
        if (truncated ? readKeys.has(x.key) : scanned(x)) NH.setDone(x, true);
      });
    });
    return { added, updates, fresh };
  };

  // ---- 「後で読む」リストへの追加・削除 (一覧のボタン・作品ページのメニュー・ページ側の追加が共通で使う。NH.update の fn の中で呼ぶ) ----
  // 新しく「後で読む」に入れる項目。work = NH.parseWork の結果、status = 連載状態 (分かっていれば)。R18 はサイトが分かれば sub を足す
  NH.newLaterItem = (work, title, status) => NH.withSub({
    key: NH.workKey(work), site: work.site, id: work.id, url: work.url, title, addedAt: Date.now(), done: false, kind: 'later', ...(status ? { status } : {}),
  });
  // リストに足す。戻り値: 'revived' (読了だった作品の読了を外してリストに戻した) / 'exists' (すでにリストにある) / 'added' (新しく足した)
  NH.addLater = (d, work, title, status) => {
    const it = NH.findItem(d, work);
    if (it && it.done) { NH.setDone(it, false); return 'revived'; } // 読了の作品は、消さずに読了を外して戻す
    if (it) return 'exists';
    const item = NH.newLaterItem(work, title, status);
    // 履歴に覚えてある連載状態・全話数を引き継ぐ (履歴の「＋」・作品ページのメニュー・ランキングのボタンのどこから足しても同じ。ページから読めた状態が先)
    const h = (d.history || []).find((x) => x.key === item.key);
    if (h) {
      if (!item.status && h.status) item.status = h.status;
      if (h.total) { item.total = h.total; if (h.totalAt) item.totalAt = h.totalAt; } // 公式の履歴から入った全話数 (totalAt が無い) は仮の値。作品情報で読み直す
      if (h.r18 !== undefined) item.r18 = !!h.r18; // ハーメルンの R-18 (作品情報で調べた結果)。NSFW モードで隠すため
      // 読書位置が消えている (外したあとの足し直し・整理) 作品は、履歴に覚えた最後の位置 (話・読み具合) から戻す
      if (h.url && (h.ep || h.pr) && !(d.progress || {})[item.key]) { // 読み具合 (pr) を覚える前の履歴は、話だけ戻す
        const { no, pr } = NH.historyPos(h); // 別の話のときに覚えた番号・読み具合は使わない
        d.progress = d.progress || {};
        d.progress[item.key] = { url: h.url, ep: h.ep || '0', no: no || undefined, y: (pr && pr.y) || 0, done: !!(pr && pr.done), at: h.at || Date.now() };
      }
    }
    d.readLater.push(item);
    return 'added';
  };
  // リストから外す (読書位置も外す。リストの「削除」と同じ)。戻り値: 元に戻すための控え {item, at (元の位置), progress (読書位置)}
  NH.removeItem = (d, it) => {
    const removed = { item: it, at: d.readLater.indexOf(it), progress: (d.progress || {})[it.key] };
    d.readLater = d.readLater.filter((x) => x !== it);
    NH.dropProgress(d, [it.key]);
    return removed;
  };
  // NH.removeItem の控えから元に戻す (メモ・タグ・読了などもそのまま。すでにあれば何もしない)
  NH.restoreItem = (d, removed) => {
    if (d.readLater.some((x) => x.key === removed.item.key)) return;
    d.readLater.splice(Math.min(removed.at, d.readLater.length), 0, removed.item);
    if (removed.progress) { d.progress = d.progress || {}; if (!d.progress[removed.item.key]) d.progress[removed.item.key] = removed.progress; }
  };
  // 一覧のボタンを押したとき: リストにあって読了でなければ外し、無いか読了なら足す (読了なら読了を外して戻す)。
  // 戻り値: {result: 'removed' | 'revived' | 'added', removed: 元に戻すための控え (外したときだけ。無ければ null)}
  NH.toggleLater = (d, work, title, status) => {
    const it = NH.findItem(d, work);
    if (it && !it.done) return { result: 'removed', removed: NH.removeItem(d, it) };
    return { result: NH.addLater(d, work, title, status) === 'revived' ? 'revived' : 'added', removed: null };
  };
});
