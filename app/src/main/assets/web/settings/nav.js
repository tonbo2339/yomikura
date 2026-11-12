// ---- 画面の移動 (トップ ⇄ 詳細)。Android の「戻る」でも戻れるよう履歴に積む ----
// 開いたときに読み込む画面。版ごとに足される画面は、自分で登録する
const ON_SHOW = { names: () => paintNamesPage() };
const EDITION_PAGES = new Set(['main']); // EDITION_PAINT を呼ぶ画面の id (その版が差し込む画面は、自分で足す)
const EDITION_PAINT = []; // 版ごとに足される画面の、paint() での表示の更新 (その版が差し込む JS が登録する)
let curPage = 'main'; // いま開いている画面。paint() はこの画面の分だけ描く
// 広い画面 (タブレット・横向き) は、左に一覧 (main)・右に詳細の 2 列。一覧だけを開く状態は無いので、main は DEFAULT_DETAIL を右に出す (幅は style.css の @media と同じ)
const SPLIT = window.matchMedia('(min-width: 900px)');
const DEFAULT_DETAIL = 'start';
const scrollAt = {}; // 画面ごとの、離れたときのスクロール位置
function show(id, dir) {
  const split = SPLIT.matches;
  if (split && id === 'main') id = DEFAULT_DETAIL;
  document.body.classList.toggle('split', split);
  $$('.page').forEach((p) => { p.hidden = p.id !== 'p-' + id && !(split && p.id === 'p-main'); p.classList.remove('enter', 'back'); });
  $$('#p-main [data-go]').forEach((b) => b.classList.toggle('current', split && b.dataset.go === id)); // 右に出している設定の行の印
  curPage = id;
  if (ON_SHOW[id]) ON_SHOW[id]();
  paint(true); // 開いたとき: この画面の中身 (トップなら重い値も) を読み込む
  const p = $('p-' + id);
  if (dir) { void p.offsetWidth; p.classList.add(dir); }
  window.scrollTo(0, dir === 'back' ? scrollAt[id] || 0 : 0); // 戻ったときは、出ていったときの位置へ (詳細から戻って、一覧の先頭に戻されない)
}
// fromMain = 左の一覧 (main) の行を押した。2 列表示で、右にふつうの詳細を出しているときに一覧の別の行を押したら、積まずに置き換える
// (積むと「戻る」で、開いた順に 1 つずつ戻ることになり、設定から出るまでに何度も押す。Android 標準の設定の 2 列表示と同じ動き)
function go(id, fromMain) {
  scrollAt[curPage] = window.scrollY;
  const cur = $('p-' + curPage);
  const replace = fromMain && SPLIT.matches && cur && !cur.querySelector('.nav.sub');
  if (replace) history.replaceState({ page: id }, ''); else history.pushState({ page: id }, '');
  show(id, 'enter');
}
window.addEventListener('popstate', (e) => { closeConfirm(); show((e.state && e.state.page) || 'main', 'back'); });
SPLIT.addEventListener('change', () => show(curPage)); // 回転・ウィンドウの大きさの変更で、1 列 ⇄ 2 列を切り替える

// 詳細画面の上のナビゲーション (‹ 設定 / 見出し)
// 版ごとに足される画面 (<section class="page" data-title="見出し" data-back="戻る先の名前">) も見出しを持つ
$$('.page[data-title]').forEach((p) => { TITLES[p.id.slice(2)] = p.dataset.title; });
Object.keys(TITLES).forEach((id) => {
  const nav = el('div', 'nav');
  const backTo = id.startsWith('rank-') ? '行き先' : $('p-' + id).dataset.back ? $('p-' + id).dataset.back : id === 'names' ? 'ミュート' : '設定';
  if (backTo !== '設定') nav.classList.add('sub'); // 設定の直下でないページ (さらに中のページ)。2 列表示でも、戻るボタンを出す
  const back = el('button', 'back'); back.innerHTML = BACK + '<span>' + backTo + '</span>'; back.onclick = () => history.back();
  const h = el('h2', '', TITLES[id]); h.setAttribute('aria-level', '1'); // 各ページの見出し (ページに h1 が 1 つ)
  nav.append(back, h, el('span'));
  $('p-' + id).prepend(nav);
});
// トップの行: アイコンと「›」
$$('[data-icon]').forEach((s) => (s.innerHTML = svg(s.dataset.icon)));
$$('[data-go], [data-names], [data-system], #offlineLink, #historyLink, #importBtn, [data-chev]').forEach((b) => b.insertAdjacentHTML('beforeend', CHEV));
$$('[data-names]').forEach((b) => (b.onclick = () => openNames(b.dataset.names)));
$$('[data-go]').forEach((b) => (b.onclick = () => go(b.dataset.go, !!b.closest('#p-main'))));
// Android の設定画面へ (アプリ内ページからだけ呼べる NHStore.openSystem)
$$('[data-system]').forEach((b) => (b.onclick = () => window.NHStore && NHStore.openSystem && NHStore.openSystem(b.dataset.system)));


