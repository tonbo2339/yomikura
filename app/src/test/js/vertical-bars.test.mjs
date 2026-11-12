// ui-vertical.js (カクヨムの縦組み): 縦組みを切り替え直しても、スクロールの聞き手が増えないことの単体テスト。実行: node app/src/test/js/vertical-bars.test.mjs
import vm from 'node:vm';
import assert from 'node:assert/strict';
import { readJs } from './scripts.mjs';

const timers = [];
const scrollListeners = [];
const cls = new Set();
let vertical = false;
const ctx = {
  NH: { load: () => ({ settings: {} }), flagOf: () => true, off: { siteBar() {} } },
  document: {
    body: { classList: { contains: () => vertical } },
    documentElement: { classList: { add: (c) => cls.add(c), remove: (c) => cls.delete(c), toggle: (c, on) => (on ? cls.add(c) : cls.delete(c)) } },
    scrollingElement: { scrollLeft: 0, scrollWidth: 1000, clientWidth: 100 },
    getElementById: () => null, addEventListener() {},
  },
  addEventListener: (t, f) => { if (t === 'scroll') scrollListeners.push(f); },
  setTimeout: (f) => timers.push(f),
  Date,
};
ctx.window = ctx;
vm.createContext(ctx);
vm.runInContext(readJs('ui/ui-vertical.js'), ctx);
const V = ctx.NH_UI_VERTICAL({ adapter: { id: 'kakuyomu', bodySel: 'x' }, mk() {}, cur: { episode: 1 }, isShort: false });
assert.ok(V);
const syncs = timers.splice(0).slice(0, 3); // 800 / 2500 / 4500 ms の同期 (同じ関数)
const runSync = () => { syncs[0](); timers.length = 0; };
vertical = true; runSync();
assert.equal(scrollListeners.length, 1);
vertical = false; runSync();
vertical = true; runSync();
assert.equal(scrollListeners.length, 1); // 切り替え直しても増えない
console.log('vertical-bars ok');
