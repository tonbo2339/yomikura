// offline.html: 本文の画面 (読む・表示の設定)
// ---- 本文 ----
const FONTS = { gothic: '"Noto Sans CJK JP","Noto Sans JP",sans-serif', mincho: '"Noto Serif CJK JP","Noto Serif JP",serif' };
async function reader(key, ep) {
  const { site, id } = NH.splitKey(key);
  let meta, rec, i;
  try {
    meta = await source.meta(site, id);
    i = ((meta && meta.eps) || []).findIndex((e) => e.ep === ep);
    rec = i >= 0 ? await source.episode(site, id, ep) : null;
  } catch (e) { return showError(e.message); }
  const eps = (meta && meta.eps) || [];
  if (!rec) return go(here(key));
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
  const fs = 17 * ((S.size || 100) / 100);
  const css = 'font-size:' + fs + 'px;line-height:' + (S.lh || 1.9) + ';' + (FONTS[S.font] ? 'font-family:' + FONTS[S.font] + ';' : '');
  let scroller;
  if (vertical) {
    scroller = el('div'); scroller.id = 'vwrap';
    const inner = el('div'); inner.id = 'inner'; inner.style.cssText = css;
    inner.append(h, article); scroller.appendChild(inner);
    root.append(top, scroller, bottom);
  } else {
    const page = el('div'); page.id = 'page'; page.style.cssText = css;
    page.append(h, article);
    root.append(top, page, bottom);
  }

  // 読書位置: 本文の進み具合 (0〜1)。縦書きは右から左へ進む (scrollLeft は 0 から負へ)
  // 1 画面に収まる短い話は、動かせないので「最後まで見た」(max が 0) として 1 にする
  const metrics = () => vertical
    ? { pos: -scroller.scrollLeft, max: scroller.scrollWidth - scroller.clientWidth }
    : { pos: window.scrollY, max: document.documentElement.scrollHeight - window.innerHeight };
  const seek = (y) => { const m = metrics(); if (vertical) scroller.scrollLeft = -y * m.max; else window.scrollTo(0, y * m.max); };
  const ratio = (m) => (m.max <= 8 ? 1 : Math.max(0, Math.min(1, m.pos / m.max)));
  let armed = false, timer = 0;
  const write = () => {
    if (!armed) return;
    const m = metrics();
    const y = ratio(m);
    pos.textContent = (i + 1) + ' / ' + eps.length + ' ・ ' + Math.round(y * 100) + '%';
    NH.update((d) => {
      d.progress = d.progress || {};
      const prev = d.progress[key]; // 同じ話なら、カクヨムの目次の番号 (no) を引き継ぐ (サイトで読んだときに控えたもの)
      d.progress[key] = { url: rec.url, ep: ep, no: prev && prev.ep === ep && prev.no ? prev.no : undefined, y, done: y >= 0.98, next: next ? next.url : null, at: Date.now() };
      NH.pruneProgress(d);
    });
  };
  const target = vertical ? scroller : window;
  // 進む向きへ動かすと上部バーを隠し、戻すと出す (サイトのページと同じ)。進む = 縦書きは左へ、横書きは下へ
  let lastPos = metrics().pos, acc = 0, barHidden = false, mute = 0;
  const setBar = (h) => { barHidden = h; acc = 0; mute = Date.now() + 450; NH.off.readerBar(h); }; // バーの出入りで画面の広さが変わり、スクロールの知らせが出るので、しばらく無視する
  const barScroll = () => {
    const p = metrics().pos, dy = p - lastPos; lastPos = p;
    if (Date.now() < mute) return;
    if (p <= 0) { acc = 0; if (barHidden) setBar(false); return; }
    if (dy > 0) { acc = Math.max(0, acc) + dy; if (!barHidden && acc > 24 && p > 56) setBar(true); }
    else if (dy < 0) { acc = Math.min(0, acc) + dy; if (barHidden && acc < -16) setBar(false); }
  };
  target.addEventListener('scroll', () => { barScroll(); clearTimeout(timer); timer = setTimeout(write, 600); }, { passive: true });
  window.addEventListener('pagehide', write);
  document.addEventListener('visibilitychange', () => { if (document.hidden) write(); });
  ['touchstart', 'wheel'].forEach((ev) => target.addEventListener(ev, () => { armed = true; }, { passive: true }));

  const saved = (load().progress || {})[key];
  if (saved && saved.ep === ep && !saved.done && saved.y > 0.005) {
    [0, 200, 600].forEach((ms) => setTimeout(() => { if (!armed) seek(saved.y); }, ms));
    setTimeout(() => { if (!armed) snack('前回の位置から再開'); }, 300);
  }
  setTimeout(() => { armed = true; write(); }, 1200);

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
    const seg = el('div', 'seg');
    [['site', '標準'], ['gothic', 'ゴシック'], ['mincho', '明朝']].forEach(([v, t]) => {
      const b = el('button', (S.font || 'site') === v ? 'on' : '', t);
      b.onclick = () => { setSetting('font', v); seg.querySelectorAll('button').forEach((x) => x.classList.toggle('on', x === b)); };
      seg.appendChild(b);
    });
    fr.append(el('span', 'lbl', '書体'), seg);
    const ir = el('label', 'setrow');
    const isw = el('input', 'switch'); isw.type = 'checkbox'; isw.checked = NH.flagOf(S, 'offlineImages');
    isw.onchange = () => setSetting('offlineImages', isw.checked);
    ir.append(el('span', 'lbl', '挿絵を表示する'), isw);
    g.append(vr, fr, stepper('文字の大きさ', 'size', 100, 80, 250, 10, (v) => v + '%'), stepper('行の間隔', 'lh', 1.9, 1.2, 3, 0.1, (v) => v.toFixed(1)), ir);
    sh.append(g, el('p', 'hint', '閉じると反映します'));
    sheetShow(sh, '表示', () => { sheetHide(sh); location.reload(); });
  }
}
