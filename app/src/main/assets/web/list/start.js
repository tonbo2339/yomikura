// list.html: 開いたときの初期化 (ジャンルの候補・古い作品の片付け・最初の描画)

$('clearDone').onclick = clearDone;
// ジャンルの候補 (既定 + これまで使ったもの)
(() => {
  const dl = $('tagList');
  const d = load();
  // NSFW モードで隠れている作品のジャンルは候補に出さない
  [...new Set([...PRESET_TAGS, ...d.readLater.filter((x) => !NH.nsfwHides(d, x)).flatMap(NH.tagsOf)])].forEach((t) => { const o = el('option'); o.value = t; dl.appendChild(o); });
})();
// 開いたときに、読了から一定日数たった作品を片付ける (設定で切り替え)
(() => {
  let n = 0;
  NH.update((d) => { n = NH.pruneDone(d); NH.pruneProgress(d); NH.pruneHistory(d); NH.fixLoginTitles(d); });
  render();
  if (NH.unreadable) snack('保存データを読めませんでした。消さないよう、書き込みを止めています。設定 → バックアップ の「読み込む」で、バックアップから復元できます');
  else if (n) snack('読了から日数のたった ' + n + ' 作品を削除しました');
  // 版ごとに足される、開いたときの処理 (差し込み口。<!--#include--> は AppAssets の expandIncludes が展開する)
})();
