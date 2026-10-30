// ---- データの管理: バックアップ ----
// ファイルの形と項目は core/backup.js の NH.BACKUP_PARTS / NH.backupJson
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
    box.appendChild(switchRow(label, exportSel.has(k), (on) => { on ? exportSel.add(k) : exportSel.delete(k); paintExport(); }, BACKUP_COUNT[k](d)));
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
  ask(names + ' を、バックアップの内容 (' + detail + ') に置き換えます。いまの内容は消えます。アプリを閉じるまでは、設定の「復元前の状態に戻す」で取り消せます。' + (bad ? '\n読めないファイルが ' + bad + ' 個ありました。' : ''), '置き換える', () => {
    const stashed = stashBeforeRestore(keys, names);
    NH.update((d) => {
      keys.forEach((k) => {
        const b = BACKUP.find((x) => x[0] === k);
        let v = parts[k];
        if (k === 'list') v = v.filter((x) => x && typeof x === 'object' && typeof x.key === 'string');
        if (k === 'history') v = v.filter((x) => x && typeof x === 'object');
        if (k === 'settings') v = NH.restoredSettings(v); // 初回の画面をもう一度出さない・アイコンの「後で」の予約は戻さない
        d[b[2]] = v;
      });
    }, { force: true }); // 保存データが読めなくなっていても、バックアップで置き換えられるように (読めなかった中身は unreadableCopy に残る)
    flashNext(names + ' を読み込みました' + (stashed ? '' : ' (大きすぎて、取り消すための控えは作れませんでした)'));
    location.replace('settings.html'); // 設定をメモリに持っているので、読み直す。「読み込む」と「復元前の状態に戻す」のあるトップへ
  });
};

// ---- 復元の取り消し: 置き換える前の中身を sessionStorage に控え (アプリを閉じるまで)、「復元前の状態に戻す」で戻す ----
// 控え = {at, names, keys (置き換える保存データの項目名), data (そのうち、置き換える前にあった項目の中身)}。
// 前に無かった項目は data に入らないので、戻すときは keys のうち data に無いものを消す
const UNDO_KEY = 'nh-restore-undo';
function stashBeforeRestore(keys, names) {
  try {
    const d = load(), data = {};
    const dataKeys = keys.map((k) => BACKUP.find((x) => x[0] === k)[2]);
    dataKeys.forEach((key) => { if (d[key] !== undefined) data[key] = d[key]; });
    sessionStorage.setItem(UNDO_KEY, JSON.stringify({ at: Date.now(), names, keys: dataKeys, data }));
    return true;
  } catch (e) { // 大きすぎて控えられないときは、取り消しの行を出さない (古い控えも消す。古い控えで戻すと、今回の復元の前に戻らない)
    dropUndo();
    return false;
  }
}
// 控えを捨てる (戻したあと・控えを作れなかったとき・すべてのデータを削除したとき。消したデータを控えから戻せないように)
function dropUndo() {
  try { sessionStorage.removeItem(UNDO_KEY); } catch (e) { /* 控えなし */ }
}
// 控えを保存データに戻す (純粋な関数。d を書き換える)
function applyUndo(d, u) {
  (u.keys || Object.keys(u.data)).forEach((key) => { if (Object.hasOwn(u.data, key)) d[key] = u.data[key]; else delete d[key]; });
}
const pendingUndo = () => { try { return JSON.parse(sessionStorage.getItem(UNDO_KEY) || 'null'); } catch (e) { return null; } };
function paintUndo() {
  const u = pendingUndo(), b = $('undoRestoreBtn');
  b.hidden = !u;
  if (u) $('undoRestoreWhen').textContent = new Date(u.at).toLocaleString('ja-JP', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' }) + ' の復元';
}
$('undoRestoreBtn').onclick = () => {
  const u = pendingUndo();
  if (!u) return paintUndo();
  ask(u.names + ' を、復元する前の内容に戻します。復元したあとに変えた分は消えます。', '戻す', () => {
    NH.update((d) => applyUndo(d, u), { force: true });
    dropUndo();
    flashNext(u.names + ' を復元前の内容に戻しました');
    location.replace('settings.html');
  });
};
paintUndo();
