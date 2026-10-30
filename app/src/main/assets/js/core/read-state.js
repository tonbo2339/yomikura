// Yomikura 共通基盤 (7): 作品の読み具合・続きから開く URL・話番号・読書位置の計算
window.NH.define('read-state', function (NH) {
  NH.need('siteinfo');
  // リストの作品の読書位置。更新の確認のあと (urlAt) に読んだものだけ (それより古いものは、サイトのしおりのほうが新しい)。無ければ null
  NH.progressOf = (d, it) => {
    const p = (d.progress || {})[it.key];
    return p && (!it.urlAt || p.at >= it.urlAt) ? p : null;
  };
  // 続きから開く URL。読書位置 (d.progress) が、更新取得で得た url より新しければそれを優先する。
  //  - 途中までなら、その話をそのまま開く (位置は話ページ側で復元)
  //  - 読み終えた話なら、次の話 (記録してあれば) を開く
  NH.resumeUrl = function (d, it) {
    const p = NH.progressOf(d, it);
    if (p) {
      if (!p.done) return p.url;
      if (p.next) return p.next;
    }
    return it.url;
  };

  // 読書位置 p の話番号 (「第N話」に出す数)。no = 話 ID が連番でないサイト (カクヨム) で、目次の並びから数えた番号 (ui.js が足す)。
  // ep が短い数字 (なろう・ハーメルンの話番号) ならそれ。ep = '0' は短編で、番号なし。分からなければ null
  NH.epNumber = (p) => {
    if (!p) return null;
    if (Number.isInteger(p.no) && p.no >= 1 && p.no <= 99999) return p.no;
    return /^[1-9]\d{0,4}$/.test(String(p.ep)) ? Number(p.ep) : null;
  };
  // 履歴の作品 e に覚えた、最後に開いた話 (e.ep。短編・番号なしは '0') の何話目か (no) と読み具合 (pr)。
  // 覚えたのが別の話 (話を開いてすぐ離れ、記録する前に次の話へ移った) なら使わない。epNoEp / pr.ep = 覚えたときの話 (古い記録には無いので、そのときは使う)
  NH.historyPos = (e) => {
    const ep = e.ep || '0';
    return {
      no: e.epNo && (!e.epNoEp || e.epNoEp === ep) ? e.epNo : null,
      pr: e.pr && (e.pr.ep == null || e.pr.ep === ep) ? e.pr : null,
    };
  };
  // サイトの行き先の URL (更新の確認で入った、しおり・次の未読の話) から話番号を読む。分からなければ null。
  // ハーメルンの #nh-next は「しおりの話の次」なので 1 足す。カクヨムの続き (resume_reading) は番号が分からない
  NH.epFromUrl = (site, url) => {
    const u = NH.parseUrl(url);
    if (!u) return null;
    let m;
    if ((site === 'narou' || site === 'narou18') && (m = u.pathname.match(/^\/n\d{4}[a-z]+\/(\d+)\/?$/i))) return Number(m[1]);
    if (site === 'hameln' && (m = u.pathname.match(/^\/novel\/\d+\/(\d+)\.html$/))) return Number(m[1]) + (u.hash === '#nh-next' ? 1 : 0);
    return null;
  };
  // リストの作品の読み具合 (リストのカード・上の「続きから読む」で使う)。
  //   short: 短編か / total: 全話数 (分からなければ 0) / ep: いま読んでいる (次に読む) 話の番号 (分からなければ null)
  //   epPct: その話の中の進み (0〜1) / workPct: 作品全体の進み (0〜1。全話数が分からなければ null)
  //   started: 読み始めているか / finished: 最後の話まで読み終えたか / fromSite: 位置はサイトのしおりから (アプリでは読んでいない)
  //   unread: 未読の話数 (分からなければ null) / complete: 完結した作品か
  // 読書位置は NH.progressOf (更新の確認のあとに読んだものだけ)
  NH.readState = (d, it) => {
    const p = NH.progressOf(d, it);
    const short = it.status === 'short' || (!!p && String(p.ep) === '0');
    let total = short ? 1 : Math.max(Number(it.total) || 0, Number(it.lastEp) || 0);
    let ep = null, epPct = 0, readEps = null, started = false, fromSite = false;
    if (p) {
      started = true;
      const n = short ? 1 : NH.epNumber(p);
      if (p.done && p.next && n) { ep = n + 1; readEps = n; } // その話を読み終えて、次の話から
      else if (n) { ep = n; epPct = p.done ? 1 : Math.max(0, Math.min(1, p.y || 0)); readEps = n - 1 + epPct; }
      else if (p.done && !p.next && total) { ep = total; readEps = total; epPct = 1; } // 話番号は分からないが、最後の話 (次の話が無い) まで読み終えた
      else epPct = p.done ? 1 : Math.max(0, Math.min(1, p.y || 0)); // 話番号の分からない話 (カクヨムで目次を読めていない)
    } else {
      // アプリで読んでいなければ、サイトの行き先の話番号か、未読の話数 (全話数 - 未読 = 読んだ話数。カクヨムの続きの URL は番号が無い) から
      const n = NH.epFromUrl(it.site, it.url) || (total && it.unread != null && it.unread < total ? total - it.unread + 1 : null);
      if (n && n > 1) { ep = n; readEps = n - 1; started = true; fromSite = true; }
    }
    // 読み終えたのは、全話数まで読んで、その話で終わっている (次の話が無い) とき
    const finished = !!total && readEps != null && readEps >= total && (!p || (!!p.done && !p.next));
    // 全話数より先を読んでいるのに終わっていない: 全話数が古い (新しい話が出た) か、話番号が飛ぶ作品 (ハーメルン)。全話数は使わない
    if (!finished && total && !short && readEps != null && (readEps >= total || (ep && ep > total))) total = 0;
    if (total && ep && ep > total) ep = total;
    const workPct = total && readEps != null ? Math.max(0, Math.min(1, readEps / total)) : short ? epPct : null;
    const calc = total && readEps != null ? Math.max(0, total - Math.floor(readEps)) : null;
    const unread = calc != null && it.unread != null ? Math.min(calc, it.unread) : calc != null ? calc : it.unread != null ? it.unread : null;
    return { short, total, ep, epPct, workPct, started, finished, fromSite, unread, complete: it.status === 'complete' };
  };

  // 作品を読んだことがあるか (ランキング・検索で薄くする目印): 'done' 読了 / 'reading' 読みかけ / 'seen' 開いたことがある / null
  // 読書位置・リストの読了・閲覧履歴 (公式の履歴を取り込んだものも含む) から判定する
  // 一覧の作品ごとに呼ぶので、リストと履歴は先に索引にしておき、(key) => 状態 の関数を返す
  NH.readStates = function (d) {
    const progress = d.progress || {};
    const doneInList = new Set(d.readLater.filter((x) => x.done).map((x) => x.key));
    const seen = new Set((d.history || []).map((h) => h.key));
    return (key) => {
      const p = progress[key];
      // p.done はその話を読み終えたこと。次の話がある (p.next) なら、作品はまだ読みかけ
      if (doneInList.has(key) || (p && p.done && !p.next)) return 'done';
      if (p) return 'reading';
      return seen.has(key) ? 'seen' : null;
    };
  };
  NH.READ_LABEL = { done: '読了', reading: '読みかけ', seen: '閲覧済み' };

  // 話ページの読書位置。top / h = 本文の上端と高さ、scrollY = いまのスクロール、viewH = 画面の高さ。
  // y = 本文の何割まで来たか (画面の上端)、done = 画面の下端が本文の 97% を過ぎた
  NH.scrollState = (scrollY, top, h, viewH) => ({ y: (scrollY - top) / h, done: (scrollY + viewH - top) / h >= 0.97 });
});
