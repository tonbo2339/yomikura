// Yomikura 共通基盤 (14): ランキング・検索結果のミュートとブロック
window.NH.define('mute', function (NH) {
  NH.need('store');
  // ---- ランキング / 検索結果のミュート ----
  // mute: {authors: [作者名], words: [キーワード・タグ], mode: 'hide' | 'dim', block: {works: [{key, title}], authors, words}}
  //   作者は完全一致、キーワードはタイトルとタグに含まれていれば一致 (全角半角・大小は区別しない)
  //   ミュートは隠す / 薄く (一時的に表示もできる)。ブロック (block) は完全に出さない (件数にも「表示する」にも入れない)。
  //   ブロックはランキング・検索の一覧に加えて、おすすめや作者の作品一覧など、ページの中の作品へのリンクにも効く (ui.js の sweepBlocked)
  NH.muteOf = (d) => Object.assign({ authors: [], words: [], mode: 'hide' }, d.mute);
  NH.blockOf = (d) => Object.assign({ works: [], authors: [], words: [] }, NH.muteOf(d).block);
  const norm = NH.norm;
  // 作者・キーワードに一致した理由 (無ければ null)。info = {title, author, tags}
  const matchReason = (authors, words, info) => {
    const author = norm(info.author);
    if (author && authors.some((a) => norm(a) === author)) return '作者: ' + info.author;
    const hay = [info.title, ...(info.tags || [])].map(norm);
    const w = words.find((x) => norm(x) && hay.some((h) => h.includes(norm(x))));
    return w ? 'キーワード: ' + w : null;
  };
  NH.muteReason = (d, info) => { const m = NH.muteOf(d); return matchReason(m.authors, m.words, info); };
  // info = {key, title, author, tags}。作者が分からないとき (おすすめなどのリンク) は、作品とキーワードだけで判定する
  NH.blockReason = (d, info) => {
    const b = NH.blockOf(d);
    if (info.key && b.works.some((w) => w.key === info.key)) return 'この作品';
    return matchReason(b.authors, b.words, info);
  };
  NH.hasBlock = (d) => { const b = NH.blockOf(d); return !!(b.works.length || b.authors.length || b.words.length); };
  // ブロックの追加と解除。kind: 'works' (value = {key, title}、解除は key) | 'authors' | 'words'
  NH.addBlock = (kind, value) => NH.update((d) => {
    d.mute = NH.muteOf(d);
    const b = (d.mute.block = NH.blockOf(d));
    if (kind === 'works') {
      if (value && value.key && !b.works.some((w) => w.key === value.key)) b.works.push({ key: value.key, title: String(value.title || '') });
      return;
    }
    const v = String(value || '').trim();
    if (v && !b[kind].some((x) => norm(x) === norm(v))) b[kind].push(v);
  });
  NH.removeBlock = (kind, value) => NH.update((d) => {
    d.mute = NH.muteOf(d);
    const b = (d.mute.block = NH.blockOf(d));
    b[kind] = b[kind].filter((x) => (kind === 'works' ? x.key : x) !== value);
  });
  // 追加 (重複は足さない)。kind: 'authors' | 'words'
  NH.addMute = (kind, value) => NH.update((d) => {
    const v = String(value || '').trim();
    d.mute = NH.muteOf(d);
    if (v && !d.mute[kind].some((x) => norm(x) === norm(v))) d.mute[kind].push(v);
  });
  NH.removeMute = (kind, value) => NH.update((d) => {
    d.mute = NH.muteOf(d);
    d.mute[kind] = d.mute[kind].filter((x) => x !== value);
  });
});
