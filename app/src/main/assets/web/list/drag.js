// list.html: 長押しの並べ替え・横スワイプの削除 (設定の「横にスワイプして削除」でオフにできる)
// ---- 長押しして上下へ動かす並べ替え / 横にスワイプして削除 ----
// 作品を長押しすると持ち上がり、指を上下に動かして離した場所に入る。画面の端に近づくと自動でスクロールする。
// 長押しになる前に横へ動かすと、カードが指について動き、幅の SWIPE_RATIO を越えて離すとリストから削除する (Gmail と同じ)。
// 表示中 (絞り込み後) の並びの中で入れ替え、見えていない作品の位置は変えない。
const LONG_PRESS_MS = 380;
const SWIPE_RATIO = 0.35; // 幅のこの割合を越えて離すと削除
let dragEndedAt = 0;

// 表示中の作品 (keys) の並びを新しい順にする。d.readLater の中では、表示中の作品が占めていた場所に順に入れ直す
function applyOrder(keys) {
  mutate((d) => {
    const set = new Set(keys);
    const slots = [];
    d.readLater.forEach((x, i) => { if (set.has(x.key)) slots.push(i); });
    const byKey = new Map(d.readLater.map((x) => [x.key, x]));
    keys.forEach((k, j) => { d.readLater[slots[j]] = byKey.get(k); });
  });
}

// reorder = 長押しで動かせるか (自分で並べた順で、選択中でないとき)。swipe = 横スワイプで削除できるか (設定。選択中は使わない)。まとめての削除は選択モード (select.js)
function enableDrag(c, it, visible, reorder, swipe) {
  let timer = 0, sx = 0, sy = 0, st = null, raf = 0, swiping = false, swipeDx = 0;
  c.addEventListener('contextmenu', (e) => e.preventDefault());
  if (!reorder && !swipe) return;

  const layout = (clientY) => {
    st.y = clientY;
    const dy = Math.max(st.minDy, Math.min(st.maxDy, clientY + scrollY - st.startY));
    c.style.transform = 'translateY(' + dy + 'px) scale(1.02)';
    // 持ち上げた作品の中心より上にある作品の数 = 入る位置
    const center = st.tops[st.idx] + st.hs[st.idx] / 2 + dy;
    let target = 0;
    st.tops.forEach((t, j) => { if (j !== st.idx && t + st.hs[j] / 2 < center) target++; });
    st.target = target;
    const step = st.hs[st.idx] + st.gap;
    st.cards.forEach((x, j) => {
      if (j === st.idx) return;
      const shift = st.idx < target && j > st.idx && j <= target ? -step : st.idx > target && j >= target && j < st.idx ? step : 0;
      x.style.transform = shift ? 'translateY(' + shift + 'px)' : '';
    });
  };
  // 画面の端に近いあいだは自動でスクロールする
  const autoScroll = () => {
    if (!st) return;
    const edge = 90, v = st.y < edge + 60 ? -14 : st.y > innerHeight - edge ? 14 : 0;
    if (v) { scrollBy(0, v); layout(st.y); }
    raf = requestAnimationFrame(autoScroll);
  };
  const start = () => {
    const cards = [...$('active').querySelectorAll('.item')];
    const idx = cards.indexOf(c);
    if (idx < 0) return;
    const tops = cards.map((x) => x.getBoundingClientRect().top + scrollY);
    const hs = cards.map((x) => x.offsetHeight);
    const gap = cards.length > 1 ? Math.max(0, tops[1] - (tops[0] + hs[0])) : 0;
    st = { cards, idx, tops, hs, gap, startY: sy + scrollY, y: sy, target: idx,
      minDy: tops[0] - tops[idx], maxDy: tops[cards.length - 1] + hs[cards.length - 1] - hs[idx] - tops[idx] };
    c.classList.add('dragging');
    cards.forEach((x) => { if (x !== c) x.style.transition = 'transform .15s'; });
    try { navigator.vibrate && navigator.vibrate(15); } catch (e) { /* 振動なし */ }
    raf = requestAnimationFrame(autoScroll);
  };
  const finish = (apply) => {
    clearTimeout(timer); timer = 0;
    if (!st) return;
    cancelAnimationFrame(raf);
    const { cards, idx, target } = st;
    st = null;
    dragEndedAt = Date.now();
    c.classList.remove('dragging');
    cards.forEach((x) => { x.style.transform = ''; x.style.transition = ''; });
    if (apply && target !== idx) {
      const keys = visible.map((x) => x.key);
      keys.splice(target, 0, keys.splice(idx, 1)[0]);
      applyOrder(keys);
    }
  };

  // 横スワイプ: カードが指について動き、十分動かして離すと画面の外へ出て削除される。足りなければ元へ戻る
  const swipeTo = (dx) => {
    const w = c.offsetWidth, p = Math.min(1, Math.abs(dx) / (w * SWIPE_RATIO));
    c.style.transform = 'translateX(' + dx + 'px)';
    c.style.opacity = String(1 - p * 0.4);
    const over = p >= 1;
    if (over !== c.classList.contains('removing')) {
      c.classList.toggle('removing', over);
      if (over) try { navigator.vibrate && navigator.vibrate(20); } catch (e) { /* 振動なし */ }
    }
  };
  const endSwipe = (apply) => {
    if (!swiping) return;
    swiping = false;
    dragEndedAt = Date.now();
    const dx = swipeDx;
    if (apply && Math.abs(dx) >= c.offsetWidth * SWIPE_RATIO) {
      c.style.transition = 'transform .18s, opacity .18s';
      c.style.transform = 'translateX(' + (dx > 0 ? '110%' : '-110%') + ')';
      c.style.opacity = '0';
      setTimeout(() => remove(it.key), 180);
    } else {
      c.style.transition = 'transform .18s, opacity .18s';
      c.style.transform = ''; c.style.opacity = ''; c.classList.remove('removing');
      setTimeout(() => { c.style.transition = ''; }, 200);
    }
  };

  c.addEventListener('touchstart', (e) => {
    if (e.touches.length !== 1 || e.target.closest('button')) return;
    endSwipe(false);
    sx = e.touches[0].clientX; sy = e.touches[0].clientY;
    clearTimeout(timer);
    // 並べ替えられないとき (自分で並べた順以外) は長押しを待たない。待つと、ゆっくり横に動かしたときにスワイプにならない (-1 = 指が触れている印)
    timer = reorder ? setTimeout(() => { timer = 0; start(); }, LONG_PRESS_MS) : -1;
  }, { passive: true });
  c.addEventListener('touchmove', (e) => {
    const t = e.touches[0];
    if (swiping) { if (e.cancelable) e.preventDefault(); swipeDx = t.clientX - sx; swipeTo(swipeDx); return; }
    if (!st) { // 長押しになる前に動いたら、横ならスワイプ、縦ならふつうのスクロール
      if (!timer) return;
      const dx = t.clientX - sx, dy = t.clientY - sy;
      if (Math.hypot(dx, dy) <= 10) return;
      clearTimeout(timer); timer = 0;
      if (swipe && Math.abs(dx) > Math.abs(dy) * 1.2) { swiping = true; swipeDx = dx; if (e.cancelable) e.preventDefault(); swipeTo(dx); }
      return;
    }
    if (e.cancelable) e.preventDefault();
    layout(t.clientY);
  }, { passive: false });
  c.addEventListener('touchend', () => { endSwipe(true); finish(true); });
  c.addEventListener('touchcancel', () => { endSwipe(false); finish(false); });
}
