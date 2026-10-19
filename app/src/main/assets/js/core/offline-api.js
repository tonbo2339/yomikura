// Yomikura 共通基盤 (10): オフライン保存の窓口 (NHOffline) の呼び出しと、保存まわりの小さな道具
window.NH.define('offline-api', function (NH, T) { // T = オフライン保存の窓口に添える合言葉
  // ---- オフライン保存 (本体は Kotlin の OfflineStore。端末の内部ストレージに作品ごとのフォルダで置く) ----
  //   meta:    {site, id, title, url, savedAt, updatedAt, total, eps: [{ep, title, url}]}  保存した話の並び
  //   episode: {ep, title, url, html, at}  本文は安全な要素 (p, br, ruby など) だけにしてある
  // 書き込み (putEpisode / putMeta) は保存を進めるサイト上から、読み出し・一覧・削除はアプリ内ページからだけ使える。
  const json = (fn, fallback) => { try { const r = fn(); return r ? JSON.parse(r) : fallback; } catch (e) { return fallback; } };
  const off = () => window.NHOffline || null;
  NH.off = {
    available: () => !!off(),
    // 挿絵を端末に取って保存する (アプリが取る)。結果 {name, bytes} / {error: 'challenge'} / {} (取れなかった)
    saveImage: (site, id, url, referer) => json(() => off() && off().saveImage(site, id, url, referer, T), {}),
    putEpisode: (site, id, ep, obj) => !!off() && off().putEpisode(site, id, String(ep), JSON.stringify(obj), T),
    putMeta: (site, id, obj) => !!off() && off().putMeta(site, id, JSON.stringify(obj), T),
    meta: (site, id) => json(() => off() && off().meta(site, id, T), null),
    episode: (site, id, ep) => json(() => off() && off().episode(site, id, String(ep)), null),
    list: () => json(() => off() && off().list(), []),
    readerBar: (hide) => { try { off() && off().readerBar(!!hide); } catch (e) {} }, // 読書画面で上部バーを隠す / 出す
    remove: (site, id) => !!off() && off().delete(site, id),
    removeAll: () => !!off() && off().deleteAll(),
    onUnmetered: () => { try { return !off() || off().onUnmetered(); } catch (e) { return true; } },
    // 保存キュー (端末の待ち行列)。追加は保存を進めるサイト上から、中身の読み出し・並べ替え・削除はアプリ内ページからだけ
    queueAdd: (job) => (off() && off().queueAdd(JSON.stringify(job), T)) || 0,
    queueList: () => json(() => off() && off().queueList(), []),
    queueMove: (key, delta) => !!off() && off().queueMove(key, delta),
    queueRemove: (key) => !!off() && off().queueRemove(key),
    queueClearFinished: () => !!off() && off().queueClearFinished(),
    queueLive: () => json(() => off() && off().queueLive(), {}), // 保存中の作品の進み {running, key, title, ok, total, paused, waitingWifi}
    queueControl: (action) => !!off() && off().queueControl(action), // 'pause' | 'resume' | 'cancel'
    queueRetry: (key) => !!off() && off().queueRetry(key), // 止まった・確認が必要だった作品を、もう一度始める
    queueStart: () => !!off() && off().queueStart(T), // 前景サービスで保存キューを進める (作品のページから)
  };

  // 「後で読む」の作品のキー (site:id) から、保存のキーへ
  NH.splitKey = (key) => { const i = String(key).indexOf(':'); return { site: key.slice(0, i), id: key.slice(i + 1) }; };
  NH.fmtBytes = (n) => (n >= 1048576 ? (n / 1048576).toFixed(1) + ' MB' : n > 0 ? Math.max(1, Math.round(n / 1024)) + ' KB' : '0 KB');
  NH.OFFLINE_URL = 'https://yomikura.local/offline.html';
  NH.QUEUE_URL = 'https://yomikura.local/queue.html'; // 保存キューの画面
  // 保存キューの作品が、まだ終わっていない (順番に進める) か。Kotlin の SaveQueue.Status.active と同じ
  NH.queueActive = (j) => ['WAITING', 'RUNNING', 'WAIT_WIFI', 'PAUSED'].includes(j.status);

  // オフライン保存で選んだ話。st = {mode: 'all' | 'range' | 'pick', from, to (目次の上から数えた 1 始まり), picked: Set(ep)}、plan = {eps, todo}
  NH.selectEpisodes = function (plan, st) {
    if (st.mode === 'range') {
      const idx = new Map(plan.eps.map((e, i) => [e.ep, i + 1]));
      return plan.todo.filter((e) => idx.get(e.ep) >= st.from && idx.get(e.ep) <= st.to);
    }
    if (st.mode === 'pick') return plan.todo.filter((e) => st.picked.has(e.ep));
    return plan.todo;
  };
});
