// 一覧の表示の反映 (サイト上の画面。ui-list.js が呼ぶ): ミュート (一致した作品を隠す / 薄く表示)・ブロック・読んだ作品の印・連載状態の絞り込み。
// 書き込みは「変わったときだけ」にしてある (文字・class・バーの中身)。書き換えると MutationObserver が反応して繰り返しになるため、足すときもこの決まりを守る。
// ctx = {mk, entries (一覧の作品 {box, key, title, author, tags, status, tag}。ui-list.js と共有する 1 つの配列。画面から外れたものはここで取り除く)}。
// 戻り値: {applyView, viewed (applyView を一度でも呼んだか)}
window.NH_UI_LIST_VIEW = function (ctx) {
  const { mk, entries } = ctx;
  const STATUS = [['', 'すべて'], ['serial', '連載中'], ['complete', '完結'], ['unfinished', '未完'], ['short', '短編']];
  let pill = null;
  let filterBar = null;
  const onWorkPage = !!NH.parseWork(location.href);
  let viewed = false;
  function applyView() {
    viewed = true;
    const d = NH.load();
    const dim = NH.muteOf(d).mode === 'dim';
    // ページが一覧を作り直した (カクヨムなど) ときの、画面から外れた古い作品は数えない
    for (let i = entries.length - 1; i >= 0; i--) if (!entries[i].box.isConnected) entries.splice(i, 1);
    // 連載状態の絞り込みは、状態を読み取れた作品が 3 つ以上あるページだけ (切り替えのバーを出せるページだけ)
    const counts = {};
    entries.forEach((e) => { if (e.status) counts[e.status] = (counts[e.status] || 0) + 1; });
    // 作品・話のページ (下の「おすすめ」などに作品が並ぶ) では出さない。ランキング・検索の一覧のためのもの
    const known = !onWorkPage && Object.values(counts).reduce((a, b) => a + b, 0) >= 3;
    const want = (known && d.settings && d.settings.statusFilter) || '';
    let n = 0;
    const readState = !NH.flag(d, 'dimRead') ? () => null : NH.readStates(d);
    entries.forEach((e) => {
      // 読んだ作品は薄くして、「読了 / 読みかけ / 閲覧済み」の印を付ける (設定でオフにできる)
      const rs = readState(e.key);
      e.box.classList.toggle('nh-read', !!rs);
      e.tag.hidden = !rs;
      e.tag.textContent = rs ? NH.READ_LABEL[rs] : '';
      // ブロックは完全に出さない (ミュートの件数にも入れない)
      // NSFW モードがオンなら、R-18 のタグ (ハーメルン) の作品も出さない
      const blocked = !!NH.blockReason(d, e) || (NH.nsfwOn(d) && e.tags.some((t) => /^R-?18$/.test(t)));
      e.box.classList.toggle('nh-blocked', blocked);
      const why = blocked ? null : NH.muteReason(d, e);
      e.box.classList.toggle('nh-muted', !!why);
      e.box.classList.toggle('nh-muted-dim', !!why && dim);
      if (why) { e.box.dataset.nhMuted = why; n++; } else delete e.box.dataset.nhMuted;
      // 状態が読み取れない作品は絞り込みで隠さない
      e.box.classList.toggle('nh-filtered', !!want && !!e.status && e.status !== want);
    });
    paintFilterBar(known, counts, want);
    // 何件ミュートしたかと、一時的に表示する切り替え (隠すモードのときだけ)
    if (!pill) {
      pill = mk('button', 'nh-mute-pill');
      pill.addEventListener('click', () => { document.documentElement.classList.toggle('nh-reveal'); applyView(); });
      (document.body || document.documentElement).appendChild(pill);
    }
    const reveal = document.documentElement.classList.contains('nh-reveal');
    pill.hidden = !n || dim;
    // 文字は変わったときだけ書き換える (書き換えると MutationObserver が反応して繰り返しになるため)
    const label = n + ' 件をミュート中 ・ ' + (reveal ? '隠す' : '表示する');
    if (pill.textContent !== label) pill.textContent = label;
  }

  // 連載状態の切り替え (画面の下)
  function paintFilterBar(known, counts, want) {
    if (!known) { if (filterBar) filterBar.hidden = true; return; }
    if (!filterBar) {
      filterBar = mk('div', 'nh-filter');
      (document.body || document.documentElement).appendChild(filterBar);
      document.documentElement.classList.add('nh-has-filter');
    }
    filterBar.hidden = false;
    // 中身は選択や件数が変わったときだけ作り直す (MutationObserver の繰り返しを避ける)
    const sig = want + '|' + JSON.stringify(counts);
    if (filterBar.dataset.sig === sig) return;
    filterBar.dataset.sig = sig;
    filterBar.textContent = '';
    // 選んでいる状態は、このページに 0 件でも出す (解除できるように)
    STATUS.filter(([k]) => !k || counts[k] || k === want).forEach(([k, label]) => {
      const b = mk('button', k === want ? 'on' : '', label + (k ? ' ' + (counts[k] || 0) : ''));
      b.addEventListener('click', () => {
        NH.setSetting('statusFilter', k);
        applyView();
      });
      filterBar.appendChild(b);
    });
  }

  return { applyView, viewed: () => viewed };
};
