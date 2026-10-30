// Yomikura 共通基盤 (9): 本文を安全な要素だけにする (保存するとき・表示するとき)
window.NH.define('clean', function (NH) {
  // 本文は安全な要素だけにして保存する・表示する (スクリプトは落とす)。残すのは段落・改行・ルビ・強調と、
  // 下の「書式」(許可したクラス・スタイル・表・脚注・ページ内ジャンプ・動きの定義) と、保存した挿絵・引用元の目印。
  // 保存したあとのデータをアプリ内ページで表示するときにも、もう一度これを通す (サイト側から書き込まれた物を実行しないため)。
  // 見た目の CSS は、読む画面 (web/offline/style.css) が同じ名前で用意する (サイトの CSS は保存先に無いため)
  const KEEP = new Set(['P', 'BR', 'HR', 'RUBY', 'RT', 'RP', 'RB', 'B', 'STRONG', 'I', 'EM', 'SUP', 'SUB']);
  const SKIP = new Set(['SCRIPT', 'STYLE', 'IFRAME', 'INS', 'NOSCRIPT', 'IMG', 'SVG', 'FORM', 'BUTTON', 'AUDIO', 'VIDEO']);
  const esc = (t) => t.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  // 挿絵は、端末に保存した物 (img/<名前>) だけ残す。サイトの画像の URL は残さない (保存のときに data-nh-local を付けてある)
  const IMG_LOCAL = /^img\/i[0-9a-f]{8}\.(jpg|png|gif|webp)$/;

  // ---- 書式 ----
  // クラス: この一覧にある物だけ残す (ハーメルンの特殊タグ・色・大小・表・脚注・動く装飾。数字つきの shake-1 / vib-2 も)
  const FORMAT_CLASSES = new Set(['bold', 'italic', 'strike', 'underline', 'small', 'xsmall', 'big', 'xbig', 'center', 'right', 'left',
    'color', 'bgcolor', 'bordercolor', 'strokecolor', 'tag_text', 'tag_table', 'tag_box', 'tag_vbox', 'tag_pc', 'tag_sp',
    'tag_blink', 'tag_fade', 'tag_telop', 'tag_opacity', 'tag_blur', 'tag_shake', 'tag_vib', 'tag_inview', 'marquee', 'inner_marquee',
    'footnote', 'footnote_line', 'footnote_text']);
  const CLASS_PATTERN = /^(shake|vib)-\d{1,2}$/;
  const classOk = (c) => FORMAT_CLASSES.has(c) || CLASS_PATTERN.test(c);

  // スタイル: 許可した項目だけ、値の形を確かめて残す (url() や expression などは通さない)。値の !important は外す
  const NUM = '-?\\d{1,4}(?:\\.\\d{1,3})?';
  const POS = '\\d{1,4}(?:\\.\\d{1,3})?';
  const LEN = '(?:' + NUM + '(?:px|em|%|vw|vh)?|auto)';
  const LENS = LEN + '(?:\\s+' + LEN + '){0,3}';
  const COLOR = '(?:#[0-9a-fA-F]{3,8}|rgba?\\(\\s*\\d{1,3}\\s*,\\s*\\d{1,3}\\s*,\\s*\\d{1,3}\\s*(?:,\\s*[0-9.]+\\s*)?\\)|[a-zA-Z]{3,20})';
  const TRANSFORM_FN = '(?:rotate|rotateX|rotateY|rotateZ|skew|skewX|skewY|scale|scaleX|scaleY|translate|translateX|translateY)\\(\\s*' +
    NUM + '(?:deg|px|em|%)?(?:\\s*,\\s*' + NUM + '(?:deg|px|em|%)?)*\\s*\\)';
  const TRANSFORM = TRANSFORM_FN + '(?:\\s+' + TRANSFORM_FN + ')*';
  const TIME = POS + '(?:s|ms)';
  const KEYWORDS = (words) => '(?:' + words + ')';
  const WRITING = KEYWORDS('vertical-rl|vertical-lr|horizontal-tb|tb-rl|lr-tb');
  const STYLE_RULES = {
    'color': COLOR, 'background-color': COLOR, 'border-color': COLOR,
    '-webkit-text-stroke': POS + 'px\\s+' + COLOR,
    'font-size': null, // 大きさの範囲を確かめる (下)
    'letter-spacing': NUM + '(?:px|em)',
    'line-height': NUM + '(?:px|em|%)?',
    'text-align': KEYWORDS('left|right|center|justify|start|end'),
    'vertical-align': KEYWORDS('top|middle|bottom|baseline|sub|super|text-top|text-bottom'),
    'display': KEYWORDS('inline-block|inline|block|inline-table'),
    'box-sizing': KEYWORDS('border-box|content-box'),
    'white-space': KEYWORDS('normal|nowrap|pre|pre-wrap|pre-line'),
    'overflow': KEYWORDS('hidden|auto|visible|scroll|clip'),
    'border': KEYWORDS('none|' + POS + '(?:px|em)\\s+(?:solid|dashed|dotted|double|none)(?:\\s+' + COLOR + ')?'),
    'border-radius': LENS, 'padding': LENS, 'margin': LENS,
    'width': LEN, 'height': LEN, 'max-width': LEN, 'max-height': LEN, 'min-width': LEN, 'min-height': LEN,
    'position': KEYWORDS('relative|absolute|static'),
    'left': LEN, 'top': LEN, 'right': LEN, 'bottom': LEN,
    'z-index': '[0-5]', // 読む画面の上下のバー (z-index 10) の上に出ないように
    'transform': TRANSFORM,
    'writing-mode': WRITING, '-webkit-writing-mode': WRITING,
    'text-orientation': KEYWORDS('upright|mixed|sideways'),
    'text-combine-upright': KEYWORDS('all|none|digits\\s*[2-4]'),
    '-webkit-text-combine': KEYWORDS('horizontal|none'),
    'animation-name': '[A-Za-z][\\w-]{0,40}',
    'animation-duration': TIME, 'animation-delay': '-?' + TIME,
    'animation-iteration-count': KEYWORDS('infinite|\\d{1,4}'),
    'animation-direction': KEYWORDS('normal|reverse|alternate|alternate-reverse'),
    'animation-timing-function': KEYWORDS('linear|ease|ease-in|ease-out|ease-in-out|step-start|step-end'),
    'animation-play-state': KEYWORDS('paused|running'),
    'animation-fill-mode': KEYWORDS('none|forwards|backwards|both'),
    'filter': KEYWORDS('blur\\(\\s*' + POS + 'px\\s*\\)|opacity\\(\\s*[0-9.]{1,5}\\s*\\)'),
    'background-image': 'linear-gradient\\(\\s*to\\s+(?:right|left|top|bottom)\\s*,\\s*' + COLOR + '\\s+\\d{1,3}%\\s*,\\s*' + COLOR + '\\s+\\d{1,3}%\\s*\\)',
    'background-size': '\\d{1,3}%\\s+\\d{1,3}%',
    'background-clip': 'text', '-webkit-background-clip': 'text',
  };
  const STYLE_RE = Object.create(null); // 「constructor」「__proto__」などの名前が、継承された値に当たらないように
  Object.keys(STYLE_RULES).forEach((k) => { if (STYLE_RULES[k]) STYLE_RE[k] = new RegExp('^(?:' + STYLE_RULES[k] + ')$'); });
  const SIZE_VALUE = /^(\d{1,2}(\.\d{1,3})?)(em|%)$/;
  const styleOk = (prop, val) => {
    if (prop === 'font-size') {
      if (!SIZE_VALUE.test(val)) return false;
      const num = parseFloat(val);
      return val.endsWith('%') ? num >= 30 && num <= 400 : num >= 0.3 && num <= 4; // 極端な大きさは通さない
    }
    return !!STYLE_RE[prop] && STYLE_RE[prop].test(val);
  };
  const styleOf = (n) => {
    const out = [];
    String(n.getAttribute('style') || '').split(';').forEach((decl) => {
      const i = decl.indexOf(':');
      if (i < 0) return;
      const prop = decl.slice(0, i).trim().toLowerCase();
      const val = decl.slice(i + 1).replace(/\s*!important\s*$/i, '').trim();
      if (styleOk(prop, val)) out.push(prop + ':' + val);
    });
    return out.join(';');
  };
  const classesOf = (n) => String(n.getAttribute('class') || '').split(/\s+/).filter(classOk).join(' ');

  // ページ内の目印と脚注の ID。目印: jump.r233 (掲示板形式の引用元)・jump.uenohou (ページ内ジャンプの行き先)。脚注: f_ と fn_ の組
  const MARK_ID = /^jump\.[\w.-]{1,40}$/;
  const FN_ID = /^(?:f|fn)_[A-Za-z0-9]{1,24}$/;
  const JUMP_HREF = /^#jump\.[\w.-]{1,40}$/;
  const FN_HREF = /^#(?:f|fn)_[A-Za-z0-9]{1,24}$/;
  // 表のセルの結合
  const spanOf = (n, name) => { const v = String(n.getAttribute(name) || ''); return /^\d{1,2}$/.test(v) && Number(v) > 1 ? ' ' + name + '="' + Number(v) + '"' : ''; };

  // 書式を残す要素。書式 (クラス・スタイル・目印) が何も付かない span / div は、中身だけ残す (今までと同じ)
  const FORMATTED = new Set(['SPAN', 'DIV', 'TABLE', 'TBODY', 'THEAD', 'TR', 'TD', 'TH']);
  const open = (tag, n) => {
    const cls = classesOf(n);
    const style = styleOf(n);
    const id = tag === 'span' && MARK_ID.test(String(n.getAttribute('id') || '')) ? n.getAttribute('id') : '';
    const cell = tag === 'td' || tag === 'th' ? spanOf(n, 'colspan') + spanOf(n, 'rowspan') : '';
    if (!cls && !style && !id && !cell && (tag === 'span' || tag === 'div')) return null;
    return '<' + tag + (id ? ' id="' + id + '"' : '') + (cls ? ' class="' + cls + '"' : '') + cell + (style ? ' style="' + style + '"' : '') + '>';
  };
  // 脚注 (a.footnote_number = 本文の中の印、a.footnote_link = 脚注の欄から印へ戻るリンク) とページ内ジャンプ (a.jump_link)。ほかのリンクは中身の文字だけ
  const anchor = (n, inner) => {
    const cls = String(n.getAttribute('class') || '').split(/\s+/);
    const href = String(n.getAttribute('href') || ''), id = String(n.getAttribute('id') || '');
    if (cls.includes('footnote_number') && FN_HREF.test(href) && FN_ID.test(id)) return '<a class="footnote_number" href="' + href + '" id="' + id + '">' + inner + '</a>';
    if (cls.includes('footnote_link') && FN_HREF.test(href) && FN_ID.test(id)) return '<a class="footnote_link" href="' + href + '" id="' + id + '">' + inner + '</a>';
    if (cls.includes('jump_link') && JUMP_HREF.test(href)) return '<a class="jump_link" href="' + href + '">' + inner + '</a>';
    return inner;
  };

  // 本文の中の <style>: @keyframes (点滅・フェードの動きの定義。サイトが話ごとに作る) だけ残す。
  // 名前と、各コマの宣言 (opacity / transform / left / top) の形を確かめ、1 つでも外れる定義は捨てる
  const KF_DECL = [new RegExp('^opacity\\s*:\\s*(?:[01]|0?\\.\\d{1,3})$'), new RegExp('^transform\\s*:\\s*' + TRANSFORM + '$'),
    new RegExp('^(?:left|top)\\s*:\\s*' + LEN + '$')];
  const keyframesOf = (css) => {
    const out = [];
    const block = /@keyframes\s+([A-Za-z][\w-]{0,40})\s*\{((?:\s*(?:\d{1,3}(?:\.\d+)?%|from|to)\s*\{[^{}]*\})+)\s*\}/g;
    for (let m = block.exec(css); m; m = block.exec(css)) {
      const frames = [];
      let ok = true;
      const frame = /(\d{1,3}(?:\.\d+)?%|from|to)\s*\{([^{}]*)\}/g;
      for (let f = frame.exec(m[2]); f; f = frame.exec(m[2])) {
        const decls = f[2].split(';').map((d) => d.trim()).filter(Boolean);
        if (!decls.every((d) => KF_DECL.some((re) => re.test(d)))) { ok = false; break; }
        frames.push(f[1] + '{' + decls.join(';') + '}');
      }
      if (ok && frames.length) out.push('@keyframes ' + m[1] + '{' + frames.join('') + '}');
    }
    return out.join('');
  };

  NH.cleanNode = function clean(node) {
    let out = '';
    node.childNodes.forEach((n) => {
      if (n.nodeType === 3) { out += esc(n.nodeValue); return; }
      if (n.nodeType !== 1) return;
      const t = n.tagName.toUpperCase();
      // 画像へのリンク (ハーメルンの「【挿絵表示】」) は、保存した画像があれば、リンクの代わりにその画像を置く (保存のときに data-nh-local を付けてある)
      const linkedLocal = t === 'A' ? n.getAttribute('data-nh-local') : '';
      if (t === 'IMG' || (linkedLocal && IMG_LOCAL.test(linkedLocal))) {
        const local = n.getAttribute('data-nh-local') || n.getAttribute('src') || '';
        if (IMG_LOCAL.test(local)) out += '<img src="' + local + '" alt="">';
        return;
      }
      if (t === 'STYLE') { // 動きの定義 (@keyframes) だけ。ほかの CSS は捨てる
        const kf = keyframesOf(String(n.textContent || ''));
        if (kf) out += '<style>' + kf + '</style>';
        return;
      }
      if (SKIP.has(t)) return;
      const tag = t.toLowerCase();
      if (t === 'BR' || t === 'HR') out += '<' + tag + '>';
      else if (t === 'EM' && /(^|\s)emphasisDots(\s|$)/.test(String(n.getAttribute('class') || ''))) out += '<em class="emphasisDots">' + clean(n) + '</em>'; // カクヨムの傍点 (《《》》)。ただの <em> は斜体
      else if (KEEP.has(t)) out += '<' + tag + '>' + clean(n) + '</' + tag + '>';
      else if (t === 'A') out += anchor(n, clean(n));
      else if (FORMATTED.has(t)) {
        const o = open(tag, n);
        out += o ? o + clean(n) + '</' + tag + '>' : clean(n);
      } else out += clean(n); // そのほかの要素は中身だけ残す
    });
    return out;
  };
  // 先頭に <body> を付けて読む (本文が <style> から始まると、<style> が <head> に入り、body だけ見る整形で落ちるため)
  NH.cleanHtml = (html) => NH.cleanNode(new DOMParser().parseFromString('<body>' + String(html || ''), 'text/html').body);
});
