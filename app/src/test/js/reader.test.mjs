// reader.js (自動スクロール・読み上げ) の、DOM に依存しない部分の単体テスト。実行: node app/src/test/js/reader.test.mjs
// 段落の取り出し・自動スクロールの動きは DOM が要るので、ブラウザでの確認 (tools/ の手順) で見る。
import fs from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';

const CODE = fs.readFileSync(fileURLToPath(new URL('../../main/assets/js/ui/reader.js', import.meta.url)), 'utf8');
const ctx = {
  window: {}, console, requestAnimationFrame: () => 0, cancelAnimationFrame: () => {},
  location: { hostname: 'ncode.syosetu.com' },
  document: { head: { appendChild: () => {} }, documentElement: { appendChild: () => {} }, createElement: () => ({ style: {} }), addEventListener: () => {}, removeEventListener: () => {} },
};
ctx.window = ctx; ctx.addEventListener = () => {}; ctx.removeEventListener = () => {};
vm.createContext(ctx);
vm.runInContext(CODE, ctx);
const R = ctx.NH_READER;

const tests = [];
const test = (name, fn) => tests.push([name, fn]);

test('SPEEDS: 10 段階で、だんだん速くなる', () => {
  assert.equal(R.SPEEDS.length, 10);
  for (let i = 1; i < R.SPEEDS.length; i++) assert.ok(R.SPEEDS[i] > R.SPEEDS[i - 1], `段階 ${i + 1}`);
  assert.ok(R.SPEEDS[0] >= 5 && R.SPEEDS[9] <= 400); // 遅すぎず、速すぎない
});

test('clampLevel / speedOf: 範囲に収め、おかしな値は既定 (4) に', () => {
  assert.equal(R.clampLevel(0), 1);
  assert.equal(R.clampLevel(99), 10);
  assert.equal(R.clampLevel('7'), 7);
  assert.equal(R.clampLevel(3.6), 4);
  for (const bad of [undefined, null, NaN, 'abc', {}]) assert.equal(R.clampLevel(bad), 4, String(bad));
  assert.equal(R.speedOf(4), R.SPEEDS[3]);
  assert.equal(R.speedOf(undefined), R.SPEEDS[3]);
  assert.equal(R.speedOf(100), R.SPEEDS[9]);
});

test('clean: ゼロ幅の文字を除き、空白をそろえる', () => {
  assert.equal(R.clean('  こんにちは​  \n 世界﻿ '), 'こんにちは 世界');
  assert.equal(R.clean('​‌‍'), '');
  assert.equal(R.clean(null), '');
  assert.equal(R.clean('改\n行　全角'), '改 行 全角');
});

let failed = 0;
for (const [name, fn] of tests) {
  try { fn(); console.log('ok  ', name); } catch (e) { failed++; console.log('FAIL', name, '\n    ', e.message); }
}
console.log(failed ? `${failed} 件失敗` : `${tests.length} 件すべて成功`);
process.exit(failed ? 1 : 0);
