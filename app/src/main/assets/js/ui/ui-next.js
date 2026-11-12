// 話ページ最下部の「読み終わり → 次の小説へ」のカード (サイト上の画面。ui.js が呼ぶ)。
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
    // お気に入りを使う設定で、まだお気に入りに入れていない作品なら、「この作品をお気に入りに追加」を足す。
    // 押しても、次の作品へは移らない (その場で「追加しました」になる)。次へ進むかは、そのあとで、下の「次の小説へ」で決める。
    // 「次へ」を押したとき、読み終えた作品をリストから外す (読んだ記録は履歴に残る) かは、設定 removeOnNext (既定オン)。オフならリストに残す。
    // 左のボタン: 「消す」設定 (既定) は「読み終えてリストへ」(リストから外して、リストへ戻る)。「残す」設定 (removeOnNext がオフ) は「リストから削除」(その場で削除する)
    const remove = NH.flag(d, 'removeOnNext');
    const offerFav = NH.favOn(d) && !NH.isFav(d, cur);
    const b = mk('button', 'nh-next-btn' + (next ? '' : ' last'));
    if (next) {
      const t1 = seq ? 'リストの並びの次へ' : '次の小説へ'; // 設定に関わらず同じ文 (消すか残すかは、設定 removeOnNext で決まる)。seq = 絞り込み・並べ替えたリストの並びの続き
      // 数字は span に分ける (縦書きでは、数字を縦に起こす。前後のすき間は、横書きだけ CSS で空ける)
      b.append(mk('span', 't1', t1), mk('span', 't2', next.title));
      const t3 = mk('span', 't3');
      t3.append('残り', mk('span', 'n' + (String(remaining).length <= 2 ? ' tcy' : ''), String(remaining)), '作品'); // 2 桁までは縦書きで 1 つにまとめる (.tcy)
      b.appendChild(t3);
    } else if (seq) {
      b.append(mk('span', 't1', '読み切りました'), mk('span', 't2', 'リストの並びの最後です'), mk('span', 't3', 'リストに戻ります'));
    } else {
      b.append(mk('span', 't1', '読み切りました'), mk('span', 't2', 'リストに次の作品はありません'), mk('span', 't3', 'リストに戻ります'));
    }
    const flushPos = () => { if (window.__nhFlushPos) window.__nhFlushPos(); }; // 外す前に、いまの読書位置と履歴の読み具合を書く (履歴の「読みかけ」の表示のため。外したあとは書き戻させない)
    const markRead = () => { flushPos(); window.__nhNoPosWrite = true; NH.update((dd) => { const x = NH.findItem(dd, cur); if (x) NH.removeItem(dd, x); }); }; // リストから外す (読んだ記録は履歴に残る)。外したあとに読書位置を書き戻さない (ui-readpos.js)
    b.addEventListener('click', () => {
      if (remove) markRead();
      NH.go(next ? NH.resumeUrl(NH.load(), next) : NH.LIST_URL);
    });
    if (offerFav) {
      const fav = mk('button', 'nh-next-fav', '★ この作品をお気に入りに追加');
      fav.addEventListener('click', () => {
        const title = adapter.workTitle ? adapter.workTitle() : '';
        const status = (adapter.pageStatus ? adapter.pageStatus() : '') || (cur.episode == null ? 'short' : '');
        NH.update((dd) => { NH.addFav(dd, cur, title, status); });
        fav.textContent = '★ お気に入りに追加しました'; // 次の作品へは移らない。押し直せないようにして、追加したと分かる色にする
        fav.disabled = true;
        fav.classList.add('added');
      });
      wrap.appendChild(fav);
    }
    // 左: 読み終えてリストへ戻る (今の作品はリストから外れ、次の作品はそのまま残る)。右: 次の作品。
    // 次の作品が無いときは、右のボタン (読み切り) もリストへ戻るので、左は出さない
    const row = mk('div', 'nh-next-row');
    if (remove) {
      // 「消す」設定 (既定): 左は「読み終えてリストへ」(リストから外して、リストへ戻る)。次の作品が無いときは、右のボタンがリストへ戻るので、出さない
      if (next) {
        const leave = mk('button', 'nh-next-leave');
        leave.append('読み終えて', document.createElement('br'), 'リストへ'); // 横書きは 2 行。縦書きのカードは、改行を消して 1 列にする (CSS)
        leave.addEventListener('click', () => { markRead(); location.href = NH.LIST_URL; });
        row.appendChild(leave);
      }
    } else {
      // 「残す」設定: 左は「リストから削除」。この作品をリストから外す (リストの「削除」と同じ。読書位置も外す)。
      // 押したら、その場で「削除しました」になる (ページは動かない。そのあと「次の小説へ」などを選べる)
      const del = mk('button', 'nh-next-leave');
      del.append('リストから', document.createElement('br'), '削除');
      del.addEventListener('click', () => {
        flushPos(); window.__nhNoPosWrite = true;
        NH.update((dd) => { const x = NH.findItem(dd, cur); if (x) NH.removeItem(dd, x); });
        del.textContent = '削除しました';
        del.disabled = true;
        del.classList.add('done');
      });
      row.appendChild(del);
    }
    row.appendChild(b);
    wrap.appendChild(row);
    const sub = mk('button', 'nh-next-sub', 'リストを見る');
    sub.addEventListener('click', () => (location.href = NH.LIST_URL));
    wrap.appendChild(sub);
    // 置き場所: アダプターが指定する要素 (話の下の「前へ / 次へ」) の直後 = おすすめより上。無ければページの最後
    const vwrap = document.getElementById('nh-vwrap'); // サイトの話ページの縦書き (ui-vertical.js): 箱の左端 (読み終わり) に置く
    if (vwrap) { wrap.classList.add('nh-next-v'); vwrap.appendChild(wrap); return; }
    // カクヨムの縦組み (サイト自身。body の class writingDirection-vertical): カードも縦長にする。縦組みになるのは、ページを開いて少し経ってからなので、class の変わりに合わせて付け外しする
    const vertical = () => !!document.body && document.body.classList.contains('writingDirection-vertical');
    const follow = () => wrap.classList.toggle('nh-next-v', vertical());
    follow();
    if (document.body) new MutationObserver(follow).observe(document.body, { attributes: true, attributeFilter: ['class'] });
    const at = adapter.endCardAfter && adapter.endCardAfter();
    if (at) { wrap.classList.add('inline'); at.insertAdjacentElement('afterend', wrap); }
    else (document.body || document.documentElement).appendChild(wrap);
  }
};
