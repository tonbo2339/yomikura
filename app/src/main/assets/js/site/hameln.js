// ハーメルン アダプター
(function () {
  // 作品トップ: /novel/ID/  話ページ: /novel/ID/12.html
  const WORK_RE = /^\/novel\/(\d+)\/(?:(\d+)\.html)?$/;

  NH.register({
    id: 'hameln',
    name: 'ハーメルン',
    match: (host) => host === 'syosetu.org' || host.endsWith('.syosetu.org'),
    collectUrl: 'https://syosetu.org/?mode=siori2_view&type=2&filter=1',
    onCollectPage: (loc) => loc.search.includes('mode=siori2_view'),
    bodySel: '#honbun,#maegaki,#atogaki',
    // ページの下の畳む欄 (セレクターは sites.js)
    foldGroups: ((window.NH_SITES || {}).hameln || {}).fold,
    seqEpisodes: true, // 話番号が 1, 2, 3... の連番
    lastEpIsCount: true, // 更新の確認で分かる最終話 (lastEp) は全話数。話番号が飛ぶ作品があるので、話番号とは比べない
    // 「次の話 >>」 (最終話には無い)
    nextUrl() {
      const a = [...document.querySelectorAll('a')].find((x) => /次の話/.test(x.textContent));
      return a ? a.href : null;
    },

    // 「読了 → 次の小説へ」を置く場所: 本文の下の「<< 前の話 / 次の話 >>」の並び (li.novelnb の ul。上にもあるので最後のもの)
    endCardAfter() {
      const li = document.querySelectorAll('li.novelnb');
      return li.length ? li[li.length - 1].closest('ul') : null;
    },

    // ランキング / 検索結果の 1 作品の囲みから作者名を取る (ミュート用)。「作者：名前」の文字だけ (リンクでないことが多い)。名前に空白が入ることがあるので行末まで取る
    authorOf(box) {
      // 検索結果は「作者：〇〇」。トップ・作者ページの一覧は「連載：4話 作：〇〇」(「原作：」を拾わないよう、前が空白か行頭の「作：」だけ)
      const m = box.innerText.match(/作者[：:]\s*([^\n]+)/) || box.innerText.match(/(?:^|[\s　])作[：:]\s*([^\n]+)/);
      if (m) return m[1].trim();
      const a = box.querySelector('a[href*="/user/"], a[href*="mode=user"]');
      return a ? a.textContent.trim() : '';
    },

    // タグは「タグ：A B C」の文字 (リンクではない)。原作も含める (ミュート用)
    tagsOf(box) {
      const t = box.innerText;
      const tags = ((t.match(/タグ[：:]\s*([^\n]+)/) || [])[1] || '').split(/\s+/).filter(Boolean);
      const origin = (t.match(/原作[：:]\s*([^\n]+)/) || [])[1];
      return origin ? [origin.trim(), ...tags] : tags;
    },

    // 連載状態 (絞り込み用): 行頭の「連載：5話」「完結：12話」「短編：」(と「未完」) の表示から
    statusOf(box) {
      const m = box.innerText.match(/(?:^|\n)\s*(連載|完結|短編|未完)[^：:\n]{0,6}[：:]/);
      if (m) return { 連載: 'serial', 完結: 'complete', 短編: 'short', 未完: 'unfinished' }[m[1]];
      // トップの「新着短編小説」の作品は、欄に連載状態の字が無い。同じ ul.entry の見出し (li.id5) が短編ならそう読む
      const li = box.closest && box.closest('li');
      for (let h = li && li.previousElementSibling; h; h = h.previousElementSibling) {
        if (h.classList.contains('id5')) return /短編/.test(h.textContent) ? 'short' : '';
      }
      return '';
    },

    // 作品の小説情報 (?mode=ss_detail) の「話数 連載(連載中) 8話」から全話数。戻り値は narou と同じ形 {total, status, sub}
    async workInfo(w) {
      const doc = await NH.fetchListDoc('https://syosetu.org/?mode=ss_detail&nid=' + w.id, 1);
      const text = doc.body.textContent.replace(/\s+/g, ' ');
      const m = text.match(/話数\s*\S*?\s*(\d+)\s*話/);
      const tags = (text.match(/タグ\s*(.*?)\s*掲載開始/) || [])[1] || ''; // 「タグ R-18 ガールズラブ … 掲載開始」(R-18 は R18 の作品、R-15 は対象外)
      // 連載状態: 「話数 連載(連載中) 8話」「話数 連載(完結) 16話」「話数 短編 1話」
      const sm = text.match(/話数\s*(連載|短編)?\s*(?:[(（]([^)）]*)[)）])?/) || [];
      const status = sm[1] === '短編' ? 'short' : /完結/.test(sm[2] || '') ? 'complete' : sm[1] === '連載' || /連載中/.test(sm[2] || '') ? 'serial' : '';
      return { total: m ? Number(m[1]) : null, status, sub: '', r18: /(^|\s)R-?18(\s|$)/.test(tags) };
    },

    // 作品名: 話ページは本文上の作品リンク (href="./")、目次はタイトル「作品名 - ハーメルン」
    workTitle() {
      const a = document.querySelector('#pagetitle a[href="./"], #maind a[href="./"]'); // 見出し (短編・1 話の作品の作品トップは、ページの題が「作品名 - 話名」なので見出しから読む)
      const t = a && a.textContent.trim();
      if (t && !/^目\s*次$/.test(t)) return t;
      return /\/\d+\.html$/.test(location.pathname) ? '' : document.title.replace(/ - ハーメルン$/, '').trim();
    },

    // 公式の閲覧履歴 (?mode=rireki、新しく読んだ順に 1 ページ)。1 作品 = li#rireki-idN:
    //   作品へのリンク、最後に読んだ話 (「2話」)、最新話 (「16話」) のリンクが並ぶ
    isHistoryPage: (loc) => loc.hostname === 'syosetu.org' && new URLSearchParams(loc.search).get('mode') === 'rireki',
    parseHistory(doc) {
      return [...doc.querySelectorAll('li[id^="rireki-id"]')].map((li) => {
        const links = [...li.querySelectorAll('a[href*="/novel/"]')];
        const w = links[0] && this.parseWork(links[0].href);
        if (!w) return null;
        const eps = links.slice(1).filter((a) => { const x = this.parseWork(a.href); return x && x.id === w.id && x.episode != null; });
        const last = eps[1] && this.parseWork(eps[1].href);
        return { id: w.id, title: links[0].textContent.trim(), url: eps[0] ? eps[0].href : w.url, total: last ? last.episode : null, anchor: links[0] };
      }).filter(Boolean);
    },

    parseWork(url) {
      const u = NH.parseUrl(url);
      if (!u) return null;
      // R-18 の作品は h.syosetu.org にある。作品の ID は同じなので、キーは hameln:ID のまま。
      // 作品の URL はそのホストのまま (保存の作業は作品のページのホストで話を取るので、ホストを変えると取れない)
      if (u.hostname !== 'syosetu.org' && u.hostname !== 'h.syosetu.org') return null;
      const m = u.pathname.match(WORK_RE);
      if (!m) return null;
      return { id: m[1], url: u.origin + '/novel/' + m[1] + '/', episode: m[2] ? Number(m[2]) : null };
    },

    // しおり (読みかけ) から未読のある作品を集める。同一 origin (syosetu.org) 上で実行する。
    // 未読 = 全話数 - しおり話数。次の未読は、しおりの話 (一覧のリンク先) に #nh-next を付けておき、開いたときに ui.js が「次の話」へ移る
    // (話番号が飛ぶ作品があるので、しおり話数 + 1 では外れることがある)
    async collectUpdates() {
      const items = [];
      let sawList = false;
      let truncated = false; // ページの上限で打ち切った (続きのページがある)
      for (let page = 1; page <= 10; page++) {
        const doc = await NH.fetchListDoc(page === 1 ? this.collectUrl : this.collectUrl + '&page=' + page, page - 1);
        if (/しおり一覧\(/.test(doc.body.textContent)) sawList = true;
        const lis = [...doc.querySelectorAll('li[id^="siori-id"]')];
        for (const li of lis) {
          const a = li.querySelector('a[href*="/novel/"]');
          const m = (li.querySelector('.date') || {}).textContent;
          const nums = m && m.match(/(\d+)話／全(\d+)話/);
          const w = a && this.parseWork(a.href);
          if (!w || !nums) continue;
          const read = Number(nums[1]);
          const total = Number(nums[2]);
          if (total <= read) continue; // 未読なし (しおり話数 > 全話数 の表記ゆれも含む)
          const title = a.childNodes[0] ? a.childNodes[0].textContent.trim() : a.textContent.trim();
          items.push({ id: w.id, title, url: a.href.split('#')[0] + '#nh-next', unread: total - read, lastEp: total });
        }
        if (!lis.length || !doc.querySelector('a[href*="page=' + (page + 1) + '"]')) break;
        if (page === 10) truncated = true;
      }
      return { ok: sawList, items, truncated };
    },

    rankingItems() {
      const items = [...document.querySelectorAll('a.search_novel_title')].map((a) => {
        const clone = a.cloneNode(true);
        clone.querySelectorAll('.search_novel_order').forEach((e) => e.remove()); // 「1位 」を除く
        return { title: clone.textContent.trim(), href: a.href, anchor: a };
      });
      // トップ・作者ページ (/user/ID/) の一覧: ul.entry > li > a.idN。カード全体が 1 つのリンクで、題名のあとに「原作・連載状況・作者」の欄 (.date) が続く。
      // ボタンはリンクの中の最後に置く (外だと、ミュートでカードを隠したときにボタンだけ残る)。
      // 目次ページの話の一覧も同じ ul.entry なので、話へのリンクは除く (作品のトップへのリンクだけ)
      document.querySelectorAll('ul.entry > li > a[href]').forEach((a) => {
        const w = this.parseWork(a.href);
        if (!w || w.episode != null) return;
        const clone = a.cloneNode(true);
        clone.querySelectorAll('.date').forEach((e) => e.remove());
        items.push({ title: clone.textContent.trim(), href: a.href, anchor: a, after: a.lastElementChild || undefined });
      });
      return items;
    },
  });
})();
