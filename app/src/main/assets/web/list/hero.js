// list.html: 上の「さっきまで読んでいた作品」のカード
function lastRead(d, items) {
  let best = null;
  let at = 0;
  items.forEach((it) => {
    const p = progressOf(d, it);
    if (p && (!p.done || p.next) && p.at > at) { best = it; at = p.at; }
  });
  return best;
}

// others = このカードの作品のほかに、リストにある作品の数
// カードの地をタップすると続きから。連載なら「続きから」と「目次」のボタンも出す (ボタンを入れるので、カード自体は button 要素にしない)
function heroCard(d, it, others, list) {
  const st = NH.readState(d, it);
  const h = el('div', 'hero');
  asButton(h, () => openWork(d, it, list));
  h.setAttribute('aria-label', (st.started ? '続きから読む: ' : '次に読む: ') + it.title);
  const eyebrow = el('div', 'eyebrow');
  eyebrow.append(el('span', 'dot ' + it.site), document.createTextNode((st.started && !st.finished ? '続きから読む' : '次に読む') + ' ・ ' + NH.siteLabel(it)));
  h.append(eyebrow, el('div', 'title', it.title));
  h.appendChild(progressBlock(st, 'pg hero-pg'));
  const extra = [st.unread && !st.short ? '未読' + st.unread + '話' : '', STATUS_NAME[it.status] || '', others > 0 ? 'ほか ' + others + ' 件' : ''].filter(Boolean);
  if (extra.length) {
    const metaBox = el('div', 'meta');
    extra.forEach((t) => metaBox.appendChild(el('span', '', t))); // 区切りの「・」は CSS。折り返しは項目の切れ目でだけ起きる
    h.appendChild(metaBox);
  }
  if (!st.short) h.appendChild(goButtons(d, it, list, st));
  else { const play = el('div', 'play'); play.innerHTML = svg('play'); h.appendChild(play); }
  return h;
}
