
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
const LH_STD = NH_TEXTLOOK.LH.std, LH_MAX = NH_TEXTLOOK.LH.max; // 範囲と変換は、話ページの TT と共通 (site/readbg.js の NH_TEXTLOOK)
const lhPos = () => NH_TEXTLOOK.lhPos(S.lh);
const lhFromPos = NH_TEXTLOOK.lhFromPos;
$('size').oninput = (e) => { S.size = Number(e.target.value); commitSoon(); };
$('lh').oninput = (e) => { S.lh = lhFromPos(Number(e.target.value)); commitSoon(); };
$$('.step').forEach((b) => (b.onclick = () => {
  const d = Number(b.dataset.d);
  if (b.dataset.t === "size") S.size = Math.min(NH_TEXTLOOK.SIZE.max, Math.max(NH_TEXTLOOK.SIZE.min, S.size + d));
  else S.lh = lhFromPos(Math.max(LH_STD, Math.min(LH_MAX, lhPos() + d)));
  commitSoon();
}));

// ---- 色を自由に決める部品: 色の四角 (彩度 × 明るさ) と色合いの帯。色の計算は Kotlin の MainActivity.buttonColor と同じ (HSV) ----
const hsv2hex = (h, s, v) => { const f = (n) => { const k = (n + h / 60) % 6; return v - v * s * Math.max(0, Math.min(k, 4 - k, 1)); }; return '#' + [f(5), f(3), f(1)].map((x) => Math.round(x * 255).toString(16).padStart(2, '0')).join(''); };
const hex2hsv = (hex) => {
  const [r, g, b] = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255);
  const max = Math.max(r, g, b), d = max - Math.min(r, g, b);
  let h = 0;
  if (d) h = max === r ? ((g - b) / d + 6) % 6 : max === g ? (b - r) / d + 2 : (r - g) / d + 4;
  return { h: Math.round(h * 60) % 360, s: max ? d / max : 0, v: max };
};
const COLOR_PICK_MIN_V = 0.1; // 真っ黒にはしない (文字が読めるかどうかは、Kotlin 側がバーの色の明るさから自動で決める)
// 四角と帯の 1 組を作る。onPick(hex) は、動かすたびに呼ぶ (保存は呼び出し側)。sync(hex) は、外で色が変わったときだけ中の HSV を合わせ直す (なぞっている最中に戻すと色合いが飛ぶ)
function makeColorPicker(onPick) {
  const sv = el('div', 'cpick-sv'), hue = el('div', 'cpick-hue');
  const svDot = el('i'), hueDot = el('i');
  sv.append(svDot); hue.append(hueDot);
  sv.tabIndex = 0; hue.tabIndex = 0;
  sv.setAttribute('role', 'slider'); sv.setAttribute('aria-label', '彩度と明るさ');
  hue.setAttribute('role', 'slider'); hue.setAttribute('aria-label', '色合い');
  const box = el('div', 'cpick'); box.append(sv, hue);
  let c = { h: 150, s: 0.5, v: 0.9 };
  const paint = () => {
    sv.style.background = 'linear-gradient(to top, #000, rgba(0,0,0,0)), linear-gradient(to right, #fff, ' + hsv2hex(c.h, 1, 1) + ')';
    svDot.style.left = (c.s * 100) + '%'; svDot.style.top = ((1 - c.v) * 100) + '%'; svDot.style.background = hsv2hex(c.h, c.s, c.v);
    hueDot.style.left = '50%'; hueDot.style.top = (c.h / 360 * 100) + '%'; hueDot.style.background = hsv2hex(c.h, 1, 1);
  };
  const changed = () => { paint(); onPick(hsv2hex(c.h, c.s, c.v)); };
  dragIn(sv, (x, y) => { c = { ...c, s: x, v: Math.max(COLOR_PICK_MIN_V, 1 - y) }; changed(); });
  dragIn(hue, (x, y) => { c = { ...c, h: Math.min(359, Math.round(y * 360)) }; changed(); });
  sv.addEventListener('keydown', (e) => {
    const d = { ArrowLeft: [-0.05, 0], ArrowRight: [0.05, 0], ArrowUp: [0, 0.05], ArrowDown: [0, -0.05] }[e.key];
    if (!d) return;
    c = { ...c, s: Math.min(1, Math.max(0, c.s + d[0])), v: Math.min(1, Math.max(COLOR_PICK_MIN_V, c.v + d[1])) }; changed(); e.preventDefault();
  });
  hue.addEventListener('keydown', (e) => {
    const d = { ArrowUp: -10, ArrowDown: 10 }[e.key];
    if (!d) return;
    c = { ...c, h: (c.h + d + 360) % 360 }; changed(); e.preventDefault();
  });
  paint();
  return {
    el: box,
    current: () => hsv2hex(c.h, c.s, c.v),
    sync(hex) { if (hex && hex.toLowerCase() !== hsv2hex(c.h, c.s, c.v)) { c = hex2hsv(hex); paint(); } },
  };
}
const isHexColor = (c) => typeof c === 'string' && /^#[0-9a-fA-F]{6}$/.test(c);
const sameColor = (a, b) => !!a && !!b && a.toUpperCase() === b.toUpperCase();
// 「自由」の丸 (虹色)。色の見本の並びの最後に置く
function customSwatch(label, ring) {
  const b = el('button', 'swatch custom' + (ring ? ' ring' : ''));
  b.setAttribute('aria-label', label);
  return b;
}

