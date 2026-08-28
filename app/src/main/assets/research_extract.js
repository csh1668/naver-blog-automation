// 검색 결과·본문 추출. 반환은 항상 JSON 문자열. 실패는 빈 배열/빈 객체.
// 네이버·구글 결과 페이지는 클래스 이름이 난독화돼 자주 바뀌므로 특정 클래스에 기대지 않고
// "보이는 외부 링크 + 그 주변 텍스트" 를 일반적으로 모은다. 결과 페이지 자체의 본문 요약(summary)도 함께 돌려준다 —
// 영업시간·주소·가격 같은 정보는 결과 페이지의 플레이스 카드에 이미 있어 페이지를 더 열지 않아도 된다.
window.__research = window.__research || (function () {
  function txt(el) { return (el && (el.innerText || el.textContent) || '').replace(/\s+/g, ' ').trim(); }
  function visible(el) {
    try { var r = el.getBoundingClientRect(); return r.width > 0 && r.height > 0; } catch (e) { return true; }
  }
  // 검색 사이트 자신의 내비게이션·광고·로그인 링크는 결과가 아니다 (호스트 기준).
  var SKIP_HOST = /^(search|m\.search|help|nid|ader|adcr|cr|siape|shopping|smartstore|brand|keep|blog\.naver\.com\/search)\.naver\.com$|^(www\.)?google\.[a-z.]+$|^accounts\.google\.com$|^policies\.google\.com$|^support\.google\.com$|^(www\.)?bing\.com$|^go\.microsoft\.com$/i;
  function host(u) { try { return new URL(u).hostname; } catch (e) { return ''; } }
  function hits(limit) {
    var out = [], seen = {}, anchors = document.querySelectorAll('a[href^="http"]');
    for (var i = 0; i < anchors.length && out.length < limit; i++) {
      var a = anchors[i]; var href = a.href || ''; if (!href || seen[href]) continue;
      var h = host(href); if (!h || SKIP_HOST.test(h)) continue;
      // m.help.pay.naver.com 같은 도움말·결제·로그인·광고 하위 도메인
      if (/(^|\.)(help|pay|nid|ader|adcr|keep)\.[a-z.]*naver\.com$/.test(h)) continue;
      if (!visible(a)) continue;
      var title = txt(a);
      if (title.length < 6 || title.length > 120) continue;
      // 가격표·리뷰 수·리뷰어 닉네임·사진/리뷰 탭 같은 부가 링크는 건너뛴다
      if (/^\d[\d,]*\s*원$|^리뷰\s*[\d,]+$|더보기$|전체보기$|^[A-Za-z0-9]{2,6}\*{2,}$|^방문자\s*리뷰/.test(title)) continue;
      if (/\/(photo|review)(\/|\?|$)|filterType=|selectedVisitorReview=/.test(href)) continue;
      seen[href] = 1;
      var card = a.closest('li, article, section, div');
      var snippet = card ? txt(card).slice(0, 240) : '';
      out.push({ title: title.slice(0, 100), url: href, snippet: snippet });
    }
    return out;
  }
  // 살아 있는 요소의 innerText 를 쓴다 — 접힌 검색옵션 패널처럼 display:none 인 것은 자동으로 빠진다.
  // (복제본은 레이아웃이 없어 innerText 가 textContent 처럼 숨은 글자까지 다 뱉는다.)
  function summary(rootSel, max) {
    // 모바일 통합검색은 #ct(본문) 아래에 결과가 있고 그 위는 검색창·탭 같은 내비게이션이다.
    var root = document.querySelector(rootSel) || document.querySelector('#ct, #container, #content') || document.body;
    var s = (root.innerText || '').replace(/\s+/g, ' ').trim();
    // 결과 상단의 "새 창 열림"·"Keep에 저장" 같은 접근성 문구는 정보가 아니다.
    s = s.replace(/새 창 열림|Keep에 저장|Keep에 바로가기|옵션펼치기|접기/g, ' ').replace(/\s+/g, ' ');
    return s.slice(0, max);
  }
  function searchNaver() {
    try { return JSON.stringify({ hits: hits(8), summary: summary('#main_pack', 1800) }); } catch (e) { return '{"hits":[],"summary":""}'; }
  }
  function searchGoogle() {
    try { return JSON.stringify({ hits: hits(8), summary: summary('#search', 1800) }); } catch (e) { return '{"hits":[],"summary":""}'; }
  }
  function searchBing() {
    try { return JSON.stringify({ hits: hits(8), summary: summary('#b_results', 1800) }); } catch (e) { return '{"hits":[],"summary":""}'; }
  }
  function pageText() {
    try {
      var root = document.querySelector('article, .se-main-container, #postViewArea, main, #content, #app-root') || document.body;
      var clone = root.cloneNode(true);
      clone.querySelectorAll('script, style, nav, header, footer, aside, iframe, noscript').forEach(function (n) { n.remove(); });
      return JSON.stringify({ title: document.title, text: txt(clone).slice(0, 4000) });
    } catch (e) { return '{}'; }
  }
  return { searchNaver: searchNaver, searchGoogle: searchGoogle, searchBing: searchBing, pageText: pageText };
})();
