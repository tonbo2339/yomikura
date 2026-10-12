// ---- バックアップの画面の共通部品 (どの送り先の画面でも使う) ----
// 送り先ごとの画面が、Kotlin の窓口 (setAuto / setKeep / setEnabled) と状態 (state() の JSON) を渡して使う。
// 状態 st は BackupPrefs.stateJson の形 {auto, hours, keep: {種類: 数}, enabled: {種類: bool}, lastOkAt, lastAt, lastOk, lastMsg, nextAt, busy}。
// 窓口 api は { setAuto(on, hours), setKeep(kind, n), setEnabled(kind, on) }。repaint は、変えたあとに画面を描き直す関数
const BACKUP_HOURS = [6, 12, 24]; // BackupSchedule.HOURS と同じ
const BACKUP_KEEP_PRESETS = [[3, '3 個'], [5, '5 個'], [10, '10 個']];
const BACKUP_KEEP_MAX = 1000; // BackupSchedule.KEEP_MAX と同じ
const BACKUP_KIND_NAME = { settings: '設定・ミュート', data: 'リスト・読書位置', history: '閲覧履歴' }; // BackupPayload の種類の名前
const fmtTime = (t) => new Date(t).toLocaleString('ja-JP', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' });

// 自動の間隔 (時間) の選択
function paintBackupHours(box, st, api, repaint) {
  pickList(box, BACKUP_HOURS.map((h) => [h, h + ' 時間']), st.hours, (h) => { api.setAuto(true, h); repaint(); });
}

// 残す数が何日分か (送る間隔から)。例: 10 個・12 時間ごと → 約 5 日分
const backupKeepSpan = (n, hours) => { const days = n * hours / 24; return days >= 1 ? '約 ' + (Math.round(days * 10) / 10) + ' 日分' : '約 ' + Math.round(n * hours) + ' 時間分'; };
const backupKeepOpen = {}; // 送り先 + 種類 → 「数を自分で選ぶ」を開いているか (描き直しても閉じないように覚える)

// 種類ごとのオン・オフと、残す数。選べるのは 3 / 5 / 10 と数の入力 (1〜BACKUP_KEEP_MAX)。
// 減らすとき・1 個にするときは、次の送信で古いものが消えることを確かめる。
// opts = { box: 描く先の要素, kind, st, api, repaint, scope: 送り先の名前 (開き具合を分ける) }
function paintBackupKeep(opts) {
  const { box, kind, st, api, repaint } = opts, key = (opts.scope || '') + ':' + kind;
  const name = (BACKUP_KIND_NAME[kind] || kind) + 'のバックアップ', cur = (st.keep || {})[kind];
  const save = (n) => {
    const apply = () => { backupKeepOpen[key] = !BACKUP_KEEP_PRESETS.some(([k]) => k === n); api.setKeep(kind, n); repaint(); };
    if (n === 1) ask(name + 'を 1 個だけ残します。壊れたデータを送ると、それまでの良いバックアップが消えて、戻せなくなります。', '1 個にする', apply);
    else if (cur === 0 || n < cur) ask(name + 'を、次の送信で新しい ' + n + ' 個だけ残します。古いものは消えます。', '減らす', apply);
    else apply();
  };
  box.innerHTML = '';
  // 送るかどうか (オフのときは残す数を出さない)
  const sw = el('label', 'cell');
  sw.appendChild(el('span', 'text', 'バックアップする'));
  const cb = el('input', 'switch'); cb.type = 'checkbox'; cb.checked = !!(st.enabled || {})[kind];
  cb.onchange = () => { api.setEnabled(kind, cb.checked); repaint(); };
  sw.appendChild(cb);
  box.appendChild(sw);
  if (!cb.checked) return;

  const known = Number.isInteger(cur); // 古い版の送り先の状態には keep が無い
  const preset = BACKUP_KEEP_PRESETS.some(([n]) => n === cur);
  const open = backupKeepOpen[key] ?? (known && !preset);
  [...BACKUP_KEEP_PRESETS, ['custom', '数を自分で選ぶ']].forEach(([k, label]) => {
    box.appendChild(checkRow(label, k === 'custom' ? open : !open && k === cur, () => {
      if (k !== 'custom') return save(k);
      backupKeepOpen[key] = true; paintBackupKeep(opts);
      box.querySelector('input.field').focus();
    }));
  });
  if (open) {
    const row = el('div', 'cell');
    row.style.cssText = 'gap:10px;padding:10px 16px';
    const inp = el('input', 'field');
    inp.type = 'number'; inp.min = '1'; inp.max = String(BACKUP_KEEP_MAX); inp.inputMode = 'numeric';
    inp.placeholder = '1〜' + BACKUP_KEEP_MAX + ' の数';
    inp.value = known && cur > 0 && !preset ? String(cur) : '';
    inp.style.cssText = 'flex:1;min-width:0';
    const ok = el('button', 'btn primary', '保存');
    ok.onclick = () => {
      const n = Number(inp.value);
      if (!Number.isInteger(n) || n < 1 || n > BACKUP_KEEP_MAX) return snack('1〜' + BACKUP_KEEP_MAX + ' の数で入れてください');
      if (n === cur) return snack('いまと同じ数です');
      save(n);
    };
    row.append(inp, ok);
    box.appendChild(row);
  }
  // 何日分残るかと、注意 (1 個 / 無制限は以前の設定として残っている場合だけ出る)
  const hours = st.hours || 12;
  const note = cur === 0 ? 'いまは無制限に残しています (以前の設定)。数を入れると、そのあとは入れた数だけ残します'
    : cur === 1 ? '1 個だけ残す設定です。壊れたデータを送ると、それまでの良いバックアップが消えて、戻せなくなります'
    : cur + ' 個残すと、' + hours + ' 時間ごとに送る場合は' + backupKeepSpan(cur, hours) + 'です';
  const memo = el('div', 'cell', note);
  memo.style.cssText = 'font-size:13px;color:var(--sub);padding:10px 16px;line-height:1.5';
  box.appendChild(memo);
}
