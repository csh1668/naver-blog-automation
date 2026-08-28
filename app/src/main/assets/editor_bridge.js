// app/src/main/assets/editor_bridge.js
// 스마트에디터 ONE 내부 API 호출. 규칙은 spike/findings.md §2~§4. 모든 결과는 AndroidBridge 콜백으로 회신한다.
window.__app = (function () {
  var B = window.AndroidBridge;
  function log(m) { try { B.log(String(m)); } catch (e) {} }
  function err(step, e) { try { B.onError(step, (e && (e.stack || e.message)) || (function () { try { return JSON.stringify(e); } catch (_) { return String(e); } })()); } catch (_) {} }
  function frameWin() { var f = document.querySelector('#mainFrame'); return (f && f.contentWindow) || window; }
  function editor() { var w = frameWin(); var eds = w.SmartEditor && w.SmartEditor._editors; if (!eds) return null; var ids = Object.keys(eds); return ids.length ? eds[ids[0]] : null; }
  function uid() { return 'SE-' + (window.crypto && crypto.randomUUID ? crypto.randomUUID() : Date.now() + '-' + Math.random().toString(16).slice(2)); }

  // 바깥 페이지의 body 높이가 0 이라 #mainFrame 이 0px 로 렌더된다. 픽셀 높이를 직접 준다.
  function fitFrame() {
    try {
      var d = document.documentElement, b = document.body;
      if (d) d.style.height = '100%';
      if (b) { b.style.height = '100%'; b.style.margin = '0'; }
      var f = document.querySelector('#mainFrame');
      if (!f) return;
      f.style.height = window.innerHeight + 'px';
      f.style.width = '100%';
    } catch (e) { err('fit', e); }
  }

  function checkReady() {
    try {
      var ed = editor();
      if (ed && typeof ed.setDocumentData === 'function' && ed._videoUploadService && ed._videoUploadService._imageUploadService) { fitFrame(); B.onReady(); return true; }
      return false;
    } catch (e) { err('ready', e); return false; }
  }

  // "작성 중인 글이 있습니다" → 취소(새 글), 도움말 → 닫기. 없으면 0.
  function dismissPopups() {
    try {
      var doc = frameWin().document, count = 0;
      ['취소', '닫기'].forEach(function (label) {
        var nodes = Array.prototype.slice.call(doc.querySelectorAll('button, a'));
        nodes.filter(function (n) { return (n.innerText || '').trim() === label; }).forEach(function (n) { n.click(); count++; });
      });
      B.onPopupsDismissed(count);
    } catch (e) { err('popups', e); }
  }

  // items: [{ref, url}] — url 은 앱이 shouldInterceptRequest 로 제공하는 같은 origin 경로
  function uploadImages(items) {
    var ed = editor(); if (!ed) { err('upload', 'editor missing'); return; }
    var svc = ed._videoUploadService._imageUploadService;
    (async function () {
      for (var i = 0; i < items.length; i++) {
        var item = items[i];
        try {
          var blob = await (await fetch(item.url, { cache: 'no-store' })).blob();
          var file = new File([blob], item.ref + '.jpg', { type: 'image/jpeg', lastModified: Date.now() });
          var list = svc.createSourceList([item.ref], [file]);
          var pending = await svc.uploadImagesFromFiles(list);      // Promise<Promise[]> — 두 번 await
          var results = await Promise.all(Array.isArray(pending) ? pending : [pending]);
          var r = results[0];
          if (!r || r.code !== 'SUCCESS' || !r.response || !r.response.url) { B.onImageFailed(item.ref, JSON.stringify(r && (r.response || r))); return; }
          var resp = r.response;
          B.onImageUploaded(item.ref, JSON.stringify({ url: resp.url, path: resp.path, fileName: resp.fileName, width: resp.width, height: resp.height, fileSize: resp.fileSize, domain: resp.domain }));
        } catch (e) {
          B.onImageFailed(item.ref, (e && (e.message || JSON.stringify(e))) || String(e));
          return;
        }
      }
    })();
  }

  function setDocument(json) {
    try {
      var ed = editor(); if (!ed) { err('inject', 'editor missing'); return; }
      var doc = JSON.parse(json);
      var before = ed.getDocumentData();
      doc.document.version = before.document.version || doc.document.version;
      doc.document.id = before.document.id || doc.document.id;
      var r = ed.setDocumentData(doc);
      Promise.resolve(r).then(function () {
        setTimeout(function () {
          try { fitFrame(); B.onInjected(ed.getDocumentData().document.components.length); } catch (e) { err('inject', e); }
        }, 800);
      }).catch(function (e) { err('inject', e); });
    } catch (e) { err('inject', e); }
  }

  try { window.addEventListener('resize', fitFrame); } catch (e) {}

  return { checkReady: checkReady, dismissPopups: dismissPopups, uploadImages: uploadImages, setDocument: setDocument, fitFrame: fitFrame, uid: uid };
})();