// タブの名前の入力欄 (一度だけ作る。値は paint で入れ直す)
const nameInputs = {};
// 上部バーにタブとして出るサイトか (TopBar.tabSpecs と同じ条件)
// R18 は「サイト名のタブ」にしたとき、または なろうを隠しているときだけ
const tabShown = (k) => siteOn(k) && (!SITES.find(([x]) => x === k)[2] || S.r18Mode === 'tabs' || !siteOn('narou'));
// タブの点の既定の色は TAB_COLOR_DEFAULT (app.js。Kotlin の BarTheme の色と同じ)。選べる色:
const TAB_COLORS = ['#4A8FE0', '#E07B3C', '#22B3A4', '#D9529A', '#8E6CD8', '#2F8F5B', '#E0504A', '#D9A400', '#8E8E93'];
const COLOR_NAMES = { '#4A8FE0': '青', '#E07B3C': 'オレンジ', '#22B3A4': '青緑', '#D9529A': 'ピンク', '#8E6CD8': '紫', '#2F8F5B': '緑', '#E0504A': '赤', '#D9A400': '黄', '#8E8E93': 'グレー' };
// バーの背景色 (settings.barBg)。先頭 (null) は、テーマに合わせた既定の色
const BAR_BGS = [
  [null, '既定'], ['#FFFFFF', '白'], ['#E5E5EA', '薄いグレー'], ['#F4ECD8', 'ベージュ'], ['#DCE9F7', '薄い青'], ['#DDEEDD', '薄い緑'], ['#F6DDE6', '薄いピンク'],
  ['#2C2C2E', '濃いグレー'], ['#1B2A41', '紺'], ['#1F3A2B', '深い緑'], ['#000000', '黒'],
];
const barBgSwatches = BAR_BGS.map(([c, name]) => {
  const b = el('button', 'swatch ring');
  if (c) b.style.backgroundColor = c; else b.style.backgroundImage = 'linear-gradient(135deg, #F9F9F9 50%, #1C1C1E 50%)'; // 既定は、ライトとダークの半分ずつ
  b.setAttribute('aria-label', 'タブバーの背景色: ' + name);
  b.onclick = () => { if (c) S.barBg = c; else delete S.barBg; commit(); };
  $('barBgSwatches').appendChild(b);
  return { color: c, button: b };
});
// 背景色の「自由」: 丸を押すと色の四角と帯が出る (色は settings.barBg に "#rrggbb" で入る。文字の色は Kotlin 側が背景の明るさから決める)
const barBgPicker = makeColorPicker((hex) => { S.barBg = hex; commitSoon(); });
$('barBgCustom').appendChild(barBgPicker.el);
const barBgCustomBtn = customSwatch('タブバーの背景色: 自由', true);
barBgCustomBtn.onclick = () => { if (!isBarBgCustom()) { S.barBg = barBgPicker.current(); commit(); } };
$('barBgSwatches').appendChild(barBgCustomBtn);
const isBarBgCustom = () => isHexColor(S.barBg) && !BAR_BGS.some(([c]) => sameColor(c, S.barBg));
const swatches = {}; // サイト → [{color, button}] (最後の color: 'custom' は「自由」の丸)
const sitePickers = {}; // サイト → {picker, box} (「自由」の色の四角と帯)
// 選べる色 (既定・TAB_COLORS) のどれでもない色が入っているとき、「自由」
const isSiteColorCustom = (k) => {
  const cur = (S.siteColors || {})[k];
  return isHexColor(cur) && !sameColor(cur, TAB_COLOR_DEFAULT[k]) && !TAB_COLORS.some((c) => sameColor(c, cur));
};
const tabMoves = {}; // サイト → [上へ, 下へ]
// タブの並び (settings.tabOrder。TopBar.tabSpecs と同じ: 書いていないサイトは既定の順で後ろに)
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
    b.style.backgroundColor = c; // background (一括の指定) だと background-clip が戻り、丸が押せる範囲いっぱいに広がる
    b.setAttribute('aria-label', label + 'のタブの色: ' + (i ? COLOR_NAMES[c] || '色' + i : '既定の色'));
    b.onclick = () => {
      S.siteColors = Object.assign({}, S.siteColors);
      if (i) S.siteColors[k] = c; else delete S.siteColors[k];
      commit();
    };
    sw.appendChild(b);
    return { color: i ? c : null, button: b };
  });
  // 「自由」: 丸を押すと色の四角と帯が出る (色は settings.siteColors[サイト] に "#rrggbb" で入る)
  const picker = makeColorPicker((hex) => { S.siteColors = Object.assign({}, S.siteColors, { [k]: hex }); commitSoon(); });
  const pickBox = el('div', 'cpick-wrap'); pickBox.hidden = true; pickBox.appendChild(picker.el);
  const custom = customSwatch(label + 'のタブの色: 自由', false);
  custom.onclick = () => { if (!isSiteColorCustom(k)) { S.siteColors = Object.assign({}, S.siteColors, { [k]: picker.current() }); commit(); } };
  sw.appendChild(custom);
  swatches[k].push({ color: 'custom', button: custom });
  sitePickers[k] = { picker, box: pickBox };
  row.append(top, sw, pickBox);
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
const adultOn = () => NH.flagOf(S, 'adult');
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
const ICON_LABEL = { paper: '紙', green: '緑', ocean: '海', purple: '紫', sakura: '桜', aigold: '藍と金', yuyake: '夕焼け', matcha: '抹茶', night: '夜', dusk: '夕暮れ', wood: '木', sea: '海辺' };
function paintIcons() {
  const g = $('iconGrid'); g.innerHTML = '';
  // 「後で」で予約したアイコン (次に開いたとき変わる)。点線の枠で示し、今のアイコンを押すと予約を取り消せる
  const pending = (load().settings || {}).launcherIconPending || '';
  const queued = pending && pending !== currentIcon ? pending : '';
  LAUNCHER.forEach((k) => {
    const b = el('button', 'icon-opt' + (k === currentIcon ? ' on' : '') + (k === queued ? ' pending' : ''));
    b.setAttribute('aria-pressed', k === currentIcon ? 'true' : 'false');
    b.setAttribute('aria-label', 'アイコン「' + (ICON_LABEL[k] || k) + '」'); // 絵だけのボタンなので、読み上げ用の名前を付ける
    const img = el('img'); img.src = 'icons/' + k + '.svg'; img.alt = '';
    b.append(img);
    b.onclick = () => {
      if (!window.NHStore || !NHStore.setLauncherIcon) return;
      if (k === currentIcon) {
        if (!queued) return;
        NHStore.setLauncherIcon(k, false); // 今のアイコンを予約し直す = 次に開いても変わらない
        snack('アイコンの変更を取り消しました');
        setTimeout(paintIcons, 300);
        return;
      }
      if (k === queued) { snack('次にアプリを開いたとき、このアイコンに変わります'); return; }
      b.classList.remove('pop'); void b.offsetWidth; b.classList.add('pop'); // 選んだ絵が、ぴょんと跳ねる
      // 今すぐ: アプリを閉じて、ホーム画面のアイコンが変わる (開き直しはしない)。後で: 次にアプリを開いたときに変わる
      askChoice('アイコンを変えます', 'ホーム画面のアイコンは、アプリを閉じると変わります。',
        ['今すぐ (アプリを閉じる)', () => { NHStore.setLauncherIcon(k, true); }],
        ['後で (次に開いたとき)', () => { NHStore.setLauncherIcon(k, false); snack('次にアプリを開いたとき、アイコンが変わります (今のアイコンを押すと取り消せます)'); setTimeout(paintIcons, 300); }]);
    };
    g.appendChild(b);
  });
}

