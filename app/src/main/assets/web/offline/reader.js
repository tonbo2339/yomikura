// offline.html: 本文の画面 (読む・表示の設定)
// ---- 本文 ----
// 書体の表は site/fonts.js (NH_FONTS)。自分で入れたフォントの一覧は NHFonts (アプリ側)
const READER_FONTS = [['site', '標準'], ['gothic', 'ゴシック'], ['mincho', '明朝']];
function readerFonts() {
  let custom = [];
  try { custom = JSON.parse(window.NHFonts ? NHFonts.list() : '[]'); } catch (e) { /* 入れたフォントなし */ }
  return READER_FONTS.concat(custom.map((f) => [NH_FONTS.valueOf(f.id), f.name]));
}
// 入れたフォントの @font-face (読む画面の中で 1 つだけ。話を移るたびに作り直さない)
function setFontFace(font) {
  let st = document.getElementById('nhFontFace');
  if (!st) { st = el('style'); st.id = 'nhFontFace'; document.head.appendChild(st); }
  st.textContent = NH_FONTS.face(font);
}
async function reader(key, ep) {
  const { site, id } = NH.splitKey(key);
  let meta, rec, i;
  try {
    meta = await source.meta(site, id);
    i = ((meta && meta.eps) || []).findIndex((e) => e.ep === ep);
    rec = i >= 0 ? await source.episode(site, id, ep) : null;
  } catch (e) { return showError(e.message); }
  const eps = (meta && meta.eps) || [];
  if (!rec) return i >= 0 ? showError('この話のデータを読めませんでした。保存し直してください') : go(here(key)); // 目録にある話のファイルが無い・壊れている
  document.body.classList.add('reading');
  document.documentElement.style.background = 'var(--surface)';
  const S = settings();
  const vertical = !!S.vertical;
  const prev = eps[i - 1], next = eps[i + 1];

  root.innerHTML = '';
  const top = el('div', 'rbar top');
  const bk = iconBtn('back', '目次', () => go(here(key)));
  const st = iconBtn('text', '表示の設定', openSettings);
  top.append(bk, el('div', 'name', meta.title), st);
  const bottom = el('div', 'rbar bottom');
  const pv = iconBtn('prev', '前の話', () => go(here(key, prev.ep))); pv.disabled = !prev;
  const nx = iconBtn('next', '次の話', () => go(here(key, next.ep))); nx.disabled = !next;
  const pos = el('div', 'pos', (i + 1) + ' / ' + eps.length);
  bottom.append(pv, pos, nx);

  const article = el('div'); article.id = 'text';
  article.innerHTML = NH.cleanHtml(rec.html); // 保存した物でも、表示の前にもう一度安全な要素だけにする
  // 挿絵: 保存した名前 (img/<名前>) を、アプリの配信の場所に書き換える。取れていなければ文字で知らせる
  const imgAt = NH.splitKey(key);
  article.querySelectorAll('img[src^="img/"]').forEach((img) => {
    img.src = 'https://yomikura.local/offline-img/' + imgAt.site + '/' + imgAt.id + '/' + img.getAttribute('src').slice(4);
    img.onerror = () => img.replaceWith(el('span', 'img-missing', '[挿絵が見つかりません]'));
  });
  article.classList.toggle('no-img', !NH.flagOf(S, 'offlineImages')); // 挿絵の表示 (既定は表示)
  const h = el('h2', '', rec.title);
  // 本文の端のナビゲーション (なろうの話ページの、上と下にある「前の話・目次・次の話」と同じ): 横書きは本文の上と下、縦書きは右の端 (読み始め) と左の端 (読み終わり)
  const endBtn = (label, to, off) => { const b = el('button', 'endbtn', label); if (off) b.disabled = true; else b.onclick = () => go(to); return b; };
  const toc = () => endBtn('目次', here(key));
  const endNav = (...btns) => { const d = el('div', 'endnav'); d.append(...btns); return d; };
  const fs = 17 * ((S.size || 100) / 100);
  setFontFace(S.font);
  const css = 'font-size:' + fs + 'px;line-height:' + (S.lh || 1.9) + ';' + (NH_FONTS.family(S.font) ? 'font-family:' + NH_FONTS.family(S.font) + ';' : '');
  let scroller;
  if (vertical) {
    scroller = el('div'); scroller.id = 'vwrap';
    const inner = el('div'); inner.id = 'inner'; inner.style.cssText = css;
    // 縦書きは右から左へ進む: 右の端に「前の話」、左の端 (読み終わり) に「次の話」
    inner.append(endNav(endBtn('前の話', prev && here(key, prev.ep), !prev), toc()), h, article, endNav(toc(), endBtn('次の話', next && here(key, next.ep), !next)));
    scroller.appendChild(inner);
    root.append(top, scroller, bottom);
  } else {
    const page = el('div'); page.id = 'page'; page.style.cssText = css;
    const horizNav = () => endNav(endBtn('‹ 前の話', prev && here(key, prev.ep), !prev), toc(), endBtn('次の話 ›', next && here(key, next.ep), !next));
    page.append(horizNav(), h, article, horizNav());
    root.append(top, page, bottom);
  }
  RICH.apply(article); // 書式 (色・>>N の吹き出し。richtext.js)。色を読むので、画面に付けたあとで
  if (vertical) { NH.uprightRuns(article); NH.uprightRuns(h); } // 見出し (「第 12 話」など) の数字・英字も立てる

  // 読書位置: 本文の進み具合 (0〜1)。縦書きは右から左へ進む (scrollLeft は 0 から負へ)
  // 1 画面に収まる短い話は、動かせないので「最後まで見た」(max が 0) として 1 にする
  const metrics = () => vertical
    ? { pos: -scroller.scrollLeft, max: scroller.scrollWidth - scroller.clientWidth }
    : { pos: window.scrollY, max: document.documentElement.scrollHeight - window.innerHeight };
  const seek = (y) => { const m = metrics(); if (vertical) scroller.scrollLeft = -y * m.max; else window.scrollTo(0, y * m.max); };
  const ratio = (m) => (m.max <= 8 ? 1 : Math.max(0, Math.min(1, m.pos / m.max)));
  let armed = false, timer = 0;
  const saved = (load().progress || {})[key];
  let lastY = saved && saved.ep === ep ? saved.y : null, lastDone = saved && saved.ep === ep ? !!saved.done : null; // 最後に書いた位置 (変わっていなければ書き直さない)
  // 一部の話だけ保存した作品では、順番は保存した中でのもの (2 話目しか無いのに「1 / 2」と出て紛らわしいので「保存 1 / 2」)
  const partial = !!meta.total && eps.length < meta.total;
  // 「次の話」の行き先。保存した次の話が、目次の次の話とは限らない (一部だけ保存・飛びがある) ので、そのときは作品のページへ。null (= 読了) にしない
  const contiguous = !next || !/^(narou|hameln)/.test(site) || Number(next.ep) === Number(ep) + 1; // なろう・ハーメルンの番号は、目次の位置
  const nextUrl = next && contiguous ? next.url : (partial || next ? meta.url : null);
  const write = () => {
    if (!armed) return;
    const m = metrics();
    const y = ratio(m);
    const done = y >= 0.98;
    pos.textContent = (partial ? '保存 ' : '') + (i + 1) + ' / ' + eps.length + ' ・ ' + Math.round(y * 100) + '%';
    if (lastY !== null && Math.abs(y - lastY) < 0.005 && done === lastDone) return;
    lastY = y; lastDone = done;
    NH.update((d) => {
      d.progress = d.progress || {};
      const prev = d.progress[key]; // 同じ話なら、カクヨムの目次の番号 (no) を引き継ぐ (サイトで読んだときに控えたもの)。全話を保存していれば、目次の位置が番号
      const no = prev && prev.ep === ep && prev.no ? prev.no : (meta.total && eps.length === meta.total ? i + 1 : undefined);
      d.progress[key] = { url: rec.url, ep: ep, no, y, done, next: nextUrl, at: Date.now() };
      NH.pruneProgress(d);
    });
  };
  const target = vertical ? scroller : window;
  // 進む向きへ動かすと上部バーを隠し、戻すと出す (サイトのページと同じ)。進む = 縦書きは左へ、横書きは下へ
  let lastPos = metrics().pos, acc = 0, barHidden = false, mute = 0;
  const setBar = (h) => { barHidden = h; acc = 0; mute = Date.now() + 450; document.body.classList.toggle('bars-hidden', h); NH.off.readerBar(h); }; // バーの出入りで画面の広さが変わり、スクロールの知らせが出るので、しばらく無視する
  // バーを隠す設定 (スクロールで隠す・普段は隠しておく) のときは、Kindle のように、本文を画面いっぱいに置いて、バーは本文の上に半透明で重ねる。
  // 横書きでバーを隠す設定: 出すのは本文のタップだけ (スクロールでは出さない)。出ているバーは、スクロールし始めると隠す。縦書き: 出すのはタップだけ (戻す向きでは出さない)。出ているバーは、進む向きで隠す。
  // どちらも、本文の広さは変わらないので、文字は動かない (上のタブバーも、縦書きでは本文に重ねる = アプリ側 MainActivity.applyBarOverlay)
  const overlay = vertical || NH.barModeOf(S) !== 'always'; // 縦書きは、タブバーの設定に関わらず、本文のバーを重ねる (タブバーを隠す設定なら、タブバーも同時に出し入れ。「いつも表示」なら出したまま)
  document.body.classList.toggle('bars-overlay', overlay);
  document.body.classList.toggle('bars-opaque', NH.flagOf(S, 'readerBarOpaque', false)); // バーを不透明にする設定
  const barScroll = () => {
    const m = metrics(), p = m.pos, dy = p - lastPos; lastPos = p;
    if (Date.now() < mute) return;
    if (overlay && vertical) { // 縦書き: 出すのは本文のタップだけ (戻す向きでは出さない。サイトの縦書きと同じ)。出ているバーは、進む向き (左へ = pos が増える) に動かすと隠す
      if (dy > 0) { acc = Math.max(0, acc) + dy; if (!barHidden && acc > 24) setBar(true); }
      else if (dy < 0) acc = Math.min(0, acc) + dy;
      return;
    }
    if (overlay) { if (!barHidden) { acc += Math.abs(dy); if (acc > 24) setBar(true); } return; }
    if (p <= 0) { acc = 0; if (barHidden) setBar(false); return; }
    if (m.max > 8 && m.max - p < 6) { // 最後まで来たら出す (前後の話へ行けるように)。バーが出ると画面が縮むので、出し終えてから、もう一度最後まで進める
      acc = 0;
      if (barHidden) { setBar(false); setTimeout(() => seek(1), 520); }
      return;
    }
    if (dy > 0) { acc = Math.max(0, acc) + dy; if (!barHidden && acc > 24 && p > 56) setBar(true); }
    else if (dy < 0) { acc = Math.min(0, acc) + dy; if (barHidden && acc < -16) setBar(false); }
  };
  target.addEventListener('scroll', () => { barScroll(); clearTimeout(timer); timer = setTimeout(write, 600); }, { passive: true });
  // 本文のタップで、バーを出し入れする (リンク・ボタンのタップは除く)
  target.addEventListener('click', (e) => { if (!e.target.closest('a,button,.rbar,.endbtn')) setBar(!barHidden); });
  if (overlay) { // 始めはバーを隠して、本文だけを見せる。アプリ側がまだ読書ページと認識していないことがあるので、少し待って、もう一度頼む
    setBar(true);
    setTimeout(() => { if (barHidden) { mute = Date.now() + 450; NH.off.readerBar(true); } }, 700);
    // 初回だけ、バーの出し方を知らせる (本文だけが出ていて、気づきにくいため)。一度出したら、もう出さない
    if (!NH.flagOf(S, "readerTapHintShown", false)) setTimeout(() => { snack("本文をタップすると、バーが出ます (もう一度タップで隠れます)"); setSetting("readerTapHintShown", true); }, 1800);
  }
  window.addEventListener('pagehide', write);
  document.addEventListener('visibilitychange', () => { if (document.hidden) write(); });
  ['touchstart', 'wheel'].forEach((ev) => target.addEventListener(ev, () => { armed = true; }, { passive: true }));

  if (saved && saved.ep === ep && !saved.done && saved.y > 0.005) {
    [0, 200, 600].forEach((ms) => setTimeout(() => { if (!armed) seek(saved.y); }, ms));
    setTimeout(() => { if (!armed) snack('前回の位置から再開'); }, 300);
  }
  // 読み終えた話を開き直しただけでは、位置を書き換えない (読了が外れる)
  setTimeout(() => { armed = true; if (!(saved && saved.ep === ep && saved.done)) write(); }, 1200);

  // 表示の設定 (本文の文字は、サイトを読むときの設定と共通)
  function openSettings() {
    const sh = $('sheet'); sh.innerHTML = '';
    sh.append(el('div', 'grip'), el('h2', '', '表示'));
    const g = el('div', 'group');
    const stepper = (label, k, def, min, max, stepBy, fmt) => {
      const r = el('div', 'setrow');
      const val = el('span', 'val');
      const cur = () => { const v = settings()[k]; return v == null || v < min ? def : v; }; // 設定の画面の「標準」(行間 0) などは既定の値として扱う
      const paint = () => (val.textContent = fmt(cur()));
      const mk = (txt, dir) => { const b = el('button', 'step', txt); b.setAttribute('aria-label', label + (dir < 0 ? 'を小さく' : 'を大きく')); b.onclick = () => { setSetting(k, Math.round(Math.max(min, Math.min(max, cur() + dir * stepBy)) * 100) / 100); paint(); }; return b; };
      r.append(el('span', 'lbl', label), mk('−', -1), val, mk('＋', 1)); paint();
      return r;
    };
    const vr = el('label', 'setrow');
    const sw = el('input', 'switch'); sw.type = 'checkbox'; sw.checked = vertical;
    sw.onchange = () => setSetting('vertical', sw.checked);
    vr.append(el('span', 'lbl', '縦書き'), sw);
    const fr = el('div', 'setrow');
    const fonts = readerFonts();
    if (fonts.length <= READER_FONTS.length) {
      const seg = el('div', 'seg');
      fonts.forEach(([v, t]) => {
        const b = el('button', (S.font || 'site') === v ? 'on' : '', t);
        b.onclick = () => { setSetting('font', v); seg.querySelectorAll('button').forEach((x) => x.classList.toggle('on', x === b)); };
        seg.appendChild(b);
      });
      fr.append(el('span', 'lbl', '書体'), seg);
    } else {
      // 入れたフォントがあるときは、並べきれないので選ぶ形にする
      const sel = el('select', 'picker');
      fonts.forEach(([v, t]) => { const o = el('option', '', t); o.value = v; o.selected = (S.font || 'site') === v; sel.appendChild(o); });
      sel.onchange = () => setSetting('font', sel.value);
      fr.append(el('span', 'lbl', '書体'), sel);
    }
    const ir = el('label', 'setrow');
    const isw = el('input', 'switch'); isw.type = 'checkbox'; isw.checked = NH.flagOf(S, 'offlineImages');
    isw.onchange = () => setSetting('offlineImages', isw.checked);
    ir.append(el('span', 'lbl', '挿絵を表示する'), isw);
    g.append(vr, fr, stepper('文字の大きさ', 'size', 100, 80, 250, 10, (v) => v + '%'), stepper('行の間隔', 'lh', 1.9, 1.2, 3, 0.1, (v) => v.toFixed(1)), ir);
    sh.append(g, el('p', 'hint', '閉じると反映します'));
    sheetShow(sh, '表示', () => { sheetHide(sh); location.reload(); });
  }
}
