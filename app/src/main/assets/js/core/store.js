// Yomikura 共通基盤 (2): 保存データ (読み込み・書き込み・整える・読書位置の整理)
window.NH.define('store', function (NH, T) { // T = 保存データの窓口に添える合言葉
  // ---- データ保存 ----
  // サイトごとに origin が違うので、localStorage ではなく Kotlin 側 (NHStore) に置く。
  // アプリ内ページ (リスト / 設定) もこのファイルを読み込んで同じ関数を使う。
  // データ形式:
  //   settings.menuItems: {項目: true/false}  ⋮ メニューに出す項目 (項目と初期値は Kotlin の MenuItems。未設定は初期値。診断だけ初期値オフ)
  //   readLater: [{key, site, id, url, title, addedAt, done, kind('update'|'later'), unread, lastEp, total, totalAt, totalTry, r18, urlAt, memo, tag, sub, status}]  (total = 全話数、作品ページを開いたときに覚える。totalAt は覚えた時刻、totalTry は全話数を読みにいった時刻 = 読めなかったときも 1 日は読み直さない)
  //              status: 連載状態 serial | complete | short (分かったときだけ。ランキング・検索の一覧と作品ページから。リストの絞り込み用)
  //              cat: なろうのブックマークのカテゴリ番号 (更新の確認で取り込んだ作品。カテゴリを絞って確認したとき、ほかのカテゴリの作品を読了にしないため)
  //              manualKind: 手で「未読の更新」へ移した作品 (更新の確認で、サイトの一覧に無くても読了にしない。サイトの一覧に出たら外す)
  //              sub: R18 (site narou18) のサイト noc | mnlt | mid (NH.r18Sub。分かったときだけ。history とオフラインの meta にも)
  //   logins:    {narou, hameln, kakuyomu: bool}   各サイトのログインの印 (Cookie) の有無。Kotlin が写す (NH.collectActive)
  //   progress:  {key: {url, ep, no, y, done, next, at}}   話ごとの読書位置。no = 何話目か (カクヨムなど話 ID が連番でないサイトだけ。目次の並びから数える。NH.epNumber)
  //   settings:  {theme, font, size, lh, vertical, offlineWifiOnly, dimRead, start, startSite, autoCheck, autoCheckHours, sites:{...}, history, rankings:{site:{url,label,spec|custom}}, statusFilter,
  //               historyMax (履歴に残す作品数。未設定は 300、0 = 無制限) / historyDays (履歴に残す日数。未設定・0 = 無期限),
  //               bgCheck (裏での確認、BgCheck.kt), bookmarkCats:{narou|narou18: [カテゴリ番号]}, r18Mode, siteNames, siteColors, tabsOneRow, tabsTinted,
  //               uiSize (アプリ内ページの操作部分の大きさ。compact | standard | large。未設定は standard),
  //               hideBarOnScroll (サイトのページを下へスクロールすると上部バーを隠す。未設定は true),
  //               showReload (上部バーの再読み込みボタン) / showLoadBar (読み込みの進み具合の線) / menuShare (旧。⋮ メニューの共有・URL コピー・ほかのブラウザをまとめて外す。いまは menuItems が未設定のときだけ見る) / heroResume (リストの上のカードを「さっきまで読んでいた作品」にする) / foldComments (話ページの下の感想・レビューの欄を畳む) / foldRecommend (おすすめ作品の欄を畳む) / foldLinks (作者マイページ・共有などのリンクを畳む) / foldReactions (ポイント・顔文字の応援を畳む) / foldRemember (畳んだ欄の開き具合をサイトごとに覚える。覚えた分は foldOpen = {サイト: {欄: true}}) = いずれも未設定は true,
  //               collectSites:{narou|narou18|hameln|kakuyomu: false} (更新を確認しないサイト。未設定は確認する),
  //               tabOrder:[サイトのキー] (上部バーのタブの並び。設定画面の ▲▼),
  //               adult (18 歳以上か。初回の画面で聞く。false なら R18 のサイトは sites でもオフにしてあり、選ぶ欄も出さない。未設定は true 扱い),
  //               nsfw (NSFW モード。オンなら R18 のサイトへ一切アクセスせず、リスト・履歴・ランキングにも R18 を出さない。未設定は false)}
  //   readSeq:{keys: [作品のキー], at} (リストで絞り込み・検索中に作品を開いたときの並び。読了の次の作品に使う。絞り込みなしで開いたら null)
  //   history, mute, rest:{site: 止められた時刻}, lastCollect, lastCollectFg (アプリの画面で確認した時刻), collectStartAt (確認を始めた時刻。Kotlin の CollectCooldown), collectLog:{site: {n, added, fresh} | {err}},
  //   bookmarkCatInfo:{narou|narou18: [{id, name, n}]} (ブックマークのカテゴリ名と件数。更新の確認のときに控える)
  const EMPTY = () => ({ readLater: [] });

  // strict = 書き込みのために読む。保存データを読めなかった (窓口が例外・中身が壊れている) ときは、空のデータを返さずに例外にする
  // (空のデータに手を加えて書くと、保存データ全体が消えてしまうため)。例外の raw は読めなかった中身
  const loadFresh = function (strict) {
    let raw = null;
    try {
      raw = window.NHStore ? window.NHStore.get(T) : localStorage.getItem('nh-data');
      return NH.sanitize(raw ? JSON.parse(raw) : EMPTY());
    } catch (e) {
      if (raw !== '') NH.unreadable = true; // 読めなかった (画面で知らせる。書き込みは NH.update が止める)
      if (strict && raw !== '') throw Object.assign(new Error('unreadable'), { raw: raw || '' });
      return EMPTY();
    }
  };

  // 保存データは大きくなるので、版 (NHStore.version) が変わらない間は読み込んだ物を使い回す (読むたびに全体を JSON.parse しない)。
  // 返す物は読み取り専用として扱う。書き換えるときは NH.update を使う (NH.update は毎回読み直す)
  // 開発用の検査: window.__NH_READONLY を立てておくと (テスト用)、使い回す物を書き換えようとした時点で例外にする
  const readonly = (x) => {
    if (!x || typeof x !== 'object') return x;
    const fail = () => { throw new Error('NH.load() の結果は書き換えられません (NH.update を使う)'); };
    return new Proxy(x, {
      get: (t, k) => readonly(t[k]),
      set: fail, deleteProperty: fail, defineProperty: fail, setPrototypeOf: fail,
    });
  };
  let cache = null;
  NH.load = function () {
    const s = window.NHStore;
    if (!s || !s.version) return loadFresh();
    const v = s.version();
    if (!cache || cache.v !== v) cache = { v, d: loadFresh() };
    return window.__NH_READONLY ? readonly(cache.d) : cache.d;
  };

  // 外から来たデータ (バックアップの読み込み・古い版) で型が違っても、画面が白くならないように整える。
  // 想定と違う項目は捨てる (作品の一覧は、中身が作品の形 = key が文字の物だけ残す)。
  const isObj = (NH.isObj = (x) => !!x && typeof x === 'object' && !Array.isArray(x)); // 統計 (stats.js) も使う
  const OBJECT_KEYS = ['settings', 'progress', 'mute', 'rest', 'collectLog', 'rankings', 'bookmarkCatInfo', 'statusFilter', 'readStats'];
  const ARRAY_KEYS = ['history']; // 閲覧履歴は配列 (オブジェクトの扱いにすると、読むたびに消えてしまう)
  NH.sanitize = function (d) {
    if (!isObj(d)) return EMPTY();
    if (!Array.isArray(d.readLater)) d.readLater = [];
    d.readLater = d.readLater.filter((e) => isObj(e) && typeof e.key === 'string');
    OBJECT_KEYS.forEach((k) => { if (k in d && !isObj(d[k])) delete d[k]; });
    ARRAY_KEYS.forEach((k) => { if (k in d && !Array.isArray(d[k])) delete d[k]; });
    // 中の形もそろえる (手で直したバックアップや古い版のデータで、配列のはずの所が文字列などだと、ミュートや履歴の並べ替えが例外で止まる)
    if (d.history) {
      d.history = d.history.filter((e) => isObj(e) && typeof e.key === 'string');
      d.history.forEach((e) => { if (typeof e.at !== 'number' || !isFinite(e.at)) e.at = 0; });
    }
    const strings = (a) => (Array.isArray(a) ? a.filter((x) => typeof x === 'string') : []);
    if (d.mute) {
      ['authors', 'words'].forEach((k) => { if (k in d.mute) d.mute[k] = strings(d.mute[k]); });
      if ('block' in d.mute) {
        const b = isObj(d.mute.block) ? d.mute.block : {};
        ['authors', 'words'].forEach((k) => { if (k in b) b[k] = strings(b[k]); });
        if ('works' in b) b.works = Array.isArray(b.works) ? b.works.filter((w) => isObj(w) && typeof w.key === 'string') : [];
        d.mute.block = b;
      }
    }
    return d;
  };

  NH.save = function (d) {
    const raw = JSON.stringify(d);
    if (window.NHStore) window.NHStore.set(raw, T);
    else localStorage.setItem('nh-data', raw);
  };

  // 読み込み → fn で変更 → 保存。画面と画面外の巡回 (Collector) は同時に書くことがあるので、
  // 読んだときの版のままなら書き (setIf)、ほかが先に書いていたら読み直して fn をやり直す (片方の書き込みが消えないように)。
  // fn は何度か呼ばれることがあるので、fn の外の変数に数を足していくような書き方はしない (fn の中で数え直す)
  // opts.force = 保存データが読めなくても書く (バックアップの読み込みなど、置き換えるとき)。読めなかった中身は unreadableCopy に残す。
  // force でなければ、読めないときは何も書かない (NH.unreadable を立てる)
  // 版付きの書き込み (setIf) が 8 回とも通らなかったら、書かずにあきらめる。版を見ずに書き直すと、断られて空として読んだデータ
  // (信頼の決まる前のページは get が '' を返す) で、保存データ全体を上書きしてしまうことがあるため
  NH.unreadable = false;
  NH.update = function (fn, opts = {}) {
    const fresh = () => {
      try { return loadFresh(true); } catch (e) {
        NH.unreadable = true;
        if (!opts.force) return null;
        const d = EMPTY();
        if (e.raw) d.unreadableCopy = e.raw;
        return d;
      }
    };
    const s = window.NHStore;
    if (s && s.setIf) {
      for (let i = 0; i < 8; i++) {
        const v = s.version();
        const d = fresh();
        if (!d) return loadFresh();
        fn(d);
        if (s.setIf(JSON.stringify(d), v, T)) return d;
      }
      return loadFresh();
    }
    const d = fresh(); // 版付きの書き込みが無い窓口 (localStorage・古い版) だけ
    if (!d) return loadFresh();
    fn(d);
    NH.save(d);
    return d;
  };

  // 読書位置 (d.progress) は、リストに入っている作品 (読了を除く) と、それ以外で最近読んだ PROGRESS_KEEP 作品だけ残す
  NH.PROGRESS_KEEP = 100; // 履歴の「途・未」や話数の判定にも使うので、少なすぎると読んだ作品が「未」に戻る
  NH.pruneProgress = function (d) {
    const p = d.progress;
    if (!p) return;
    const inList = new Set(d.readLater.filter((x) => !x.done).map((x) => x.key));
    const others = Object.keys(p).filter((k) => !inList.has(k)).sort((a, b) => (p[b].at || 0) - (p[a].at || 0));
    others.slice(NH.PROGRESS_KEEP).forEach((k) => delete p[k]);
  };

  // 設定の 1 項目を書く (設定画面の外 = リスト・オフライン・サイトのページの画面から。設定画面は S を通して書く)
  NH.setSetting = (k, v) => NH.update((d) => { d.settings = d.settings || {}; d.settings[k] = v; });

  // 設定のオン / オフの項目の読み取り。未設定 (undefined / null) は def (既定はオン)。既定オンの項目は false と書かれたときだけオフ (early.js の `!== false` と同じ)、既定オフの項目は真のときだけオン。
  // 設定の画面側は NH.flagOf(S, 'キー')、保存データ d (settings を持つ) からは NH.flag(d, 'キー')。既定オフの項目は def に false を渡す
  NH.flagOf = (st, key, def = true) => { const v = st ? st[key] : undefined; return v === undefined || v === null ? def : def ? v !== false : !!v; };
  NH.flag = (d, key, def = true) => NH.flagOf(d && d.settings, key, def);
});
