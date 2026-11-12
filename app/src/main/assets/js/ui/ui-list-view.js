// 一覧の表示の反映 (サイト上の画面。ui-list.js が呼ぶ): ミュート (一致した作品を隠す / 薄く表示)・ブロック・読んだ作品の印・連載状態の絞り込み。
// 書き込みは「変わったときだけ」にしてある (文字・class・バーの中身)。書き換えると MutationObserver が反応して繰り返しになるため、足すときもこの決まりを守る。
// ctx = {mk, entries (一覧の作品 {box, key, title, author, tags, status, tag}。ui-list.js と共有する 1 つの配列。画面から外れたものはここで取り除く)}。
// 戻り値: {applyView, viewed (applyView を一度でも呼んだか)}
window.NH_UI_LIST_VIEW = function (ctx) {
  const { mk, entries } = ctx;
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

  // 連載状態の切り替え (画面の下)。帯は filterBarOf が作る
  function paintFilterBar(known, counts, want) {
    if (!filterBar) {
      if (!known) return;
      filterBar = NH_UI_LIST_VIEW.filterBarOf(mk, (k) => { NH.setSetting('statusFilter', k); applyView(); });
    }
    filterBar.paint(known, counts, want);
  }

  return { applyView, viewed: () => viewed };
};

/**
 * 連載状態の切り替えの帯 (画面の下に固定。.nh-filter)。作った時点で画面に付ける。
 * paint(known, counts, want): known が false なら隠す (html の nh-has-filter も外す = 余白・お知らせの避けを戻す)。
 * 中身は選択や件数が変わったときだけ作り直す (書き換えると MutationObserver が反応して繰り返しになるため)。onPick(key) は押されたとき。
 */
window.NH_UI_LIST_VIEW.filterBarOf = function (mk, onPick) {
  const STATUS = [['', 'すべて'], ['serial', '連載中'], ['complete', '完結'], ['unfinished', '未完'], ['short', '短編']];
  const bar = mk('div', 'nh-filter');
  const root = document.documentElement;
  (document.body || root).appendChild(bar);
  const slide = NH_UI_LIST_VIEW.slideWith(bar);

  function paint(known, counts, want) {
    if (!known) {
      bar.hidden = true;
      root.classList.remove('nh-has-filter');
      return;
    }
    if (bar.hidden || !root.classList.contains('nh-has-filter')) {
      bar.hidden = false;
      root.classList.add('nh-has-filter');
      slide.reset(); // 隠れていた間にスクロールした分・高さの測り直し (隠れている間の高さは 0)
    }
    const sig = want + '|' + JSON.stringify(counts);
    if (bar.dataset.sig === sig) return;
    bar.dataset.sig = sig;
    slide.remeasure();
    bar.textContent = '';
    // 選んでいる状態は、このページに 0 件でも出す (解除できるように)
    STATUS.filter(([k]) => !k || counts[k] || k === want).forEach(([k, label]) => {
      const b = mk('button', k === want ? 'on' : '', label + (k ? ' ' + (counts[k] || 0) : ''));
      b.addEventListener('click', () => onPick(k));
      bar.appendChild(b);
    });
  }
  return { paint };
};

/**
 * 下へスクロールした分だけ帯を下へ隠し、上へ戻した分だけ出す (上のバー HeaderScroll と同じ。向きで切り替えず、スクロール量にそのまま合わせる =
 * 指に付いて動く。止めたら途中で止まる。先頭・末尾では出す)。書くのは transform だけ (合成のみ)・スクロールの通知は passive で rAF にまとめ・
 * 変わらないときは何も書かない。隠れている (hidden) 間は何もしない。
 * 戻り値: remeasure() = 中身を作り直した (高さが変わりうる)、reset() = 出し直す (再表示のとき)。画面の大きさが変わったときは、自分で測り直す
 */
window.NH_UI_LIST_VIEW.slideWith = function (bar) {
  const M = NH_UI_LIST_VIEW.BAR_SLIDE;
  let last = window.scrollY;
  let offset = 0;
  let travel = 0;
  let queued = false;
  const show = () => { offset = 0; bar.classList.remove('nh-snap'); bar.style.transform = ''; };
  const frame = () => {
    queued = false;
    if (bar.hidden) return;
    const y = window.scrollY;
    if (!travel) {
      if (!bar.offsetHeight) return; // まだ高さが分からない (測れるまで待つ)
      travel = bar.offsetHeight + M.EXTRA;
    }
    const edge = y <= 0 || y + window.innerHeight >= document.documentElement.scrollHeight - M.EDGE;
    const next = NH_UI_LIST_VIEW.barSlide(offset, y - last, travel, edge);
    if (next.consumed) last = y; // 小さな動きは last を進めず、たまってから動かす
    if (next.offset === offset) return;
    bar.classList.toggle('nh-snap', next.reset); // 端で出し直すときだけ、短い動きを付ける (指に付くときは付けない)
    offset = next.offset;
    bar.style.transform = offset ? 'translate3d(0,' + offset + 'px,0)' : '';
  };
  window.addEventListener('scroll', () => { if (!queued) { queued = true; requestAnimationFrame(frame); } }, { passive: true });
  window.addEventListener('resize', () => { travel = 0; }, { passive: true }); // 回転・文字の大きさの変更で高さが変わりうる
  return {
    remeasure: () => { travel = 0; },
    reset: () => { travel = 0; last = window.scrollY; show(); },
  };
};

// 切り替えの帯の動かし方の定数 (px)
window.NH_UI_LIST_VIEW.BAR_SLIDE = {
  EXTRA: 40, // 帯の高さに足す (下の余白・影の分。画面の外まで出す)
  EDGE: 2, // 末尾とみなす余裕
  DEADBAND: 2, // これより小さい (たまった) 動きでは動かさない = 揺れで動かない
};

/**
 * 帯のずらし量 (0 = 出ている、travel = 隠れ切った) を、スクロールの動き dy (下が +) で進める純粋な関数。
 * edge = 先頭 / 末尾 (出し直す)。戻り値 {offset, consumed (dy を使った = 呼び出し側が基準を進めてよい), reset (端で出し直した)}
 */
window.NH_UI_LIST_VIEW.barSlide = function (offset, dy, travel, edge) {
  if (edge) return { offset: 0, consumed: true, reset: offset !== 0 };
  if (Math.abs(dy) < window.NH_UI_LIST_VIEW.BAR_SLIDE.DEADBAND) return { offset, consumed: false, reset: false };
  return { offset: Math.min(travel, Math.max(0, offset + dy)), consumed: true, reset: false };
};
