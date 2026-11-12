// 読書ページ (本文) の背景色の表。サイトのページの描画前 (early.js) と設定の画面で共通に使う (どちらも、ここだけを見る)。
// 設定の readBg の値: 'site' (サイトのまま。既定) / 下の key。背景・文字・リンクは組にして選ぶ (背景だけ変えると暗い色で字が読めなくなるため)。
// ページの地 (html, body) と本文は、この CSS が描画前に変える。そのほかの白い面と、その上の文字・リンクは、ui/ui-readbg.js が
// data-nh-surface / data-nh-ink / data-nh-link の印を付け、この CSS がその印の色を決める。
// ロジックは持たない (描画前に動くので NH は無い)。二重に読まれても同じ値を作るだけ。
window.NH_READBG = (function () {
  const SITE = 'site';
  const P = ':root:not(#_):not(#_) ';
  // key, 表示名, 背景, 文字, リンク ('' = サイトの色のまま。背景が明るい組はサイトの青で読めるので、暗い組だけ明るい青にする)
  const LIST = [
    ['white', '白', '#ffffff', '#222222', ''],
    ['cream', '生成り', '#f5ecd7', '#3b3226', ''],
    ['gray', '薄い灰色', '#e6e6e6', '#222222', ''],
    ['dark', '濃い灰色', '#2b2b2b', '#d6d6d6', '#8ab4f8'],
    ['black', '黒', '#000000', '#c8c8c8', '#8ab4f8'],
  ];
  const find = (key) => LIST.find((x) => x[0] === key) || null;
  // 設定の画面の選択肢 ([[key, 表示名], …]。先頭は「サイト標準」)
  const choices = () => [[SITE, 'サイト標準']].concat(LIST.map((x) => [x[0], x[1]]));
  // body = sites.js の body ({text: 本文の文字})。サイト標準・不明な key は空
  const css = (key, body) => {
    const c = find(key);
    if (!c || !body || !body.text) return '';
    return 'html,body{background:' + c[2] + '!important}' + body.text + '{color:' + c[3] + '!important}' +
      // 印の規則は、サイトの CSS (ID 付きの規則・!important) に負けないよう、:not(#_) で選択子の強さを上げる (_ という id は使われない)
      P + '[data-nh-surface]{background-color:' + c[2] + '!important}' + P + '[data-nh-ink]{color:' + c[3] + '!important}' +
      (c[4] ? P + '[data-nh-link]{color:' + c[4] + '!important}' : '');
  };
  // 設定の画面の見本用 ({bg, fg}。サイト標準は null)
  const colors = (key) => { const c = find(key); return c ? { bg: c[2], fg: c[3] } : null; };
  return { SITE, choices, css, colors };
})();

// 本文の見た目 (文字の大きさ・行間・書体) の範囲と選択肢。設定の「本文の見た目」と、話ページの TT (表示の設定。ui/ui-viewer.js) が、同じ表を見る。
// ロジックは持たない (描画前の early.js が読むことも無いが、このファイルを読む場所すべてで同じ値になるよう、ここに置く)。
window.NH_TEXTLOOK = (function () {
  const SIZE = { min: 80, max: 250, step: 5, def: 100 }; // 文字の大きさ (%)。100% は端末の標準の大きさ
  // 行間のスライダー: 位置 std = 標準 (設定の lh = 0)、それより右は 1.2〜3.0 (位置 12〜30 = 値 × 10)。オフラインの読み画面の行の間隔 (1.2〜3.0) と同じ範囲
  const LH = { std: 11, max: 30 };
  const lhPos = (v) => (v ? Math.round(v * 10) : LH.std);
  const lhFromPos = (n) => (n <= LH.std ? 0 : Math.min(LH.max, n) / 10);
  const lhLabel = (v) => (v ? Number(v).toFixed(1) : '標準');
  const FONTS = [['site', 'サイト標準'], ['gothic', 'ゴシック'], ['mincho', '明朝']]; // 自分で入れたフォントは、設定の画面で足す
  return { SIZE, LH, lhPos, lhFromPos, lhLabel, FONTS };
})();
