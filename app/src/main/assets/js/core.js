// Yomikura 共通基盤: アダプター登録 / データ保存
(function () {
  // 保存データの窓口に添える合言葉 (NHData.token)。アプリがページ本体にだけ入れる (iframe には無い)。読んだらページから消す
  const fresh = window.__nhT;
  try { delete window.__nhT; } catch (e) { /* 消せなくても動く */ }
  if (window.NH) return; // 二重注入防止
  const NH = (window.NH = { adapters: [] });
  const T = fresh || '';

  NH.LIST_URL = 'https://yomikura.local/list.html';
  NH.HISTORY_URL = 'https://yomikura.local/list.html#history';
  NH.SETTINGS_URL = 'https://yomikura.local/settings.html';

  // 同じ id は一度だけ (onPageFinished が 1 ページで複数回来ても重複させない)
  NH.register = function (adapter) {
    if (!NH.adapters.some((a) => a.id === adapter.id)) NH.adapters.push(adapter);
  };

  // 現在のページに対応するアダプター (なければ null)
  NH.adapter = function () {
    return NH.adapters.find((a) => a.match(location.hostname, location.pathname)) || null;
  };

  // 文字列を URL にする (現在のページ基準。読めなければ null)。アダプターの parseWork が共通で使う
  NH.parseUrl = function (url) {
    try { return new URL(url, location.href); } catch (e) { return null; }
  };

  // URL から {site, id, url, ...} を得る (どのアダプターにも該当しなければ null)
  NH.parseWork = function (url) {
    for (const a of NH.adapters) {
      const w = a.parseWork(url);
      if (w) return Object.assign({ site: a.id }, w);
    }
    return null;
  };

  // ---- データ保存 ----
  // サイトごとに origin が違うので、localStorage ではなく Kotlin 側 (NHStore) に置く。
  // アプリ内ページ (リスト / 設定) もこのファイルを読み込んで同じ関数を使う。
  // データ形式:
  //   settings.menuItems: {項目: true/false}  ⋮ メニューに出す項目 (項目と初期値は Kotlin の MenuItems。未設定は初期値。診断だけ初期値オフ)
  //   readLater: [{key, site, id, url, title, addedAt, done, kind('update'|'later'), unread, lastEp, total, totalAt, totalTry, r18, urlAt, memo, tag, sub, status}]  (total = 全話数、作品ページを開いたときに覚える。totalAt は覚えた時刻、totalTry は全話数を読みにいった時刻 = 読めなかったときも 1 日は読み直さない)
  //              status: 連載状態 serial | complete | short (分かったときだけ。ランキング・検索の一覧と作品ページから。リストの絞り込み用)
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
  //   history, mute, rest:{site: 止められた時刻}, lastCollect, lastCollectFg (アプリの画面で確認した時刻), collectLog:{site: {n, added, fresh} | {err}},
  //   bookmarkCatInfo:{narou|narou18: [{id, name, n}]} (ブックマークのカテゴリ名と件数。更新の確認のときに控える)
  const EMPTY = () => ({ readLater: [] });

  const loadFresh = function () {
    try {
      const raw = window.NHStore ? window.NHStore.get(T) : localStorage.getItem('nh-data');
      return NH.sanitize(raw ? JSON.parse(raw) : EMPTY());
    } catch (e) {
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
  const isObj = (x) => !!x && typeof x === 'object' && !Array.isArray(x);
  const OBJECT_KEYS = ['settings', 'progress', 'mute', 'rest', 'collectLog', 'rankings', 'bookmarkCatInfo', 'statusFilter', 'readStats'];
  const ARRAY_KEYS = ['history']; // 閲覧履歴は配列 (オブジェクトの扱いにすると、読むたびに消えてしまう)
  NH.sanitize = function (d) {
    if (!isObj(d)) return EMPTY();
    if (!Array.isArray(d.readLater)) d.readLater = [];
    d.readLater = d.readLater.filter((e) => isObj(e) && typeof e.key === 'string');
    OBJECT_KEYS.forEach((k) => { if (k in d && !isObj(d[k])) delete d[k]; });
    ARRAY_KEYS.forEach((k) => { if (k in d && !Array.isArray(d[k])) delete d[k]; });
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
  NH.update = function (fn) {
    const s = window.NHStore;
    if (s && s.setIf) {
      for (let i = 0; i < 8; i++) {
        const v = s.version();
        const d = loadFresh();
        fn(d);
        if (s.setIf(JSON.stringify(d), v, T)) return d;
      }
    }
    const d = loadFresh();
    fn(d);
    NH.save(d);
    return d;
  };

  // 読書位置 (d.progress) は、リストに入っている作品 (読了を除く) と、それ以外で最近読んだ PROGRESS_KEEP 作品だけ残す
  NH.PROGRESS_KEEP = 10;
  NH.pruneProgress = function (d) {
    const p = d.progress;
    if (!p) return;
    const inList = new Set(d.readLater.filter((x) => !x.done).map((x) => x.key));
    const others = Object.keys(p).filter((k) => !inList.has(k)).sort((a, b) => (p[b].at || 0) - (p[a].at || 0));
    others.slice(NH.PROGRESS_KEEP).forEach((k) => delete p[k]);
  };

  // ---- R18 のサイト (ノクターン / ムーンライト / ミッドナイト) ----
  // 作品の URL (novel18.syosetu.com) はどれも同じなので、開いているページから判断する:
  // ランキング・検索ならホスト、作品ページならヘッダーのサイトへのリンク (c-follow-header__item)。分からなければ ''
  // サイトの一覧 (名前・R18 の掲載サイト・表示の既定) の唯一の定義。HTML 側 (list / settings など) もここから引く:
  //   NH.SITE_DEFS      [{key, name, sub, on}] 表示順。sub = R18 (narou18) の掲載サイトか、on = 設定 sites の既定 (undefined = 既定なし)
  //   NH.SITE_NAMES     {key: 名前} 本体のサイト (narou / narou18 / hameln / kakuyomu)
  //   NH.SUB_NAMES      {key: 名前} R18 の掲載サイト (noc / mnlt / mid)
  //   NH.SITE_DEFAULT   {key: bool} 表示の既定 (本体のサイトと掲載サイト。narou18 は掲載サイトの有無で決まるので持たない)
  //   NH.COLLECT_IDS    更新を確認するサイトのキー (本体のサイト、表示順 = 巡回の順)
  //   NH.R18_SITES      掲載サイトのキー
  NH.SITE_DEFS = [
    { key: 'narou', name: 'なろう', on: true },
    { key: 'narou18', name: 'R18' },
    { key: 'hameln', name: 'ハーメルン', on: true },
    { key: 'kakuyomu', name: 'カクヨム', on: true },
    { key: 'noc', name: 'ノクターン', sub: true, on: true },
    { key: 'mnlt', name: 'ムーンライト', sub: true, on: true },
    { key: 'mid', name: 'ミッドナイト', sub: true, on: true },
  ];
  const namesOf = (sub) => NH.SITE_DEFS.filter((x) => !!x.sub === sub).reduce((o, x) => ((o[x.key] = x.name), o), {});
  NH.SUB_NAMES = namesOf(true);
  const SUB_HOST = /^(noc|mnlt|mid)\.syosetu\.com$/;
  const subOfUrl = (href) => { try { return (new URL(href).hostname.match(SUB_HOST) || [])[1] || ''; } catch (e) { return ''; } };
  NH.r18Sub = function () {
    const here = subOfUrl(location.href);
    if (here || location.hostname !== 'novel18.syosetu.com') return here;
    const links = [...document.querySelectorAll('a.c-follow-header__item'), ...document.querySelectorAll('a[href*=".syosetu.com"]')];
    for (const a of links) { const s = subOfUrl(a.href); if (s) return s; }
    return '';
  };
  // リストや履歴に出すサイト名 (R18 はサイトが分かっていればその名前)。
  // 上部バーのタブに付けた名前 (settings.siteNames、MainActivity.tabName と同じく 12 文字まで) があればそれを使う。
  // 一覧では作品の数だけ呼ばれるので、設定は 1 秒だけ覚えておく
  NH.SITE_NAMES = namesOf(false);
  let tabNames = null;
  let tabNamesAt = 0;
  const tabName = (key) => {
    if (!tabNames || Date.now() - tabNamesAt > 1000) {
      const d = NH.load();
      tabNames = (d.settings && d.settings.siteNames) || {};
      tabNamesAt = Date.now();
    }
    return String(tabNames[key] || '').trim().slice(0, 12);
  };
  // 絞り込みなどで使うサイトのキー: R18 はサイトが分かっていれば noc / mnlt / mid、分からなければ narou18
  NH.siteKey = (x) => (x.site === 'narou18' && NH.SUB_NAMES[x.sub] ? x.sub : x.site);
  // x は項目 ({site, sub}) か、キーだけ ({site: 'noc'} など)
  NH.siteLabel = (x) => {
    const key = NH.siteKey(x);
    return tabName(key) || NH.SUB_NAMES[key] || NH.SITE_NAMES[key] || key;
  };
  // R18 の作品の項目に、今のページから分かるサイトを書き足す (分からなければそのまま)
  NH.withSub = (obj) => {
    const s = obj.site === 'narou18' ? NH.r18Sub() : '';
    if (s) obj.sub = s;
    return obj;
  };

  // ---- オフライン保存 (本体は Kotlin の OfflineStore。端末の内部ストレージに作品ごとのフォルダで置く) ----
  //   meta:    {site, id, title, url, savedAt, updatedAt, total, eps: [{ep, title, url}]}  保存した話の並び
  //   episode: {ep, title, url, html, at}  本文は安全な要素 (p, br, ruby など) だけにしてある
  // 書き込み (putEpisode / putMeta) は保存を進めるサイト上から、読み出し・一覧・削除はアプリ内ページからだけ使える。
  const json = (fn, fallback) => { try { const r = fn(); return r ? JSON.parse(r) : fallback; } catch (e) { return fallback; } };
  const off = () => window.NHOffline || null;
  NH.off = {
    available: () => !!off(),
    // 挿絵を端末に取って保存する (アプリが取る)。結果 {name, bytes} / {error: 'challenge'} / {} (取れなかった)
    saveImage: (site, id, url, referer) => json(() => off() && off().saveImage(site, id, url, referer, T), {}),
    putEpisode: (site, id, ep, obj) => !!off() && off().putEpisode(site, id, String(ep), JSON.stringify(obj), T),
    putMeta: (site, id, obj) => !!off() && off().putMeta(site, id, JSON.stringify(obj), T),
    meta: (site, id) => json(() => off() && off().meta(site, id, T), null),
    episode: (site, id, ep) => json(() => off() && off().episode(site, id, String(ep)), null),
    list: () => json(() => off() && off().list(), []),
    readerBar: (hide) => { try { off() && off().readerBar(!!hide); } catch (e) {} }, // 読書画面で上部バーを隠す / 出す
    remove: (site, id) => !!off() && off().delete(site, id),
    removeAll: () => !!off() && off().deleteAll(),
    onUnmetered: () => { try { return !off() || off().onUnmetered(); } catch (e) { return true; } },
    // 保存キュー (端末の待ち行列)。追加は保存を進めるサイト上から、中身の読み出し・並べ替え・削除はアプリ内ページからだけ
    queueAdd: (job) => (off() && off().queueAdd(JSON.stringify(job), T)) || 0,
    queueList: () => json(() => off() && off().queueList(), []),
    queueMove: (key, delta) => !!off() && off().queueMove(key, delta),
    queueRemove: (key) => !!off() && off().queueRemove(key),
    queueClearFinished: () => !!off() && off().queueClearFinished(),
    queueLive: () => json(() => off() && off().queueLive(), {}), // 保存中の作品の進み {running, key, title, ok, total, paused, waitingWifi}
    queueControl: (action) => !!off() && off().queueControl(action), // 'pause' | 'resume' | 'cancel'
    queueRetry: (key) => !!off() && off().queueRetry(key), // 止まった・確認が必要だった作品を、もう一度始める
    queueStart: () => !!off() && off().queueStart(T), // 前景サービスで保存キューを進める (作品のページから)
  };
  // 本文は安全な要素だけにして保存する・表示する (属性・スクリプト・画像は落とす)。残すのは段落・改行・ルビ・強調。
  // 保存したあとのデータをアプリ内ページで表示するときにも、もう一度これを通す (サイト側から書き込まれた物を実行しないため)
  const KEEP = new Set(['P', 'BR', 'HR', 'RUBY', 'RT', 'RP', 'RB', 'B', 'STRONG', 'I', 'EM']);
  const SKIP = new Set(['SCRIPT', 'STYLE', 'IFRAME', 'INS', 'NOSCRIPT', 'IMG', 'SVG', 'FORM', 'BUTTON', 'AUDIO', 'VIDEO']);
  const esc = (t) => t.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  // 挿絵は、端末に保存した物 (img/<名前>) だけ残す。サイトの画像の URL は残さない (保存のときに data-nh-local を付けてある)
  const IMG_LOCAL = /^img\/i[0-9a-f]{8}\.(jpg|png|gif|webp)$/;
  NH.cleanNode = function clean(node) {
    let out = '';
    node.childNodes.forEach((n) => {
      if (n.nodeType === 3) { out += esc(n.nodeValue); return; }
      if (n.nodeType !== 1) return;
      const t = n.tagName.toUpperCase();
      if (t === 'IMG') {
        const local = n.getAttribute('data-nh-local') || n.getAttribute('src') || '';
        if (IMG_LOCAL.test(local)) out += '<img src="' + local + '" alt="">';
        return;
      }
      if (SKIP.has(t)) return;
      const tag = t.toLowerCase();
      if (t === 'BR' || t === 'HR') out += '<' + tag + '>';
      else if (KEEP.has(t)) out += '<' + tag + '>' + clean(n) + '</' + tag + '>';
      else out += clean(n); // div / span / a などは中身だけ残す
    });
    return out;
  };
  NH.cleanHtml = (html) => NH.cleanNode(new DOMParser().parseFromString(String(html || ''), 'text/html').body);

  // 「後で読む」の作品のキー (site:id) から、保存のキーへ
  NH.splitKey = (key) => { const i = String(key).indexOf(':'); return { site: key.slice(0, i), id: key.slice(i + 1) }; };
  NH.fmtBytes = (n) => (n >= 1048576 ? (n / 1048576).toFixed(1) + ' MB' : n > 0 ? Math.max(1, Math.round(n / 1024)) + ' KB' : '0 KB');
  NH.OFFLINE_URL = 'https://yomikura.local/offline.html';
  NH.QUEUE_URL = 'https://yomikura.local/queue.html'; // 保存キューの画面
  // 保存キューの作品が、まだ終わっていない (順番に進める) か。Kotlin の SaveQueue.Status.active と同じ
  NH.queueActive = (j) => ['WAITING', 'RUNNING', 'WAIT_WIFI', 'PAUSED'].includes(j.status);

  // 設定: 表示するサイトの有効判定 (初期は全部表示。18 歳未満を選ぶと R18 はオフで保存される)
  // (MainActivity.siteOn と同じ既定値)
  const SITE_DEFAULT = (NH.SITE_DEFAULT = NH.SITE_DEFS.filter((x) => x.on !== undefined).reduce((o, x) => ((o[x.key] = x.on), o), {}));
  NH.R18_SITES = Object.keys(NH.SUB_NAMES);
  NH.siteOn = (d, key) => {
    const s = (d.settings && d.settings.sites) || {};
    return s[key] === undefined ? SITE_DEFAULT[key] : !!s[key];
  };
  // なろう / R18 のブックマークで更新を確認するカテゴリ (1〜10、settings.bookmarkCats[id])。未設定ならカテゴリ1だけ。
  // 'all' は作品の入っているカテゴリ全部 (カテゴリの件数を控えてあればそれ、まだなら 1〜10 全部)
  NH.bookmarkCats = (d, id) => {
    const v = ((d.settings || {}).bookmarkCats || {})[id];
    if (v === 'all') {
      const info = ((d.bookmarkCatInfo || {})[id] || []).filter((x) => x.n > 0).map((x) => x.id);
      return info.length ? info : [1, 2, 3, 4, 5, 6, 7, 8, 9, 10];
    }
    const c = (Array.isArray(v) ? v : []).map(Number).filter((n) => n >= 1 && n <= 10);
    return c.length ? [...new Set(c)].sort((a, b) => a - b) : [1];
  };
  // 更新確認の対象にするか: 表示するサイトで、かつ settings.collectSites[id] がオフでない (R18 は 3 サイトのどれかが有効なら)。
  // collectSites のキーは narou / narou18 / hameln / kakuyomu。未設定は対象 (MainActivity.collectOn と同じ)
  NH.COLLECT_IDS = Object.keys(NH.SITE_NAMES);
  // NSFW モード (settings.nsfw): オンの間は、R18 のサイトへ一切アクセスせず (Kotlin 側で遮断)、リスト・履歴・ランキングにも R18 の作品を出さない。
  // R18 の作品 = narou18 の作品、または r18 の印のある作品 (ハーメルンの R-18 タグ)。履歴は同じキーのリストの作品の印も見る
  // 設定に無いときの既定は版で決まる (window.NH_EDITION.r18OptIn。Kotlin の NHData.nsfwOn と同じ)
  NH.R18_OPT_IN = !!(window.NH_EDITION || {}).r18OptIn;
  // 設定の書き出し・読み込みの形式名 (JSON の app。Kotlin の Edition.BACKUP_APP)。書き出しのファイル名の頭にも使う
  NH.BACKUP_APP = (window.NH_EDITION || {}).backupApp || 'yomikura';
  // 読み込みで受け付ける形式名 (Kotlin の Edition.BACKUP_ACCEPTS)。版によっては、ほかの版で書き出したファイルも読める
  NH.BACKUP_APPS = (window.NH_EDITION || {}).backupApps || [NH.BACKUP_APP];
  NH.isBackupApp = (name) => NH.BACKUP_APPS.includes(name);
  NH.nsfwOn = (d) => { const v = d && d.settings && d.settings.nsfw; return v === undefined || v === null ? NH.R18_OPT_IN : !!v; };
  NH.isR18Item = (d, it) => it.site === 'narou18' || !!it.r18 || !!((d.readLater || []).find((x) => x.key === it.key) || {}).r18;
  NH.nsfwHides = (d, it) => NH.nsfwOn(d) && NH.isR18Item(d, it);
  NH.collectOn = (d, id) => {
    if (id === 'narou18' && NH.nsfwOn(d)) return false;
    const shown = id === 'narou18' ? NH.R18_SITES.some((k) => NH.siteOn(d, k)) : NH.siteOn(d, id);
    return shown && ((d.settings || {}).collectSites || {})[id] !== false;
  };
  NH.collectAny = (d) => NH.COLLECT_IDS.some((id) => NH.collectOn(d, id));
  // 裏での確認の「時刻で」確認する時刻どうしは、30 分以上あける (サイトへのアクセスを控えるため。Kotlin の BgCheck.MIN_GAP と同じ)。0:00 をまたぐ差も測る
  NH.BG_MIN_GAP = 30;
  NH.timeMinutes = (hhmm) => { const m = /^(\d{1,2}):(\d{2})$/.exec(String(hhmm || '')); return m && +m[1] < 24 && +m[2] < 60 ? +m[1] * 60 + +m[2] : null; };
  NH.timeGap = (a, b) => { const x = NH.timeMinutes(a), y = NH.timeMinutes(b); if (x === null || y === null) return Infinity; const d = Math.abs(x - y); return Math.min(d, 1440 - d); };
  // times の i 番目を value にしてよいか (i が範囲外 = 追加するとき)。ほかのどの時刻とも 30 分以上あいていること
  NH.timeSlotOk = (times, i, value) => times.every((t, j) => j === i || NH.timeGap(t, value) >= NH.BG_MIN_GAP);
  // 追加する時刻の初期値: 12:00 から 30 分刻みで、あいている最初の時刻 (あいていなければ null)
  NH.freeTimeSlot = (times) => {
    for (let k = 0; k < 48; k++) {
      const m = (720 + k * 30) % 1440;
      const t = String(Math.floor(m / 60)).padStart(2, '0') + ':' + String(m % 60).padStart(2, '0');
      if (NH.timeSlotOk(times, -1, t)) return t;
    }
    return null;
  };
  // 更新の元 (しおり・ブックマーク・フォロー) は、ログインしないと見られない。d.logins = {narou, hameln, kakuyomu: ログインの印 (Cookie) があるか}
  // は、Kotlin (MainActivity.syncLogins) がページを開くたびに写す (画面が無い裏の確認でも読めるように)。R18 のブックマークはなろうと同じログイン。
  // 分からないとき (まだ写していない) はログイン済みとみなす。collectOn は「設定で対象にしているか」、collectActive は「実際に確認するか」
  NH.loginOf = (d, id) => ((d && d.logins) || {})[id === 'narou18' ? 'narou' : id] !== false;
  NH.collectActive = (d, id) => NH.collectOn(d, id) && NH.loginOf(d, id);
  NH.collectAnyActive = (d) => NH.COLLECT_IDS.some((id) => NH.collectActive(d, id));

  // 続きから開く URL。読書位置 (d.progress) が、更新取得で得た url より新しければそれを優先する。
  //  - 途中までなら、その話をそのまま開く (位置は話ページ側で復元)
  //  - 読み終えた話なら、次の話 (記録してあれば) を開く
  NH.resumeUrl = function (d, it) {
    const p = (d.progress || {})[it.key];
    if (p && (!it.urlAt || p.at >= it.urlAt)) {
      if (!p.done) return p.url;
      if (p.next) return p.next;
    }
    return it.url;
  };

  // 設定の 1 項目を書く (設定画面の外 = リスト・オフライン・サイトのページの画面から。設定画面は S を通して書く)
  NH.setSetting = (k, v) => NH.update((d) => { d.settings = d.settings || {}; d.settings[k] = v; });

  // リストの種類: 更新取得で入ったもの = 'update' (未読の更新)、それ以外 = 'later' (後で読む)
  NH.kindOf = (x) => (x.kind === 'update' ? 'update' : 'later');

  // 読了の印。読了にした時刻 (doneAt) も持つ (一定日数で自動的に片付けるため)
  NH.setDone = (x, on) => {
    x.done = !!on;
    if (on) x.doneAt = x.doneAt || Date.now();
    else delete x.doneAt;
  };
  // 読書位置 p の話番号 (「第N話」に出す数)。no = 話 ID が連番でないサイト (カクヨム) で、目次の並びから数えた番号 (ui.js が足す)。
  // ep が短い数字 (なろう・ハーメルンの話番号) ならそれ。ep = '0' は短編で、番号なし。分からなければ null
  NH.epNumber = (p) => {
    if (!p) return null;
    if (Number.isInteger(p.no) && p.no >= 1 && p.no <= 99999) return p.no;
    return /^[1-9]\d{0,4}$/.test(String(p.ep)) ? Number(p.ep) : null;
  };
  // リストから外した作品の、話ごとの読書位置の記録も消す
  NH.dropProgress = (d, keys) => { if (d.progress) keys.forEach((k) => delete d.progress[k]); };
  // 読了から一定日数 (settings.pruneDays、既定 30 日) たった作品を消す。settings.pruneDone === false なら消さない。
  // doneAt の無い読了 (この機能より前のもの) は、今から数える。戻り値: 消した件数
  NH.PRUNE_DAYS = 30;
  NH.pruneDone = function (d) {
    const st = d.settings || {};
    d.readLater.forEach((x) => { if (x.done && !x.doneAt) x.doneAt = Date.now(); });
    if (st.pruneDone === false) return 0;
    const limit = Date.now() - (st.pruneDays || NH.PRUNE_DAYS) * 86400000;
    const gone = d.readLater.filter((x) => x.done && x.doneAt < limit).map((x) => x.key);
    if (gone.length) {
      d.readLater = d.readLater.filter((x) => !gone.includes(x.key));
      NH.dropProgress(d, gone);
    }
    return gone.length;
  };

  // 作品トップの URL (アダプターを読み込んでいないアプリ内ページでも使えるよう、サイトごとの形をここに持つ)
  NH.workUrl = (site, id) => ({
    narou: 'https://ncode.syosetu.com/' + id + '/',
    narou18: 'https://novel18.syosetu.com/' + id + '/',
    hameln: 'https://syosetu.org/novel/' + id + '/',
    kakuyomu: 'https://kakuyomu.jp/works/' + id,
  }[site] || null);
  // 作品を読んだことがあるか (ランキング・検索で薄くする目印): 'done' 読了 / 'reading' 読みかけ / 'seen' 開いたことがある / null
  // 読書位置・リストの読了・閲覧履歴 (公式の履歴を取り込んだものも含む) から判定する
  // 一覧の作品ごとに呼ぶので、リストと履歴は先に索引にしておき、(key) => 状態 の関数を返す
  NH.readStates = function (d) {
    const progress = d.progress || {};
    const doneInList = new Set(d.readLater.filter((x) => x.done).map((x) => x.key));
    const seen = new Set((d.history || []).map((h) => h.key));
    return (key) => {
      const p = progress[key];
      if (doneInList.has(key) || (p && p.done)) return 'done';
      if (p) return 'reading';
      return seen.has(key) ? 'seen' : null;
    };
  };
  NH.READ_LABEL = { done: '読了', reading: '読みかけ', seen: '閲覧済み' };

  // ---- バックアップの形 (設定の「書き出す」・読み込み。ほかの版にあるバックアップの送信も、Kotlin が同じ形で作る) ----
  // {app: NH.BACKUP_APP, format:1, exportedAt, appVersion, parts:{settings, list, progress, history, mute}}。
  // 項目ごとのファイルも同じ形 (parts が 1 つだけ)。読み込み (settings.html の nhImport) は入っている項目だけを置き換える。
  // [項目, 名前, 保存データのキー, 型]
  NH.BACKUP_PARTS = [
    ['settings', '設定', 'settings', 'object'],
    ['list', 'リスト', 'readLater', 'array'],
    ['progress', '読書位置', 'progress', 'object'],
    ['history', '閲覧履歴', 'history', 'array'],
    ['mute', 'ミュート', 'mute', 'object'],
  ];
  NH.backupJson = function (d, keys, appVersion) {
    const parts = {};
    keys.forEach((k) => {
      const b = NH.BACKUP_PARTS.find((x) => x[0] === k);
      parts[k] = d[b[2]] === undefined ? (b[3] === 'array' ? [] : {}) : d[b[2]];
    });
    return JSON.stringify({ app: NH.BACKUP_APP, format: 1, exportedAt: new Date().toISOString(), appVersion: appVersion || '', parts });
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
    return s.keys.slice(i + 1).map((k) => d.readLater.find((x) => x.key === k)).filter((x) => x && !x.done);
  };

  // 読了のあとに開く「次の小説」。絞り込みの並び (seqRest) があればその続き、無ければ同じ種類 (未読 / 後で読む) の先頭、それも無ければ残り全体。
  // 戻り値: {seq: 並びの続きか (並びの最後なら pool が空でも true), pool: 候補, next: 次の作品 | undefined, remaining: 残りの作品数}
  NH.nextPool = function (d, it) {
    const seq = NH.seqRest(d, it);
    const active = d.readLater.filter((x) => !x.done && x !== it);
    const same = active.filter((x) => NH.kindOf(x) === NH.kindOf(it));
    const pool = seq || (same.length ? same : active);
    return { seq: !!seq, pool, next: pool[0], remaining: pool.length };
  };

  // オフライン保存で選んだ話。st = {mode: 'all' | 'range' | 'pick', from, to (目次の上から数えた 1 始まり), picked: Set(ep)}、plan = {eps, todo}
  NH.selectEpisodes = function (plan, st) {
    if (st.mode === 'range') {
      const idx = new Map(plan.eps.map((e, i) => [e.ep, i + 1]));
      return plan.todo.filter((e) => idx.get(e.ep) >= st.from && idx.get(e.ep) <= st.to);
    }
    if (st.mode === 'pick') return plan.todo.filter((e) => st.picked.has(e.ep));
    return plan.todo;
  };

  // 話ページの読書位置。top / h = 本文の上端と高さ、scrollY = いまのスクロール、viewH = 画面の高さ。
  // y = 本文の何割まで来たか (画面の上端)、done = 画面の下端が本文の 97% を過ぎた
  NH.scrollState = (scrollY, top, h, viewH) => ({ y: (scrollY - top) / h, done: (scrollY + viewH - top) / h >= 0.97 });

  // 取得した更新 (未読あり) をリストに取り込む。順番は既存の並びを保ち、新規は末尾に足す。
  //  - 既存: url / unread / lastEp を更新。読了済みでも lastEp が伸びていれば未読に戻す
  //  - 「後で読む」に置いた作品は、更新があっても未読扱いにしない (続きの URL と最終話だけ更新する)
  //  - 今回の取得に含まれない「更新由来」の項目は、他で読み終えたとみなして読了にする
  // 戻り値: {added: 新しくリストに入った数, updates: 未読のある作品の数, fresh: 前回から話が増えた作品の名前}
  //   fresh は裏での確認の通知に使う (同じ未読で何度も知らせないよう、増えたものだけ)
  NH.mergeUpdates = function (site, items) {
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
        if (it && NH.kindOf(it) === 'later') {
          Object.assign(it, { url: u.url, lastEp: u.lastEp, urlAt: Date.now(), kind: 'later' });
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
          Object.assign(it, { url: u.url, unread: u.unread, lastEp: u.lastEp, urlAt: Date.now() });
        } else {
          d.readLater.push({ key, site, id: u.id, url: u.url, title: u.title, addedAt: Date.now(), done: false, kind: 'update', unread: u.unread, lastEp: u.lastEp, urlAt: Date.now() });
          added++;
          fresh.push(u.title);
        }
      });
      d.readLater.forEach((x) => {
        if (x.site === site && x.kind === 'update' && !seen.has(x.key)) NH.setDone(x, true);
      });
    });
    return { added, updates, fresh };
  };

  // ---- 取得のマナー (アクセスしすぎで Bot 扱いされないように) ----
  //  - 1ページ目は今開いているページをそのまま使い、同じページを取り直さない
  //  - 2ページ目以降は 1.5〜3 秒あけて取る
  //  - Cloudflare の確認画面 / 403 / 429 / 503 が返ったら、それ以上は取りに行かずに止める (NH.CHALLENGE)
  NH.CHALLENGE = 'challenge';
  NH.sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  // サイトへのアクセスのマナー: 続けて取りに行くときは、1 件ごとに 1.5〜3 秒あける (Kotlin の Politeness と同じ値。これより短くしない)
  NH.POLITE_MS = { min: 1500, jitter: 1500 };
  NH.pauseMs = (random = Math.random()) => NH.POLITE_MS.min + (Number.isFinite(random) ? Math.min(Math.max(random, 0), 0.999999) : 0) * NH.POLITE_MS.jitter;
  NH.pause = () => NH.sleep(NH.pauseMs());
  NH.isChallenge = (doc) => /Just a moment|しばらくお待ちください/.test((doc && doc.title) || '');
  // URL を取って HTML の文書にする。止められた (403 / 429 / 503 や確認画面) ときは NH.CHALLENGE の Error、
  // それ以外の 4xx / 5xx は Error('http 状態')。どちらも呼び出し側の catch で扱う (HTML を返して「ログインしていない」と誤判定しない)
  NH.fetchDoc = async function (href) {
    const res = await fetch(href, { credentials: 'include' });
    const doc = new DOMParser().parseFromString(await res.text(), 'text/html');
    if ([403, 429, 503].includes(res.status) || NH.isChallenge(doc)) throw new Error(NH.CHALLENGE);
    if (!res.ok) throw new Error('http ' + res.status);
    return doc;
  };
  NH.fetchListDoc = async function (url, pageIndex) {
    const u = new URL(url, location.href);
    if (pageIndex === 0 && u.origin === location.origin && u.pathname === location.pathname && u.search === location.search) {
      if (NH.isChallenge(document)) throw new Error(NH.CHALLENGE);
      return document;
    }
    if (pageIndex > 0) await NH.pause();
    return NH.fetchDoc(u.href);
  };

  // 止められたサイトは一定時間おやすみする (自動チェックは 60 分、手動の更新は 10 分)
  NH.restMinutes = (auto) => (auto ? 60 : 10);
  NH.isResting = (d, id, auto) => {
    const at = (d.rest || {})[id];
    return !!at && Date.now() - at < NH.restMinutes(auto) * 60000;
  };
  NH.markRest = (id) => NH.update((d) => { d.rest = d.rest || {}; d.rest[id] = Date.now(); });

  // ---- 読書の統計 ----
  // readStats: {since: 記録を始めた時刻, old: {e, c} (古くて捨てた日の合計), days: {'YYYY-MM-DD': {e: 読了した話数, c: 文字数, w: {作品のキー: 話数}}}}
  // 話の最後まで読んだとき (読書位置の done。ui.js) に 1 話として数える。日付は端末の時刻 (ローカル)。
  // days は直近 STATS_DAYS 日だけ残し、それより古い日は old に足して捨てる。設定 stats === false なら記録しない。端末の中だけ (バックアップには入れない)
  NH.STATS_DAYS = 400;
  const p2 = (n) => String(n).padStart(2, '0');
  NH.dayKey = (t) => { const d = new Date(t); return d.getFullYear() + '-' + p2(d.getMonth() + 1) + '-' + p2(d.getDate()); };
  // 'YYYY-MM-DD' の n 日前後 (夏時間の切り替えでずれないよう、昼の 12 時で数える)
  const shiftDay = (key, n) => { const [y, m, d] = key.split('-').map(Number); return NH.dayKey(new Date(y, m - 1, d + n, 12).getTime()); };
  NH.statsOn = (d) => !(d.settings && d.settings.stats === false);
  NH.noteRead = function (d, key, chars, now = Date.now()) {
    if (!NH.statsOn(d) || typeof key !== 'string') return;
    const s = d.readStats = isObj(d.readStats) ? d.readStats : {};
    if (!s.since) s.since = now;
    s.days = isObj(s.days) ? s.days : {};
    const k = NH.dayKey(now);
    const day = s.days[k] = isObj(s.days[k]) ? s.days[k] : { e: 0, c: 0, w: {} };
    day.w = isObj(day.w) ? day.w : {};
    day.e = (day.e || 0) + 1;
    day.c = (day.c || 0) + Math.max(0, Math.round(Number(chars)) || 0);
    day.w[key] = (day.w[key] || 0) + 1;
    const days = Object.keys(s.days).sort();
    while (days.length > NH.STATS_DAYS) {
      const o = days.shift(), x = s.days[o] || {};
      s.old = { e: ((s.old || {}).e || 0) + (x.e || 0), c: ((s.old || {}).c || 0) + (x.c || 0) };
      delete s.days[o];
    }
  };
  // 集計。now の日を「今日」とする。戻り値:
  //   today / week (今日を含む 7 日) / month (30 日): {e: 話数, c: 文字数, works: 作品数}、all: {e, c, since}
  //   streak: {now: 今日 (まだなら昨日) までの連続日数, best: 最長}、last14: [{day, e, c}] 古い順、top: [{key, n}] 直近 30 日に多く読んだ作品 (5 件まで)
  NH.statsSummary = function (d, now = Date.now()) {
    const s = isObj(d.readStats) ? d.readStats : {}, days = isObj(s.days) ? s.days : {};
    const today = NH.dayKey(now);
    const sum = (n) => {
      const out = { e: 0, c: 0, works: 0 }, keys = new Set();
      for (let i = 0; i < n; i++) {
        const x = days[shiftDay(today, -i)];
        if (!x) continue;
        out.e += x.e || 0; out.c += x.c || 0;
        Object.keys(x.w || {}).forEach((k) => keys.add(k));
      }
      out.works = keys.size;
      return out;
    };
    const all = { e: (s.old || {}).e || 0, c: (s.old || {}).c || 0, since: s.since || 0 };
    Object.values(days).forEach((x) => { all.e += (x && x.e) || 0; all.c += (x && x.c) || 0; });
    // 連続日数: 今日まだ読んでいなければ、昨日までの連続を数える (今日これから読めば続く)
    const has = (k) => !!days[k] && days[k].e > 0;
    let cur = 0, k = has(today) ? today : shiftDay(today, -1);
    while (has(k)) { cur++; k = shiftDay(k, -1); }
    let best = 0, run = 0, prev = null;
    Object.keys(days).filter(has).sort().forEach((dk) => { run = prev && shiftDay(prev, 1) === dk ? run + 1 : 1; best = Math.max(best, run); prev = dk; });
    const last14 = [];
    for (let i = 13; i >= 0; i--) { const dk = shiftDay(today, -i), x = days[dk] || {}; last14.push({ day: dk, e: x.e || 0, c: x.c || 0 }); }
    const per = {};
    for (let i = 0; i < 30; i++) { const x = days[shiftDay(today, -i)]; if (x) Object.entries(x.w || {}).forEach(([key, n]) => { per[key] = (per[key] || 0) + n; }); }
    const top = Object.entries(per).sort((a, b) => b[1] - a[1] || (a[0] < b[0] ? -1 : 1)).slice(0, 5).map(([key, n]) => ({ key, n }));
    return { today: sum(1), week: sum(7), month: sum(30), all, streak: { now: cur, best }, last14, top };
  };

  // ---- ランキング / 検索結果のミュート ----
  // mute: {authors: [作者名], words: [キーワード・タグ], mode: 'hide' | 'dim', block: {works: [{key, title}], authors, words}}
  //   作者は完全一致、キーワードはタイトルとタグに含まれていれば一致 (全角半角・大小は区別しない)
  //   ミュートは隠す / 薄く (一時的に表示もできる)。ブロック (block) は完全に出さない (件数にも「表示する」にも入れない)。
  //   ブロックはランキング・検索の一覧に加えて、おすすめや作者の作品一覧など、ページの中の作品へのリンクにも効く (ui.js の sweepBlocked)
  NH.muteOf = (d) => Object.assign({ authors: [], words: [], mode: 'hide' }, d.mute);
  NH.blockOf = (d) => Object.assign({ works: [], authors: [], words: [] }, NH.muteOf(d).block);
  const norm = (s) => String(s || '').normalize('NFKC').toLowerCase().trim();
  // 作者・キーワードに一致した理由 (無ければ null)。info = {title, author, tags}
  const matchReason = (authors, words, info) => {
    const author = norm(info.author);
    if (author && authors.some((a) => norm(a) === author)) return '作者: ' + info.author;
    const hay = [info.title, ...(info.tags || [])].map(norm);
    const w = words.find((x) => norm(x) && hay.some((h) => h.includes(norm(x))));
    return w ? 'キーワード: ' + w : null;
  };
  NH.muteReason = (d, info) => { const m = NH.muteOf(d); return matchReason(m.authors, m.words, info); };
  // info = {key, title, author, tags}。作者が分からないとき (おすすめなどのリンク) は、作品とキーワードだけで判定する
  NH.blockReason = (d, info) => {
    const b = NH.blockOf(d);
    if (info.key && b.works.some((w) => w.key === info.key)) return 'この作品';
    return matchReason(b.authors, b.words, info);
  };
  NH.hasBlock = (d) => { const b = NH.blockOf(d); return !!(b.works.length || b.authors.length || b.words.length); };
  // ブロックの追加と解除。kind: 'works' (value = {key, title}、解除は key) | 'authors' | 'words'
  NH.addBlock = (kind, value) => NH.update((d) => {
    d.mute = NH.muteOf(d);
    const b = (d.mute.block = NH.blockOf(d));
    if (kind === 'works') {
      if (value && value.key && !b.works.some((w) => w.key === value.key)) b.works.push({ key: value.key, title: String(value.title || '') });
      return;
    }
    const v = String(value || '').trim();
    if (v && !b[kind].some((x) => norm(x) === norm(v))) b[kind].push(v);
  });
  NH.removeBlock = (kind, value) => NH.update((d) => {
    d.mute = NH.muteOf(d);
    const b = (d.mute.block = NH.blockOf(d));
    b[kind] = b[kind].filter((x) => (kind === 'works' ? x.key : x) !== value);
  });
  // 追加 (重複は足さない)。kind: 'authors' | 'words'
  NH.addMute = (kind, value) => NH.update((d) => {
    const v = String(value || '').trim();
    d.mute = NH.muteOf(d);
    if (v && !d.mute[kind].some((x) => norm(x) === norm(v))) d.mute[kind].push(v);
  });
  NH.removeMute = (kind, value) => NH.update((d) => {
    d.mute = NH.muteOf(d);
    d.mute[kind] = d.mute[kind].filter((x) => x !== value);
  });

  // ---- 閲覧履歴 ----
  // history: [{key, site, id, title, url, ep, at, appAt, siteAt, siteUrl, total}] 新しい順。
  //   appAt  = アプリで最後に開いた時刻 (url / ep はそのときの話)
  //   siteAt = 公式の閲覧履歴ページで最後に見かけた時刻 (siteUrl は公式の「続きから」)
  // 公式の履歴はユーザーがそのページを開いたときだけ取り込む (こちらから取りに行かない)。
  NH.HISTORY_MAX = 300; // 保存する作品数の既定 (settings.historyMax。0 = 無制限)
  NH.historyOn = (d) => !(d.settings && d.settings.history === false);
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
    const h = d.history || [];
    const i = h.findIndex((x) => x.key === key);
    title = title || (i >= 0 && h[i].title) || (NH.findItem(d, w) || {}).title || '';
    const track = NH.historyOn(d) && !!title;
    if (!fixSub && !track) return;
    NH.update((d) => {
      if (fixSub) d.readLater.forEach((x) => { if (x.key === key) x.sub = sub; });
      if (!track || !NH.historyOn(d)) return;
      const h = (d.history = d.history || []);
      const i = h.findIndex((x) => x.key === key);
      const e = i >= 0 ? h.splice(i, 1)[0] : { key, site: w.site, id: w.id };
      e.title = title;
      if (w.episode != null || short || !e.url) { e.url = url; e.ep = w.episode != null ? String(w.episode) : null; } // 目次を開いただけなら、読んだ話は残す
      e.at = e.appAt = Date.now();
      if (sub) e.sub = sub;
      h.unshift(e);
      NH.pruneHistory(d);
    });
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

  // ---- ページ内の移動 (カクヨムは Next.js で、話から話へページを読み込み直さずに移る) ----
  // 注入した JS は読み込みのときに 1 回しか動かないので、作品 / 話が変わったら読み込み直す (読書位置・履歴・読了のカードを新しいページで作り直すため)。
  // 一覧どうし (作品でないページの間) の移動は、MutationObserver で足りるので何もしない。
  // workSig: 今のページの作品と話を表す文字列 (作品でなければ '')
  NH.workSig = (url) => { const w = NH.parseWork(url); return w ? w.site + ':' + w.id + ':' + (w.episode == null ? '' : w.episode) : ''; };
  // 作品 / 話が変わったら onChange を 1 度だけ呼ぶ。history の書き換え・popstate・1 秒ごとの確認 (history を先に退避された場合の保険) で見張る。
  // beforeChange: history を書き換える直前に呼ぶ (まだ前の話のうちに、読書位置などを書き切るため。変わったあとでは書けない)
  // 戻り値: 今変わっているかを返す関数
  NH.watchWork = function (onChange, beforeChange) {
    const sig = NH.workSig(location.href);
    let fired = false;
    let timer = 0;
    const changed = () => NH.workSig(location.href) !== sig;
    const check = () => {
      if (fired || !changed()) return;
      fired = true;
      if (timer && typeof clearInterval === 'function') clearInterval(timer);
      onChange();
    };
    ['pushState', 'replaceState'].forEach((m) => {
      const orig = history[m];
      if (typeof orig !== 'function') return;
      history[m] = function () {
        if (beforeChange && !fired) { try { beforeChange(); } catch (e) { /* 書けなくても移動は止めない */ } }
        const r = orig.apply(this, arguments);
        check();
        return r;
      };
    });
    window.addEventListener('popstate', check);
    timer = setInterval(check, 1000);
    return changed;
  };

  // action = {label, fn} を渡すと、ボタン (「元に戻す」など) を付けて少し長く出す
  NH.toast = function (msg, action) {
    const t = document.createElement('div');
    t.className = 'nh-toast'; // スタイルは ui.js が注入
    t.textContent = msg;
    if (action) {
      const b = document.createElement('button');
      b.textContent = action.label;
      b.addEventListener('click', () => { t.remove(); action.fn(); });
      t.appendChild(b);
    }
    document.documentElement.appendChild(t);
    setTimeout(() => t.remove(), action ? 5000 : 1800);
  };

})();
