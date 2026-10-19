// offline.html: どの画面を開くかの判断
if (!NH.off.available()) {
  root.append(el('div', 'empty', 'このページはアプリの中でだけ使えます'));
} else if (!wkey) library();
else if (q.get('ep') != null) reader(wkey, q.get('ep'));
else work(wkey);
EXT.onOpen();
showFlash();
