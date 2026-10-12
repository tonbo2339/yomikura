// ---- ランキングの行き先 ----
// settings.rankings[サイト] = {url, label, spec?}。spec は下の選択肢 (なろう / カクヨム) の状態、custom はメニューで登録したページ
const NAROU_KINDS = [['top', 'ランキングトップ'], ['total', '総合'], ['attn', '注目度'], ['isekai', '異世界転生/転移'], ['genre', 'ジャンル別']];
const NAROU_PERIODS = [['daily', '日間'], ['weekly', '週間'], ['monthly', '月間'], ['quarter', '四半期'], ['yearly', '年間'], ['total', '累計']];
const NAROU_STATUS = [['total', 'すべて'], ['r', '連載中'], ['er', '完結済'], ['t', '短編']];
const NAROU_ISEKAI = [['1', '恋愛'], ['2', 'ファンタジー'], ['o', '文芸・SF・その他']];
const NAROU_GENRES = [['101', '異世界〔恋愛〕'], ['102', '現実世界〔恋愛〕'], ['201', 'ハイファンタジー'], ['202', 'ローファンタジー'], ['301', '純文学'], ['302', 'ヒューマンドラマ'],
  ['303', '歴史'], ['304', '推理'], ['305', 'ホラー'], ['306', 'アクション'], ['307', 'コメディー'], ['401', 'VRゲーム'], ['402', '宇宙'], ['403', '空想科学'],
  ['404', 'パニック'], ['9901', '童話'], ['9902', '詩'], ['9903', 'エッセイ'], ['9999', 'その他']];
const KK_GENRES = [['all', '総合'], ['fantasy', '異世界ファンタジー'], ['action', '現代ファンタジー'], ['sf', 'SF'], ['love_story', '恋愛'], ['romance', 'ラブコメ'],
  ['drama', '現代ドラマ'], ['horror', 'ホラー'], ['mystery', 'ミステリー'], ['nonfiction', 'エッセイ・ノンフィクション'], ['history', '歴史・時代・伝奇'], ['criticism', '創作論・評論']];
const KK_PERIODS = [['daily', '日間'], ['weekly', '週間'], ['monthly', '月間'], ['yearly', '年間'], ['entire', '累計']];
const KK_LENGTH = [['long', '長編'], ['short', '短編']];
// ハーメルンのランキング (https://syosetu.org/?mode=XXX)
const HAMELN_RANKS = [['rank', '日間 (既定)'], ['rank_week', '週間'], ['rank_month', '月間'], ['rank_3month', '四半期'], ['rank_year', '年間'], ['rank_total', '累計'],
  ['rank_complete_total', '完結累計'], ['rank_new', '新作日間'], ['rank_new_user', 'ルーキー日間'], ['rank_2_day', '二次創作日間'],
  ['rank_ss_add_day', '日間 (短編)'], ['rank_ss_week', '週間 (短編)'], ['rank_ss_month', '月間 (短編)']];
const R18_RANKS = [['top', 'ランキングトップ (既定)'], ['daily', '日間'], ['weekly', '週間'], ['monthly', '月間'], ['quarter', '四半期'], ['yearly', '年間']];
const RANK_DEFAULT = {
  narou: { kind: 'top', period: 'daily', status: 'total', isekai: '1', genre: '201' },
  kakuyomu: { genre: 'all', period: 'daily', length: 'long' },
};
const narouUrl = (p) => {
  const base = 'https://yomou.syosetu.com/rank/';
  if (p.kind === 'top') return base + 'top/';
  if (p.kind === 'attn') return base + 'attnlist/type/total/';
  if (p.kind === 'total') return base + 'list/type/' + p.period + '_' + p.status + '/';
  if (p.kind === 'isekai') return base + 'isekailist/type/' + p.period + '_' + p.isekai + '/';
  return base + 'genrelist/type/' + p.period + '_' + p.genre + '/';
};
const narouLabel = (p) => {
  if (p.kind === 'top' || p.kind === 'attn') return nameOf(NAROU_KINDS, p.kind);
  const what = p.kind === 'total' ? '総合' + (p.status !== 'total' ? ' (' + nameOf(NAROU_STATUS, p.status) + ')' : '')
    : p.kind === 'isekai' ? '異世界 ' + nameOf(NAROU_ISEKAI, p.isekai) : nameOf(NAROU_GENRES, p.genre);
  return nameOf(NAROU_PERIODS, p.period) + ' ' + what;
};
const kkUrl = (p) => 'https://kakuyomu.jp/rankings/' + p.genre + '/' + p.period + '?work_variation=' + p.length;
const kkLabel = (p) => nameOf(KK_PERIODS, p.period) + ' ' + nameOf(KK_GENRES, p.genre) + (p.length === 'short' ? ' (短編)' : '');

const rankingsOf = () => Object.assign({}, load().settings && load().settings.rankings);
function setRanking(key, value) { // value = {url, label, spec} か null (既定に戻す)
  NH.update((d) => {
    d.settings = d.settings || {};
    const r = Object.assign({}, d.settings.rankings);
    if (value) r[key] = value; else delete r[key];
    d.settings.rankings = r;
  });
  paint();
}
function rankSpec(key) { const r = rankingsOf()[key]; return Object.assign({}, RANK_DEFAULT[key], r && !r.custom ? r.spec : null); }

