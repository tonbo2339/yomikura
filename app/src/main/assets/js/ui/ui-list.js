// ランキング・検索の一覧に「＋ 後で読む」「ミュート」のボタンを付け、ミュート・ブロック・連載状態の絞り込み・読んだ作品の印を反映する。
// 一覧の外 (おすすめ・作者の作品一覧など) の作品へのリンクも、ブロックに一致すれば隠す。ui.js が呼ぶ (関数を定義するだけで、読み込んだだけでは何もしない)。
// ctx = ui.js の共通の部品 {adapter, mk}。見た目の CSS は ui.js の共通スタイル (.nh-chip / .nh-sheet / .nh-filter など)
// ここは「一覧の作品を読み取り、ボタンを差し込み、ページの書き換わりを見張る」流れだけ。役割ごとの部品:
//   ui-list-later (「後で読む」ボタン)・ui-list-view (表示の反映・絞り込み)・ui-list-block (一覧の外のリンクのブロック)・ui-list-sheet (ミュート・ブロックのシート)
window.NH_UI_LIST = function (ctx) {
  const { adapter, mk } = ctx;
  const SEL = window.NH_LIST_SELECTORS; // サイトの作りに依存するセレクターは sites.js

  // クリックの定型: 押しても、下のリンク (作品へ) は反応させない
  const onChip = (btn, fn) => btn.addEventListener('click', (e) => { e.preventDefault(); e.stopPropagation(); fn(e); });

  // anchor から外へ、囲みをさかのぼる。stop(親) が true になる手前、ページの骨組みの手前、画面 1.5 枚分より大きくなる手前まで (10 段まで)。
  // 結果が 1 件だけのページでページ全体までさかのぼらないよう、段数と大きさに上限を設ける
  const climbBox = (anchor, stop) => {
    const maxH = window.innerHeight * 1.5; // 1 作品の囲みが画面 1.5 枚分より大きくなることはまず無い
    let b = anchor;
    for (let depth = 0; depth < 10; depth++) {
      const p = b.parentElement;
      if (!p || p.matches(SEL.frame) || stop(p)) break;
      if (p.getBoundingClientRect().height > maxH) break;
      b = p;
    }
    return b;
  };
  // 1 作品の囲み: タイトル (data-nh-title) を 1 つだけ含む、いちばん外側の要素
  const boxOf = (anchor) => climbBox(anchor, (p) => p.querySelectorAll('[data-nh-title]').length !== 1);
  // 囲みの中のタグ: アダプターが読めればそれ (ハーメルンは文字)、無ければタグ検索へのリンク
  const tagsOf = (box) => adapter.tagsOf ? adapter.tagsOf(box)
    : [...box.querySelectorAll(SEL.tagLinks)].map((a) => a.textContent.trim()).filter(Boolean);

  const entries = []; // {box, key, title, author, tags, status, tag}。ui-list-view と共有する 1 つの配列
  const later = NH_UI_LIST_LATER({ mk, onChip });
  const view = NH_UI_LIST_VIEW({ mk, entries });
  const block = NH_UI_LIST_BLOCK({ climbBox });
  // 一覧とリンクの両方の表示を直す (ミュート・ブロックを足したり外したりしたとき)
  const refreshView = () => { view.applyView(); block.sweepBlocked(); };
  const openMuteSheet = NH_UI_LIST_SHEET({ mk, refreshView });

  // 一覧が長い (300 作品) ページで固まらないよう、まず全作品ぶんを読み取り (囲み・作者・タグ・状態)、そのあとまとめて差し込む。
  // 読み取りと差し込みを交互にすると、差し込むたびにページの配置の計算がやり直しになる。保存データも 1 回だけ読む
  const addButtons = () => {
    const items = adapter.rankingItems();
    items.forEach((item) => { item.anchor.dataset.nhTitle = '1'; });
    // 読み取り
    const todo = [];
    items.forEach((item) => {
      const work = NH.parseWork(item.href);
      if (!work || item.anchor.dataset.nhBtn) return;
      item.anchor.dataset.nhBtn = '1';
      const box = boxOf(item.anchor);
      const entry = { box, key: NH.workKey(work), title: item.title, author: adapter.authorOf ? adapter.authorOf(box) : '', tags: tagsOf(box), status: adapter.statusOf ? adapter.statusOf(box) : '' };
      todo.push({ item, work, entry });
    });
    // 新しい作品が無ければ何もしない (カクヨムはページが何度も書き換わる。作品の無い話ページでも、広告などで何度も呼ばれる)
    if (!todo.length) { if (!view.viewed()) view.applyView(); block.sweepBlocked(); return; }
    const listed = NH.load().readLater;
    const inList = new Set(listed.map((x) => x.key));
    const activeKeys = new Set(listed.filter((x) => !x.done).map((x) => x.key)); // 「追加済み」と出す作品 (読了は除く)
    // リストにある作品は、一覧に出ている連載状態で更新する (リストの絞り込み用。状態が変わったときだけ書く)
    const known = todo.filter(({ entry }) => entry.status && inList.has(entry.key));
    if (known.length) {
      const changed = known.filter(({ entry }) => { const it = listed.find((x) => x.key === entry.key); return it && it.status !== entry.status; });
      if (changed.length) NH.update((d) => changed.forEach(({ entry }) => { const it = d.readLater.find((x) => x.key === entry.key); if (it) it.status = entry.status; }));
    }
    // 差し込み
    todo.forEach(({ item, work, entry }) => {
      const btn = later.create(work, entry, item.title, activeKeys.has(entry.key));
      entries.push(entry);
      const muteBtn = mk('button', 'nh-chip nh-chip-mute', 'ミュート');
      onChip(muteBtn, () => openMuteSheet(entry));
      // タイトル行の直下に置く (アダプターが after を指定していればその要素の後、なければ anchor の親ブロックの後)
      const row = mk('div', 'nh-chips');
      entry.tag = mk('span', 'nh-read-tag');
      entry.tag.hidden = true;
      row.append(btn, muteBtn, entry.tag);
      (item.after || item.anchor.parentElement).insertAdjacentElement('afterend', row);
    });
    refreshView();
  };

  if (adapter.rankingItems) {
    addButtons();
    // あとから読み込まれる一覧 (カクヨムのページ内遷移、追加読み込み) にも付ける
    let t = 0;
    new MutationObserver(() => { clearTimeout(t); t = setTimeout(addButtons, 400); })
      .observe(document.body || document.documentElement, { childList: true, subtree: true });
  }
};
