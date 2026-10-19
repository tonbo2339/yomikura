// カクヨム アダプター
(function () {
  // 作品トップ: /works/ID  話ページ: /works/ID/episodes/EID (EID は連番ではない)
  const WORK_RE = /^\/works\/(\d+)(?:\/episodes\/(\d+))?\/?$/;

  // 作品ページの埋め込みデータ (章 → 話の並び) から、話の一覧を順番どおりに読む。読めなければ空
  function tocOf(doc, workId) {
    const out = [];
    try {
      const st = JSON.parse(doc.getElementById('__NEXT_DATA__').textContent).props.pageProps.__APOLLO_STATE__;
      ((st['Work:' + workId] || {}).tableOfContentsV2 || []).forEach((r) => {
        ((st[r.__ref] || {}).episodeUnions || []).forEach((u) => {
          const e = st[u.__ref];
          if (e && e.id) out.push({ ep: String(e.id), url: location.origin + '/works/' + workId + '/episodes/' + e.id, title: e.title || '' });
        });
      });
    } catch (e) { /* 読めなければ空 */ }
    return out;
  }

  NH.register({
    id: 'kakuyomu',
    name: 'カクヨム',
    match: (host) => host === 'kakuyomu.jp',
    collectUrl: 'https://kakuyomu.jp/my/antenna/works',
    onCollectPage: (loc) => loc.pathname.startsWith('/my/antenna/works'),
    bodySel: '.widget-episodeBody',
    topToc: true, // 話ページの一番上に「目次へ」(作品のページ) を置く。作品へのパンくずは sites.js で隠しているため
    spa: true, // 話から話へページを読み込み直さずに移る (ui.js が作品 / 話の変化を見張る)
    // 話番号が連番でないので、次の話は「次のエピソード」リンクから取る (最終話には無い)
    nextUrl() {
      const a = document.querySelector('#contentMain-readNextEpisode a, a#contentMain-readNextEpisode');
      return a ? a.href : null;
    },

    // 目次 (オフライン保存用): ページの HTML には最初の数話しかリンクが無いので、埋め込みデータ (章 → 話の並び) から読む
    async listEpisodes(w) {
      const out = tocOf(document, w.id);
      return out.length ? out : NH.linkEpisodes(this, w, document);
    },

    // 話ページで「何話目か」(読書位置の「第N話」用)。話 ID は連番でないので、作品ページの目次の並びで数える。
    // 目次の ID の並びは、この origin の localStorage に控える (読み進める間はサイトへ通信しない)。
    // 控えに今の話が無いとき (新しい話・初めての作品) だけ作品ページを取り直す。取り直しは 1 作品 1 日 1 回まで。分からなければ null
    async episodeNo(w) {
      const key = 'nh-toc:' + w.id, id = String(w.episode), DAY = 86400000;
      const read = () => { try { const v = JSON.parse(localStorage.getItem(key)); return v && Array.isArray(v.ids) ? v : null; } catch (e) { return null; } };
      const noOf = (ids) => { const i = ids.indexOf(id); return i >= 0 ? i + 1 : null; };
      const c = read();
      const hit = c && noOf(c.ids);
      if (hit) return hit;
      if (c && Date.now() - c.at < DAY) return null; // さっき取り直したが、今の話は目次に無い (非公開の話など)。続けて取りに行かない
      const doc = await NH.fetchListDoc(location.origin + '/works/' + w.id, 1); // 間をあけてから取る。確認画面などは例外 (呼び出し側が休止する)
      const ids = tocOf(doc, w.id).map((e) => e.ep);
      if (!ids.length) return null;
      try { localStorage.setItem(key, JSON.stringify({ at: Date.now(), ids })); } catch (e) { /* 控えられなくても番号は返す */ }
      return noOf(ids);
    },

    // 「読了 → 次の小説へ」を置く場所: 話の下の応援ボタンの直下 (作品情報・おすすめより上)
    endCardAfter: () => document.querySelector('#episodeFooter-cheerSection'),

    // ランキング / 検索結果の 1 作品の囲みから作者名を取る (ミュート用)
    authorOf(box) {
      const a = box.querySelector('a[href^="/users/"]');
      return a ? a.textContent.trim() : '';
    },

    // 連載状態 (絞り込み用): 「連載中 38話」「完結済 12話」の表示から。短編は長編/短編の切り替え (work_variation) で分かれる
    statusOf(box) {
      const m = box.innerText.match(/(連載中|完結済)\s*\d+話/);
      return m ? (m[1] === '連載中' ? 'serial' : 'complete') : '';
    },

    // ページの下の畳む欄 (セレクターは sites.js)
    foldGroups: ((window.NH_SITES || {}).kakuyomu || {}).fold,

    // 作品ページの連載状態: 「連載中全40話」「完結済全12話」の表示 (クラス名はハッシュ付きなので、前方一致で探して文字で見る)
    pageStatus() {
      const t = [...document.querySelectorAll('[class*="Meta_metaItem"]')].map((e) => e.textContent.trim()).find((s) => /^(連載中|完結済)/.test(s));
      return t ? (t.startsWith('連載中') ? 'serial' : 'complete') : '';
    },

    // 作品の全話数: 作品ページの情報欄「連載中 全44話」(ページ内だけで分かる)
    workInfoOnPage: true, // workInfo は今開いているページから読む (作品ページだけで呼ぶ。話ページでは読めず、1 日の読み直し待ちだけ付いてしまう)
    workInfo() {
      const t = [...document.querySelectorAll('[class*="Meta_metaItem"]')].map((e) => e.textContent).find((s) => /全\d+話/.test(s));
      return { total: t ? Number(t.match(/全(\d+)話/)[1]) : null, status: this.pageStatus(), sub: '' }; // 連載状態は同じ情報欄の「連載中」「完結済」から
    },

    // 作品名: 話ページはパンくず、作品ページは見出し。無ければタイトル「話 - 作品（作者） - カクヨム」から取る
    workTitle() {
      const a = document.querySelector('#worksEpisodesEpisodeHeader-breadcrumbs a') || document.querySelector('h1 a[href^="/works/"]');
      const t = a && a.textContent.trim();
      if (t) return t;
      // 作品のページ名は「作品名（作者） - カクヨム」。この形でなければ (「お探しのページは…」などのエラー) 作品名とみなさない
      const m = document.title.match(/^(?:.* - )?(.+)（[^）]*） - カクヨム$/);
      return m ? m[1].trim() : '';
    },

    // 公式の閲覧履歴 (/my/antenna/reading_histories)
    isHistoryPage: (loc) => loc.pathname.startsWith('/my/antenna/reading_histories'),
    parseHistory(doc) {
      return [...doc.querySelectorAll('li.js-reading-history')].map((li) => {
        const a = li.querySelector('a.widget-antennaList-workInfo');
        const w = a && this.parseWork(a.getAttribute('href'));
        if (!w) return null;
        const t = li.querySelector('.widget-antennaList-title');
        const tot = [...li.querySelectorAll('.widget-antennaList-event li')].map((e) => e.textContent.match(/(\d+)話/)).find(Boolean);
        return { id: w.id, title: t ? t.textContent.trim() : '', url: location.origin + '/works/' + w.id + '/resume_reading', total: tot ? Number(tot[1]) : null, anchor: t || a };
      }).filter(Boolean);
    },

    parseWork(url) {
      const u = NH.parseUrl(url);
      if (!u) return null;
      if (u.hostname !== 'kakuyomu.jp') return null;
      const m = u.pathname.match(WORK_RE);
      if (!m) return null;
      return { id: m[1], url: u.origin + '/works/' + m[1], episode: m[2] || null };
    },

    // フォロー中の小説 (未読あり) から集める。同一 origin (kakuyomu.jp) 上で実行する。
    // 次の未読話は /works/ID/resume_reading (サイトが続きの話へ飛ばしてくれる)
    async collectUpdates() {
      const items = [];
      let sawList = false;
      let truncated = false; // ページの上限で打ち切った (続きのページがある)
      const readIds = []; // 見たページにあった、未読の無い作品 (打ち切ったときも、これらは読了にしてよい)
      for (let page = 1; page <= 10; page++) {
        const doc = await NH.fetchListDoc(page === 1 ? this.collectUrl : this.collectUrl + '?page=' + page, page - 1);
        if (doc.querySelector('.widget-antennaList-item') || doc.querySelector('.widget-antennaList')) sawList = true;
        const lis = [...doc.querySelectorAll('li.widget-antennaList-item[data-is-unread="1"]')];
        for (const li of lis) {
          const a = li.querySelector('a.widget-antennaList-workInfo');
          const w = a && this.parseWork(a.getAttribute('href'));
          if (!w) continue;
          const t = li.querySelector('.widget-antennaList-title');
          const unread = Number(((li.querySelector('.widget-antennaList-unreadEpisodeCount b') || {}).textContent) || 1);
          const tot = [...li.querySelectorAll('.widget-antennaList-event li')].map((e) => e.textContent.match(/(\d+)話/)).find((m) => m && !/未読/.test(m.input));
          items.push({ id: w.id, title: t ? t.textContent.trim() : '', url: location.origin + '/works/' + w.id + '/resume_reading', unread, lastEp: tot ? Number(tot[1]) : null });
        }
        doc.querySelectorAll('li.widget-antennaList-item:not([data-is-unread="1"]) a.widget-antennaList-workInfo').forEach((a) => {
          const w = this.parseWork(a.getAttribute('href'));
          if (w) readIds.push(w.id);
        });
        const more = !!doc.querySelector('a[href*="page=' + (page + 1) + '"]');
        // 未読の行が無いページで止める (後ろのページまで取りに行くと、サイトへのアクセスが増えるため)。
        // まだ続きのページがあるときは、見ていない作品を「今回の取得に無い」として読了にしないよう truncated にする
        if (!lis.length || !more) { if (more) truncated = true; break; }
        if (page === 10) truncated = true;
      }
      return { ok: sawList, items, truncated, readIds };
    },

    // ランキング・検索結果: 作品タイトルの見出しリンク (クラス名はハッシュ付きなので見出し要素で拾う)
    rankingItems() {
      const seen = new Set();
      return [...document.querySelectorAll('h2 a[href^="/works/"],h3 a[href^="/works/"],h4 a[href^="/works/"]')]
        .filter((a) => /^\/works\/\d+$/.test(a.getAttribute('href')) && a.textContent.trim())
        .filter((a) => !a.closest('#episodeFooter-work')) // 話ページの下にある、今読んでいる作品自身の情報欄は除く
        .filter((a) => !seen.has(a.pathname) && seen.add(a.pathname))
        // ボタンは見出し (h2/h3/h4) の直後に置く (見出しの中だと作者名の横に並んでしまう)
        .map((a) => ({ title: a.textContent.trim(), href: a.href, anchor: a, after: a.closest('h2,h3,h4') }));
    },
  });
})();
