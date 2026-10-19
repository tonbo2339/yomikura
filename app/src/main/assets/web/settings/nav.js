// ---- 画面の移動 (トップ ⇄ 詳細)。Android の「戻る」でも戻れるよう履歴に積む ----
// 開いたときに読み込む画面。版ごとに足される画面は、自分で登録する
const ON_SHOW = { names: () => paintNamesPage() };
const EDITION_PAGES = new Set(['main']); // EDITION_PAINT を呼ぶ画面の id (その版が差し込む画面は、自分で足す)
const EDITION_PAINT = []; // 版ごとに足される画面の、paint() での表示の更新 (その版が差し込む JS が登録する)
let curPage = 'main'; // いま開いている画面。paint() はこの画面の分だけ描く
function show(id, dir) {
  $$('.page').forEach((p) => { p.hidden = p.id !== 'p-' + id; p.classList.remove('enter', 'back'); });
  curPage = id;
  if (ON_SHOW[id]) ON_SHOW[id]();
  paint(true); // 開いたとき: この画面の中身 (トップなら重い値も) を読み込む
  const p = $('p-' + id);
  if (dir) { void p.offsetWidth; p.classList.add(dir); }
  window.scrollTo(0, 0);
}
function go(id) { history.pushState({ page: id }, ''); show(id, 'enter'); }
window.addEventListener('popstate', (e) => { closeConfirm(); show((e.state && e.state.page) || 'main', 'back'); });

// 詳細画面の上のナビゲーション (‹ 設定 / 見出し)
// 版ごとに足される画面 (<section class="page" data-title="見出し" data-back="戻る先の名前">) も見出しを持つ
$$('.page[data-title]').forEach((p) => { TITLES[p.id.slice(2)] = p.dataset.title; });
Object.keys(TITLES).forEach((id) => {
  const nav = el('div', 'nav');
  const back = el('button', 'back'); back.innerHTML = BACK + '<span>' + (id.startsWith('rank-') ? '行き先' : $('p-' + id).dataset.back ? $('p-' + id).dataset.back : id === 'names' ? 'ミュート' : '設定') + '</span>'; back.onclick = () => history.back();
  const h = el('h2', '', TITLES[id]); h.setAttribute('aria-level', '1'); // 各ページの見出し (ページに h1 が 1 つ)
  nav.append(back, h, el('span'));
  $('p-' + id).prepend(nav);
});
// トップの行: アイコンと「›」
$$('[data-icon]').forEach((s) => (s.innerHTML = svg(s.dataset.icon)));
$$('[data-go], [data-names], [data-system], #offlineLink, #historyLink, #importBtn, [data-chev]').forEach((b) => b.insertAdjacentHTML('beforeend', CHEV));
$$('[data-names]').forEach((b) => (b.onclick = () => openNames(b.dataset.names)));
$$('[data-go]').forEach((b) => (b.onclick = () => go(b.dataset.go)));
// Android の設定画面へ (アプリ内ページからだけ呼べる NHStore.openSystem)
$$('[data-system]').forEach((b) => (b.onclick = () => window.NHStore && NHStore.openSystem && NHStore.openSystem(b.dataset.system)));

// チェックマークで選ぶ 1 行 (選んでいる行に ✓ と aria-pressed を付ける)。sub は 2 行目の小さい説明。設定のどの選択の一覧もこれで作る
function checkRow(label, on, onClick, sub) {
  const c = setPressed(el('button', 'cell'), on);
  const t = el('span', 'text', label);
  if (sub) t.appendChild(el('small', '', sub));
  c.appendChild(t);
  if (on) c.insertAdjacentHTML('beforeend', CHECK);
  c.onclick = onClick;
  return c;
}

// チェックマークで 1 つ選ぶ一覧 (iOS の選択リスト)
function pickList(box, items, current, onPick) {
  box.innerHTML = '';
  items.forEach(([k, label]) => {
    box.appendChild(checkRow(label, String(k) === String(current), () => onPick(k)));
  });
}

// 行の下に出す、横に並んだ 1 つ選ぶボタン (少ない選択肢を、別の画面に分けずに切り替える)
// 選んでいる印 (白い丸みの部分) は、ボタンを作り直しても残る 1 つの要素 (.seg-thumb) で、選び直すと次のボタンへ滑る
function segPick(box, items, current, onPick) {
  box.querySelectorAll('button').forEach((b) => b.remove());
  items.forEach(([k, label]) => {
    const b = setPressed(el('button', String(k) === String(current) ? 'on' : '', label), String(k) === String(current));
    b.onclick = () => onPick(k);
    box.appendChild(b);
  });
  let thumb = box.querySelector('.seg-thumb');
  const first = !thumb;
  if (first) { thumb = el('i', 'seg-thumb'); thumb.hidden = true; box.prepend(thumb); box.classList.add('slide'); }
  const place = () => {
    const on = box.querySelector('button.on');
    if (!on || !on.offsetWidth) return; // 隠れている画面では測れない (見えたときに ResizeObserver が置き直す)
    thumb.style.width = on.offsetWidth + 'px';
    thumb.style.transform = 'translateX(' + on.offsetLeft + 'px)';
    thumb.hidden = false;
  };
  if (first) thumb.classList.add('instant'); // 最初の位置へは滑らず、すぐ置く
  place();
  if (first) {
    requestAnimationFrame(() => requestAnimationFrame(() => thumb.classList.remove('instant')));
    let lastW = box.offsetWidth; // 幅が変わったとき (画面が見えた・回転した) だけ、滑らずに置き直す。高さだけ変わる (操作部分の大きさ) ときは滑らせる
    if (window.ResizeObserver) new ResizeObserver(() => {
      if (box.offsetWidth === lastW) return;
      lastW = box.offsetWidth;
      thumb.classList.add('instant'); place();
      requestAnimationFrame(() => requestAnimationFrame(() => thumb.classList.remove('instant')));
    }).observe(box);
  }
}
