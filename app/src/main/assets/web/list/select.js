// list.html: 選択モード (メーラーと同じ。チェックボックスで選んで、ゴミ箱でまとめて削除)
// ---- 選択 (ui.sel = 選んだ作品の key の集合。null = 選択モードではない) ----
$('selectBtn').innerHTML = svg('check');
$('selClose').innerHTML = svg('close');
$('selDel').innerHTML = svg('del');
const selecting = () => !!ui.sel;
let selVisible = []; // いま選べる作品 (表示中の並び) の key

function beginSelect() { ui.sel = new Set(); document.body.classList.add('selecting'); render(); }
function endSelect() { if (!ui.sel) return; ui.sel = null; document.body.classList.remove('selecting'); render(); }
function toggleSel(key) {
  if (!ui.sel) return;
  if (ui.sel.has(key)) ui.sel.delete(key); else ui.sel.add(key);
  paintSelect();
}
// render が呼ぶ。表示中の作品に無い選択は外し、バーとチェックの表示を合わせる (履歴のタブでは選択モードを終える)
function syncSelect(visible) {
  selVisible = visible ? visible.map((x) => x.key) : [];
  $('selectBtn').hidden = !visible || !selVisible.length;
  if (ui.sel && !visible) { ui.sel = null; document.body.classList.remove('selecting'); }
  if (ui.sel) { const keep = new Set(selVisible); ui.sel.forEach((k) => { if (!keep.has(k)) ui.sel.delete(k); }); }
  paintSelect();
}
function paintSelect() {
  const on = selecting(), n = on ? ui.sel.size : 0;
  $('selBar').hidden = !on;
  $('selectBtn').classList.toggle('on', on);
  $('selN').textContent = n ? n + ' 件を選択中' : '作品を選んでください';
  $('selAll').textContent = n && n === selVisible.length ? '選択を外す' : 'すべて選択';
  $('selDel').disabled = !n;
  $('selDel').setAttribute('aria-label', n ? '選んだ ' + n + ' 件を削除' : '選んだ作品を削除');
  $$('.item[data-key]').forEach((c) => {
    const s = on && ui.sel.has(c.dataset.key);
    c.classList.toggle('picked', s);
    c.setAttribute('aria-checked', s ? 'true' : 'false');
  });
}
$('selectBtn').onclick = () => (selecting() ? endSelect() : beginSelect());
$('selClose').onclick = endSelect;
$('selAll').onclick = () => {
  if (!ui.sel) return;
  const all = ui.sel.size === selVisible.length;
  ui.sel.clear();
  if (!all) selVisible.forEach((k) => ui.sel.add(k));
  paintSelect();
};
$('selDel').onclick = () => {
  if (!ui.sel || !ui.sel.size) return;
  const keys = [...ui.sel];
  ui.sel = null; document.body.classList.remove('selecting');
  removeMany(keys);
};
document.addEventListener('keydown', (e) => { if (e.key === 'Escape' && selecting() && $('sheet').classList.contains('show') === false) endSelect(); });