// 隠している間の目印 (棒・小さなボタン) の縦の位置 (settings.barButtonPos = 0〜1。既定 0.5)。小さなボタンをなぞると、アプリ (Kotlin) が書くので、
// S ではなく保存データを直に読み書きする (LIVE_KEYS)。paintBarHandlePage からも使うので、巻き上げられる function で書く
function barPosNow() {
  const raw = (load().settings || {}).barButtonPos;
  return Math.round(Math.min(1, Math.max(0, typeof raw === 'number' ? raw : 0.5)) * 100);
}
function setBarPos(pct) {
  NH.update((d) => { d.settings = d.settings || {}; d.settings.barButtonPos = Math.min(100, Math.max(0, pct)) / 100; });
  paint();
}
$('barPosRange').oninput = (e) => setBarPos(Number(e.target.value));
$$('.stepper[data-pos]').forEach((b) => (b.onclick = () => setBarPos(barPosNow() + Number(b.dataset.pos))));

// 使い方: チュートリアルをもう一度見る・ヒントをもう一度出す (ヒントの出し方は app.js の showHint)
$('tutorialBtn').onclick = () => openUrl('tutorial.html?from=settings');
$('guideBtn').onclick = () => { NH.update((d) => NH.guideStart(d)); openUrl(NH.LIST_URL); }; // 体験型のガイド (core/guide.js)。リストから始める
$('hintsReset').onclick = () => {
  NH.update((d) => { if (d.settings) delete d.settings.hintsSeen; });
  snack('ヒントをもう一度出します');
};

