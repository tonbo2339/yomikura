// Yomikura 共通基盤 (8b): お気に入りの作品 (★)。「後で読む」とは別の一覧で、読了の自動削除などで消えない。
//   favorites: [{key, site, id, url, title, addedAt, status?, sub?, r18?}]  新しく足した順 (先頭が新しい)。作品の数に上限は無い。
//   読んだ場所は progress (話ごとの読書位置) をそのまま使う。お気に入りの作品の progress は、リストから外しても消えないよう守る (store.js の
//   pruneProgress が NH.favProtected を見る)。ただし守るのは、新しく読んだ順に settings.favoritePosMax 作品ぶんまで (未設定は 100、0 = 無制限)。
//   それより古い作品の記録は、ふつうの作品と同じに、古いものから消える (作品は一覧に残り、読んだ場所だけ「まだ読んでいません」に戻る)。
window.NH.define('favorites', function (NH) {
  NH.need('store');
  NH.need('list-model'); // NH.workKey / NH.findItem
  NH.need('siteinfo'); // NH.withSub
  NH.FAV_POS_MAX = 100;
  NH.favPosMaxOf = (d) => { const v = d && d.settings && d.settings.favoritePosMax; return typeof v === 'number' && v >= 0 ? v : NH.FAV_POS_MAX; };
  NH.favOn = (d) => NH.flag(d, 'favOn'); // 設定「お気に入りを使う」(既定オン)。オフでも、お気に入りのデータは消さない
  NH.favsOf = (d) => (Array.isArray(d.favorites) ? d.favorites : []);
  NH.isFav = (d, work) => NH.favsOf(d).some((x) => x.key === NH.workKey(work));
  // 読んだ場所の記録を守るお気に入りの作品のキー: 記録のある作品を新しく読んだ順に並べ、最大数まで
  NH.favProtected = (d) => {
    const p = d.progress || {};
    const max = NH.favPosMaxOf(d);
    const keys = NH.favsOf(d).map((x) => x.key).filter((k) => p[k]).sort((a, b) => (p[b].at || 0) - (p[a].at || 0));
    return new Set(max > 0 ? keys.slice(0, max) : keys);
  };
  // お気に入りに足す。戻り値: 'added' / 'exists'。作品名・連載状態は、リスト・履歴に覚えたものがあればそれを使う
  NH.addFav = (d, work, title, status) => {
    const key = NH.workKey(work);
    if (NH.isFav(d, work)) return 'exists';
    const it = NH.findItem(d, work) || (d.history || []).find((x) => x.key === key) || {};
    const fav = NH.withSub({
      key, site: work.site, id: work.id, url: work.url || it.url, title: title || it.title || work.id, addedAt: Date.now(),
      ...(status || it.status ? { status: status || it.status } : {}),
      ...(it.r18 !== undefined ? { r18: !!it.r18 } : {}),
    });
    if (it.sub && !fav.sub) fav.sub = it.sub;
    d.favorites = [fav].concat(NH.favsOf(d));
    return 'added';
  };
  // お気に入りから外す。戻り値: 外した作品と位置 (元に戻すための控え。無ければ null)
  NH.removeFav = (d, work) => {
    const key = NH.workKey(work);
    const i = NH.favsOf(d).findIndex((x) => x.key === key);
    if (i < 0) return null;
    return { item: d.favorites.splice(i, 1)[0], at: i };
  };
  NH.restoreFav = (d, removed) => {
    if (NH.isFav(d, removed.item)) return;
    d.favorites = NH.favsOf(d).slice();
    d.favorites.splice(Math.min(removed.at, d.favorites.length), 0, removed.item);
  };
  // ★ を押したとき: あれば外し、無ければ足す。戻り値: {result: 'removed' | 'added', removed: 元に戻す控え | null}
  NH.toggleFav = (d, work, title, status) => {
    if (NH.isFav(d, work)) return { result: 'removed', removed: NH.removeFav(d, work) };
    return { result: NH.addFav(d, work, title, status), removed: null };
  };
});
