// 保存キューの 1 作品を、画面に出さない WebView で保存する (前景サービス OfflineService が使う)。
// Kotlin が window.NH_BG_JOB = {site, id, title, url, total, eps:[{ep,url,title}]} を置いてから、このファイルが動く。
// 端末への読み書き・止める・待つ・進みの知らせは、すべて Kotlin の窓口 (NHOffline / NHStore) に任せる。
(function () {
  // 窓口の合言葉 (OfflineService が入れる)。読んだらページから消す (core.js の __nhT と同じ。iframe には無い)
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
    const plan = NH.offlinePlanFrom(adapter, w, job.eps, job.title, job.total, job.images);
    const st = await NH.offlineRun(adapter, w, plan, plan.todo, {
      shouldStop: () => native.stopRequested(T),
      offline: () => !!(native.offline && native.offline(T)),
      wait: async () => { while (native.waiting(T) && !native.stopRequested(T)) await NH.sleep(2000); },
      onEpisode: (e, s) => native.progress(JSON.stringify({ ok: s.ok, total: plan.todo.length, title: e.title }), T),
    });
    native.finished(JSON.stringify({
      ok: st.ok, failed: st.failedTotal, fails: st.fails, blocked: st.blocked, lastErr: st.lastErr,
      stopped: native.stopRequested(T), total: plan.todo.length, bytes: st.bytes,
    }), T);
  };

  NH.offlineBgRun().catch((e) => native.finished(JSON.stringify({ error: String((e && e.message) || e) }), T));
})();
