// ---- 読書の補助 (音量キー・自動スクロール・読み上げ。Kotlin の MainActivity / reader.js が設定 volumeKeys / autoScrollSpeed / ttsRate を読む) ----
const AUTO_SPEED = { min: 1, max: 10, def: 4 };   // 自動スクロールの速さの段階 (reader.js の SPEEDS と同じ 10 段階)
const TTS_RATE = { min: 5, max: 20, def: 10 };    // 読み上げの速さ (0.1 刻みの整数。10 = 標準の 1.0 倍。保存は 1.0 のような倍率)
const clampTo = (r, v) => Math.min(r.max, Math.max(r.min, Math.round(Number.isFinite(v) ? v : r.def)));
const autoSpeedNow = () => clampTo(AUTO_SPEED, S.autoScrollSpeed == null ? AUTO_SPEED.def : Number(S.autoScrollSpeed));
const ttsRateNow = () => clampTo(TTS_RATE, S.ttsRate == null ? TTS_RATE.def : Number(S.ttsRate) * 10);
function paintReading() {
  $('autoSpeed').value = autoSpeedNow();
  $('autoSpeedV').textContent = autoSpeedNow() + ' / ' + AUTO_SPEED.max;
  $('ttsRate').value = ttsRateNow();
  $('ttsRateV').textContent = (ttsRateNow() / 10).toFixed(1) + ' 倍';
}
$('autoSpeed').oninput = (e) => { S.autoScrollSpeed = clampTo(AUTO_SPEED, Number(e.target.value)); commitSoon(); };
$('ttsRate').oninput = (e) => { S.ttsRate = clampTo(TTS_RATE, Number(e.target.value)) / 10; commitSoon(); };
$$('.stepper[data-k]').forEach((b) => (b.onclick = () => {
  const d = Number(b.dataset.d);
  if (b.dataset.k === 'autoScrollSpeed') S.autoScrollSpeed = clampTo(AUTO_SPEED, autoSpeedNow() + d);
  else S.ttsRate = clampTo(TTS_RATE, ttsRateNow() + d) / 10;
  commitSoon();
}));
