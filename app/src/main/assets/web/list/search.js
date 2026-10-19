// list.html: 検索 (虫眼鏡で開く)
// ---- 検索 (虫眼鏡で開く。閉じると、言葉を消して元の表示に戻る) ----
$('searchBtn').innerHTML = svg('search');
$('searchIcon').innerHTML = '<path d="' + ICON_PATHS.search + '"/>';
$('qClose').innerHTML = svg('close');
function openSearch() {
  $('searchBar').hidden = false;
  $('searchBtn').classList.add('on');
  $('q').focus();
}
function closeSearch() {
  runSearch.cancel();
  $('searchBar').hidden = true;
  $('searchBtn').classList.remove('on');
  $('q').value = '';
  if (ui.q) { ui.q = ''; render(); }
}
const runSearch = debounce(() => { ui.q = $('q').value; render(); }, 120); // 打つたびに全体を描き直さない
$('searchBtn').onclick = () => ($('searchBar').hidden ? openSearch() : closeSearch());
$('qClose').onclick = closeSearch;
$('q').addEventListener('input', runSearch);
$('q').addEventListener('keydown', (e) => { if (e.key === 'Escape') closeSearch(); if (e.key === 'Enter') { runSearch.flush(); $('q').blur(); } });
