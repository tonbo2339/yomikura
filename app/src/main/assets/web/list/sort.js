// list.html: 一覧の並べ替え
const arrowsOn = (d) => NH.flag(d, 'listArrows');
const swipeOn = (d) => NH.flag(d, 'listSwipe'); // 横スワイプで削除 (既定はオン)

// 一覧の並べ替え (表示の順だけ。手動の並び (▲▼ で動かした順) は残す)。手動以外のときは ▲▼ を出さない
const SORTS = [['manual', '自分で並べた順'], ['updated', '更新が新しい順'], ['unread', '未読が多い順'], ['title', '名前順'], ['site', 'サイト順']];
const SORT_KEY = {
  updated: (x) => -(x.urlAt || x.addedAt || 0), // 並べ替えの値の関数は (作品, 保存データ)
  unread: (x, d) => -(NH.readState(d, x).unread || 0), // アプリで読んだ分も引いた未読の話数 (d = 保存データ。比べるたびに読み込まない)
  title: (x) => x.title || '',
  site: (x) => NH.siteLabel(x),
};
const COLLATOR = new Intl.Collator('ja'); // 名前順・サイト順の文字比較 (比べるたびに localeCompare で照合の設定を作らない)
const sortMode = (d) => (SORT_KEY[d.settings && d.settings.listSort] ? d.settings.listSort : 'manual');
// 同じ値の作品は手動の順のまま (Array.prototype.sort は安定)
function sortItems(items, mode) {
  const k = SORT_KEY[mode];
  if (!k) return items;
  // 並べ替えの値は、作品ごとに 1 回だけ求める (比べるたびに求めると、「未読が多い順」は、比べるたびに保存データを読み込んで、作品が増えると遅い)
  const d = load();
  return items.map((x) => [k(x, d), x])
    .sort((a, b) => (typeof a[0] === 'string' ? COLLATOR.compare(a[0], b[0]) : a[0] - b[0]))
    .map((p) => p[1]);
}

// 読書位置が一番新しい作品 (途中まで、または読み終えた話の次がある作品)。無ければ null
