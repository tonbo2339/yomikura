// 話ページの「表示の設定」(TT) のシート (サイト上の画面。ui.js が呼ぶ)。サイトのビューワー設定の代わりに、アプリ側の設定 (本文の見た目) を、その場で変える。
// 項目・範囲・選択肢・見せ方は、設定の「本文の見た目」と共通 (site/readbg.js の NH_TEXTLOOK・NH_READBG、スライダーと −＋、チェックの一覧、見本)。組み方向 (横組み / 縦組み = settings.siteVertical) もここ。
// 設定は NH.setSetting で書く。ページへの反映は、閉じたときにページを読み込み直す (描画前の early.js が、その設定で本文の見た目を作るため)。
// ctx = {mk, openSite (サイト側の設定を開く関数。無ければ null)}。戻り値: open()
window.NH_UI_VIEWER = function (ctx) {
  const { mk, openSite } = ctx;
  const T = window.NH_TEXTLOOK, R = window.NH_READBG, F = window.NH_FONTS;
  const CHECK = '<svg viewBox="0 0 16 12" width="16" height="12" aria-hidden="true"><path d="M1.5 6.5 6 11 14.5 1.5" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"/></svg>';
  return function open() {
    const st = () => NH.load().settings || {};
    let changed = false;
    const scrim = mk('div', 'nh-scrim');
    const sheet = mk('div', 'nh-sheet');
    const close = () => { scrim.remove(); sheet.remove(); if (changed) location.reload(); };
    scrim.addEventListener('click', close);
    const set = (k, v) => { NH.setSetting(k, v); changed = true; paint(); };

    // 設定の「大きさと行間」と同じ: 名前・− ・スライダー・＋ ・今の値
    const slider = (label, value, text, min, max, step, onInput, dec, inc) => {
      const row = mk('div', 'nh-vs-slider');
      const minus = mk('button', 'nh-vs-step', '−');
      const plus = mk('button', 'nh-vs-step', '＋');
      minus.setAttribute('aria-label', label + 'を小さく');
      plus.setAttribute('aria-label', label + 'を大きく');
      minus.addEventListener('click', dec);
      plus.addEventListener('click', inc);
      const range = mk('input');
      range.type = 'range'; range.min = min; range.max = max; range.step = step; range.value = value;
      range.setAttribute('aria-label', label);
      const val = mk('span', 'nh-vs-val', text);
      // 動かしている間は、数字だけ直す (保存と描き直しは、離したとき)
      range.addEventListener('input', () => { val.textContent = onInput.label(Number(range.value)); });
      range.addEventListener('change', () => onInput.set(Number(range.value)));
      row.append(mk('span', 'nh-vs-name', label), minus, range, plus, val);
      return row;
    };
    // 設定の一覧と同じ: 名前と、選んでいるものにチェック
    const pick = (label, sample, on, onClick) => {
      const b = mk('button', 'nh-vs-pick');
      b.type = 'button';
      const name = mk('span', 'nh-vs-pickname', label);
      if (sample) { name.style.background = sample.bg; name.style.color = sample.fg; }
      b.appendChild(name);
      const mark = mk('span', 'nh-vs-check');
      if (on) mark.innerHTML = CHECK;
      b.appendChild(mark);
      b.setAttribute('aria-pressed', on ? 'true' : 'false');
      b.addEventListener('click', onClick);
      return b;
    };

    function paint() {
      const s = st();
      sheet.textContent = '';
      sheet.appendChild(mk('div', 'nh-sheet-title', '表示の設定'));

      // 見本 (設定の「プレビュー」と同じ)
      const prev = mk('div', 'nh-vs-preview');
      prev.innerHTML = '夜明け前の駅には、まだ誰もいなかった。<ruby>改札<rt>かいさつ</rt></ruby>を抜けると、冷たい風が頬をなでる。';
      const size = s.size || T.SIZE.def;
      prev.style.fontFamily = (F && F.family(s.font)) || 'inherit';
      if (F && F.face(s.font, true)) { // 追加したフォントの見本 (@font-face は、サイトのページ用の /font/current を使う)
        let ff = document.getElementById('nh-vs-face');
        if (!ff) { ff = mk('style'); ff.id = 'nh-vs-face'; document.head.appendChild(ff); }
        if (ff.textContent !== F.face(s.font, true)) ff.textContent = F.face(s.font, true);
      }
      prev.style.fontSize = (16 * size / 100) + 'px';
      prev.style.lineHeight = s.lh || 1.8;
      const bg = R ? R.colors(s.readBg) : null;
      if (bg) { prev.style.background = bg.bg; prev.style.color = bg.fg; }
      if (NH.flagOf(s, 'siteVertical', false)) { prev.style.writingMode = 'vertical-rl'; prev.style.height = '8em'; }
      sheet.appendChild(prev);

      sheet.appendChild(mk('div', 'nh-sheet-label', '組み方向'));
      const seg = mk('div', 'nh-sheet-seg');
      [[false, '横組み'], [true, '縦組み']].forEach(([v, label]) => {
        const b = mk('button', NH.flagOf(s, 'siteVertical', false) === v ? 'on' : '', label);
        b.addEventListener('click', () => set('siteVertical', v));
        seg.appendChild(b);
      });
      sheet.appendChild(seg);

      sheet.appendChild(mk('div', 'nh-sheet-label', '大きさと行間'));
      const g = mk('div', 'nh-vs-group');
      g.appendChild(slider('サイズ', size, size + '%', T.SIZE.min, T.SIZE.max, T.SIZE.step,
        { label: (n) => n + '%', set: (n) => set('size', n) },
        () => set('size', Math.max(T.SIZE.min, size - T.SIZE.step)),
        () => set('size', Math.min(T.SIZE.max, size + T.SIZE.step))));
      const pos = T.lhPos(s.lh);
      g.appendChild(slider('行間', pos, T.lhLabel(s.lh), T.LH.std, T.LH.max, 1,
        { label: (n) => T.lhLabel(T.lhFromPos(n)), set: (n) => set('lh', T.lhFromPos(n)) },
        () => set('lh', T.lhFromPos(Math.max(T.LH.std, pos - 1))),
        () => set('lh', T.lhFromPos(Math.min(T.LH.max, pos + 1)))));
      sheet.appendChild(g);

      sheet.appendChild(mk('div', 'nh-sheet-label', 'フォント'));
      const fg = mk('div', 'nh-vs-group');
      const cur = s.font || 'site';
      const extra = NH.off.fonts(); // 追加したフォント {names, current}。名前と番号だけ (ファイルの id は、サイトのページには渡さない)
      T.FONTS.forEach(([k, label]) => fg.appendChild(pick(label, null, k === cur, () => set('font', k))));
      extra.names.forEach((name, i) => fg.appendChild(pick(name, null, extra.current === i && /^custom:/.test(cur), () => { NH.off.chooseFont(i); changed = true; paint(); })));
      sheet.appendChild(fg);

      if (R) {
        sheet.appendChild(mk('div', 'nh-sheet-label', '背景色'));
        const bgGroup = mk('div', 'nh-vs-group');
        R.choices().forEach(([k, label]) => bgGroup.appendChild(pick(label, R.colors(k), (s.readBg || R.SITE) === k, () => set('readBg', k))));
        sheet.appendChild(bgGroup);
      }

      sheet.appendChild(mk('div', 'nh-sheet-note nh-vs-note', '閉じると、この設定でページを読み込み直します。フォントの追加は、設定の「本文の見た目」でできます'));
      if (openSite) {
        const b = mk('button', 'nh-sheet-row nh-sheet-link', 'サイトの設定 (操作など) を開く');
        b.addEventListener('click', () => { const c = changed; changed = false; scrim.remove(); sheet.remove(); if (c) location.reload(); else openSite(); });
        sheet.appendChild(b);
      }
      const done = mk('button', 'nh-sheet-row nh-sheet-cancel', '閉じる');
      done.addEventListener('click', close);
      sheet.appendChild(done);
    }
    paint();
    const root = document.body || document.documentElement;
    root.append(scrim, sheet);
  };
};
