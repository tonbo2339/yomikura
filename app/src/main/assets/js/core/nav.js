// Yomikura 共通基盤 (16): ページ内の移動 (カクヨムの話から話へ) の見張り
window.NH.define('nav', function (NH) {
  // ---- ページ内の移動 (カクヨムは Next.js で、話から話へページを読み込み直さずに移る) ----
  // 注入した JS は読み込みのときに 1 回しか動かないので、作品 / 話が変わったら読み込み直す (読書位置・履歴・読了のカードを新しいページで作り直すため)。
  // 一覧どうし (作品でないページの間) の移動は、MutationObserver で足りるので何もしない。
  // workSig: 今のページの作品と話を表す文字列 (作品でなければ '')
  NH.workSig = (url) => { const w = NH.parseWork(url); return w ? w.site + ':' + w.id + ':' + (w.episode == null ? '' : w.episode) : ''; };
  // 作品 / 話が変わったら onChange を 1 度だけ呼ぶ。history の書き換え・popstate・1 秒ごとの確認 (history を先に退避された場合の保険) で見張る。
  // beforeChange: history を書き換える直前に呼ぶ (まだ前の話のうちに、読書位置などを書き切るため。変わったあとでは書けない)
  // 戻り値: 今変わっているかを返す関数
  NH.watchWork = function (onChange, beforeChange) {
    const sig = NH.workSig(location.href);
    let fired = false;
    let timer = 0;
    const changed = () => NH.workSig(location.href) !== sig;
    const check = () => {
      if (fired || !changed()) return;
      fired = true;
      if (timer && typeof clearInterval === 'function') clearInterval(timer);
      onChange();
    };
    ['pushState', 'replaceState'].forEach((m) => {
      const orig = history[m];
      if (typeof orig !== 'function') return;
      history[m] = function () {
        if (beforeChange && !fired) { try { beforeChange(); } catch (e) { /* 書けなくても移動は止めない */ } }
        const r = orig.apply(this, arguments);
        check();
        return r;
      };
    });
    window.addEventListener('popstate', check);
    timer = setInterval(check, 1000);
    return changed;
  };
});
