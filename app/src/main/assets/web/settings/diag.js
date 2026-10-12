// ---- 診断情報 (不具合の報告に貼る文。Kotlin の DiagBridge = NHDiag) ----
// 載せるのは版・端末・設定の要約・データの件数・直近のエラーだけ。外へは送らず、コピーするだけ
const DIAG = window.NHDiag || null;
function paintDiag() {
  $('diagText').textContent = DIAG ? DIAG.report() : '(この版では表示できません)';
  $('diagCopy').disabled = $('diagClear').disabled = !DIAG;
}
ON_SHOW.diag = paintDiag; // この画面を開いたとき、最新の中身にする
$('diagCopy').onclick = () => snack(DIAG && DIAG.copy() ? '診断情報をコピーしました' : 'コピーできませんでした');
$('diagClear').onclick = () => ask('エラー・警告の記録を消します。', '消す', () => { DIAG.clearLog(); paintDiag(); snack('消しました'); });