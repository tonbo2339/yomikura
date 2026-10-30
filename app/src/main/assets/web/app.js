// アプリ内ページ (リスト / 履歴 / 設定) 共通の小さな道具。core/ (NH) の後に読み込む
const $ = (id) => document.getElementById(id);
const $$ = (sel) => document.querySelectorAll(sel);
const el = NH.el; // DOM の要素を作る (core/base.js)

// 右端などに置く、アイコンだけのボタン (label は読み上げ用の名前)。押しても下の行 (カード) は反応しない
function iconBtn(name, label, fn, cls) {
  const b = el('button', 'icon-btn' + (cls ? ' ' + cls : ''));
  b.innerHTML = svg(name); b.setAttribute('aria-label', label);
  b.onclick = (e) => { e.stopPropagation(); fn(); };
  return b;
}

// 画面を移ったあとに出すお知らせ: 移る前に flashNext で控え、移った先で showFlash が出す (移るとお知らせが消えるため)
function flashNext(text) { try { sessionStorage.setItem('nh-flash', text); } catch (e) { /* 出さない */ } }
function showFlash() { try { const f = sessionStorage.getItem('nh-flash'); if (f) { sessionStorage.removeItem('nh-flash'); snack(f); } } catch (e) { /* 出さない */ } }

// 見出し (section-label) と、その下の囲み (group) を box に足して、囲みを返す (設定・オフラインの画面で共通)
function addGroup(box, title) {
  const g = el('div', 'group');
  box.append(el('div', 'section-label', title), g);
  return g;
}

