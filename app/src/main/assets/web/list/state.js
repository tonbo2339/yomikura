// list.html: 画面の状態 (タブ・絞り込み・検索) と、検索の一致の判定
const PRESET_TAGS = ['異世界', '現代', '恋愛', 'ラブコメ', 'ホラー', 'ミステリー', 'SF', '歴史', 'その他'];
// 保存データの読み書きと「続きから」の規則は core/ (サイト上の注入スクリプトと共通)
const { load, resumeUrl, kindOf } = NH;

const ui = { tab: null, site: '', tag: '', status: '', q: '', src: 'all', sel: null }; // sel = 選択モードで選んだ作品の key の集合 (select.js)
// 連載状態の絞り込み (作品の status。ランキング・検索の一覧と作品ページから分かったものだけ。分からない作品は「すべて」にだけ出る)
const STATUS_CHIPS = [['serial', '連載中'], ['complete', '完結'], ['short', '短編']];
const STATUS_NAME = Object.fromEntries(STATUS_CHIPS);
// 検索: 全角・半角と大文字・小文字をそろえ、空白で区切った言葉がすべて含まれる作品だけ
const norm = NH.norm;
const queryTerms = () => norm(ui.q).split(' ').filter(Boolean);
function matchQuery(it, terms) {
  if (!terms.length) return true;
  const hay = norm([it.title, it.memo, it.tag, NH.siteLabel(it), STATUS_NAME[it.status]].join(' '));
  return terms.every((t) => hay.includes(t));
}
try { ui.tab = localStorage.getItem('nh-tab'); } catch (e) {}
if (location.hash === '#history') ui.tab = 'history'; // 設定の「閲覧履歴」からは履歴のタブを開く
