// ---- 読書の統計 (core.js の NH.noteRead が数え、NH.statsSummary が集計する。計算は core.js でテスト済み) ----
// 話の最後まで読んだときに 1 話。記録は端末の中だけ
const fmtChars = (n) => (n >= 10000 ? '約 ' + (Math.round(n / 1000) / 10) + ' 万字' : n.toLocaleString('ja-JP') + ' 字');
const statsTitle = (d, key) => {
  const w = (d.readLater || []).find((x) => x.key === key) || (d.history || []).find((x) => x.key === key);
  return (w && w.title) || key;
};
function paintStatsRow() { // トップの行の値: 今日の話数
  const e = NH.statsOn(load()) ? NH.statsSummary(load()).today.e : 0;
  $('v-stats').textContent = !NH.statsOn(load()) ? '記録しない' : e ? '今日 ' + e + ' 話' : '';
}
function paintStats() {
  const d = load(), s = NH.statsSummary(d);
  const amount = (label, x, works) => {
    const c = el('div', 'cell');
    const v = el('span', 'value', x.e + ' 話 ・ ' + fmtChars(x.c) + (works ? '\n' + x.works + ' 作品' : ''));
    v.style.cssText = 'white-space:pre-line;text-align:right'; // 作品数は 2 行目 (… で切らない)
    c.append(el('span', 'text', label), v);
    return c;
  };
  const t = $('statsTable'); t.innerHTML = '';
  t.append(amount('今日', s.today, true), amount('7 日', s.week, true), amount('30 日', s.month, true), amount('全期間', s.all, false));
  $('stNow').textContent = s.streak.now + ' 日';
  $('stBest').textContent = s.streak.best + ' 日';
  // 棒グラフ (画面の読み上げには、数字の一覧を 1 つの文で渡す)
  const max = Math.max(1, ...s.last14.map((x) => x.e));
  const label = (x) => { const [, m, dd] = x.day.split('-').map(Number); return m + '/' + dd; };
  const chart = $('statsChart'); chart.innerHTML = '';
  chart.setAttribute('aria-label', '直近 14 日に読んだ話数: ' + s.last14.map((x) => label(x) + ' ' + x.e + ' 話').join('、'));
  const bars = el('div', 'bars'), labels = el('div', 'bars-labels');
  bars.setAttribute('aria-hidden', 'true'); labels.setAttribute('aria-hidden', 'true');
  s.last14.forEach((x, i) => {
    const col = el('div', 'col'), bar = el('i');
    bar.style.height = Math.max(3, Math.round((x.e / max) * 80)) + 'px';
    if (!x.e) bar.className = 'zero';
    if (x.e) col.append(el('b', '', String(x.e)));
    col.append(bar); bars.append(col);
    labels.append(el('span', '', i % 2 === 1 || i === 13 ? label(x) : ''));
  });
  chart.append(bars, labels);
  const top = $('statsTop'); top.innerHTML = '';
  if (!s.top.length) { const c = el('div', 'cell', 'まだ記録がありません'); c.style.cssText = 'color:var(--sub);font-size:14px'; top.append(c); }
  s.top.forEach((x) => {
    const c = el('div', 'cell'), name = el('span', 'text', statsTitle(d, x.key));
    name.style.cssText = 'font-size:15px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap';
    c.append(name, el('span', 'value', x.n + ' 話')); top.append(c);
  });
  const since = (d.readStats || {}).since;
  $('statsSince').textContent = since ? fmtTime(since) + ' から記録しています' : '';
  $('statsReset').disabled = !(d.readStats && (s.all.e > 0));
}
ON_SHOW.stats = paintStats; // この画面を開いたとき、最新にする
$('statsReset').onclick = () => ask('読書の統計の記録をすべて消します。リスト・履歴・読書位置は消えません。', '消す', () => { NH.update((d) => { delete d.readStats; }); paintStats(); paintStatsRow(); });
EDITION_PAINT.push(paintStatsRow);
