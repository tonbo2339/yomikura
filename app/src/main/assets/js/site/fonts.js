// 本文の書体の表。サイトのページの描画前 (early.js)・設定の画面・オフラインの読む画面で共通に使う (どれも、ここだけを見る)。
// 設定の font の値: 'site' (サイトのまま) / 'gothic' / 'mincho' / 'custom:<12 桁の 16 進>' (自分で入れたフォント。ファイルはアプリの中にあり、
// アプリが https://yomikura.local/font/<12 桁> で返す (アプリ内ページ用)。サイトのページは /font/current (いま選んでいるフォント) で読む)。
// ロジックは持たない (描画前に動くので NH は無い)。二重に読まれても同じ値を作るだけ。
window.NH_FONTS = (function () {
  const BASE = 'https://yomikura.local/font/';
  const CUSTOM = /^custom:([0-9a-f]{12})$/;
  const PRESETS = {
    gothic: '"Noto Sans CJK JP","Noto Sans JP",sans-serif',
    mincho: '"Noto Serif CJK JP","Noto Serif JP",serif',
  };
  // 入れたフォントに無い字 (記号・難しい漢字) は、ゴシックで補う
  const family = (font) => PRESETS[font] || (CUSTOM.test(font) ? '"nh-custom",' + PRESETS.gothic : '');
  // 入れたフォントを読む @font-face (入れたフォントでなければ空)。読み込み中は仮の字体で出し、読めたら替える。
  // forSite = サイトのページ (ページの JS から見える) 用: 端末ごとの id を見せず、アプリが「いま選んでいるフォント」を返す /font/current で読む
  const face = (font, forSite) => {
    const m = CUSTOM.exec(font);
    return m ? '@font-face{font-family:"nh-custom";src:url("' + BASE + (forSite ? 'current' : m[1]) + '");font-display:swap}' : '';
  };
  const idOf = (font) => { const m = CUSTOM.exec(font); return m ? m[1] : ''; };
  return { PRESETS, family, face, idOf, valueOf: (id) => 'custom:' + id };
})();
