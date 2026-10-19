// Yomikura 共通基盤 (3): 対応サイトの一覧・R18 の掲載サイト・表示名・作品の URL の形
window.NH.define('siteinfo', function (NH) {
  NH.need('store'); // 表示名の設定を NH.load で読む
  // ---- R18 のサイト (ノクターン / ムーンライト / ミッドナイト) ----
  // 作品の URL (novel18.syosetu.com) はどれも同じなので、開いているページから判断する:
  // ランキング・検索ならホスト、作品ページならヘッダーのサイトへのリンク (c-follow-header__item)。分からなければ ''
  // サイトの一覧 (名前・R18 の掲載サイト・表示の既定) の唯一の定義。HTML 側 (list / settings など) もここから引く:
  //   NH.SITE_DEFS      [{key, name, color, sub, on}] 表示順。color = 上部バーのタブの点の既定色 (Kotlin の BarTheme と同じ。sites-sync.test.mjs が確かめる)、sub = R18 (narou18) の掲載サイトか、on = 設定 sites の既定 (undefined = 既定なし)
  //   NH.SITE_NAMES     {key: 名前} 本体のサイト (narou / narou18 / hameln / kakuyomu)
  //   NH.SUB_NAMES      {key: 名前} R18 の掲載サイト (noc / mnlt / mid)
  //   NH.SITE_DEFAULT   {key: bool} 表示の既定 (本体のサイトと掲載サイト。narou18 は掲載サイトの有無で決まるので持たない)
  //   NH.COLLECT_IDS    更新を確認するサイトのキー (本体のサイト、表示順)
  //   NH.COLLECT_ORDER  更新の巡回の順 (COLLECT_IDS と同じサイトを、確認画面が出やすい順に並べたもの。SiteConfig.COLLECT_ORDER と同じ)
  //   NH.R18_SITES      掲載サイトのキー
  NH.SITE_DEFS = [
    { key: 'narou', name: 'なろう', color: '#4A8FE0', on: true },
    { key: 'narou18', name: 'R18' },
    { key: 'hameln', name: 'ハーメルン', color: '#E07B3C', on: true },
    { key: 'kakuyomu', name: 'カクヨム', color: '#22B3A4', on: true },
    { key: 'noc', name: 'ノクターン', color: '#D9529A', sub: true, on: true },
    { key: 'mnlt', name: 'ムーンライト', color: '#D9529A', sub: true, on: true },
    { key: 'mid', name: 'ミッドナイト', color: '#D9529A', sub: true, on: true },
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
  // 上部バーのタブに付けた名前 (settings.siteNames、AppSettings.siteName と同じく 12 文字まで) があればそれを使う。
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

  // 設定: 表示するサイトの有効判定 (初期は全部表示。18 歳未満を選ぶと R18 はオフで保存される)
  // (AppSettings.siteOn と同じ既定値)
  const SITE_DEFAULT = (NH.SITE_DEFAULT = NH.SITE_DEFS.filter((x) => x.on !== undefined).reduce((o, x) => ((o[x.key] = x.on), o), {}));
  NH.R18_SITES = Object.keys(NH.SUB_NAMES);
  NH.siteOn = (d, key) => {
    const s = (d.settings && d.settings.sites) || {};
    return s[key] === undefined ? SITE_DEFAULT[key] : !!s[key];
  };

  // hint = 作品の URL (リストの url など)。ハーメルンの R-18 (h.syosetu.org) は、そのホストの作品ページにする
  const hamelnBase = (hint) => ((NH.parseUrl(hint) || {}).hostname === 'h.syosetu.org' ? 'https://h.syosetu.org' : 'https://syosetu.org');
  // 1 話目の URL (「1 話から読む」)。話の URL が作品の ID から作れないサイト (カクヨム) は null。
  // ハーメルンは 1 話が削除されていると /1.html が無いので、作品のページを開く (null)
  NH.firstEpUrl = (site, id, hint) => ({
    narou: 'https://ncode.syosetu.com/' + id + '/1/',
    narou18: 'https://novel18.syosetu.com/' + id + '/1/',
  }[site] || null);

  // 作品トップの URL (アダプターを読み込んでいないアプリ内ページでも使えるよう、サイトごとの形をここに持つ)
  NH.workUrl = (site, id, hint) => ({
    narou: 'https://ncode.syosetu.com/' + id + '/',
    narou18: 'https://novel18.syosetu.com/' + id + '/',
    hameln: hamelnBase(hint) + '/novel/' + id + '/',
    kakuyomu: 'https://kakuyomu.jp/works/' + id,
  }[site] || null);
});
