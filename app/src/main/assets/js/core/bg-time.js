// Yomikura 共通基盤 (6): 裏での確認の「時刻で」確認する時刻の決まり
window.NH.define('bg-time', function (NH) {
  // 裏での確認の「時刻で」確認する時刻どうしは、30 分以上あける (サイトへのアクセスを控えるため。Kotlin の BgCheck.MIN_GAP と同じ)。0:00 をまたぐ差も測る
  NH.BG_MIN_GAP = 30;
  NH.timeMinutes = (hhmm) => { const m = /^(\d{1,2}):(\d{2})$/.exec(String(hhmm || '')); return m && +m[1] < 24 && +m[2] < 60 ? +m[1] * 60 + +m[2] : null; };
  NH.timeGap = (a, b) => { const x = NH.timeMinutes(a), y = NH.timeMinutes(b); if (x === null || y === null) return Infinity; const d = Math.abs(x - y); return Math.min(d, 1440 - d); };
  // times の i 番目を value にしてよいか (i が範囲外 = 追加するとき)。ほかのどの時刻とも 30 分以上あいていること
  NH.timeSlotOk = (times, i, value) => times.every((t, j) => j === i || NH.timeGap(t, value) >= NH.BG_MIN_GAP);
  // 追加する時刻の初期値: 12:00 から 30 分刻みで、あいている最初の時刻 (あいていなければ null)
  NH.freeTimeSlot = (times) => {
    for (let k = 0; k < 48; k++) {
      const m = (720 + k * 30) % 1440;
      const t = String(Math.floor(m / 60)).padStart(2, '0') + ':' + String(m % 60).padStart(2, '0');
      if (NH.timeSlotOk(times, -1, t)) return t;
    }
    return null;
  };
});
