// list.html: 画面の外 (裏の確認・保存) で変わったデータの反映・作品の情報の補い
// 画面の外 (裏の確認・保存) で保存データが変わったとき (MainActivity が呼ぶ)。続けて来るのでまとめて描き直す。
// 作品を持ち上げている間・メニューのシートを開いている間は待つ (シートを閉じると描き直す)
let dataTimer = 0;
window.nhDataChanged = () => {
  clearTimeout(dataTimer);
  dataTimer = setTimeout(() => {
    if (document.querySelector('.item.dragging') || $('sheet').classList.contains('show')) { window.nhDataChanged(); return; }
    render();
  }, 400);
};
window.nhProgress = (i, n) => { if (checking) $('checkProg').textContent = i + ' / ' + n; };
$('collect').onclick = collect;
// サイト (ノクターンなど) が分からない R18 の作品は、アプリが作品ページを読んで判別する。分かったら描き直す
window.nhSubsFilled = () => render();
(() => {
  const ids = load().readLater.filter((x) => x.site === 'narou18' && !x.sub).map((x) => x.id);
  if (ids.length && window.NHStore && NHStore.fillR18Subs) NHStore.fillR18Subs(JSON.stringify(ids));
})();
// ハーメルンの作品が R-18 かは、まだ調べていない作品 (r18 が未設定) を、小説情報のページで判別する (NSFW モード中は通信しない)
(() => {
  const ids = load().readLater.filter((x) => x.site === 'hameln' && x.r18 === undefined).map((x) => x.id);
  if (ids.length && window.NHStore && NHStore.fillHamelnR18) NHStore.fillHamelnR18(JSON.stringify(ids));
})();
if (window.NHStore && NHStore.collecting && NHStore.collecting()) {
  nhCollecting(true);
  // 起動時の確認など、リストを開く前から進んでいたときは、いまの進み具合を出す
  const p = NHStore.progressNow ? NHStore.progressNow() : '';
  if (p) $('checkProg').textContent = p;
}
