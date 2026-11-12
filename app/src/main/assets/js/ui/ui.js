// サイト上の画面 (作品のページ・一覧・巡回) の入口。ここでは「どのページか」を判断して、役割ごとの部品 (ui-*.js) を呼ぶ。
// 部品: ui-style (スタイル)・ui-collect (更新の巡回)・ui-save (オフライン保存の画面)・ui-list (一覧のボタン・ミュート)・ui-fold (欄を畳む)・ui-work (作品情報・公式の履歴)・ui-readpos (読書位置)・ui-next (読了 → 次の小説へ)
(function () {
  if (document.documentElement.dataset.nhUi) return;
  document.documentElement.dataset.nhUi = '1';
  // オフライン保存の準備中に early.js が出した覆い。保存の画面を出したら (または保存でなければ) 外す (外さないとページが隠れたままになる)
  const dropCover = () => {
    const c = document.getElementById('nh-cover');
    if (c) c.remove();
  };
  const adapter = NH.adapter();
  if (!adapter) { dropCover(); return; } // ナビゲーションはアプリ上部のネイティブバーにある
  if (window.NH_BG_JOB) return; // 保存キューの作品は画面を出さない (画面外の WebView。保存は offline-bg.js がする)
  // Cloudflare の確認画面 (通過すると本来のページに読み込み直される)。巡回中でなければ何もしない
  const challenge = NH.isChallenge(document);
  if (challenge && !/^#nh-(collect|save)/.test(location.hash)) { dropCover(); delete document.documentElement.dataset.nhUi; return; }


  NH_UI_STYLE();

  const mk = NH.el; // DOM の要素を作る (core/base.js)

  // 更新の巡回 (ui-collect.js)
  if (NH_UI_COLLECT({ adapter, challenge })) return;

  const saveWork = location.hash === '#nh-save' && adapter.bodySel ? NH.parseWork(location.href) : null;
  // オフライン保存の画面 (ui-save.js)
  if (saveWork) { NH_UI_SAVE({ adapter, challenge, mk, dropCover, askDialog }, saveWork); return; }

  dropCover(); // ここから先は保存ではない
  // いま開いている作品のページ (短編・目次) で ⋮ の「この作品をオフライン保存」を押すと、URL に #nh-save が付くだけで読み込み直されない。
  // 保存の画面はページを開いたときに出すので、読み込み直す
  window.addEventListener('hashchange', () => { if (location.hash === '#nh-save') location.reload(); });

  // ---- 選択のダイアログ (画面の真ん中に出る。画面は離れない)。押した項目の値を返す。背景を押すと null ----
  // options: [[ラベル, 値], ...]
  function askDialog(title, note, options) {
    return new Promise((res) => {
      const scrim = mk('div', 'nh-scrim');
      const box = mk('div', 'nh-dialog');
      const done = (v) => { scrim.remove(); box.remove(); res(v); };
      scrim.addEventListener('click', () => done(null));
      const body = mk('div', 'nh-dialog-body');
      body.append(mk('div', 'nh-dialog-title', title));
      if (note) body.append(mk('div', 'nh-dialog-note', note));
      box.appendChild(body);
      options.forEach(([label, value]) => {
        const b = mk('button', 'nh-dialog-row', label);
        b.addEventListener('click', () => done(value));
        box.appendChild(b);
      });
      document.documentElement.append(scrim, box);
    });
  }

  // ---- ランキング・検索の一覧のボタン・ミュート・ブロック (ui-list.js) ----
  NH_UI_LIST({ adapter, mk });
  // ---- 話ページ: 読書位置の記録と復元 ----
  // 本文の高さに対する比率で持つので、文字サイズやフォントを変えても位置がずれない。
  const cur = NH.parseWork(location.href);
  // ページ内の移動で作品 / 話が変わるサイト (カクヨム): 以降の cur / 読書位置はこのページのものなので、変わったら読み込み直す。
  // 読み込み直すまでの間に、前の話の位置を新しい話の位置で上書きしない (moved が立つと write が何もしない)。
  // 移る直前に flushPos で前の話の位置を書き切る (スクロール中の保存は間引いているので、直近の位置が残っていることがある)
  let flushPos = () => {};
  // 読み込み直しが続く場合 (移動先の判定が読み込み直しても変わらない、など) の歯止め: 10 秒に 3 回までで、それ以上は読み込み直さない
  const reloadOk = () => {
    try {
      const now = Date.now();
      const log = (JSON.parse(sessionStorage.getItem('nh-reloads')) || []).filter((t) => now - t < 10000);
      if (log.length >= 3) return false;
      log.push(now);
      sessionStorage.setItem('nh-reloads', JSON.stringify(log));
    } catch (e) { /* 記録できなければそのまま読み込み直す */ }
    return true;
  };
  const moved = adapter.spa ? NH.watchWork(() => { if (reloadOk()) location.reload(); }, () => flushPos()) : () => false;

  // しおりの話から次の話へ (hameln.js の更新の取り込みが、未読の 1 話目の代わりに #nh-next 付きのしおりの話を記録する)。
  // このページは読んでいないので、閲覧履歴や読書位置には残さずに移る。次の話が無ければ、印だけ外してこのページを出す
  if (cur && location.hash === '#nh-next') {
    const n = adapter.nextUrl ? adapter.nextUrl() : null;
    if (n) { NH.go(n, true); return; }
    history.replaceState(null, '', location.pathname + location.search);
  }

  // ---- 閲覧履歴: アプリで開いた作品 / 話を記録 ----
  // 作品名も本文も無いページ (作品の URL のまま出るエラーや確認の途中のページ) は、開いたことにしない
  // 短編は話番号が無く、作品ページに本文がある
  const isShort = !!cur && cur.episode == null && !!adapter.bodySel && !!document.querySelector(adapter.bodySel);
  const curTitle = cur && adapter.workTitle ? adapter.workTitle() : '';

  // 作品のページから読める連載状態 (serial / complete / short)。アダプターが読めればそれ、短編は作品ページに本文があることで分かる。読めなければ ''
  const pageStatus = () => (adapter.pageStatus ? adapter.pageStatus() : '') || (isShort ? 'short' : '');

  // 今開いている作品を「後で読む」に追加する (外部のリンクを開いたとき / 上部バーのメニューの「この作品を後で読むに追加」)。
  // 結果: added / exists / failed (作品名も本文も無いページ。エラー・削除された作品など) / '' (作品のページではない)。
  // ページ内の移動 (カクヨムなど) があるので、呼ばれたときの URL とページから読み直す
  const addCurrentWork = () => {
    const w = NH.parseWork(location.href);
    if (!w) return '';
    const t = adapter.workTitle ? adapter.workTitle() : '';
    if (!t && !(adapter.bodySel && document.querySelector(adapter.bodySel))) return 'failed';
    const title = t || document.title.replace(/ - (ハーメルン|カクヨム|小説家になろう)$/, '').trim();
    const status = pageStatus();
    let result = 'failed'; // 保存データを読めず NH.update が書かなかったときは failed のまま
    NH.update((d) => {
      result = NH.addLater(d, w, title, status) === 'exists' ? 'exists' : 'added'; // NH.update がやり直したときのために、ここで決め直す
    });
    return result;
  };
  window.nhAddCurrent = addCurrentWork;
  // ⋮ メニューのための今の作品の状態。{inList, saved (端末に保存してある), url (作品のページ。保存は ここ + #nh-save で始める), viewer (話ページ = 「表示の設定」を出せる)}。作品のページでなければ null
  window.nhWorkState = () => {
    const w = NH.parseWork(location.href);
    const it = w && NH.findItem(NH.load(), w);
    return w ? { inList: !!it && NH.kindOf(it) === 'later', saved: !!NH.off.meta(w.site, w.id), url: w.url, viewer: !!window.nhOpenViewer } : null;
  };
  // 今の作品をリストから外す (⋮ メニューの「リストから削除」)。結果: removed / '' (作品のページではない・リストに無い)
  window.nhRemoveCurrent = () => {
    const w = NH.parseWork(location.href);
    let result = '';
    NH.update((d) => {
      result = '';
      const it = w && NH.findItem(d, w);
      if (!it || NH.kindOf(it) !== 'later') return; // メニューに「削除」を出したのは「後で読む」にあるとき (間に状態が変わったら、何もしない)
      NH.removeItem(d, it);
      window.__nhNoPosWrite = true; // 外したあとに読書位置を書き戻さない (ui-readpos.js)
      result = 'removed';
    });
    return result;
  };

  // ---- ページの下の欄 (感想・おすすめなど) を畳む (ui-fold.js) ----
  NH_UI_FOLD({ adapter, mk });

  // ---- 読書ページの背景色の仕上げ: 白い面と、その上の文字を合わせる (ui-readbg.js) ----
  NH_UI_READBG();

  // ---- 外部のリンクから開いた作品を「後で読む」に追加 (LinkOpener.open が #nh-later[-back] を付けて開く) ----
  // -back は追加したらすぐ、読んでいた作品に戻る (このページは読んでいないので閲覧履歴にも残さない)
  const laterM = cur && location.hash.match(/^#nh-later(-back)?$/);
  if (laterM) {
    history.replaceState(null, '', location.pathname + location.search); // 再読み込みで二重に追加しないよう印を外す
    const result = addCurrentWork() || 'failed';
    if (laterM[1]) NH.laterBack(result);
    else NH.toast({ added: '後で読むに追加しました', exists: 'すでにリストにあります', failed: '作品のページを開けなかったので、追加していません' }[result]);
  }
  // リストにある作品を開いたとき、ページから読めた連載状態で更新する (リストの絞り込み用。変わったときだけ書く)
  if (cur && (curTitle || (adapter.bodySel && document.querySelector(adapter.bodySel)))) {
    const st = pageStatus();
    const it0 = st ? NH.findItem(NH.load(), cur) : null;
    if (it0 && it0.status !== st) NH.update((d) => { const it = NH.findItem(d, cur); if (it) it.status = st; });
  }
  if (cur && !(laterM && laterM[1]) && (curTitle || document.querySelector(adapter.bodySel))) {
    NH.recordVisit(cur, curTitle, cur.episode != null ? location.origin + location.pathname : cur.url, isShort);
  }
  // 作品の小説情報を覚える・公式の閲覧履歴を取り込む (ui-work.js)
  NH_UI_WORK({ adapter, mk, cur });
  // 「表示の設定」(TT): サイトのビューワー設定の代わりに、アプリの表示の設定 (文字の大きさ・行間・書体・背景色。ui-viewer.js) を開く。
  //   ⋮ メニューの「表示の設定」が window.nhOpenViewer を呼ぶ (nhWorkState の viewer で、出すかを決める)。ページの上には置かない。
  // 話ページの一番上の列は、カクヨムだけ (サイトの目次パネルを開くハンバーガーが要るため): 「‹ 目次」(作品のページ。作品へのパンくずは sites.js で隠している) と、右に「目次の一覧」(ハンバーガー)。
  //   横書きでは、ページの一番上に置くだけで、スクロールで流れる (追いかけてこない)。縦書きのときだけ、画面の上に固定して、本文のタップで出し入れする (ui-vertical.js)。
  //   なろう・ハーメルンには列を置かない (前後の話・目次は各サイトの作りを使う)
  if (cur && (cur.episode != null || isShort) && adapter.bodySel && document.querySelector(adapter.bodySel) && !window.nhOpenViewer) { // 短編 (話番号が無く本文がある) も、表示の設定を出せる
    // サイトのビューワー設定のボタン。アプリのシートの下の「サイトの設定 (操作など) を開く」から押す。サイトのパネルの側にアプリのボタンを置くサイト (panelSel) では、行き来になるので出さない
    const siteBtn = adapter.viewerBtn && !adapter.panelSel ? adapter.viewerBtn : '';
    // サイトのパネルは、操作設定の欄だけ残してあるので、開いたら、その欄 (2 つ目のタブ) を出す
    const openSiteSettings = () => { const t = document.querySelector(siteBtn); if (!t) return; t.click(); setTimeout(() => { const tab = [...document.querySelectorAll('.js-episode-setting-tab-button')].find((b) => /操作/.test(b.textContent)); if (tab) tab.click(); }, 150); };
    const openViewer = NH_UI_VIEWER({ mk, openSite: siteBtn ? openSiteSettings : null });
    window.nhOpenViewer = openViewer;
    if (adapter.sideTocBtn) {
      const row = mk('div', 'nh-toc-top');
      row.id = 'nh-toc-top';
      // 左: 目次へ。右: ハンバーガー (サイトの目次パネル)。話のタイトルは、各サイトが本文の上に出している見た目をそのまま使う (ここには置かない)
      const left = mk('div', 'nh-toc-link');
      const toc = mk('a', 'nh-toc-nav', '‹ 目次');
      toc.href = cur.url;
      left.appendChild(toc);
      row.appendChild(left);
      const burger = mk('button', 'nh-toc-tool');
      burger.type = 'button';
      burger.setAttribute('aria-label', '目次の一覧');
      burger.innerHTML = '<svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true"><path fill="currentColor" d="M3 6h18v2H3V6zm0 5h18v2H3v-2zm0 5h18v2H3v-2z"/></svg>';
      // 元のボタンは、サイトのヘッダーごと隠している。押すだけなら、隠れたままでも効く (ヘッダーが作り直されたら、そのときの要素を探す)
      burger.onclick = () => { const t = document.querySelector(adapter.sideTocBtn); if (t) t.click(); };
      row.appendChild(burger);
      (document.body || document.documentElement).prepend(row);
      document.documentElement.classList.add('nh-has-toc'); // 上の列の分、本文を下げる
      // 背景色の設定 (readBg) があれば、上の列も、その背景と文字の色に合わせる (黒の背景の上に、明るい列だけが浮かないように)
      const rb = window.NH_READBG && window.NH_READBG.colors((NH.load().settings || {}).readBg);
      if (rb) { row.style.setProperty('--nh-bar-bg', rb.bg); row.style.setProperty('--nh-bar-fg', rb.fg); }
    }
    // なろうの「表示調整」: サイトのパネルはそのまま開く。パネルの一番下に、アプリの表示の設定を開くボタンを足す (アプリが持つ項目はパネルから隠してあるので、そこから行けるように)
    const panel = adapter.panelSel && document.querySelector(adapter.panelSel);
    if (panel && !panel.querySelector('.nh-panel-btn')) {
      const b = mk('button', 'nh-panel-btn', '表示の設定 (文字・背景色など)');
      b.type = 'button';
      b.onclick = openViewer;
      panel.appendChild(b);
    }
  }
  // 話ページの読書位置の記録と復元 (ui-readpos.js)。移る直前に位置を書き切る関数を、ページ内の移動の見張りに渡す
  // 縦書き (アプリの設定 siteVertical。ui-vertical.js): 縦書きの間は、読書位置は横スクロールの進みで読み書きする
  const vertical = NH_UI_VERTICAL({ adapter, mk, cur, isShort });
  const flush = NH_UI_READPOS({ adapter, cur, isShort, laterBack: !!(laterM && laterM[1]), moved, vertical });
  if (flush) flushPos = flush;

  // ---- 話ページ最下部の「読み終わり → 次の小説へ」 (ui-next.js) ----
  NH_UI_NEXT({ adapter, mk, cur });
})();
