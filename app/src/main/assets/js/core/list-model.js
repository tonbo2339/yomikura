// Yomikura 共通基盤 (8): 「後で読む」リストの作品の扱い・更新の取り込み・次の小説
window.NH.define('list-model', function (NH) {
  NH.need('store');
  NH.need('nsfw');
  NH.need('read-state');
  NH.need('siteinfo'); // NH.withSub
  // リストの種類: 更新取得で入ったもの = 'update' (未読の更新)、それ以外 = 'later' (後で読む)
  NH.kindOf = (x) => (x.kind === 'update' ? 'update' : 'later');

  // 読み終えた作品は、リストから外す (NH.removeItem)。読んだ記録は履歴 (d.history) が持つ。作品に「読了」の印は付けない
  // (以前は done / doneAt の印を付けて隠していた。古いデータの印の付いた作品は、store.js の読み込みで外す)

  // リストから外した作品の、話ごとの読書位置の記録も消す。ただし、お気に入りの作品の位置は消さない (core/favorites.js の仕様。NH.favProtected)
  NH.dropProgress = (d, keys) => {
    if (!d.progress) return;
    const keep = new Set(NH.favProtected ? NH.favProtected(d) : []);
    keys.forEach((k) => { if (!keep.has(k)) delete d.progress[k]; });
  };

  // 作品のジャンル・タグ (x.tag に、スペース・読点・カンマで区切って複数入る。以前の 1 つだけの値もそのまま読める)。重複は除く
  NH.tagsOf = (x) => [...new Set(String((x && x.tag) || '').split(/[\s,、，]+/).filter(Boolean))];

  NH.workKey = (w) => w.site + ':' + w.id;
  NH.itemByKey = (d, key) => d.readLater.find((x) => x.key === key); // キーからリストの作品 (無ければ undefined)
  NH.findItem = (d, w) => NH.itemByKey(d, NH.workKey(w));

  // 絞り込み・検索したリストの順で読み進める (readSeq = {keys: [作品のキー], at})。
  // リストで絞り込み中に作品を開いたとき、リストが並べた順を控える。1 日以上前の並びは使わない。
  NH.READ_SEQ_TTL = NH.DAY_MS;
  NH.setReadSeq = (d, keys) => { d.readSeq = keys ? { keys, at: Date.now() } : null; };
  // 今の作品が控えた並びに入っていれば、その後ろで読んでいない作品の列 (順番どおり)。入っていなければ null
  NH.seqRest = function (d, it) {
    const s = d.readSeq;
    if (!it || !s || !Array.isArray(s.keys) || !(Date.now() - s.at <= NH.READ_SEQ_TTL)) return null;
    const i = s.keys.indexOf(it.key);
    if (i < 0) return null;
    return s.keys.slice(i + 1).map((k) => NH.itemByKey(d, k)).filter((x) => x && !NH.nsfwHides(d, x));
  };

  // 読み終えたあとに開く「次の小説」。絞り込みの並び (seqRest) があればその続き、無ければ同じ種類 (未読 / 後で読む) の先頭、それも無ければ残り全体。
  // 戻り値: {seq: 並びの続きか (並びの最後なら pool が空でも true), pool: 候補, next: 次の作品 | undefined, remaining: 残りの作品数}
  NH.nextPool = function (d, it) {
    const seq = NH.seqRest(d, it);
    const active = d.readLater.filter((x) => x !== it && !NH.nsfwHides(d, x)); // NSFW モードで隠れている作品は、次に選ばない
    const same = active.filter((x) => NH.kindOf(x) === NH.kindOf(it));
    const pool = seq || (same.length ? same : active);
    return { seq: !!seq, pool, next: pool[0], remaining: pool.length };
  };
  // 取得した更新 (未読あり) をリストに取り込む。順番は既存の並びを保ち、新規は末尾に足す。
  //  - 既存の作品: url / unread / lastEp を更新
  //  - 「後で読む」に置いた作品は、更新があっても未読扱いにしない (続きの URL と最終話だけ更新する)
  //  - リストに無い作品 (読み終えて外した作品を含む): サイトに未読があれば、新規の「更新」として入れる。判断は「後で読む」に入っているかだけ
  //  - 今回の取得に無い作品は、何も変えない (リストから消さない・「後で読む」へ移さない・未読の数も変えない)。
  //    作品をリストから消すのは、使う人が「リストから削除」を明示してしたときだけ (更新の確認は、作品を消さない)
  // 戻り値: {added: 新しくリストに入った数, updates: 未読のある作品の数, fresh: 前回から話が増えた作品の名前, freshKeys: そのキー, trace: 作品ごとの判定}
  //   fresh は裏での確認の通知に使う (同じ未読で何度も知らせないよう、増えたものだけ)
  //   items の cat (カテゴリ) は作品に覚えておく
  NH.mergeUpdates = function (site, items) {
    let added = 0;
    let updates = 0;
    let fresh = [];
    let freshKeys = []; // fresh と同じ並びの作品のキー (保存済みの作品の自動更新が使う)
    const counted = new Set(); // updates に数えた作品のキー (NH.update のやり直しごとに空にする)
    let trace = []; // 作品ごとの取り込みの判定 (開発用ログに残す。戻り値の trace)
    NH.update((d) => {
      added = 0; updates = 0; fresh = []; freshKeys = []; trace = []; counted.clear(); // NH.update がやり直したときのために、ここで数え直す
      items.forEach((u) => {
        const key = NH.workKey({ site, id: u.id });
        const it = NH.itemByKey(d, key);
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
        // 最終話が取れなかった回 (カクヨムは、取れないことがある) は、覚えている最終話を空で上書きしない。
        // 空にすると、次に取れたとき「話が増えた」と取り違えて、読み終えた作品が未読に戻ってしまう
        const lastEpOf = (x) => (u.lastEp != null ? u.lastEp : x.lastEp);
        // 「後で読む」の作品は、更新に数えない
        if (it && NH.kindOf(it) === 'later') {
          Object.assign(it, { url: u.url, lastEp: lastEpOf(it), urlAt: moved(it) ? Date.now() : it.urlAt, kind: 'later' });
          delete it.unread;
          trace.push(key + ' later (後で読む。更新に数えない)');
          return;
        }
        if (!counted.has(key)) { counted.add(key); updates++; } // 同じ作品が 1 回の取得に 2 度あっても 1 つに数える
        if (it) {
          // 最終話が増えた (最終話が分からないサイトでは未読が増えた) なら新着
          const grew = u.lastEp != null && it.lastEp != null ? u.lastEp > it.lastEp : (u.unread || 0) > (it.unread || 0);
          if (grew) { fresh.push(it.title || u.title); freshKeys.push(key); }
          const p = NH.progressOf(d, it);
          trace.push(key + ' exist grew=' + (grew ? 1 : 0) + ' pos=' + (p ? (p.ep || '?') + (p.done ? 'd' : '') : '-')
            + ' lastEp=' + (it.lastEp == null ? '-' : it.lastEp) + '>' + (u.lastEp == null ? '-' : u.lastEp) + ' unread=' + (u.unread == null ? '-' : u.unread));
          Object.assign(it, { url: u.url, unread: u.unread, lastEp: lastEpOf(it), urlAt: moved(it) || (grew && !appAhead(it)) ? Date.now() : it.urlAt });
          if (u.cat != null) it.cat = u.cat;
          delete it.manualKind; // サイトの一覧に出たので、ふつうの更新の作品として扱う
        } else {
          const item = { key, site, id: u.id, url: u.url, title: u.title, addedAt: Date.now(), kind: 'update', unread: u.unread, lastEp: u.lastEp, urlAt: Date.now(), ...(u.cat != null ? { cat: u.cat } : {}) };
          const h = NH.inheritFromHistory(d, item); // 読み終えて外したあとの再入場でも、R18 の印 (NSFW モードで隠すため)・連載状態・全話数を失わない
          if (item.r18 === undefined && (NH.parseUrl(u.url) || {}).hostname === 'h.syosetu.org') item.r18 = true; // ハーメルンの R-18 は h.syosetu.org にある
          d.readLater.push(item);
          added++;
          // 新着の通知に数えるのは、話が増えたとき。履歴に覚えた全話数より増えていない作品 (読み終えて外したが、サイトのしおりが古いまま未読が残る作品など) は、更新には入れるが、確認のたびに新着として通知しない
          const known = !!(h && h.total && u.lastEp != null && h.total >= u.lastEp);
          if (!known) { fresh.push(u.title); freshKeys.push(key); }
          trace.push(key + ' new unread=' + (u.unread == null ? '-' : u.unread) + ' lastEp=' + (u.lastEp == null ? '-' : u.lastEp));
        }
      });
    });
    return { added, updates, fresh, freshKeys, trace };
  };

  // ---- 「後で読む」リストへの追加・削除 (一覧のボタン・作品ページのメニュー・ページ側の追加が共通で使う。NH.update の fn の中で呼ぶ) ----
  // 新しく「後で読む」に入れる項目。work = NH.parseWork の結果、status = 連載状態 (分かっていれば)。R18 はサイトが分かれば sub を足す
  NH.newLaterItem = (work, title, status) => NH.withSub({
    key: NH.workKey(work), site: work.site, id: work.id, url: work.url, title, addedAt: Date.now(), kind: 'later', ...(status ? { status } : {}),
  });
  // 「後で読む」に入っているか (「後で読む」の作品)。「更新」の作品は含めない (「後で読む」に移す対象)
  NH.inLater = (d, work) => { const it = NH.findItem(d, work); return !!it && NH.kindOf(it) === 'later'; };
  // 履歴に覚えてある連載状態・全話数・R18 の印・掲載サイトを、項目 (item) に引き継ぐ (項目に先にある値は変えない)。履歴の作品 (無ければ undefined) を返す。
  // リストに足すとき (addLater)・更新の確認が作品を入れるとき (mergeUpdates) が共通で使う
  NH.inheritFromHistory = (d, item) => {
    const h = (d.history || []).find((x) => x.key === item.key);
    if (!h) return undefined;
    if (!item.status && h.status) item.status = h.status;
    if (h.total) { item.total = h.total; if (h.totalAt) item.totalAt = h.totalAt; } // 公式の履歴から入った全話数 (totalAt が無い) は仮の値。作品情報で読み直す
    if (h.r18 !== undefined) item.r18 = !!h.r18; // ハーメルンの R-18 (作品情報で調べた結果)。NSFW モードで隠すため
    if (h.sub && !item.sub) item.sub = h.sub; // R18 の掲載サイト (ノクターン・ムーンライト・ミッドナイト) も引き継ぐ
    return h;
  };
  // 「後で読む」に足す。戻り値: 'moved' (「更新」の作品を「後で読む」に移した) / 'exists' (すでに「後で読む」にある) / 'added' (新しく足した)
  NH.addLater = (d, work, title, status) => {
    const it = NH.findItem(d, work);
    if (it && NH.kindOf(it) === 'update') { it.kind = 'later'; delete it.manualKind; return 'moved'; } // 外さずに、タブだけ移す (メモ・読書位置はそのまま)
    if (it) return 'exists';
    const item = NH.newLaterItem(work, title, status);
    // 履歴に覚えてある連載状態・全話数を引き継ぐ (履歴の「＋」・作品ページのメニュー・ランキングのボタンのどこから足しても同じ。ページから読めた状態が先)
    const h = NH.inheritFromHistory(d, item);
    if (h) {
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
  // NH.removeItem の控えから元に戻す (メモ・タグなどもそのまま。すでにあれば何もしない)
  NH.restoreItem = (d, removed) => {
    if (d.readLater.some((x) => x.key === removed.item.key)) return;
    d.readLater.splice(Math.max(0, Math.min(removed.at, d.readLater.length)), 0, removed.item); // at = -1 (外す前から無かった) でも、末尾の 1 つ手前に入れない
    if (removed.progress) { d.progress = d.progress || {}; if (!d.progress[removed.item.key]) d.progress[removed.item.key] = removed.progress; }
  };
  // 一覧のボタンを押したとき: 「後で読む」にあれば外し、無ければ足す (「更新」の作品は「後で読む」に移す)。
  // 戻り値: {result: 'removed' | 'moved' | 'added', removed: 元に戻すための控え (外したときだけ。無ければ null)}
  NH.toggleLater = (d, work, title, status) => {
    const it = NH.findItem(d, work);
    if (it && NH.kindOf(it) === 'later') return { result: 'removed', removed: NH.removeItem(d, it) };
    const r = NH.addLater(d, work, title, status);
    return { result: r === 'moved' ? r : 'added', removed: null };
  };
});
