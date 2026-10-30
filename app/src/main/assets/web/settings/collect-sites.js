// ---- 更新を確認するサイト (settings.collectSites[id]。未設定はオン。COLLECT_SITES は app.js) ----
// 表示するサイトのうち、更新を確認できるもの (R18 は 3 サイトのどれかが表示中のとき)
const collectShown = (id) => (id === 'narou18' ? NH.R18_SITES.some(siteOn) : siteOn(id));
function paintCollectSites(any) {
  const box = $('collectSiteList');
  box.innerHTML = '';
  COLLECT_SITES.filter(([id]) => collectShown(id)).forEach(([id, label]) => {
    const ok = loggedIn(id);
    // ログインしていないサイトは確認しない (オフに見せる。選んでいた値は残す)
    const row = switchRow(label, ok && (S.collectSites || {})[id] !== false,
      (on) => { S.collectSites = Object.assign({}, S.collectSites, { [id]: on }); commit(); }, ok ? 'ログイン済み' : '未ログイン');
    row.cb.disabled = !ok;
    row.inert = !ok; // 押せない (薄く見せる)
    box.appendChild(row);
  });
  const active = any && activeAny();
  $('collectNone').hidden = any;
  $('autoBody').hidden = !any;
  // どのサイトにもログインしていないときは、確認の設定 (起動時の確認・カテゴリ・裏での確認) を押せなくして、理由を出す
  $('autoBody').inert = !active;
  $('needLogin').hidden = !any || active;
}
