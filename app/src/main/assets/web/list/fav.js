// list.html: お気に入りのタブ (★ をつけた作品。「後で読む」と違い、リストから外しても消えない)
// データの規則は core/favorites.js。読んだ場所は progress をそのまま使う
function favOpenUrl(d, f) {
  const it = d.readLater.find((x) => x.key === f.key);
  return NH.resumeUrl(d, it || { key: f.key, url: f.url || NH.workUrl(f.site, f.id) }); // リストにあれば「続きから」、なければ読んだ話か作品の URL
}
// ★ の付け外し (外したときは「元に戻す」を出す)。work = {site, id, url}
function favToggle(work, title, status) {
  let r = null;
  NH.update((d) => { r = NH.toggleFav(d, work, title, status); });
  if (saveFailed(r)) return;
  render();
  if (r.result === 'removed') snack('お気に入りから外しました', () => { NH.update((x) => NH.restoreFav(x, r.removed)); render(); });
  else snack('お気に入りに追加しました');
}
// 読んだ場所: 「10 / 112話」。記録が無ければ「まだ読んだ記録がありません」(古い記録は、設定した数を超えると消える)
function favPosText(d, f) {
  const p = (d.progress || {})[f.key];
  const listed = d.readLater.find((x) => x.key === f.key);
  const hist = (d.history || []).find((x) => x.key === f.key) || {};
  const total = (listed || {}).total || hist.total || 0;
  const n = p && NH.epNumber(p);
  if (!p) return '';
  if (!n) return p.done ? '読了' : '読みかけ';
  return n + (total && total >= n ? ' / ' + total : '') + '話' + (p.done && total && n >= total ? ' 読了' : '');
}
function favRow(d, f) {
  const r = el('div', 'card hrow');
  asButton(r, () => { NH.update((x) => NH.setReadSeq(x, null)); openUrl(favOpenUrl(d, f)); });
  const main = el('div', 'main');
  main.appendChild(el('div', 'title', f.title || '(作品名不明)'));
  const meta = el('div', 'meta');
  const site = el('span'); site.append(el('span', 'dot ' + f.site), document.createTextNode(' ' + NH.siteLabel(f)));
  meta.appendChild(site);
  if (STATUS_NAME[f.status]) meta.appendChild(el('span', 'badge status ' + f.status, STATUS_NAME[f.status]));
  const pos = favPosText(d, f);
  meta.appendChild(el('span', 'hsrc ' + (pos ? 'app' : 'site'), pos || '読んだ記録なし'));
  main.appendChild(meta);
  r.appendChild(main);
  // 後で読むに追加 (リストにあるときは印だけ)
  const inList = d.readLater.some((x) => x.key === f.key);
  const add = el('button', 'icon-btn hadd' + (inList ? ' on' : ''));
  add.innerHTML = svg(inList ? 'check' : 'add');
  add.setAttribute('aria-label', inList ? 'リストに追加済み' : '後で読むに追加');
  add.onclick = (ev) => {
    ev.stopPropagation();
    if (inList) return;
    NH.update((dd) => {
      const w = { site: f.site, id: f.id, url: f.url };
      if (NH.addLater(dd, w, f.title || f.id, f.status) === 'added' && f.sub) NH.findItem(dd, w).sub = f.sub;
    });
    snack('後で読むに追加しました');
    render();
  };
  r.appendChild(add);
  const star = el('button', 'icon-btn hadd fav on');
  star.innerHTML = svg('starOn');
  star.setAttribute('aria-label', 'お気に入りから外す');
  star.onclick = (ev) => { ev.stopPropagation(); favToggle(f, f.title, f.status); };
  r.appendChild(star);
  return r;
}
function renderFav(d) {
  const terms = queryTerms();
  const all = NH.favsOf(d).filter((f) => !NH.nsfwHides(d, f));
  $('hero').innerHTML = '';
  const sites = [...new Set(all.map(NH.siteKey))];
  if (ui.site && !sites.includes(ui.site)) ui.site = '';
  const fl = $('filters'); fl.innerHTML = '';
  if (sites.length > 1) {
    const f = chipAdder(fl);
    f('すべて', !ui.site, () => { ui.site = ''; render(); });
    sites.forEach((s) => f(NH.siteLabel({ site: s }), ui.site === s, () => { ui.site = ui.site === s ? '' : s; render(); }, s));
  }
  fl.hidden = !fl.childElementCount;
  const root = $('active'); root.innerHTML = '';
  showHint(root, 'list-fav');
  const shown = all.filter((f) => (!ui.site || NH.siteKey(f) === ui.site) && matchQuery(f, terms));
  $('qCount').textContent = terms.length ? shown.length + ' 件' : '';
  if (!shown.length) {
    if (terms.length && all.length) {
      const b = el('button', 'btn', '検索をやめる'); b.onclick = () => closeSearch();
      root.appendChild(emptyState('search', '「' + ui.q.trim() + '」に合うお気に入りがありません', '別の言葉でも探せます', b));
    } else {
      root.appendChild(emptyState('star', 'お気に入りはまだありません', 'ランキングや検索結果の ☆、リストの作品のメニューから追加できます'));
    }
    return;
  }
  const list = el('div', 'hlist');
  shown.forEach((f) => list.appendChild(favRow(d, f)));
  root.appendChild(list);
  // 読んだ場所の記録は、設定した数を超えると古いものから消えることを、ここで知らせる
  const max = NH.favPosMaxOf(d);
  const hint = el('div', 'list-hint', max > 0
    ? '読んだ場所の記録は、新しく読んだ ' + max + ' 作品ぶんまで残り、古いものから消えます (作品はお気に入りに残ります)。数は設定の「リスト」で変えられます。'
    : '読んだ場所の記録は、すべて残します (設定の「リスト」で数を決められます)。');
  hint.style.textAlign = 'left';
  root.appendChild(hint);
}
