// Yomikura 共通基盤 (5): 更新の確認の対象にするサイト・ログインの判定・ブックマークのカテゴリ
window.NH.define('collect-rules', function (NH) {
  NH.need('siteinfo');
  NH.need('nsfw');
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
  // collectSites のキーは narou / narou18 / hameln / kakuyomu。未設定は対象 (SiteConfig.collectOn と同じ)
  NH.COLLECT_IDS = Object.keys(NH.SITE_NAMES);
  // 巡回の順。確認画面が出やすいサイトを先にして、通してもらっている間にほかのサイトを確認できるようにする (SiteConfig.COLLECT_ORDER と同じ)
  NH.COLLECT_ORDER = ['hameln', 'narou', 'narou18', 'kakuyomu'];

  NH.collectOn = (d, id) => {
    if (id === 'narou18' && NH.nsfwOn(d)) return false;
    const shown = id === 'narou18' ? NH.R18_SITES.some((k) => NH.siteOn(d, k)) : NH.siteOn(d, id);
    return shown && ((d.settings || {}).collectSites || {})[id] !== false;
  };
  NH.collectAny = (d) => NH.COLLECT_IDS.some((id) => NH.collectOn(d, id));

  // 更新の元 (しおり・ブックマーク・フォロー) は、ログインしないと見られない。d.logins = {narou, hameln, kakuyomu: ログインの印 (Cookie) があるか}
  // は、Kotlin (LoginCookies.sync) がページを開くたびに写す (画面が無い裏の確認でも読めるように)。R18 のブックマークはなろうと同じログイン。
  // 分からないとき (まだ写していない) はログイン済みとみなす。collectOn は「設定で対象にしているか」、collectActive は「実際に確認するか」
  NH.loginOf = (d, id) => ((d && d.logins) || {})[id === 'narou18' ? 'narou' : id] !== false;
  NH.collectActive = (d, id) => NH.collectOn(d, id) && NH.loginOf(d, id);
  NH.collectAnyActive = (d) => NH.COLLECT_IDS.some((id) => NH.collectActive(d, id));
});
