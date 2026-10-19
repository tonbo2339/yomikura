// Yomikura 共通基盤 (4): NSFW モード (R18 を隠す・アクセスしない)
window.NH.define('nsfw', function (NH) {
  // NSFW モード (settings.nsfw): オンの間は、R18 のサイトへ一切アクセスせず (Kotlin 側で遮断)、リスト・履歴・ランキングにも R18 の作品を出さない。
  // R18 の作品 = narou18 の作品、または r18 の印のある作品 (ハーメルンの R-18 タグ)。履歴は同じキーのリストの作品の印も見る
  // 設定に無いときの既定は版で決まる (window.NH_EDITION.r18OptIn。Kotlin の NHData.nsfwOn と同じ)
  NH.R18_OPT_IN = !!(window.NH_EDITION || {}).r18OptIn;

  NH.nsfwOn = (d) => { const v = d && d.settings && d.settings.nsfw; return v === undefined || v === null ? NH.R18_OPT_IN : !!v; };
  NH.isR18Item = (d, it) => it.site === 'narou18' || !!it.r18 || !!((d.readLater || []).find((x) => x.key === it.key) || {}).r18;
  NH.nsfwHides = (d, it) => NH.nsfwOn(d) && NH.isR18Item(d, it);
});
