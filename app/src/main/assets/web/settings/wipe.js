// ---- データの管理: 消去 ----
const CAN_DATA = !!(window.NHStore && NHStore.wipeAll);
$('clearCache').onclick = () => ask('ページの一時ファイル (キャッシュ) を消します。ログイン・リスト・設定は消えません。', 'キャッシュを削除', () => {
  if (CAN_DATA && NHStore.clearWebCache()) snack('キャッシュを削除しました');
});
$('clearLogins').onclick = () => ask('なろう・ハーメルン・カクヨム' + (NEEDS_LOGIN ? 'と GitHub' : '') + 'のログイン情報を消します。リスト・設定は消えません。', 'ログイン情報を削除', () => {
  if (CAN_DATA && NHStore.clearLogins()) { dropLogins(); snack('ログイン情報を削除しました'); refreshLogins(); paintGithub(); }
});
$('wipeAll').onclick = () => ask('リスト・設定・閲覧履歴・読書位置・ミュート・オフライン保存をすべて消して、最初の画面に戻ります。取り消せません。', 'すべて削除', () => {
  if (CAN_DATA) NHStore.wipeAll();
});
