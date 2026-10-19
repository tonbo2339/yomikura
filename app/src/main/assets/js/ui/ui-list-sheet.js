// ミュート・ブロックの追加シート (作品 / 作者 / タグ / キーワード。サイト上の画面。ui-list.js が呼ぶ)。
// 上の切り替えで、ミュート (隠す・薄く。一時的に表示もできる) とブロック (完全に出さない) を選ぶ。作品ごとはブロックだけ。
// ctx = {mk, refreshView (一覧とリンクの表示を直す)}。戻り値: openMuteSheet(entry) (entry = 一覧の作品 {key, title, author, tags})
window.NH_UI_LIST_SHEET = function (ctx) {
  const { mk, refreshView } = ctx;
  return function openMuteSheet(entry) {
    const scrim = mk('div', 'nh-scrim');
    const sheet = mk('div', 'nh-sheet');
    const close = () => { scrim.remove(); sheet.remove(); };
    scrim.addEventListener('click', close);
    let mode = 'mute';
    let typed = ''; // 書きかけのキーワード (ミュート / ブロックを切り替えて描き直しても消さない)
    const add = (kind, value, label) => {
      close();
      if (mode === 'mute') {
        NH.addMute(kind, value);
        refreshView();
        NH.toast(label + ' をミュートしました');
      } else {
        NH.addBlock(kind, value);
        refreshView();
        NH.toast(label + ' をブロックしました', { label: '元に戻す', fn: () => { NH.removeBlock(kind, kind === 'works' ? value.key : value); refreshView(); } });
      }
    };
    const paint = () => {
      sheet.textContent = '';
      const verb = mode === 'mute' ? 'ミュート' : 'ブロック';
      sheet.appendChild(mk('div', 'nh-sheet-title', entry.title));
      const seg = mk('div', 'nh-sheet-seg');
      [['mute', 'ミュート'], ['block', 'ブロック']].forEach(([k, label]) => {
        const b = mk('button', k === mode ? 'on' : '', label);
        b.addEventListener('click', () => { mode = k; paint(); });
        seg.appendChild(b);
      });
      sheet.append(seg, mk('div', 'nh-sheet-note', mode === 'mute'
        ? '一致した作品を隠す (または薄く表示)。画面の下のボタンで一時的に表示できます'
        : '一致した作品を完全に出しません。ランキング・検索のほか、おすすめや作者の作品一覧からも消えます'));
      const g = mk('div', 'nh-sheet-group');
      if (mode === 'block') {
        const b = mk('button', 'nh-sheet-row', 'この作品をブロック');
        const short = entry.title.length > 20 ? entry.title.slice(0, 20) + '…' : entry.title; // お知らせが長くなりすぎないように
        b.addEventListener('click', () => add('works', { key: entry.key, title: entry.title }, '「' + short + '」'));
        g.appendChild(b);
      }
      if (entry.author) {
        const b = mk('button', 'nh-sheet-row', '作者「' + entry.author + '」を' + verb);
        b.addEventListener('click', () => add('authors', entry.author, '作者「' + entry.author + '」'));
        g.appendChild(b);
      }
      if (g.childElementCount) sheet.appendChild(g);
      if (entry.tags.length) {
        sheet.appendChild(mk('div', 'nh-sheet-label', 'タグを' + verb));
        const tags = mk('div', 'nh-sheet-tags');
        entry.tags.slice(0, 12).forEach((t) => {
          const b = mk('button', 'nh-sheet-tag', '# ' + t);
          b.addEventListener('click', () => add('words', t, '「' + t + '」'));
          tags.appendChild(b);
        });
        sheet.appendChild(tags);
      }
      sheet.appendChild(mk('div', 'nh-sheet-label', 'キーワードを' + verb + ' (タイトル・タグに含まれていれば一致)'));
      const form = mk('div', 'nh-sheet-form');
      const input = mk('input'); input.type = 'text'; input.placeholder = '例: 追放'; input.value = typed;
      input.enterKeyHint = 'done';
      const ok = mk('button', 'nh-sheet-add', '追加');
      const submit = () => { const v = input.value.trim(); if (v) add('words', v, '「' + v + '」'); };
      input.addEventListener('input', () => { typed = input.value; });
      input.addEventListener('keydown', (e) => { if (e.key === 'Enter') submit(); });
      ok.addEventListener('click', submit);
      form.append(input, ok);
      sheet.appendChild(form);
      const manage = mk('button', 'nh-sheet-row nh-sheet-link', 'ミュート・ブロックの一覧と設定');
      manage.addEventListener('click', () => (location.href = NH.SETTINGS_URL + '?page=mute'));
      const cancel = mk('button', 'nh-sheet-row nh-sheet-cancel', 'キャンセル');
      cancel.addEventListener('click', close);
      sheet.append(manage, cancel);
    };
    paint();
    document.documentElement.append(scrim, sheet);
  };
};
