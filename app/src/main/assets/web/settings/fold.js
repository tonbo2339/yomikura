// ---- 話ページの下の欄 (page-fold.html): 畳むボタンの名前を、画面の文に入れる ----
// 名前は js/site/sites.js の NH_FOLDS の label が唯一の定義 (ページ側のボタンと同じ)。HTML には data-fold-label="<key>" の空の枠だけ置く
(window.NH_FOLDS || []).forEach((f) => {
  document.querySelectorAll('[data-fold-label="' + f.key + '"]').forEach((el) => { el.textContent = f.label; });
});
