// list.html: データ操作 (元に戻せる書き換え)・並べ替えの操作
// ---- データ操作 (変更前の状態を取っておき、元に戻せるようにする) ----
// NH.update のあと、書けていなければ知らせて true (wrote = fn の中で決めた結果。書かなかったときは null)。呼び出し側は、true なら何もせず戻る
function saveFailed(wrote) {
  if (!wrote) { snack('保存データを読めないため、変更できませんでした'); return true; } // NH.update が書かなかった (NH.unreadable)
  if (NH.updateGaveUp) { render(); snack('保存できませんでした。もう一度お試しください'); return true; } // 書き込みを断られ続けた (ほかが書き続けている)
  return false;
}
function mutate(fn, message) {
  let before = null, after = null; // 変更前と変更後の写し
  const copy = (d) => JSON.parse(JSON.stringify({ readLater: d.readLater, progress: d.progress || {} }));
  NH.update((d) => { before = copy(d); fn(d); after = copy(d); });
  if (saveFailed(after)) return;
  render();
  if (message) snack(message, () => { NH.update((x) => undoChange(x, before, after)); render(); });
  else hideSnack(); // 別の操作をしたら、前の「元に戻す」は使えなくする (その操作まで戻ってしまうため)
}
// この操作で変わった作品と読書位置だけを、変更前に戻す。丸ごと書き戻すと、元に戻すまでの間に
// 更新の確認が足した作品や、ほかで書いた読書位置まで消えてしまうため
function undoChange(x, before, after) {
  const js = (o) => JSON.stringify(o);
  const was = new Map(before.readLater.map((e, i) => [e.key, [e, i]]));
  const now = new Map(after.readLater.map((e) => [e.key, e]));
  const keys = new Set([...was.keys(), ...now.keys()].filter((k) => js(was.get(k)?.[0]) !== js(now.get(k))));
  const list = x.readLater.filter((e) => !keys.has(e.key));
  // 前にあった作品は、前の位置へ戻す (前から順に入れると、ほかが変わっていなければ元の並びになる)
  [...keys].map((k) => was.get(k)).filter(Boolean).sort((p, q) => p[1] - q[1])
    .forEach(([e, i]) => list.splice(Math.min(i, list.length), 0, e));
  x.readLater = list;
  const bp = before.progress, ap = after.progress;
  x.progress = x.progress || {};
  new Set([...Object.keys(bp), ...Object.keys(ap)]).forEach((k) => {
    if (js(bp[k]) === js(ap[k])) return;
    if (k in bp) x.progress[k] = bp[k]; else delete x.progress[k];
  });
}
// 並びだけを変える操作 (長押しの並べ替え・先頭 / 末尾へ)。mutate の「元に戻す」は、中身が変わった作品だけを戻すので、並びだけの変更は戻せない。
// ここは、変更前の並び (key の配列) を取っておき、元に戻すときはその並びへ戻す (作品の中身・読書位置には触らない)。並びが変わらなかったときは何も出さない
function mutateOrder(fn, message) {
  let before = null, after = null;
  NH.update((d) => { before = d.readLater.map((x) => x.key); fn(d); after = d.readLater.map((x) => x.key); });
  if (saveFailed(after)) return;
  render();
  if (message && before.join('\n') !== after.join('\n')) snack(message, () => { NH.update((x) => { x.readLater = restoreOrder(x.readLater, before); }); render(); });
  else hideSnack();
}
function patch(key, fn) { mutate((d) => { const x = d.readLater.find((y) => y.key === key); if (x) fn(x); }); }
function remove(key) { mutate((d) => { d.readLater = d.readLater.filter((x) => x.key !== key); NH.dropProgress(d, [key]); }, 'リストから削除しました'); }
// 選択モードでまとめて削除 (1 回で元に戻せる)
function removeMany(keys) {
  if (!keys.length) return;
  mutate((d) => { const gone = new Set(keys); d.readLater = d.readLater.filter((x) => !gone.has(x.key)); NH.dropProgress(d, keys); }, keys.length + ' 作品をリストから削除しました');
}
function switchKind(key) {
  const it = load().readLater.find((y) => y.key === key);
  if (!it) return;
  const to = kindOf(it) === 'update' ? 'later' : 'update';
  // 手で「未読の更新」へ移した作品は、サイトの一覧に無くてもリストから外さない (manualKind。サイトの一覧に出たら外す)
  mutate((d) => {
    const x = d.readLater.find((y) => y.key === key);
    if (!x) return;
    x.kind = to;
    if (to === 'update') x.manualKind = true; else delete x.manualKind;
  }, '「' + (to === 'later' ? '後で読む' : '更新') + '」タブへ移しました');
}
function toTop(key) {
  mutateOrder((d) => {
    const i = d.readLater.findIndex((x) => x.key === key);
    if (i > 0) d.readLater.unshift(d.readLater.splice(i, 1)[0]);
  }, '先頭へ移しました');
}
function toBottom(key) {
  mutateOrder((d) => {
    const i = d.readLater.findIndex((x) => x.key === key);
    if (i >= 0 && i < d.readLater.length - 1) d.readLater.push(d.readLater.splice(i, 1)[0]);
  }, '末尾へ移しました');
}
// 表示中の並びの中で入れ替える (絞り込み中でも、見えている隣と入れ替わる)
function move(key, delta, visible) {
  const pos = visible.findIndex((x) => x.key === key);
  const to = pos + delta;
  if (pos < 0 || to < 0 || to >= visible.length) return;
  mutate((d) => {
    const a = d.readLater.findIndex((x) => x.key === key);
    const b = d.readLater.findIndex((x) => x.key === visible[to].key);
    if (a < 0 || b < 0) return; // 動かしているあいだに、どちらかが消えた (更新の確認など)。入れ替えると、リストに空の項目が入る
    [d.readLater[a], d.readLater[b]] = [d.readLater[b], d.readLater[a]];
  });
  // 描き直すとボタンが作り直されるので、動かした作品の同じ向きのボタンへフォーカスを戻す (端に着いたら反対の向き。TalkBack・キーボードで続けて動かせるように)
  const btns = [...document.querySelectorAll('[data-move]')].filter((b) => b.dataset.key === key);
  const want = delta < 0 ? 'up' : 'down';
  const b = btns.find((x) => x.dataset.move === want && !x.disabled) || btns.find((x) => !x.disabled);
  if (b) b.focus({ preventScroll: false });
}
