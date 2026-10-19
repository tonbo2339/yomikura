// なろうのログイン画面 (syosetu.com/login/input/) だけで動く処理。
// 「次回から自動的にログイン」(name=skip) を入れた状態にして、外したら注意を出す。
// このアプリの「ログイン済み」は、このチェックで残る印で判定している (設定 → ログイン)。
// ID とパスワードには触らない。送信もしない。
(() => {
  if (location.hostname !== 'syosetu.com' || !location.pathname.startsWith('/login/input')) return;
  if (document.documentElement.dataset.nhLogin) return; // 二重に入れない
  document.documentElement.dataset.nhLogin = '1';

  const box = document.querySelector('input[type="checkbox"][name="skip"]');
  if (!box) return;
  box.checked = true; // 次回から自動的にログインを入れた状態にする (読み込むたびに、この値から始まる)

  // 外したときの注意 (吹き出し)
  const note = document.createElement('div');
  note.textContent = 'このチェックを入れないと、アプリがログイン状態を確認できず、正常に動作しません。';
  note.style.cssText = 'margin:8px 0 0;padding:10px 12px;border-radius:10px;background:#fff4e5;color:#8a4b00;' +
    'font:600 13px/1.5 sans-serif;border:1px solid #f0c27a;';
  note.hidden = true;
  const field = box.closest('label') || box.parentElement;
  field.insertAdjacentElement('afterend', note);

  const paint = () => { note.hidden = box.checked; };
  box.addEventListener('change', paint);
  paint();
})();
