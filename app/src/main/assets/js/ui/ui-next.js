// 話ページ最下部の「読了 → 次の小説へ」のカード (サイト上の画面。ui.js が呼ぶ)。
// ctx = ui.js の共通の部品 {adapter, mk, cur (いまの作品。作品のページでなければ null)}。
window.NH_UI_NEXT = function (ctx) {
  const { adapter, mk, cur } = ctx;
  if (!cur) return;
  const d = NH.load();
  const it = NH.findItem(d, cur);
  // 出すのは「読み終わり」のページだけ:
  //  - 話ページ: 次の話が無い (最新話) か、更新取得で分かった最終話 (lastEp) に着いた
  //    (lastEp との比較は話番号が連番のサイト = seqEpisodes だけ。カクヨムの話 ID は連番でない。
  //     ハーメルンの lastEp は全話数で、話番号が飛ぶ作品では最後の話より手前で着いたことになるので比べない = lastEpIsCount)
  //  - 話番号の無いページ: 本文があるもの (短編) だけ。目次ページには出さない
  const hasNext = adapter.nextUrl ? !!adapter.nextUrl() : false;
  const atEnd = cur.episode == null
    ? !!(adapter.bodySel && document.querySelector(adapter.bodySel))
    : !hasNext || (!!adapter.seqEpisodes && !adapter.lastEpIsCount && !!it && !!it.lastEp && Number(cur.episode) >= it.lastEp);
  if (it && atEnd) {
    // 次の小説: リストで絞り込んで開いたときは、その並びの続き (並びの最後なら、それで終わり)。
    // そうでなければ、同じ種類 (未読 / 後で読む) の先頭、無ければ残り全体の先頭
    const { seq, next, remaining } = NH.nextPool(d, it);
    const wrap = mk('div', 'nh-next');
    wrap.appendChild(mk('div', 'nh-next-cap', 'ここまで読みました'));
    const b = mk('button', 'nh-next-btn' + (next ? '' : ' last'));
    if (next) {
      const t1 = seq ? '読了して、リストの並びの次へ' : '読了して、次の小説へ'; // seq = 絞り込み・並べ替えたリストの並びの続き
      b.append(mk('span', 't1', t1), mk('span', 't2', next.title), mk('span', 't3', '残り ' + remaining + ' 作品'));
    } else if (seq) {
      b.append(mk('span', 't1', '読み切りました'), mk('span', 't2', 'リストの並びの最後です'), mk('span', 't3', 'リストに戻ります'));
    } else {
      b.append(mk('span', 't1', '読み切りました'), mk('span', 't2', 'リストに次の作品はありません'), mk('span', 't3', 'リストに戻ります'));
    }
    const markRead = () => NH.update((dd) => { const x = NH.findItem(dd, cur); if (x) NH.setDone(x, true); });
    b.addEventListener('click', () => {
      markRead();
      NH.go(next ? NH.resumeUrl(NH.load(), next) : NH.LIST_URL);
    });
    // 左: 読了してリストへ戻る (次の作品はそのまま残る)。右: 次の作品。
    // 次の作品が無いときは、右のボタン (読み切り) もリストへ戻るので、左は出さない
    const row = mk('div', 'nh-next-row');
    if (next) {
      const leave = mk('button', 'nh-next-leave', '読了して\nリストへ');
      leave.addEventListener('click', () => { markRead(); location.href = NH.LIST_URL; });
      row.appendChild(leave);
    }
    row.appendChild(b);
    wrap.appendChild(row);
    const sub = mk('button', 'nh-next-sub', 'リストを見る');
    sub.addEventListener('click', () => (location.href = NH.LIST_URL));
    wrap.appendChild(sub);
    // 置き場所: アダプターが指定する要素 (話の下の「前へ / 次へ」) の直後 = おすすめより上。無ければページの最後
    const at = adapter.endCardAfter && adapter.endCardAfter();
    if (at) { wrap.classList.add('inline'); at.insertAdjacentElement('afterend', wrap); }
    else (document.body || document.documentElement).appendChild(wrap);
  }
};
