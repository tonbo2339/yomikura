// list.html: 作品のカード
// ---- カード ----
function card(d, it, isActive, visible) {
  const c = el('div', 'card item' + (isActive ? '' : ' done'));
  const picking = isActive && selecting(); // 選択モードでは、押すと開かず選ぶ (select.js)
  asButton(c, () => { if (picking) toggleSel(it.key); else if (Date.now() - dragEndedAt > 500) openWork(d, it, visible); }); // 持ち上げて離したときは開かない (キーボードでも Enter / Space で開く)
  if (isActive) enableDrag(c, it, visible, sortMode(d) === 'manual' && !picking, swipeOn(d) && !picking);
  if (picking) {
    c.dataset.key = it.key; c.setAttribute('role', 'checkbox');
    const box = el('span', 'selbox'); box.innerHTML = svg('check');
    c.appendChild(box);
  }

  const main = el('div', 'main');
  const meta = el('div', 'meta');
  const site = el('span', 'site');
  site.append(el('span', 'dot ' + it.site), document.createTextNode(NH.siteLabel(it)));
  meta.appendChild(site);
  const st = NH.readState(d, it);
  // 連載状態 (連載中・完結・短編。分かるときだけ。履歴と同じ場所・同じ印)
  if (STATUS_NAME[it.status]) meta.appendChild(el('span', 'badge status ' + it.status, STATUS_NAME[it.status]));
  if (isActive && st.unread && !st.short) meta.appendChild(el('span', 'badge unread', '未読' + st.unread + '話'));
  NH.tagsOf(it).forEach((t) => meta.appendChild(el('span', 'badge tag', t)));
  main.append(meta, el('div', 'title', it.title));
  if (it.memo) main.appendChild(el('div', 'memo', it.memo));
  if (isActive) {
    main.appendChild(progressBlock(st));
    if (!st.short) main.appendChild(goButtons(d, it, visible, st));
  }
  c.appendChild(main);

  if (isActive) {
    // 読む順番の上下 (設定でオフにできる)。表示中の並びの端では押せない
    const i = visible.indexOf(it);
    if (arrowsOn(d) && sortMode(d) === 'manual' && visible.length > 1) {
      const mv = el('div', 'move');
      const up = iconBtn('up', '上へ', () => move(it.key, -1, visible));
      const down = iconBtn('down', '下へ', () => move(it.key, 1, visible));
      up.disabled = i === 0; down.disabled = i === visible.length - 1;
      up.dataset.move = 'up'; down.dataset.move = 'down'; up.dataset.key = down.dataset.key = it.key; // 動かしたあと、同じボタンへフォーカスを戻す
      mv.append(up, down);
      c.appendChild(mv);
    }
    const more = iconBtn('more', 'メニュー', () => openSheet(it.key));
    more.dataset.key = it.key; // シートを閉じたあと、描き直したこのボタンへフォーカスを戻す
    c.appendChild(more);
  } else {
    c.appendChild(iconBtn('undo', '未読に戻す', () => restore(it.key)));
  }
  return c;
}

function emptyState(icon, title, text, button) {
  const e = el('div', 'empty');
  const i = el('div', 'ico'); i.innerHTML = svg(icon);
  e.append(i, el('h2', '', title), el('p', '', text));
  if (button) e.appendChild(button);
  return e;
}

// 絞り込みのチップを fl に足す関数 (label, 選択中か, 押したとき, サイトの点の色) を返す
const chipAdder = (fl) => (label, on, fn, dot) => {
  const b = setPressed(el('button', 'chip' + (on ? ' on' : '')), on);
  if (dot) b.appendChild(el('span', 'dot ' + dot));
  b.appendChild(document.createTextNode(label)); b.onclick = fn; fl.appendChild(b);
};
