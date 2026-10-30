// offline.html: 一覧 (保存した作品)
// ---- 一覧 ----
function library() {
  const d0 = NH.load(); // NSFW モードがオンなら、R18 の作品は一覧に出さない (保存データは消さない)
  const works = NH.off.list().filter((w) => !NH.nsfwHides(d0, w)).sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0));
  const total = works.reduce((n, w) => n + (w.bytes || 0), 0);
  root.innerHTML = '';
  const head = el('header', 'page-head');
  const hd = el('div'); hd.append(el('h1', '', 'オフライン'), el('div', 'sub', works.length + ' 作品 ・ ' + NH.fmtBytes(total)));
  head.appendChild(hd);
  root.appendChild(head);

  if (!works.length) {
    const e = el('div', 'empty');
    e.append(el('h2', '', '保存した作品はありません'), el('p', '', 'リストの作品の「⋮」から「オフラインに保存」を選ぶと、通信なしで読めるようになります。'));
    root.appendChild(e);
  } else {
    const list = el('div', 'list');
    works.forEach((w) => {
      const del = iconBtn('del', '削除', () => ask('「' + w.title + '」を削除', '削除', () => { NH.off.remove(w.site, w.id); snack('削除しました'); library(); }, 'この作品の保存データ (' + NH.fmtBytes(w.bytes || 0) + ') を端末から消します。サイトの作品や、リストの記録には影響しません。'));
      list.appendChild(workRow(w, (w.eps || []).length + (w.total ? ' / ' + w.total : '') + '話', '', [...EXT.rowButtons(w), del]));
    });
    root.appendChild(list);
  }
  const ext = EXT.section();
  if (ext) root.appendChild(ext);

  // 保存キュー (いま保存している作品・順番待ち) の画面への入り口。作品があるときは件数を出す
  const queued = NH.off.queueList().filter(NH.queueActive).length;
  const qg = el('div', 'group'); qg.style.marginTop = '16px';
  const ql = el('a', 'cell'); ql.href = 'queue.html'; ql.style.textDecoration = 'none';
  ql.append(el('span', 'text', '保存キュー'), el('span', 'value', queued ? queued + ' 作品が保存待ち' : ''));
  qg.appendChild(ql);
  root.appendChild(qg);

  // 保存の設定 (Wi-Fi のときだけ・自動で保存・縦書き) は設定の画面にある
  const sg = el('div', 'group'); sg.style.marginTop = '16px';
  const sl = el('a', 'cell'); sl.href = 'settings.html?page=offlineset'; sl.style.textDecoration = 'none';
  sl.append(el('span', 'text', '保存の設定'), el('span', 'value', settings().offlineWifiOnly ? 'Wi-Fi のみ' : ''));
  sg.appendChild(sl);
  root.appendChild(sg);
  if (works.length) {
    const g2 = el('div', 'group'); g2.style.marginTop = '16px';
    const b = el('button', 'cell danger', 'すべてのオフライン保存を削除…');
    // 消すのは、いま一覧に出ている作品だけ (NSFW モードで隠れている R18 の作品は、確認の文に数えていないので消さない)
    b.onclick = () => ask('すべて削除', 'すべて削除', () => { works.forEach((w) => NH.off.remove(w.site, w.id)); snack('削除しました'); library(); }, works.length + ' 作品 (' + NH.fmtBytes(total) + ') を端末から消します。');
    g2.appendChild(b); root.appendChild(g2);
  }
}

// 作品の行 (端末・外の作品共通)。count = 「12 / 30 話」、src = 開く元、buttons = 右端のボタン
function workRow(w, count, src, buttons) {
  const c = el('div', 'card row');
  asButton(c, () => (location.href = here(w.site + ':' + w.id, null, src)));
  const main = el('div', 'main');
  const meta = el('div', 'meta');
  const site = el('span'); site.style.cssText = 'display:inline-flex;align-items:center;gap:6px;font-weight:700';
  site.append(el('span', 'dot ' + w.site), document.createTextNode(NH.siteLabel(w)));
  meta.append(site, el('span', '', count), el('span', '', NH.fmtBytes(w.bytes || 0)), el('span', '', date(w.updatedAt || w.savedAt) + ' 保存'));
  main.append(el('div', 'title', w.title), meta);
  c.appendChild(main);
  buttons.filter(Boolean).forEach((b) => c.appendChild(b));
  return c;
}
