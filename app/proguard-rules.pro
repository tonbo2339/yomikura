# JavaScript から名前で呼ばれるメソッド (WebView の addJavascriptInterface)。クラス名は指定しない (どのクラスに付いていても残す)
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
# マニフェストの activity-alias (ランチャーアイコン切り替え) が指す MainActivity は、AGP がマニフェストから自動で残す
