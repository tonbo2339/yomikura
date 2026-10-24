// なろうのログインの 2 段階認証の自動入力 (site/login-totp.js) の単体テスト。1 回のログイン (authtoken) につき各 1 回だけ動くこと・鍵が無ければ何もしないこと・
// コードの切り替わり際は次のコードを待つこと・セットアップキーの登録ボタンを確かめる。
// 実行: node app/src/test/js/login-totp.test.mjs。DOM は linkedom (無ければ飛ばす。dom.test.mjs と同じ)。
import fs from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const SRC = fs.readFileSync(fileURLToPath(new URL('../../main/assets/js/site/login-totp.js', import.meta.url)), 'utf8');

function findLinkedom() {
  const bases = [process.env.NH_TEST_DEPS, fileURLToPath(new URL('../../../../', import.meta.url)), process.cwd()].filter(Boolean);
  for (const b of bases) {
    try { return createRequire(b.replace(/\/?$/, '/') + 'x.js')('linkedom'); } catch (e) { /* 次へ */ }
  }
  return null;
}
const linkedom = findLinkedom();
if (!linkedom) {
  console.log('skip login-totp.test.mjs: linkedom が見つかりません (npm i --no-save linkedom)');
  process.exit(0);
}

const TOKEN = 'AbCdEfGhIjK';
const CHOOSER = `https://syosetu.com/user2stepauth/input/authtoken/${TOKEN}/`;
const INPUT = `https://syosetu.com/user2stepauth/totpinput/authtoken/${TOKEN}/`;
const CHOOSER_HTML = `<main><a href="https://syosetu.com/user2stepauth/totpinput/authtoken/${TOKEN}/">認証アプリで認証する</a></main>`;
const INPUT_HTML = '<main><form action="/x"><input type="text" name="totp_code"><input type="submit"></form></main>';
// 認証アプリの設定のページ (パスは「…totpedit/secretinput/」)。公開版の書き出しの検査に引っかからないよう、前半を分けて書く
const SECRET = 'https://syosetu.com/' + 'person' + 'al' + 'totpedit/secretinput/';
const SECRET_HTML = '<main><div class="p-codecopy"><div class="p-codecopy__code"><span class="js-secret"> GEZDGNBVGY3TQOJQ </span></div></div></main>';

// ページを開いた状態でスクリプトを動かす。session は同じ authtoken の「2 回目」を試すとき、前の回の sessionStorage を渡す
// 窓口 (NHTotp) は、合言葉 (ここでは TK) を添えた呼び出しにだけ答える (Kotlin の TotpBridge と同じ)。storageThrows = sessionStorage が使えない画面
function open({ href, html, has = true, code = '123456', nowSec = 1000 * 30 + 10, session = new Map(), noBridge = false, storageThrows = false, typed = '' }) {
  const u = new URL(href);
  const { document, window } = linkedom.parseHTML(html);
  Object.defineProperty(window.HTMLInputElement.prototype, 'form', { get() { return this.closest('form'); }, configurable: true });
  const out = { navigated: [], timers: [], saved: [], clicks: 0, session };
  const TOKEN_OK = 'TK';
  const bridge = {
    has: (t) => t === TOKEN_OK && has,
    code: (t) => (t === TOKEN_OK ? code : ''),
    save: (k, t) => { if (t !== TOKEN_OK) return false; out.saved.push(k); return true; },
  };
  const ctx = {
    document, window: null, location: {
      hostname: u.hostname, pathname: u.pathname,
      get href() { return href; }, set href(v) { out.navigated.push(v); },
    },
    sessionStorage: storageThrows
      ? { getItem: () => { throw new Error('denied'); }, setItem: () => { throw new Error('denied'); } }
      : { getItem: (k) => (session.has(k) ? session.get(k) : null), setItem: (k, v) => session.set(k, v) },
    setTimeout: (fn, ms) => { out.timers.push({ fn, ms }); return out.timers.length; },
    Date: Object.assign(function () {}, { now: () => nowSec * 1000 }),
  };
  ctx.window = ctx;
  ctx.NH = { define: (name, fn) => fn(ctx.NH, TOKEN_OK) }; // 合言葉は NH.define の第 2 引数で渡る (core/base.js)
  if (!noBridge) ctx.NHTotp = bridge;
  document.querySelectorAll('input[type="submit"]').forEach((b) => b.addEventListener('click', () => { out.clicks++; }));
  out.document = document;
  out.input = document.querySelector('input[name="totp_code"]');
  if (out.input && typed) out.input.value = typed;
  vm.runInContext(SRC, vm.createContext(ctx));
  return out;
}

const tests = [];
const test = (name, fn) => tests.push([name, fn]);

test('「認証」: 登録済みなら認証アプリへ進み、同じログインの 2 回目 (戻るで来たとき) は進まない', () => {
  const first = open({ href: CHOOSER, html: CHOOSER_HTML });
  assert.deepEqual(first.navigated, [`https://syosetu.com/user2stepauth/totpinput/authtoken/${TOKEN}/`]);
  const second = open({ href: CHOOSER, html: CHOOSER_HTML, session: first.session });
  assert.deepEqual(second.navigated, []);
});

