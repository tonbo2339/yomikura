// ---- オープンソースライセンス: ソースを公開しているリポジトリがある版だけ、その場所を出す (Kotlin の Edition.PUBLIC_REPO) ----
const LIC_REPO = (window.NH_EDITION || {}).publicRepo || '';
$('licRepo').hidden = !LIC_REPO;
$('licRepoText').textContent = LIC_REPO ? 'https://github.com/' + LIC_REPO : '';
