// 各サイトのページの作り (CSS のセレクター) をここに集めた。サイトが作りを変えて、隠れるはずのものが出た / 出るはずのものが消えた
// ときは、まずこのファイルだけを直す。ロジックは含めない (early.js = 描画前に CSS を入れる、ui.js = 畳む欄のボタン、各アダプター = 一覧の読み取り)。
//
//  hide   = 常に隠すもの (アプリ宣伝など)。CSS のルールそのもの (末尾の {display:none!important} まで書く)
//  bars   = ヘッダー・フッター (とその分の余白)。読書ページ (episode) のときだけ隠し、ほかのページ (トップ・一覧・作品の目次・作者ページ) では出す。
//           書き方は hide と同じ。ページ内の移動 (カクヨム) でも、移るたびに early.js が切り替える
//  episode = 読書ページ (話ページ) の pathname
//  body   = 本文。box = 文字サイズ (zoom) の対象、text = フォント・行間の対象
//  fold   = 話ページの下で普段は畳む欄 (開閉のボタンは ui.js)。comments = 感想・レビュー、recommend = おすすめ、links = 作者ページ・共有など、
//           reactions = 応援・リアクション (ポイント・顔文字)
//           (空文字なら、そのサイトには無い / 畳む必要が無い)。設定 foldComments / foldRecommend / foldLinks / foldReactions で切り替わる
//
// ブラウザの DevTools (CDP) で確かめるときは、実機の WebView の UA で開くこと (特に R18 は UA で作りが変わる)。
window.NH_SITES = {
  // ハーメルン: 感想の一覧はページの中に出ない (「感想を書く」などのリンクだけ) ので fold.comments は無い。
  // 最終話だけ、下に「読者層が似ている作品」(おすすめ。#ss の中の ul.entry > li.id5) と、#ss の外 (ページ末尾の body 直下) に「この作品が紹介された捜索」(同じ ul.entry > li.id5) が付く。目次ページの話の一覧も同じ ul.entry なので、li.id5 で絞らないと一覧まで畳まれる。話ページの下の Xで読了報告・アプリでシェア・感想を書く・評価する・推薦する は、
  // id もクラスも無い div なので、中のリンク (a.tweetbtn / a.sharebtn / a.linkbutton) を持つ #ss 直下の div で指定する
  hameln: {
    hide: '',
    bars: '#header,#footer,#nav2+br{display:none!important}',
    episode: /^\/novel\/\d+\/\d+\.html/,
    body: { box: '#honbun,#maegaki,#atogaki', text: '#honbun,#honbun p,#maegaki,#maegaki p,#atogaki,#atogaki p' },
    fold: { comments: '', recommend: '#ss > ul.entry:has(> li.id5),body > ul.entry:has(> li.id5)', links: '#ss > div:has(> a.tweetbtn),#ss > div:has(> a.sharebtn),#ss > div:has(> a.linkbutton)' },
  },

  // カクヨム: 話ページの下 (#episodeFooter) は「次のエピソード」と応援ボタンがあるので残し、その中の「アプリで次のエピソードを読む」だけ隠す。
  // クラス名はハッシュ付きなので、前方一致 ([class*=]) で拾う。応援コメントはもともとボタンの奥に畳まれているので fold.comments は無い
  // (話ページの下 #episodeFooter の id は安定している)
  kakuyomu: {
    hide:
      '#contentMain-readNextEpisodeWithApp,' +
      // トップの告知バナー
      '#banners,' +
      // 公式アプリへの誘導 (「アプリで開く」)
      '[class*="OpenInAppLink_"],[class*="_openInAppLink"],#episodeFooter-readWithApp{display:none!important}',
    bars:
      '#worksEpisodesEpisodeHeader,[class*="GlobalHeader_"],[class*="GlobalFooter_globalFooter"],' +
      '[class*="CompactMediaGlobalHeader_"]{display:none!important}' +
      // 固定ヘッダーの高さ分に取ってあった上の余白を詰める
      '[class*="DefaultTemplate_fixed"],[class*="SubHeaderSpacer_"]{padding-top:0!important}',
    episode: /^\/works\/\d+\/episodes\/\d+/,
    body: { box: '.widget-episodeBody', text: '.widget-episodeBody,.widget-episodeBody p' },
    // 最新話 (最終話) は下に「関連小説」(#episodeFooter-related)・作者・作品情報・アプリ宣伝 (#episodeFooter-readWithApp。常に隠す) が増える。
    // 「次の更新予定」は残す
    // links = 応援の囲み (#episodeFooter-cheerSection: 応援する・応援コメント・共有のアイコン・こっそり誤字報告。応援コメントの一覧もこの中)、
    //         作者の欄 (#episodeFooter-author) と作品情報 (#episodeFooter-work。作品名・@作者名・フォロー・ギフト・★で称える・タグ)、書籍の告知 (#episodeFooter-lastPublishedMediaMix。「好評発売中」・書影・出版社)、
    //         ログインしていないときの「新規登録で充実の読書を」の囲み (#episodeFooter-signupMessage)
    fold: { comments: '', recommend: '#contentMain-relatedWork,#episodeFooter-related', links: '#episodeFooter-cheerSection,#episodeFooter-author,#episodeFooter-work,#episodeFooter-lastPublishedMediaMix,#episodeFooter-signupMessage' },
  },

  // なろう (R18 の novel18 も同じ作り): アプリ宣伝は常に隠し、ヘッダー / フッター (bars) は話ページだけ隠す。話ページの header.c-menu には「表示調整」
  // 「ブックマーク追加」など読むための機能があるので残し、サイトナビだけの c-follow-header を消す。
  // fold: comments = 感想の一覧 (.p-new-impression)・感想フォーム (#impression)・イチオシレビュー (#review。最終話だけ)、
  //       recommend = おすすめ (.p-recommend)、links = 作者マイページ・縦書き PDF・誤字報告・情報提供 (.c-under-nav) と共有 (.c-sns)。
  //       残すもの: ポイントの欄・リアクション・ブックマーク・前へ / 目次 / 次へ
  narou: {
    hide: 'a[href*="onelink.me"],[class*="c-applink"],.c-novel-attention{display:none!important}',
    bars: '.l-header,nav.c-follow-header,.l-footer{display:none!important}',
    episode: /^\/n\w+\/\d+\/?$/,
    body: { box: '.p-novel__text', text: '.p-novel__text,.p-novel__text p' },
    // comments には、感想の下の「▲ページの上部へ」(#view_upline) と、小説の下・感想の上に出る作者のお知らせ (.p-rankingtag。「新連載を始めました」など) も入れる
    // (畳んだら、一緒に消える)。前書き・後書きは本文の一部なので畳まない
    // reactions = 「ポイントを入れて作者を応援しよう」(#novel_hyouka) と顔文字のリアクション (#nav-reaction)
    fold: { comments: '.p-rankingtag,.p-new-impression,#impression,#review,#view_upline,.c-announce--note:has(a[href*="/login/"]),.c-novel-feedback-form--disapproval', recommend: '.p-recommend', links: '.c-under-nav,.c-sns', reactions: '#novel_hyouka,#nav-reaction' },
    // ノクターン / ムーンライト / ミッドナイト (ランキング・検索・トップ) は古い作りのスマホ版 (oldHost)。読書ページではないので、ヘッダー・フッターは隠さない
    oldHost: /^(noc|mnlt|mid)\.syosetu\.com$/,
  },
};

