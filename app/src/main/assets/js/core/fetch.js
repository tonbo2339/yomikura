// Yomikura 共通基盤 (12): サイトからの取得のマナー (間をあける・止められたら休む)
window.NH.define('fetch', function (NH) {
  NH.need('store');
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
});
