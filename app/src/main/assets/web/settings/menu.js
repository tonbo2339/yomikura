// ---- ⋮ メニューに出す項目 (上部バー → メニューに出す項目) ----
// 項目の名前・見出し・初期値は Kotlin の MenuItems だけに書いてあり、NHStore.menuItems() で読む ([{key, label, group, default, on}])。
// 選んだ値は settings.menuItems = {key: true / false}
function paintMenuItems() {
  let items = [];
  try { items = JSON.parse(NHStore.menuItems()); } catch (e) { items = []; }
  const box = $('menuItemList');
  box.textContent = '';
  let group = null, g = null;
  items.forEach((it) => {
    if (it.group !== group) {
      group = it.group;
      g = addGroup(box, group);
    }
    const c = el('label', 'cell');
    const sw = el('input', 'switch');
    sw.type = 'checkbox';
    sw.checked = !!it.on;
    // ほかの設定と同じく S を通して書く (保存データへ直接書くと、あとで別の設定を変えたときに S で上書きされて消える)
    sw.onchange = () => { S.menuItems = Object.assign({}, S.menuItems, { [it.key]: sw.checked }); commit(); };
    c.append(el('span', 'text', it.label), sw);
    g.append(c);
  });
}
ON_SHOW.menu = paintMenuItems; // 開いたとき、いまの設定で並べ直す
