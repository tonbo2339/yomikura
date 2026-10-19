// list.html: 作品の操作シート (メモ・ジャンル・読了など)
// ---- 操作シート ----
function openSheet(key) {
  const d = load();
  const it = d.readLater.find((x) => x.key === key);
  if (!it) return;
  const sh = $('sheet'); sh.innerHTML = '';
  ui.sheetKey = key;
  sh.appendChild(el('div', 'grip'));
  sh.appendChild(el('h2', '', it.title));
  const sub = el('div', 'sheet-sub');
  sub.append(el('span', 'dot ' + it.site), document.createTextNode(NH.siteLabel(it) + ' ・ ' + (kindOf(it) === 'update' ? '未読の更新' : '後で読む')));
  sh.appendChild(sub);

  const acts = el('div', 'actions');
  // 操作の前に書きかけのメモとジャンルを保存する (後にすると「元に戻す」の表示が消えるため先に)
  const act = (icon, label, fn, into = acts) => {
    const b = el('button', 'action'); b.innerHTML = svg(icon); b.appendChild(el('span', '', label));
    b.onclick = () => { closeSheet(true); fn(); };
    into.appendChild(b);
  };
  if (sortMode(d) === 'manual') act('top', '先頭へ', () => toTop(key)); // ほかの並べ替えでは、動かしても表示が変わらないので出さない
  act('check', '読了にする', () => markDone(key));
  act('swap', kindOf(it) === 'update' ? '後で読むへ' : '未読へ', () => switchKind(key));
  sh.appendChild(acts);

  // オフライン保存: 保存してあれば「オフラインで読む」、無ければ「オフラインに保存」。どちらも追加・更新の保存ができる
  const workUrl = NH.workUrl(it.site, it.id, it.url);
  if (NH.off.available() && workUrl) {
    // 話が 1 つも無い (保存が途中で止まった・空の) ときは、読むボタンを出さず「オフラインに保存」にする
    const savedEps = ((NH.off.meta(it.site, it.id) || {}).eps || []).length;
    const row = el('div', 'actions');
    if (savedEps) act('book', 'オフラインで読む (' + savedEps + '話)', () => (location.href = 'offline.html?w=' + encodeURIComponent(key)), row);
    act('download', savedEps ? '追加・更新を保存' : 'オフラインに保存', () => openUrl(workUrl + '#nh-save'), row);
    sh.appendChild(row);
  }

  sh.appendChild(el('div', 'field-label', 'メモ'));
  const ta = el('textarea'); ta.placeholder = '気になった点、おすすめされた理由など'; ta.value = it.memo || '';
  sh.appendChild(ta);
  sh.appendChild(el('div', 'field-label', 'ジャンル'));
  const inp = el('input'); inp.type = 'text'; inp.placeholder = '自由入力、または下から選択'; inp.setAttribute('list', 'tagList'); inp.value = it.tag || '';
  sh.appendChild(inp);
  const row = el('div', 'tag-row');
  PRESET_TAGS.forEach((t) => {
    const b = el('button', 'chip' + (inp.value === t ? ' on' : ''), t);
    b.onclick = () => { inp.value = inp.value === t ? '' : t; row.querySelectorAll('.chip').forEach((x) => x.classList.toggle('on', x.textContent === inp.value)); };
    row.appendChild(b);
  });
  inp.oninput = () => row.querySelectorAll('.chip').forEach((x) => x.classList.toggle('on', x.textContent === inp.value.trim()));
  sh.appendChild(row);

  const dr = el('div', 'done-row');
  const del = el('button', 'btn danger', '削除'); del.onclick = () => { remove(key); closeSheet(); };
  const ok = el('button', 'btn primary', '保存');
  ok.onclick = () => closeSheet(true);
  dr.append(del, ok);
  sh.appendChild(dr);

  // 外側のタップや「戻る」で閉じたときも、メモとジャンルは保存する
  ui.sheetSave = () => {
    if (ta.value.trim() !== (it.memo || '') || inp.value.trim() !== (it.tag || '')) patch(key, (x) => { x.memo = ta.value.trim(); x.tag = inp.value.trim(); });
  };
  sheetShow(sh, it.title, () => closeSheet(true));
  history.pushState({ nhSheet: true }, ''); // Android の「戻る」でシートだけ閉じるため
}
// シートを閉じる。履歴に積んだ分を戻し、popstate で実際に隠す
function closeSheet(saveEdits) {
  if (saveEdits && ui.sheetSave) ui.sheetSave();
  ui.sheetSave = null;
  if (history.state && history.state.nhSheet) history.back(); else hideSheet();
}
function hideSheet() {
  if (ui.sheetSave) { ui.sheetSave(); ui.sheetSave = null; }
  const key = ui.sheetKey; ui.sheetKey = null;
  sheetHide($('sheet')); render();
  // render() で一覧を作り直すので、シートを開いたカードのメニューボタンへフォーカスを戻す (カードが無くなっていれば戻さない)
  if (key && document.activeElement === document.body) {
    const b = [...$('active').querySelectorAll('[data-key]')].find((x) => x.dataset.key === key);
    if (b) b.focus({ preventScroll: true });
  }
}
window.addEventListener('popstate', hideSheet);
