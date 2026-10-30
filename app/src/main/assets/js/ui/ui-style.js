// サイト上の画面 (ui*.js) の共通スタイル。ui.js が最初に呼ぶ (画面の部品のクラス名と CSS は、ここだけ)
window.NH_UI_STYLE = function () {
  // 全画面の覆い (オフライン保存の確認・進み具合) のスタイル
  const OV_CSS = `
    .nh-ov{position:fixed;inset:0;z-index:2147483647;background:#12100e;color:#f2ede7;display:flex;flex-direction:column;
      align-items:center;justify-content:center;gap:22px;padding:24px;font-family:"Noto Sans JP",sans-serif}
    .nh-ov .spin{width:48px;height:48px;border-radius:50%;border:4px solid #2a2520;border-top-color:#3fae73;animation:nhspin .9s linear infinite}
    @keyframes nhspin{to{transform:rotate(360deg)}}
    .nh-ov .ok{width:56px;height:56px;margin:0 auto 4px}
    .nh-ov .ok svg{width:100%;height:100%;fill:none;stroke:#3fae73;stroke-width:3.5;stroke-linecap:round;stroke-linejoin:round}
    .nh-ov .ok circle{stroke-dasharray:145;stroke-dashoffset:145;animation:nhdraw .5s ease-out forwards}
    .nh-ov .ok path{stroke-dasharray:40;stroke-dashoffset:40;animation:nhdraw .3s .4s ease-out forwards}
    @keyframes nhdraw{to{stroke-dashoffset:0}}
    @media (prefers-reduced-motion:reduce){.nh-ov .ok circle,.nh-ov .ok path{animation:none;stroke-dashoffset:0}}
    .nh-ov h3{margin:0;font-size:19px;font-weight:800}
    .nh-save{display:flex;flex-direction:column;align-items:center;gap:16px;max-width:100%;max-height:100%}
    .nh-ov p{margin:0;font-size:14px;line-height:1.6;color:#ab9f94;text-align:center;max-width:330px}
    .nh-ov p.wt{color:#f2ede7;font-weight:700;font-size:15px}
    .nh-ov p.big{color:#f2ede7;font-size:26px;font-weight:800}
    .nh-ov .btns{display:flex;gap:10px;flex-wrap:wrap;justify-content:center}
    .nh-ov button{border:0;border-radius:999px;padding:12px 24px;font-size:15px;font-weight:700;background:#3fae73;color:#06210f;font-family:inherit}
    .nh-ov button.sub{background:#2a2520;color:#f2ede7}
    .nh-ov button.small{padding:7px 14px;font-size:13px}
    .nh-ov .seg{display:flex;gap:4px;background:#1c1916;border-radius:12px;padding:3px;border:1px solid #3a342d}
    .nh-ov .seg[hidden]{display:none}
    .nh-ov .seg button{background:none;color:#ab9f94;padding:8px 16px;border-radius:9px;font-size:14px;font-weight:600}
    .nh-ov .seg button.on{background:#3fae73;color:#06210f;font-weight:800}
    .nh-ov .panel{display:flex;flex-direction:column;align-items:center;gap:10px}
    .nh-ov .range{display:flex;align-items:center;gap:8px;color:#f2ede7;font-size:14px;flex-wrap:wrap;justify-content:center}
    .nh-ov input[type=number]{width:76px;height:38px;border:0;border-radius:10px;background:#1c1916;color:#f2ede7;font-size:16px;text-align:center}
    .nh-ov .pick{max-height:34vh;overflow:auto;width:min(340px,92vw);background:#1c1916;border-radius:14px;padding:4px 14px}
    .nh-ov .pick label{display:flex;gap:10px;align-items:center;padding:9px 0;font-size:14px;color:#f2ede7;line-height:1.4}
    .nh-ov .bar{width:min(300px,80vw);height:6px;border-radius:3px;background:#2a2520;overflow:hidden}
    .nh-ov .bar i{display:block;height:100%;width:0;background:#3fae73;transition:width .3s}
  `;

  // ---- 共通スタイル ----
  const style = document.createElement('style');
  style.textContent = OV_CSS + `
    .nh-chip{display:inline-flex;align-items:center;gap:6px;margin:8px 0 6px;height:32px;padding:0 14px;border:1.5px solid #2f8f5b;border-radius:999px;
      background:#fff;color:#23744a;font:700 13px/1 "Noto Sans JP",sans-serif;letter-spacing:.02em;cursor:pointer;
      box-shadow:0 1px 2px rgba(0,0,0,.06);transition:transform .12s,background .15s,color .15s}
    .nh-chip:active{transform:scale(.95)}
    .nh-chip.nh-pop{animation:nhpop .38s cubic-bezier(.3,1.6,.5,1)}
    @keyframes nhpop{0%{transform:scale(.88)}100%{transform:none}}
    @media (prefers-reduced-motion:reduce){.nh-chip.nh-pop{animation:none}}
    .nh-chip.on{background:#2f8f5b;color:#fff;border-color:#2f8f5b}
    .nh-fold{display:block;box-sizing:border-box;width:calc(100% - 24px);margin:10px 12px;height:42px;border:1.5px solid rgba(128,128,134,.3);border-radius:12px;
      background:transparent;color:#85858b;font:600 14px "Noto Sans JP",sans-serif;cursor:pointer}
    .nh-fold:active{opacity:.6}
    .nh-toast{position:fixed;left:50%;bottom:max(24px,env(safe-area-inset-bottom));transform:translateX(-50%);z-index:2147483647;max-width:88vw;
      background:#2b2621;color:#f2ede7;padding:12px 20px;border-radius:14px;font:600 14px/1.4 "Noto Sans JP",sans-serif;
      box-shadow:0 10px 30px rgba(0,0,0,.35);animation:nhin .22s cubic-bezier(.2,.8,.2,1)}
    @keyframes nhin{from{opacity:0;transform:translate(-50%,12px)}}
    .nh-next{margin:40px 16px 96px;padding:18px;border-radius:24px;background:#fff;color:#1f1a16;
      font-family:"Noto Sans JP",sans-serif;box-shadow:0 1px 2px rgba(0,0,0,.06),0 10px 30px rgba(30,20,10,.12);border:1px solid rgba(60,40,20,.08)}
    .nh-next.inline{margin:20px 12px 28px;text-align:left;clear:both}
    .nh-next-cap{display:flex;align-items:center;justify-content:center;gap:8px;font-size:12px;font-weight:700;letter-spacing:.1em;color:#6f645a;margin-bottom:14px}
    .nh-next-cap::before,.nh-next-cap::after{content:"";height:1px;width:28px;background:rgba(60,40,20,.15)}
    .nh-next-btn{display:block;width:100%;text-align:left;border:0;border-radius:18px;padding:16px 56px 16px 18px;color:#fff;position:relative;cursor:pointer;
      background:radial-gradient(120% 140% at 100% 0%,#3fae73 0%,#23744a 60%,#1b5c3b 100%);box-shadow:0 8px 22px rgba(35,116,74,.3)}
    .nh-next-btn::after{content:"";position:absolute;right:16px;top:50%;width:28px;height:28px;margin-top:-14px;border-radius:50%;
      background:rgba(255,255,255,.2) url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='white'%3E%3Cpath d='M10 6 8.59 7.41 13.17 12l-4.58 4.59L10 18l6-6z'/%3E%3C/svg%3E") center/20px no-repeat}
    .nh-next-btn:active{transform:scale(.985)}
    .nh-next-btn .t1{display:block;font-size:12px;font-weight:700;opacity:.85;margin-bottom:4px;letter-spacing:.04em}
    .nh-next-btn .t2{display:block;font-size:16px;font-weight:800;line-height:1.45;word-break:break-all}
    .nh-next-btn .t3{display:block;font-size:12px;opacity:.8;margin-top:6px}
    .nh-next-btn.last{background:linear-gradient(135deg,#4a3a2c,#2b221b);box-shadow:0 8px 22px rgba(40,30,20,.3)}
    .nh-next-sub{display:block;width:100%;margin-top:10px;height:44px;border:0;border-radius:14px;background:#efeae3;color:#1f1a16;font:700 14px "Noto Sans JP",sans-serif;cursor:pointer}
    .nh-next-row{display:flex;gap:10px;align-items:stretch}
    .nh-next-row .nh-next-btn{flex:1;min-width:0}
    .nh-next-leave{flex:0 0 auto;width:92px;white-space:pre-line;border:0;border-radius:18px;background:#efeae3;color:#1f1a16;font:700 13px/1.4 "Noto Sans JP",sans-serif;cursor:pointer;padding:0 8px}
    .nh-next-leave:active{opacity:.7}
    .nh-toc-top{display:block;padding:12px 16px;font:700 14px "Noto Sans JP",sans-serif;color:#23744a;text-decoration:none;background:#f6f2ec;border-bottom:1px solid rgba(60,40,20,.08)}
    .nh-toc-top:active{opacity:.7}
    .nh-hist{display:inline-block;margin:4px 0 2px;padding:2px 10px;border-radius:999px;background:rgba(47,143,91,.12);color:#23744a;font:700 12px/1.6 "Noto Sans JP",sans-serif}
    .nh-banner{position:fixed;left:16px;right:16px;bottom:max(20px,env(safe-area-inset-bottom));z-index:2147483647;border:0;text-align:left;cursor:pointer;
      padding:14px 18px;border-radius:14px;background:#2b2621;color:#f2ede7;font:600 14px/1.5 "Noto Sans JP",sans-serif;box-shadow:0 10px 30px rgba(0,0,0,.35);
      animation:nhin .22s cubic-bezier(.2,.8,.2,1)}
    .nh-chips{display:flex;flex-wrap:wrap;gap:8px;margin:8px 0 6px}
    .nh-chips .nh-chip{margin:0}
    .nh-chip-mute{border-color:rgba(120,120,128,.45);color:#6e6e73;background:#fff}
    .nh-read{opacity:.5}
    .nh-read-tag{display:inline-flex;align-items:center;height:28px;padding:0 10px;border-radius:999px;background:rgba(120,120,128,.16);color:#6e6e73;font:700 12px "Noto Sans JP",sans-serif}
    .nh-muted{display:none!important}
    .nh-blocked,.nh-blocked-link{display:none!important}
    .nh-toast button{margin-left:14px;border:0;background:none;color:#7fd3a0;font:inherit;cursor:pointer;padding:0}
    .nh-sheet-seg{display:flex;gap:2px;padding:2px;margin-bottom:12px;border-radius:10px;background:rgba(118,118,128,.12)}
    .nh-sheet-seg button{flex:1;height:34px;border:0;border-radius:8px;background:none;color:#000;font:600 14px "Noto Sans JP",sans-serif;cursor:pointer}
    .nh-sheet-seg button.on{background:#fff;box-shadow:0 1px 3px rgba(0,0,0,.15)}
    .nh-sheet-note{font-size:12px;color:rgba(60,60,67,.6);margin:-4px 4px 12px}
    .nh-reveal .nh-muted,.nh-muted.nh-muted-dim{display:revert!important;opacity:.35}
    .nh-muted::before{content:"ミュート中 (" attr(data-nh-muted) ")";display:block;font:600 12px/1.6 "Noto Sans JP",sans-serif;color:#ff3b30}
    .nh-mute-pill{position:fixed;left:50%;transform:translateX(-50%);bottom:max(20px,env(safe-area-inset-bottom));z-index:2147483646;border:0;cursor:pointer;
      padding:10px 18px;border-radius:999px;background:rgba(44,44,46,.94);color:#fff;font:600 13px/1.3 "Noto Sans JP",sans-serif;box-shadow:0 6px 20px rgba(0,0,0,.3)}
    .nh-mute-pill[hidden],.nh-read-tag[hidden]{display:none!important}
    .nh-filtered{display:none!important}
    .nh-filter{position:fixed;left:12px;right:12px;bottom:max(14px,env(safe-area-inset-bottom));z-index:2147483646;display:flex;gap:2px;padding:3px;
      border-radius:12px;background:#f2f2f7;box-shadow:0 6px 24px rgba(0,0,0,.18);font:500 13px/1 "Noto Sans JP",sans-serif}
    .nh-filter[hidden]{display:none}
    /* 下の絞り込みが出ているときは、お知らせ・ミュートの印をその上に出す (重ねて隠さない) */
    body:has(.nh-filter:not([hidden])) :is(.nh-toast,.nh-banner,.nh-mute-pill){bottom:calc(max(14px,env(safe-area-inset-bottom)) + 50px)}
    .nh-filter button{flex:1;border:0;background:none;color:#000;padding:9px 2px;border-radius:9px;font:inherit;cursor:pointer;white-space:nowrap}
    .nh-filter button.on{background:#fff;font-weight:700;box-shadow:0 2px 6px rgba(0,0,0,.12)}
    .nh-has-filter .nh-mute-pill{bottom:calc(max(14px,env(safe-area-inset-bottom)) + 56px)}
    .nh-has-filter body{padding-bottom:72px}
    .nh-scrim{position:fixed;inset:0;z-index:2147483646;background:rgba(0,0,0,.35)}
    .nh-sheet{position:fixed;left:0;right:0;bottom:0;z-index:2147483647;max-height:85vh;overflow-y:auto;background:#f2f2f7;color:#000;
      border-radius:14px 14px 0 0;padding:16px 16px max(20px,env(safe-area-inset-bottom));font:15px/1.45 "Noto Sans JP",sans-serif;
      animation:nhup .28s cubic-bezier(.3,.7,.4,1)}
    @keyframes nhup{from{transform:translateY(100%)}}
    .nh-sheet-title{font-weight:700;font-size:15px;margin:0 4px 12px;word-break:break-all}
    .nh-sheet-label{font-size:13px;color:rgba(60,60,67,.6);margin:16px 4px 8px}
    .nh-sheet-group{border-radius:12px;overflow:hidden}
    .nh-sheet-row{display:block;width:100%;min-height:50px;border:0;background:#fff;color:#ff3b30;font:500 16px "Noto Sans JP",sans-serif;cursor:pointer}
    .nh-sheet-group .nh-sheet-row+.nh-sheet-row{border-top:1px solid rgba(60,60,67,.3)} /* ダーク (サイトを暗くする表示) でも境目が見える濃さ */
    .nh-sheet-link{margin-top:16px;border-radius:12px;color:#1e9a55}
    .nh-sheet-cancel{margin-top:8px;border-radius:12px;color:#1e9a55;font-weight:700}
    .nh-dialog{position:fixed;left:50%;top:50%;transform:translate(-50%,-50%);z-index:2147483647;width:min(300px,86vw);overflow:hidden;
      background:#fff;color:#000;border-radius:14px;text-align:center;font:15px/1.45 "Noto Sans JP",sans-serif;box-shadow:0 10px 30px rgba(0,0,0,.25)}
    .nh-dialog-body{padding:18px 16px 14px}
    /* 文字は 1 行に収まるなら 1 行、収まらなければ句の切れ目で 2 行にそろえる (text-wrap: balance) */
    .nh-dialog-title,.nh-dialog-note,.nh-dialog-row{text-wrap:balance;word-break:auto-phrase}
    .nh-dialog-title{font-weight:700;font-size:17px;margin-bottom:6px}
    .nh-dialog-note{font-size:13px;color:rgba(60,60,67,.75)}
    .nh-dialog-row{display:block;width:100%;min-height:48px;border:0;border-top:.5px solid rgba(60,60,67,.25);background:none;color:#007aff;font:500 17px "Noto Sans JP",sans-serif;cursor:pointer}
    .nh-sheet-tags{display:flex;flex-wrap:wrap;gap:8px}
    .nh-sheet-tag{border:0;border-radius:999px;padding:7px 12px;background:#fff;color:#000;font:500 14px "Noto Sans JP",sans-serif;cursor:pointer}
    .nh-sheet-form{display:flex;gap:8px}
    .nh-sheet-form input{flex:1;min-width:0;height:44px;border:0;border-radius:10px;padding:0 12px;background:#fff;color:#000;font:16px "Noto Sans JP",sans-serif}
    .nh-sheet-add{border:0;border-radius:10px;padding:0 18px;background:#1e9a55;color:#fff;font:700 15px "Noto Sans JP",sans-serif;cursor:pointer}
  `;
  (document.head || document.documentElement).appendChild(style);
};
