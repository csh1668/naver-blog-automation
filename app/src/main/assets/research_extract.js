// 검색 결과·본문 추출. 반환은 항상 JSON 문자열. 실패는 빈 배열/빈 객체.
// 네이버·구글 결과 페이지는 클래스 이름이 난독화돼 자주 바뀌므로 특정 클래스에 기대지 않고
// "보이는 외부 링크 + 그 주변 텍스트" 를 일반적으로 모은다. 결과 페이지 자체의 본문 요약(summary)도 함께 돌려준다 —
// 영업시간·주소·가격 같은 정보는 결과 페이지의 플레이스 카드에 이미 있어 페이지를 더 열지 않아도 된다.
window.__research = window.__research || (function () {
  function txt(el) { return (el && (el.innerText || el.textContent) || '').replace(/\s+/g, ' ').trim(); }
  function visible(el) {
    try { var r = el.getBoundingClientRect(); return r.width > 0 && r.height > 0; } catch (e) { return true; }
  }
  var SKIP = /(^|\.)(search|help|nid|ader|adcr|cr|siape|shopping|smartstore|brand)\.naver\.com|google\.[a-z.]+\/(search|url|preferences|advanced_search|intl)|accounts\.google|policies\.google|support\.google|webcache\.|javascript:/i;
  function host(u) { try { return new URL(u).hostname; } catch (e) { return ''; } }
  function hits(limit) {
    var out = [], seen = {}, anchors = document.querySelectorAll('a[href^="http"]');
    for (var i = 0; i < anchors.length && out.length < limit; i++) {
      var a = anchors[i]; var href = a.href || ''; if (!href || seen[href]) continue;
      if (SKIP.test(href) || !host(href)) continue;
      if (!visible(a)) continue;
      var title = txt(a);
      if (title.length < 6 || title.length > 120) continue;
      // 가격표·리뷰 수 같은 부가 링크는 건너뛴다
      if (/^\d[\d,]*\s*원$|^리뷰\s*[\d,]+$|더보기$/.test(title)) continue;
      seen[href] = 1;
      var card = a.closest('li, article, section, div');
      var snippet = card ? txt(card).slice(0, 240) : '';
      out.push({ title: title.slice(0, 100), url: href, snippet: snippet });
    }
    return out;
  }
  function summary(rootSel, max) {
    var root = document.querySelector(rootSel) || document.body;
    var clone = root.cloneNode(true);
    clone.querySelectorAll('script, style, nav, header, footer, iframe, noscript, form, [role="navigation"]').forEach(function (n) { n.remove(); });
    return txt(clone).slice(0, max);
  }
  function searchNaver() {
    try { return JSON.stringify({ hits: hits(8), summary: summary('#main_pack', 1800) }); } catch (e) { return '{"hits":[],"summary":""}'; }
  }
  function searchGoogle() {
    try { return JSON.stringify({ hits: hits(8), summary: summary('#search', 1800) }); } catch (e) { return '{"hits":[],"summary":""}'; }
  }
  function pageText() {
    try {
      var root = document.querySelector('article, .se-main-container, #postViewArea, main, #content, #app-root') || document.body;
      var clone = root.cloneNode(true);
      clone.querySelectorAll('script, style, nav, header, footer, aside, iframe, noscript').forEach(function (n) { n.remove(); });
      return JSON.stringify({ title: document.title, text: txt(clone).slice(0, 4000) });
    } catch (e) { return '{}'; }
  }
  return { searchNaver: searchNaver, searchGoogle: searchGoogle, pageText: pageText };
})();
