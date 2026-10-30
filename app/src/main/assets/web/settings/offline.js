// ---- オフライン保存の設定 ----
// 「Wi-Fi のときだけ保存する」をオフにしたら、Wi-Fi を待っている保存をすぐ進める (保存の側は回線が変わったときにしか見直さないため)。
// スイッチ自体の保存は data-setting の共通の処理 (bindToggles) が行うので、保存されたあとに動かす
$('offlineWifiOnly').addEventListener('change', (e) => {
  if (e.target.checked) return;
  setTimeout(() => { if (NH.off.queueList().some((j) => j.status === 'WAIT_WIFI')) NH.off.queueStart(); }, 0);
});
