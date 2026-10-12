
// 設定のスイッチ (data-setting / data-default を持つ checkbox) は app.js の bindToggles / paintToggles でまとめて結ぶ
bindToggles(() => S, commit);
$('clearHistory').onclick = () => ask('閲覧履歴をすべて消去します。リストと読書位置は消えません。', '閲覧履歴を消去', () => {
  NH.update((d) => { d.history = []; }); paint(); snack('閲覧履歴を消去しました');
});
$('reset').onclick = () => ask('設定を初期状態に戻します。リストと閲覧履歴は消えません。', '初期状態に戻す', () => {
  S = Object.assign({}, DEFAULTS, { sites: Object.assign({}, SITE_DEFAULT), adult: S.adult, nsfw: S.nsfw, welcomed: S.welcomed }); // 年齢の答え・NSFW モード・初回の画面を済ませた印は初期化しない (人前で急に R18 が出ないように。初回の画面も出し直さない)
  if (!adultOn()) NH.R18_SITES.forEach((k) => (S.sites[k] = false));
  NH.update((d) => { if (d.settings) LIVE_KEYS.forEach((k) => delete d.settings[k]); }); // 初期化ではランキングの行き先なども戻す
  commit(); snack('設定を初期状態に戻しました');
});
// 行間のスライダー: 位置 LH_STD = 標準 (S.lh = 0)、それより右は 1.2〜3.0 (位置 12〜30 = 値 × 10)。オフラインの読み画面の行の間隔 (1.2〜3.0) と同じ範囲
const LH_STD = 11, LH_MAX = 30;
const lhPos = () => (S.lh ? Math.round(S.lh * 10) : LH_STD);
const lhFromPos = (n) => (n <= LH_STD ? 0 : Math.min(LH_MAX, n) / 10);
$('size').oninput = (e) => { S.size = Number(e.target.value); commitSoon(); };
$('lh').oninput = (e) => { S.lh = lhFromPos(Number(e.target.value)); commitSoon(); };
$$('.step').forEach((b) => (b.onclick = () => {
  const d = Number(b.dataset.d);
  if (b.dataset.t === 'size') S.size = Math.min(250, Math.max(80, S.size + d));
  else S.lh = lhFromPos(Math.max(LH_STD, Math.min(LH_MAX, lhPos() + d)));
  commitSoon();
}));

// タブの名前の入力欄 (一度だけ作る。値は paint で入れ直す)
const nameInputs = {};
// 上部バーにタブとして出るサイトか (MainActivity.fillBar と同じ条件)
// R18 は「サイト名のタブ」にしたとき、または なろうを隠しているときだけ
const tabShown = (k) => siteOn(k) && (!SITES.find(([x]) => x === k)[2] || S.r18Mode === 'tabs' || !siteOn('narou'));
// タブの点の既定の色は TAB_COLOR_DEFAULT (app.js。MainActivity の NAROU_COLOR などと同じ)。選べる色:
const TAB_COLORS = ['#4A8FE0', '#E07B3C', '#22B3A4', '#D9529A', '#8E6CD8', '#2F8F5B', '#E0504A', '#D9A400', '#8E8E93'];
const COLOR_NAMES = { '#4A8FE0': '青', '#E07B3C': 'オレンジ', '#22B3A4': '青緑', '#D9529A': 'ピンク', '#8E6CD8': '紫', '#2F8F5B': '緑', '#E0504A': '赤', '#D9A400': '黄', '#8E8E93': 'グレー' };
const swatches = {}; // サイト → [{color, button}]
const tabMoves = {}; // サイト → [上へ, 下へ]
// タブの並び (settings.tabOrder。MainActivity.fillBar と同じ: 書いていないサイトは既定の順で後ろに)
const TAB_KEYS = SITES.map(([k]) => k);
const tabOrder = () => {
  const o = (Array.isArray(S.tabOrder) ? S.tabOrder : []).filter((k, i, a) => TAB_KEYS.includes(k) && a.indexOf(k) === i);
  return [...o, ...TAB_KEYS.filter((k) => !o.includes(k))];
};
// 出ているタブの中で、となりと入れ替える (隠れているサイトは飛ばす)
function moveTab(k, dir) {
  const full = tabOrder();
  const shown = full.filter(tabShown);
  const j = shown.indexOf(k) + dir;
  if (j < 0 || j >= shown.length) return;
  const a = full.indexOf(k), b = full.indexOf(shown[j]);
  [full[a], full[b]] = [full[b], full[a]];
  S.tabOrder = full;
  commit();
}
SITES.forEach(([k, label]) => {
  const row = el('div', 'cell name-cell');
  const top = el('label', 'name-top');
  const inp = el('input', 'field');
  inp.type = 'text'; inp.placeholder = label; inp.maxLength = 12;
  inp.style.cssText = 'flex:1;min-width:0;text-align:right';
  inp.oninput = () => {
    const v = inp.value.trim();
    S.siteNames = Object.assign({}, S.siteNames);
    if (v) S.siteNames[k] = v; else delete S.siteNames[k];
    commitSoon();
  };
  nameInputs[k] = inp;
  // 並べ替え (label の中なので、押しても入力欄にフォーカスが移らないよう既定の動作を止める)
  const mv = el('div', 'tab-move');
  tabMoves[k] = ['up', 'down'].map((name, i) => {
    const b = el('button', 'icon-btn');
    b.innerHTML = svg(name);
    b.setAttribute('aria-label', label + 'のタブを' + (i ? '下へ' : '上へ'));
    b.onclick = (e) => { e.preventDefault(); moveTab(k, i ? 1 : -1); };
    mv.appendChild(b);
    return b;
  });
  top.append(el('span', 'text', label), inp, mv);
  // 色: 既定 + 選べる色 (既定と同じ色は並べない)。既定を選ぶと設定から消す
  const sw = el('div', 'swatches');
  swatches[k] = [TAB_COLOR_DEFAULT[k], ...TAB_COLORS.filter((c) => c !== TAB_COLOR_DEFAULT[k])].map((c, i) => {
    const b = el('button', 'swatch');
    b.style.background = c;
    b.setAttribute('aria-label', label + 'のタブの色: ' + (i ? COLOR_NAMES[c] || '色' + i : '既定の色'));
    b.onclick = () => {
      S.siteColors = Object.assign({}, S.siteColors);
      if (i) S.siteColors[k] = c; else delete S.siteColors[k];
      commit();
    };
    sw.appendChild(b);
    return { color: i ? c : null, button: b };
  });
  row.append(top, sw);
  $('nameList').appendChild(row);
});

