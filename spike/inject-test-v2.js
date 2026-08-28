// [스파이크용 throwaway] v2: 텍스트 주입과 이미지 업로드를 분리 + 업로드 실패 진단
// 사용법: 글쓰기 페이지 F12 → Console 붙여넣기 → Enter
// 결과: 콘솔 마지막에 "RESULT_JSON ..." 한 줄로 출력됨. 그 줄을 우클릭 → "Copy string contents" 로 복사해서 spike/inject-result.json 에 저장.
(async () => {
  const out = { ts: new Date().toISOString(), steps: [], errors: [] };
  const step = (s) => { out.steps.push(s); console.log('[inject]', s); };
  const errOf = (e) => (e && (e.stack || e.message)) || String(e);
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

  // ---- A. 텍스트만 주입 ----
  try {
    step('A: text-only setDocumentData');
    const before = ed.getDocumentData();
    const doc = {
      document: {
        version: before.document.version, theme: 'default', language: 'ko-KR', id: before.document.id,
        components: [
          titleComp('스파이크 v2 제목'),
          textComp([
            paragraph([textNode('첫 문단. '), textNode('굵게', { bold: true }), textNode(' 그리고 '), textNode('빨강', { fontColor: '#ff0010' })]),
            paragraph([textNode('큰 소제목', { fontSizeCode: 'fs28', bold: true })]),
            paragraph([textNode('가운데 정렬')], { align: 'center' }),
            paragraph([textNode('목록 하나')], { list: { type: 'bullet', level: 0, '@ctype': 'paragraphListStyle' } }),
          ]),
          quotationComp('인용구', '출처'),
          textComp([paragraph([textNode('마지막 문단')])]),
        ],
      },
      documentId: before.documentId || '',
    };
    const r = ed.setDocumentData(doc);
    if (r && r.then) await r;
    await new Promise(res => setTimeout(res, 1000));
    out.afterA = ed.getDocumentData().document.components.map(c => c['@ctype']);
    step('A done: ' + out.afterA.join(','));
  } catch (e) { out.errors.push('A: ' + errOf(e)); console.error(e); }

  // ---- B. 업로드 서비스 진단 ----
  const svc = ed._videoUploadService._imageUploadService;
  try {
    step('B: inspect upload service');
    const src = (f) => { try { return f.toString().slice(0, 2500); } catch (e) { return errOf(e); } };
    out.svcSource = {
      createSourceList: src(svc.createSourceList),
      uploadImagesFromFiles: src(svc.uploadImagesFromFiles),
      uploadImages: src(svc.uploadImages),
      validateImages: src(svc.validateImages),
    };
    const up = svc._imageUploader;
    const names = (obj) => { const s = new Set(); let o = obj; while (o && o !== Object.prototype) { Object.getOwnPropertyNames(o).forEach(n => s.add(n)); o = Object.getPrototypeOf(o); } return [...s].sort(); };
    out.imageUploader = up ? { props: names(up), ctor: up.constructor && up.constructor.name } : null;
    if (up) { for (const n of ['upload', 'uploadFiles', 'uploadImages', 'uploadFile']) { if (typeof up[n] === 'function') out.imageUploader['src_' + n] = src(up[n]); } }
    out.maxImageUploadSize = svc.maxImageUploadSize;
  } catch (e) { out.errors.push('B: ' + errOf(e)); console.error(e); }

  // ---- C. 실제 업로드 시도 (여러 인자 형태) ----
  const canvas = document.createElement('canvas'); canvas.width = 800; canvas.height = 600;
  const ctx = canvas.getContext('2d'); ctx.fillStyle = '#3182f6'; ctx.fillRect(0, 0, 800, 600);
  ctx.fillStyle = '#fff'; ctx.font = 'bold 48px sans-serif'; ctx.fillText('SPIKE v2', 40, 300);
  const blob = await new Promise(r => canvas.toBlob(r, 'image/png'));
  const file = new File([blob], 'spike-test.png', { type: 'image/png', lastModified: Date.now() });
  out.fileSize = file.size;

  const attempts = [
    { name: 'C1 createSourceList([id],[file]) -> uploadImagesFromFiles(list)', fn: async () => { const l = svc.createSourceList(['spike-' + Date.now()], [file]); out.sourceListC1 = JSON.parse(JSON.stringify(l, (k, v) => v instanceof File ? '[File]' : v)); return await svc.uploadImagesFromFiles(l); } },
    { name: 'C2 uploadImagesFromFiles([file])', fn: async () => await svc.uploadImagesFromFiles([file]) },
    { name: 'C3 uploadImages([file])', fn: async () => await svc.uploadImages([file]) },
  ];
  let uploaded = null;
  for (const a of attempts) {
    try {
      step(a.name);
      const r = await a.fn();
      out.uploadedRaw = JSON.parse(JSON.stringify(r));
      out.uploadedBy = a.name;
      step('upload OK via ' + a.name + ': ' + JSON.stringify(out.uploadedRaw).slice(0, 400));
      uploaded = r; break;
    } catch (e) { out.errors.push(a.name + ': ' + errOf(e)); console.error(a.name, e); }
  }

  // ---- D. 업로드 성공 시 이미지 포함 주입 ----
  if (uploaded) {
    try {
      const list = Array.isArray(uploaded) ? uploaded : (uploaded.list || uploaded.images || uploaded.result || [uploaded]);
      const u = list[0];
      const pick = (o, keys) => { for (const k of keys) if (o && o[k] != null) return o[k]; return null; };
      const domain = pick(u, ['domain']) || 'https://blogfiles.pstatic.net';
      const path = pick(u, ['path', 'url', 'src']);
      const ow = pick(u, ['originalWidth', 'width']) || 800, oh = pick(u, ['originalHeight', 'height']) || 600;
      const width = Math.min(693, ow), height = Math.round(oh * width / ow);
      const imageComp = { id: uid(), layout: 'default', src: (path && path.startsWith('http')) ? path : (domain + path + '?type=w1'), internalResource: true, represent: true, path, domain, fileSize: pick(u, ['fileSize']) || file.size, width, widthPercentage: 0, height, originalWidth: ow, originalHeight: oh, fileName: pick(u, ['fileName']) || file.name, format: 'normal', displayFormat: 'normal', imageLoaded: true, contentMode: 'fit', origin: { srcFrom: 'local', '@ctype': 'imageOrigin' }, ai: false, '@ctype': 'image' };
      out.imageComp = imageComp;
      step('D: setDocumentData with image');
      const cur = ed.getDocumentData();
      cur.document.components.splice(cur.document.components.length - 1, 0, imageComp);
      const r = ed.setDocumentData(cur); if (r && r.then) await r;
      await new Promise(res => setTimeout(res, 1000));
      out.afterD = ed.getDocumentData().document.components.map(c => c['@ctype']);
      step('D done: ' + out.afterD.join(','));
    } catch (e) { out.errors.push('D: ' + errOf(e)); console.error(e); }
  }

  const json = JSON.stringify(out, null, 2);
  try { if (typeof copy === 'function') copy(json); else await navigator.clipboard.writeText(json); step('copied to clipboard'); } catch (e) { out.errors.push('clipboard: ' + errOf(e)); }
  console.log('RESULT_JSON ' + JSON.stringify(out));
})();
