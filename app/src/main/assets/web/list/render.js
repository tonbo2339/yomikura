// list.html: 一覧の描画
// ---- 描画 ----
function render() {
  const d = load();
  paintCheckRow(d);
  const shown = d.readLater.filter((x) => !NH.nsfwHides(d, x)); // NSFW モードがオンなら R18 の作品は出さない (データは消さない)
  const active = shown.filter((x) => !x.done);
  const done = shown.filter((x) => x.done);
  const cnt = { update: active.filter((x) => kindOf(x) === 'update').length, later: active.filter((x) => kindOf(x) === 'later').length };
  if (!['update', 'later', 'history'].includes(ui.tab)) ui.tab = cnt.update ? 'update' : 'later';
  // 開いたとき (最初の 1 回だけ): 前回選んだタブが空で、もう一方に作品があるなら、そちらを開く (見えない作品に気づけるように)
  if (!ui.tabChecked) {
    ui.tabChecked = true;
    const other = ui.tab === 'update' ? 'later' : 'update';
    if (ui.tab !== 'history' && !cnt[ui.tab] && cnt[other]) ui.tab = other;
  }
  // 更新を確認するサイトが 1 つも無いとき、またはその (対象の) どのサイトにもログインしていないときは、
  // 更新アイコンと、(更新の作品が無ければ)「未読の更新」の欄を出さない (ログインの有無は d.logins。Kotlin が写している)
  const canCollect = NH.collectAnyActive(d);
  $('checkRow').hidden = !canCollect;
  $('collect').hidden = !canCollect;
  const showUpdateTab = canCollect || cnt.update > 0;
  if (!showUpdateTab && ui.tab === 'update') ui.tab = 'later';


  // タブ
  const tabs = $('tabs'); tabs.innerHTML = '';
  [['update', '更新'], ['later', '後で読む'], ['history', '履歴']].filter(([k]) => k !== 'update' || showUpdateTab).forEach(([k, label]) => {
    const b = setPressed(el('button', ui.tab === k ? 'on' : '', label), ui.tab === k);
    if (k !== 'history') b.appendChild(el('span', 'n', String(cnt[k]))); // 履歴は増える一方で数に意味がないので出さない
    b.onclick = () => { ui.tab = k; ui.site = ''; ui.tag = ''; ui.status = ''; try { localStorage.setItem('nh-tab', k); } catch (e) {} render(); };
    tabs.appendChild(b);
  });

  if (ui.tab === 'history') { $('sortRow').hidden = true; syncSelect(null); return renderHistory(d); } // 並べ替えは作品の一覧だけ
  // 絞り込み (このタブにある分だけ)。検索中も、いまのタブの中だけを探す
  const terms = queryTerms();
  const inTab = active.filter((x) => kindOf(x) === ui.tab && matchQuery(x, terms));
  const sites = [...new Set(inTab.map(NH.siteKey))]; // R18 はノクターンなどに分ける
  const stats = STATUS_CHIPS.filter(([k]) => inTab.some((x) => x.status === k)); // 連載状態が分かっている作品があるものだけ
  const tags = [...new Set(inTab.flatMap(NH.tagsOf))];
  if (ui.site && !sites.includes(ui.site)) ui.site = '';
  if (ui.status && !stats.some(([k]) => k === ui.status)) ui.status = '';
  if (ui.tag && !tags.includes(ui.tag)) ui.tag = '';
  const fl = $('filters'); fl.innerHTML = '';
  if (sites.length > 1 || tags.length || stats.length) {
    const f = chipAdder(fl);
    f('すべて', !ui.site && !ui.tag && !ui.status, () => { ui.site = ''; ui.tag = ''; ui.status = ''; render(); });
    stats.forEach(([k, label]) => f(label, ui.status === k, () => { ui.status = ui.status === k ? '' : k; render(); }));
    if (sites.length > 1) sites.forEach((s) => f(NH.siteLabel({ site: s }), ui.site === s, () => { ui.site = ui.site === s ? '' : s; render(); }, s));
    tags.forEach((t) => f('# ' + t, ui.tag === t, () => { ui.tag = ui.tag === t ? '' : t; render(); }));
  }
  fl.hidden = !fl.childElementCount;

  const mode = sortMode(d);
  const visible = sortItems(inTab.filter((x) => (!ui.site || NH.siteKey(x) === ui.site) && (!ui.tag || NH.tagsOf(x).includes(ui.tag)) && (!ui.status || x.status === ui.status)), mode);
  // 並べ替えの欄 (選ぶと設定に覚える)
  const sel = $('sortSel');
  if (!sel.dataset.ready) {
    sel.dataset.ready = '1';
    SORTS.forEach(([k, label]) => sel.appendChild(el('option', '', label)).value = k);
    sel.addEventListener('change', () => { NH.setSetting('listSort', sel.value); render(); });
  }
  sel.value = mode;
  syncSelect(visible);
  $('sortRow').hidden = inTab.length < 2; // 並べる作品が 2 つ以上あるときだけ
  const hero = $('hero'); hero.innerHTML = '';
  const root = $('active'); root.innerHTML = '';

  if (!visible.length) {
    if (terms.length && !inTab.length) {
      const b = el('button', 'btn', '検索をやめる'); b.onclick = () => closeSearch();
      root.appendChild(emptyState('search', '「' + ui.q.trim() + '」に合う作品がありません', '別の言葉や、サイト名・連載状態でも探せます', b));
    } else if (inTab.length) {
      root.appendChild(emptyState('search', '条件に合う作品がありません', '絞り込みを解除してください'));
    } else if (ui.tab === 'update') {
      const b = el('button', 'btn primary', '更新を確認'); b.onclick = collect; // (この欄が出るのは更新を確認できるときだけ)
      root.appendChild(emptyState('inbox', 'しおりの作品の更新はありません', 'ブックマーク・しおり・フォローから未読を取り込みます', b));
    } else {
      root.appendChild(emptyState('book', '後で読むリストは空です', 'ランキングや検索結果の「＋ 後で読む」で追加できます'));
    }
  } else {
    // 上のカードは「さっきまで読んでいた作品の続き」。読んだ形跡が無ければ、リストの先頭 (次に読む)。
    // 絞り込み中は、絞り込んだ中から選ぶ (絞り込みなしなら、どちらのタブの作品でもよい)
    // 検索中は、結果を見やすくするため上のカードは出さない
    if (!terms.length) {
      const resume = NH.flag(d, 'heroResume'); // 設定: さっきまで読んでいた作品を出す
      const pick = (resume ? lastRead(d, isNarrowed() ? visible : active) : null) || visible[0];
      hero.appendChild(heroCard(d, pick, visible.filter((x) => x !== pick).length, visible));
    }
    visible.forEach((it) => root.appendChild(card(d, it, true, visible)));
  }

  const doneRoot = $('done'); doneRoot.innerHTML = '';
  const doneShown = done.filter((x) => (!terms.length || kindOf(x) === ui.tab) && matchQuery(x, terms));
  doneShown.slice().reverse().forEach((it) => doneRoot.appendChild(card(d, it, false)));
  $('doneBox').hidden = !doneShown.length;
  $('doneHead').textContent = '読了 ' + doneShown.length;
  $('qCount').textContent = terms.length ? visible.length + doneShown.length + ' 件' : '';
  const st = d.settings || {};
  $('pruneHint').textContent = !NH.flagOf(st, 'pruneDone') ? '' : '読了から ' + (st.pruneDays || NH.PRUNE_DAYS) + ' 日たつと自動で削除します (設定で変更)';
  paintSelect(); // カードを作り直したあとにも、選んだ作品のチェックを付ける (syncSelect の時点では、まだ古いカード)
}
