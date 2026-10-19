// ランキング・検索の一覧の「＋ 後で読む」ボタン (サイト上の画面。ui-list.js が呼ぶ)。
// 同じ作品が 1 ページに何度も出る (ノクターンのランキングトップの日間・週間・月間など) ので、押したら同じ作品のボタンを全部そろえる。
// リストへの追加・削除の規則は core/list-model.js (NH.toggleLater など)。ここは、ボタンの見た目と、押したあとのお知らせ・元に戻すだけ。
// ctx = {mk, onChip (クリックの定型: 押しても下のリンクは反応させない)}。戻り値: {create, refresh}
window.NH_UI_LIST_LATER = function (ctx) {
  const { mk, onChip } = ctx;
  const btnsOf = new Map(); // 作品のキー → [ボタン]
  const paintBtn = (btn, on) => {
    btn.textContent = on ? '✓ リストに追加済み' : '＋ 後で読む';
    btn.classList.toggle('on', on);
  };
  // key の作品のボタンを全部 on / off の見た目にする (画面から外れたボタンは飛ばす)
  const paintKey = (key, on) => (btnsOf.get(key) || []).forEach((b) => { if (b.isConnected) paintBtn(b, on); });
  // 読了にした作品は「追加済み」にしない (押すと、読了を外してリストに戻す)
  const isOn = (d, work) => { const it = NH.findItem(d, work); return !!it && !it.done; };
  const refresh = (work) => paintKey(NH.workKey(work), isOn(NH.load(), work));
  // 押した直後に、同じ作品のボタンをぽんと弾ませる (押したことが手応えとして分かるように。最初の表示や元の状態への描き直しでは動かさない)
  const pop = (key) => (btnsOf.get(key) || []).forEach((b) => {
    if (!b.isConnected) return;
    b.classList.remove('nh-pop');
    void b.offsetWidth; // 付け直して、続けて押しても最初から動かす
    b.classList.add('nh-pop');
    b.addEventListener('animationend', () => b.classList.remove('nh-pop'), { once: true });
  });

  // ボタンを押した: リストに足す / 外す。外したときは「元に戻す」を出す (メモ・タグ・読了などもそのまま戻す)
  function toggle(work, entry, title) {
    let r = null; // NH.update がやり直したときのために、fn の中で決め直す。書かなかったときは null のまま
    NH.update((d) => { r = NH.toggleLater(d, work, title, entry.status); });
    if (!r) { NH.toast('保存データを読めないため、変更できませんでした'); return; }
    const undo = r.removed && { label: '元に戻す', fn: () => { NH.update((d) => NH.restoreItem(d, r.removed)); refresh(work); } };
    NH.toast(r.result === 'revived' ? 'リストに戻しました (読了を外しました)' : r.result === 'added' ? '後で読むに追加しました' : 'リストから外しました', undo || undefined);
    refresh(work);
    pop(NH.workKey(work));
  }

  // 1 作品ぶんのボタンを作る。on = 「追加済み」と出すか。同じ作品のボタンとして控える
  function create(work, entry, title, on) {
    const btn = mk('button', 'nh-chip');
    onChip(btn, () => toggle(work, entry, title));
    btnsOf.set(entry.key, (btnsOf.get(entry.key) || []).filter((b) => b.isConnected).concat(btn));
    paintBtn(btn, on);
    return btn;
  }

  return { create, refresh };
};
