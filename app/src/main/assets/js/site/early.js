// ページ描画前に入れる CSS。ロジックは含めない。
//  - 読書ページのヘッダー / フッターの非表示 (ほかのページでは出す)
//  - 設定画面で選んだ本文のフォント (自分で入れたフォントは @font-face で読む。書体の表は fonts.js) / 文字サイズ / 行間
(function () {
  const h = location.hostname;
  // 保存データの窓口に添える合言葉 (core/base.js と同じ。アプリがこのスクリプトの前に入れる)
  const T = window.__nhT || '';
  try { delete window.__nhT; } catch (e) { /* 消せなくても動く */ }
  let css = '';
  // ページの下の欄を畳む対象 (sites.js の fold。ui.js が開閉のボタンを置く)。開いているものは data-nh-open が付く
  let foldSite = ''; // 開き具合を覚えるときのサイト名 (settings.foldOpen[サイト][欄])
  let fold = null; // {comments, recommend, links, reactions: セレクター}
  let body = null; // {box: 拡大の対象, text: フォント・行間の対象}

  // サイトごとの CSS・セレクターは sites.js (window.NH_SITES)。このスクリプトの前にアプリが入れる
  const SITES = window.NH_SITES || {};
  const siteKey = h === 'syosetu.org' || h === 'h.syosetu.org' ? 'hameln' : h === 'kakuyomu.jp' ? 'kakuyomu' : h === 'syosetu.com' || h.endsWith('.syosetu.com') ? 'narou' : '';
  const site = SITES[siteKey];
  if (site) {
    css = site.hide;
    body = site.body;
    fold = site.fold;
    foldSite = siteKey;
  }
  // ヘッダー・フッター (sites.js の bars) は読書ページ (episode) だけ隠す。別の <style id="nh-bars"> にして、media で有効 / 無効を切り替える
  const bars = (site && site.bars) || '';
  const isReading = () => !!(site && site.episode && site.episode.test(location.pathname));

  // オフライン保存 (#nh-save): ページの中身を描かずに、保存の画面 (ui.js の .nh-ov) と同じ地色で覆う。
  // ui.js はページの読み込みが終わってから画面を出すので、それまでサイトのページがちらっと見えないように。
  // 覆いは別の <style id="nh-cover"> にして、ui.js が外す。ui.js が動かなかったときのために、一定時間で自動的にも外す
  let cover = '';
  if (location.hash === '#nh-save') {
    cover = 'html{background:#12100e!important}body{visibility:hidden!important}.nh-ov{visibility:visible!important}' +
      'html::after{content:"準備しています";position:fixed;inset:0;display:flex;align-items:center;justify-content:center;' +
      'background:#12100e;color:#f2ede7;font:800 19px "Noto Sans JP",sans-serif;z-index:2147483646}';
  }

  // 本文の文字サイズ: 「100%」がどのサイトでも端末の標準の文字の大きさ (font-size: medium) になるよう、
  // サイトの本文の元の大きさとの比 (なろう系は本文が小さめ) を掛けて大きくする。方法は 2 つ (どのサイトも同じ処理):
  //  - font-size (はじめはこちら): zoom と違い、本文の中の位置の計算 (ハーメルンの「>>233」を押している間の吹き出しなど、サイトの JS が座標を出す物) をずらさない
  //  - zoom: サイトが本文の囲みの文字の大きさを !important で決めている (なろうの「表示調整」) と font-size は効かない。そのときは、サイトの大きさに重ねて効く zoom にする
  // font-size が効いたかは、ページが読めてから測る (fitSize)。効かなければ zoom に切り替え、控えて、次のページから最初から使う。
  // 比もページが読めてから測り、この origin の localStorage に控えて次のページでは描画前から使う。
  // 測るのは本文の囲みの親 (なろうの「表示調整」など、サイト側で本文に付けた大きさはそのまま活かす)
  let size = null; // {want: 設定の倍率, ratio: 控えてあった比, mode: 'font' | 'zoom'}
  const SIZE_KEY = 'nh-size-ratio';
  const MODE_KEY = 'nh-size-mode';
  const sizeCss = () => {
    const z = Math.round(size.want * size.ratio * 1000) / 1000;
    if (z === 1) return '';
    return body.box + (size.mode === 'zoom' ? '{zoom:' + z + '}' : '{font-size:calc(1em * ' + z + ')}');
  };

  // 設定 (NHStore が document-start で使えない場合は何もしない)
  try {
    const raw = window.NHStore && window.NHStore.get(T);
    const s = (raw && JSON.parse(raw).settings) || {};
    if (fold) {
      const hide = (sel) => sel.split(',').map((x) => x + ':not([data-nh-open])').join(',') + '{display:none!important}';
      // 「開き具合を覚える」(foldRemember、既定オン) のとき、前に開いたままにした欄は畳まない
      const kept = (s.foldRemember !== false && s.foldOpen && s.foldOpen[foldSite]) || {};
      (window.NH_FOLDS || []).forEach((f) => { if (fold[f.key] && s[f.flag] !== false && !kept[f.key]) css += hide(fold[f.key]); });
    }
    if (body) {
      let ratio = 1, mode = 'font';
      try {
        ratio = Number(localStorage.getItem(SIZE_KEY)) || 1;
        if (localStorage.getItem(MODE_KEY) === 'zoom') mode = 'zoom';
      } catch (e) { /* 使えない */ }
      size = { want: (s.size || 100) / 100, ratio, mode };
      const F = window.NH_FONTS; // 書体の表 (site/fonts.js)
      if (F && F.family(s.font)) css += F.face(s.font, true) + body.text + '{font-family:' + F.family(s.font) + '!important}';
      if (s.lh) css += body.text + '{line-height:' + s.lh + '!important}';
    }
  } catch (e) { /* 設定なし */ }

  if (!css && !bars && !cover && !size) return;
  const add = (text, id) => {
    const el = document.createElement('style');
    if (id) el.id = id;
    el.textContent = text;
    (document.head || document.documentElement).appendChild(el);
    return el;
  };
  // 本文の元の大きさを測って比を控え、控えと違っていれば (初めてのサイト・サイト側の変更) 直す。font-size が効いていなければ zoom に切り替える
  const fitSize = () => {
    const box = document.querySelector(body.box);
    if (!box || !box.parentElement || !document.body) return;
    const probe = document.createElement('div');
    probe.style.cssText = 'position:absolute;visibility:hidden;font-size:medium';
    document.body.appendChild(probe);
    const std = parseFloat(getComputedStyle(probe).fontSize);
    probe.remove();
    const base = parseFloat(getComputedStyle(box.parentElement).fontSize);
    if (!std || !base) return;
    const ratio = Math.round((std / base) * 1000) / 1000;
    try { localStorage.setItem(SIZE_KEY, String(ratio)); } catch (e) { /* 使えない */ }
    if (ratio !== size.ratio) { size.ratio = ratio; applySize(); }
    checkMode(box);
    watchMode(box);
  };
  const applySize = () => { (document.getElementById('nh-size') || add('', 'nh-size')).textContent = sizeCss(); };
  // font-size が効いているか: 本文の囲みの文字の大きさが、親の大きさ × 倍率になっているか (2% まで許す)。効いていなければ zoom にする
  const checkMode = (box) => {
    const z = size.want * size.ratio;
    if (size.mode !== 'font' || z === 1 || !box.parentElement) return;
    const base = parseFloat(getComputedStyle(box.parentElement).fontSize), got = parseFloat(getComputedStyle(box).fontSize), want = base * z;
    if (!base || !got || Math.abs(got - want) <= want * 0.02) return;
    size.mode = 'zoom';
    try { localStorage.setItem(MODE_KEY, 'zoom'); } catch (e) { /* 使えない */ }
    applySize();
  };
  // サイトの JS が、ページが読めたあとで本文の囲みに大きさを付ける (なろうの「表示調整」は style 属性に font-size: 100%) ので、
  // 読み込みが終わったとき・囲みの style / class が変わったときにも確かめる
  let watching = false;
  const watchMode = (box) => {
    if (watching) return;
    watching = true;
    window.addEventListener('load', () => checkMode(box));
    if (typeof MutationObserver === 'function') new MutationObserver(() => checkMode(box)).observe(box, { attributes: true, attributeFilter: ['style', 'class'] });
  };
  // カクヨムは話から目次へ、ページを読み込み直さずに移る (Next.js)。history の書き換えと戻る操作のたびに切り替える
  const putBars = () => {
    const el = add(bars, 'nh-bars');
    const sync = () => { el.media = isReading() ? 'all' : 'not all'; };
    sync();
    ['pushState', 'replaceState'].forEach((m) => {
      const orig = history[m];
      if (typeof orig !== 'function') return;
      history[m] = function () { const r = orig.apply(this, arguments); sync(); return r; };
    });
    window.addEventListener('popstate', sync);
  };
  const put = () => {
    if (css) add(css);
    if (bars) putBars();
    if (size) {
      const sc = sizeCss();
      if (sc) add(sc, 'nh-size');
      if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', fitSize);
      else fitSize();
    }
    if (cover) {
      add(cover, 'nh-cover');
      setTimeout(() => {
        const c = document.getElementById('nh-cover');
        if (c) c.remove();
      }, 25000);
    }
  };
  if (document.documentElement) put();
  else document.addEventListener('DOMContentLoaded', put);
})();
