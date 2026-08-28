// [스파이크용 throwaway] documentModel 주입 + JS 생성 이미지 업로드 검증
// 사용법: 글쓰기 페이지에서 (빈 글 상태 권장) F12 → Console 에 붙여넣기 → Enter
// 완료 후 에디터에 제목/서식 문단/이미지가 보이는지 눈으로 확인. 결과 JSON은 클립보드에 복사됨.
(async () => {
  const out = { ts: new Date().toISOString(), steps: [], errors: [] };
  const step = (s) => { out.steps.push(s); console.log('[inject]', s); };
  try {
    const frame = document.querySelector('#mainFrame');
    const w = (frame && frame.contentWindow) || window;
    const ed = w.SmartEditor._editors.blogpc001;
    const uid = () => 'SE-' + crypto.randomUUID();

    // ---- documentModel 빌더 (앱에서 쓸 규칙의 초안) ----
    const textNode = (value, style = {}) => ({
      id: uid(), value,
      style: { fontFamily: 'nanumsquare', fontSizeCode: 'fs19', ...style, '@ctype': 'nodeStyle' },
      '@ctype': 'textNode',
    });
    const paragraph = (nodes, style = {}) => ({
      id: uid(), nodes,
      style: { lineHeight: 1.7, ...style, '@ctype': 'paragraphStyle' },
      '@ctype': 'paragraph',
    });
    const textComp = (paragraphs) => ({ id: uid(), layout: 'default', value: paragraphs, '@ctype': 'text' });
    const plainPara = (value) => ({ id: uid(), nodes: [{ id: uid(), value, '@ctype': 'textNode' }], '@ctype': 'paragraph' });
    const titleComp = (title) => ({ id: uid(), layout: 'default', title: [plainPara(title)], subTitle: null, align: 'left', '@ctype': 'documentTitle' });
    const quotationComp = (quote, source) => ({ id: uid(), layout: 'default', value: [plainPara(quote)], source: [plainPara(source)], '@ctype': 'quotation' });

    // ---- 1. 테스트 이미지 생성 (canvas → PNG File) ----
    step('make test image');
    const canvas = document.createElement('canvas');
    canvas.width = 800; canvas.height = 600;
    const ctx = canvas.getContext('2d');
    ctx.fillStyle = '#3182f6'; ctx.fillRect(0, 0, 800, 600);
    ctx.fillStyle = '#fff'; ctx.font = 'bold 48px sans-serif'; ctx.fillText('SPIKE ' + new Date().toLocaleTimeString(), 40, 300);
    const blob = await new Promise(r => canvas.toBlob(r, 'image/png'));
    const file = new File([blob], 'spike-test.png', { type: 'image/png' });
    out.fileSize = file.size;

    // ---- 2. 업로드 서비스 호출 ----
    step('createSourceList');
    const svc = ed._videoUploadService._imageUploadService;
    const sourceList = svc.createSourceList(['spike-' + Date.now()], [file]);
    out.sourceListShape = JSON.parse(JSON.stringify(sourceList, (k, v) => (v instanceof File ? '[File ' + v.name + ']' : v)));
    step('uploadImagesFromFiles');
    const uploaded = await svc.uploadImagesFromFiles(sourceList);
    out.uploadedRaw = JSON.parse(JSON.stringify(uploaded));
    step('uploaded: ' + JSON.stringify(out.uploadedRaw).slice(0, 500));

    // 응답 → image 컴포넌트 매핑 (응답 형태를 모르므로 방어적으로 탐색)
    const list = Array.isArray(uploaded) ? uploaded : (uploaded && (uploaded.list || uploaded.images || uploaded.result)) || [uploaded];
    const u = list[0];
    const pick = (o, keys) => { for (const k of keys) { if (o && o[k] != null) return o[k]; } return null; };
    const domain = pick(u, ['domain']) || 'https://blogfiles.pstatic.net';
    const path = pick(u, ['path', 'url', 'src']);
    const ow = pick(u, ['originalWidth', 'width']) || 800;
    const oh = pick(u, ['originalHeight', 'height']) || 600;
    const width = Math.min(693, ow);
    const height = Math.round(oh * width / ow);
    const imageComp = {
      id: uid(), layout: 'default',
      src: (path && path.startsWith('http')) ? path : (domain + path + '?type=w1'),
      internalResource: true, represent: true,
      path, domain,
      fileSize: pick(u, ['fileSize']) || file.size,
      width, widthPercentage: 0, height,
      originalWidth: ow, originalHeight: oh,
      fileName: pick(u, ['fileName']) || file.name,
      format: 'normal', displayFormat: 'normal', imageLoaded: true, contentMode: 'fit',
      origin: { srcFrom: 'local', '@ctype': 'imageOrigin' },
      ai: false, '@ctype': 'image',
    };
    out.imageComp = imageComp;

    // ---- 3. documentModel 조립 ----
    step('build documentModel');
    const before = ed.getDocumentData();
    const doc = {
      document: {
        version: before.document.version, theme: 'default', language: 'ko-KR', id: before.document.id,
        components: [
          titleComp('스파이크 주입 테스트 제목'),
          textComp([
            paragraph([textNode('첫 문단입니다. '), textNode('굵은 글씨', { bold: true }), textNode('와 '), textNode('빨간 글씨', { fontColor: '#ff0010' }), textNode('가 섞여 있습니다.')]),
            paragraph([textNode('큰 글씨 소제목', { fontSizeCode: 'fs28', bold: true })]),
            paragraph([textNode('가운데 정렬된 문단입니다.')], { align: 'center' }),
            paragraph([textNode('목록 항목 하나')], { list: { type: 'bullet', level: 0, '@ctype': 'paragraphListStyle' } }),
            paragraph([textNode('목록 항목 둘')], { list: { type: 'bullet', level: 0, '@ctype': 'paragraphListStyle' } }),
          ]),
          quotationComp('인용구 테스트입니다.', '출처 테스트'),
          imageComp,
          textComp([paragraph([textNode('사진 아래 마지막 문단입니다.')])]),
        ],
      },
      documentId: before.documentId || '',
    };

    // ---- 4. 주입 ----
    step('setDocumentData');
    const ret = ed.setDocumentData(doc);
    out.setReturn = (ret && typeof ret.then === 'function') ? await ret : ret;
    await new Promise(r => setTimeout(r, 1500));
    out.after = ed.getDocumentData();
    out.afterCtypes = out.after.document.components.map(c => c['@ctype']);
    out.titleAfter = ed.getDocumentTitle && ed.getDocumentTitle();
    step('done. components after: ' + out.afterCtypes.join(','));
  } catch (e) {
    out.errors.push(e && (e.stack || e.message || String(e)));
    console.error('[inject] error', e);
  }
  console.log(out);
  copy(JSON.stringify(out, null, 2));
  console.log('>>> 결과가 클립보드에 복사되었습니다. spike/inject-result.json 에 저장해 주세요.');
})();
