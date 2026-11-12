// Yomikura 共通基盤 (19): 縦書きの共通の部品
// 縦書きの共通の部品 (保存した作品の読む画面 offline/reader.js と、サイトの話ページの縦書き ui/ui-vertical.js が共通で使う)。
window.NH.define('vertical', function (NH) {
  // 縦書きの半角の数字・英字を立てる (そのままだと、ブラウザは横向きに寝かせる)。
  // 数字: 1〜2 桁は 1 マスにまとめ (tate-chu-yoko)、3 桁以上 (「104,115」など) は 1 字ずつ立てる。英字: 1〜2 字は 1 マスにまとめ、3〜4 字は 1 字ずつ立てる。
  // それより長い英単語は、寝かせたまま (縦に何文字も積むと読みにくい)。1 マスにまとめる分は class "tcy"、立てる分は "upr" の span にする。
  // その class の見た目 (text-combine-upright・text-orientation) は、使う側の CSS が決める (縦書きの箱の中だけに掛けるため)
  NH.uprightRuns = function (root) {
    const re = /[0-9]+(?:[,.][0-9]+)*|[A-Za-z]+/g;
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
    const nodes = [];
    for (let n = walker.nextNode(); n; n = walker.nextNode()) {
      if (!n.parentElement.closest('rt,rp,script,style') && re.test(n.nodeValue)) nodes.push(n);
      re.lastIndex = 0;
    }
    nodes.forEach((n) => {
      const text = n.nodeValue;
      const frag = document.createDocumentFragment();
      let last = 0;
      text.replace(re, (m, at) => {
        const digits = /^[0-9]/.test(m), len = digits ? m.replace(/[^0-9]/g, '').length : m.length;
        const cls = len <= 2 ? 'tcy' : digits || len <= 4 ? 'upr' : '';
        if (!cls) return m;
        if (at > last) frag.append(text.slice(last, at));
        const s = document.createElement('span'); s.className = cls; s.textContent = m; frag.append(s);
        last = at + m.length;
        return m;
      });
      if (!last) return;
      if (last < text.length) frag.append(text.slice(last));
      n.replaceWith(frag);
    });
  };
});
