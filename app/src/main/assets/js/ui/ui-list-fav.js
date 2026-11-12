// ランキング・検索の一覧の「☆ お気に入り」ボタン (サイト上の画面。ui-list.js が呼ぶ)。同じ作品が 1 ページに何度も出るので、押したら同じ作品のボタンを全部そろえる。
// お気に入りへの追加・削除の規則は core/favorites.js (NH.toggleFav)。ここは、ボタンの見た目と、押したあとのお知らせ・元に戻すだけ。
// ctx = {mk, onChip (クリックの定型: 押しても下のリンクは反応させない)}。戻り値: {create}
window.NH_UI_LIST_FAV = function (ctx) {
  const { mk, onChip } = ctx;
  const btnsOf = new Map(); // 作品のキー → [ボタン]
  const paintBtn = (btn, on) => {
    btn.textContent = on ? '★ お気に入り' : '☆ お気に入り';
    btn.classList.toggle('on', on);
    btn.setAttribute('aria-pressed', String(on));
  };
  const paintKey = (key, on) => (btnsOf.get(key) || []).forEach((b) => { if (b.isConnected) paintBtn(b, on); });

  function toggle(work, entry, title) {
    let r = null; // NH.update がやり直しても、最後に決めた結果を使う。書かなかったときは null のまま
    NH.update((d) => { r = NH.toggleFav(d, work, title, entry.status); });
    if (!r) { NH.toast('保存データを読めないため、変更できませんでした'); return; }
    if (NH.updateGaveUp) { NH.toast('保存できませんでした。もう一度お試しください'); return; }
    const undo = r.removed && { label: '元に戻す', fn: () => { NH.update((d) => NH.restoreFav(d, r.removed)); paintKey(entry.key, true); } };
    NH.toast(r.result === 'removed' ? 'お気に入りから外しました' : 'お気に入りに追加しました', undo || undefined);
    paintKey(entry.key, r.result !== 'removed');
  }

  // 1 作品ぶんのボタン。on = 「お気に入り済み」と出すか
  function create(work, entry, title, on) {
    const btn = mk('button', 'nh-chip nh-chip-fav');
    onChip(btn, () => toggle(work, entry, title));
    btnsOf.set(entry.key, (btnsOf.get(entry.key) || []).filter((b) => b.isConnected).concat(btn));
    paintBtn(btn, on);
    return btn;
  }
  return { create };
};
