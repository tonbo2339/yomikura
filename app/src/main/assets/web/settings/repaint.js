// ---- 描き直し ----
// paint(full): 保存や操作のたびに呼ぶ。トップの設定のスイッチ、いま開いている画面の中身だけを描く (開いていない画面は、開くとき show() が描く)。
// full (画面を開いたとき) だけ、トップの重い値 (ログインの状況) も読み直す。
const startSiteOf = () => {
  const on = SITES.filter(([k]) => siteOn(k));
  return on.some(([k]) => k === S.startSite) ? S.startSite : (on[0] || [])[0];
};
function paintTop() {
  segPick($('densitySeg'), DENSITIES, S.uiSize || 'standard', (k) => { S.uiSize = k; applyUiSize(k); commit(); });
  segPick($('themeSeg'), THEMES, S.theme, (k) => { S.theme = k; commit(); });
  $('adult').checked = adultOn();
  $('nsfw').checked = NH.nsfwOn({ settings: S });
  const lockIco = $('nsfwIco'), lockOpen = !$('nsfw').checked; // 鍵: R18 が使える (NSFW モードがオフ) とき開く
  if (lockIco.dataset.shown && lockIco.classList.contains('open') !== lockOpen) lockIco.classList.add('anim'); // 最初の表示では動かさず、切り替わってからは動かす (途中で描き直されても、動きを止めない)
  lockIco.classList.toggle('open', lockOpen);
  lockIco.dataset.shown = '1';
}
// トップの重い値: ログインの状況 (アプリへの問い合わせ。ログインの画面を描き直す)
function paintMainHeavy() { paintAccounts(); }
// ログインの状況が変わった (ログアウトなど): ログインの画面などを直す
function refreshLogins() { paintAccounts(); if (curPage === 'auto') paintAuto(); else if (curPage === 'bg') paintBg(); }

