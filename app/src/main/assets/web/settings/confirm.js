// ---- 確認のアクションシート: ask() / closeConfirm() は app.js ----
// 履歴の保存の上限を変える。いまの履歴から消える分があれば、先に確かめる (消した分は戻せない)
function setHistoryLimit(key, n) {
  n = Number(n);
  const gone = NH.pruneHistory({ history: (load().history || []).slice(), settings: Object.assign({}, S, { [key]: n }) });
  const apply = () => { S[key] = n; commit(); NH.update((d) => NH.pruneHistory(d)); paint(); };
  if (gone > 0) ask('履歴が ' + gone + ' 作品消えます。消した分は戻せません。', '変更して消す', apply); else apply();
}
