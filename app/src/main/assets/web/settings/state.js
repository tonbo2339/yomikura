// サイトの表 (SITES / RANK_SITES / ACCOUNTS / COLLECT_SITES / TAB_COLOR_DEFAULT) は app.js
const { load, SITE_DEFAULT } = NH; // 保存データの読み書きは core.js と共通 (書くときは NH.update)
const DEFAULTS = { theme: 'auto', font: 'site', size: 100, lh: 0, start: 'list', startSite: 'narou', autoCheck: true, autoCheckHours: 3, listArrows: true, dimRead: true, pruneDone: true, pruneDays: 30, historyMax: 300, historyDays: 0, sites: Object.assign({}, SITE_DEFAULT) };
const FONTS = { site: 'inherit', gothic: '"Noto Sans CJK JP","Noto Sans JP",sans-serif', mincho: '"Noto Serif CJK JP","Noto Serif JP",serif' };
const R18_MODES = [['menu', '「なろう」を素早く 2 回タップ'], ['tabs', '上部バーに、サイト名のタブで出す']];
const START = [['ranking', 'ランキング'], ['top', 'サイトのトップ'], ['list', 'リスト'], ['last', '最後に開いていたページ']];
const DENSITIES = [['compact', '小さめ'], ['standard', '標準'], ['large', '大きめ']];
const THEMES = [['auto', '自動'], ['light', 'ライト'], ['dark', 'ダーク']];
const FONT_NAMES = [['site', 'サイト標準'], ['gothic', 'ゴシック'], ['mincho', '明朝']];
const HOURS = [1, 3, 6, 12];
const PRUNE_DAYS = [7, 30, 90];
const HISTORY_MAX = [[100, '100 作品'], [300, '300 作品'], [1000, '1000 作品'], [0, '無制限']];
const HISTORY_DAYS = [[30, '30 日'], [90, '90 日'], [180, '半年'], [365, '1 年'], [0, '無期限']];
// アイコンの選択肢のキー (並びは Kotlin の LauncherIcon.ICONS と同じ。名前は付けず、画像 icons/<キー>.svg だけを並べる)
const LAUNCHER = ['paper', 'green', 'ocean', 'purple', 'sakura', 'aigold', 'yuyake', 'matcha', 'night', 'dusk', 'wood', 'sea'];
const TITLES = { names: '', bg: '裏での確認と通知', bar: '上部バー', history: '閲覧履歴', accounts: 'ログイン', data: '初期化と消去', diag: '診断情報', privacy: 'プライバシー', licenses: 'オープンソースライセンス', stats: '読書の統計', reading: '読書の補助', export: '書き出す', start: '起動ページ', update: 'アプリの更新', auto: '更新の確認', list: 'リスト', fold: '話ページの下の欄', text: '本文の文字', icon: 'アプリのアイコン', sites: '表示するサイト', mute: 'ミュート・ブロック・既読', ranking: 'ランキングの行き先', 'rank-narou': 'なろう', 'rank-hameln': 'ハーメルン', 'rank-kakuyomu': 'カクヨム', 'rank-noc': 'ノクターン', 'rank-mnlt': 'ムーンライト', 'rank-mid': 'ミッドナイト' };
const MUTE_MODES = [['hide', '隠す'], ['dim', '薄く表示']];

const CHEV = '<svg class="chev" viewBox="0 0 8 13"><path d="M1.5 1.5 6.5 6.5 1.5 11.5" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>';
const CHECK = '<svg class="check" viewBox="0 0 24 24"><path d="M9 16.17 4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z"/></svg>';
const BACK = '<svg viewBox="0 0 12 20"><path d="M10 2 2 10l8 8" fill="none" stroke="currentColor" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"/></svg>';

let S = Object.assign({}, DEFAULTS, load().settings);
S.sites = Object.assign({}, SITE_DEFAULT, S.sites);
// 行間: 0 = 標準、それ以外は 1.2〜3.0。以前の版が保存した 0.1〜1.1 などは標準に戻す (サイトの文字が潰れるため)
if (S.lh && !(S.lh >= 1.2 && S.lh <= 3)) {
  S.lh = S.lh > 3 ? 3 : 0;
  NH.update((d) => { if (d.settings) d.settings.lh = S.lh; });
}
// ランキングの行き先と連載状態の絞り込みは、アプリ (メニュー) やサイト上の画面からも書き換わるので S には持たず、毎回保存データを読み書きする。
// アイコンの「後で」の予約 (launcherIconPending) も、この画面を開いている間にアプリが書くので同じ (S から書き戻すと、予約が消える)
const LIVE_KEYS = ['rankings', 'statusFilter', 'launcherIconPending'];
LIVE_KEYS.forEach((k) => delete S[k]);
// 表示するサイト = 選んでいて、成人向けのサイトは使えるとき (18 歳以上で NSFW モードがオフ) だけ。上部バー・起動ページ・更新の確認と同じ判断 (Kotlin の siteOn)。
// 選んだ値 (S.sites) は、NSFW モードをオンにしても消さない (オフに戻したら、また表示される)
const siteOn = (k) => !!S.sites[k] && (!NH.R18_SITES.includes(k) || r18Usable());
const nameOf = (list, v) => (list.find(([k]) => k === v) || [, ''])[1];

// 保存。commit() はすぐ保存して描き直す。commitSoon() は入力中 (スライダー・文字) の連続した変更用で、画面はすぐ直し、保存は 150ms まとめる (閉じる前に必ず保存)
let commitTimer = 0;
function writeSettings() {
  clearTimeout(commitTimer); commitTimer = 0;
  NH.update((d) => {
    const live = {};
    LIVE_KEYS.forEach((k) => { if (d.settings && d.settings[k] !== undefined) live[k] = d.settings[k]; });
    d.settings = Object.assign({}, S, live);
  });
}
function commit(heavy) { writeSettings(); paint(heavy === true); } // heavy = トップの重い値 (オフラインの数・ログイン) も読み直す
function commitSoon() {
  paint();
  clearTimeout(commitTimer);
  commitTimer = setTimeout(writeSettings, 150);
}
const flushCommit = () => { if (commitTimer) writeSettings(); };
window.addEventListener('pagehide', flushCommit);
document.addEventListener('visibilitychange', () => { if (document.hidden) flushCommit(); });
