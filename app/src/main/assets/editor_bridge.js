// app/src/main/assets/editor_bridge.js
// 스마트에디터 ONE 내부 API 호출. 규칙은 spike/findings.md §2~§4. 모든 결과는 AndroidBridge 콜백으로 회신한다.
window.__app = (function () {
  var B = window.AndroidBridge;
  var DRAFT_TEXT = '작성 중인 글이 있습니다';
  var DRAFT_POLL_MS = 300, DRAFT_MAX_MS = 60000;
  var draftTimer = null, readyReported = false;
  function log(m) { try { B.log(String(m)); } catch (e) {} }
  function err(step, e) { try { B.onError(step, (e && (e.stack || e.message)) || (function () { try { return JSON.stringify(e); } catch (_) { return String(e); } })()); } catch (_) {} }
  function frameWin() { var f = document.querySelector('#mainFrame'); return (f && f.contentWindow) || window; }
  function frameDoc() { try { return frameWin().document; } catch (e) { return null; } }
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

  function className(n) {
    var c = n && n.className;
    if (c && typeof c === 'object' && 'baseVal' in c) c = c.baseVal;
    return String(c || '').toLowerCase();
  }
  function visible(n) {
    try { var r = n.getBoundingClientRect(); return r.width > 0 && r.height > 0; } catch (e) { return false; }
  }
  function inToolbar(n) {
    for (var p = n; p; p = p.parentElement) {
      if (/toolbar/.test(className(p)) || /toolbar/.test(String(p.id || '').toLowerCase())) return true;
    }
    return false;
  }
  // 노드의 조상 중 팝업/레이어 컨테이너. 자기 자신은 제외한다(제목 요소도 se-popup-title 처럼 이름에 popup 이 들어간다).
  function popupContainer(node) {
    for (var p = node.parentElement, i = 0; p && i < 8; p = p.parentElement, i++) {
      if (p.getAttribute && p.getAttribute('role') === 'dialog') return p;
      if (/layer|popup|modal|dialog/.test(className(p))) return p;
    }
    var q = node;
    for (var j = 0; j < 3 && q && q.parentElement; j++) q = q.parentElement;
    return q;
  }
  // 컨테이너 안에서 정확히 label 인 버튼/링크만 누른다.
  function clickInside(container, label) {
    if (!container) return false;
    var nodes = Array.prototype.slice.call(container.querySelectorAll('button, a, [role="button"]'));
    for (var i = 0; i < nodes.length; i++) {
      var n = nodes[i];
      if ((n.innerText || n.textContent || '').trim() !== label) continue;
      if (!visible(n) || inToolbar(n)) continue;
      n.click();
      return true;
    }
    return false;
  }
  // phrase 를 담은 가장 깊은(문서 순서상 마지막) 보이는 요소.
  function deepestWithText(doc, phrase) {
    var all = doc.querySelectorAll('body *'), hit = null;
    for (var i = 0; i < all.length; i++) {
      var el = all[i];
      if ((el.textContent || '').indexOf(phrase) === -1) continue;
      if (!visible(el)) continue;
      hit = el;
    }
    return hit;
  }
  // "작성 중인 글이 있습니다" 다이얼로그 → 취소(기존 임시저장 글 불러오지 않음). 성공하면 true.
  // 문구를 담은 노드에서 조상을 하나씩 올라가며 "취소" 버튼을 가진 첫 조상 안에서만 누른다.
  function dismissDraftDialog() {
    var doc = frameDoc();
    if (!doc) return false;
    var text = deepestWithText(doc, DRAFT_TEXT);
    if (!text) return false;
    for (var p = text.parentElement, i = 0; p && i < 8; p = p.parentElement, i++) {
      if (clickInside(p, '취소')) return true;
    }
    return false;
  }
  // 임시저장 다이얼로그는 주입 뒤에도 뒤늦게 뜬다. 준비된 순간부터 60초간 감시한다.
  function autoDismissDraftDialog() {
    if (draftTimer) return;
    var elapsed = 0;
    draftTimer = setInterval(function () {
      elapsed += DRAFT_POLL_MS;
      var done = false;
      try { done = dismissDraftDialog(); } catch (e) { err('popups', e); }
      if (done) log('draft dialog dismissed');
      if (done || elapsed >= DRAFT_MAX_MS) { clearInterval(draftTimer); draftTimer = null; }
    }, DRAFT_POLL_MS);
  }

  function checkReady() {
    try {
      var ed = editor();
      if (ed && typeof ed.setDocumentData === 'function' && ed._videoUploadService && ed._videoUploadService._imageUploadService) {
        fitFrame();
        if (!readyReported) { readyReported = true; autoDismissDraftDialog(); }
        B.onReady();
        return true;
      }
      return false;
    } catch (e) { err('ready', e); return false; }
  }

  // 임시저장 다이얼로그 → 취소, 도움말 레이어 → 닫기. 레이어/팝업 컨테이너 안쪽만 건드린다. 없으면 0.
  function dismissPopups() {
    try {
      var doc = frameDoc(), count = 0;
      if (doc) {
        if (dismissDraftDialog()) count++;
        var nodes = Array.prototype.slice.call(doc.querySelectorAll('button, a, [role="button"]'));
        nodes.forEach(function (n) {
          if ((n.innerText || n.textContent || '').trim() !== '닫기') return;
          if (!visible(n) || inToolbar(n)) return;
          var c = popupContainer(n);
          var isLayer = !!c && (/layer|popup|modal|dialog/.test(className(c)) || (c.getAttribute && c.getAttribute('role') === 'dialog'));
          if (!isLayer) return;
          n.click(); count++;
        });
      }
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
