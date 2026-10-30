// web/offline/richtext.js の、DOM に依存しない部分 (色の反転・引用元の取り出し) の単体テスト。実行: node app/src/test/js/richtext.test.mjs
// 色の反転は、ハーメルンの novel_sp_main.js (getInvertedAndHueRotatedColor) と同じ式。実機のページで、サイトの計算と同じ結果になることを確かめた物を見本にしている。
import fs from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';

const CODE = fs.readFileSync(fileURLToPath(new URL('../../main/assets/web/offline/richtext.js', import.meta.url)), 'utf8');
const ctx = { console, Math, String, RegExp, Number, NH: {} };
vm.createContext(ctx);
vm.runInContext(CODE + '\nthis.RICH = RICH;', ctx);
const R = ctx.RICH;

const tests = [];
const test = (name, fn) => tests.push([name, fn]);

test('nightRgb: 明るさだけ反転する (黒は白に、白は黒に。灰色は反対側の灰色に)', () => {
  assert.deepEqual([...R.nightRgb([0, 0, 0])], [255, 255, 255]);
  assert.deepEqual([...R.nightRgb([255, 255, 255])], [0, 0, 0]);
  assert.deepEqual([...R.nightRgb([68, 68, 68])], [187, 187, 187]);
});

test('nightRgb: サイト (ハーメルンの夜間モード) が実際に計算した値と同じ (実機のページで jQuery Color の式と 509 色を突き合わせて、差 0 だった)', () => {
  const site = [
    [[0, 0, 238], [224, 224, 255]], [[0, 128, 0], [73, 255, 73]], [[255, 0, 0], [255, 146, 146]], [[128, 0, 128], [255, 165, 255]],
    [[255, 165, 0], [120, 78, 0]], [[169, 169, 169], [86, 86, 86]], [[207, 100, 4], [232, 111, 4]],
  ];
  for (const [from, to] of site) assert.deepEqual([...R.nightRgb(from)], to, from + '');
});

test('refHtml: 目印 jump.rN から jump.rNe の間を取り、段落の区切りは改行にする。無ければ空', () => {
  const html = '<p><span id="jump.r1"></span><span class="small">1</span> 名前</p><p>本文1</p><p>本文2</p><p><span id="jump.r1e"></span>　</p>' +
    '<p><span id="jump.r2"></span>二つ目</p><p><span id="jump.r2e"></span></p>';
  assert.equal(R.refHtml(html, 1), '<span class="small">1</span> 名前<br>本文1<br>本文2<br>');
  assert.equal(R.refHtml(html, 2), '二つ目<br>');
  assert.equal(R.refHtml(html, 3), ''); // 目印が無い
  assert.equal(R.refHtml(html, '1e'), ''); // 数字以外は受け付けない
  assert.equal(R.refHtml(html, '1)|(.'), '');
});

let failed = 0;
for (const [name, fn] of tests) {
  try { fn(); console.log('ok   ' + name); } catch (e) { failed++; console.log('FAIL ' + name + '\n     ' + e.message); }
}
console.log(failed ? failed + ' 件失敗' : tests.length + ' 件すべて成功');
process.exit(failed ? 1 : 0);
