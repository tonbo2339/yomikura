// なろうのログインの 2 段階認証を、認証アプリのコード (NHTotp) で自動で進める。鍵を登録していないときは何もしない。
//  - 「認証」(/user2stepauth/input/): 「認証アプリで認証する」へ進む
//  - 認証コードの入力 (/user2stepauth/totpinput/): 6 桁を入れて送る
//  - 認証アプリの設定のセットアップキー (…totpedit/secretinput/): 「ヨミクラにも登録する」を出す
// 自動で進めるのは、同じログイン (authtoken) につき各 1 回だけ。コードが違ったときに繰り返さず、「戻る」でメール認証を選べるようにする。
// 窓口 (NHTotp) には、ページ本体だけが持つ合言葉 (NH.define の第 2 引数) を添える (iframe からは呼べないように)。
// ページの作りが変わったら、下の SEL を直す。
NH.define('login-totp', (NH, T) => {
  if (location.hostname !== 'syosetu.com' || !window.NHTotp) return;

  const SEL = {
    toTotp: 'a[href*="/user2stepauth/totpinput/"]', // 「認証アプリで認証する」
    codeInput: 'input[name="totp_code"]',
    secret: '.js-secret', // セットアップキーの文字
  };
  const SAFE_SEC = 5; // コードが切り替わるまでこれ未満なら、次のコードまで待つ

  const path = location.pathname;
  const token = (path.match(/\/authtoken\/([^/]+)/) || [])[1] || '';

  // 「同じログインで自動に進んだか」を覚える sessionStorage。使えない (例外) ときは、自動では進まない (前に進んだか分からないので、繰り返さないため)
  let storageOk = true;
  const key = (step) => 'nhTotp:' + step + ':' + token;
  const tried = (step) => {
    try { return sessionStorage.getItem(key(step)) === '1'; } catch (e) { storageOk = false; return true; }
  };
  const markTried = (step) => { try { sessionStorage.setItem(key(step), '1'); } catch (e) { /* 使えない */ } };

  const banner = (text, warn) => {
    const b = document.createElement('div');
    b.textContent = text;
    b.style.cssText = 'margin:12px 16px;padding:10px 12px;border-radius:10px;font:600 13px/1.5 sans-serif;border:1px solid ' +
      (warn ? '#f0c27a;background:#fff4e5;color:#8a4b00' : '#9cc9f5;background:#e8f3ff;color:#0b4a8a');
    const anchor = document.querySelector('main') || document.body;
    anchor.insertBefore(b, anchor.firstChild);
    return b;
  };

  const secLeft = () => 30 - ((Date.now() / 1000) % 30);

  // ---- 「認証」: 認証アプリで認証する へ ----
  if (path.startsWith('/user2stepauth/input/')) {
    if (!token || !NHTotp.has(T) || tried('forward') || !storageOk) return;
    const a = document.querySelector(SEL.toTotp);
    if (!a) return;
    markTried('forward');
    banner('認証アプリの画面へ進みます…');
    location.href = a.href;
    return;
  }

  // ---- 認証コードの入力 ----
  if (path.startsWith('/user2stepauth/totpinput/')) {
    if (!token || !NHTotp.has(T)) return;
    const input = document.querySelector(SEL.codeInput);
    if (!input || !input.form) return;
    const already = tried('send');
    if (!storageOk) return; // 前に送ったか分からないので、自動では入れない (手で入れられる)
    if (already) { banner('自動で入力したコードが通りませんでした。認証アプリのコードを手で入れてください。', true); return; }
    const info = banner('認証コードを入力します…');
    const send = () => {
      if (input.value) { info.textContent = '入力中のため、自動の入力をやめました。'; return; } // 待っている間に手で打ち始めていたら、上書きして送らない
      const code = NHTotp.code(T);
      if (!/^\d{6}$/.test(code)) { info.textContent = '認証コードを作れませんでした。手で入れてください。'; return; }
      markTried('send'); // 送る直前に覚える (コードを作れなかったときは覚えない = 読み込み直したら、もう一度試せる)
      input.value = code;
      const btn = input.form.querySelector('input[type="submit"],button[type="submit"]');
      if (btn) btn.click(); else input.form.submit();
    };
    const wait = secLeft() < SAFE_SEC ? Math.ceil((secLeft() + 0.5) * 1000) : 0; // 切り替わり際は次のコードを待つ
    if (wait) info.textContent = '次の認証コードを待っています…';
    setTimeout(send, wait);
    return;
  }

  // ---- 認証アプリの設定: セットアップキーをヨミクラに登録 ----
  if (/totpedit\/secretinput/.test(path)) {
    const keyEl = document.querySelector(SEL.secret);
    if (!keyEl) return;
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.style.cssText = 'display:block;margin:12px 0;padding:10px 14px;border-radius:10px;border:1px solid #1e88e5;' +
      'background:#fff;color:#1e88e5;font:600 14px/1.4 sans-serif;';
    const paint = () => { btn.textContent = NHTotp.has(T) ? 'ヨミクラに登録し直す' : 'ヨミクラにも登録する (ログインのコードを自動で入れる)'; };
    paint();
    const note = document.createElement('div');
    note.style.cssText = 'font:13px/1.5 sans-serif;margin:4px 0;';
    btn.addEventListener('click', () => {
      const ok = NHTotp.save(keyEl.textContent.trim(), T);
      note.textContent = ok ? '登録しました。このあと「次へ」で認証コードを入れて、設定を完了してください。' : '登録できませんでした。';
      paint();
    });
    const box = keyEl.closest('.p-codecopy') || keyEl.parentElement;
    box.insertAdjacentElement('afterend', btn);
    btn.insertAdjacentElement('afterend', note);
  }
});
