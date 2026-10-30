// オフライン保存の画面 (作品のページを #nh-save 付きで開いたとき。ui.js が呼ぶ)。関数を定義するだけで、読み込んだだけでは何もしない。
// ctx = ui.js の共通の部品 {adapter, challenge (確認画面か), mk, dropCover, askDialog}、w = 作品 (NH.parseWork)。見た目の CSS は ui.js の共通スタイル (.nh-save)
// ---- オフライン保存 (リスト / オフライン画面が、作品のページを #nh-save 付きで開く) ----
// 目次を読む → 見本の 1 話から容量を見積もる → 保存する話を選ぶ (すべて / 範囲 / えらぶ) → 間をあけて 1 話ずつ保存する。
// 保存済みの話は飛ばすので、あとから開き直すと続きや新しい話だけが保存される。
// サイトへのアクセスのマナーは巡回と同じ: 1.5〜3 秒あけ、止められたら (403 / 429 / 503 / 確認画面) そこでやめて休止する。
window.NH_UI_SAVE = function (ctx, w) {
  const { adapter, challenge, mk, dropCover, askDialog } = ctx;
  dropCover();
  const key = NH.workKey(w);
  const ov = mk('div', 'nh-ov');
  const body = mk('div', 'nh-save');
  ov.appendChild(body);
  (document.body || document.documentElement).appendChild(ov);
  // 戻る: 前のページへ。前が同じ作品のページ (⋮ の保存で #nh-save を付けただけ) なら、ハッシュが外れるだけで画面が残るので、読み込み直す
  const back = () => {
    if (history.length <= 1) return location.replace(NH.LIST_URL);
    window.addEventListener('hashchange', () => { if (location.hash !== '#nh-save') location.reload(); }, { once: true });
    history.back();
  };
  const open = () => location.replace(NH.OFFLINE_URL + '?w=' + encodeURIComponent(key));
  // 画面を作り直す。buttons: [[文字, 押したとき, 'sub' (控えめ)]]
  const screen = (title, lines, buttons, spin) => {
    body.textContent = '';
    if (spin === 'ok') { const ok = mk('div', 'ok'); ok.innerHTML = '<svg viewBox="0 0 52 52"><circle cx="26" cy="26" r="23"/><path d="M15 27l8 8 14-16"/></svg>'; body.appendChild(ok); } // 済んだ画面: 丸とチェックが描かれる
    else if (spin) body.appendChild(mk('div', 'spin'));
    body.appendChild(mk('h3', '', title));
    lines.forEach((l) => body.appendChild(typeof l === 'string' ? mk('p', '', l) : l));
    if (buttons && buttons.length) {
      const row = mk('div', 'btns');
      buttons.forEach(([t, fn, cls]) => { const b = mk('button', cls || '', t); b.onclick = fn; row.appendChild(b); });
      body.appendChild(row);
    }
  };
  const fail = (title, text) => screen(title, [text], [['戻る', back]]);

  // 保存する話を選ぶ画面。選んだ話の配列 (やめたら null) で解決する
  const choose = (plan, avg) => new Promise((resolve) => {
    const st = { mode: 'all', from: 1, to: plan.eps.length, picked: new Set(plan.todo.map((e) => e.ep)), images: true };
    const selected = () => NH.selectEpisodes(plan, st);
    const summary = mk('p', 'sum');
    // 挿絵を含めるか (既定は含める)。見本の 1 話に挿絵が無ければ、行は出さない
    const imageRow = () => {
      const n = (plan.todo[0].rec || {}).imgCount || 0;
      if (!n) return mk('div');
      const row = mk('div', 'seg');
      const draw2 = () => {
        row.textContent = '';
        [[true, '挿絵も保存'], [false, '文字だけ']].forEach(([v, t]) => {
          const b = mk('button', st.images === v ? 'on' : '', t);
          b.onclick = () => { st.images = v; draw2(); };
          row.appendChild(b);
        });
      };
      draw2();
      const wrap = mk('div');
      wrap.append(row, mk('p', 'hint', '見本の 1 話に挿絵が ' + n + ' 枚あります (' + (st.images ? '端末に保存します' : '挿絵は保存しません') + ')'));
      return wrap;
    };
    const go = mk('button', '', '保存する');
    const refresh = () => {
      const n = selected().length;
      summary.textContent = n ? n + ' 話 ・ 推定 約 ' + NH.fmtBytes(n * avg) + ' (見本の 1 話から計算)'
        : st.mode === 'range' && st.from > st.to ? '「から」の話が「まで」より後になっています'
        : '保存する話がありません';
      go.disabled = !n;
      go.style.opacity = n ? '' : '.4';
    };
    const seg = mk('div', 'seg');
    seg.hidden = plan.todo.length < 2; // 保存する話が 1 つだけなら、選び方は要らない
    const modes = [['all', 'すべて'], ['range', '範囲'], ['pick', 'えらぶ']];
    const panel = mk('div', 'panel');
    const draw = () => {
      seg.textContent = '';
      modes.forEach(([m, t]) => {
        const b = mk('button', st.mode === m ? 'on' : '', t);
        b.onclick = () => { st.mode = m; draw(); };
        seg.appendChild(b);
      });
      panel.textContent = '';
      if (st.mode === 'range') {
        const num = (k) => {
          const i = mk('input');
          i.type = 'number'; i.min = 1; i.max = plan.eps.length; i.value = st[k]; i.inputMode = 'numeric';
          i.oninput = () => { st[k] = Math.max(1, Math.min(plan.eps.length, Number(i.value) || 1)); refresh(); };
          return i;
        };
        const row = mk('div', 'range');
        row.append(num('from'), mk('span', '', '話目 から'), num('to'), mk('span', '', '話目 まで'));
        panel.append(row, mk('p', '', '目次の上から数えた順番です (保存済みの話は飛ばします)'));
      } else if (st.mode === 'pick') {
        const tools = mk('div', 'btns');
        [['すべて選ぶ', () => plan.todo.forEach((e) => st.picked.add(e.ep))], ['すべて外す', () => st.picked.clear()]].forEach(([t, fn]) => {
          const b = mk('button', 'sub small', t);
          b.onclick = () => { fn(); draw(); };
          tools.appendChild(b);
        });
        const box = mk('div', 'pick');
        plan.eps.forEach((e, i) => {
          const lab = mk('label');
          const c = mk('input');
          c.type = 'checkbox';
          const have = plan.saved.has(e.ep);
          c.checked = have || st.picked.has(e.ep);
          c.disabled = have;
          c.onchange = () => { if (c.checked) st.picked.add(e.ep); else st.picked.delete(e.ep); refresh(); };
          const t = (e.title || '').trim();
          // 題名が番号そのもの (「1」「１」…) の作品は、番号だけにする (「1. 1」と重ねない)
          const name = !t ? '(題名なし)' : t.normalize('NFKC') === String(i + 1) ? '' : t;
          lab.append(c, mk('span', '', (i + 1) + '話目' + (name ? '　' + name : '') + (have ? ' (保存済み)' : '')));
          box.appendChild(lab);
        });
        panel.append(tools, box);
      }
      refresh();
    };
    go.onclick = () => resolve({ list: selected(), images: st.images });
    const cancel = mk('button', 'sub', 'やめる');
    cancel.onclick = () => resolve(null);
    screen('オフライン保存', [
      mk('p', 'wt', plan.title),
      '全 ' + plan.eps.length + ' 話 ・ 保存済み ' + plan.saved.size + ' 話 ・ 未保存 ' + plan.todo.length + ' 話',
      seg, panel, summary, imageRow(),
    ], []);
    const row = mk('div', 'btns');
    row.append(go, cancel);
    body.appendChild(row);
    draw();
  });

  (async () => {
    if (!NH.off.available()) return fail('保存できません', 'この画面ではオフライン保存を使えません');
    const d0 = NH.load();
    if (NH.isResting(d0, adapter.id, false)) return fail('少し待ってください', 'このサイトにアクセスを止められたばかりです。しばらくしてからもう一度お試しください');
    if (challenge) { NH.markRest(adapter.id); return fail('確認画面が出ています', 'サイトの確認画面を通してから、もう一度お試しください'); }
    NH.update((d) => { if (d.rest) delete d.rest[adapter.id]; }); // ここまで普通に開けたので、止められてはいない

    screen('目次を読んでいます', [], [], true);
    let plan;
    try {
      plan = await NH.offlinePlan(adapter, w);
    } catch (e) {
      if (e && e.message === NH.CHALLENGE) NH.markRest(adapter.id);
      return fail('目次を読めませんでした', e && e.message === NH.CHALLENGE ? 'アクセス制限が出たので止めました。しばらくしてからお試しください' : 'ログインが必要な作品か、ページの形が想定と違います');
    }
    if (!plan.eps.length) return fail('目次を読めませんでした', 'ログインが必要な作品か、ページの形が想定と違います');
    if (!plan.todo.length) return screen('すべて保存済みです', ['全 ' + plan.eps.length + ' 話を保存しています'], [['開く', open], ['戻る', back, 'sub']], 'ok');

    screen('容量を見積もっています', [plan.title], [], true);
    try {
      await NH.offlineGet(adapter, w, plan.todo[0], false); // 見本は文字だけ (挿絵の数だけ数える)
    } catch (e) {
      if (e && e.message === NH.CHALLENGE) NH.markRest(adapter.id);
      return fail('話を取り込めませんでした', e && e.message === NH.CHALLENGE ? 'アクセス制限が出たので止めました。しばらくしてからお試しください' : (e && e.message) || '');
    }
    const picked = await choose(plan, plan.todo[0].bytes);
    if (!picked) return back();
    const chosen = picked.list;
    plan.images = picked.images;

    // 「Wi-Fi のときだけ」の設定: 従量課金の回線なら、キューに入れる前に聞く。待つ場合は、サービスが Wi-Fi になるまで待つ
    let mobileOk = false;
    if (NH.flag(d0, 'offlineWifiOnly', false) && !NH.off.onUnmetered()) {
      const pick = await askDialog('Wi-Fi につながっていません', '「Wi-Fi のときだけ保存する」がオンです。', [['あとで (Wi-Fi になってから)', 'wait'], ['今すぐ (モバイル回線で)', 'mobile'], ['キャンセル', 'cancel']]);
      if (!pick || pick === 'cancel') return back();
      mobileOk = pick === 'mobile';
    }

    // 保存キューに入れて、端末の前景サービスで保存する (画面を離れても、別のアプリに移っても続く)。進みは通知とリストの帯で出す
    const added = NH.off.queueAdd({
      site: adapter.id, id: w.id, title: plan.title, url: plan.workUrl, total: plan.total,
      eps: chosen.map((e) => ({ ep: e.ep, url: e.url, title: e.title })),
      mobileOk, images: picked.images, status: 'WAITING', note: '', addedAt: Date.now(),
    });
    if (added) NH.off.queueStart();
    screen(added ? 'キューに入れました' : '保存キューに入れられませんでした', [
      mk('p', 'wt', plan.title),
      added ? added + ' 話を保存キューに入れました。' + (mobileOk || !NH.flag(d0, 'offlineWifiOnly', false) || NH.off.onUnmetered() ? '画面を離れても続きます' : 'Wi-Fi につながると始まります') : 'すでに保存キューにあるか、この作品は保存できません',
    ], [['保存キューを開く', () => location.replace(NH.QUEUE_URL)], ['戻る', back, 'sub']], added ? 'ok' : false);
  })();
};
