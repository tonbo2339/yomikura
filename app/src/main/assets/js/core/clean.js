// Yomikura 共通基盤 (9): 本文を安全な要素だけにする (保存するとき・表示するとき)
window.NH.define('clean', function (NH) {
  // 本文は安全な要素だけにして保存する・表示する (属性・スクリプト・画像は落とす)。残すのは段落・改行・ルビ・強調。
  // 保存したあとのデータをアプリ内ページで表示するときにも、もう一度これを通す (サイト側から書き込まれた物を実行しないため)
  const KEEP = new Set(['P', 'BR', 'HR', 'RUBY', 'RT', 'RP', 'RB', 'B', 'STRONG', 'I', 'EM']);
  const SKIP = new Set(['SCRIPT', 'STYLE', 'IFRAME', 'INS', 'NOSCRIPT', 'IMG', 'SVG', 'FORM', 'BUTTON', 'AUDIO', 'VIDEO']);
  const esc = (t) => t.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  // 挿絵は、端末に保存した物 (img/<名前>) だけ残す。サイトの画像の URL は残さない (保存のときに data-nh-local を付けてある)
  const IMG_LOCAL = /^img\/i[0-9a-f]{8}\.(jpg|png|gif|webp)$/;
  NH.cleanNode = function clean(node) {
    let out = '';
    node.childNodes.forEach((n) => {
      if (n.nodeType === 3) { out += esc(n.nodeValue); return; }
      if (n.nodeType !== 1) return;
      const t = n.tagName.toUpperCase();
      if (t === 'IMG') {
        const local = n.getAttribute('data-nh-local') || n.getAttribute('src') || '';
        if (IMG_LOCAL.test(local)) out += '<img src="' + local + '" alt="">';
        return;
      }
      if (SKIP.has(t)) return;
      const tag = t.toLowerCase();
      if (t === 'BR' || t === 'HR') out += '<' + tag + '>';
      else if (KEEP.has(t)) out += '<' + tag + '>' + clean(n) + '</' + tag + '>';
      else out += clean(n); // div / span / a などは中身だけ残す
    });
    return out;
  };
  NH.cleanHtml = (html) => NH.cleanNode(new DOMParser().parseFromString(String(html || ''), 'text/html').body);
});
