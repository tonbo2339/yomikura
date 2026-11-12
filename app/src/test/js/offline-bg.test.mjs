// offline/offline-bg.js (保存キューの 1 作品を、画面に出さない WebView で保存する) の単体テスト。実行: node app/src/test/js/offline-bg.test.mjs
// Kotlin の窓口 (NHOffline) と、取り込みの部品 (NH.offline*) の代わりを用意して、自動更新 (refresh) の止め方を確かめる
import fs from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';
import { jsPath } from './scripts.mjs';

const SRC = fs.readFileSync(jsPath('offline/offline-bg.js'), 'utf8');

// job = Kotlin が置く NH_BG_JOB。nh = NH の差し替え。結果は finished に渡された JSON を並べる
async function run(job, nh = {}) {
  const finished = [];
  const marked = [];
  const NH = {
    CHALLENGE: 'challenge',
    adapter: () => ({ id: 'narou' }),
    isChallenge: () => false,
    markRest: (id) => marked.push(id),
    sleep: async () => {},
    offlinePlan: async () => ({ eps: [{ ep: '1' }], todo: [{ ep: '1' }] }),
    offlinePlanFrom: (a, w, eps) => ({ eps, todo: eps }),
    offlineRun: async () => ({ ok: 1, failedTotal: 0, fails: 0, blocked: false, lastErr: '', bytes: 10 }),
    ...nh,
  };
  const ctx = {
    NH,
    NH_BG_JOB: job,
    __nhBgT: 'tok',
    NHOffline: {
      finished: (json) => finished.push(JSON.parse(json)),
      stopRequested: () => false, offline: () => false, waiting: () => false, progress: () => {},
    },
    document: { documentElement: { dataset: {} } },
    JSON, String,
  };
  ctx.window = ctx;
  vm.createContext(ctx);
  vm.runInContext(SRC, ctx);
  await new Promise((r) => setTimeout(r, 0)); // offlineBgRun の非同期の終わりを待つ
  return { finished, marked };
}

const tests = [];
const test = (name, fn) => tests.push([name, fn]);
const refresh = { site: 'narou', id: 'n1', url: 'https://ncode.syosetu.com/n1/', refresh: true };

test('自動更新: 目次の代わりに確認画面が出ていたら、休止して「止められた」と知らせる', async () => {
  const { finished, marked } = await run(refresh, { isChallenge: () => true });
  assert.equal(finished.length, 1);
  assert.equal(finished[0].blocked, true);
  assert.deepEqual([...marked], ['narou']);
});

test('自動更新: 目次の 2 ページ目以降で確認画面 (例外) が出ても、休止して「止められた」と知らせる', async () => {
  const { finished, marked } = await run(refresh, { offlinePlan: async () => { throw new Error('challenge'); } });
  assert.equal(finished[0].blocked, true);
  assert.deepEqual([...marked], ['narou']);
});

test('自動更新: 目次が 0 話に読めたら、静かに終わらず、エラーで止める', async () => {
  const { finished } = await run(refresh, { offlinePlan: async () => ({ eps: [], todo: [] }) });
  assert.equal(finished.length, 1);
  assert.match(finished[0].error, /目次/);
});

test('自動更新: そのほかの例外は、エラーとして終わる (止まったままにしない)', async () => {
  const { finished } = await run(refresh, { offlinePlan: async () => { throw new Error('boom'); } });
  assert.equal(finished[0].error, 'boom');
});

test('自動更新: 目次が読めれば保存へ進み、挿絵の設定 (images) を引き継ぐ', async () => {
  let plan;
  const { finished } = await run({ ...refresh, images: false }, { offlineRun: async (a, w, p) => { plan = p; return { ok: 1, failedTotal: 0, fails: 0, blocked: false, lastErr: '', bytes: 1 }; } });
  assert.equal(plan.images, false);
  assert.equal(finished[0].ok, 1);
});

test('キューからの保存 (refresh でない): 渡された話の一覧で計画を作る。目次は読まない', async () => {
  let read = 0;
  const { finished } = await run({ site: 'narou', id: 'n1', url: 'u', title: 't', total: 5, eps: [{ ep: '3' }] }, { offlinePlan: async () => { read++; return {}; } });
  assert.equal(read, 0);
  assert.equal(finished[0].ok, 1);
});

let failed = 0;
for (const [name, fn] of tests) {
  try { await fn(); console.log('ok   ' + name); } catch (e) { failed++; console.log('FAIL ' + name + '\n     ' + e.message); }
}
console.log(failed ? failed + ' 件失敗' : tests.length + ' 件すべて成功');
process.exit(failed ? 1 : 0);
