// 更新の巡回取得 (サイト上で動く)。画面に出さない WebView (Collector.kt) が、なろうのブックマークから順に各サイトの一覧を開く。
// サイトごとに、そのサイト上で取得して取り込み (設定で非表示のサイトは飛ばす)、終わったら結果を NHStore.bgDone で返す。
//   #nh-collect-bg = 裏での確認 (BgCheckJob)、#nh-collect-fg = アプリの「更新」ボタン、#nh-collect-fg-auto = 起動時の自動確認
// ctx = ui.js の共通の部品 {adapter, challenge (確認画面か)}。巡回のページなら処理して true、そうでなければ false を返す (true のとき ui.js は何も出さない)。
window.NH_UI_COLLECT = function (ctx) {
  const { adapter, challenge } = ctx;
  const SEQ = NH.COLLECT_ORDER; // 巡回の順 (core/collect-rules.js)
  const hashM = location.hash.match(/^#nh-collect-(bg|fg|fg-auto)$/);
  if (!(hashM && adapter.collectUpdates && adapter.onCollectPage && adapter.onCollectPage(location))) return false;
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
  // 確認画面で止まったこのページを画面に出して通してもらうとき、次のサイトからの巡回は別の WebView が続けている (Collector の split)。
  // その場合、この WebView は自分のサイトだけで終わり (次へ進まない)、結果もまっさらにしない (続きの WebView の結果を消さない)
  const split = !!(window.NHStore && NHStore.isSplit && NHStore.isSplit());
  const nextIdOf = () => SEQ.slice(SEQ.indexOf(adapter.id) + 1).find((id) => enabled.includes(id));
  const urlOf = (id) => NH.adapters.find((a) => a.id === id).collectUrl + '#nh-collect-' + mode;
  // 巡回は、休んでいない最初の対象サイトから始まる (SiteConfig.collectStartUrl。休んでいるサイトは開きもしない)。そのサイトが結果をまっさらにする
  if (adapter.id === enabled[0] && !split) {
    // この実行の中で止められて休止になったサイト (確認画面・アクセス制限。先のサイトが休止にしたあと、次のサイトのページがここへ来る) は、
    // その結果 (challenge など) を残す。実行の前から休んでいたサイトだけを rest と書く (実行の始まり = collectStartAt。CollectCooldown.markStarted)
    const prev = d0.collectLog || {};
    const started = d0.collectStartAt || 0;
    NH.update((d) => { d.collectLog = {}; });
    resting.forEach((id) => {
      const stoppedNow = prev[id] && ((d0.rest || {})[id] || 0) >= started;
      if (stoppedNow) NH.update((d) => { d.collectLog[id] = prev[id]; });
      else logErr(id, 'rest');
    });
  }

  // 巡回のページは履歴に残さない (replace)
  const go = (delay) => setTimeout(() => {
    const nextId = split ? null : nextIdOf();
    if (nextId) {
      location.replace(urlOf(nextId));
      return;
    }
    // lastCollectFg = アプリの画面で確認した時刻 (直後の裏での確認は通知しない)
    NH.update((d) => { d.lastCollect = Date.now(); if (!bg) d.lastCollectFg = d.lastCollect; });
    if (window.NHStore && NHStore.bgDone) NHStore.bgDone(JSON.stringify(NH.load().collectLog || {}));
  }, delay);

  if (!here) { go(0); return true; }
  // 確認の進み具合 (リスト画面の「1 / 4」): 対象のサイトの何番目か
  if (window.NHStore && NHStore.progress) NHStore.progress(String(enabled.indexOf(adapter.id) + 1), String(enabled.length));
  // 確認画面のままなら少し待つ (自然に通過すれば読み込み直されて、ここは消える)。まだ止まっているなら、このサイトは休止して、
  // アプリに知らせる。アプリが画面に出して通してもらうと答えた (hold) ら、この WebView はそのまま待ち (通れば読み込み直されて、続きを取得する)、
  // 次のサイトからはアプリが別の WebView で続ける。答えが無い (裏の確認など) ときは、ここで諦めて次へ進む
  if (challenge) {
    logErr(adapter.id, 'challenge');
    NH.markRest(adapter.id);
    setTimeout(() => {
      const nextId = nextIdOf();
      const hold = window.NHStore && NHStore.challenge && NHStore.challenge(adapter.id, nextId ? urlOf(nextId) : '') === 'hold';
      if (!hold) go(0);
    }, 15000);
    return true;
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
  return true;
};
