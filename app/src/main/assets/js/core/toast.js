// Yomikura 共通基盤 (17): ページの下の小さなお知らせ
window.NH.define('toast', function (NH) {
  // action = {label, fn} を渡すと、ボタン (「元に戻す」など) を付けて少し長く出す
  NH.toast = function (msg, action) {
    const t = document.createElement('div');
    t.className = 'nh-toast'; // スタイルは ui.js が注入
    t.textContent = msg;
    if (action) {
      const b = document.createElement('button');
      b.textContent = action.label;
      b.addEventListener('click', () => { t.remove(); action.fn(); });
      t.appendChild(b);
    }
    document.documentElement.appendChild(t);
    setTimeout(() => t.remove(), action ? 5000 : 1800);
  };
});
