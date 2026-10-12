// ---- ミュートとブロック (完全に出さない) の追加欄: 種類 (キーワード・タグ / 作者) を選んで入力する。作りは共通 ----
const addKind = { mute: 'words', block: 'words' }; // 欄 → 選んでいる種類
function setupAddForm(id, addFn, what) {
  $$('#' + id + 'Kind button').forEach((b) => (b.onclick = () => {
    addKind[id] = b.dataset.v;
    $(id + 'Input').placeholder = addKind[id] === 'words' ? '例: 追放' : '作者名 (完全一致)';
    paintMute();
  }));
  const submit = () => {
    const v = $(id + 'Input').value.trim();
    if (!v) return;
    addFn(addKind[id], v); $(id + 'Input').value = ''; paint(); snack('「' + v + '」を' + what + 'しました');
  };
  $(id + 'Add').onclick = submit;
  $(id + 'Input').addEventListener('keydown', (e) => { if (e.key === 'Enter') submit(); });
}
setupAddForm('mute', NH.addMute, 'ミュート');
setupAddForm('block', NH.addBlock, 'ブロック');