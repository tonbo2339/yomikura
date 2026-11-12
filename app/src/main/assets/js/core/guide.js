// Yomikura 共通基盤: 体験型のガイド (さわって覚える 4 ステップ) の手順と進み具合。表示は guide-bar.js
// 保存は settings.guide = {step: 0〜3 (いまの手順), base: 始めたときの「後で読む」の数} / {step: 'done'} (終えた) / {step: 'off'} (やめた)。
// 手順は、ユーザーがその操作をしたことを、保存データとページの様子 (obs) から見分けて自動で進む。画面に依存しないので、ここだけでテストできる。
window.NH.define('guide', function (NH) {
  const WANT = 2; // 「後で読む」に足してもらう数

  // 手順の定義。文はここだけを直す。text(ctx) の ctx = {added (足した数)}。done(d, g, obs) = その手順を終えたか
  // obs = {onRanking (ランキングのページ)・onList (アプリのリスト)・onWork (作品・話のページ)}
  NH.GUIDE_STEPS = [
    { text: () => '上のタブ (なろう・ハーメルン・カクヨム) を押して、メニューの「ランキング」を開いてみましょう', done: (d, g, o) => !!o.onRanking },
    { text: (c) => '気になる作品の「＋ 後で読む」を ' + WANT + ' つ押してみましょう (いま ' + Math.min(c.added, WANT) + ' / ' + WANT + ')', done: (d, g) => NH.guideAdded(d, g) >= WANT },
    { text: () => '上のタブバーの、本のマーク (リスト) を押して、足した作品を見てみましょう', done: (d, g, o) => !!o.onList },
    { text: () => '作品を押すと、続きから読めます', done: (d, g, o) => !!o.onWork },
  ];
  NH.GUIDE_DONE_TEXT = 'ガイドはおしまいです。ヒントや使い方は、設定の「使い方」からいつでも見られます';

  const later = (d) => (d.readLater || []).filter((x) => x && NH.kindOf(x) === 'later').length;
  const stateOf = (d) => (d && d.settings && d.settings.guide) || null;

  // ガイドが進行中か (始めていて、終えても、やめてもいない)
  NH.guideActive = (d) => { const g = stateOf(d); return !!g && typeof g.step === 'number' && g.step >= 0 && g.step < NH.GUIDE_STEPS.length; };
  // 始めてから「後で読む」に増えた数
  NH.guideAdded = (d, g) => Math.max(0, later(d) - ((g || stateOf(d) || {}).base || 0));
  // いまの手順の番号 (進行中でなければ -1)
  NH.guideStep = (d) => (NH.guideActive(d) ? stateOf(d).step : -1);
  // いまの手順の文 (進行中でなければ '')
  NH.guideText = (d) => (NH.guideActive(d) ? NH.GUIDE_STEPS[stateOf(d).step].text({ added: NH.guideAdded(d) }) : '');
  // obs を見て、進んだあとの手順の番号を返す (保存はしない)。終えたら GUIDE_STEPS.length。進行中でなければ -1
  NH.guideNext = (d, obs) => {
    const g = stateOf(d);
    if (!NH.guideActive(d)) return -1;
    let step = g.step;
    while (step < NH.GUIDE_STEPS.length && NH.GUIDE_STEPS[step].done(d, g, obs || {})) step++;
    return step;
  };
  // 以下は NH.update の中で呼ぶ (d を書き換える)
  NH.guideStart = (d) => { d.settings = d.settings || {}; d.settings.guide = { step: 0, base: later(d) }; };
  NH.guideSet = (d, step) => { // guideNext の結果を書く
    const g = stateOf(d);
    if (!NH.guideActive(d)) return;
    d.settings.guide = step >= NH.GUIDE_STEPS.length ? { step: 'done' } : Object.assign({}, g, { step });
  };
  NH.guideStop = (d) => { d.settings = d.settings || {}; d.settings.guide = { step: 'off' }; };
});
