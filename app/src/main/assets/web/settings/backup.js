// ---- データの管理: バックアップ ----
// ファイルの形と項目は core.js の NH.BACKUP_PARTS / NH.backupJson
const BACKUP = NH.BACKUP_PARTS;
const BACKUP_COUNT = {
  settings: (d) => Object.keys(d.settings || {}).length + ' 項目',
  list: (d) => (d.readLater || []).length + ' 作品',
  progress: (d) => Object.keys(d.progress || {}).length + ' 作品',
  history: (d) => (d.history || []).length + ' 作品',
  mute: (d) => { const m = NH.muteOf(d), b = NH.blockOf(d); return (m.words.length + m.authors.length) + ' 件' + (NH.hasBlock(d) ? '・ブロック ' + (b.works.length + b.words.length + b.authors.length) + ' 件' : ''); },
};
const exportSel = new Set(BACKUP.map((b) => b[0]));
const dayStamp = () => { const t = new Date(); return t.getFullYear() + String(t.getMonth() + 1).padStart(2, '0') + String(t.getDate()).padStart(2, '0'); };
function paintExport() {
  const d = load(), box = $('exportList');
  box.innerHTML = '';
  BACKUP.forEach(([k, label]) => {
    const row = el('label', 'cell');
    const t = el('span', 'text', label); t.appendChild(el('small', '', BACKUP_COUNT[k](d)));
    const cb = el('input', 'switch'); cb.type = 'checkbox'; cb.checked = exportSel.has(k);
    cb.onchange = () => { cb.checked ? exportSel.add(k) : exportSel.delete(k); paintExport(); };
    row.append(t, cb);
    box.appendChild(row);
  });
  $('exportOne').disabled = $('exportMany').disabled = !exportSel.size || !(window.NHStore && NHStore.exportFiles);
}
const backupFile = (keys, name) => ({ name, text: NH.backupJson(load(), keys, CURRENT) });
const chosen = () => BACKUP.map((b) => b[0]).filter((k) => exportSel.has(k));
$('exportOne').onclick = () => {
  const keys = chosen(); if (!keys.length) return;
  const label = keys.length === BACKUP.length ? 'all' : keys.join('-');
  NHStore.exportFiles('one', JSON.stringify([backupFile(keys, NH.BACKUP_APP + '-' + label + '-' + dayStamp() + '.json')]));
};
$('exportMany').onclick = () => {
  const keys = chosen(); if (!keys.length) return;
  NHStore.exportFiles('many', JSON.stringify(keys.map((k) => backupFile([k], NH.BACKUP_APP + '-' + k + '-' + dayStamp() + '.json'))));
};
window.nhBackupResult = (json) => { const r = JSON.parse(json); snack(r.message); };
$('importBtn').onclick = () => { if (window.NHStore && NHStore.importFiles) NHStore.importFiles(); else snack('この版では読み込めません'); };

// 読み込み: 選んだファイル (複数可) の中身を調べ、入っている項目を確認してから置き換える
window.nhImport = (json) => {
  const parts = {}; let bad = 0;
  JSON.parse(json).forEach((text) => {
    let o = null;
    try { o = JSON.parse(text); } catch (e) { /* 壊れたファイル */ }
    if (!o || !NH.isBackupApp(o.app) || !o.parts || typeof o.parts !== 'object') { bad++; return; }
    BACKUP.forEach(([k, , , type]) => {
      const v = o.parts[k];
      if (type === 'array' ? Array.isArray(v) : (v && typeof v === 'object' && !Array.isArray(v))) parts[k] = v;
    });
  });
  const keys = BACKUP.map((b) => b[0]).filter((k) => parts[k] !== undefined);
  if (!keys.length) { snack(bad ? APP_NAME + ' のバックアップではありません' : '読み込める項目がありませんでした'); return; }
  const names = keys.map((k) => BACKUP.find((b) => b[0] === k)[1]).join('・');
  const asData = Object.fromEntries(BACKUP.map(([k, , key]) => [key, parts[k]])); // 数え方 (BACKUP_COUNT) は保存データの形で数える
  const detail = keys.map((k) => BACKUP.find((b) => b[0] === k)[1] + ' ' + BACKUP_COUNT[k](asData)).join('、');
  ask(names + ' を、バックアップの内容 (' + detail + ') に置き換えます。いまの内容は消えます。' + (bad ? '\n読めないファイルが ' + bad + ' 個ありました。' : ''), '置き換える', () => {
    NH.update((d) => {
      keys.forEach((k) => {
        const b = BACKUP.find((x) => x[0] === k);
        let v = parts[k];
        if (k === 'list') v = v.filter((x) => x && typeof x === 'object' && typeof x.key === 'string');
        if (k === 'history') v = v.filter((x) => x && typeof x === 'object');
        if (k === 'settings') v = Object.assign({}, v, { welcomed: true }); // 初回の画面をもう一度出さない
        d[b[2]] = v;
      });
    }, { force: true }); // 保存データが読めなくなっていても、バックアップで置き換えられるように (読めなかった中身は unreadableCopy に残る)
    flashNext(names + ' を読み込みました');
    location.replace('settings.html?page=data'); // 設定をメモリに持っているので、読み直す
  });
};
