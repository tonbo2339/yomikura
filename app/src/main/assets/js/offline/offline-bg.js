// 保存キューの 1 作品を、画面に出さない WebView で保存する (前景サービス OfflineService が使う)。
// Kotlin が window.NH_BG_JOB = {site, id, title, url, total, eps:[{ep,url,title}]} を置いてから、このファイルが動く。
// 端末への読み書き・止める・待つ・進みの知らせは、すべて Kotlin の窓口 (NHOffline / NHStore) に任せる。
(function () {
  // 窓口の合言葉 (OfflineService が入れる)。読んだらページから消す (core/base.js の __nhT と同じ。iframe には無い)
  const T = window.__nhBgT || '';
  try { delete window.__nhBgT; } catch (e) { /* 消せなくても動く */ }
  if (!window.NH || !window.NH_BG_JOB || document.documentElement.dataset.nhBg) return;
  document.documentElement.dataset.nhBg = '1';
  const job = window.NH_BG_JOB;
  const native = window.NHOffline; // 窓口は OfflineService が用意する (保存・止める・待つ・進み・終わり)

  NH.offlineBgRun = async function () {
    const adapter = NH.adapter();
    if (!adapter) return native.finished(JSON.stringify({ error: 'サイトを判別できません' }), T);
    const w = { id: job.id, url: job.url };
    // 自動更新 (job.refresh): 話の一覧は渡されないので、この作品のページ (目次) を読んで、保存していない話を求める
    let plan;
    if (job.refresh) {
      // 目次の代わりに確認画面 (Cloudflare・年齢確認) が出ていたら、話が 0 件に見えて「何も無かった」で終わってしまう。止められたと知らせる
      const blocked = () => { NH.markRest && NH.markRest(adapter.id); return native.finished(JSON.stringify({ blocked: true, ok: 0, total: 0 }), T); };
      if (NH.isChallenge && NH.isChallenge(document)) return blocked();
      try { plan = await NH.offlinePlan(adapter, w); } catch (e) { if (e && e.message === NH.CHALLENGE) return blocked(); throw e; } // 目次の 2 ページ目以降で確認画面
      // 目次が読めない (作りが変わった・ログイン切れ) と、何も無かったことになって静かに終わる。止めたと知らせる
      if (!plan.eps.length) return native.finished(JSON.stringify({ error: '目次を読めませんでした' }), T);
      plan.images = job.images !== false;
    } else {
      plan = NH.offlinePlanFrom(adapter, w, job.eps, job.title, job.total, job.images);
    }
    const st = await NH.offlineRun(adapter, w, plan, plan.todo, {
      shouldStop: () => native.stopRequested(T),
      offline: () => !!(native.offline && native.offline(T)),
      // 一時停止・Wi-Fi 待ち・回線待ちのあいだ、確認の間隔を少しずつ延ばす (2 → 3 → 5 秒)。一晩待つあいだ、端末を 2 秒ごとに起こさない
      wait: async () => { for (let ms = 2000; native.waiting(T) && !native.stopRequested(T); ms = Math.min(5000, ms + 1000)) await NH.sleep(ms); },
      onEpisode: (e, s) => native.progress(JSON.stringify({ ok: s.ok, total: plan.todo.length, title: e.title }), T),
    });
    native.finished(JSON.stringify({
      ok: st.ok, failed: st.failedTotal, fails: st.fails, blocked: st.blocked, lastErr: st.lastErr,
      stopped: native.stopRequested(T), total: plan.todo.length, bytes: st.bytes,
    }), T);
  };

  NH.offlineBgRun().catch((e) => native.finished(JSON.stringify({ error: String((e && e.message) || e) }), T));
})();
