// ---- 設定の検索 (トップの上の枠) ----
// 設定の文字は、すべて、この HTML の中にある (開いていない画面は hidden)。入力した言葉を、各画面の見出し・項目名・説明から探して、一覧にする。
// 押すと、その画面へ移って、その項目までスクロールして、少しのあいだ光らせる。
// 見えない言い換え (タグ): 行・見出しに data-kw="スリープ 寝る" と付けておくと、見える名前に無い言葉でも見つかる (結果の見た目は、いつもの項目名のまま)。
// 索引は、入力のたびに、画面から作る (項目を足したときに、別の一覧を直さなくてよい)。いま隠れている項目 (条件で出ない欄) は、結果に出さない。
const SEARCH_KINDS = '.cell > .text, .section-label, .hint, .slider .name, .update-card .t1';
const searchBox = $('setSearch');
const searchOut = $('searchResults');

// 画面ごとの名前 (結果に「どの画面か」を添える)。トップの行の名前は、その行が開く画面の見出しとして使う
function pageTitleOf(page) {
  const id = page.id.slice(2);
  if (TITLES[id]) return TITLES[id];
  const row = $('p-main').querySelector('[data-go="' + id + '"] .text');
  return row ? row.textContent : '';
}
// 項目が、いま画面に出せるか (自分と親が hidden でない。画面 (.page) そのものは、開いていなくても hidden なので数えない)
function searchShowable(node, page) {
  for (let n = node; n && n !== page; n = n.parentElement) if (n === searchOut || (n.hidden && n.id !== 'mainList')) return false; // 結果の欄 (前の結果) 自身は、探さない // mainList は、検索中に自分で隠している (中の項目は、出せる)
  return true;
}
// 検索の対象 [{ node, page, label, kw, kind }] (kw = 見えないタグ)。ラベルが空のものは除く
function searchIndex() {
  const items = [];
  $$('.page').forEach((page) => {
    page.querySelectorAll(SEARCH_KINDS).forEach((node) => {
      const label = (node.textContent || '').replace(/\s+/g, ' ').trim();
      if (!label || !searchShowable(node, page)) return;
      const holder = node.closest('[data-kw]');
      items.push({ node, page, label, kw: holder && page.contains(holder) ? holder.dataset.kw : '', kind: node.classList.contains('hint') ? 'hint' : 'label' });
    });
  });
  return items;
}
const norm = (s) => String(s).toLowerCase().replace(/[ァ-ヶ]/g, (c) => String.fromCharCode(c.charCodeAt(0) - 0x60)); // カタカナもひらがなとして比べる

// 結果。項目名のほうが先 (説明文より)。同じ項目が重ならないよう、同じ画面・同じ文は 1 つ
function searchFind(query) {
  const words = norm(query).split(/\s+/).filter(Boolean);
  if (!words.length) return [];
  const seen = new Set();
  const hits = [];
  searchIndex().forEach((it) => {
    const text = norm(it.label + ' ' + it.kw);
    if (!words.every((w) => text.includes(w))) return;
    const key = it.page.id + '|' + it.label;
    if (seen.has(key)) return;
    seen.add(key);
    hits.push(it);
  });
  hits.sort((a, b) => (a.kind === 'hint') - (b.kind === 'hint'));
  return hits.slice(0, 40);
}

function showResults(query) {
  const typing = query.trim().length > 0;
  $('mainList').hidden = typing;
  searchOut.hidden = !typing;
  if (!typing) { searchOut.textContent = ''; return; }
  const hits = searchFind(query);
  searchOut.textContent = '';
  if (!hits.length) { searchOut.appendChild(el('div', 'hint', '見つかりませんでした')); return; }
  const group = el('div', 'group');
  hits.forEach((it) => {
    const row = el('button', 'cell search-hit');
    const title = pageTitleOf(it.page);
    const text = it.kind === 'hint' && it.label.length > 60 ? it.label.slice(0, 60) + '…' : it.label;
    row.append(el('span', 'text', text), el('span', 'where', title === text ? '' : title));
    row.onclick = () => openHit(it);
    group.appendChild(row);
  });
  searchOut.appendChild(group);
}

// その項目の画面へ移り、項目まで動いて光らせる。トップの行 (data-go) は、その行が開く画面へ
function openHit(it) {
  const id = it.page.id.slice(2);
  const target = it.node;
  searchBox.value = '';
  showResults('');
  const nav = id === 'main' ? target.closest('[data-go]') : null; // トップの行 (開く画面がある行) は、その画面へ
  if (nav) go(nav.dataset.go, true);
  else if (id !== 'main') go(id, true);
  requestAnimationFrame(() => {
    if (nav) return; // 行の先の画面へ移った。その画面の先頭から見せる
    const row = target.closest('.cell, .section-label, .hint') || target;
    row.scrollIntoView({ block: 'center' });
    row.classList.add('search-flash');
    setTimeout(() => row.classList.remove('search-flash'), 1800);
  });
}

searchBox.addEventListener('input', () => showResults(searchBox.value));
searchBox.addEventListener('keydown', (e) => { if (e.key === 'Escape') { searchBox.value = ''; showResults(''); } });
