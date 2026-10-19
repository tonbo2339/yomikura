// offline.html: 画面の状態 (開く作品・読む元)・戻る・エラー・設定の読み書き
// オフライン保存した作品を、一覧 → 目次 → 本文の順に開く画面。
// ?w=<site:id> で作品の目次、?w=...&ep=<話> で本文。保存と削除は端末の内部ストレージ (OfflineStore)。
// ?src=<EXT.srcName> なら、端末ではなく外の作品を読む (版ごとに足される機能。EXT の登録は、その版が差し込む JS)。
const { load } = NH;
const q = new URLSearchParams(location.search);
const wkey = q.get('w');
const root = $('app');
const date = (t) => new Date(t).toLocaleDateString('ja-JP', { month: 'numeric', day: 'numeric' });
const go = (url) => location.replace(url);
// 一覧へ移ってからお知らせを出す (移るとお知らせが消えるので、控えて移った先で出す)
const goWithFlash = (url, text) => { flashNext(text); go(url); };
// 版ごとに足される機能の差し込み口。登録が無い版は、端末の作品だけ
//   srcName = ?src= がこの値なら外の作品を読む / source = その読み元 {meta, episode} / srcLabel = 保存先の名前 (「N 話を○○に保存」)
//   rowButtons(w) = 一覧の端末の作品の行に足すボタン / section() = 一覧の下に足す欄 (無ければ null)
//   tocButtons(site, id, title) = 端末の作品の目次に足すボタン / remoteTocButtons = 外の作品の目次のボタン / onOpen() = 開いたときの処理
const EXT = { srcName: '', source: null, srcLabel: '', rowButtons: () => [], section: () => null, tocButtons: () => [], remoteTocButtons: () => [], onOpen: () => {} };
const SRC = EXT.srcName && q.get('src') === EXT.srcName ? EXT.srcName : '';
// 読む元。どちらも Promise を返す
const source = SRC && EXT.source ? EXT.source
  : { meta: async (site, id) => NH.off.meta(site, id), episode: async (site, id, ep) => NH.off.episode(site, id, ep) };
const here = (w, ep, src = SRC) => 'offline.html?w=' + encodeURIComponent(w) + (ep != null ? '&ep=' + encodeURIComponent(ep) : '') + (src ? '&src=' + src : '');

// 上の帯の「‹ オフライン」(一覧へ戻る)
function backNav() {
  const nav = el('div', 'topnav');
  const bk = el('button'); bk.innerHTML = svg('back') + '<span>オフライン</span>'; bk.onclick = () => go('offline.html');
  nav.appendChild(bk);
  return nav;
}
// 読めなかったとき (外の作品につながらないなど)
function showError(text) {
  root.innerHTML = '';
  const e = el('div', 'empty'); e.append(el('h2', '', '開けませんでした'), el('p', '', text));
  root.append(backNav(), e);
}
const settings = () => load().settings || {};
const setSetting = NH.setSetting;

// ---- 確認シート (削除など): ask(見出し, 実行の名前, 実行, 説明) と closeConfirm() は app.js ----
