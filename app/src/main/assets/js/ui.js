// UI: 更新の巡回 / オフライン保存の画面 / ランキングの「＋ 後で読む」・ミュート・絞り込み / 読書位置と閲覧履歴 / 話ページ最下部の「読了 → 次の小説へ」
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

  // 全画面の覆い (オフライン保存の確認・進み具合) のスタイル
  const OV_CSS = `
    .nh-ov{position:fixed;inset:0;z-index:2147483647;background:#12100e;color:#f2ede7;display:flex;flex-direction:column;
      align-items:center;justify-content:center;gap:22px;padding:24px;font-family:"Noto Sans JP",sans-serif}
    .nh-ov .spin{width:48px;height:48px;border-radius:50%;border:4px solid #2a2520;border-top-color:#3fae73;animation:nhspin .9s linear infinite}
    @keyframes nhspin{to{transform:rotate(360deg)}}
    .nh-ov h3{margin:0;font-size:19px;font-weight:800}
    .nh-save{display:flex;flex-direction:column;align-items:center;gap:16px;max-width:100%;max-height:100%}
    .nh-ov p{margin:0;font-size:14px;line-height:1.6;color:#ab9f94;text-align:center;max-width:330px}
    .nh-ov p.wt{color:#f2ede7;font-weight:700;font-size:15px}
    .nh-ov p.big{color:#f2ede7;font-size:26px;font-weight:800}
    .nh-ov .btns{display:flex;gap:10px;flex-wrap:wrap;justify-content:center}
    .nh-ov button{border:0;border-radius:999px;padding:12px 24px;font-size:15px;font-weight:700;background:#3fae73;color:#06210f;font-family:inherit}
    .nh-ov button.sub{background:#2a2520;color:#f2ede7}
    .nh-ov button.small{padding:7px 14px;font-size:13px}
    .nh-ov .seg{display:flex;gap:4px;background:#1c1916;border-radius:12px;padding:3px;border:1px solid #3a342d}
    .nh-ov .seg button{background:none;color:#ab9f94;padding:8px 16px;border-radius:9px;font-size:14px;font-weight:600}
    .nh-ov .seg button.on{background:#3fae73;color:#06210f;font-weight:800}
    .nh-ov .panel{display:flex;flex-direction:column;align-items:center;gap:10px}
    .nh-ov .range{display:flex;align-items:center;gap:8px;color:#f2ede7;font-size:14px;flex-wrap:wrap;justify-content:center}
    .nh-ov input[type=number]{width:76px;height:38px;border:0;border-radius:10px;background:#1c1916;color:#f2ede7;font-size:16px;text-align:center}
    .nh-ov .pick{max-height:34vh;overflow:auto;width:min(340px,92vw);background:#1c1916;border-radius:14px;padding:4px 14px}
    .nh-ov .pick label{display:flex;gap:10px;align-items:center;padding:9px 0;font-size:14px;color:#f2ede7;line-height:1.4}
    .nh-ov .bar{width:min(300px,80vw);height:6px;border-radius:3px;background:#2a2520;overflow:hidden}
    .nh-ov .bar i{display:block;height:100%;width:0;background:#3fae73;transition:width .3s}
  `;

  // ---- 共通スタイル ----
  const style = document.createElement('style');
  style.textContent = OV_CSS + `
    .nh-chip{display:inline-flex;align-items:center;gap:6px;margin:8px 0 6px;height:32px;padding:0 14px;border:1.5px solid #2f8f5b;border-radius:999px;
      background:#fff;color:#23744a;font:700 13px/1 "Noto Sans JP",sans-serif;letter-spacing:.02em;cursor:pointer;
      box-shadow:0 1px 2px rgba(0,0,0,.06);transition:transform .12s,background .15s,color .15s}
    .nh-chip:active{transform:scale(.95)}
    .nh-chip.on{background:#2f8f5b;color:#fff;border-color:#2f8f5b}
    .nh-fold{display:block;box-sizing:border-box;width:calc(100% - 24px);margin:10px 12px;height:42px;border:1.5px solid rgba(128,128,134,.3);border-radius:12px;
      background:transparent;color:#85858b;font:600 14px "Noto Sans JP",sans-serif;cursor:pointer}
    .nh-fold:active{opacity:.6}
    .nh-toast{position:fixed;left:50%;bottom:max(24px,env(safe-area-inset-bottom));transform:translateX(-50%);z-index:2147483647;max-width:88vw;
      background:#2b2621;color:#f2ede7;padding:12px 20px;border-radius:14px;font:600 14px/1.4 "Noto Sans JP",sans-serif;
      box-shadow:0 10px 30px rgba(0,0,0,.35);animation:nhin .22s cubic-bezier(.2,.8,.2,1)}
    @keyframes nhin{from{opacity:0;transform:translate(-50%,12px)}}
    .nh-next{margin:40px 16px 96px;padding:18px;border-radius:24px;background:#fff;color:#1f1a16;
      font-family:"Noto Sans JP",sans-serif;box-shadow:0 1px 2px rgba(0,0,0,.06),0 10px 30px rgba(30,20,10,.12);border:1px solid rgba(60,40,20,.08)}
    .nh-next.inline{margin:20px 12px 28px;text-align:left;clear:both}
    .nh-next-cap{display:flex;align-items:center;justify-content:center;gap:8px;font-size:12px;font-weight:700;letter-spacing:.1em;color:#6f645a;margin-bottom:14px}
    .nh-next-cap::before,.nh-next-cap::after{content:"";height:1px;width:28px;background:rgba(60,40,20,.15)}
    .nh-next-btn{display:block;width:100%;text-align:left;border:0;border-radius:18px;padding:16px 56px 16px 18px;color:#fff;position:relative;cursor:pointer;
      background:radial-gradient(120% 140% at 100% 0%,#3fae73 0%,#23744a 60%,#1b5c3b 100%);box-shadow:0 8px 22px rgba(35,116,74,.3)}
    .nh-next-btn::after{content:"";position:absolute;right:16px;top:50%;width:28px;height:28px;margin-top:-14px;border-radius:50%;
      background:rgba(255,255,255,.2) url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='white'%3E%3Cpath d='M10 6 8.59 7.41 13.17 12l-4.58 4.59L10 18l6-6z'/%3E%3C/svg%3E") center/20px no-repeat}
    .nh-next-btn:active{transform:scale(.985)}
    .nh-next-btn .t1{display:block;font-size:12px;font-weight:700;opacity:.85;margin-bottom:4px;letter-spacing:.04em}
    .nh-next-btn .t2{display:block;font-size:16px;font-weight:800;line-height:1.45;word-break:break-all}
    .nh-next-btn .t3{display:block;font-size:12px;opacity:.8;margin-top:6px}
    .nh-next-btn.last{background:linear-gradient(135deg,#4a3a2c,#2b221b);box-shadow:0 8px 22px rgba(40,30,20,.3)}
    .nh-next-sub{display:block;width:100%;margin-top:10px;height:44px;border:0;border-radius:14px;background:#efeae3;color:#1f1a16;font:700 14px "Noto Sans JP",sans-serif;cursor:pointer}
    .nh-next-row{display:flex;gap:10px;align-items:stretch}
    .nh-next-row .nh-next-btn{flex:1;min-width:0}
    .nh-next-leave{flex:0 0 auto;width:92px;white-space:pre-line;border:0;border-radius:18px;background:#efeae3;color:#1f1a16;font:700 13px/1.4 "Noto Sans JP",sans-serif;cursor:pointer;padding:0 8px}
    .nh-next-leave:active{opacity:.7}
    .nh-toc-top{display:block;padding:12px 16px;font:700 14px "Noto Sans JP",sans-serif;color:#23744a;text-decoration:none;background:#f6f2ec;border-bottom:1px solid rgba(60,40,20,.08)}
    .nh-toc-top:active{opacity:.7}
    .nh-hist{display:inline-block;margin:4px 0 2px;padding:2px 10px;border-radius:999px;background:rgba(47,143,91,.12);color:#23744a;font:700 12px/1.6 "Noto Sans JP",sans-serif}
    .nh-banner{position:fixed;left:16px;right:16px;bottom:max(20px,env(safe-area-inset-bottom));z-index:2147483647;border:0;text-align:left;cursor:pointer;
      padding:14px 18px;border-radius:14px;background:#2b2621;color:#f2ede7;font:600 14px/1.5 "Noto Sans JP",sans-serif;box-shadow:0 10px 30px rgba(0,0,0,.35);
      animation:nhin .22s cubic-bezier(.2,.8,.2,1)}
    .nh-chips{display:flex;flex-wrap:wrap;gap:8px;margin:8px 0 6px}
    .nh-chips .nh-chip{margin:0}
    .nh-chip-mute{border-color:rgba(120,120,128,.45);color:#6e6e73;background:#fff}
    .nh-read{opacity:.5}
    .nh-read-tag{display:inline-flex;align-items:center;height:28px;padding:0 10px;border-radius:999px;background:rgba(120,120,128,.16);color:#6e6e73;font:700 12px "Noto Sans JP",sans-serif}
    .nh-muted{display:none!important}
    .nh-blocked,.nh-blocked-link{display:none!important}
    .nh-toast button{margin-left:14px;border:0;background:none;color:#7fd3a0;font:inherit;cursor:pointer;padding:0}
    .nh-sheet-seg{display:flex;gap:2px;padding:2px;margin-bottom:12px;border-radius:10px;background:rgba(118,118,128,.12)}
    .nh-sheet-seg button{flex:1;height:34px;border:0;border-radius:8px;background:none;color:#000;font:600 14px "Noto Sans JP",sans-serif;cursor:pointer}
    .nh-sheet-seg button.on{background:#fff;box-shadow:0 1px 3px rgba(0,0,0,.15)}
    .nh-sheet-note{font-size:12px;color:rgba(60,60,67,.6);margin:-4px 4px 12px}
    .nh-reveal .nh-muted,.nh-muted.nh-muted-dim{display:revert!important;opacity:.35}
    .nh-muted::before{content:"ミュート中 (" attr(data-nh-muted) ")";display:block;font:600 12px/1.6 "Noto Sans JP",sans-serif;color:#ff3b30}
    .nh-mute-pill{position:fixed;left:50%;transform:translateX(-50%);bottom:max(20px,env(safe-area-inset-bottom));z-index:2147483646;border:0;cursor:pointer;
      padding:10px 18px;border-radius:999px;background:rgba(44,44,46,.94);color:#fff;font:600 13px/1.3 "Noto Sans JP",sans-serif;box-shadow:0 6px 20px rgba(0,0,0,.3)}
    .nh-mute-pill[hidden],.nh-read-tag[hidden]{display:none!important}
    .nh-filtered{display:none!important}
    .nh-filter{position:fixed;left:12px;right:12px;bottom:max(14px,env(safe-area-inset-bottom));z-index:2147483646;display:flex;gap:2px;padding:3px;
      border-radius:12px;background:#f2f2f7;box-shadow:0 6px 24px rgba(0,0,0,.18);font:500 13px/1 "Noto Sans JP",sans-serif}
    .nh-filter[hidden]{display:none}
    .nh-filter button{flex:1;border:0;background:none;color:#000;padding:9px 2px;border-radius:9px;font:inherit;cursor:pointer;white-space:nowrap}
    .nh-filter button.on{background:#fff;font-weight:700;box-shadow:0 2px 6px rgba(0,0,0,.12)}
    .nh-has-filter .nh-mute-pill{bottom:calc(max(14px,env(safe-area-inset-bottom)) + 56px)}
    .nh-has-filter body{padding-bottom:72px}
    .nh-scrim{position:fixed;inset:0;z-index:2147483646;background:rgba(0,0,0,.35)}
    .nh-sheet{position:fixed;left:0;right:0;bottom:0;z-index:2147483647;max-height:85vh;overflow-y:auto;background:#f2f2f7;color:#000;
      border-radius:14px 14px 0 0;padding:16px 16px max(20px,env(safe-area-inset-bottom));font:15px/1.45 "Noto Sans JP",sans-serif;
      animation:nhup .28s cubic-bezier(.3,.7,.4,1)}
    @keyframes nhup{from{transform:translateY(100%)}}
    .nh-sheet-title{font-weight:700;font-size:15px;margin:0 4px 12px;word-break:break-all}
    .nh-sheet-label{font-size:13px;color:rgba(60,60,67,.6);margin:16px 4px 8px}
    .nh-sheet-group{border-radius:12px;overflow:hidden}
    .nh-sheet-row{display:block;width:100%;min-height:50px;border:0;background:#fff;color:#ff3b30;font:500 16px "Noto Sans JP",sans-serif;cursor:pointer}
    .nh-sheet-group .nh-sheet-row+.nh-sheet-row{border-top:.5px solid rgba(60,60,67,.18)}
    .nh-sheet-link{margin-top:16px;border-radius:12px;color:#1e9a55}
    .nh-sheet-cancel{margin-top:8px;border-radius:12px;color:#1e9a55;font-weight:700}
    .nh-dialog{position:fixed;left:50%;top:50%;transform:translate(-50%,-50%);z-index:2147483647;width:min(300px,86vw);overflow:hidden;
      background:#fff;color:#000;border-radius:14px;text-align:center;font:15px/1.45 "Noto Sans JP",sans-serif;box-shadow:0 10px 30px rgba(0,0,0,.25)}
    .nh-dialog-body{padding:18px 16px 14px}
    /* 文字は 1 行に収まるなら 1 行、収まらなければ句の切れ目で 2 行にそろえる (text-wrap: balance) */
    .nh-dialog-title,.nh-dialog-note,.nh-dialog-row{text-wrap:balance;word-break:auto-phrase}
    .nh-dialog-title{font-weight:700;font-size:17px;margin-bottom:6px}
    .nh-dialog-note{font-size:13px;color:rgba(60,60,67,.75)}
    .nh-dialog-row{display:block;width:100%;min-height:48px;border:0;border-top:.5px solid rgba(60,60,67,.25);background:none;color:#007aff;font:500 17px "Noto Sans JP",sans-serif;cursor:pointer}
    .nh-sheet-tags{display:flex;flex-wrap:wrap;gap:8px}
    .nh-sheet-tag{border:0;border-radius:999px;padding:7px 12px;background:#fff;color:#000;font:500 14px "Noto Sans JP",sans-serif;cursor:pointer}
    .nh-sheet-form{display:flex;gap:8px}
    .nh-sheet-form input{flex:1;min-width:0;height:44px;border:0;border-radius:10px;padding:0 12px;background:#fff;color:#000;font:16px "Noto Sans JP",sans-serif}
    .nh-sheet-add{border:0;border-radius:10px;padding:0 18px;background:#1e9a55;color:#fff;font:700 15px "Noto Sans JP",sans-serif;cursor:pointer}
  `;
  (document.head || document.documentElement).appendChild(style);

  const mk = (tag, cls, text) => {
    const e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text;
    return e;
  };

  // ---- 更新の巡回取得 ----
  // 画面に出さない WebView (Collector.kt) が、なろうのブックマークから順に各サイトの一覧を開く。
  // サイトごとに、そのサイト上で取得して取り込み (設定で非表示のサイトは飛ばす)、終わったら結果を NHStore.bgDone で返す。
  //   #nh-collect-bg = 裏での確認 (BgCheckJob)、#nh-collect-fg = アプリの「更新」ボタン、#nh-collect-fg-auto = 起動時の自動確認
  const SEQ = NH.COLLECT_IDS; // 巡回の順 (core.js の SITE_DEFS の順)
  const hashM = location.hash.match(/^#nh-collect-(bg|fg|fg-auto)$/);
  if (hashM && adapter.collectUpdates && adapter.onCollectPage && adapter.onCollectPage(location)) {
    const mode = hashM[1];
    const auto = mode !== 'fg'; // 自動 (起動時 / 裏): 止められたサイトの休止を長めにする
    const bg = mode === 'bg'; // アプリを開いていないときの確認 (lastCollectFg を付けない)
    // このサイトの確認に失敗した: 結果 (collectLog) に残す (結果の表示・通知に「確認できなかった」と出す)
    const logErr = (id, err) => NH.update((d) => { d.collectLog = d.collectLog || {}; d.collectLog[id] = { err }; });
    // このページ自体が普通に開けたなら、止められてはいない (確認画面を通過した場合を含む)
    if (!challenge) NH.update((d) => { if (d.rest) delete d.rest[adapter.id]; });
    const d0 = NH.load();
    const usable = SEQ.filter((id) => NH.collectActive(d0, id) && NH.adapters.some((a) => a.id === id && a.collectUpdates));
    // 直前に止められた (Cloudflare の確認など) サイトは、しばらく取りに行かない
    const resting = usable.filter((id) => NH.isResting(d0, id, auto));
    const enabled = usable.filter((id) => !resting.includes(id));
    const here = enabled.includes(adapter.id);
    // 巡回は最初の対象サイトから始まる (MainActivity.collectStartUrl)。そのサイトが結果をまっさらにする
    if (adapter.id === usable[0]) {
      NH.update((d) => { d.collectLog = {}; });
      resting.forEach((id) => logErr(id, 'rest'));
    }

    // 巡回のページは履歴に残さない (replace)
    const go = (delay) => setTimeout(() => {
      const nextId = SEQ.slice(SEQ.indexOf(adapter.id) + 1).find((id) => enabled.includes(id));
      if (nextId) {
        location.replace(NH.adapters.find((a) => a.id === nextId).collectUrl + '#nh-collect-' + mode);
        return;
      }
      // lastCollectFg = アプリの画面で確認した時刻 (直後の裏での確認は通知しない)
      NH.update((d) => { d.lastCollect = Date.now(); if (!bg) d.lastCollectFg = d.lastCollect; });
      if (window.NHStore && NHStore.bgDone) NHStore.bgDone(JSON.stringify(NH.load().collectLog || {}));
    }, delay);

    if (!here) return go(0);
    // 確認の進み具合 (リスト画面の「1 / 4」): 対象のサイトの何番目か
    if (window.NHStore && NHStore.progress) NHStore.progress(String(enabled.indexOf(adapter.id) + 1), String(enabled.length));
    // 確認画面のままなら少し待ち (通過すれば読み込み直されてここは消える)、だめならこのサイトは休止して飛ばす
    if (challenge) {
      logErr(adapter.id, 'challenge');
      NH.markRest(adapter.id);
      return go(15000);
    }
    adapter.collectUpdates().then(
      (r) => {
        if (!r.ok) {
          logErr(adapter.id, 'login');
          return go(2500);
        }
        // 「後で読む」に置いた作品は数えない (未読の更新には出さない)
        const { added, updates, fresh } = NH.mergeUpdates(adapter.id, r.items, { truncated: !!r.truncated, readIds: r.readIds, cats: r.cats });
        NH.update((d) => {
          d.collectLog = d.collectLog || {};
          d.collectLog[adapter.id] = { n: updates, added, fresh };
        });
        go(1500 + Math.random() * 1000); // サイトからサイトへ移るときも間をあける
      },
      (e) => {
        const blocked = e && e.message === NH.CHALLENGE;
        if (blocked) NH.markRest(adapter.id); // アクセス制限: これ以上は取りに行かない
        logErr(adapter.id, blocked ? 'blocked' : 'error');
        go(2500);
      }
    );
    return;
  }

  const saveWork = location.hash === '#nh-save' && adapter.bodySel ? NH.parseWork(location.href) : null;
  // オフライン保存の画面 (ui-save.js)
  if (saveWork) { NH_UI_SAVE({ adapter, challenge, mk, dropCover, askDialog }, saveWork); return; }

  dropCover(); // ここから先は保存ではない

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
      result = 'exists'; // NH.update がやり直したときのために、ここで決め直す
      const it = NH.findItem(d, w);
      if (it && it.done) { NH.setDone(it, false); result = 'added'; return; } // 読了の作品は、読了を外してリストに戻す
      if (it) return;
      d.readLater.push(NH.withSub({ key: NH.workKey(w), site: w.site, id: w.id, url: w.url, title, addedAt: Date.now(), done: false, kind: 'later', ...(status ? { status } : {}) }));
      result = 'added';
    });
    return result;
  };
  window.nhAddCurrent = addCurrentWork;
  // ⋮ メニューのための今の作品の状態。{inList, saved (端末に保存してある), url (作品のページ。保存は ここ + #nh-save で始める)}。作品のページでなければ null
  window.nhWorkState = () => {
    const w = NH.parseWork(location.href);
    const it = w && NH.findItem(NH.load(), w);
    return w ? { inList: !!it && !it.done, saved: !!NH.off.meta(w.site, w.id), url: w.url } : null;
  };
  // 今の作品をリストから外す (⋮ メニューの「リストから削除」)。結果: removed / '' (作品のページではない・リストに無い)
  window.nhRemoveCurrent = () => {
    const w = NH.parseWork(location.href);
    let result = '';
    NH.update((d) => {
      result = '';
      const it = w && NH.findItem(d, w);
      if (!it) return;
      d.readLater = d.readLater.filter((x) => x !== it);
      NH.dropProgress(d, [it.key]);
      result = 'removed';
    });
    return result;
  };

  // ---- ページの下の欄 (感想・おすすめなど) を畳む ----
  // 畳むのは early.js の CSS (描画前)。ここは開閉のボタンを置く。設定 foldComments などの fold* (既定オン)、adapter.foldGroups を持つサイトだけ。
  // 開いたかどうかは、設定 foldRemember (既定オン) のときサイトごとに settings.foldOpen に覚える (開いたまま次の話へ)。サイトの「感想を書く」を押したときは、畳んだ感想欄も出す
  const FOLD = [['comments', 'foldComments', '感想・お知らせ'], ['recommend', 'foldRecommend', 'おすすめ'], ['links', 'foldLinks', '作者・共有・リンク'], ['reactions', 'foldReactions', '応援・リアクション']];
  const openers = {};
  const repaints = {};
  const foldSite = location.hostname === 'syosetu.org' ? 'hameln' : location.hostname === 'kakuyomu.jp' ? 'kakuyomu' : 'narou';
  const rememberFold = (k, open) => {
    if ((NH.load().settings || {}).foldRemember === false) return;
    NH.update((d) => {
      const s = (d.settings = d.settings || {});
      const o = ((s.foldOpen = s.foldOpen || {})[foldSite] = s.foldOpen[foldSite] || {});
      if (open) o[k] = true; else delete o[k];
    });
  };
  // 畳む CSS (early.js が描画の前に入れる)。開いたままの欄 (foldRemember) は early.js が入れないので、閉じるときにここで入れる。
  // 同じ欄に 2 回入れない (id で見分ける)
  function ensureFoldHide(k, sel) {
    const id = 'nh-fold-hide-' + k;
    if (document.getElementById(id)) return;
    const style = mk('style');
    style.id = id;
    style.textContent = sel.split(',').map((x) => x + ':not([data-nh-open])').join(',') + '{display:none!important}';
    (document.head || document.documentElement).appendChild(style);
  }
  function setupFold() {
    const groups = adapter.foldGroups;
    if (!groups) return;
    const st = NH.load().settings || {};
    FOLD.forEach(([k, flag, label]) => {
      if (!groups[k] || st[flag] === false) return;
      // 置いたあとに増えた枠 (広告のスクリプトが後から差し込む) も、開閉の対象に入れる
      if (repaints[k]) { repaints[k](); return; }
      const first = document.querySelector(groups[k]);
      if (!first) return;
      const btn = mk('button', 'nh-fold');
      btn.id = 'nh-fold-' + k;
      let open = st.foldRemember !== false && !!((st.foldOpen || {})[foldSite] || {})[k];
      const paint = () => {
        ensureFoldHide(k, groups[k]);
        document.querySelectorAll(groups[k]).forEach((b) => (open ? b.setAttribute('data-nh-open', '') : b.removeAttribute('data-nh-open')));
        // 同じ文字なら代入しない (代入すると childList の監視が反応し、探し直しが続いてしまう)
        const text = (open ? '▲ ' : '▼ ') + label + (open ? 'を隠す' : 'を表示');
        if (btn.textContent !== text) btn.textContent = text;
      };
      btn.onclick = () => { open = !open; paint(); rememberFold(k, open); };
      openers[k] = () => { if (!open) { open = true; paint(); rememberFold(k, true); } };
      repaints[k] = paint;
      first.insertAdjacentElement('beforebegin', btn);
      paint();
    });
  }
  if (adapter.foldGroups) {
    setupFold();
    setTimeout(setupFold, 1500); // 後から描かれるページ (カクヨムなど) 用
    setTimeout(setupFold, 4000);
    // 広告の枠などは広告のスクリプトが後から差し込む。読み込みから 20 秒は、DOM が増えたところで探し直す (置いたボタンは二重に作らない)
    let foldTimer = null;
    const foldWatch = new MutationObserver(() => { if (!foldTimer) foldTimer = setTimeout(() => { foldTimer = null; setupFold(); }, 400); });
    foldWatch.observe(document.documentElement, { childList: true, subtree: true });
    setTimeout(() => foldWatch.disconnect(), 20000);
    document.addEventListener('click', (e) => {
      const a = e.target.closest && e.target.closest('a');
      if (a && /感想を書く/.test(a.textContent) && openers.comments) openers.comments();
    }, true);
  }

  // ---- 外部のリンクから開いた作品を「後で読む」に追加 (MainActivity.openLink が #nh-later[-back] を付けて開く) ----
  // -back は追加したらすぐ、読んでいた作品に戻る (このページは読んでいないので閲覧履歴にも残さない)
  const laterM = cur && location.hash.match(/^#nh-later(-back)?$/);
  if (laterM) {
    history.replaceState(null, '', location.pathname + location.search); // 再読み込みで二重に追加しないよう印を外す
    const result = addCurrentWork() || 'failed';
    if (laterM[1] && window.NHStore && NHStore.laterBack) NHStore.laterBack(result);
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
  // 作品の小説情報 (全話数・連載状態・R18 の掲載サイト) を、リストの作品に覚える (続きから読むの「第N話/全N話」などに使う)。
  // 覚えてから 1 日以内なら読み直さない (サイトへの通信を減らす)。確認画面が出たら、そのサイトを休止する
  async function rememberInfo(w) {
    const DAY = 86400000;
    const d0 = NH.load();
    const it = NH.findItem(d0, w);
    // 読めたが全話数が分からなかったとき (作品情報に無い作品) も totalTry を残して、1 日は読み直さない。止められて休止中のサイトにも取りに行かない
    if (!it || NH.isResting(d0, adapter.id, true) || (it.totalAt && Date.now() - it.totalAt < DAY) || (it.totalTry && Date.now() - it.totalTry < DAY)) return;
    let info = null;
    try {
      info = await adapter.workInfo(w);
    } catch (e) {
      const msg = (e && e.message) || '';
      if (msg === NH.CHALLENGE) NH.markRest(adapter.id);
      // 通信の失敗は記録しない (次に開いたとき読み直す)。ただし 4xx (削除された作品など) は読み直しても同じなので、1 日あける
      else if (/^http 4/.test(msg)) NH.update((d) => { const x = NH.findItem(d, w); if (x) x.totalTry = Date.now(); });
      return;
    }
    const total = info && info.total;
    NH.update((d) => {
      const x = NH.findItem(d, w);
      if (!x) return;
      x.totalTry = Date.now();
      if (!total) return;
      Object.assign(x, { total, totalAt: x.totalTry });
      delete x.totalTry;
      x.r18 = !!info.r18; // R-18 のタグがあるか (NSFW モードで隠す)。未確認の作品は undefined のまま
      if (info.status && !x.status) x.status = info.status;
      if (info.sub) x.sub = info.sub;
    });
  }
  if (cur && cur.episode == null && adapter.workInfo) rememberInfo(cur);

  // ---- 公式の閲覧履歴ページ: 開いたときだけ読み取り、アプリの履歴と突き合わせる ----
  if (adapter.isHistoryPage && adapter.isHistoryPage(location) && NH.historyOn(NH.load())) {
    const items = adapter.parseHistory(document);
    if (items.length) {
      const added = NH.importSiteHistory(adapter.id, items);
      const d = NH.load();
      // 各作品に、アプリでの読み具合を添える
      items.forEach((u) => {
        const key = adapter.id + ':' + u.id;
        const p = (d.progress || {})[key];
        const inList = d.readLater.some((x) => x.key === key && !x.done);
        const label = [p ? (NH.epNumber(p) ? 'アプリ: 第' + NH.epNumber(p) + '話' : 'アプリで読書中') + (p.done ? ' 読了' : p.y >= 0.01 ? ' ' + Math.round(p.y * 100) + '%' : '') : '', inList ? 'リスト' : '']
          .filter(Boolean).join(' ・ ');
        if (label && u.anchor && !u.anchor.dataset.nhHist) {
          u.anchor.dataset.nhHist = '1';
          u.anchor.insertAdjacentElement('afterend', mk('div', 'nh-hist', label));
        }
      });
      const bn = mk('button', 'nh-banner', '閲覧履歴をアプリと照合しました' + (added ? ' (新しく ' + added + ' 件)' : '') + ' ・ アプリの履歴を開く');
      bn.addEventListener('click', () => (location.href = NH.HISTORY_URL));
      (document.body || document.documentElement).appendChild(bn);
      setTimeout(() => bn.remove(), 8000);
    }
  }
  // 話ページの一番上に「目次へ」(作品のページ)。アダプターの topToc が true のサイトだけ (カクヨム。作品へのパンくずは sites.js で隠している)
  if (cur && cur.episode != null && adapter.topToc && adapter.bodySel && document.querySelector(adapter.bodySel) && !document.getElementById('nh-toc-top')) {
    const toc = mk('a', 'nh-toc-top', '目次へ');
    toc.id = 'nh-toc-top';
    toc.href = cur.url;
    (document.body || document.documentElement).prepend(toc);
  }
  // 短編の読書位置は話番号 '0' として持つ (オフライン保存の短編と同じ)
  if (cur && adapter.bodySel && (cur.episode != null || isShort) && !(laterM && laterM[1])) {
    const key = NH.workKey(cur);
    const epNo = isShort ? '0' : String(cur.episode);
    const epUrl = location.origin + location.pathname;
    // 読んだ文字数 (空白・改行を除く。読書の統計用)
    const bodyChars = () => [...document.querySelectorAll(adapter.bodySel)].reduce((n, e) => n + (e.textContent || '').replace(/\s/g, '').length, 0);
    let counted = false; // この話は、このページを開いているあいだ 1 回だけ、読書の統計に数える
    const bodyBox = () => {
      const els = [...document.querySelectorAll(adapter.bodySel)];
      if (!els.length) return null;
      let top = Infinity, bot = 0;
      els.forEach((e) => {
        const r = e.getBoundingClientRect();
        top = Math.min(top, r.top + window.scrollY);
        bot = Math.max(bot, r.bottom + window.scrollY);
      });
      return { top, h: Math.max(1, bot - top) };
    };
    let touched = false;
    let armed = false; // 復元が落ち着くまでは記録しない (先頭の 0 で上書きしないため)
    let lastY = -1, lastDone = null; // 前に書いた位置 (ほとんど動いていなければ書かない。書くたびに保存データ全体を書き直すため)
    ['touchstart', 'wheel', 'keydown'].forEach((ev) => window.addEventListener(ev, () => { touched = true; armed = true; }, { passive: true }));
    // 話番号が連番でないサイト (カクヨム) は、目次の並びから何話目かを求めて、読書位置に no として足す。
    // 同じ話で分かっていれば通信しない。確認画面などで取れなかったら、そのサイトを休止する
    const prev = (NH.load().progress || {})[key];
    let epOrd = prev && prev.ep === epNo && prev.no ? prev.no : null;
    // 前に最後まで読んだ話を開き直したとき (履歴・続きから読む) は、前回の位置に戻しただけで読了になるので、統計にもう一度数えない
    if (prev && prev.url === epUrl && prev.done) counted = true;
    const write = () => {
      if (!armed || moved()) return;
      const b = bodyBox();
      if (!b) return;
      const { y, done } = NH.scrollState(window.scrollY, b.top, b.h, window.innerHeight);
      if (Math.abs(y - lastY) < 0.005 && done === lastDone) return;
      lastY = y; lastDone = done;
      const next = adapter.nextUrl ? adapter.nextUrl() : null;
      const note = done && !counted; // 最後まで読んだ (初めて)
      const chars = note ? bodyChars() : 0;
      if (note) counted = true;
      NH.update((d) => {
        d.progress = d.progress || {};
        d.progress[key] = { url: epUrl, ep: epNo, no: epOrd || undefined, y: Math.max(0, y), done, next, at: Date.now() };
        if (note) NH.noteRead(d, key, chars);
        NH.pruneProgress(d);
      });
    };
    if (!epOrd && !isShort && adapter.episodeNo && !NH.isResting(NH.load(), adapter.id, true)) {
      adapter.episodeNo(cur).then((n) => { if (n) { epOrd = n; lastY = -1; write(); } })
        .catch((e) => { if (e && e.message === NH.CHALLENGE) NH.markRest(adapter.id); });
    }
    // 書くたびに保存データ全体を書き直すので、スクロール中は 4 秒に 1 回まで (止まったときの分も含めて遅れて書く)。ページを離れる / 隠れるときは write を直接呼ぶ
    let timer = 0;
    window.addEventListener('scroll', () => { if (!timer) timer = setTimeout(() => { timer = 0; write(); }, 4000); }, { passive: true });
    window.addEventListener('pagehide', write);
    document.addEventListener('visibilitychange', () => { if (document.hidden) write(); });
    flushPos = write;

    const saved = (NH.load().progress || {})[key];
    const restoring = saved && saved.url === epUrl && saved.y > 0.005 && !location.hash; // 読了の位置を過ぎていても、その話を開き直したときは前回の位置から
    let restoredAt = -1; // 最後に戻した scrollY (そこから動いていれば、音量キーなどで利用者が動かした)
    const restore = () => {
      if (touched) return;
      const b = bodyBox();
      if (b) { window.scrollTo(0, b.top + saved.y * b.h); restoredAt = window.scrollY; }
    };
    // 前回の位置まで戻せたか (本文の描画が遅いと、まだ戻せていないことがある)
    const restored = () => {
      const b = bodyBox();
      return !!b && NH.scrollState(window.scrollY, b.top, b.h, window.innerHeight).y >= saved.y - 0.02;
    };
    if (restoring) {
      [0, 300, 900, 2000].forEach((ms) => setTimeout(restore, ms));
      setTimeout(() => { if (!touched) NH.toast('前回の位置から再開'); }, 400);
    }
    // 記録を始める。前回の位置へまだ戻せていなければ、もう少し待って戻し直す (先頭の位置で前回の位置を上書きしないため)。
    // 戻し直すのは、戻した位置から動いていないときだけ (音量キー・自動スクロールで読み進めた位置を引き戻さない)。
    // 何度か待っても戻せなければ (文字の大きさを変えて、前の位置まで届かないなど)、そのまま記録を始める
    const arm = (tries) => {
      if (armed) return;
      const userMoved = restoredAt >= 0 && Math.abs(window.scrollY - restoredAt) > 2;
      if (restoring && !touched && !userMoved && !restored() && tries > 0) {
        restore();
        setTimeout(() => arm(tries - 1), 1500);
        return;
      }
      armed = true;
      write();
    };
    setTimeout(() => arm(5), 2300);
  }

  // ---- 話ページ最下部の「読了 → 次の小説へ」 ----
  if (cur) {
    const d = NH.load();
    const it = NH.findItem(d, cur);
    // 出すのは「読み終わり」のページだけ:
    //  - 話ページ: 次の話が無い (最新話) か、更新取得で分かった最終話 (lastEp) に着いた
    //    (lastEp との比較は話番号が連番のサイト = seqEpisodes だけ。カクヨムの話 ID は連番でない。
    //     ハーメルンの lastEp は全話数で、話番号が飛ぶ作品では最後の話より手前で着いたことになるので比べない = lastEpIsCount)
    //  - 話番号の無いページ: 本文があるもの (短編) だけ。目次ページには出さない
    const hasNext = adapter.nextUrl ? !!adapter.nextUrl() : false;
    const atEnd = cur.episode == null
      ? !!(adapter.bodySel && document.querySelector(adapter.bodySel))
      : !hasNext || (!!adapter.seqEpisodes && !adapter.lastEpIsCount && !!it && !!it.lastEp && Number(cur.episode) >= it.lastEp);
    if (it && atEnd) {
      // 次の小説: リストで絞り込んで開いたときは、その並びの続き (並びの最後なら、それで終わり)。
      // そうでなければ、同じ種類 (未読 / 後で読む) の先頭、無ければ残り全体の先頭
      const { seq, next, remaining } = NH.nextPool(d, it);
      const wrap = mk('div', 'nh-next');
      wrap.appendChild(mk('div', 'nh-next-cap', 'ここまで読みました'));
      const b = mk('button', 'nh-next-btn' + (next ? '' : ' last'));
      if (next) {
        const t1 = seq ? '読了して、リストの並びの次へ' : '読了して、次の小説へ'; // seq = 絞り込み・並べ替えたリストの並びの続き
        b.append(mk('span', 't1', t1), mk('span', 't2', next.title), mk('span', 't3', '残り ' + remaining + ' 作品'));
      } else if (seq) {
        b.append(mk('span', 't1', '読み切りました'), mk('span', 't2', 'リストの並びの最後です'), mk('span', 't3', 'リストに戻ります'));
      } else {
        b.append(mk('span', 't1', '読み切りました'), mk('span', 't2', 'これで最後です'), mk('span', 't3', 'リストに戻ります'));
      }
      const markRead = () => NH.update((dd) => { const x = NH.findItem(dd, cur); if (x) NH.setDone(x, true); });
      b.addEventListener('click', () => {
        markRead();
        NH.go(next ? NH.resumeUrl(NH.load(), next) : NH.LIST_URL);
      });
      // 左: 読了してリストへ戻る (次の作品はそのまま残る)。右: 次の作品。
      // 次の作品が無いときは、右のボタン (読み切り) もリストへ戻るので、左は出さない
      const row = mk('div', 'nh-next-row');
      if (next) {
        const leave = mk('button', 'nh-next-leave', '読了して\nリストへ');
        leave.addEventListener('click', () => { markRead(); location.href = NH.LIST_URL; });
        row.appendChild(leave);
      }
      row.appendChild(b);
      wrap.appendChild(row);
      const sub = mk('button', 'nh-next-sub', 'リストを見る');
      sub.addEventListener('click', () => (location.href = NH.LIST_URL));
      wrap.appendChild(sub);
      // 置き場所: アダプターが指定する要素 (話の下の「前へ / 次へ」) の直後 = おすすめより上。無ければページの最後
      const at = adapter.endCardAfter && adapter.endCardAfter();
      if (at) { wrap.classList.add('inline'); at.insertAdjacentElement('afterend', wrap); }
      else (document.body || document.documentElement).appendChild(wrap);
    }
  }
})();
