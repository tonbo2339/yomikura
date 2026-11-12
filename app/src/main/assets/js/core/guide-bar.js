// Yomikura 共通基盤: 体験型のガイドの案内バー (画面の下に出る 1 枚。上は、ページの見出しのボタンに重なるので使わない)。手順・判定は guide.js。ここは、表示と、1 秒おきの見張りだけ。
// アプリ内ページ・サイトのページのどちらにも出す (core はどちらにも入る)。ガイドが進行中のページだけ動く。スタイルは、サイトの CSS に負けないよう、ここの中だけで持つ。
// 出さない所: 画面に出さない裏の WebView (保存・更新の確認。誰も見ていないのに進めない)・iframe・設定 / 使い方 / 初回の画面・縦書きの読書 (本文を隠さない)
window.NH.define('guide-bar', function (NH) {
  if (typeof document === 'undefined' || !document.documentElement) return;
  if (window.top !== window || window.NH_BG_JOB) return;

  const APP_HOST = 'yomikura.local';
  const QUIET_PAGES = /\/(settings|tutorial|welcome|error)\.html$/; // この画面では出さない (進み具合は見張る)
  const DONE_SHOW_MS = 6000; // 終えたときの一言を出している間
  let bar = null, text = null, badge = null, stopBtn = null, timer = 0, doneUntil = 0;

  const onApp = () => location.host === APP_HOST;
  const visible = () => document.visibilityState === 'visible';

  // いまのページの様子 (guide.js の obs)
  function observe() {
    let a = null, onRanking = false, onWork = false;
    try { a = onApp() ? null : NH.adapter && NH.adapter(); } catch (e) { /* サイトのページでなければ、サイトの判定は無い */ }
    if (a) {
      try { onRanking = !!(a.rankingItems && a.rankingItems().length); } catch (e) { /* ページの作りが違えば、そのページは違う */ }
      try { onWork = !!NH.parseWork(location.href); } catch (e) { /* 同上 */ }
    }
    const onList = onApp() && /\/list\.html$/.test(location.pathname);
    const reader = onApp() && /\/offline\.html$/.test(location.pathname) && /[?&]ep=/.test(location.search); // 保存した話を読む画面も「作品を開いた」
    return { onRanking, onList, onWork: onWork || reader };
  }

  // 本文を隠さない: 縦書きの読書 (サイトの縦書き・保存した作品の縦書き)
  const hideForReading = () => {
    const c = document.documentElement.classList;
    return c.contains('nh-v') || c.contains('nh-vbars') || (onApp() && /\/offline\.html$/.test(location.pathname) && /[?&]ep=/.test(location.search));
  };

  function make() {
    bar = document.createElement('div');
    bar.setAttribute('role', 'status');
    bar.setAttribute('aria-live', 'polite');
    bar.id = 'nh-guide';
    bar.style.cssText = 'position:fixed;left:8px;right:8px;max-width:720px;margin:0 auto;z-index:2147483000;box-sizing:border-box;display:flex;align-items:center;gap:10px;'
      + 'padding:10px 12px;border-radius:14px;background:rgba(43,38,33,.96);color:#f2ede7;font:14px/1.5 "Noto Sans JP",sans-serif;box-shadow:0 4px 14px rgba(0,0,0,.3);'
      + 'writing-mode:horizontal-tb;direction:ltr;text-align:left';
    badge = document.createElement('span');
    badge.style.cssText = 'flex:none;padding:2px 8px;border-radius:999px;background:#1e9a55;color:#fff;font:700 12px/1.5 "Noto Sans JP",sans-serif;white-space:nowrap';
    text = document.createElement('span');
    text.style.cssText = 'flex:1;min-width:0';
    stopBtn = document.createElement('button');
    stopBtn.type = 'button';
    stopBtn.textContent = 'やめる';
    stopBtn.style.cssText = 'flex:none;border:0;background:none;color:#5cc58c;font:700 14px "Noto Sans JP",sans-serif;padding:6px 4px;cursor:pointer';
    stopBtn.onclick = () => { NH.update((d) => NH.guideStop(d)); remove(); stopTimer(); };
    bar.append(badge, text, stopBtn);
    document.documentElement.appendChild(bar);
  }
  function remove() { if (bar) { bar.remove(); bar = null; } }
  function show(label, message) {
    if (!bar) make();
    // サイトの一覧の下に絞り込みバー (ui-style.js の .nh-filter) が出ているときは、その上に避ける
    bar.style.bottom = document.documentElement.classList.contains('nh-has-filter') ? 'calc(72px + env(safe-area-inset-bottom,0px))' : 'calc(12px + env(safe-area-inset-bottom,0px))';
    badge.textContent = label;
    badge.style.display = label ? '' : 'none';
    stopBtn.style.display = label ? '' : 'none'; // 終えたときの一言には、やめるボタンは要らない
    if (text.textContent !== message) text.textContent = message;
  }
  const stopTimer = () => { if (timer) { clearInterval(timer); timer = 0; } };

  function tick() {
    if (!visible()) return;
    const d = NH.load();
    if (doneUntil) { // 終えた直後の一言
      if (Date.now() < doneUntil) return;
      doneUntil = 0; remove(); stopTimer(); return;
    }
    if (!NH.guideActive(d)) { remove(); stopTimer(); return; }
    const next = NH.guideNext(d, observe());
    if (next !== NH.guideStep(d)) NH.update((x) => NH.guideSet(x, next)); // 進んだときだけ書く (毎秒書くと、アプリが毎秒保存の変化を受ける)
    if (next >= NH.GUIDE_STEPS.length) {
      if (!QUIET_PAGES.test(location.pathname) || !onApp()) { doneUntil = Date.now() + DONE_SHOW_MS; show('', NH.GUIDE_DONE_TEXT); }
      else stopTimer();
      return;
    }
    if ((onApp() && QUIET_PAGES.test(location.pathname)) || hideForReading()) { remove(); return; }
    const now = NH.load();
    show((NH.guideStep(now) + 1) + ' / ' + NH.GUIDE_STEPS.length, NH.guideText(now));
  }

  function start() {
    if (timer || !NH.guideActive(NH.load())) return;
    tick();
    timer = setInterval(tick, 1000);
  }
  start(); // ガイドを始める入口 (チュートリアル・設定) はリストへ移動するので、ページを開いたときに見張りを始めれば足りる
});