function paintStart() {
  const on = SITES.filter(([k]) => siteOn(k));
  pickList($('startList'), START, S.start, (k) => { S.start = k; commit(); });
  $('startSiteLabel').hidden = $('startSiteList').hidden = S.start === 'list' || S.start === 'last';
  pickList($('startSiteList'), on, startSiteOf(), (k) => { S.startSite = k; commit(); });
}
function paintLinks() { pickList($('linkModeList'), LINK_MODES, S.externalLinks === 'app' ? 'app' : 'browser', (k) => { S.externalLinks = k; commit(); }); }
function paintAuto() {
  paintCollectSites(NH.collectAny({ settings: S }));
  $('autoHoursBox').hidden = !S.autoCheck;
  pickList($('autoHoursList'), HOURS.map((h) => [h, h + ' 時間']), S.autoCheckHours, (h) => { S.autoCheckHours = h; commit(); });
  paintCats('narou', $('catsNarou'));
  paintCats('narou18', $('catsNarou18'));
  $('catsR18Box').hidden = !['noc', 'mnlt', 'mid'].some(siteOn);
}
function paintListPage() {
  $('favPosBox').hidden = !NH.flagOf(S, 'favOn');
  pickList($('favPosList'), FAV_POS_OPTIONS, typeof S.favoritePosMax === 'number' ? S.favoritePosMax : NH.FAV_POS_MAX, (n) => { S.favoritePosMax = n; commit(); });
}
// 隠している間の目印の、いまの設定の言い方 (バーの画面の行の右側に出す)
function barHandleLabel() {
  if ((S.barPos || 'top') === 'top' || (S.barHidden || 'line') === 'line') return '細い棒';
  return '小さなボタン';
}
// 小さなボタンの見本の色 (Kotlin の MainActivity.buttonColor と同じ色)
const BAR_BUTTON_RGB = { green: '#1e9a55', blue: '#2f6fd6', orange: '#e07b00', pink: '#d6477a' };
function barButtonRgb() {
  const k = S.barButtonColor || 'gray';
  if (k === 'custom') return hsv2hex(S.barButtonHue != null ? S.barButtonHue : 150, (S.barButtonSat != null ? S.barButtonSat : 80) / 100, (S.barButtonVal != null ? S.barButtonVal : 70) / 100);
  return BAR_BUTTON_RGB[k] || null; // null = 灰色 (文字の色に近い)
}
function paintBarButtonPreview() {
  const box = $('barBtnPreview'); box.innerHTML = '';
  const rgb = barButtonRgb(), alpha = (S.barButtonAlpha || 70) / 100, plain = S.barButtonShape === 'plain';
  const b = el('span', 'barbtn-preview');
  const mark = { more: 'more', settings: 'settings', book: 'book', arrow: 'next' }[S.barButtonIcon || 'more'];
  b.innerHTML = S.barButtonIcon === 'dot' ? '<i class="dot"></i>' : svg(mark || 'more');
  const color = rgb || 'currentColor';
  if (plain) { b.style.color = color; b.style.opacity = String(alpha); }
  else { b.style.background = rgb || 'rgba(128,128,128,1)'; b.style.opacity = String(alpha); b.style.color = rgb ? '#fff' : 'inherit'; }
  box.appendChild(b);
}
// 隠している間の目印の画面
function paintBarHandlePage() {
  const side = (S.barPos || 'top') !== 'top';
  segPick($('barHiddenSeg'), BAR_HIDDEN, side ? (S.barHidden || 'line') : 'line', (k) => { S.barHidden = k; commit(); });
  $('barHandleNote').textContent = side ? '細い棒は、タブバーを置いてある端に出る目印です。端から内側へスワイプして、タブバーを出します。小さなボタンは、押すとタブバーが出ます。' : '上に置いているときは、細い棒だけです (小さなボタンは、タブバーを左か右に置いたときに選べます)。';
  $('barPosBox').hidden = !side; // 縦の位置は、左右に置いているときだけ (上のバーの棒は、横の中央)
  $('barPosRange').value = barPosNow();
  $('barPosV').textContent = barPosNow() + '%';
  $('barBtnBox').hidden = !side || (S.barHidden || 'line') !== 'button';
  segPick($('barBtnShapeSeg'), BAR_BUTTON_SHAPES, S.barButtonShape || 'circle', (k) => { S.barButtonShape = k; commit(); });
  segPick($('barBtnIconSeg'), BAR_BUTTON_ICONS, S.barButtonIcon || 'more', (k) => { S.barButtonIcon = k; commit(); });
  segPick($('barBtnColorSeg'), BAR_BUTTON_COLORS, S.barButtonColor || 'gray', (k) => { S.barButtonColor = k; commit(); });
  paintBarPicker();
  $('barBtnCustom').hidden = (S.barButtonColor || 'gray') !== 'custom';
  segPick($('barBtnAlphaSeg'), BAR_BUTTON_ALPHAS, S.barButtonAlpha || 70, (k) => { S.barButtonAlpha = k; commit(); });
  paintBarButtonPreview();
}
// 全画面の画面
function paintFullscreenPage() {
  const on = NH.flagOf(S, 'fullscreen', false);
  $('fsBox').hidden = !on;
  $('stBox').hidden = on; // 全画面のときは、全画面の「時計」を使う
  segPick($('fsCutoutSeg'), CUTOUTS, S.fullscreenCutout || 'ignore', (k) => { S.fullscreenCutout = k; commit(); });
  $('fsClockSideRow').hidden = !NH.flagOf(S, 'fullscreenClock', false);
  segPick($('fsClockSideSeg'), CLOCK_SIDES, S.fullscreenClockSide || 'end', (k) => { S.fullscreenClockSide = k; commit(); });

}
// バーの画面 (置き場所・隠し方)。目印と全画面は、それぞれ別の画面 (paintBarHandlePage / paintFullscreenPage)
function paintBarPage() {
  paintBarModeList($('barModeList'), S, (k) => { NH.setBarMode(S, k); commit(); });
  $('barHideBox').hidden = (S.barPos || 'top') === 'top' || NH.barModeOf(S) === 'always'; // 左右に置いていて、バーが隠れることがあるときだけ
  showHint($('barHint'), 'bar-position');
  segPick($('barPosSeg'), BAR_POSITIONS, S.barPos || 'top', (k) => { S.barPos = k; if (k === 'top' && NH.flagOf(S, 'barEdge', false)) NH.setBarMode(S, 'scroll'); commit(); }); // 上に置くときは「端から出す」を使えないので、スクロールで隠すに戻す
  $('v-barhandle').textContent = barHandleLabel();
  barBgSwatches.forEach(({ color, button }) => { const on = color === (S.barBg || null); button.classList.toggle('on', on); setPressed(button, on); });
  const bgCustom = isBarBgCustom();
  barBgCustomBtn.classList.toggle('on', bgCustom); setPressed(barBgCustomBtn, bgCustom);
  $('barBgCustom').hidden = !bgCustom;
  if (bgCustom) barBgPicker.sync(S.barBg);
  $('railHeightBox').hidden = NH.barModeOf(S) !== 'edge'; // 重ねて出す (端から出す) ときだけ。ふつうに並べるときは画面いっぱい
  segPick($('railHeightSeg'), RAIL_HEIGHTS, S.railHeight || 'auto', (k) => { S.railHeight = k; commit(); });
  segPick($('cardAlphaSeg'), BAR_CARD_ALPHAS, S.barCardAlpha || 95, (k) => { S.barCardAlpha = k; commit(); });
  pickList($('r18ModeList'), R18_MODES, S.r18Mode || 'menu', (k) => { S.r18Mode = k; commit(); });
  $('r18ModeBox').hidden = !r18Usable();
  // 名前の欄は、上部バーにタブとして出ているサイトだけ出す
  const order = tabOrder();
  const shownTabs = order.filter(tabShown);
  // 並びの順に置き直す (変わったときだけ。名前の入力中に置き直すとフォーカスが外れる)
  const rows = order.map((k) => nameInputs[k].closest('.cell'));
  if (rows.some((r, i) => $('nameList').children[i] !== r)) rows.forEach((r) => $('nameList').appendChild(r));
  Object.entries(nameInputs).forEach(([k, inp]) => {
    if (document.activeElement !== inp) inp.value = (S.siteNames || {})[k] || '';
    inp.closest('.cell').hidden = !tabShown(k);
    tabMoves[k][0].disabled = shownTabs[0] === k;
    tabMoves[k][1].disabled = shownTabs[shownTabs.length - 1] === k;
    const cur = (S.siteColors || {})[k] || null;
    const custom = isSiteColorCustom(k);
    swatches[k].forEach(({ color, button }) => { const on = color === 'custom' ? custom : color === cur; button.classList.toggle('on', on); setPressed(button, on); });
    sitePickers[k].box.hidden = !custom;
    if (custom) sitePickers[k].picker.sync(cur);
  });
}
function paintHistoryPage() {
  const hn = (load().history || []).length;
  $('historyCount').textContent = hn ? hn + ' 作品を記録しています' : '記録はありません';
  $('clearHistory').disabled = !hn;
  pickList($('historyMaxList'), HISTORY_MAX, S.historyMax, (n) => setHistoryLimit('historyMax', n));
  pickList($('historyDaysList'), HISTORY_DAYS, S.historyDays, (n) => setHistoryLimit('historyDays', n));
}
function paintTextPage() {
  paintFontFace();
  pickList($('fontList'), fontNames(), S.font, (k) => { S.font = k; commit(); });
  paintFontManage();
  pickList($('readBgList'), NH_READBG.choices(), S.readBg || NH_READBG.SITE, (k) => { S.readBg = k; commit(); });
  $('size').value = S.size;
  $('lh').value = lhPos();
  $('lh').setAttribute('aria-valuetext', S.lh ? S.lh.toFixed(1) : '標準');
  $('sizeV').textContent = S.size + '%';
  $('lhV').textContent = S.lh ? S.lh.toFixed(1) : '標準';
  const p = $('preview');
  p.style.fontFamily = NH_FONTS.family(S.font) || 'inherit';
  p.style.fontSize = (16 * S.size / 100) + 'px';
  p.style.lineHeight = S.lh || 1.8;
  p.classList.toggle('vertical', NH.flagOf(S, 'siteVertical', false)); // 組み方向の設定に合わせて、見本も縦書きにする
  const bg = NH_READBG.colors(S.readBg); // 見本にも背景色を反映する (サイト標準は、画面の標準の色)
  p.style.background = bg ? bg.bg : '';
  p.style.color = bg ? bg.fg : '';
}
function paintSitesPage() {
  $$('#siteList input').forEach((cb) => (cb.checked = siteOn(cb.dataset.k)));
  $$('#siteList [data-r18]').forEach((row) => (row.hidden = !r18Usable()));
  $('r18Tip').hidden = !r18Usable() || (S.r18Mode || 'menu') !== 'menu'; // 「なろう」を 2 回タップする出し方のときだけ (タブで出すときは、普通に押せる)
}
// 画面ごとの描き方 (rank-<サイト> は paintPage で)
const PAGE_PAINT = {
  start: paintStart, links: paintLinks, auto: paintAuto, bg: paintBg, list: paintListPage, bar: paintBarPage, barhandle: paintBarHandlePage, fullscreen: paintFullscreenPage, history: paintHistoryPage, text: paintTextPage,
  sites: paintSitesPage, icon: paintIcons, mute: paintMute, ranking: paintRanking, accounts: paintAccounts, export: paintExport, update: paintGithub, reading: paintReading,
};
function paintPage(id) {
  if (PAGE_PAINT[id]) PAGE_PAINT[id]();
  else if (id.startsWith('rank-')) paintRankSite(id.slice(5));
  if (EDITION_PAGES.has(id)) EDITION_PAINT.forEach((f) => f());
}
function paint(full) {
  paintToggles(S);
  applySiteColors(S);
  paintTop();
  paintPage(curPage);
  if (full && (curPage === 'main' || SPLIT.matches)) paintMainHeavy(); // 2 列のときは、一覧がいつも見えている
}

$('version').textContent = APP_NAME + ' ' + CURRENT;
paintUpdate();
if (CAN_UPDATE && upd.state === 'idle') checkUpdate(); // 設定を開いたとき、前回の確認から 30 分以上たっていれば確認する
// ランキング画面などから ?page=mute のように直接開かれたとき: トップを下に積んでからその画面へ
const directPage = new URLSearchParams(location.search).get('page');
if (directPage && TITLES[directPage] && !(history.state && history.state.page)) {
  history.replaceState({ page: 'main' }, '');
  history.pushState({ page: directPage }, '');
}
show((history.state && history.state.page) || 'main'); // テーマを変えると画面が読み込み直されるので、開いていた詳細画面に戻す (show が描く)
// 読み込みのあとに読み直したときの知らせ
showFlash();
if (NH.unreadable) snack('保存データを読めませんでした。消さないよう、書き込みを止めています。下の「バックアップ」の「読み込む」で、バックアップから復元できます');