// 小さなボタンの色を自由に決める (部品は上の makeColorPicker と同じ作り。こちらは HTML に置いた四角と帯を使い、色は 3 つの値 (barButtonHue / Sat / Val) で持つ)
const cpick =() => ({ h: S.barButtonHue != null ? S.barButtonHue : 150, s: (S.barButtonSat != null ? S.barButtonSat : 80) / 100, v: (S.barButtonVal != null ? S.barButtonVal : 70) / 100 });
function paintBarPicker() {
  const { h, s, v } = cpick();
  $('cpickSv').style.background = 'linear-gradient(to top, #000, rgba(0,0,0,0)), linear-gradient(to right, #fff, ' + hsv2hex(h, 1, 1) + ')';
  $('cpickSvDot').style.left = (s * 100) + '%'; $('cpickSvDot').style.top = ((1 - v) * 100) + '%'; $('cpickSvDot').style.background = hsv2hex(h, s, v);
  $('cpickHueDot').style.left = '50%'; $('cpickHueDot').style.top = (h / 360 * 100) + '%'; $('cpickHueDot').style.background = hsv2hex(h, 1, 1);
}
// 指・マウスで四角や帯をなぞる (押している間、動きに合わせて色を変える)。キーボードの矢印でも動かせる
function dragIn(el, onMove) {
  const at = (e) => { const r = el.getBoundingClientRect(); return [Math.min(1, Math.max(0, (e.clientX - r.left) / r.width)), Math.min(1, Math.max(0, (e.clientY - r.top) / r.height))]; };
  el.addEventListener('pointerdown', (e) => { el.setPointerCapture(e.pointerId); onMove(...at(e)); e.preventDefault(); });
  el.addEventListener('pointermove', (e) => { if (el.hasPointerCapture(e.pointerId)) onMove(...at(e)); });
  el.addEventListener('pointerup', () => commit());
}
const barColorChanged = () => { S.barButtonColor = 'custom'; paintBarPicker(); commitSoon(); };
dragIn($('cpickSv'), (x, y) => { S.barButtonSat = Math.round(x * 100); S.barButtonVal = Math.round(Math.max(0.2, 1 - y) * 100); barColorChanged(); });
dragIn($('cpickHue'), (x, y) => { S.barButtonHue = Math.min(359, Math.round(y * 360)); barColorChanged(); });
$('cpickSv').addEventListener('keydown', (e) => { const d = { ArrowLeft: [-5, 0], ArrowRight: [5, 0], ArrowUp: [0, 5], ArrowDown: [0, -5] }[e.key]; if (!d) return; const c = cpick(); S.barButtonSat = Math.min(100, Math.max(0, Math.round(c.s * 100) + d[0])); S.barButtonVal = Math.min(100, Math.max(20, Math.round(c.v * 100) + d[1])); barColorChanged(); e.preventDefault(); });
$('cpickHue').addEventListener('keydown', (e) => { const d = { ArrowUp: -10, ArrowDown: 10 }[e.key]; if (!d) return; S.barButtonHue = (cpick().h + d + 360) % 360; barColorChanged(); e.preventDefault(); });
