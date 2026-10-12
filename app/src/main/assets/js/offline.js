// オフライン保存 (サイトのページ上で動く): 目次を読み、各話の本文を取り込んで、端末に保存する。
// 画面 (確認・進み具合) は ui.js が出す。ここは取り込みと保存だけ。
(function () {
  if (!window.NH || NH.offlinePlan) return;

  // 本文の要素 (前書き・本文・後書きなど) を文書の順に、<hr> で区切って 1 つの HTML にする (安全な要素だけ。NH.cleanNode は core.js)
  NH.cleanBody = (doc, adapter) =>
    [...doc.querySelectorAll(adapter.bodySel)].map(NH.cleanNode).filter((h) => h.replace(/<[^>]*>|\s/g, '')).join('<hr>');

  // 目次のリンクから話の一覧を作る (作品の話ページへのリンクだけを、出てきた順に)。アダプターが listEpisodes を持っていればそちら
  NH.linkEpisodes = function (adapter, w, doc) {
    const seen = new Set();
    const out = [];
    doc.querySelectorAll('a[href]').forEach((a) => {
      const x = adapter.parseWork(a.href);
      if (!x || x.id !== w.id || x.episode == null) return;
      const ep = String(x.episode);
      if (seen.has(ep)) return;
      seen.add(ep);
      // ハーメルンは投稿日時 (span.date) がリンクの中にあるので、除いてからタイトルにする
      const t = a.cloneNode(true);
      t.querySelectorAll('.date, time').forEach((e) => e.remove());
      out.push({ ep, url: a.href.split('#')[0], title: t.textContent.replace(/\s+/g, ' ').trim() });
    });
    return out;
  };

  // 保存の計画: 話の一覧 (eps) から、保存済みの話を除いた「これから保存する話」を求める。
  // total は作品の全話数 (目次の全部)。キューからは選んだ話だけが来るので、そのときは total を別に渡す
  NH.offlinePlanFrom = function (adapter, w, eps, title, total, images) {
    const meta = NH.off.meta(adapter.id, w.id);
    const saved = new Map(((meta && meta.eps) || []).map((e) => [e.ep, e]));
    return {
      title: title || (meta && meta.title) || w.id,
      workUrl: w.url,
      eps,
      total: total || eps.length,
      images: images !== false, // 挿絵も保存する (既定)
      saved,
      todo: eps.filter((e) => !saved.has(e.ep)),
      meta,
    };
  };

  // 保存の計画: 目次を読み、保存済みの話を除いた「これから保存する話」を求める。今開いている目次ページの上で呼ぶ
  NH.offlinePlan = async function (adapter, w) {
    let eps = adapter.listEpisodes ? await adapter.listEpisodes(w) : NH.linkEpisodes(adapter, w, document);
    // 目次の無い作品 (短編): いま開いているページに本文があれば、それを 1 話として扱う
    if (!eps.length && NH.cleanBody(document, adapter)) {
      eps = [{ ep: '0', url: location.href.split('#')[0], title: adapter.workTitle ? adapter.workTitle() : w.id, doc: document }];
    }
    return NH.offlinePlanFrom(adapter, w, eps, adapter.workTitle && adapter.workTitle());
  };

  // 挿絵 1 枚を、アプリの中から取って端末に保存する (サイトのページからは、画像の置き場所の CORS で取れないため)。
  // 保存できたら {name, bytes}、取れなかったら null。確認画面 (403 / 429 / 503) は話と同じく NH.CHALLENGE で止める
  function fetchImage(site, id, url) {
    const got = NH.off.saveImage(site, id, url, location.href);
    if (got.error === 'challenge') throw new Error(NH.CHALLENGE);
    return got.name ? got : null;
  }

  // 1 話を取り込む (まだ保存しない)。見積もりの見本にも使うので、取り込んだ内容は e.rec に残す
  // withImages = true なら挿絵も端末に保存する。挿絵なしで取り込んだ結果は、挿絵ありとして使い回さない (作り直す)
  NH.offlineGet = async function (adapter, w, e, withImages) {
    const want = !!withImages;
    if (e.rec && !!e.rec.withImages === want) return e.rec;
    const doc = e.doc || (await NH.fetchDoc(e.url));
    const imgs = [...doc.querySelectorAll(adapter.bodySel)].flatMap((b) => [...b.querySelectorAll('img')]);
    let imgBytes = 0, saved = 0;
    if (want) {
      const seen = new Map(); // 同じ話の中で同じ挿絵は 1 回だけ取る
      const counted = new Set(); // 容量と件数は、保存した挿絵 1 枚ごとに 1 回だけ数える
      for (const img of imgs) {
        const src = img.getAttribute('src') || img.getAttribute('data-src') || '';
        let url;
        try { url = new URL(src, e.url).href; } catch (x) { continue; }
        if (!/^https?:\/\//.test(url)) continue;
        if (!seen.has(url)) seen.set(url, fetchImage(adapter.id, w.id, url));
        const got = seen.get(url);
        if (!got) continue;
        img.setAttribute('data-nh-local', 'img/' + got.name);
        if (!counted.has(url)) { counted.add(url); imgBytes += got.bytes; saved++; }
      }
    }
    const html = NH.cleanBody(doc, adapter);
    if (!html) throw new Error('本文が見つかりません');
    e.rec = { ep: e.ep, title: e.title, url: e.url, html, at: Date.now(), withImages: want, imgCount: imgs.length, imgSaved: saved };
    e.bytes = new Blob([JSON.stringify(e.rec)]).size + imgBytes;
    return e.rec;
  };

  // 取り込んだ 1 話を端末に保存する
  NH.offlineStore = function (adapter, w, plan, e) {
    if (!NH.off.putEpisode(adapter.id, w.id, e.ep, e.rec)) throw new Error('保存できません');
    plan.saved.set(e.ep, { ep: e.ep, title: e.title, url: e.url });
    delete e.rec; // 保存したぶんはメモリから手放す
  };

  // 選んだ話を 1 話ずつ取り込んで保存する (画面は持たない。進み具合は hooks で知らせる)。
  // hooks: { shouldStop(): bool, wait(): Promise (一時停止のあいだ待つ), onEpisode(e, state) } 。戻り値の state: {ok, fails, failedTotal, bytes, blocked, lastErr}
  // 止められたら (確認画面・続けて 3 回の失敗・中止) そこで終わる。保存済みの話は次に飛ばすので、開き直せば続きから。
  NH.offlineRun = async function (adapter, w, plan, chosen, hooks = {}) {
    const st = { ok: 0, fails: 0, failedTotal: 0, bytes: 0, blocked: false, lastErr: '' };
    const stopped = () => !!(hooks.shouldStop && hooks.shouldStop());
    for (let i = 0; i < chosen.length && !stopped(); i++) {
      if (hooks.wait) await hooks.wait(); // 一時停止・Wi-Fi 待ちの間はここで止まる
      if (stopped()) break;
      const e = chosen[i];
      const net = !e.rec; // 見本の 1 話はもう取り込んである (通信しない)
      try {
        await NH.offlineGet(adapter, w, e, plan.images);
        st.bytes += e.bytes;
        NH.offlineStore(adapter, w, plan, e);
        st.ok++;
        st.fails = 0;
      } catch (err) {
        st.lastErr = (err && err.message) || '';
        if (st.lastErr === NH.CHALLENGE) { st.blocked = true; NH.markRest(adapter.id); break; }
        st.failedTotal++;
        if (++st.fails >= 3) break; // 続けて失敗するなら、ログイン切れなどなのでやめる
      }
      if (hooks.onEpisode) hooks.onEpisode(e, st);
      if (st.ok % 5 === 0) NH.offlineCommit(adapter, w, plan);
      if (net && i < chosen.length - 1 && !stopped()) await NH.pause();
    }
    NH.offlineCommit(adapter, w, plan);
    return st;
  };

  // 保存した話の並びと作品の情報 (meta) を書く。目次の順に並べ、目次から消えた話は最後に残す
  NH.offlineCommit = function (adapter, w, plan) {
    const order = plan.eps.filter((e) => plan.saved.has(e.ep)).map((e) => plan.saved.get(e.ep));
    const known = new Set(order.map((e) => e.ep));
    plan.saved.forEach((e) => { if (!known.has(e.ep)) order.push(e); });
    const now = Date.now();
    return NH.off.putMeta(adapter.id, w.id, NH.withSub({
      site: adapter.id, id: w.id, title: plan.title, url: plan.workUrl,
      savedAt: (plan.meta && plan.meta.savedAt) || now, updatedAt: now, total: plan.total, eps: order,
      ...(plan.meta && plan.meta.sub ? { sub: plan.meta.sub } : {}),
    }));
  };
})();
