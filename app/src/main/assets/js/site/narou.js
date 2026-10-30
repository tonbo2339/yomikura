// なろう系アダプター: 小説家になろう (narou) と R18 (narou18: ノクターン / ムーンライト / ミッドナイト)
(function () {
  // 作品トップ: /n1234ab/  話ページ: /n1234ab/12/ (Nコードは n + 数字4桁 + 英字)
  const WORK_RE = /^\/(n\d{4}[a-z]+)(\/(\d+)?\/?)?$/;
  // R18 の作品ページは novel18、ランキングは noc / mnlt / mid
  const R18_HOSTS = ['novel18.syosetu.com', 'noc.syosetu.com', 'mnlt.syosetu.com', 'mid.syosetu.com'];
  const isR18 = (host, path) => R18_HOSTS.includes(host) || (host === 'syosetu.com' && /^\/favnovelmain18\//.test(path || ''));

  function make(v) {
    // ブックマークのカテゴリ名と件数を控える (設定で確認するカテゴリを選ぶときに出す)。
    // 名前は「移動」の選択欄 (10 個全部)、件数は表示を切り替える選択欄 (中身のあるカテゴリだけ、「カテゴリ1(31)」の形) から
    const saveCategories = (doc) => {
      const names = {};
      doc.querySelectorAll('select.p-up-bookmark-chk-tool__category-select option').forEach((o) => { names[o.value] = o.textContent.trim(); });
      if (!Object.keys(names).length) return;
      const counts = {};
      doc.querySelectorAll('select.js-bookmark_list_form_select option').forEach((o) => {
        const c = (o.value.match(/nowcategory=(\d+)/) || [])[1];
        const m = o.textContent.match(/\((\d+)\)\s*$/);
        if (c) counts[c] = m ? Number(m[1]) : 0;
      });
      NH.update((d) => {
        d.bookmarkCatInfo = d.bookmarkCatInfo || {};
        d.bookmarkCatInfo[v.id] = Object.keys(names).map((k) => ({ id: Number(k), name: names[k], n: counts[k] || 0 }));
      });
    };

    return {
      id: v.id,
      name: v.name,
      collectUrl: v.collectUrl,
      bodySel: '.p-novel__text',
      seqEpisodes: true, // 話番号が 1, 2, 3... の連番
      match: (host, path) => (host === 'syosetu.com' || host.endsWith('.syosetu.com')) && isR18(host, path) === !!v.r18,
      onCollectPage: (loc) => loc.pathname.startsWith(v.favPath),
      // 巡回の開始ページから転送された、ログイン・2 段階認証のページ (ログイン切れ・2 段階認証の途中。巡回の画面はここで「ログイン切れ」として次へ進む = ui-collect.js)
      onLoginPage: (loc) => loc.pathname.startsWith('/login/') || loc.pathname.startsWith('/user2stepauth/'),

      // 話ページの「次へ」リンク (最終話には無い)
      nextUrl() {
        const a = document.querySelector('a.c-pager__item--next');
        return a ? a.href : null;
      },

      // 目次 (オフライン保存用): 100 話ごとのページを順にたどる。いま開いているのが目次の 1 ページ目であること
      async listEpisodes(w) {
        const out = [];
        const seen = new Set();
        let doc = document;
        for (let page = 0; page < 40; page++) {
          doc.querySelectorAll('a.p-eplist__subtitle').forEach((a) => {
            const x = this.parseWork(a.href);
            if (!x || x.id !== w.id || x.episode == null || seen.has(x.episode)) return;
            seen.add(x.episode);
            out.push({ ep: String(x.episode), url: a.href.split('#')[0], title: a.textContent.replace(/\s+/g, ' ').trim() });
          });
          const nx = doc.querySelector('a.c-pager__item--next');
          if (!nx) break;
          doc = await NH.fetchListDoc(nx.href, page + 1);
        }
        return out;
      },

      // 「読了 → 次の小説へ」を置く場所: 本文の下の「前へ / 目次 / 次へ」(上にも同じものがあるので最後のもの)
      endCardAfter() {
        const p = document.querySelectorAll('.c-pager');
        return p.length ? p[p.length - 1] : null;
      },

      // ランキング / 検索結果の 1 作品の囲みから作者名を取る (ミュート用)
      authorOf(box) {
        // 作者ページへのリンクを優先 (author-box はポイントの数字も含むので使わない)。複数指定は文書順になるので 1 つずつ見る
        const a = box.querySelector('a[href*="mypage.syosetu.com"]') || box.querySelector('.p-ranklist-item__author, .p-ranktop-item__author, .c-novel-item__name');
        if (a && a.textContent.trim()) return a.textContent.trim();
        const m = box.innerText.match(/作者[：:]\s*([^\n]+)/); // 検索結果は「作者：〇〇」の文字だけ (名前に空白が入ることがある)
        return m ? m[1].trim() : '';
      },

      // タグ (ミュート用): タグ検索へのリンク。無ければ「キーワード： A B C」の文字
      tagsOf(box) {
        const links = [...box.querySelectorAll('a[href*="word="]')].map((a) => a.textContent.trim()).filter(Boolean);
        if (links.length) return links;
        const m = box.innerText.match(/キーワード[：:]\s*([^\n]+)/);
        return m ? m[1].split(/\s+/).filter(Boolean) : [];
      },

      // 連載状態 (絞り込み用)。タイトルの「【完結】」などに反応しないよう、独立した表示 (行頭・空白・括弧の前後) だけを見る
      statusOf(box) {
        const m = box.innerText.match(/(?:^|\s)(短編|連載中|完結済)(?=\s|（|\(|$)/m);
        return m ? { 短編: 'short', 連載中: 'serial', 完結済: 'complete' }[m[1]] : '';
      },

      // 作品の小説情報 (連載状態・全話数・R18 の掲載サイト)。小説情報のページ 1 回で取る。
      // 戻り値: {total, status, sub} (分からない項目は null / '')
      async workInfo(w) {
        const doc = await NH.fetchListDoc(new URL('/novelview/infotop/ncode/' + w.id + '/', w.url).href, 1);
        const t = doc.body.textContent.replace(/\s+/g, ' ');
        const st = t.match(/(連載中|完結済)\s*全(\d+)エピソード/);
        const short = !st && /短編\s*あらすじ/.test(t); // 短編は全話数の行が無く、「R18短編 あらすじ」の見出しで分かる (1 話)
        const site = t.match(/掲載サイト\s*(ノクターンノベルズ|ムーンライトノベルズ|ミッドナイトノベルズ)/);
        return {
          total: st ? Number(st[2]) : short ? 1 : null,
          status: st ? { 連載中: 'serial', 完結済: 'complete' }[st[1]] : short ? 'short' : '',
          sub: site ? { ノクターンノベルズ: 'noc', ムーンライトノベルズ: 'mnlt', ミッドナイトノベルズ: 'mid' }[site[1]] : '',
        };
      },

      // ページの下の畳む欄 (セレクターは sites.js)
      foldGroups: ((window.NH_SITES || {}).narou || {}).fold,

      // 作品名。話ページは上の作品リンク、目次はタイトル (目次の .c-announce a は「ログイン」の案内なので後回し)。
      // ログインしていない話ページも、上の案内 (.c-announce a) は「ログイン」へのリンクなので、作品のトップ (/nコード/) へのリンクだけを作品名にする。
      // querySelector の複数指定は文書順になるので 1 つずつ見る
      workTitle() {
        const epPage = /^\/n\d{4}[a-z]+\/\d+\/?$/.test(location.pathname);
        const code = location.pathname.split('/')[1];
        const norm = (s) => (s || '').trim().replace(/\s+/g, ' ');
        const workLink = (sel) => [...document.querySelectorAll(sel)]
          .filter((a) => { try { return new URL(a.getAttribute('href'), location.href).pathname.replace(/\/$/, '') === '/' + code; } catch (e) { return false; } })
          .map((a) => norm(a.textContent)).find((t) => t && t !== '目次');
        const t = (epPage ? [() => workLink('.c-announce a'), () => workLink('a[href]'), () => norm((document.querySelector('.p-novel__title') || {}).textContent)]
          : [() => norm((document.querySelector('.p-novel__title') || {}).textContent), () => norm((document.querySelector('.c-announce a') || {}).textContent)])
          .map((f) => f()).find(Boolean);
        // どちらも無いのは作品のページではない (エラーや確認の途中のページ。title は「移動」「エラー」など) ので、document.title は使わない
        return t || '';
      },

      // 公式の閲覧履歴 (yomou.syosetu.com/rireki/list/)。R18 版は対象外
      isHistoryPage: (loc) => !v.r18 && loc.hostname === 'yomou.syosetu.com' && loc.pathname.startsWith('/rireki/'),
      parseHistory(doc) {
        return [...doc.querySelectorAll('.p-rireki-item')].map((li) => {
          const a = li.querySelector('a.c-card__title');
          const w = a && this.parseWork(a.href);
          if (!w) return null;
          const resume = li.querySelector('a.c-button--primary'); // 「続きから読む（エピソードN）」
          const tot = li.textContent.match(/全(\d+)エピソード/);
          return { id: w.id, title: a.textContent.trim(), url: resume ? resume.href : w.url, total: tot ? Number(tot[1]) : null, anchor: a };
        }).filter(Boolean);
      },

      // URL → {id, url, episode}。作品ページ以外は null
      parseWork(url) {
        const u = NH.parseUrl(url);
        if (!u) return null;
        // 作品のホスト (通常版は ncode)
        if (u.hostname !== v.workHost) return null;
        const m = u.pathname.match(WORK_RE);
        if (!m) return null;
        return { id: m[1], url: u.origin + '/' + m[1] + '/', episode: m[3] ? Number(m[3]) : null };
      },

      // ブックマークの、設定で選んだカテゴリ (既定はカテゴリ1) から未読のある作品を集める。同一 origin (syosetu.com) 上で実行する。
      // 戻り値: {ok, items:[{id, title, url(最初の未読話), unread, lastEp}]}
      async collectUpdates() {
        const items = [];
        let sawList = false;
        let fetched = 0; // 取ったページの通し番号 (2 ページ目以降は間をあける)
        let truncated = false; // ページの上限で打ち切った (続きのページがある)
        const cats = NH.bookmarkCats(NH.load(), v.id);
        for (const cat of cats) {
          let next = v.collectUrl.replace(/nowcategory=\d+/, 'nowcategory=' + cat);
          for (let page = 0; next && page < 10; page++, fetched++) {
            const doc = await NH.fetchListDoc(next, fetched);
            if (fetched === 0) saveCategories(doc);
            const lis = [...doc.querySelectorAll('.p-up-bookmark-item')];
            if (doc.querySelector('.p-up-bookmark-list, .c-up-panel__list') || lis.length) sawList = true;
            for (const li of lis) {
              const a = li.querySelector('.p-up-bookmark-item__title a');
              const p = li.querySelector('a.c-button--primary'); // 未読があるときだけ <a>
              if (!a || !p) continue;
              const w = this.parseWork(a.href);
              const ep = (p.getAttribute('href') || '').match(/\/(\d+)\/?$/);
              const n = Number((p.querySelector('.p-up-bookmark-item__unread-num') || {}).textContent);
              if (!w || !ep || !n) continue;
              const title = [...a.childNodes].filter((x) => x.nodeType === 3).map((x) => x.textContent).join('').replace(/\s+/g, ' ').trim()
                || a.textContent.replace(/^\s*(連載|短編)\s*/, '').trim();
              items.push({ id: w.id, title, url: new URL(p.getAttribute('href'), location.href).href, unread: n, lastEp: Number(ep[1]) + n - 1, cat });
            }
            const nx = doc.querySelector('.c-pager__item--next a, a[rel=next]');
            next = nx ? nx.getAttribute('href') : null;
          }
          if (next) truncated = true;
        }
        // 作品の入ったカテゴリのうち、見ていないものがある (確認するカテゴリを絞った) ときは、見たカテゴリ (cats) を返す。
        // 見ていないカテゴリの作品を「今回の取得に無い」として読了にしないため (読了から日がたつと、リストから消えてしまう)
        const info = (NH.load().bookmarkCatInfo || {})[v.id] || [];
        const partial = info.some((c) => c.n > 0 && !cats.includes(Number(c.id)));
        return { ok: sawList, items, truncated, cats: partial ? cats : null };
      },

      // ランキング / 検索結果 / トップ / 作者ページの作品: [{title, href, anchor, after?}] (after の直後、無ければ anchor の親ブロックの直後にボタンを付ける)
      rankingItems() {
        // R18 (スマホ版の HTML) は古い作りで、ページごとに違う。どれも 1 つの囲みが 1 作品:
        //  - ランキングトップ (3 サイト共通): dl.ranking > dd > a.title
        //  - 期間別ランキング: ノクターン / ムーンライトは .ranking_inbox ul.ranking > li.title > a、ミッドナイトは div.rankList > p > a.title
        //  - 検索結果: ノクターン / ムーンライトは ul.novellist > li.title > a、ミッドナイトは article.search_novel の h1 > a.tl
        const sel = [
          '.p-ranktop-item__title a', '.p-ranklist-item__title a',
          'dl.ranking > dd > a.title', '.ranking_inbox ul.ranking > li.title > a', 'div.rankList > p > a.title',
          'ul.novellist > li.title > a', 'article.search_novel h1 > a.tl',
        ].join(', ');
        const items = [...document.querySelectorAll(sel)].map((a) => ({
          title: a.textContent.trim(),
          href: a.href,
          anchor: a,
          // ランキングトップは dt (順位の画像) が横に並ぶので、ボタンは dd の外でなく中 (最後) に置く
          after: a.matches('dl.ranking > dd > a') ? a.parentElement.lastElementChild : undefined,
        }));
        // トップ・おすすめなどのカード: カード全体が a.c-novel-item で、タイトルは中の .c-novel-item__title。
        // ボタンはカードの中、タイトルの直後に置く (カードの外だと、ミュートでカードを隠したときにボタンだけ残る)
        document.querySelectorAll('a.c-novel-item[href]').forEach((a) => {
          const t = a.querySelector('.c-novel-item__title');
          if (t) items.push({ title: t.textContent.trim(), href: a.href, anchor: a, after: t });
        });
        // 古い作りの R18 (ノクターン / ムーンライト / ミッドナイト) のトップ:
        //  - 「小説PickUp！」: noc / mnlt は ul.link_list > li > a (題名の前に「・」が付く)、mid は .recommend_item > a.title
        //  - 「イチオシレビュー！」(mid): .recommend_item の中の作品へのリンク (a.link_u。小説情報へのリンク a.infotop は除く)
        // ul.link_list にはメニューのリンクも並ぶので、作品のトップへのリンクだけを拾う。ボタンはリンクのすぐ後ろ (囲みの中) に置く
        document.querySelectorAll('ul.link_list > li > a[href], .recommend_item a.title, .recommend_item a.link_u:not(.infotop)').forEach((a) => {
          const w = this.parseWork(a.href);
          if (!w || w.episode != null) return;
          items.push({ title: a.textContent.trim().replace(/^[・･]\s*/, ''), href: a.href, anchor: a, after: a });
        });
        // 作者ページ (マイページ) の作品一覧: a.c-novel-list__title (見出しの親ブロックの後ろにボタンを置く)
        document.querySelectorAll('a.c-novel-list__title[href]').forEach((a) => items.push({ title: a.textContent.trim(), href: a.href, anchor: a }));
        // 作者ページの横の一覧 (お気に入りの作品など): a.c-side-list__item。タイトルのあとの「[全9エピソード] 作者名」は題名に入れない。
        // これもカード全体がリンクなので、ボタンはリンクの中の最後に置く
        document.querySelectorAll('a.c-side-list__item[href]').forEach((a) => {
          const c = a.cloneNode(true);
          c.querySelectorAll('.u-text-subtext').forEach((e) => e.remove());
          items.push({ title: c.textContent.trim(), href: a.href, anchor: a, after: a.lastElementChild || undefined });
        });
        // 検索結果 (search.php): タイトルは span.novel_h で、リンクではない。読むリンクは a.read_button (話のURL)。
        // タイトルを作品トップへのリンクにする (1 回目だけ a を作り、2 回目以降はそれを使う)
        document.querySelectorAll('.smpnovel_list').forEach((box) => {
          const t = box.querySelector('.novel_h');
          const r = box.querySelector('a.read_button');
          if (!t || !r) return;
          const w = this.parseWork(r.href);
          if (w) {
            let link = t.querySelector('a');
            if (!link) {
              link = document.createElement('a');
              link.append(...t.childNodes);
              t.append(link);
            }
            link.href = w.url;
            link.style.color = 'inherit';
            link.style.textDecoration = 'none';
          }
          items.push({ title: t.textContent.trim(), href: r.href, anchor: t });
        });
        return items;
      },
    };
  }

  NH.register(make({
    id: 'narou', name: 'なろう', workHost: 'ncode.syosetu.com',
    favPath: '/favnovelmain/', collectUrl: 'https://syosetu.com/favnovelmain/list/?nowcategory=1&order=newlist',
  }));
  NH.register(make({
    id: 'narou18', name: 'ノクターン系', r18: true, workHost: 'novel18.syosetu.com',
    favPath: '/favnovelmain18/', collectUrl: 'https://syosetu.com/favnovelmain18/list/?nowcategory=1&order=newlist',
  }));
})();