// 表示するサイトのスイッチ (一度だけ作る)
SITES.forEach(([k, label, r18]) => {
  const row = el('label', 'cell');
  const name = el('span', 'text');
  name.append(el('span', 'dot ' + siteDot(k)), document.createTextNode('  ' + label + (r18 ? ' (R18)' : '')));
  const cb = el('input', 'switch'); cb.type = 'checkbox'; cb.dataset.k = k;
  cb.onchange = () => { S.sites[k] = cb.checked; commit(); };
  if (r18) row.dataset.r18 = '1';
  row.append(name, cb); $('siteList').appendChild(row);
});
// 18 歳以上か。オフにしたら R18 のサイトもオフ、オンに戻したら R18 のサイトは既定 (全部オン) に
const adultOn = () => S.adult !== false;
// R18 のサイトを使えるか。18 歳以上で、さらに R18 のサイトを「使う」版 (NH.R18_OPT_IN) なら、使うと決めている (NSFW モードがオフ) とき
const r18Usable = () => adultOn() && !NH.nsfwOn({ settings: S }); // NSFW モードがオンの間は、どの版でも一切アクセスしない (Kotlin の nsfwOn と同じ)
$('adult').onchange = (e) => {
  S.adult = e.target.checked;
  NH.R18_SITES.forEach((k) => (S.sites[k] = S.adult && SITE_DEFAULT[k]));
  commit(true);
};

// NSFW モード: オンの間は R18 のサイトへ一切アクセスせず、リスト・履歴・ランキングにも R18 を出さない (設定を変えても R18 の「表示するサイト」は変えない)
// 成人向けのサイトを自分で使うと決めた人だけが使う版 (NH.R18_OPT_IN) も、スイッチは同じ「NSFW モード」(オン = 成人向けに一切アクセスしない)。
// 最初はオン。オフにする (使えるようにする) とき、18 歳以上かを確かめる。年齢の行はそのために隠す
if (NH.R18_OPT_IN) {
  $('adultRow').hidden = true;
  $('siteHint').textContent = '「NSFW モード」がオンの間は、成人向けのサイトにアクセスせず、リスト・履歴・ランキングにも成人向けの作品を出しません。オフにすると使えます (18 歳以上の方向け)。';
}
$('nsfw').onchange = (e) => {
  if (!NH.R18_OPT_IN || e.target.checked) { S.nsfw = e.target.checked; commit(true); return; }
  // オフにする (成人向けを使えるようにする)。18 歳以上の確認は一度だけ (S.adult が true になっていれば、もう聞かない)
  const release = () => {
    S.adult = true; S.nsfw = false;
    if (!NH.R18_SITES.some((k) => S.sites[k])) NH.R18_SITES.forEach((k) => (S.sites[k] = SITE_DEFAULT[k])); // 初めてのときだけ既定に (選んでいたサイトは変えない)
    commit(true);
  };
  if (S.adult === true) return release();
  e.target.checked = true; // 確かめるまではオンのまま
  ask('NSFW モードをオフにすると、成人向けのサイトが使えるようになります。18 歳以上ですか？', '18 歳以上です。オフにする', release);
};

// アイコンの選択 (切り替えは Kotlin 側: ランチャーの別名を有効にする)
let currentIcon = (window.NHStore && NHStore.launcherIcon) ? NHStore.launcherIcon() : 'paper';
function paintIcons() {
  const g = $('iconGrid'); g.innerHTML = '';
  LAUNCHER.forEach((k) => {
    const b = el('button', 'icon-opt' + (k === currentIcon ? ' on' : ''));
    b.setAttribute('aria-pressed', k === currentIcon ? 'true' : 'false');
    const img = el('img'); img.src = 'icons/' + k + '.svg'; img.alt = '';
    b.append(img);
    b.onclick = () => {
      if (k === currentIcon || !window.NHStore || !NHStore.setLauncherIcon) return;
      // 今すぐ: アプリを閉じて、ホーム画面のアイコンが変わる (開き直しはしない)。後で: 次にアプリを開いたときに変わる
      askChoice('アイコンを変えます', 'ホーム画面のアイコンは、アプリを閉じると変わります。',
        ['今すぐ (アプリを閉じる)', () => { NHStore.setLauncherIcon(k, true); }],
        ['後で (次に開いたとき)', () => { NHStore.setLauncherIcon(k, false); snack('次にアプリを開いたとき、アイコンが変わります'); }]);
    };
    g.appendChild(b);
  });
}
