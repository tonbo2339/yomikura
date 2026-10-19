// list.html: 読書位置と読み具合の表示・作品を開く先
// ---- 読書位置と読み具合 ----
const progressOf = NH.progressOf; // 更新取得のあとに読んでいなければ使わない
const pctText = (x) => Math.floor(x * 100) + '%'; // 99.6% を 100% と出さない (読み終えていないのに 100% にしない)
// 読み具合の表示 (カード・上のカード共通)。NH.readState から、主な文 (main)・添える文 (note)・割合 (pct: 0〜1 | null) を作る
//   連載: 「12 / 120話」と作品全体の割合 (履歴の話数の書き方と同じ)。短編: 「短編」と、その話の割合
function stateView(st) {
  if (st.short) { // 「短編」の印は連載状態の欄 (カードの印・上のカードの添え書き) に出る。ここは読んだ状態だけ
    if (!st.started) return { main: 'まだ読んでいません', note: '', pct: null };
    return { main: st.epPct >= 1 ? '読了' : '読みかけ', note: '', pct: st.epPct };
  }
  const tot = st.total ? st.total + '話' : '';
  if (!st.started) return tot ? { main: tot, note: 'まだ読んでいません', pct: null } : { main: 'まだ読んでいません', note: '', pct: null }; // 0% の棒は出さない (読んでいないことは文で分かる)
  if (st.finished) return { main: tot ? tot + ' 読了' : '最新話まで読了', note: st.complete ? '' : '新しい話を待っています', pct: 1 }; // 完結した作品は、新しい話を待たない
  const main = st.ep && st.total ? st.ep + ' / ' + st.total + '話' : st.ep ? st.ep + '話' : tot || '読みかけ';
  // 全話数が分からないときは、その話の中の割合を添える (作品全体の割合は出せない)
  const note = st.fromSite ? 'サイトのしおりから' : st.workPct == null && st.epPct > 0 ? 'この話 ' + pctText(st.epPct) : '';
  return { main, note, pct: st.workPct };
}
// 読み具合の欄: 文と割合の行、その下に進みの棒
function progressBlock(st, cls) {
  const v = stateView(st);
  const box = el('div', cls || 'pg');
  const row = el('div', 'pg-row');
  row.appendChild(el('span', 'pg-main', v.main));
  if (v.note) row.appendChild(el('span', 'pg-note', v.note));
  if (v.pct != null) row.appendChild(el('span', 'pg-pct', pctText(v.pct)));
  box.appendChild(row);
  if (v.pct != null) {
    const bar = el('div', 'pg-bar'); const fill = el('i'); fill.style.width = Math.round(v.pct * 1000) / 10 + '%';
    bar.setAttribute('role', 'progressbar'); bar.setAttribute('aria-label', '読んだ割合');
    bar.setAttribute('aria-valuemin', '0'); bar.setAttribute('aria-valuemax', '100'); bar.setAttribute('aria-valuenow', String(Math.floor(v.pct * 100)));
    bar.appendChild(fill); box.appendChild(bar);
  }
  return box;
}
// 連載の作品で「続きから」と「目次」を選ぶボタン (短編は目次が無いので出さない)
function goButtons(d, it, list, st) {
  const row = el('div', 'go');
  const resume = el('button', 'go-btn primary');
  resume.innerHTML = svg('play');
  // 押したときに開く先と合わせる (1 話目の URL が分からないサイトは、作品のページを開くので「読みはじめる」)
  const label = st.started ? (st.finished ? '最新話を開く' : '続きから') : NH.firstEpUrl(it.site, it.id) ? '1 話から読む' : '読みはじめる';
  resume.appendChild(el('span', '', label));
  resume.onclick = (e) => { e.stopPropagation(); openWork(d, it, list); };
  const toc = el('button', 'go-btn');
  toc.innerHTML = svg('toc');
  toc.appendChild(el('span', '', '目次'));
  toc.onclick = (e) => { e.stopPropagation(); openWork(d, it, list, true); };
  row.append(resume, toc);
  return row;
}

// 絞り込み (サイト・タグ・連載状態) か検索をしているか
const isNarrowed = () => !!(ui.site || ui.tag || ui.status || queryTerms().length);

// 作品を開く。絞り込み・検索中か、自分で並べた順以外で並べているなら、いま並んでいる順を控えて、読了のあとはその続きを次の作品にする
// (どちらでもなければ控えを消す。次の作品はリストの順になる)
// toc = 目次 (作品のトップ) を開く。まだ読み始めていない連載は 1 話目から (1 話目の URL が分かるサイトだけ)
function openWork(d, it, list, toc) {
  const keep = (isNarrowed() || sortMode(d) !== 'manual') && list;
  NH.update((x) => NH.setReadSeq(x, keep ? list.map((y) => y.key) : null));
  openUrl(toc ? tocTarget(it) : startTarget(d, it) || openTarget(d, it));
}
function startTarget(d, it) {
  const st = NH.readState(d, it);
  if (st.started || st.short || navigator.onLine === false) return null;
  return NH.firstEpUrl(it.site, it.id, it.url);
}
// 目次の開く先。通信できないとき、オフライン保存のある作品は保存した目次
function tocTarget(it) {
  const online = NH.workUrl(it.site, it.id, it.url) || it.url;
  if (navigator.onLine !== false || !NH.off.available() || !NH.off.meta(it.site, it.id)) return online;
  return 'offline.html?w=' + encodeURIComponent(it.key);
}

// 開く先。通信できないとき、オフライン保存のある作品は保存した本文で開く (続きの話が保存してあればそこから)
function openTarget(d, it) {
  const online = resumeUrl(d, it);
  if (navigator.onLine !== false || !NH.off.available()) return online;
  const meta = NH.off.meta(it.site, it.id);
  if (!meta || !(meta.eps || []).length) return online;
  const eps = meta.eps;
  const p = (d.progress || {})[it.key];
  const cur = p ? eps.findIndex((e) => e.ep === p.ep) : -1;
  const at = cur < 0 ? 0 : p.done ? Math.min(eps.length - 1, cur + 1) : cur;
  return 'offline.html?w=' + encodeURIComponent(it.key) + '&ep=' + encodeURIComponent(eps[at].ep);
}
