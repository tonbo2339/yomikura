// list.html: 長押しの並べ替え・横スワイプの削除 (設定の「横にスワイプして削除」でオフにできる)
// ---- 長押しして上下へ動かす並べ替え / 横にスワイプして削除 ----
// 作品を長押しすると持ち上がり、指を上下に動かして離した場所に入る。画面の端に近づくと自動でスクロールする。
// 長押しになる前に横へ動かすと、カードが指について動き、幅の SWIPE_RATIO を越えて離すとリストから削除する (Gmail と同じ)。
// 表示中 (絞り込み後) の並びの中で入れ替え、見えていない作品の位置は変えない。
const LONG_PRESS_MS = 380;
const SWIPE_RATIO = 0.35; // 幅のこの割合を越えて離すと削除
let dragEndedAt = 0;
let hintShown = false; // 「自分で並べた順」でないと長押しで並べ替えられない、という案内は、1 回開いているあいだに 1 度だけ出す

// 表示中の作品 (keys) の並びを新しい順にした、リスト (list) の新しい配列を返す。表示中の作品が占めていた場所に順に入れ直し、
// 見えていない作品の位置は変えない。keys のうち、いまのリストに無い作品 (動かしている間に、更新の確認などで消えた) は飛ばす
// (飛ばさないと、場所と作品の数が合わずに、リストに空の項目が入って、ほかの作品が消える)
function orderedList(list, keys) {
  const byKey = new Map(list.map((x) => [x.key, x]));
  const ks = [...new Set(keys)].filter((k) => byKey.has(k)); // keys の重複は 1 つにする (場所より作品が多いと、場所の外へ書いてしまう)
  const set = new Set(ks);
  const slots = [];
  list.forEach((x, i) => { if (set.has(x.key)) slots.push(i); });
  const out = list.slice();
  ks.forEach((k, j) => { out[slots[j]] = byKey.get(k); });
  return out;
}
// 元の並び (key の配列) へ戻した配列を返す。元の並びにある作品を元の順に並べ、そのあとに、増えた作品を今の順のまま続ける
function restoreOrder(list, keys) {
  const byKey = new Map(list.map((x) => [x.key, x]));
  const known = new Set(keys);
  return [...keys.filter((k) => byKey.has(k)).map((k) => byKey.get(k)), ...list.filter((x) => !known.has(x.key))];
}
// 表示中の作品 (keys) の並びを新しい順にする (元に戻せる)
function applyOrder(keys) {
  mutateOrder((d) => { d.readLater = orderedList(d.readLater, keys); }, '並べ替えました');
}

// 持ち上げた作品 (idx 番目) を dy だけ動かしたとき、入る位置 (動かしたあとの番号) を返す。tops / hs = 各作品の上端の位置と高さ。
// 上へ動かしたときは、持ち上げた作品の上端が、上にある作品の中心を越えたら、その作品の前に入る。下へ動かしたときは、下端が下にある作品の中心を越えたら、
// その作品の後に入る。(中心どうしで比べると、持ち上げた作品が先頭・末尾の作品より背が高いとき、いちばん上・下まで動かしても 1 つ手前にしか入れない)
function dragTarget(tops, hs, idx, dy) {
  const top = tops[idx] + dy;
  const bottom = top + hs[idx];
  if (dy < 0) {
    for (let j = 0; j < idx; j++) if (top < tops[j] + hs[j] / 2) return j;
  } else if (dy > 0) {
    for (let j = tops.length - 1; j > idx; j--) if (bottom > tops[j] + hs[j] / 2) return j;
  }
  return idx;
}

// reorder = 長押しで動かせるか (自分で並べた順で、選択中でないとき)。hint = 長押しされたら「自分で並べた順にすると並べ替えられる」と案内するか (ほかの並べ替えのとき)。swipe = 横スワイプで削除できるか (設定。選択中は使わない)。まとめての削除は選択モード (select.js)
function enableDrag(c, it, visible, reorder, swipe, hint) {
  let hintFired = false; // この指で、案内を出した
  let timer = 0, hintTimer = 0, sx = 0, sy = 0, st = null, raf = 0, swiping = false, swipeDx = 0;
  c.addEventListener('contextmenu', (e) => e.preventDefault());
  if (!reorder && !swipe && !hint) return;

  const layout = (clientY) => {
    st.y = clientY;
    const dy = Math.max(st.minDy, Math.min(st.maxDy, clientY + scrollY - st.startY));
    c.style.transform = 'translateY(' + dy + 'px) scale(1.02)';
    const target = dragTarget(st.tops, st.hs, st.idx, dy); // 入る位置
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
    // 端に近いほど速く (端の手前 150px / 90px から。遅い 6px から速い 22px まで)。長い一覧でも、いちばん上・下まで時間がかかりすぎない
    const edge = 90, topZone = edge + 60;
    const near = (a) => Math.min(1, Math.max(0, a));
    const v = st.y < topZone ? -(6 + 16 * near((topZone - st.y) / topZone)) : st.y > innerHeight - edge ? 6 + 16 * near((st.y - (innerHeight - edge)) / edge) : 0;
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
    const { cards, idx, target, tops, hs, gap } = st;
    st = null;
    dragEndedAt = Date.now();
    const settle = () => { c.classList.remove('dragging'); cards.forEach((x) => { x.style.transform = ''; x.style.transition = ''; }); };
    if (apply && target !== idx) {
      const keys = visible.map((x) => x.key);
      keys.splice(target, 0, keys.splice(idx, 1)[0]);
      // 落とす場所へ、持ち上げた作品をなめらかに寄せてから (ほかの作品は、もうその場所にいる) 描き直す。
      // すぐ元の位置に戻して描き直すと、作品が指の位置から落とす場所へ一気に飛んで、ずれて見える
      const step = hs[idx] + gap;
      const finalTop = target > idx ? tops[target] - step + hs[target] + gap : tops[target]; // 下へ動かしたときは、ずれた作品のすぐ下。上へ動かしたときは、target 番目の場所
      c.style.transition = 'transform .12s';
      c.style.transform = 'translateY(' + (finalTop - tops[idx]) + 'px)';
      setTimeout(() => { settle(); applyOrder(keys); }, 130); // 待つあいだも dragging のまま (裏のデータの変更で描き直されない。sync.js)
    } else {
      settle();
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
    clearTimeout(hintTimer); hintFired = false;
    if (hint && !hintShown) hintTimer = setTimeout(() => { hintShown = hintFired = true; snack('長押しで並べ替えるには、「並べ替え」を「自分で並べた順」にしてください'); }, 700);
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
      clearTimeout(hintTimer);
      clearTimeout(timer); timer = 0;
      if (swipe && Math.abs(dx) > Math.abs(dy) * 1.2) { swiping = true; swipeDx = dx; if (e.cancelable) e.preventDefault(); swipeTo(dx); }
      return;
    }
    if (e.cancelable) e.preventDefault();
    layout(t.clientY);
  }, { passive: false });
  c.addEventListener('touchend', () => { clearTimeout(hintTimer); if (hintFired) { dragEndedAt = Date.now(); hintFired = false; } endSwipe(true); finish(true); }); // 案内を出した指を離しても、作品を開かない
  c.addEventListener('touchcancel', () => { clearTimeout(hintTimer); endSwipe(false); finish(false); });
}
