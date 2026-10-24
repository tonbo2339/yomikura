// ---- ログイン: なろうの認証アプリの鍵 (NHTotp) ----
// 鍵そのものは画面に出さない・読まない。登録は、なろうの認証アプリの設定画面から (login-totp.js)。ここは状況の表示と削除だけ。
const CAN_TOTP = !!(window.NHTotp && NHTotp.has);
function paintTotp() {
  $('totpGroup').hidden = $('totpHint').hidden = !CAN_TOTP;
  if (!CAN_TOTP) return;
  const on = NHTotp.has('');
  $('totpState').textContent = on ? '登録済み' : '未登録';
  $('totpClear').hidden = !on;
}
$('totpClear').onclick = () => ask('登録した認証アプリの鍵を、この端末から削除します。なろうの設定は変わりません。このあとのログインでは、認証コードを手で入れます。', '鍵を削除', () => {
  if (CAN_TOTP && NHTotp.clear()) { snack('鍵を削除しました'); paintTotp(); }
});
paintTotp();
