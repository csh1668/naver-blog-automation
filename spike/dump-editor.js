// [스파이크용 throwaway] 네이버 스마트에디터 ONE 내부 구조 덤프
// 사용법: 글쓰기 페이지(blog.naver.com/{id}?Redirect=Write)에서 F12 → Console 에 전체 붙여넣기 → Enter
// 결과는 클립보드에 JSON으로 복사됨 (copy 함수). 콘솔에도 요약 출력.
(async () => {
  const out = { ts: new Date().toISOString(), errors: [] };
  const safe = (label, fn) => { try { return fn(); } catch (e) { out.errors.push(label + ': ' + e.message); return null; } };

  // 1. 에디터가 iframe(#mainFrame) 안에 있으면 그쪽 window 사용
  const frame = document.querySelector('#mainFrame');
  const w = (frame && frame.contentWindow) || window;
  const d = w.document;
  out.top = { href: location.href, ua: navigator.userAgent, hasMainFrame: !!frame };
  out.frame = { href: w.location.href, title: d.title };

  // 2. 에디터 인스턴스
  const editors = safe('editors', () => w.SmartEditor && w.SmartEditor._editors);
  out.editorIds = editors ? Object.keys(editors) : null;
  const ed = editors && editors[out.editorIds[0]];
  if (!ed) { out.errors.push('editor instance not found'); console.log(out); copy(JSON.stringify(out, null, 2)); return; }

  // 3. 메서드 이름 수집 (프로토타입 체인 포함)
  const methodNames = (obj) => {
    const names = new Set();
    let o = obj;
    while (o && o !== Object.prototype) {
      Object.getOwnPropertyNames(o).forEach(n => { try { if (typeof obj[n] === 'function') names.add(n); } catch (_) {} });
      o = Object.getPrototypeOf(o);
    }
    return [...names].sort();
  };
  out.editorMethods = methodNames(ed);
  out.editorOwnProps = Object.keys(ed).sort();

  // 4. 문서 데이터 (사용자가 미리 샘플 서식을 입력해 둔 상태를 전제)
  out.documentData = safe('getDocumentData', () => ed.getDocumentData && ed.getDocumentData());
  out.hasSetDocumentData = typeof ed.setDocumentData === 'function';

  // 5. 이미지 업로드 서비스 경로 탐색
  const findService = () => {
    const cands = [];
    const visit = (obj, path, depth) => {
      if (!obj || typeof obj !== 'object' || depth > 3) return;
      for (const k of Object.keys(obj)) {
        if (/upload/i.test(k)) cands.push(path + '.' + k);
        if (depth < 3) { try { visit(obj[k], path + '.' + k, depth + 1); } catch (_) {} }
      }
    };
    visit(ed, 'editor', 0);
    return cands;
  };
  out.uploadPaths = safe('uploadPaths', findService);
  const imgSvc = safe('imgSvc', () => ed._videoUploadService && ed._videoUploadService._imageUploadService);
  out.imageUploadServiceMethods = imgSvc ? methodNames(imgSvc) : null;

  // 6. 제목/본문/발행 버튼 DOM
  out.dom = safe('dom', () => ({
    titleEl: [...d.querySelectorAll('[class*="title"]')].slice(0, 5).map(e => e.tagName + '.' + e.className),
    contentEditable: [...d.querySelectorAll('[contenteditable="true"]')].map(e => e.tagName + '.' + e.className).slice(0, 10),
    publishButtons: [...d.querySelectorAll('button, a')].filter(e => (e.innerText || '').trim() === '발행').map(e => e.tagName + '.' + e.className + '#' + e.id),
    fileInputs: [...d.querySelectorAll('input[type=file]')].map(e => ({ cls: e.className, id: e.id, accept: e.accept, multiple: e.multiple })),
  }));

  console.log('=== SmartEditor dump ===');
  console.log('editorIds', out.editorIds);
  console.log('hasSetDocumentData', out.hasSetDocumentData);
  console.log('imageUploadServiceMethods', out.imageUploadServiceMethods);
  console.log('errors', out.errors);
  console.log(out);
  copy(JSON.stringify(out, null, 2));
  console.log('>>> 결과가 클립보드에 복사되었습니다. spike/editor-dump.json 에 붙여넣어 저장해 주세요.');
})();
