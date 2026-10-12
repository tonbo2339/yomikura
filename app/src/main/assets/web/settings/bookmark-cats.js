// ---- なろう / R18 のブックマークの、更新を確認するカテゴリ (settings.bookmarkCats) ----
// カテゴリ名と件数は、更新の確認のときに narou.js が控えたもの (bookmarkCatInfo)。空のカテゴリは、選んでいなければ出さない
const setCats = (id, v) => { S.bookmarkCats = Object.assign({}, S.bookmarkCats, { [id]: v }); commit(); };
function paintCats(id, box) {
  box.innerHTML = '';
  const d = load();
  const info = (d.bookmarkCatInfo || {})[id] || [];
  const all = (S.bookmarkCats || {})[id] === 'all';
  const sel = NH.bookmarkCats({ settings: S, bookmarkCatInfo: d.bookmarkCatInfo }, id);
  const row = (label, sub, on, fn) => box.appendChild(checkRow(label, on, fn, sub));
  row('すべてのカテゴリ', '作品の入っているカテゴリを全部', all, () => setCats(id, all ? [1] : 'all'));
  const known = new Map(info.map((x) => [x.id, x]));
  // カテゴリ名をまだ控えていないときは、1〜3 と選んだものだけ出し、残りは「ほかのカテゴリ」で開く
  const folded = !info.length && !catsOpen[id];
  for (let i = 1; i <= 10; i++) {
    const x = known.get(i);
    const on = sel.includes(i);
    if (info.length && (!x || !x.n) && !on) continue;
    if (folded && i > 3 && !on) continue;
    row(x ? x.name : 'カテゴリ' + i, x ? x.n + ' 作品' : '', on, () => {
      const next = on ? sel.filter((c) => c !== i) : [...sel, i];
      if (!next.length) { snack('1 つ以上選んでください'); return; }
      setCats(id, next);
    });
  }
  if (folded) {
    const more = el('button', 'cell accent'); more.appendChild(el('span', 'text', 'ほかのカテゴリを表示'));
    more.onclick = () => { catsOpen[id] = true; paintCats(id, box); };
    box.appendChild(more);
  }
}
const catsOpen = {};
