// ---- 裏での確認と通知 (時刻の計算・アラーム・通知は BgCheck.kt) ----
const BG_DEFAULT = { on: false, mode: 'interval', hours: 3, quietOn: true, quietFrom: '00:00', quietTo: '07:00', times: ['07:30', '21:00'], days: [0, 1, 2, 3, 4, 5, 6] };
const BG_HOURS = [1, 2, 3, 4, 6, 8, 12, 24];
const WEEK = ['日', '月', '火', '水', '木', '金', '土'];
// 設定の写し (配列も複製)。BG_DEFAULT や NH.load() の保存データの配列は共有物なので、書き換えない。変えるときは bgSet
const bgConf = () => {
  const c = Object.assign({}, BG_DEFAULT, S.bgCheck);
  c.times = (Array.isArray(c.times) ? c.times : BG_DEFAULT.times).slice();
  c.days = (Array.isArray(c.days) ? c.days : BG_DEFAULT.days).slice();
  return c;
};
function bgSet(patch) { S.bgCheck = Object.assign(bgConf(), patch); commit(); }
const bgPlan = () => { try { return JSON.parse(NHStore.bgPlan()); } catch (e) { return null; } };
const timeInput = (value, label, onChange) => {
  const t = el('input', 'bg-time'); t.type = 'time'; t.value = value; t.setAttribute('aria-label', label);
  t.onchange = () => { if (t.value) onChange(t.value); };
  return t;
};
function paintBg() {
  const c = bgConf();
  $('bgOn').checked = !!c.on;
  $('bgBox').hidden = !c.on;
  // ログインしているサイトが無いときは、押せなくして理由を出す (サイトを 1 つも選んでいないときは、bgNone が説明する)
  const need = !activeAny();
  $('bgToggleGroup').inert = need;
  $('bgBox').inert = need;
  const collectAny = NH.collectAny({ settings: S });
  $('bgNone').hidden = collectAny;
  $('bgNeedLogin').hidden = !need || !collectAny;
  const box = $('bgRules'); box.innerHTML = '';
  box.appendChild(segRow([['interval', '間隔で'], ['times', '時刻で']], c.mode, (k) => bgSet({ mode: k })));
  if (c.mode === 'interval') {
    box.appendChild(selectRow('間隔', BG_HOURS.map((h) => [h, h + ' 時間ごと']), c.hours, (v) => bgSet({ hours: Number(v) })));
    const q = el('label', 'cell'); const qs = el('input', 'switch'); qs.type = 'checkbox'; qs.checked = !!c.quietOn;
    qs.onchange = () => bgSet({ quietOn: qs.checked });
    q.append(el('span', 'text', '確認しない時間帯'), qs); box.appendChild(q);
    if (c.quietOn) {
      const r = el('div', 'cell bg-range');
      r.append(timeInput(c.quietFrom, '確認しない時間帯の開始', (v) => bgSet({ quietFrom: v })), el('span', '', 'から'), timeInput(c.quietTo, '確認しない時間帯の終了', (v) => bgSet({ quietTo: v })), el('span', '', 'まで'));
      r.style.cssText = 'gap:8px;justify-content:center'; box.appendChild(r);
    }
  } else {
    c.times.forEach((t, i) => {
      const r = el('div', 'cell'); r.appendChild(el('span', 'text', '時刻 ' + (i + 1)));
      r.appendChild(timeInput(t, '時刻 ' + (i + 1), (v) => {
        // 確認の時刻どうしは 30 分以上あける (サイトへのアクセスを控えるため)。断ったときは、入力欄を元の時刻に戻す
        if (!NH.timeSlotOk(c.times, i, v)) { snack('確認の時刻は、ほかの時刻と ' + NH.BG_MIN_GAP + ' 分以上あけてください'); paintBg(); return; }
        bgSet({ times: c.times.map((x, j) => (j === i ? v : x)) });
      }));
      if (c.times.length > 1) { const d = el('button', 'bg-del', '削除'); d.setAttribute('aria-label', '時刻 ' + (i + 1) + ' を削除'); d.onclick = () => bgSet({ times: c.times.filter((_, j) => j !== i) }); r.appendChild(d); }
      box.appendChild(r);
    });
    const add = el('button', 'cell accent'); add.appendChild(el('span', 'text', '＋ 時刻を追加'));
    add.onclick = () => {
      const t = NH.freeTimeSlot(c.times); // ほかの時刻と 30 分以上あいている最初の時刻
      if (!t) return snack('追加できる時刻がありません');
      bgSet({ times: [...c.times, t] });
    };
    box.appendChild(add);
  }
  const days = $('bgDays'); days.innerHTML = '';
  WEEK.forEach((w, i) => {
    const b = setPressed(el('button', c.days.includes(i) ? 'on' : '', w), c.days.includes(i));
    b.setAttribute('aria-label', w + '曜日');
    b.onclick = () => bgSet({ days: c.days.includes(i) ? c.days.filter((x) => x !== i) : [...c.days, i].sort() });
    days.appendChild(b);
  });
  const p = bgPlan();
  $('bgSlots').textContent = p && p.slots.length ? p.slots.join(' / ') : 'ありません';
  $('bgNext').textContent = p && p.next ? new Date(p.next).toLocaleString('ja-JP', { month: 'numeric', day: 'numeric', weekday: 'short', hour: '2-digit', minute: '2-digit' })
    : !c.days.length ? '曜日を選んでください' : '—';
  $('bgNotifyWarn').hidden = !p || p.notify;
}
// 予定 (次の確認) と通知の許可は、アプリ側で決まったあとにここが呼ばれる
window.nhPlanChanged = () => { if (curPage === 'bg') paintBg(); }; // 画面を開くときに読み直すので、開いていなければ何もしない
$('bgOn').onchange = (e) => {
  bgSet({ on: e.target.checked });
  if (e.target.checked && window.NHStore && NHStore.requestNotify) NHStore.requestNotify();
};
$('bgNotifyAsk').onclick = () => { if (window.NHStore && NHStore.requestNotify) NHStore.requestNotify(); };
$('bgRunNow').onclick = () => { if (window.NHStore && NHStore.bgRunNow) { NHStore.bgRunNow(); snack('裏で確認を始めました'); } };
