// ---- 各設定 ----
$('updateCard').onclick = () => go('update');
$('updCheck').onclick = checkUpdate;
$('updInstall').onclick = () => { if (avail && window.NHStore && NHStore.installUpdate) NHStore.installUpdate(); };
$('updLogin').onclick = $('updLoginTop').onclick = () => { location.href = 'https://github.com/login'; }; // ログインしたあとは、⋮ メニューの「設定」で戻る
// ログイン済みならボタンを押せなくして、そう表示する (ログインの情報はアプリ内の WebView の Cookie)。
// Cookie が残っていても、更新の確認が「ログインが必要」と返ったとき (セッション切れ) は、ログインし直せるようにする
function paintGithub() {
  const user = window.NHStore && NHStore.githubUser ? NHStore.githubUser() : '';
  const expired = !!user && upd.state === 'login';
  $('updLogin').disabled = !!user && !expired;
  $('updLogin').textContent = expired ? 'GitHub にログインし直す' : user ? 'GitHub にログイン済み' + (user !== 'yes' ? ' (' + user + ')' : '') : 'GitHub にログイン';
}