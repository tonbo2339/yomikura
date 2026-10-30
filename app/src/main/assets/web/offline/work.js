// offline.html: 作品の目次
// ---- 作品の目次 ----
async function work(key) {
  const { site, id } = NH.splitKey(key);
  let meta;
  try { meta = await source.meta(site, id); } catch (e) { return showError(e.message); }
  if (!meta) return go('offline.html');
  const eps = meta.eps || [];
  const d = load();
  const p = (d.progress || {})[key];
  root.innerHTML = '';
  root.appendChild(backNav());
  const head = el('header', 'page-head');
  const hd = el('div'); hd.append(el('h1', '', meta.title), el('div', 'sub', NH.siteLabel(meta) + ' ・ ' + eps.length + (meta.total ? ' / ' + meta.total : '') + ' 話を' + (SRC ? EXT.srcLabel : '') + '保存'));
  head.appendChild(hd); root.appendChild(head);
  head.querySelector('h1').style.fontSize = '24px';

  // 続きから: 読書位置の話が保存してあればそこ、読了なら次の話、無ければ最初
  const cur = p && eps.findIndex((e) => e.ep === p.ep);
  let start = 0;
  if (p && cur >= 0) start = p.done ? Math.min(eps.length - 1, cur + 1) : cur;
  const row = el('div', 'actions-row');
  if (eps.length) {
    const rd = el('button', 'btn primary', p && cur >= 0 ? '続きから読む' : '最初から読む');
    rd.onclick = () => (location.href = here(key, eps[start].ep));
    row.appendChild(rd);
  }
  if (!SRC) { // 外の作品は、読むのと消すだけ (保存し直すのは端末の作品で)
    const more = el('button', 'btn tonal', '追加・更新を保存');
    more.onclick = () => openUrl(meta.url + '#nh-save');
    const del = el('button', 'btn danger', '削除');
    del.onclick = () => ask('「' + meta.title + '」を削除', '削除', () => { NH.off.remove(site, id); go('offline.html'); }, 'この作品の保存データを端末から消します。');
    row.append(more);
    EXT.tocButtons(site, id, meta.title).forEach((b) => row.appendChild(b));
    row.appendChild(del);
  } else {
    EXT.remoteTocButtons(site, id, meta.title).forEach((b) => row.appendChild(b));
  }
  root.appendChild(row);

  const g = addGroup(root, '保存した話');
  eps.forEach((e, i) => {
    const c = el('button', 'cell ep');
    const t = (e.title || '').normalize('NFKC').trim();
    c.append(el('span', 'text ep-title', !t ? (i + 1) + '話' : /^\d+$/.test(t) ? t + '話' : e.title)); // 題名が番号だけ (「２」) の作品は「2話」
    if (p && p.ep === e.ep) c.appendChild(el('span', 'state', p.done ? '読了' : Math.round(p.y * 100) + '%'));
    c.onclick = () => (location.href = here(key, e.ep));
    g.appendChild(c);
  });
}
