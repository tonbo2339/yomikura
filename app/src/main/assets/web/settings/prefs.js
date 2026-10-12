// ---- 各設定 ----
$('updateCard').onclick = () => go('update');
$('updCheck').onclick = checkUpdate;
$('updInstall').onclick = () => { if (avail && window.NHStore && NHStore.installUpdate) NHStore.installUpdate(); };
$('updLogin').onclick = () => { location.href = 'https://github.com/login'; }; // ログインしたあとは、⋮ メニューの「設定」で戻る
// ログイン済みならボタンを押せなくして、そう表示する (ログインの情報はアプリ内の WebView の Cookie)
function paintGithub() {
  const user = window.NHStore && NHStore.githubUser ? NHStore.githubUser() : '';
  $('updLogin').disabled = !!user;
  $('updLogin').textContent = user ? 'GitHub にログイン済み' + (user !== 'yes' ? ' (' + user + ')' : '') : 'GitHub にログイン';
}