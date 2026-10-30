// list.html: 履歴のタブ
// ---- 履歴のタブ (旧 history.html。閲覧した作品を日付ごとに。検索はこのタブの中だけ) ----
const H_FILTERS = [['all', 'すべて'], ['app', 'アプリで読んだ'], ['site', '公式のみ']];
const H_MATCH = { all: () => true, app: (e) => !!e.appAt, site: (e) => !e.appAt && !!e.siteAt };
const H_OFFICIAL = { narou: 'https://yomou.syosetu.com/rireki/list/', hameln: 'https://syosetu.org/?mode=rireki', kakuyomu: 'https://kakuyomu.jp/my/antenna/reading_histories' };
const H_TIME = new Intl.DateTimeFormat('ja-JP', { hour: '2-digit', minute: '2-digit' });
const midnight = (t) => { const x = new Date(t); x.setHours(0, 0, 0, 0); return x.getTime(); };
function dayLabel(t) {
  const days = Math.round((midnight(Date.now()) - midnight(t)) / 86400000);
  const d = new Date(t);
  return days === 0 ? '今日' : days === 1 ? '昨日' : (d.getMonth() + 1) + '月' + d.getDate() + '日';
}
// 開く先: リストにあれば「続きから」、そうでなければ履歴の URL (アプリで読んだ話 → 公式の続き)
function historyOpenUrl(d, e) {
  const it = d.readLater.find((x) => x.key === e.key);
  return it ? NH.resumeUrl(d, it) : NH.resumeUrl(d, { key: e.key, url: e.url || e.siteUrl });
}
// 「読んだ話」の欄を開いている作品 (画面を描き直しても開いたままにする)
const H_OPEN = new Set();
// 開いた欄 (長編だけ): 日ごとに読んだ話 (新しい日から 10 日分) と、「続きから」「目次」のボタン
function historyLog(d, e) {
  const box = el('div', 'hlog');
  box.onclick = (ev) => ev.stopPropagation(); // 行全体の「開く」を押したことにしない
  const days = Object.keys(e.reads || {}).sort().reverse();
  if (!days.length) box.appendChild(el('div', 'hlog-none', '読んだ話の記録はまだありません'));
  days.slice(0, 10).forEach((k) => {
    const [y, m, dd] = k.split('-').map(Number);
    const row = el('div', 'hlog-row');
    row.append(el('span', 'hlog-day', dayLabel(new Date(y, m - 1, dd, 12).getTime())), el('span', 'hlog-eps', NH.fmtEpisodes(e.reads[k], 12) + '話' + (e.reads[k].length > 12 ? ' (計 ' + e.reads[k].length + '話)' : '')));
    box.appendChild(row);
  });
  if (days.length > 10) box.appendChild(el('div', 'hlog-none', 'ほか ' + (days.length - 10) + ' 日分'));
  const go = el('div', 'go');
  const resume = el('button', 'go-btn primary'); resume.innerHTML = svg('play'); resume.append('続きから');
  resume.onclick = (ev) => { ev.stopPropagation(); NH.update((x) => NH.setReadSeq(x, null)); openUrl(historyOpenUrl(d, e)); };
  const toc = el('button', 'go-btn'); toc.innerHTML = svg('toc'); toc.append('目次');
  toc.onclick = (ev) => { ev.stopPropagation(); openUrl(tocTarget({ key: e.key, site: e.site, id: e.id, url: e.url || e.siteUrl })); };
  go.append(resume, toc);
  box.appendChild(go);
  return box;
}
function historyRow(d, e, inList) {
  const r = el('div', 'card hrow');
  asButton(r, () => { NH.update((x) => NH.setReadSeq(x, null)); openUrl(historyOpenUrl(d, e)); }); // 履歴から開いたら、前の絞り込みの読む順は使わない
  const main = el('div', 'main');
  main.appendChild(el('div', 'title', e.title || '(作品名不明)'));
  const meta = el('div', 'meta');
  const site = el('span'); site.append(el('span', 'dot ' + e.site), document.createTextNode(' ' + NH.siteLabel(e)));
  meta.appendChild(site);
  const listed = d.readLater.find((x) => x.key === e.key);
  const status = e.status || (listed || {}).status; // 連載状態 (履歴に覚えた値 → リストの値)
  if (STATUS_NAME[status]) meta.appendChild(el('span', 'badge status ' + status, STATUS_NAME[status]));
  if (e.appAt) {
    // 話数・読んだ状態は、リストのカードと同じ判定 (NH.readState) で出す。リストに無い作品は、履歴に覚えた値で同じ形の項目を作って判定する (url は渡さない: 渡すと、開いた話をサイトのしおりとみなして「読みかけ」にしてしまう)
    const st = NH.readState(d, listed || { key: e.key, site: e.site, status: e.status, total: e.total });
    const pos = (d.progress || {})[e.key];
    const hp = NH.historyPos(e); // 履歴に覚えた、最後に開いた話の番号・読み具合 (別の話のときに覚えたものは除く)
    const n = (pos && NH.epNumber(pos)) || hp.no || NH.epNumber({ ep: e.ep }); // 最後に読んだ (開いた) 話の番号。リストの「続きから」は次に読む話なので、読み終えた直後は 1 つ違う
    const total = st.total || e.total || 0;
    // その日 (この行が並んでいる日) に読んだ話。「3〜5・7 / 112話」。記録が無いときは、最後に読んだ (開いた) 話だけ「10 / 112話」
    // 行は開いた日に並び、記録は読んだ日に付く。日付をまたいで同じ話を読み続けたとき (開いた日には記録が無い) は、それより後の最新の日の記録を出す
    const reads = e.reads || {};
    const rowDay = NH.dayKey(e.at);
    const laterDay = Object.keys(reads).filter((k) => k > rowDay).sort().pop();
    const readList = reads[rowDay] || (laterDay && reads[laterDay]) || [];
    const shown = readList.length ? NH.fmtEpisodes(readList, 4) : n || '';
    const ep = shown && !st.short ? shown + (total && total >= Math.max(...(readList.length ? readList : [n || 0])) ? ' / ' + total : '') + '話' : ''; // 全話数が分からないときは「10話」
    // 読んだ状態 (リストの「まだ読んでいません / 読みかけ / 読了」と同じ): 読みかけ / 未読。読み終えた作品は印を付けない
    const gone = !st.started && !!e.pr; // 読書位置が消えている (リストから外した・整理された): 履歴に覚えた読み具合で補う (どの話のものでも、読み始めてはいる)
    const goneDone = gone && !!hp.pr && hp.pr.done && (st.short || (total && n && n >= total)); // 最後の話を最後まで読んでいた
    const state = st.finished || goneDone ? '' : st.started || gone ? 'mid' : 'none';
    meta.appendChild(el('span', 'hsrc app', [H_TIME.format(new Date(e.appAt)), ep].filter(Boolean).join(' ・ ')));
    if (state) {
      const mark = el('span', 'hsrc state s' + state, state === 'mid' ? '読みかけ' : '未読');
      meta.appendChild(mark);
    }
  }
  if (e.siteAt) meta.appendChild(el('span', 'hsrc site', '公式' + (e.total ? ' ・ ' + e.total + '話' : '')));
  if (inList) meta.appendChild(el('span', 'hsrc list', 'リスト'));
  // 長編は、「読んだ話」の欄を開閉する印 (押すと日ごとに読んだ話と、続きから・目次のボタンが出る)。短編は目次が無いので出さない
  const isShort = status === 'short' || e.ep === '0';
  if (!isShort) {
    const open = H_OPEN.has(e.key);
    const tg = el('button', 'hexp', open ? '▲' : '▼');
    tg.setAttribute('aria-expanded', String(open));
    tg.setAttribute('aria-label', open ? '読んだ話を閉じる' : '読んだ話を開く');
    tg.onclick = (ev) => { ev.stopPropagation(); if (open) H_OPEN.delete(e.key); else H_OPEN.add(e.key); render(); };
    meta.appendChild(tg);
  }
  main.appendChild(meta);
  if (!isShort && H_OPEN.has(e.key)) main.appendChild(historyLog(d, e));
  r.appendChild(main);
  // 後で読むに追加 (リストにあるときは印だけ)
  const add = el('button', 'icon-btn hadd' + (inList ? ' on' : ''));
  add.innerHTML = svg(inList ? 'check' : 'add');
  add.setAttribute('aria-label', inList ? 'リストに追加済み' : '後で読むに追加');
  add.onclick = (ev) => {
    ev.stopPropagation();
    if (inList) return;
    NH.update((dd) => {
      // 追加の規則は NH.addLater に任せる (連載状態・全話数・読書位置を履歴から引き継ぐ。読了の作品は、読了を外して戻す)
      const w = { site: e.site, id: e.id, url: e.url || e.siteUrl };
      if (NH.addLater(dd, w, e.title, e.status) === 'added' && e.sub) NH.findItem(dd, w).sub = e.sub;
    });
    snack('後で読むに追加しました');
    render();
  };
  r.appendChild(add);
  return r;
}
function renderHistory(d) {
  const terms = queryTerms();
  const h = (d.history || []).filter((e) => !NH.nsfwHides(d, e));
  const onList = new Set(d.readLater.filter((x) => !x.done).map((x) => x.key));
  // 履歴のタブでは、上のカード・読了の欄・読了の自動削除の案内は出さない
  $('hero').innerHTML = '';
  $('doneBox').hidden = true;
  $('pruneHint').textContent = '';
  const sites = [...new Set(h.map(NH.siteKey))]; // R18 はノクターンなどに分ける
  if (ui.site && !sites.includes(ui.site)) ui.site = '';
  const fl = $('filters'); fl.innerHTML = '';
  const f = chipAdder(fl);
  H_FILTERS.forEach(([k, label]) => f(label, ui.src === k, () => { ui.src = k; render(); }));
  if (sites.length > 1) sites.forEach((s) => f(NH.siteLabel({ site: s }), ui.site === s, () => { ui.site = ui.site === s ? '' : s; render(); }, s));
  fl.hidden = !fl.childElementCount;

  const root = $('active'); root.innerHTML = '';
  if (!NH.historyOn(d)) {
    $('qCount').textContent = ''; // 前のタブの検索の件数を残さない
    root.appendChild(emptyState('book', '履歴の記録はオフです', '設定の「閲覧履歴を記録する」で切り替えられます'));
  } else {
    const shown = h.filter((e) => H_MATCH[ui.src || 'all'](e) && (!ui.site || NH.siteKey(e) === ui.site) && matchQuery(e, terms));
    $('qCount').textContent = terms.length ? shown.length + ' 件' : '';
    if (!shown.length) {
      if (terms.length && h.length) {
        const b = el('button', 'btn', '検索をやめる'); b.onclick = () => closeSearch();
        root.appendChild(emptyState('search', '「' + ui.q.trim() + '」に合う履歴がありません', '別の言葉でも探せます', b));
      } else {
        root.appendChild(emptyState('book', h.length ? '条件に合う履歴がありません' : '履歴はまだありません', h.length ? '絞り込みを変えてください' : '作品を開くと、ここに記録されます'));
      }
    } else {
      // アプリで読んだ作品は日付ごと、公式にしか無い作品 (時刻不明) は最後にまとめる
      let lastDay = null, list = null;
      shown.forEach((e) => {
        const day = e.appAt ? dayLabel(e.appAt) : '公式の履歴のみ (別の端末など)';
        if (day !== lastDay) { root.appendChild(el('div', 'hday', day)); list = el('div', 'hlist'); root.appendChild(list); lastDay = day; }
        list.appendChild(historyRow(d, e, onList.has(e.key)));
      });
    }
  }
  // 公式の閲覧履歴は、各サイトの履歴ページを開いたときにアプリの履歴と突き合わせる (自動では取りに行かない)
  const off = el('div', 'card hofficial');
  off.appendChild(el('p', '', '公式の閲覧履歴は、各サイトの履歴ページを開いたときにアプリの履歴と突き合わせます (自動では取りに行きません)。別の端末で読んだ作品は「公式」として表示されます。'));
  const btns = el('div', 'btns');
  MAIN_SITES.forEach(([k, label]) => { // app.js のサイトの表 (R18 を除く)
    const b = el('button', 'btn tonal'); b.append(el('span', 'dot ' + k), document.createTextNode(label + 'の履歴'));
    b.onclick = () => (location.href = H_OFFICIAL[k]); btns.appendChild(b);
  });
  off.appendChild(btns);
  root.appendChild(off);
}
