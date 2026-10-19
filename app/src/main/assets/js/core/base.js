// Yomikura 共通基盤 (1): 名前空間 NH・モジュールの登録・URL の扱い・アダプターの登録・小さな道具 (norm / el)。ほかの core/ のファイルより先に読む
(function () {
  // 保存データの窓口に添える合言葉 (NHData.token)。アプリがページ本体にだけ入れる (iframe には無い)。読んだらページから消す
  const fresh = window.__nhT;
  try { delete window.__nhT; } catch (e) { /* 消せなくても動く */ }
  if (window.NH) return; // 二重注入防止
  const NH = (window.NH = { adapters: [], modules: {} });
  // 保存データ・オフライン保存の窓口 (NHStore / NHOffline) に添える合言葉。NH には置かず (ページのスクリプトから読ませない)、
  // NH.define がモジュールの関数の第 2 引数で渡す。窓口を呼ぶ store.js / offline-api.js だけが受け取る
  const token = fresh || '';

  // 機能ごとのファイル (core/*.js) は NH.define で登録する。注入は 1 ページで何度も来る (onPageFinished・DOM が読めた時点) ので、同じ名前は 2 度目からは動かさない。
  // 先に読んでおくべきモジュールは、本体の先頭で NH.need('名前') で確かめる (順番を間違えたら、後で見つけにくい undefined ではなく、ここで例外にする)
  NH.define = function (name, fn) {
    if (NH.modules[name]) return;
    NH.modules[name] = true;
    fn(NH, token);
  };
  NH.need = function (name) {
    if (!NH.modules[name]) throw new Error('NH: ' + name + ' を先に読み込んでください');
  };

  // 文字列の比較用の形 (全角・半角と大文字・小文字をそろえ、前後の空白を除く)。ミュート・検索が使う
  NH.norm = (s) => String(s || '').normalize('NFKC').toLowerCase().trim();
  // DOM の要素を作る (cls = class、text = 文字)。ページ上の画面 (ui.js) とアプリ内ページが共通で使う
  NH.el = (tag, cls, text) => {
    const e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text;
    return e;
  };

  NH.LIST_URL = 'https://yomikura.local/list.html';
  NH.HISTORY_URL = 'https://yomikura.local/list.html#history';
  NH.SETTINGS_URL = 'https://yomikura.local/settings.html';

  // 保存データなど、データから来た URL を開いてよいか (https で空白・制御文字を含まないものだけ)。
  // javascript: などを location に渡さないための共通の確認 (Kotlin の SiteUrls.safeNavUrl と同じ規則)
  NH.safeUrl = (u) => typeof u === 'string' && /^https:\/\/[^\/?#\s]+/i.test(u) && !/[\u0000-\u0020\u007f]/.test(u);
  // 確かめてから開く。開けない URL ならリストへ
  NH.go = (u, replace) => {
    const url = NH.safeUrl(u) ? u : NH.LIST_URL;
    if (replace) location.replace(url); else location.href = url;
  };

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
})();