// 畳む欄の種類の表 (key = fold のキー、flag = 設定の項目 (既定オン)、label = 開閉のボタンの名前)。early.js (畳む CSS) と ui.js (開閉のボタン) が共通で使う。
// 設定画面の項目 (settings/page-fold.html) と、畳んでいる数の数え方 (settings/repaint.js) は、同じ flag を使う
window.NH_FOLDS = [
  { key: 'comments', flag: 'foldComments', label: '感想・お知らせ' },
  { key: 'recommend', flag: 'foldRecommend', label: 'おすすめ' },
  { key: 'links', flag: 'foldLinks', label: '作者・共有・リンク' },
  { key: 'reactions', flag: 'foldReactions', label: '応援・リアクション' },
];

// 一覧 (ランキング・検索) の作品の囲みを探す (ui-list.js) ときの、サイトの作りに依存するセレクター。
//  frame = ページの骨組み (ここより外へはさかのぼらない)。なろうは l-xxx クラス、ほかは id や container 系のクラス
//          (class の Layout はカクヨムが作品の中でも使うので見ない)
//  tagLinks = タグ検索へのリンクの形 (アダプターが tagsOf を持たないサイトの既定)
window.NH_LIST_SELECTORS = {
  frame: 'main, body, [id*="container" i], [id*="contents" i], [id*="main" i], [class^="l-"], [class*=" l-"], [class*="container" i]',
  tagLinks: 'a[href*="word="], a[href*="/tags/"]',
};

