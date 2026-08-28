// [스파이크용 throwaway] Android 에서 evaluateJavascript 로 실행되는 주입 스크립트.
// __IMAGES__ 는 Kotlin 쪽에서 [{name, dataUrl}] JSON 으로 치환됨. 결과는 AndroidBridge.onResult(json) 으로 반환.
(async () => {
  const IMAGES = __IMAGES__;
  const out = { ts: new Date().toISOString(), steps: [], errors: [] };
  const step = (s) => { out.steps.push(s); console.log('[inject]', s); };
  const errOf = (e) => (e && (e.stack || e.message)) || (function () { try { return JSON.stringify(e); } catch (_) { return String(e); } })();
  const report = () => { try { AndroidBridge.onResult(JSON.stringify(out)); } catch (e) { console.log('RESULT_JSON ' + JSON.stringify(out)); } };
  try {
    const frame = document.querySelector('#mainFrame');
    const w = (frame && frame.contentWindow) || window;
    if (!w.SmartEditor || !w.SmartEditor._editors) throw new Error('SmartEditor not found (href=' + w.location.href + ')');
    const ids = Object.keys(w.SmartEditor._editors);
    out.editorIds = ids;
    const ed = w.SmartEditor._editors[ids[0]];
    const uid = () => 'SE-' + crypto.randomUUID();

    const textNode = (value, style = {}) => ({ id: uid(), value, style: { fontFamily: 'nanumsquare', fontSizeCode: 'fs19', ...style, '@ctype': 'nodeStyle' }, '@ctype': 'textNode' });
    const paragraph = (nodes, style = {}) => ({ id: uid(), nodes, style: { lineHeight: 1.7, ...style, '@ctype': 'paragraphStyle' }, '@ctype': 'paragraph' });
    const textComp = (paragraphs) => ({ id: uid(), layout: 'default', value: paragraphs, '@ctype': 'text' });
    const plainPara = (value) => ({ id: uid(), nodes: [{ id: uid(), value, '@ctype': 'textNode' }], '@ctype': 'paragraph' });
    const titleComp = (title) => ({ id: uid(), layout: 'default', title: [plainPara(title)], subTitle: null, align: 'left', '@ctype': 'documentTitle' });
    const quotationComp = (quote, source) => ({ id: uid(), layout: 'default', value: [plainPara(quote)], source: [plainPara(source)], '@ctype': 'quotation' });

    // 1. dataUrl → File
    step('build files');
    const files = [];
    for (const img of IMAGES) {
      const blob = await (await fetch(img.dataUrl)).blob();
      files.push(new File([blob], img.name, { type: blob.type || 'image/png', lastModified: Date.now() }));
    }
    out.fileSizes = files.map(f => f.size);

    // 2. upload (uploadImagesFromFiles 는 Promise 배열 반환)
    step('upload');
    const svc = ed._videoUploadService._imageUploadService;
    const list = svc.createSourceList(files.map((_, i) => 'spike-' + Date.now() + '-' + i), files);
    const pending = await svc.uploadImagesFromFiles(list);
    const results = await Promise.all(Array.isArray(pending) ? pending : [pending]);
    out.uploadResults = results.map(r => JSON.parse(JSON.stringify(r, (k, v) => (v instanceof File ? '[File]' : v))));
    step('uploaded ' + results.length);

    // 3. response → image component
    const toImageComp = (r, file, represent) => {
      const resp = r.response || r;
      const pick = (keys) => { for (const k of keys) if (resp[k] != null) return resp[k]; return null; };
      let domain = pick(['domain']) || 'https://blogfiles.pstatic.net';
      if (!/^https?:/.test(domain)) domain = 'https://' + domain;
      let path = pick(['url', 'path', 'filePath']);
      const url = pick(['url', 'src', 'fileUrl']);
      if (!path && url) { try { path = new URL(url).pathname; } catch (_) {} }
      const ow = Number(pick(['originalWidth', 'width'])) || 800;
      const oh = Number(pick(['originalHeight', 'height'])) || 600;
      const width = Math.min(693, ow), height = Math.round(oh * width / ow);
      return { id: uid(), layout: 'default', src: domain + path + '?type=w1', internalResource: true, represent, path, domain, fileSize: Number(pick(['fileSize'])) || file.size, width, widthPercentage: 0, height, originalWidth: ow, originalHeight: oh, fileName: pick(['fileName']) || file.name, format: 'normal', displayFormat: 'normal', imageLoaded: true, contentMode: 'fit', origin: { srcFrom: 'local', '@ctype': 'imageOrigin' }, ai: false, '@ctype': 'image' };
    };
    const imgs = results.map((r, i) => toImageComp(r, files[i], i === 0));

    // 4. setDocumentData
    step('setDocumentData');
    const before = ed.getDocumentData();
    const doc = {
      document: {
        version: before.document.version, theme: 'default', language: 'ko-KR', id: before.document.id,
        components: [
          titleComp('안드로이드 스파이크 제목'),
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
    out.afterImages = after.document.components.filter(c => c['@ctype'] === 'image').map(c => ({ src: c.src, path: c.path, w: c.width, h: c.height }));
    step('done: ' + out.afterCtypes.join(','));
  } catch (e) { out.errors.push(errOf(e)); console.error('[inject] error', e); }
  report();
})();
