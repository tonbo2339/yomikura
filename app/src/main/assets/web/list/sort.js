// list.html: 一覧の並べ替え
const arrowsOn = (d) => NH.flag(d, 'listArrows');

// 一覧の並べ替え (表示の順だけ。手動の並び (▲▼ で動かした順) は残す)。手動以外のときは ▲▼ を出さない
const SORTS = [['manual', '自分で並べた順'], ['updated', '更新が新しい順'], ['unread', '未読が多い順'], ['title', '名前順'], ['site', 'サイト順']];
const SORT_KEY = {
  updated: (x) => -(x.urlAt || x.addedAt || 0),
  unread: (x) => -(NH.readState(load(), x).unread || 0), // アプリで読んだ分も引いた未読の話数
  title: (x) => x.title || '',
  site: (x) => NH.siteLabel(x),
};
const sortMode = (d) => (SORT_KEY[d.settings && d.settings.listSort] ? d.settings.listSort : 'manual');
// 同じ値の作品は手動の順のまま (Array.prototype.sort は安定)
function sortItems(items, mode) {
  const k = SORT_KEY[mode];
  if (!k) return items;
  return items.slice().sort((a, b) => {
    const x = k(a), y = k(b);
    return typeof x === 'string' ? x.localeCompare(y, 'ja') : x - y;
  });
}

// 読書位置が一番新しい作品 (途中まで、または読み終えた話の次がある作品)。無ければ null
