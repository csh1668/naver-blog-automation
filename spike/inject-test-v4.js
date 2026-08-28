// [스파이크용 throwaway] v4: uploadImagesFromFiles 가 Promise 배열을 반환하므로 Promise.all 로 대기
// 사용법: 글쓰기 페이지 F12 → Console 붙여넣기 → Enter
// 결과: 콘솔 마지막 줄 "RESULT_JSON {...}" 우클릭 → Copy string contents → spike/inject-result-v4.json 에 저장
(async () => {
  const out = { ts: new Date().toISOString(), steps: [], errors: [] };
  const step = (s) => { out.steps.push(s); console.log('[inject]', s); };
  const errOf = (e) => (e && (e.stack || e.message)) || (function () { try { return JSON.stringify(e); } catch (_) { return String(e); } })();
  const frame = document.querySelector('#mainFrame');
  const w = (frame && frame.contentWindow) || window;
  const ed = w.SmartEditor._editors.blogpc001;
  const uid = () => 'SE-' + crypto.randomUUID();

  const textNode = (value, style = {}) => ({ id: uid(), value, style: { fontFamily: 'nanumsquare', fontSizeCode: 'fs19', ...style, '@ctype': 'nodeStyle' }, '@ctype': 'textNode' });
  const paragraph = (nodes, style = {}) => ({ id: uid(), nodes, style: { lineHeight: 1.7, ...style, '@ctype': 'paragraphStyle' }, '@ctype': 'paragraph' });
  const textComp = (paragraphs) => ({ id: uid(), layout: 'default', value: paragraphs, '@ctype': 'text' });
  const plainPara = (value) => ({ id: uid(), nodes: [{ id: uid(), value, '@ctype': 'textNode' }], '@ctype': 'paragraph' });
  const titleComp = (title) => ({ id: uid(), layout: 'default', title: [plainPara(title)], subTitle: null, align: 'left', '@ctype': 'documentTitle' });
  const quotationComp = (quote, source) => ({ id: uid(), layout: 'default', value: [plainPara(quote)], source: [plainPara(source)], '@ctype': 'quotation' });

  try {
    // ---- 1. 테스트 이미지 2장 (canvas → File) ----
    const makeFile = (label, color) => new Promise((resolve) => {
      const c = document.createElement('canvas'); c.width = 800; c.height = 600;
      const ctx = c.getContext('2d'); ctx.fillStyle = color; ctx.fillRect(0, 0, 800, 600);
      ctx.fillStyle = '#fff'; ctx.font = 'bold 48px sans-serif'; ctx.fillText(label, 40, 300);
      c.toBlob((b) => resolve(new File([b], label.replace(/\s+/g, '_') + '.png', { type: 'image/png', lastModified: Date.now() })), 'image/png');
    });
    const files = [await makeFile('SPIKE v4 image 1', '#3182f6'), await makeFile('SPIKE v4 image 2', '#f04452')];

    // ---- 2. 업로드 (Promise.all) ----
    step('upload 2 files');
    const svc = ed._videoUploadService._imageUploadService;
    const list = svc.createSourceList(files.map((_, i) => 'spike-' + Date.now() + '-' + i), files);
    const pending = await svc.uploadImagesFromFiles(list);
    out.pendingType = Array.isArray(pending) ? "array(" + pending.length + ")" : typeof pending;
    const results = await Promise.all(Array.isArray(pending) ? pending : [pending]);
    out.uploadResults = results.map(r => JSON.parse(JSON.stringify(r, (k, v) => (v instanceof File ? '[File]' : v))));
    step('upload results: ' + JSON.stringify(out.uploadResults).slice(0, 800));

    // ---- 3. 응답 → image 컴포넌트 ----
    const toImageComp = (r, file, represent) => {
      const resp = r.response || r;
      const pick = (keys) => { for (const k of keys) if (resp[k] != null) return resp[k]; return null; };
      let domain = pick(['domain']) || 'https://blogfiles.pstatic.net';
      if (!/^https?:/.test(domain)) domain = 'https://' + domain;
      let path = pick(['path', 'filePath']);
      const url = pick(['url', 'src', 'fileUrl']);
      if (!path && url) { try { path = new URL(url).pathname; } catch (_) {} }
      const ow = Number(pick(['originalWidth', 'width'])) || 800;
      const oh = Number(pick(['originalHeight', 'height'])) || 600;
      const width = Math.min(693, ow), height = Math.round(oh * width / ow);
      return {
        id: uid(), layout: 'default',
        src: domain + path + '?type=w1',
        internalResource: true, represent,
        path, domain,
        fileSize: Number(pick(['fileSize'])) || file.size,
        width, widthPercentage: 0, height,
        originalWidth: ow, originalHeight: oh,
        fileName: pick(['fileName']) || file.name,
        format: 'normal', displayFormat: 'normal', imageLoaded: true, contentMode: 'fit',
        origin: { srcFrom: 'local', '@ctype': 'imageOrigin' },
        ai: false, '@ctype': 'image',
      };
    };
    const imgs = results.map((r, i) => toImageComp(r, files[i], i === 0));
    out.imageComps = imgs;

    // ---- 4. 전체 문서 주입 ----
    step('setDocumentData (full)');
    const before = ed.getDocumentData();
    const doc = {
      document: {
        version: before.document.version, theme: 'default', language: 'ko-KR', id: before.document.id,
        components: [
          titleComp('스파이크 v4 제목'),
          textComp([
            paragraph([textNode('첫 문단. '), textNode('굵게', { bold: true }), textNode(', '), textNode('빨강', { fontColor: '#ff0010' }), textNode(', '), textNode('형광펜', { backgroundColor: '#ffd300' })]),
            paragraph([textNode('큰 소제목', { fontSizeCode: 'fs28', bold: true })]),
          ]),
          imgs[0],
          textComp([
            paragraph([textNode('사진 사이 문단, 가운데 정렬')], { align: 'center' }),
            paragraph([textNode('목록 하나')], { list: { type: 'bullet', level: 0, '@ctype': 'paragraphListStyle' } }),
            paragraph([textNode('목록 둘')], { list: { type: 'bullet', level: 0, '@ctype': 'paragraphListStyle' } }),
          ]),
          imgs[1],
          quotationComp('인용구', '출처'),
          textComp([paragraph([textNode('마지막 문단')])]),
        ],
      },
      documentId: before.documentId || '',
    };
    const r = ed.setDocumentData(doc); if (r && r.then) await r;
    await new Promise(res => setTimeout(res, 1500));
    const after = ed.getDocumentData();
    out.afterCtypes = after.document.components.map(c => c['@ctype']);
    out.afterImages = after.document.components.filter(c => c['@ctype'] === 'image').map(c => ({ src: c.src, path: c.path, domain: c.domain, w: c.width, h: c.height, ow: c.originalWidth, oh: c.originalHeight }));
    step('done: ' + out.afterCtypes.join(','));
  } catch (e) { out.errors.push(errOf(e)); console.error('[inject] error', e); }

  console.log('RESULT_JSON ' + JSON.stringify(out));
})();
