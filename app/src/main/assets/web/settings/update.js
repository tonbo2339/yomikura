// ---- アプリの更新 ----
// 確認とダウンロードは Kotlin (Updater) がする。結果と進み具合は nhUpdate(JSON) で届く。
// 新しい版が見つかった結果 (latest / available) は 30 分だけ覚えておき、設定を開くたびに通信しない
const UPD_CACHE = 'nh-upd';
const CURRENT = (window.NHStore && NHStore.appVersion) ? NHStore.appVersion() : '';
const CAN_UPDATE = !!(window.NHStore && NHStore.checkUpdate);
const APP_NAME = (window.NH_EDITION || {}).appName || 'ヨミクラ'; // アプリ名 (版ごと。Kotlin の BuildConfig.APP_NAME)
// 版によって増える「畳む」項目の設定名 (repaint.js が、畳んでいる数に数える)
const EXTRA_FOLD = [];
// 更新に GitHub のログインが要る版か (非公開のリポジトリから取る版だけ)。要らない版は、ログインの表示を隠す
const NEEDS_LOGIN = !!(window.NHStore && NHStore.updateNeedsLogin && NHStore.updateNeedsLogin());
$('updLoginBox').hidden = !NEEDS_LOGIN;
$('ghNote').hidden = !NEEDS_LOGIN;
let upd = { state: 'idle' };
let avail = ''; // 入れられる新しい版 (あれば)
try {
  const c = JSON.parse(localStorage.getItem(UPD_CACHE) || 'null');
  if (c && c.v === CURRENT && Date.now() - c.at < 1800000) { upd = c.r; avail = c.r.state === 'available' ? c.r.latest : ''; }
} catch (e) { /* 覚えていない */ }

window.nhUpdate = (json) => {
  upd = JSON.parse(json);
  if (upd.state === 'available') avail = upd.latest;
  else if (upd.state === 'latest') avail = '';
  if (upd.state === 'available' || upd.state === 'latest') {
    try { localStorage.setItem(UPD_CACHE, JSON.stringify({ at: Date.now(), v: CURRENT, r: upd })); } catch (e) { /* 覚えない */ }
  }
  paintUpdate();
};
function checkUpdate() {
  if (!CAN_UPDATE) return;
  upd = { state: 'checking' };
  paintUpdate();
  NHStore.checkUpdate();
}
function paintUpdate() {
  const busy = upd.state === 'checking' || upd.state === 'progress' || upd.state === 'installing';
  const text = {
    idle: '', checking: '確認しています…', latest: '最新のバージョンです',
    available: '新しいバージョン ' + (upd.latest || '') + ' があります',
    login: 'GitHub にログインすると、更新を確認できます',
    error: upd.message || '確認できませんでした',
    progress: 'ダウンロードしています… ' + (upd.pct || 0) + '%',
    installing: 'インストールの画面で「更新」を押してください',
    permission: '「このアプリからのインストール」を許可してから、もう一度押してください',
  }[upd.state] || '';
  const st = $('u-status');
  st.textContent = CAN_UPDATE ? text : 'この版では更新を確認できません';
  st.className = 'status' + (upd.state === 'latest' || upd.state === 'available' ? ' good' : upd.state === 'error' || upd.state === 'login' ? ' bad' : '');
  $('u-current').textContent = CURRENT;
  $('u-latest').textContent = avail || (upd.state === 'latest' ? CURRENT : '—');
  $('u-progress').hidden = upd.state !== 'progress';
  $('u-bar').style.width = (upd.pct || 0) + '%';
  $('updInstall').hidden = !avail || busy;
  $('updInstall').textContent = 'バージョン ' + avail + ' をダウンロードしてインストール';
  $('updCheck').disabled = busy || !CAN_UPDATE;
  $('updateCard').hidden = !avail;
  $('updateCardText').textContent = 'バージョン ' + avail + ' があります';
  $('v-update').textContent = avail ? '新 ' + avail : upd.state === 'login' ? 'ログインが必要' : 'v' + CURRENT;
  $('v-update').style.color = avail ? 'var(--accent-text)' : '';
}
