// 話ページの読書位置の記録と復元 (サイト上の画面。ui.js が呼ぶ)。短編の読書位置は話番号 '0' として持つ (オフライン保存の短編と同じ)。
// ctx = ui.js の共通の部品 {adapter, cur, isShort (短編か), laterBack (外のリンクを「後で読む」に追加して戻る途中か = 読んでいないので記録しない), moved (ページ内の移動で作品が変わったか)}。
// 戻り値: 移る直前に位置を書き切る関数 (記録しないページは null)。
window.NH_UI_READPOS = function (ctx) {
  const { adapter, cur, isShort, laterBack, moved } = ctx;
  if (!(cur && adapter.bodySel && (cur.episode != null || isShort) && !laterBack)) return null;
  let flush = null;
  const key = NH.workKey(cur);
  const epNo = isShort ? '0' : String(cur.episode);
  const epUrl = location.origin + location.pathname;
  // 読んだ文字数 (空白・改行を除く。読書の統計用)
  const bodyChars = () => [...document.querySelectorAll(adapter.bodySel)].reduce((n, e) => n + (e.textContent || '').replace(/\s/g, '').length, 0);
  let counted = false; // この話は、このページを開いているあいだ 1 回だけ、読書の統計に数える
  const bodyBox = () => {
    const els = [...document.querySelectorAll(adapter.bodySel)];
    if (!els.length) return null;
    let top = Infinity, bot = 0;
    els.forEach((e) => {
      const r = e.getBoundingClientRect();
      top = Math.min(top, r.top + window.scrollY);
      bot = Math.max(bot, r.bottom + window.scrollY);
    });
    return { top, h: Math.max(1, bot - top) };
  };
  let touched = false;
  let armed = false; // 復元が落ち着くまでは記録しない (先頭の 0 で上書きしないため)
  let lastY = -1, lastDone = null; // 前に書いた位置 (ほとんど動いていなければ書かない。書くたびに保存データ全体を書き直すため)
  ['touchstart', 'wheel', 'keydown'].forEach((ev) => window.addEventListener(ev, () => { touched = true; armed = true; }, { passive: true }));
  // 話番号が連番でないサイト (カクヨム) は、目次の並びから何話目かを求めて、読書位置に no として足す。
  // 同じ話で分かっていれば通信しない。確認画面などで取れなかったら、そのサイトを休止する
  const prev = (NH.load().progress || {})[key];
  let epOrd = prev && prev.ep === epNo && prev.no ? prev.no : null;
  // 前に最後まで読んだ話を開き直したとき (履歴・続きから読む) は、前回の位置に戻しただけで読了になるので、統計にもう一度数えない
  if (prev && prev.url === epUrl && prev.done) counted = true;
  const write = () => {
    if (!armed || moved()) return;
    const b = bodyBox();
    if (!b) return;
    const { y, done } = NH.scrollState(window.scrollY, b.top, b.h, window.innerHeight);
    if (Math.abs(y - lastY) < 0.005 && done === lastDone) return;
    lastY = y; lastDone = done;
    const next = adapter.nextUrl ? adapter.nextUrl() : null;
    const note = done && !counted; // 最後まで読んだ (初めて)
    const chars = note ? bodyChars() : 0;
    if (note) counted = true;
    NH.update((d) => {
      d.progress = d.progress || {};
      d.progress[key] = { url: epUrl, ep: epNo, no: epOrd || undefined, y: Math.max(0, y), done, next, at: Date.now() };
      // 履歴にも、何話目かを覚える (リストから外して読書位置が消えても、「さっき何話まで読んだか」が分かるように。カクヨムは目次から求めた番号)
      const hist = (d.history || []).find((x) => x.key === key);
      const ordinal = NH.epNumber(d.progress[key]);
      if (hist) hist.pr = { y: Math.round(Math.max(0, y) * 100) / 100, done, ep: epNo }; // 読書位置が消えても (リストから外す・整理) 「途・未」を出せるよう、履歴にも読み具合を覚える (ep = どの話の読み具合か。NH.historyPos)
      if (hist && ordinal) { hist.epNo = ordinal; hist.epNoEp = epNo; }
      if (ordinal && (y >= 0.1 || done)) NH.noteEpisodeRead(d, key, ordinal); // 読んだ話 (1 割まで読んだか、最後まで読んだ話) を、その日の記録に足す
      if (note) NH.noteRead(d, key, chars);
      NH.pruneProgress(d);
    });
  };
  if (!epOrd && !isShort && adapter.episodeNo && !NH.isResting(NH.load(), adapter.id, true)) {
    adapter.episodeNo(cur).then((n) => { if (n) { epOrd = n; lastY = -1; write(); } })
      .catch((e) => { if (e && e.message === NH.CHALLENGE) NH.markRest(adapter.id); });
  }
  // 書くたびに保存データ全体を書き直すので、スクロール中は 4 秒に 1 回まで (止まったときの分も含めて遅れて書く)。ページを離れる / 隠れるときは write を直接呼ぶ
  let timer = 0;
  window.addEventListener('scroll', () => { if (!timer) timer = setTimeout(() => { timer = 0; write(); }, 4000); }, { passive: true });
  window.addEventListener('pagehide', write);
  document.addEventListener('visibilitychange', () => { if (document.hidden) write(); });
  flush = write;

  const saved = (NH.load().progress || {})[key];
  const restoring = saved && saved.url === epUrl && saved.y > 0.005 && !location.hash; // 読了の位置を過ぎていても、その話を開き直したときは前回の位置から
  let restoredAt = -1; // 最後に戻した scrollY (そこから動いていれば、音量キーなどで利用者が動かした)
  const restore = () => {
    if (touched) return;
    const b = bodyBox();
    if (b) { window.scrollTo(0, b.top + saved.y * b.h); restoredAt = window.scrollY; }
  };
  // 前回の位置まで戻せたか (本文の描画が遅いと、まだ戻せていないことがある)
  const restored = () => {
    const b = bodyBox();
    return !!b && NH.scrollState(window.scrollY, b.top, b.h, window.innerHeight).y >= saved.y - 0.02;
  };
  if (restoring) {
    [0, 300, 900, 2000].forEach((ms) => setTimeout(restore, ms));
    setTimeout(() => { if (!touched) NH.toast('前回の位置から再開'); }, 400);
  }
  // 記録を始める。前回の位置へまだ戻せていなければ、もう少し待って戻し直す (先頭の位置で前回の位置を上書きしないため)。
  // 戻し直すのは、戻した位置から動いていないときだけ (音量キー・自動スクロールで読み進めた位置を引き戻さない)。
  // 何度か待っても戻せなければ (文字の大きさを変えて、前の位置まで届かないなど)、そのまま記録を始める
  const arm = (tries) => {
    if (armed) return;
    const userMoved = restoredAt >= 0 && Math.abs(window.scrollY - restoredAt) > 2;
    if (restoring && !touched && !userMoved && !restored() && tries > 0) {
      restore();
      setTimeout(() => arm(tries - 1), 1500);
      return;
    }
    armed = true;
    write();
  };
  setTimeout(() => arm(5), 2300);
  return flush;
};
