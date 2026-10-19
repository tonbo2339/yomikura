// list.html: 更新の確認 (ボタンと進み具合の表示)
// 更新の確認: アプリが画面に出さずに確認して、結果だけ知らせる (NHStore.collectNow)。
// 確認中は更新ボタンを回し、終わったら (nhCollecting(false)) 取り込んだ結果で描き直す
function collect() {
  if (window.NHStore && NHStore.collectNow) NHStore.collectNow();
}
// 確認のあいだは、ボタンを「確認中」(灰色・押せない) にする。終わったら一覧を出し直す (結果は確認のお知らせのバーで出す)
var checking = false; // var: render() は下で書く前に呼ばれることがある (let だと読めない)
// 確認中はボタンを灰色にして押せなくし、横に「2 / 4」(何サイト目か) を出す
window.nhCollecting = (on) => {
  checking = !!on;
  $('collect').disabled = checking;
  $('checkProg').hidden = !checking;
  $('checkLast').hidden = !!checking;
  $('collectText').textContent = checking ? '確認中' : '更新を確認';
  $('collect').classList.toggle('spinning', checking);
  $('checkProg').textContent = checking ? '…' : '';
  if (!on) render();
};
// 手動の確認は、前回の確認から 10 分は押せない (残りは Kotlin の CollectCooldown が NHStore.collectWaitMs で返す)。
// 待つ間はボタンを押せなくして「あと N 分」を出し、時間が来たら描き直して押せるように戻す
var waitTimer = 0;
function paintCollectWait() {
  clearTimeout(waitTimer);
  const ms = !checking && window.NHStore && NHStore.collectWaitMs ? Number(NHStore.collectWaitMs()) || 0 : 0;
  $('collect').disabled = checking || ms > 0;
  if (ms > 0) {
    $('checkLast').textContent += ' ・ あと ' + Math.ceil(ms / 60000) + ' 分';
    waitTimer = setTimeout(paintCheckRow, Math.min(ms, 60000) + 200); // 1 分ごとに「あと N 分」を進める (一覧は描き直さない。操作中のフォーカスや持ち上げを崩さないため)
  }
}
// 前回の確認の時刻 (確認していないときだけ。確認中は「確認中」と進み具合になる) と、次に押せるまでの時間
function paintCheckRow(d = load()) {
  $('checkLast').textContent = !checking && d.lastCollect ? '前回の確認 ' + new Date(d.lastCollect).toLocaleString('ja-JP', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' }) : '';
  paintCollectWait();
}
