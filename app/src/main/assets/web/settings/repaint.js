// ---- 描き直し ----
// paint(full): 保存や操作のたびに呼ぶ。トップの値と設定のスイッチ、いま開いている画面の中身だけを描く (開いていない画面は、開くとき show() が描く)。
// full (画面を開いたとき) だけ、トップの重い値 (オフラインの一覧・ログインの状況) も読み直す。
const startSiteOf = () => {
  const on = SITES.filter(([k]) => siteOn(k));
  return on.some(([k]) => k === S.startSite) ? S.startSite : (on[0] || [])[0];
};
function paintTop() {
  const on = SITES.filter(([k]) => siteOn(k));
  $('v-start').textContent = S.start === 'list' ? 'リスト' : S.start === 'last' ? '最後のページ' : nameOf(START, S.start) + '・' + nameOf(SITES, startSiteOf());
  const collectOn = NH.collectAny({ settings: S });
  $('v-auto').textContent = !collectOn ? '確認しない' : !activeAny() ? 'ログインが必要' : S.autoCheck ? '起動時に確認' : '手動のみ';
  $('v-bg').textContent = bgConf().on ? 'オン' : 'オフ';
  $('v-offlineset').textContent = NH.flagOf(S, 'offlineWifiOnly', false) ? 'Wi-Fi のみ' : '';
  $('v-links').textContent = S.externalLinks === 'app' ? 'アプリの中' : 'ブラウザ';
  $('v-bar').textContent = NH.flagOf(S, 'hideBarOnScroll') ? 'スクロールで隠す' : '常に表示';
  $('v-list').textContent = S.pruneDone ? '読了を ' + S.pruneDays + ' 日で削除' : '読了を残す';
  const folded = ['foldComments', 'foldRecommend', 'foldLinks', 'foldReactions'].concat(EXTRA_FOLD).filter((k) => NH.flagOf(S, k)).length;
  $('v-fold').textContent = folded ? folded + ' 種類を畳む' : '畳まない';
  $('v-text').textContent = fontLabel(S.font) + '・' + S.size + '%';
  $('v-sites').textContent = on.length + ' サイト';
  const hn = (load().history || []).length;
  $('v-history').textContent = !NH.flagOf(S, 'history') ? '記録しない' : hn ? hn + ' 作品' : '';
  segPick($('densitySeg'), DENSITIES, S.uiSize || 'standard', (k) => { S.uiSize = k; applyUiSize(k); commit(); });
  segPick($('themeSeg'), THEMES, S.theme, (k) => { S.theme = k; commit(); });
  $('adult').checked = adultOn();
  $('nsfw').checked = NH.nsfwOn({ settings: S });
  const lockIco = $('nsfwIco'), lockOpen = !$('nsfw').checked; // 鍵: R18 が使える (NSFW モードがオフ) とき開く
  if (lockIco.dataset.shown && lockIco.classList.contains('open') !== lockOpen) lockIco.classList.add('anim'); // 最初の表示では動かさず、切り替わってからは動かす (途中で描き直されても、動きを止めない)
  lockIco.classList.toggle('open', lockOpen);
  lockIco.dataset.shown = '1';
  const rk = rankingsOf();
  $('v-ranking').textContent = Object.keys(rk).length ? Object.keys(rk).length + ' サイトを変更' : '既定';
  const mc = NH.muteOf(load()), bc = NH.blockOf(load());
  const nMute = mc.words.length + mc.authors.length, nBlock = bc.works.length + bc.words.length + bc.authors.length;
  $('v-mute').textContent = [nMute ? 'ミュート ' + nMute : '', nBlock ? 'ブロック ' + nBlock : ''].filter(Boolean).join('・') || 'なし';
}
// トップの重い値: オフライン保存の一覧 (端末の保存の読み込み)、ログインの状況 (アプリへの問い合わせ)
function paintOffline() {
  const off = NH.off.list();
  $('v-offline').textContent = off.length ? off.length + ' 作品 ・ ' + NH.fmtBytes(off.reduce((n, w) => n + (w.bytes || 0), 0)) : '';
}
function paintMainHeavy() { paintOffline(); paintAccounts(); }
// ログインの状況が変わった (ログアウトなど): トップの値と、ログインの画面を直す
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
  $('pruneDaysBox').hidden = !S.pruneDone;
  pickList($('pruneDaysList'), PRUNE_DAYS.map((n) => [n, n + ' 日']), S.pruneDays, (n) => { S.pruneDays = n; commit(); });
}
function paintBarPage() {
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
    swatches[k].forEach(({ color, button }) => { const on = color === cur; button.classList.toggle('on', on); setPressed(button, on); });
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
  $('size').value = S.size;
  $('lh').value = lhPos();
  $('lh').setAttribute('aria-valuetext', S.lh ? S.lh.toFixed(1) : '標準');
  $('sizeV').textContent = S.size + '%';
  $('lhV').textContent = S.lh ? S.lh.toFixed(1) : '標準';
  const p = $('preview');
  p.style.fontFamily = NH_FONTS.family(S.font) || 'inherit';
  p.style.fontSize = (16 * S.size / 100) + 'px';
  p.style.lineHeight = S.lh || 1.8;
}
function paintSitesPage() {
  $$('#siteList input').forEach((cb) => (cb.checked = siteOn(cb.dataset.k)));
  $$('#siteList [data-r18]').forEach((row) => (row.hidden = !r18Usable()));
  $('r18Tip').hidden = !r18Usable() || (S.r18Mode || 'menu') !== 'menu'; // 「なろう」を 2 回タップする出し方のときだけ (タブで出すときは、普通に押せる)
}
// 画面ごとの描き方 (rank-<サイト> は paintPage で)
const PAGE_PAINT = {
  start: paintStart, links: paintLinks, auto: paintAuto, bg: paintBg, list: paintListPage, bar: paintBarPage, history: paintHistoryPage, text: paintTextPage,
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
  if (full && curPage === 'main') paintMainHeavy();
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