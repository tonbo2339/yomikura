// サイトの作りが変わって表示が崩れたときの、原因の切り分け用。⋮ メニューの「このページの表示を診断」(Kotlin の PageActions.diagnose) が、
// ページの中で window.nhSiteCheck() を呼び、sites.js のセレクターがいまのページに何件当たるかを、コピーできる文で返す。
//
// - 本文 (body.box / body.text) は読書ページの要。0 件なら「sites.js の body を直す」合図 (NG)
// - 畳む欄・隠す対象は、そのページに無いことがふつうにある (最終話だけの欄など) ので、0 件でも NG にしない (参考)
// - 載せるのは、ホスト名・パスの形 (数字は 9 に置き換える)・セレクターと件数だけ。作品の中身や ID は載せない
//
// 計算 (セレクターの分割・判定・文の組み立て) は DOM に依存しない関数 (window.NH_SITECHECK)。ページを調べるのは count だけ
(function () {
  if (window.NH_SITECHECK) return;

  // 最上位のカンマでセレクターを分ける (:has(...) や [attr="a,b"] の中のカンマでは分けない)
  function splitSelectors(list) {
    const out = [];
    let depth = 0, quote = '', cur = '';
    for (const ch of list) {
      if (quote) { cur += ch; if (ch === quote) quote = ''; continue; }
      if (ch === '"' || ch === "'") { quote = ch; cur += ch; continue; }
      if (ch === '(' || ch === '[') depth++;
      else if (ch === ')' || ch === ']') depth = Math.max(0, depth - 1);
      if (ch === ',' && depth === 0) { if (cur.trim()) out.push(cur.trim()); cur = ''; continue; }
      cur += ch;
    }
    if (cur.trim()) out.push(cur.trim());
    return out;
  }

  // sites.js の hide / bars は CSS のルールそのもの (「セレクター{...}」の並び)。ルールごとに、{ の前のセレクターを取り出す
  function hideSelectors(css) {
    return String(css || '').split('}').map((r) => r.split('{')[0]).flatMap(splitSelectors);
  }

  const siteKeyOf = (host) => host === 'syosetu.org' || host === 'h.syosetu.org' ? 'hameln' : host === 'kakuyomu.jp' ? 'kakuyomu' : /\.syosetu\.com$/.test(host) ? 'narou' : null;
  // パスの形だけ。数字は 9 に (作品の ID を載せない)。なろうの ncode (n1234ab) は nXXXX に
  const shapeOf = (path) => String(path || '/').replace(/\/n\d{1,6}[a-z]{1,3}(?=\/|$)/g, '/nXXXX').replace(/\d+/g, '9');

  // 何を調べるか。kind = episode (話ページ) / other
  function plan(host, pathname, sites) {
    const key = siteKeyOf(host);
    const s = key && sites ? sites[key] : null;
    if (!s) return null;
    const episode = !!(s.episode && s.episode.test(pathname));
    const items = [];
    if (episode) {
      items.push({ label: '本文 (文字サイズの対象)', selector: s.body && s.body.box, critical: true });
      items.push({ label: '本文 (フォント・行間の対象)', selector: s.body && s.body.text, critical: true });
      const f = s.fold || {};
      (window.NH_FOLDS || []).forEach((fd) => { if (f[fd.key]) items.push({ label: '畳む欄: ' + fd.label, selector: f[fd.key] }); }); // 名前は sites.js の NH_FOLDS
    }
    // ヘッダー・フッター (bars) は話ページだけ隠すので、話ページのときだけ調べる
    [...hideSelectors(s.hide), ...(episode ? hideSelectors(s.bars) : [])].forEach((sel) => items.push({ label: '隠す対象', selector: sel }));
    return { key, episode, items };
  }

  // 件数を調べる。count(selector) = 当たった数 (セレクターが不正で調べられないときは -1)
  function run(p, count) {
    const rows = [];
    p.items.forEach((it) => {
      // 本文などは、カンマ区切りの全体で数える。隠す対象は 1 つずつ
      const n = count(it.selector);
      rows.push({ label: it.label, selector: it.selector, n, critical: !!it.critical });
    });
    const bad = rows.filter((r) => r.critical && r.n === 0);
    const invalid = rows.filter((r) => r.n < 0);
    const verdict = bad.length ? 'NG: 本文の場所が見つかりません (sites.js の body を直す)'
      : invalid.length ? 'NG: 使えないセレクターがあります (sites.js を直す)'
      : p.episode ? 'OK: 本文は見つかっています' : 'OK (話ページではないので、本文は調べていません)';
    return { verdict, rows };
  }

  function text(host, pathname, p, r, extra) {
    const out = ['# サイトの表示の診断', 'ページ: ' + host + ' ' + shapeOf(pathname) + (p.episode ? ' (話ページ)' : ''), 'サイト: ' + p.key, '判定: ' + r.verdict];
    if (extra && extra.length) out.push(...extra);
    out.push('');
    r.rows.forEach((x) => {
      const mark = x.n < 0 ? '使えない' : x.critical ? (x.n === 0 ? 'NG 0 件' : 'OK ' + x.n + ' 件') : (x.n === 0 ? '- 0 件 (このページに無いこともある)' : x.n + ' 件');
      out.push('- ' + x.label + ': ' + mark + '  ' + x.selector);
    });
    return out.join('\n') + '\n';
  }

  window.NH_SITECHECK = { splitSelectors, hideSelectors, siteKeyOf, shapeOf, plan, run, text };

  // ページの中で呼ぶ。対応サイトのページでなければ null
  window.nhSiteCheck = () => {
    const p = plan(location.hostname, location.pathname, window.NH_SITES);
    if (!p) return null;
    const count = (sel) => { try { return document.querySelectorAll(sel).length; } catch (e) { return -1; } };
    const extra = [];
    try { if (window.NH && NH.isChallenge && NH.isChallenge(document)) extra.push('注意: 確認画面 (Cloudflare など) が出ています。通過してからもう一度診断してください'); } catch (e) { /* 無視 */ }
    return text(location.hostname, location.pathname, p, run(p, count), extra);
  };
})();
