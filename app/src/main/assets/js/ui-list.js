// ランキング・検索の一覧に「＋ 後で読む」「ミュート」のボタンを付け、ミュート・ブロック・連載状態の絞り込み・読んだ作品の印を反映する。
// 一覧の外 (おすすめ・作者の作品一覧など) の作品へのリンクも、ブロックに一致すれば隠す。ui.js が呼ぶ (関数を定義するだけで、読み込んだだけでは何もしない)。
// ctx = ui.js の共通の部品 {adapter, mk}。見た目の CSS は ui.js の共通スタイル (.nh-chip / .nh-sheet / .nh-filter など)
window.NH_UI_LIST = function (ctx) {
  const { adapter, mk } = ctx;
  // ---- ランキングの「＋ 後で読む」 ----
  // 同じ作品が 1 ページに何度も出る (ノクターンのランキングトップの日間・週間・月間など) ので、押したら同じ作品のボタンを全部そろえる
  const btnsOf = new Map(); // 作品のキー → [ボタン]
  const paintBtn = (btn, on) => {
    btn.textContent = on ? '✓ リストに追加済み' : '＋ 後で読む';
    btn.classList.toggle('on', on);
  };
  const refresh = (work) => {
    const on = !!NH.findItem(NH.load(), work);
    (btnsOf.get(NH.workKey(work)) || []).forEach((b) => { if (b.isConnected) paintBtn(b, on); });
  };
  // 1 作品の囲み: タイトル (data-nh-title) を 1 つだけ含む、いちばん外側の要素。
  // 結果が 1 件だけのページでページ全体までさかのぼらないよう、段数に上限を設け、ページの骨組みには入らない
  //   ページの骨組み: なろうは l-xxx クラス、ほかは id や container 系のクラス (class の Layout はカクヨムが作品の中でも使うので見ない)
  const PAGE_FRAME = 'main, body, [id*="container" i], [id*="contents" i], [id*="main" i], [class^="l-"], [class*=" l-"], [class*="container" i]';
  const boxOf = (anchor) => {
    const maxH = window.innerHeight * 1.5; // 1 作品の囲みが画面 1.5 枚分より大きくなることはまず無い
    let b = anchor;
    for (let depth = 0; depth < 10; depth++) {
      const p = b.parentElement;
      if (!p || p.matches(PAGE_FRAME) || p.querySelectorAll('[data-nh-title]').length !== 1) break;
      if (p.getBoundingClientRect().height > maxH) break;
      b = p;
    }
    return b;
  };
  // 囲みの中のタグ: アダプターが読めればそれ (ハーメルンは文字)、無ければタグ検索へのリンク
  const tagsOf = (box) => adapter.tagsOf ? adapter.tagsOf(box)
    : [...box.querySelectorAll('a[href*="word="], a[href*="/tags/"]')].map((a) => a.textContent.trim()).filter(Boolean);

  const entries = []; // {box, title, author, tags}
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
    if (!todo.length) { if (!viewed) applyView(); sweepBlocked(); return; }
    const inList = new Set(NH.load().readLater.map((x) => x.key));
    // リストにある作品は、一覧に出ている連載状態で更新する (リストの絞り込み用。状態が変わったときだけ書く)
    const known = todo.filter(({ entry }) => entry.status && inList.has(entry.key));
    if (known.length) {
      const listed = NH.load().readLater;
      const changed = known.filter(({ entry }) => { const it = listed.find((x) => x.key === entry.key); return it && it.status !== entry.status; });
      if (changed.length) NH.update((d) => changed.forEach(({ entry }) => { const it = d.readLater.find((x) => x.key === entry.key); if (it) it.status = entry.status; }));
    }
    // 差し込み
    todo.forEach(({ item, work, entry }) => {
      const btn = mk('button', 'nh-chip');
      btn.addEventListener('click', (e) => {
        e.preventDefault();
        e.stopPropagation();
        let added;
        NH.update((d) => {
          const it = NH.findItem(d, work);
          if (it) d.readLater = d.readLater.filter((x) => x !== it);
          else d.readLater.push(NH.withSub({ key: NH.workKey(work), site: work.site, id: work.id, url: work.url, title: item.title, addedAt: Date.now(), done: false, kind: 'later', ...(entry.status ? { status: entry.status } : {}) }));
          added = !it;
        });
        NH.toast(added ? '後で読むに追加しました' : 'リストから外しました');
        refresh(work);
      });
      btnsOf.set(entry.key, (btnsOf.get(entry.key) || []).filter((b) => b.isConnected).concat(btn));
      paintBtn(btn, inList.has(entry.key));
      entries.push(entry);
      const muteBtn = mk('button', 'nh-chip nh-chip-mute', 'ミュート');
      muteBtn.addEventListener('click', (e) => { e.preventDefault(); e.stopPropagation(); openMuteSheet(entry); });

      // タイトル行の直下に置く (アダプターが after を指定していればその要素の後、なければ anchor の親ブロックの後)
      const row = mk('div', 'nh-chips');
      entry.tag = mk('span', 'nh-read-tag');
      entry.tag.hidden = true;
      row.append(btn, muteBtn, entry.tag);
      (item.after || item.anchor.parentElement).insertAdjacentElement('afterend', row);
    });
    applyView();
    sweepBlocked();
  };

  // ---- ブロック: 一覧の外 (おすすめ・作者の作品一覧など) の作品へのリンクも隠す ----
  // 見るのは作品のトップへのリンクだけ (話へのリンクは、目次を消さないため対象外)。今開いている作品は対象外。
  // 作者はリンクからは分からないので、作品とタイトルのキーワードで判定する。隠すのは、その作品へのリンクだけを含むいちばん外側の囲み。
  // ブロックが 1 つも無ければ何もしない (ページのリンクを見て回らない)
  const curWork = NH.parseWork(location.href);
  const curKey = curWork ? NH.workKey(curWork) : '';
  const linkBox = (anchor, key) => {
    const maxH = window.innerHeight * 1.5;
    let b = anchor;
    for (let depth = 0; depth < 10; depth++) {
      const p = b.parentElement;
      if (!p || p.matches(PAGE_FRAME) || [...p.querySelectorAll('a[data-nh-wk]')].some((x) => x.dataset.nhWk !== key)) break;
      if (p.getBoundingClientRect().height > maxH) break;
      b = p;
    }
    return b;
  };
  function sweepBlocked() {
    const d = NH.load();
    const on = NH.hasBlock(d);
    const before = [...document.querySelectorAll('.nh-blocked-link')];
    if (!on && !before.length) return;
    // 読み取り (囲みの大きさを測る) を先にまとめ、隠すのはあとで (交互にすると配置の計算がやり直しになる)
    const links = [];
    if (on) {
      document.querySelectorAll('a[href]').forEach((a) => {
        if (a.closest('.nh-chips, .nh-sheet')) return;
        const w = NH.parseWork(a.href);
        if (!w || w.episode != null) return;
        const key = NH.workKey(w);
        if (key === curKey) return;
        if (a.dataset.nhWk !== key) a.dataset.nhWk = key;
        links.push({ a, key });
      });
    }
    const boxes = new Set(links.filter(({ a, key }) => NH.blockReason(d, { key, title: a.textContent.trim() })).map(({ a, key }) => linkBox(a, key)));
    before.forEach((b) => { if (!boxes.has(b)) b.classList.remove('nh-blocked-link'); });
    boxes.forEach((b) => b.classList.add('nh-blocked-link'));
  }
  // 一覧とリンクの両方の表示を直す (ミュート・ブロックを足したり外したりしたとき)
  const refreshView = () => { applyView(); sweepBlocked(); };

  // ---- 表示の反映: ミュート (一致した作品を隠す / 薄く表示) と、連載状態の絞り込み ----
  const STATUS = [['', 'すべて'], ['serial', '連載中'], ['complete', '完結'], ['unfinished', '未完'], ['short', '短編']];
  let pill = null;
  let filterBar = null;
  const onWorkPage = !!NH.parseWork(location.href);
  let viewed = false; // applyView を一度でも呼んだか
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
    const readState = d.settings && d.settings.dimRead === false ? () => null : NH.readStates(d);
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

  // ---- ミュート・ブロックの追加シート (作品 / 作者 / タグ / キーワード) ----
  // 上の切り替えで、ミュート (隠す・薄く。一時的に表示もできる) とブロック (完全に出さない) を選ぶ。作品ごとはブロックだけ
  function openMuteSheet(entry) {
    const scrim = mk('div', 'nh-scrim');
    const sheet = mk('div', 'nh-sheet');
    const close = () => { scrim.remove(); sheet.remove(); };
    scrim.addEventListener('click', close);
    let mode = 'mute';
    let typed = ''; // 書きかけのキーワード (ミュート / ブロックを切り替えて描き直しても消さない)
    const add = (kind, value, label) => {
      close();
      if (mode === 'mute') {
        NH.addMute(kind, value);
        refreshView();
        NH.toast(label + ' をミュートしました');
      } else {
        NH.addBlock(kind, value);
        refreshView();
        NH.toast(label + ' をブロックしました', { label: '元に戻す', fn: () => { NH.removeBlock(kind, kind === 'works' ? value.key : value); refreshView(); } });
      }
    };
    const paint = () => {
      sheet.textContent = '';
      const verb = mode === 'mute' ? 'ミュート' : 'ブロック';
      sheet.appendChild(mk('div', 'nh-sheet-title', entry.title));
      const seg = mk('div', 'nh-sheet-seg');
      [['mute', 'ミュート'], ['block', 'ブロック']].forEach(([k, label]) => {
        const b = mk('button', k === mode ? 'on' : '', label);
        b.addEventListener('click', () => { mode = k; paint(); });
        seg.appendChild(b);
      });
      sheet.append(seg, mk('div', 'nh-sheet-note', mode === 'mute'
        ? '一致した作品を隠す (または薄く表示)。画面の下のボタンで一時的に表示できます'
        : '一致した作品を完全に出しません。ランキング・検索のほか、おすすめや作者の作品一覧からも消えます'));
      const g = mk('div', 'nh-sheet-group');
      if (mode === 'block') {
        const b = mk('button', 'nh-sheet-row', 'この作品をブロック');
        const short = entry.title.length > 20 ? entry.title.slice(0, 20) + '…' : entry.title; // お知らせが長くなりすぎないように
        b.addEventListener('click', () => add('works', { key: entry.key, title: entry.title }, '「' + short + '」'));
        g.appendChild(b);
      }
      if (entry.author) {
        const b = mk('button', 'nh-sheet-row', '作者「' + entry.author + '」を' + verb);
        b.addEventListener('click', () => add('authors', entry.author, '作者「' + entry.author + '」'));
        g.appendChild(b);
      }
      if (g.childElementCount) sheet.appendChild(g);
      if (entry.tags.length) {
        sheet.appendChild(mk('div', 'nh-sheet-label', 'タグを' + verb));
        const tags = mk('div', 'nh-sheet-tags');
        entry.tags.slice(0, 12).forEach((t) => {
          const b = mk('button', 'nh-sheet-tag', '# ' + t);
          b.addEventListener('click', () => add('words', t, '「' + t + '」'));
          tags.appendChild(b);
        });
        sheet.appendChild(tags);
      }
      sheet.appendChild(mk('div', 'nh-sheet-label', 'キーワードを' + verb + ' (タイトル・タグに含まれていれば一致)'));
      const form = mk('div', 'nh-sheet-form');
      const input = mk('input'); input.type = 'text'; input.placeholder = '例: 追放'; input.value = typed;
      input.enterKeyHint = 'done';
      const ok = mk('button', 'nh-sheet-add', '追加');
      const submit = () => { const v = input.value.trim(); if (v) add('words', v, '「' + v + '」'); };
      input.addEventListener('input', () => { typed = input.value; });
      input.addEventListener('keydown', (e) => { if (e.key === 'Enter') submit(); });
      ok.addEventListener('click', submit);
      form.append(input, ok);
      sheet.appendChild(form);
      const manage = mk('button', 'nh-sheet-row nh-sheet-link', 'ミュート・ブロックの一覧と設定');
      manage.addEventListener('click', () => (location.href = NH.SETTINGS_URL + '?page=mute'));
      const cancel = mk('button', 'nh-sheet-row nh-sheet-cancel', 'キャンセル');
      cancel.addEventListener('click', close);
      sheet.append(manage, cancel);
    };
    paint();
    document.documentElement.append(scrim, sheet);
  }

  if (adapter.rankingItems) {
    addButtons();
    // あとから読み込まれる一覧 (カクヨムのページ内遷移、追加読み込み) にも付ける
    let t = 0;
    new MutationObserver(() => { clearTimeout(t); t = setTimeout(addButtons, 400); })
      .observe(document.body || document.documentElement, { childList: true, subtree: true });
  }
};
