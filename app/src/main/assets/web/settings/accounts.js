// ---- ログイン (各小説サイト) ----
// 状況は Cookie の有無で見る (NHStore.siteLogins)。最後の更新の確認で「ログイン切れ」と出たサイトは、その旨も出す。
// なろうは R18 のサイト (ノクターン・ムーンライト・ミッドナイト) と共通のログイン。
const CAN_ACCOUNT = !!(window.NHStore && NHStore.siteLogins);
// ログインの状況は Kotlin に問い合わせる。1 回の描き直しで何度も呼ばれるので、短い間は使い回す (ログイン・ログアウトの直後は dropLogins で捨てる)
let loginsCache = null, loginsAt = 0;
const siteLogins = () => {
  if (loginsCache && Date.now() - loginsAt < 1500) return loginsCache;
  try { loginsCache = JSON.parse(CAN_ACCOUNT ? NHStore.siteLogins() : '{}') || {}; } catch (e) { loginsCache = {}; }
  loginsAt = Date.now();
  return loginsCache;
};
const dropLogins = () => { loginsCache = null; };
// 更新の確認に使えるか = そのサイトにログインしているか (R18 のブックマークはなろうと同じログイン)。ログインを調べられない画面では、問わない
const loggedIn = (id) => !CAN_ACCOUNT || !!siteLogins()[id === 'narou18' ? 'narou' : id];
// 設定で対象にしていて、ログインしているサイトが 1 つでもあるか。無ければ、確認の設定 (起動時・カテゴリ・裏での確認) は押せない
const activeAny = () => NH.COLLECT_IDS.some((id) => NH.collectOn({ settings: S }, id) && loggedIn(id));
const accountShown = (a) => a[4].some((k) => siteOn(k) && (r18Usable() || !NH.R18_SITES.includes(k)));
function accountState(a, logins, log) {
  if (!logins[a[0]]) return 'out';
  return a[5].some((k) => log[k] && log[k].err === 'login') ? 'expired' : 'in';
}
// ログアウトの途中のサイト (終わるまでボタンを押せなくする。二重に動かさない)
const loggingOut = new Set();
// ログアウトの結果 (サイト側の手順は Kotlin の OfficialLogout)。失敗したときは、公式のページ (r.page) を画面で開いて押してもらう
window.nhLogoutResult = (json) => {
  const r = JSON.parse(json);
  const name = ACCOUNTS.find((a) => a[0] === r.site)?.[1] || r.site;
  loggingOut.delete(r.site);
  if (r.busy) { // 動いている途中か、終わってすぐ (アプリが受け付けなかった)
    snack(r.wait > 0 ? name + 'のログアウトは、あと ' + r.wait + ' 秒たってから押せます'
      : r.wait < 0 ? name + 'からログアウトしています…' : 'もう一度押してください');
    paintAccounts();
    return;
  }
  dropLogins();
  refreshLogins();
  if (r.ok || !r.page) { snack(name + 'からログアウトしました'); return; }
  snack(name + 'のサイト側のログインが残っています。公式のページで押してください');
  setTimeout(() => openUrl(r.page), 1500);
};

// 押せるまでの秒数を数えるタイマー (サイトがいくつあっても 1 つだけ)
let countdownTimer = 0;
function paintAccounts() {
  let countdown = false;
  const logins = siteLogins(), log = load().collectLog || {};
  const box = $('accountList');
  box.innerHTML = '';
  let nIn = 0, nAll = 0;
  ACCOUNTS.filter(accountShown).forEach((a) => {
    const st = accountState(a, logins, log);
    nAll++; if (st !== 'out') nIn++;
    const g = addGroup(box, a[1]);
    const row = el('div', 'cell');
    row.append(el('span', 'text', st === 'out' ? '未ログイン' : st === 'expired' ? 'ログイン切れの可能性' : 'ログイン済み'));
    if (st === 'expired') row.lastChild.style.color = 'var(--danger, #d33)';
    g.appendChild(row);
    // ボタンは 2 つを同じ幅で並べる (ログインとログアウトは、状態に関係なく両方出す。デバッグ用)
    const actions = el('div', 'cell');
    actions.style.cssText = 'gap:10px;justify-content:center;padding:12px 16px';
    const login = el('button', 'btn tonal block', 'ログインする'); login.style.flex = '1'; login.onclick = () => { location.href = a[3]; };
    actions.appendChild(login);
    // 押したあとは、終わるまでと終わってから少しの間 (アプリが決める。logoutWait) は押せなくする。画面を開き直しても同じ
    const wait = window.NHStore && NHStore.logoutWait ? NHStore.logoutWait(a[0]) : 0;
    const running = loggingOut.has(a[0]) || wait < 0;
    const busy = running || wait > 0;
    const logout = el('button', 'btn danger block', running ? 'ログアウト中…' : wait > 0 ? 'ログアウト (あと ' + wait + ' 秒)' : 'ログアウト'); logout.style.flex = '1';
    if (wait > 0 && !running) countdown = true;
    login.disabled = running;
    logout.disabled = busy;
    logout.onclick = () => ask(a[1] + 'からログアウトします。' + (a[2] ? a[2] + '。' : '') + 'リストや読書位置は消えません。', 'ログアウト', () => {
      if (!window.NHStore) return;
      // サイト側のログアウトも裏で行う (結果は nhLogoutResult)。古い版はアプリの Cookie だけ消す
      if (NHStore.logoutOfficial) {
        loggingOut.add(a[0]); paintAccounts();
        snack(a[1] + 'からログアウトしています…');
        NHStore.logoutOfficial(a[0]);
        return;
      }
      if (NHStore.logoutSite(a[0])) { dropLogins(); snack(a[1] + 'からログアウトしました'); refreshLogins(); }
    });
    actions.appendChild(logout);
    g.appendChild(actions);
    if (a[2]) box.appendChild(el('div', 'hint', a[2] + '。'));
  });
  $('v-accounts').textContent = nAll ? nIn + ' / ' + nAll + ' ログイン済み' : '';
  clearTimeout(countdownTimer);
  if (countdown) countdownTimer = setTimeout(paintAccounts, 1000); // 残りの秒数を数え、押せるようになったら戻す
}
