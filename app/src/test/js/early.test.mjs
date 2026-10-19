// early.js (描画前に入れる CSS) の、ヘッダー・フッター (bars) の切り替えの単体テスト。実行: node app/src/test/js/early.test.mjs (Node 18 以上)
// DOM は使わず、<style> を作って付ける部分だけの見せかけを用意する。
import fs from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';

const JS = (name) => fileURLToPath(new URL('../../main/assets/js/' + name, import.meta.url));
const read = (name) => fs.readFileSync(JS(name), 'utf8');

// host / path のページで early.js を動かす。戻り値: {styles: 付いた <style>, nav(path): history.pushState で移る, back(path): 戻る操作}
function open(host, path) {
  const styles = [];
  const listeners = {};
  const location = { hostname: host, pathname: path, hash: '', search: '' };
  const documentElement = { appendChild: (el) => styles.push(el) };
  const history = {
    pushState(_s, _t, url) { location.pathname = url; },
    replaceState(_s, _t, url) { location.pathname = url; },
  };
  const ctx = {
    location, history, console,
    document: { documentElement, head: null, readyState: 'complete', createElement: () => ({}), addEventListener() {}, querySelector: () => null },
    localStorage: { getItem: () => null, setItem: () => {} },
    addEventListener: (type, fn) => { listeners[type] = fn; },
  };
  ctx.window = ctx;
  vm.createContext(ctx);
  vm.runInContext(read('site/sites.js'), ctx);
  vm.runInContext(read('site/early.js'), ctx);
  return {
    styles,
    bars: () => styles.find((s) => s.id === 'nh-bars'),
    nav: (p) => ctx.history.pushState(null, '', p),
    back: (p) => { location.pathname = p; listeners.popstate(); },
  };
}

const tests = [];
const test = (name, fn) => tests.push([name, fn]);

test('読書ページ: ヘッダー・フッターの CSS が有効 (media = all)', () => {
  const p = open('ncode.syosetu.com', '/n9669bk/1/');
  assert.equal(p.bars().media, 'all');
  assert.ok(p.bars().textContent.includes('.l-header'));
});
test('ほかのページ (ランキング・目次・トップ): 無効 (media = not all)。常に隠すものとは別の <style>', () => {
  for (const [h, path] of [['yomou.syosetu.com', '/rank/daily/'], ['ncode.syosetu.com', '/n9669bk/'], ['syosetu.com', '/']]) {
    const p = open(h, path);
    assert.equal(p.bars().media, 'not all', h + path);
    assert.ok(p.styles.some((s) => !s.id && s.textContent.includes('c-applink')), 'アプリ宣伝は常に隠す');
    assert.ok(!p.styles.some((s) => !s.id && s.textContent.includes('.l-header')), 'ヘッダーが常に隠す側に入っている');
  }
});
test('カクヨムのページ内の移動: 目次 → 話で隠し、話 → 目次で出す (pushState と戻る操作)', () => {
  const p = open('kakuyomu.jp', '/works/1');
  assert.equal(p.bars().media, 'not all');
  p.nav('/works/1/episodes/2');
  assert.equal(p.bars().media, 'all');
  p.nav('/works/1');
  assert.equal(p.bars().media, 'not all');
  p.nav('/works/1/episodes/3');
  p.back('/works/1');
  assert.equal(p.bars().media, 'not all');
});
test('ハーメルン: 話ページ (/novel/ID/N.html) だけ隠す', () => {
  assert.equal(open('syosetu.org', '/novel/123/4.html').bars().media, 'all');
  assert.equal(open('h.syosetu.org', '/novel/123/4.html').bars().media, 'all'); // ハーメルンの R-18 も同じ扱い (サイトを見分けられず、何も効かなかった)
  assert.equal(open('syosetu.org', '/novel/123/').bars().media, 'not all');
  assert.equal(open('syosetu.org', '/').bars().media, 'not all');
});
test('対応サイトでなければ何も付けない', () => {
  assert.equal(open('example.com', '/').styles.length, 0);
});

let failed = 0;
for (const [name, fn] of tests) {
  try { fn(); console.log('ok  ', name); } catch (e) { failed++; console.log('FAIL', name, '\n    ', e.message); }
}
console.log(failed ? `${failed} 件失敗` : `${tests.length} 件すべて成功`);
process.exit(failed ? 1 : 0);