// 保存データ・保存キュー・外部の応答など、データから来た URL を開く。https の URL か、アプリ内のページ (list.html など) だけ開き、
// それ以外 (javascript: など) は開かない (アプリ内ページは端末の操作の窓口を持つので、データ由来の URL でスクリプトを動かさない)。開いたら true
const SAFE_URL = /^(https:\/\/|[a-z][a-z0-9-]*\.html([?#]|$))/i;
function openUrl(url, replace) {
  const u = String(url || '');
  if (!SAFE_URL.test(u) || /[\u0000-\u0020\u007f]/.test(u)) return false;
  if (replace) location.replace(u); else location.href = u;
  return true;
}

// スナックバー (#snack > #snackText, あれば #snackUndo)。undo を渡すと「元に戻す」を出す
let snackTimer = 0;
function hideSnack() {
  clearTimeout(snackTimer);
  const s = $('snack');
  if (s) s.classList.remove('show');
}
function snack(text, undo) {
  $('snackText').textContent = text;
  const u = $('snackUndo');
  if (u) {
    u.hidden = !undo;
    u.onclick = () => { hideSnack(); undo(); };
  }
  $('snack').classList.add('show');
  clearTimeout(snackTimer);
  snackTimer = setTimeout(hideSnack, undo ? 5000 : 2500);
}

// ---- サイトの表 (web ページ共通。設定・はじめに・リストなどで同じ並び・名前・色を使う) ----
// 名前・色・R18 かどうかの元は core/siteinfo.js の NH.SITE_DEFS (narou18 は「R18 全体」を指す印で、ここでは 3 つの掲載サイトに分けて持つ)
const WEB_SITES = NH.SITE_DEFS.filter((x) => x.key !== 'narou18').map((x) => ({ id: x.key, label: x.name, color: x.color, r18: !!x.sub }));
const SITES = WEB_SITES.map((s) => (s.r18 ? [s.id, s.label, true] : [s.id, s.label])); // [id, 名前, R18 なら true]
const RANK_SITES = SITES.map(([k, name]) => [k, name]); // ランキングの行き先を選ぶサイト
const MAIN_SITES = SITES.filter((s) => !s[2]); // R18 でないサイト
const TAB_COLOR_DEFAULT = Object.fromEntries(WEB_SITES.map((s) => [s.id, s.color])); // 上部バーのタブの点の既定色 (Kotlin の BarTheme と同じ)
const R18_SITE_LABELS = WEB_SITES.filter((s) => s.r18).map((s) => s.label).join('・');
// 更新を確認する単位 (R18 は 3 サイトまとめて 1 つ)
const COLLECT_SITES = [['narou', 'なろう'], ['narou18', 'R18 (' + R18_SITE_LABELS + ')'], ['hameln', 'ハーメルン'], ['kakuyomu', 'カクヨム']];
// ログインの画面: [id, 名前, 注記, ログインの URL, 含むサイト, 確認の記録のキー]。なろうは R18 のサイトと共通のログイン
const ACCOUNTS = [
  ['narou', 'なろう', R18_SITE_LABELS + 'も共通です', 'https://syosetu.com/login/input/', ['narou', ...WEB_SITES.filter((s) => s.r18).map((s) => s.id)], ['narou', 'narou18']],
  ['hameln', 'ハーメルン', '', 'https://syosetu.org/?mode=login', ['hameln'], ['hameln']],
  ['kakuyomu', 'カクヨム', '', 'https://kakuyomu.jp/my', ['kakuyomu'], ['kakuyomu']],
];
const siteDot = (k) => (WEB_SITES.some((s) => s.id === k && s.r18) ? 'narou18' : k); // 点の色の class

// ---- 小さな道具 ----
// 連続した呼び出しをまとめる。.flush() で待たずに実行、.cancel() で取り消す
function debounce(fn, ms) {
  let t = 0, args = null;
  const run = () => { t = 0; const a = args; args = null; if (a) fn(...a); };
  const d = (...a) => { args = a; clearTimeout(t); t = setTimeout(run, ms); };
  d.flush = () => { if (t) { clearTimeout(t); run(); } };
  d.cancel = () => { clearTimeout(t); t = 0; args = null; };
  return d;
}
// div などを押せるボタンにする (role・Tab で届く・Enter / Space で押せる)。中の button の操作は拾わない
function asButton(node, fn) {
  node.setAttribute('role', 'button');
  node.tabIndex = 0;
  node.onclick = fn;
  node.addEventListener('keydown', (e) => {
    if (e.target !== node || (e.key !== 'Enter' && e.key !== ' ')) return;
    e.preventDefault();
    fn(e);
  });
  return node;
}
// 「オン / オフ」を押すボタンに aria-pressed を付ける
function setPressed(btn, on) { btn.setAttribute('aria-pressed', on ? 'true' : 'false'); return btn; }

// ---- 設定のスイッチ ----
// <input type="checkbox" data-setting="キー" [data-default="off"]>。data-default が無い (on) ものは「未設定 = オン」(S[キー] !== false)、
// off は「未設定 = オフ」(!!S[キー])。getS() は設定の写しを返す関数 (差し替えられても追えるように)
// スイッチの 1 行 (設定の「スイッチ」の行)。sub は 2 行目の小さい説明。
// 切り替えると onChange(新しい値) を呼ぶ。スイッチ本体は row.cb (無効にする・値を読むときに使う)
function switchRow(label, on, onChange, sub) {
  const row = el('label', 'cell');
  const t = el('span', 'text', label);
  if (sub) t.appendChild(el('small', '', sub));
  const cb = el('input', 'switch'); cb.type = 'checkbox'; cb.checked = !!on;
  cb.onchange = () => onChange(cb.checked);
  row.append(t, cb);
  row.cb = cb;
  return row;
}
function bindToggles(getS, commit) {
  $$('[data-setting]').forEach((cb) => (cb.onchange = () => { getS()[cb.dataset.setting] = cb.checked; commit(); }));
}
function paintToggles(S) {
  $$('[data-setting]').forEach((cb) => {
    const v = S[cb.dataset.setting];
    cb.checked = cb.dataset.default === 'off' ? !!v : v !== false;
  });
}

// ---- シート (下から出る面) とその確認 ----
// sheetShow(シート, 読み上げる名前, 閉じる処理): 開く。画面の読み上げに「ダイアログ」と名前を伝え、フォーカスを移す。
// 外側 (scrim) のタップと Esc は閉じる処理を呼ぶ (省略すると sheetHide)。sheetHide(シート): 閉じて、開く前のフォーカスに戻す
let sheetOpener = null;
function sheetShow(sh, name, onClose) {
  const active = document.activeElement;
  if (!sh.classList.contains('show')) sheetOpener = active && active !== document.body ? active : null;
  sh.setAttribute('role', 'dialog');
  sh.setAttribute('aria-modal', 'true');
  if (name) sh.setAttribute('aria-label', name);
  sh.inert = false;
  sh._close = onClose || (() => sheetHide(sh));
  const sc = $('scrim');
  if (sc) { sc.classList.add('show'); sc.onclick = sh._close; }
  sh.classList.add('show');
  sh.tabIndex = -1;
  try { sh.focus({ preventScroll: true }); } catch (e) { /* フォーカスできない */ }
}
function sheetHide(sh) {
  const was = sh.classList.contains('show');
  sh.classList.remove('show');
  sh.inert = true;
  const sc = $('scrim');
  if (sc) sc.classList.remove('show');
  if (was && sheetOpener && sheetOpener.isConnected) { try { sheetOpener.focus({ preventScroll: true }); } catch (e) { /* 戻せない */ } }
  if (was) sheetOpener = null;
}
document.addEventListener('keydown', (e) => {
  const sh = document.querySelector('.sheet.show');
  if (!sh) return;
  if (e.key === 'Escape') { e.preventDefault(); (sh._close || (() => sheetHide(sh)))(); return; }
  if (e.key !== 'Tab') return;
  // シートの中でだけ Tab を回す
  const items = [...sh.querySelectorAll('a[href], button:not(:disabled), input:not(:disabled), select:not(:disabled), textarea:not(:disabled), [tabindex="0"]')];
  if (!items.length) { e.preventDefault(); return; }
  const first = items[0], last = items[items.length - 1];
  if (!sh.contains(document.activeElement) || (e.shiftKey && (document.activeElement === first || document.activeElement === sh))) { e.preventDefault(); (e.shiftKey ? last : first).focus(); }
  else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
});
// 確認のシート (#confirm があればそれ、無ければ #sheet)。
//   ask(見出し, 実行の名前, 実行, 説明): 説明なし = 画面下のアクションシート (設定)。説明あり = 見出し + 説明 + 「やめる」のダイアログ (オフライン)
function ask(title, action, fn, text) {
  const sh = $('confirm') || $('sheet');
  sh.innerHTML = '';
  if (text == null) {
    sh.appendChild(el('div', 'grip'));
    const g = el('div', 'group');
    g.appendChild(el('div', 'confirm-title', title));
    const ok = el('button', 'cell danger', action); ok.onclick = () => { closeConfirm(); fn(); };
    g.appendChild(ok);
    const cancel = el('button', 'cell cancel group', 'キャンセル'); cancel.onclick = closeConfirm;
    sh.append(g, cancel);
  } else {
    sh.append(el('div', 'grip'), el('h2', '', title), el('p', 'hint', text));
    const row = el('div', 'actions-row');
    const ok = el('button', 'btn danger', action); ok.onclick = () => { closeConfirm(); fn(); };
    const no = el('button', 'btn', 'やめる'); no.onclick = closeConfirm;
    row.append(ok, no); sh.appendChild(row);
  }
  sheetShow(sh, title, closeConfirm);
}
function closeConfirm() { sheetHide($('confirm') || $('sheet')); }
// 2 つの行き先から選ぶシート (例: 今すぐ / 後で)。a・b = [文字, 押したとき]。キャンセルで閉じる
function askChoice(title, text, a, b) {
  const sh = $('confirm') || $('sheet');
  sh.innerHTML = '';
  sh.append(el('div', 'grip'), el('h2', '', title), el('p', 'hint', text));
  const row = el('div');
  const first = el('button', 'btn primary block', a[0]); first.onclick = () => { closeConfirm(); a[1](); };
  const second = el('button', 'btn block', b[0]); second.style.marginTop = '8px'; second.onclick = () => { closeConfirm(); b[1](); };
  row.append(first, second);
  const cancel = el('button', 'cell cancel group', 'キャンセル'); cancel.onclick = closeConfirm;
  sh.append(row, cancel);
  sheetShow(sh, title, closeConfirm);
}
// 読み上げ: スナックバーは状態の知らせ。閉じているシートは操作の対象から外す
(() => {
  const sn = document.getElementById('snack');
  if (sn) { sn.setAttribute('role', 'status'); sn.setAttribute('aria-live', 'polite'); }
  document.querySelectorAll('.sheet').forEach((s) => { s.inert = true; });
})();

// 画面で使うアイコン (Material Icons の図形, Apache License 2.0)。svg('名前') で <svg> 文字列にする
const ICON_PATHS = {
  play: 'M8 5v14l11-7z',
  toc: 'M3 13h2v-2H3v2zm0 4h2v-2H3v2zm0-8h2V7H3v2zm4 4h14v-2H7v2zm0 4h14v-2H7v2zM7 7v2h14V7H7z',
  more: 'M12 8a2 2 0 1 0 0-4 2 2 0 0 0 0 4zm0 2a2 2 0 1 0 0 4 2 2 0 0 0 0-4zm0 6a2 2 0 1 0 0 4 2 2 0 0 0 0-4z',
  up: 'M7.41 15.41 12 10.83l4.59 4.58L18 14l-6-6-6 6z',
  down: 'M7.41 8.59 12 13.17l4.59-4.58L18 10l-6 6-6-6z',
  top: 'M8 11h3v10h2V11h3l-4-4-4 4zM4 3v2h16V3H4z',
  check: 'M9 16.17 4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z',
  add: 'M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z',
  undo: 'M12.5 8c-2.65 0-5.05.99-6.9 2.6L2 7v9h9l-3.62-3.62c1.39-1.16 3.16-1.88 5.12-1.88 3.54 0 6.55 2.31 7.6 5.5l2.37-.78C21.08 11.03 17.15 8 12.5 8z',
  swap: 'M6.99 11 3 15l3.99 4v-3H14v-2H6.99v-3zM21 9l-3.99-4v3H10v2h7.01v3L21 9z',
  del: 'M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z',
  inbox: 'M19 3H4.99C3.88 3 3 3.9 3 5v14c0 1.1.88 2 1.99 2H19c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm0 12h-4c0 1.66-1.35 3-3 3s-3-1.34-3-3H4.99V5H19v10z',
  book: 'M18 2H6c-1.1 0-2 .9-2 2v16c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zM6 4h5v8l-2.5-1.5L6 12V4z',
  search: 'M15.5 14h-.79l-.28-.27A6.47 6.47 0 0 0 16 9.5 6.5 6.5 0 1 0 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z',
  home: 'M10 20v-6h4v6h5v-8h3L12 3 2 12h3v8z',
  refresh: 'M17.65 6.35A7.96 7.96 0 0 0 12 4a8 8 0 1 0 7.73 10h-2.08A6 6 0 1 1 12 6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z',
  moon: 'M12 3a9 9 0 1 0 9 9c0-.46-.04-.92-.1-1.36a5.39 5.39 0 0 1-4.4 2.26 5.4 5.4 0 0 1-3.14-9.8c-.44-.06-.9-.1-1.36-.1z',
  text: 'M9 4v3h5v12h3V7h5V4H9zm-6 8h3v7h3v-7h3V9H3v3z',
  apps: 'M4 8h4V4H4v4zm6 12h4v-4h-4v4zm-6 0h4v-4H4v4zm0-6h4v-4H4v4zm6 0h4v-4h-4v4zm6-10v4h4V4h-4zm-6 4h4V4h-4v4zm6 6h4v-4h-4v4zm0 6h4v-4h-4v4z',
  globe: 'M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm-1 17.93A8 8 0 0 1 4.21 10.21L9 15v1a2 2 0 0 0 2 2v1.93zm6.9-2.54A2 2 0 0 0 16 16h-1v-3a1 1 0 0 0-1-1H8v-2h2a1 1 0 0 0 1-1V7h2a2 2 0 0 0 2-2v-.41a8 8 0 0 1 2.9 12.8z',
  link: 'M3.9 12c0-1.71 1.39-3.1 3.1-3.1h4V7H7a5 5 0 0 0 0 10h4v-1.9H7A3.1 3.1 0 0 1 3.9 12zM8 13h8v-2H8v2zm9-6h-4v1.9h4a3.1 3.1 0 0 1 0 6.2h-4V17h4a5 5 0 0 0 0-10z',
  mute: 'M12 7a5 5 0 0 1 5 5c0 .65-.13 1.26-.36 1.83l2.92 2.92A11.8 11.8 0 0 0 23 12c-1.73-4.39-6-7.5-11-7.5-1.4 0-2.74.25-3.98.7l2.16 2.16A4.85 4.85 0 0 1 12 7zM2 4.27l2.28 2.28.46.46A11.8 11.8 0 0 0 1 12c1.73 4.39 6 7.5 11 7.5 1.55 0 3.03-.3 4.38-.84l.42.42L19.73 22 21 20.73 3.27 3 2 4.27zM7.53 9.8l1.55 1.55A3 3 0 0 0 9 12a3 3 0 0 0 3 3c.22 0 .44-.03.65-.08l1.55 1.55A4.98 4.98 0 0 1 7 12c0-.79.2-1.53.53-2.2zm4.31-.78 3.15 3.15.02-.16a3 3 0 0 0-3-3l-.17.01z',
  trophy: 'M19 5h-2V3H7v2H5c-1.1 0-2 .9-2 2v1c0 2.55 1.92 4.63 4.39 4.94A5.01 5.01 0 0 0 11 15.9V19H7v2h10v-2h-4v-3.1a5.01 5.01 0 0 0 3.61-2.96C19.08 12.63 21 10.55 21 8V7c0-1.1-.9-2-2-2zM5 8V7h2v3.82C5.84 10.4 5 9.3 5 8zm14 0c0 1.3-.84 2.4-2 2.82V7h2v1z',
  info: 'M11 7h2v2h-2zm0 4h2v6h-2zm1-9C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18c-4.41 0-8-3.59-8-8s3.59-8 8-8 8 3.59 8 8-3.59 8-8 8z',
  download: 'M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z',
  computer: 'M20 18c1.1 0 1.99-.9 1.99-2L22 6c0-1.1-.9-2-2-2H4c-1.1 0-2 .9-2 2v10c0 1.1.9 2 2 2H0v2h24v-2h-4zM4 6h16v10H4V6z',
  back: 'M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z',
  prev: 'M15.41 7.41 14 6l-6 6 6 6 1.41-1.41L10.83 12z',
  next: 'M10 6 8.59 7.41 13.17 12l-4.58 4.59L10 18l6-6z',
  lock: 'M18 8h-1V6c0-2.76-2.24-5-5-5S7 3.24 7 6v2H6c-1.1 0-2 .9-2 2v10c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V10c0-1.1-.9-2-2-2zm-6 9c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2zm3.1-9H8.9V6c0-1.71 1.39-3.1 3.1-3.1 1.71 0 3.1 1.39 3.1 3.1v2z',
  person: 'M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z',
  close: 'M19 6.41 17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z',
  bell: 'M12 22c1.1 0 2-.9 2-2h-4c0 1.1.89 2 2 2zm6-6v-5c0-3.07-1.64-5.64-4.5-6.32V4c0-.83-.67-1.5-1.5-1.5s-1.5.67-1.5 1.5v.68C7.63 5.36 6 7.92 6 11v5l-2 2v1h16v-1l-2-2z',
  tune: 'M3 17v2h6v-2H3zM3 5v2h10V5H3zm10 16v-2h8v-2h-8v-2h-2v6h2zM7 9v2H3v2h4v2h2V9H7zm14 4v-2H11v2h10zm-6-4h2V7h4V5h-4V3h-2v6z',
  doc: 'M14 2H6c-1.1 0-1.99.9-1.99 2L4 20c0 1.1.89 2 1.99 2H18c1.1 0 2-.9 2-2V8l-6-6zm2 16H8v-2h8v2zm0-4H8v-2h8v2zm-3-5V3.5L18.5 9H13z',
  chart: 'M5 9.2h3V19H5zM10.6 5h2.8v14h-2.8zm5.6 8H19v6h-2.8z',
  shield: 'M12 1 3 5v6c0 5.55 3.84 10.74 9 12 5.16-1.26 9-6.45 9-12V5l-9-4zm0 10.99h7c-.53 4.12-3.28 7.79-7 8.94V12H5V6.3l7-3.11v8.8z',
  bug: 'M20 8h-2.81a5.985 5.985 0 0 0-1.82-1.96L17 4.41 15.59 3l-2.17 2.17a6.002 6.002 0 0 0-2.83 0L8.41 3 7 4.41l1.62 1.63C7.88 6.55 7.26 7.22 6.81 8H4v2h2.09c-.05.33-.09.66-.09 1v1H4v2h2v1c0 .34.04.67.09 1H4v2h2.81c1.04 1.79 2.97 3 5.19 3s4.15-1.21 5.19-3H20v-2h-2.09c.05-.33.09-.66.09-1v-1h2v-2h-2v-1c0-.34-.04-.67-.09-1H20V8zm-6 8h-4v-2h4v2zm0-4h-4v-2h4v2z',
  history: 'M13 3a9 9 0 0 0-9 9H1l3.89 3.89.07.14L9 12H6c0-3.87 3.13-7 7-7s7 3.13 7 7-3.13 7-7 7c-1.93 0-3.68-.79-4.94-2.06l-1.42 1.42A8.95 8.95 0 0 0 13 21a9 9 0 0 0 0-18zm-1 5v5l4.28 2.54.72-1.21-3.5-2.08V8H12z',
};
const svg = (name) => '<svg viewBox="0 0 24 24"><path d="' + ICON_PATHS[name] + '"/></svg>';

// 操作部分の大きさ (settings.uiSize = compact | standard | large)。ページの読み込み時と、設定で変えたときに <html data-ui> へ反映する
const UI_SIZES = ['compact', 'standard', 'large'];
function applyUiSize(v) { document.documentElement.dataset.ui = UI_SIZES.includes(v) ? v : 'standard'; }
applyUiSize((NH.load().settings || {}).uiSize);
// サイトの点の色 (.dot.<サイト> の --<サイト>): 上部バーのタブと同じ色にする (設定の siteColors、無ければ SITE_DEFS の既定色)。
// ページの読み込み時と、設定で色を変えたときに呼ぶ。R18 の掲載サイトは 1 色 (--narou18) のまま
function applySiteColors(st) {
  const custom = (st || {}).siteColors || {};
  MAIN_SITES.forEach(([k]) => document.documentElement.style.setProperty('--' + k, custom[k] || TAB_COLOR_DEFAULT[k]));
}
applySiteColors(NH.load().settings);