// 1 行のセグメント (期間など)
function segRow(items, current, onPick) {
  const c = el('div', 'cell'); const seg = el('div', 'seg'); seg.style.width = '100%';
  items.forEach(([k, label]) => { const on = String(k) === String(current); const b = setPressed(el('button', on ? 'on' : '', label), on); b.onclick = () => onPick(k); seg.appendChild(b); });
  c.appendChild(seg); return c;
}
// プルダウン (ジャンルなど、選択肢が多いもの)
function selectRow(label, items, current, onPick) {
  const c = el('label', 'cell'); c.appendChild(el('span', 'text', label));
  const sel = el('select', 'picker');
  items.forEach(([k, l]) => { const o = el('option', '', l); o.value = k; if (String(k) === String(current)) o.selected = true; sel.appendChild(o); });
  sel.onchange = () => onPick(sel.value); c.appendChild(sel); return c;
}
// メニューで登録したページ (あれば)
function customRow(key) {
  const r = rankingsOf()[key];
  if (!r || !r.custom) return null;
  const c = el('div', 'cell'); const t = el('span', 'text', 'このページ: ' + (r.label || ''));
  t.appendChild(el('small', '', r.url)); c.appendChild(t); c.insertAdjacentHTML('beforeend', CHECK);
  return c;
}

// ランキングの行き先: 一覧 (サイトごとに今の行き先) → サイトごとの選択画面 (p-rank-<site>)
const rankLabel = (key) => {
  const r = rankingsOf()[key];
  return !r ? '既定' : r.custom ? 'このページ: ' + (r.label || '') : r.label || '既定';
};
function paintRanking() {
  const list = $('rankingList'); list.innerHTML = '';
  RANK_SITES.filter(([k]) => siteOn(k)).forEach(([k, name]) => {
    const c = el('button', 'cell');
    c.append(el('span', 'dot ' + siteDot(k)), el('span', 'text', name), el('span', 'value', rankLabel(k)));
    c.insertAdjacentHTML('beforeend', CHEV);
    c.onclick = () => go('rank-' + k);
    list.appendChild(c);
  });
}
function paintRankSite(key) {
  const box = $('rb-' + key); box.innerHTML = '';
  const section = (title) => addGroup(box, title);
  // チェック付きの 1 行
  const choice = (g, label, on, fn) => g.appendChild(checkRow(label, on, fn));
  const g = section('ランキングの種類');
  // メニューで登録したページがあれば先頭に出す (選ぶと、下の選択肢に戻る)
  const customCell = customRow(key);
  if (customCell) g.appendChild(customCell);
  const isCustom = !!customCell;

  if (key === 'narou') {
    const p = rankSpec('narou');
    const save = (patch) => { const np = Object.assign(p, patch); setRanking('narou', { url: narouUrl(np), label: narouLabel(np), spec: np }); };
    NAROU_KINDS.forEach(([k, label]) => choice(g, label, !isCustom && p.kind === k, () => save({ kind: k })));
    if (!isCustom && p.kind !== 'top' && p.kind !== 'attn') {
      const g2 = section('期間と絞り込み');
      g2.appendChild(segRow(NAROU_PERIODS, p.period, (k) => save({ period: k })));
      if (p.kind === 'total') g2.appendChild(segRow(NAROU_STATUS, p.status, (k) => save({ status: k })));
      if (p.kind === 'isekai') g2.appendChild(segRow(NAROU_ISEKAI, p.isekai, (k) => save({ isekai: k })));
      if (p.kind === 'genre') g2.appendChild(selectRow('ジャンル', NAROU_GENRES, p.genre, (k) => save({ genre: k })));
    }
  } else if (key === 'hameln') {
    const cur = (rankSpec('hameln') || {}).mode || 'rank';
    HAMELN_RANKS.forEach(([k, label]) => choice(g, label, !isCustom && cur === k, () =>
      setRanking('hameln', k === 'rank' ? null : { url: 'https://syosetu.org/?mode=' + k, label: label, spec: { mode: k } })));
  } else if (key === 'kakuyomu') {
    const p = rankSpec('kakuyomu');
    const save = (patch) => { const np = Object.assign(p, patch); setRanking('kakuyomu', { url: kkUrl(np), label: kkLabel(np), spec: np }); };
    if (isCustom) {
      const b = el('button', 'cell accent', '選択肢から選ぶ'); b.onclick = () => save({}); g.appendChild(b);
    } else {
      g.appendChild(selectRow('ジャンル', KK_GENRES, p.genre, (k) => save({ genre: k })));
      g.appendChild(segRow(KK_PERIODS, p.period, (k) => save({ period: k })));
      g.appendChild(segRow(KK_LENGTH, p.length, (k) => save({ length: k })));
    }
  } else {
    // R18 (ノクターン・ムーンライト・ミッドナイトは共通の作り): ランキングトップか、期間ごとの作品ランキング
    const cur = (rankSpec(key) || {}).period || 'top';
    R18_RANKS.forEach(([p, label]) => choice(g, label, !isCustom && cur === p, () =>
      setRanking(key, p === 'top' ? null : { url: 'https://' + key + '.syosetu.com/rank/list/type/' + p + '_total/', label: label, spec: { period: p } })));
  }
}
