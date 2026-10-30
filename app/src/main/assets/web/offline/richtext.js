// offline.html: 保存した本文の書式 (ハーメルンの掲示板形式・色・脚注・動く装飾)。
// 保存した本文には、色・大小・背景色などの書式と、ページ内の目印 (空の <span id="jump.r233"></span> など)・脚注・動きの定義が残っている
// (整形は core/clean.js)。見た目の CSS は style.css。ここは、読む画面で動かす部分:
//  - ダークテーマでは、本文の色 (文字・枠線・背景色) を、サイトの夜間モードと同じ式で明るさだけ反転する
//  - 「>>233」の文字を、引用元の目印があるものだけリンクにし、押している間だけ引用元のレスを吹き出しで出す (サイトと同じ動き)
//  - 脚注の印 (a.footnote_number) を押している間だけ、脚注の文を吹き出しで出す (サイトと同じ動き)
//  - 脚注の欄のリンク・ページ内ジャンプ (a.jump_link) は、押すとその場所へ動く
//  - 「画面に入ったら再生」の動き (.tag_inview) は、画面に入ったときに始める

const RICH = (() => {
  // ---- 色 (ハーメルンの夜間モード: novel_sp_main.js の getInvertedAndHueRotatedColor と同じ式) ----
  const rgbToHsl = (r, g, b) => {
    r /= 255; g /= 255; b /= 255;
    const max = Math.max(r, g, b), min = Math.min(r, g, b), d = max - min, add = max + min;
    const l = add / 2;
    if (d === 0) return [0, 0, l];
    const s = l <= 0.5 ? d / add : d / (2 - add);
    let h;
    if (max === r) h = (60 * (g - b)) / d + 360;
    else if (max === g) h = (60 * (b - r)) / d + 120;
    else h = (60 * (r - g)) / d + 240;
    return [Math.round(h) % 360, s, l]; // 色相は整数に丸める (サイトが使う jQuery Color と同じ)
  };
  const hslToRgb = (h, s, l) => {
    const q = l < 0.5 ? l * (1 + s) : l + s - l * s, p = 2 * l - q;
    const ch = (t) => {
      t = ((t % 1) + 1) % 1;
      if (t < 1 / 6) return p + (q - p) * 6 * t;
      if (t < 1 / 2) return q;
      if (t < 2 / 3) return p + (q - p) * 6 * (2 / 3 - t);
      return p;
    };
    return [ch(h / 360 + 1 / 3), ch(h / 360), ch(h / 360 - 1 / 3)].map((v) => Math.round(v * 255));
  };
  // [r, g, b] (0〜255) → 明るさだけ反転した [r, g, b] (色相と彩度は保つ。知覚の明るさ Y が反転する)
  const nightRgb = ([r, g, b]) => {
    const [hue, sat, light] = rgbToHsl(r, g, b);
    const a = (hue >= 0 && hue < 60) || (hue >= 120 && hue < 180) || (hue >= 240 && hue < 300) ? (hue % 60) / 60 : 1 - (hue % 60) / 60;
    const pmax = hue >= 60 && hue < 180 ? 0.586611 : hue >= 180 && hue < 300 ? 0.114478 : 0.298912; // 輝度の係数 (G / B / R)
    const pmid = (hue >= 0 && hue < 60) || (hue >= 180 && hue < 240) ? 0.586611 : (hue >= 120 && hue < 180) || (hue >= 300 && hue < 360) ? 0.114478 : 0.298912;
    const c = 2 * sat * (0.5 - (pmax + pmid * a));
    const y = light > 0.5 ? light * (1 + c) - c : light * (1 - c);
    const newY = 1 - y;
    const l = newY > (1 - c) / 2 ? (newY + c) / (1 + c) : newY / (1 - c);
    return hslToRgb(hue, sat, l);
  };

  // ---- 引用元 (掲示板形式の >>N) ----
  // 引用元のレスの中身: 目印 jump.r<N> から jump.r<N>e の間 (段落の区切りは改行にする)。無ければ空
  const refHtml = (html, n) => {
    if (!/^\d{1,6}$/.test(String(n))) return '';
    const m = String(html).match(new RegExp('<span id="jump\\.r' + n + '"></span>([\\s\\S]*?)<span id="jump\\.r' + n + 'e"></span>'));
    return m ? m[1].replace(/<p\b[^>]*>/gi, '').replace(/<\/p>/gi, '<br>') : '';
  };

  // 本文 root の中の「>>N」の文字を、引用元の目印がある物だけリンクにする (a.bbs_template_popup)
  const linkifyRefs = (root) => {
    const has = (n) => !!root.querySelector('span[id="jump.r' + n + '"]') && !!root.querySelector('span[id="jump.r' + n + 'e"]');
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
    const targets = [];
    for (let n = walker.nextNode(); n; n = walker.nextNode()) if (/>>\d{1,6}/.test(n.nodeValue)) targets.push(n);
    targets.forEach((node) => {
      const parts = node.nodeValue.split(/(>>\d{1,6})/);
      if (!parts.some((p) => /^>>\d{1,6}$/.test(p) && has(p.slice(2)))) return;
      const frag = document.createDocumentFragment();
      parts.forEach((p) => {
        if (/^>>\d{1,6}$/.test(p) && has(p.slice(2))) {
          const a = document.createElement('a');
          a.className = 'bbs_template_popup';
          a.dataset.resNum = p.slice(2);
          a.href = '#jump.r' + p.slice(2);
          a.textContent = p;
          frag.appendChild(a);
        } else if (p) frag.appendChild(document.createTextNode(p));
      });
      node.replaceWith(frag);
    });
  };

  // ---- 吹き出し ----
  const POPUP = 'a.bbs_template_popup, a.footnote_number';
  // 脚注の印 (a.footnote_number) の文: 脚注の欄の、同じ ID の a.footnote_link (href="#f_…" と id="fn_…" の組) の隣の .footnote_text。
  // 見つからなければ、サイトと同じに、n 番目の印に n 番目の .footnote_text
  const footnoteHtml = (root, a) => {
    const id = a.id || '';
    const link = id ? root.querySelector('a.footnote_link[href="#' + id + '"]') : null;
    let text = link && link.parentElement ? link.parentElement.nextElementSibling : null;
    if (!text || !text.classList.contains('footnote_text')) {
      const i = [...root.querySelectorAll('a.footnote_number')].indexOf(a);
      text = root.querySelectorAll('.footnote_text')[i] || null;
    }
    return text ? text.innerHTML : '';
  };

  // 印を押している間だけ、吹き出しを出す (サイトと同じ: 離すと消える)。吹き出しは画面に固定し、押した所の上 (入らなければ下) に出す
  const bindPopups = (root, html) => {
    let tip = null;
    const hide = () => { if (tip) { tip.remove(); tip = null; } };
    const show = (a, x, y) => {
      hide();
      const body = a.classList.contains('footnote_number') ? footnoteHtml(root, a) : refHtml(html, a.dataset.resNum);
      if (!body) return;
      tip = document.createElement('div');
      tip.className = 'bbs-tip';
      tip.innerHTML = NH.cleanHtml(body); // 本文と同じ整形をもう一度通す
      document.body.appendChild(tip); // 縦書きの本文の外に置く (横書きで出す)
      if (isDark()) nightColors(tip); // 本文と同じ色にする (色を読むので、画面に付けたあとで)
      const w = tip.offsetWidth, h = tip.offsetHeight;
      const left = Math.max(4, Math.min(x, window.innerWidth - w - 4));
      const top = y - h - 50 >= 4 ? y - h - 50 : y + 50;
      tip.style.left = left + 'px';
      tip.style.top = Math.max(4, Math.min(top, window.innerHeight - h - 4)) + 'px';
    };
    const popupOf = (e) => (e.target.closest ? e.target.closest(POPUP) : null);
    root.addEventListener('touchstart', (e) => {
      const a = popupOf(e);
      if (!a) return;
      e.preventDefault(); // 押しただけで、本文のタップ (バーの出し入れ) やリンク先への移動にしない
      const t = e.touches[0];
      show(a, t.clientX, t.clientY);
    }, { passive: false });
    ['touchend', 'touchcancel'].forEach((type) => root.addEventListener(type, hide));
    root.addEventListener('mouseover', (e) => { const a = popupOf(e); if (a) show(a, e.clientX, e.clientY); });
    root.addEventListener('mouseout', (e) => { if (popupOf(e)) hide(); });
    root.addEventListener('click', (e) => { if (popupOf(e)) e.preventDefault(); }); // 押しても移動しない
    window.addEventListener('scroll', hide, true);
  };

  // ---- ページ内の移動 (脚注の欄から印へ戻る・ページ内ジャンプ) ----
  const bindJumps = (root) => {
    root.addEventListener('click', (e) => {
      const a = e.target.closest ? e.target.closest('a.footnote_link, a.jump_link') : null;
      if (!a) return;
      e.preventDefault();
      const id = (a.getAttribute('href') || '').slice(1);
      const target = id ? root.querySelector('[id="' + id + '"]') : null; // ID は clean.js が [\w.-] だけにしてある
      if (target) target.scrollIntoView({ behavior: 'smooth', block: 'center', inline: 'center' });
    });
  };

  // 「画面に入ったら再生」(.tag_inview。style に animation-play-state: paused が付いている) は、画面に入ったときに始める
  const bindInview = (root) => {
    if (typeof IntersectionObserver !== 'function') return;
    const io = new IntersectionObserver((entries) => entries.forEach((en) => {
      if (!en.isIntersecting) return;
      en.target.style.animationPlayState = 'running';
      io.unobserve(en.target);
    }));
    root.querySelectorAll('.tag_inview').forEach((el) => io.observe(el));
  };

  // ---- 本文に適用する ----
  const isDark = () => {
    const m = getComputedStyle(document.body).backgroundColor.match(/\d+(\.\d+)?/g);
    return !!m && (0.299 * m[0] + 0.587 * m[1] + 0.114 * m[2]) < 128;
  };
  // サイトは、背景色つきの枠 (.bgcolor) を作者の明るい背景のままにして、中の色を反転しない。アプリのリーダーは暗い画面なので、
  // (アプリのサイト表示でも、WebView の自動ダークテーマが同じ枠を暗くしている) 枠の背景も中の色も反転して、暗い画面に合わせる。枠の文字の色は CSS (#text.night .bgcolor)
  const nightColors = (root) => {
    const toRgb = (css) => (css.match(/\d+(\.\d+)?/g) || []).slice(0, 4).map(Number); // [r, g, b] か、透明度つきの [r, g, b, a]
    const flip = (el, prop) => {
      if (!el.style[prop]) return; // 色が書かれている (style にある) 要素だけ。書かれていない要素は、色を引き継ぐので反転しない
      const c = toRgb(getComputedStyle(el)[prop]);
      if (c.length === 4 && c[3] === 0) return; // 透明 (transparent。見えない余白に使われる) は、反転すると見えてしまうので、そのまま
      if (c.length >= 3) el.style[prop] = 'rgb(' + nightRgb(c.slice(0, 3)).join(', ') + ')';
    };
    root.querySelectorAll('.color').forEach((el) => flip(el, 'color'));
    root.querySelectorAll('.bordercolor').forEach((el) => flip(el, 'borderColor'));
    root.querySelectorAll('.bgcolor').forEach((el) => flip(el, 'backgroundColor'));
    root.classList.add('night');
  };
  const apply = (root) => {
    const html = root.innerHTML; // 目印の間の中身を取るのに使う (リンク化や色の変更より前の形)
    if (root.querySelector('span[id^="jump.r"]')) linkifyRefs(root);
    if (root.querySelector('span[id^="jump.r"], a.footnote_number')) bindPopups(root, html);
    if (root.querySelector('a.footnote_link, a.jump_link')) bindJumps(root);
    if (root.querySelector('.tag_inview')) bindInview(root);
    if (root.querySelector('.color, .bordercolor, .bgcolor') && isDark()) nightColors(root);
  };

  return { nightRgb, refHtml, apply };
})();
