// ---- 自分で入れたフォント (設定の「本文の文字」) ----
// ファイルと名前の一覧の持ち主はアプリ側 (NHFonts。端末のファイルを選ぶ画面を使う)。設定の font には 'custom:<id>' だけを置く (書体の表は site/fonts.js)。
// バックアップから戻した設定のフォントがアプリに無いときは、サイト標準と同じ表示になる (CSS の代わりの字体)。
let CUSTOM_FONTS = []; // [{id, name}]
let FONTS_KNOWN = false; // 一覧を読めたか (読めなかったときは、設定の font を直さない)
function loadFonts() {
  try { CUSTOM_FONTS = JSON.parse(window.NHFonts ? NHFonts.list() : '[]'); FONTS_KNOWN = !!window.NHFonts; } catch (e) { CUSTOM_FONTS = []; FONTS_KNOWN = false; }
}
// アプリに無いフォントが選ばれているとき (バックアップから戻した設定・入れ直したあと) は、サイト標準に戻す。
// 戻さないと、設定の画面はサイト標準と見せるのに、サイトの本文だけゴシックになる
function repairFont() {
  if (!FONTS_KNOWN || !NH_FONTS.idOf(S.font) || CUSTOM_FONTS.some((f) => NH_FONTS.valueOf(f.id) === S.font)) return;
  S.font = 'site';
  NH.update((d) => { if (d.settings) d.settings.font = 'site'; });
}
const fontNames = () => FONT_NAMES.concat(CUSTOM_FONTS.map((f) => [NH_FONTS.valueOf(f.id), f.name]));
const fontLabel = (font) => nameOf(fontNames(), font) || nameOf(FONT_NAMES, 'site');

// 設定の画面の中でフォントを見せる (プレビュー) ための @font-face
function paintFontFace() {
  let st = document.getElementById('nhFontFace');
  if (!st) { st = document.createElement('style'); st.id = 'nhFontFace'; document.head.appendChild(st); }
  st.textContent = NH_FONTS.face(S.font);
}

// 追加したフォントごとに、名前の変更 (入力欄 + 保存) と削除。初めの名前はフォントの中の名前
function paintFontManage() {
  const box = $('fontDelList');
  box.innerHTML = '';
  CUSTOM_FONTS.forEach((f) => {
    const row = el('div', 'cell');
    row.style.cssText = 'gap:10px;padding:10px 16px';
    const inp = el('input', 'field');
    inp.type = 'text'; inp.maxLength = 40; inp.value = f.name; inp.setAttribute('aria-label', '「' + f.name + '」の名前');
    inp.style.cssText = 'flex:1;min-width:0';
    const save = el('button', 'btn primary', '保存');
    save.setAttribute('aria-label', '「' + f.name + '」の名前を保存');
    save.onclick = () => renameFont(f, inp.value);
    inp.onkeydown = (e) => { if (e.key === 'Enter') { e.preventDefault(); renameFont(f, inp.value); } };
    const del = el('button', 'btn danger', '削除');
    del.setAttribute('aria-label', '「' + f.name + '」を削除');
    del.onclick = () => ask('「' + f.name + '」を削除します。', '削除', () => removeFont(f));
    row.append(inp, save, del);
    box.appendChild(row);
  });
  $('fontDelGroup').hidden = !CUSTOM_FONTS.length;
}

function renameFont(f, name) {
  name = name.trim();
  if (!name) return snack('名前を入れてください');
  if (name === f.name) return snack('いまと同じ名前です');
  if (!(window.NHFonts && NHFonts.rename(f.id, name))) return snack('名前を変えられませんでした');
  loadFonts();
  paint();
  snack('名前を変えました');
}

function removeFont(f) {
  if (!(window.NHFonts && NHFonts.remove(f.id))) return snack('削除できませんでした');
  if (S.font === NH_FONTS.valueOf(f.id)) S.font = 'site';
  loadFonts();
  commit();
  snack('削除しました');
}

$('fontAdd').onclick = () => { if (window.NHFonts) NHFonts.pick(); else snack('この画面では追加できません'); };
// 追加の結果 (アプリから)。追加できたら、そのフォントにする
window.nhFontResult = (json) => {
  const r = JSON.parse(json);
  loadFonts();
  if (r.ok && r.id) { S.font = NH_FONTS.valueOf(r.id); commit(); } else paint();
  snack(r.message);
};

// 画面を開くたびに読み直す (追加の結果が、ページが読み込まれる前に届いて見えなかったときも、ここで見える)
ON_SHOW.text = loadFonts;

loadFonts();
repairFont();