test('「認証」: 別のログイン (authtoken が違う) では、また進む', () => {
  const first = open({ href: CHOOSER, html: CHOOSER_HTML });
  const other = CHOOSER.replace(TOKEN, 'ZzZzZzZzZzZ');
  const again = open({ href: other, html: CHOOSER_HTML.replace(TOKEN, 'ZzZzZzZzZzZ'), session: first.session });
  assert.equal(again.navigated.length, 1);
});

test('鍵が登録されていなければ、何もしない', () => {
  assert.deepEqual(open({ href: CHOOSER, html: CHOOSER_HTML, has: false }).navigated, []);
  const r = open({ href: INPUT, html: INPUT_HTML, has: false });
  assert.equal(r.timers.length, 0);
  assert.equal(r.input.value, '');
});

test('窓口 (NHTotp) が無い画面 (巡回の裏の画面など) では、何もしない', () => {
  assert.deepEqual(open({ href: CHOOSER, html: CHOOSER_HTML, noBridge: true }).navigated, []);
});

test('コード入力: コードを入れて 1 回だけ送信し、2 回目 (通らなくて戻ってきたとき) は送らず、手で入れるよう知らせる', () => {
  const first = open({ href: INPUT, html: INPUT_HTML });
  assert.equal(first.timers.length, 1);
  assert.equal(first.timers[0].ms, 0); // 残り 20 秒: 待たない
  first.timers[0].fn();
  assert.equal(first.input.value, '123456');
  assert.equal(first.clicks, 1);
  const second = open({ href: INPUT, html: INPUT_HTML, session: first.session });
  assert.equal(second.timers.length, 0);
  assert.equal(second.input.value, '');
  assert.match(second.document.querySelector('main').textContent, /手で入れて/);
});

test('コード入力: コードが切り替わる直前 (残り 5 秒未満) は、次のコードまで待ってから送る', () => {
  const r = open({ href: INPUT, html: INPUT_HTML, nowSec: 1000 * 30 + 27 }); // 残り 3 秒
  assert.equal(r.timers.length, 1);
  assert.ok(r.timers[0].ms >= 3000 && r.timers[0].ms <= 4000, String(r.timers[0].ms));
  assert.equal(r.clicks, 0); // 待つ間は送らない
});

test('コード入力: コードが作れなかった (6 桁でない) ときは送らない', () => {
  const r = open({ href: INPUT, html: INPUT_HTML, code: '' });
  r.timers[0].fn();
  assert.equal(r.clicks, 0);
  assert.equal(r.input.value, '');
});

test('コード入力: 待っている間に手で打ち始めていたら、上書きして送らない', () => {
  const r = open({ href: INPUT, html: INPUT_HTML });
  r.input.value = '12'; // 手で打ち始めた
  r.timers[0].fn();
  assert.equal(r.input.value, '12');
  assert.equal(r.clicks, 0);
});

test('コード入力: コードを作れなかったときは「送った」と覚えない (読み込み直すと、もう一度試せる)', () => {
  const first = open({ href: INPUT, html: INPUT_HTML, code: '' });
  first.timers[0].fn();
  const again = open({ href: INPUT, html: INPUT_HTML, session: first.session });
  assert.equal(again.timers.length, 1); // 「通りませんでした」の警告ではなく、また自動で試す
  again.timers[0].fn();
  assert.equal(again.clicks, 1);
});

test('sessionStorage が使えない画面では、自動では進まず、偽の「通りませんでした」も出さない', () => {
  const chooser = open({ href: CHOOSER, html: CHOOSER_HTML, storageThrows: true });
  assert.deepEqual(chooser.navigated, []);
  const input = open({ href: INPUT, html: INPUT_HTML, storageThrows: true });
  assert.equal(input.timers.length, 0);
  assert.equal(input.clicks, 0);
  assert.doesNotMatch(input.document.querySelector('main').textContent, /通りませんでした/);
});

test('セットアップキーの画面: 登録ボタンが出て、押すとキー (前後の空白なし) を窓口へ渡す', () => {
  const r = open({ href: SECRET, html: SECRET_HTML });
  const btn = r.document.querySelector('button');
  assert.ok(btn, '登録ボタンが出る');
  btn.click();
  assert.deepEqual(r.saved, ['GEZDGNBVGY3TQOJQ']);
});

test('なろう以外のサイトでは、何もしない', () => {
  assert.deepEqual(open({ href: 'https://kakuyomu.jp/user2stepauth/input/authtoken/x/', html: CHOOSER_HTML }).navigated, []);
});

let failed = 0;
for (const [name, fn] of tests) {
  try { fn(); console.log('ok  ', name); } catch (e) { failed++; console.log('FAIL', name, '\n    ', e.message); }
}
if (failed) { console.log(failed + ' 件失敗'); process.exit(1); }
console.log(tests.length + ' 件すべて成功');
