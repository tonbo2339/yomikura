// ---- ミュート・ブロックの一覧: 行の数を出し、押すと別の画面で全部見て・探して・解除する ----
const NAMES_TITLE = { muteWords: 'ミュートしたキーワード・タグ', muteAuthors: 'ミュートした作者', blockWorks: 'ブロックした作品', blockWords: 'ブロックしたキーワード・タグ', blockAuthors: 'ブロックした作者' };
const NAMES_SRC = { // 一覧の種類 → 項目 ({name, remove})
  muteWords: (d) => NH.muteOf(d).words.map((v) => ({ name: v, remove: () => NH.removeMute('words', v) })),
  muteAuthors: (d) => NH.muteOf(d).authors.map((v) => ({ name: v, remove: () => NH.removeMute('authors', v) })),
  blockWorks: (d) => NH.blockOf(d).works.map((w) => ({ name: (w.title || '(タイトル不明)') + ' (' + NH.siteLabel(NH.splitKey(w.key)) + ')', remove: () => NH.removeBlock('works', w.key) })),
  blockWords: (d) => NH.blockOf(d).words.map((v) => ({ name: v, remove: () => NH.removeBlock('words', v) })),
  blockAuthors: (d) => NH.blockOf(d).authors.map((v) => ({ name: v, remove: () => NH.removeBlock('authors', v) })),
};
let namesKind = 'muteWords'; // 別画面で開いている一覧
const nameFold = (t) => String(t || '').normalize('NFKC').toLowerCase();
function openNames(kind) { namesKind = kind; $('namesQ').value = ''; go('names'); }
function paintNamesPage() {
  $('p-names').querySelector('.nav h2').textContent = NAMES_TITLE[namesKind];
  const all = NAMES_SRC[namesKind](load());
  const q = nameFold($('namesQ').value);
  const hit = all.filter((x) => !q || nameFold(x.name).includes(q));
  const box = $('namesList'); box.innerHTML = '';
  hit.forEach((x) => {
    const c = el('div', 'cell');
    c.appendChild(el('span', 'text', x.name));
    const del = el('button', 'del', '解除');
    del.onclick = () => { x.remove(); paint(); snack('「' + x.name + '」を解除しました'); paintNamesPage(); };
    c.appendChild(del);
    box.appendChild(c);
  });
  if (!all.length) box.appendChild(el('div', 'cell', 'まだありません'));
  $('namesHint').textContent = !all.length ? '' : q ? hit.length + ' 件 (全 ' + all.length + ' 件のうち)' : all.length + ' 件';
}
$('namesQ').addEventListener('input', paintNamesPage);

function paintMute() {
  const m = NH.muteOf(load()), b = NH.blockOf(load());
  Object.keys(addKind).forEach((id) => $$('#' + id + 'Kind button').forEach((x) => { const on = x.dataset.v === addKind[id]; x.classList.toggle('on', on); setPressed(x, on); }));
  pickList($('muteModeList'), MUTE_MODES, m.mode, (k) => { NH.update((d) => { d.mute = Object.assign(NH.muteOf(d), { mode: k }); }); paint(); });
  // 一覧の行: 件数だけ出す (押すと別の画面で開く)
  Object.keys(NAMES_SRC).forEach((k) => { const n = NAMES_SRC[k](load()).length; $('nc-' + k).textContent = n ? n + ' 件' : 'なし'; });
}
