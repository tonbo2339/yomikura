// Yomikura 共通基盤 (13): 読書の統計
window.NH.define('stats', function (NH) {
  NH.need('store');
  const isObj = NH.isObj;
  // ---- 読書の統計 ----
  // readStats: {since: 記録を始めた時刻, old: {e, c} (古くて捨てた日の合計), days: {'YYYY-MM-DD': {e: 読了した話数, c: 文字数, w: {作品のキー: 話数}}}}
  // 話の最後まで読んだとき (読書位置の done。ui.js) に 1 話として数える。日付は端末の時刻 (ローカル)。
  // days は直近 STATS_DAYS 日だけ残し、それより古い日は old に足して捨てる。設定 stats === false なら記録しない。端末の中だけ (バックアップには入れない)
  NH.STATS_DAYS = 400;
  const p2 = (n) => String(n).padStart(2, '0');
  NH.dayKey = (t) => { const d = new Date(t); return d.getFullYear() + '-' + p2(d.getMonth() + 1) + '-' + p2(d.getDate()); };
  // 'YYYY-MM-DD' の n 日前後 (夏時間の切り替えでずれないよう、昼の 12 時で数える)
  const shiftDay = (key, n) => { const [y, m, d] = key.split('-').map(Number); return NH.dayKey(new Date(y, m - 1, d + n, 12).getTime()); };
  NH.statsOn = (d) => NH.flag(d, 'stats');
  NH.noteRead = function (d, key, chars, now = Date.now()) {
    if (!NH.statsOn(d) || typeof key !== 'string') return;
    const s = d.readStats = isObj(d.readStats) ? d.readStats : {};
    if (!s.since) s.since = now;
    s.days = isObj(s.days) ? s.days : {};
    const k = NH.dayKey(now);
    const day = s.days[k] = isObj(s.days[k]) ? s.days[k] : { e: 0, c: 0, w: {} };
    day.w = isObj(day.w) ? day.w : {};
    day.e = (day.e || 0) + 1;
    day.c = (day.c || 0) + Math.max(0, Math.round(Number(chars)) || 0);
    day.w[key] = (day.w[key] || 0) + 1;
    const days = Object.keys(s.days).sort();
    while (days.length > NH.STATS_DAYS) {
      const o = days.shift(), x = s.days[o] || {};
      s.old = { e: ((s.old || {}).e || 0) + (x.e || 0), c: ((s.old || {}).c || 0) + (x.c || 0) };
      delete s.days[o];
    }
  };
  // 集計。now の日を「今日」とする。戻り値:
  //   today / week (今日を含む 7 日) / month (30 日): {e: 話数, c: 文字数, works: 作品数}、all: {e, c, since}
  //   streak: {now: 今日 (まだなら昨日) までの連続日数, best: 最長}、last14: [{day, e, c}] 古い順、top: [{key, n}] 直近 30 日に多く読んだ作品 (5 件まで)
  NH.statsSummary = function (d, now = Date.now()) {
    const s = isObj(d.readStats) ? d.readStats : {}, days = isObj(s.days) ? s.days : {};
    const today = NH.dayKey(now);
    const sum = (n) => {
      const out = { e: 0, c: 0, works: 0 }, keys = new Set();
      for (let i = 0; i < n; i++) {
        const x = days[shiftDay(today, -i)];
        if (!x) continue;
        out.e += x.e || 0; out.c += x.c || 0;
        Object.keys(x.w || {}).forEach((k) => keys.add(k));
      }
      out.works = keys.size;
      return out;
    };
    const all = { e: (s.old || {}).e || 0, c: (s.old || {}).c || 0, since: s.since || 0 };
    Object.values(days).forEach((x) => { all.e += (x && x.e) || 0; all.c += (x && x.c) || 0; });
    // 連続日数: 今日まだ読んでいなければ、昨日までの連続を数える (今日これから読めば続く)
    const has = (k) => !!days[k] && days[k].e > 0;
    let cur = 0, k = has(today) ? today : shiftDay(today, -1);
    while (has(k)) { cur++; k = shiftDay(k, -1); }
    let best = 0, run = 0, prev = null;
    Object.keys(days).filter(has).sort().forEach((dk) => { run = prev && shiftDay(prev, 1) === dk ? run + 1 : 1; best = Math.max(best, run); prev = dk; });
    const last14 = [];
    for (let i = 13; i >= 0; i--) { const dk = shiftDay(today, -i), x = days[dk] || {}; last14.push({ day: dk, e: x.e || 0, c: x.c || 0 }); }
    const per = {};
    for (let i = 0; i < 30; i++) { const x = days[shiftDay(today, -i)]; if (x) Object.entries(x.w || {}).forEach(([key, n]) => { per[key] = (per[key] || 0) + n; }); }
    const top = Object.entries(per).sort((a, b) => b[1] - a[1] || (a[0] < b[0] ? -1 : 1)).slice(0, 5).map(([key, n]) => ({ key, n }));
    return { today: sum(1), week: sum(7), month: sum(30), all, streak: { now: cur, best }, last14, top };
  };
});
