// Yomikura 共通基盤 (11): バックアップの形式 (書き出し・読み込み)
window.NH.define('backup', function (NH) {
  // 設定の書き出し・読み込みの形式名 (JSON の app。Kotlin の Edition.BACKUP_APP)。書き出しのファイル名の頭にも使う
  NH.BACKUP_APP = (window.NH_EDITION || {}).backupApp || 'yomikura';
  // 読み込みで受け付ける形式名 (Kotlin の Edition.BACKUP_ACCEPTS)。版によっては、ほかの版で書き出したファイルも読める
  NH.BACKUP_APPS = (window.NH_EDITION || {}).backupApps || [NH.BACKUP_APP];
  NH.isBackupApp = (name) => NH.BACKUP_APPS.includes(name);

  // ---- バックアップの形 (設定の「書き出す」・読み込み。ほかの版にあるバックアップの送信も、Kotlin が同じ形で作る) ----
  // {app: NH.BACKUP_APP, format:1, exportedAt, appVersion, parts:{settings, list, progress, history, mute}}。
  // 項目ごとのファイルも同じ形 (parts が 1 つだけ)。読み込み (settings.html の nhImport) は入っている項目だけを置き換える。
  // [項目, 名前, 保存データのキー, 型]
  NH.BACKUP_PARTS = [
    ['settings', '設定', 'settings', 'object'],
    ['list', 'リスト', 'readLater', 'array'],
    ['progress', '読書位置', 'progress', 'object'],
    ['history', '閲覧履歴', 'history', 'array'],
    ['mute', 'ミュート', 'mute', 'object'],
  ];
  // 設定のうち、その時だけの物 (アイコンの「後で」の予約)。バックアップから戻さない (古いファイルを読み込むと、次の起動でアイコンが勝手に変わるため)
  NH.BACKUP_TRANSIENT_SETTINGS = ['launcherIconPending'];
  // バックアップの設定から、読み込んで置き換える設定を作る (初回の画面はもう一度出さない)
  NH.restoredSettings = (v) => {
    const s = Object.assign({}, v, { welcomed: true });
    NH.BACKUP_TRANSIENT_SETTINGS.forEach((k) => delete s[k]);
    return s;
  };
  NH.backupJson = function (d, keys, appVersion) {
    const parts = {};
    keys.forEach((k) => {
      const b = NH.BACKUP_PARTS.find((x) => x[0] === k);
      parts[k] = d[b[2]] === undefined ? (b[3] === 'array' ? [] : {}) : d[b[2]];
    });
    return JSON.stringify({ app: NH.BACKUP_APP, format: 1, exportedAt: new Date().toISOString(), appVersion: appVersion || '', parts });
  };
});
