// ---- データの管理: 消去 ----
const CAN_DATA = !!(window.NHStore && NHStore.wipeAll);
$('clearCache').onclick = () => ask('ページの一時ファイル (キャッシュ) を消します。ログイン・リスト・設定は消えません。', 'キャッシュを削除', () => {
  if (CAN_DATA && NHStore.clearWebCache()) snack('キャッシュを削除しました');
});
$('clearLogins').onclick = () => ask('なろう・ハーメルン・カクヨム' + (NEEDS_LOGIN ? 'と GitHub' : '') + 'のログイン情報を消します。リスト・設定は消えません。', 'ログイン情報を削除', () => {
  if (CAN_DATA && NHStore.clearLogins()) { dropLogins(); snack('ログイン情報を削除しました'); refreshLogins(); paintGithub(); }
});
// 取り消せないうえ、オフライン保存の作品も全部消えるので、確認は 2 段階 (1 回目で内容を見せ、2 回目で最後の念押し)
$('wipeAll').onclick = () => ask('リスト・設定・閲覧履歴・読書位置・ミュート・オフライン保存をすべて消して、最初の画面に戻ります。取り消せません。', '次へ', () => {
  ask('本当にすべて削除しますか？', '完全に削除する', () => {
    if (!CAN_DATA) return;
    dropUndo(); // 復元の取り消しの控え (backup.js) も消す (消したはずのリスト・設定を「復元前の状態に戻す」で書き戻せないように)
    NHStore.wipeAll();
  }, 'いま消すと元に戻せません。必要なら、先に「書き出す」でバックアップのファイルを残してください。');
});
