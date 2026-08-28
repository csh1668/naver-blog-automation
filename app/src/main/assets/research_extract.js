// 검색 결과·본문 추출. 반환은 항상 JSON 문자열. 실패는 빈 배열/빈 객체.
window.__research = window.__research || (function () {
  function txt(el) { return (el && (el.innerText || el.textContent) || '').replace(/\s+/g, ' ').trim(); }
  function hits(anchors, limit) {
    var out = [], seen = {};
    for (var i = 0; i < anchors.length && out.length < limit; i++) {
      var a = anchors[i]; if (!a) continue;
      var href = a.href || ''; var title = txt(a);
      if (!/^https?:/.test(href) || !title || seen[href]) continue;
      if (/naver\.com\/search|google\.com\//.test(href)) continue;
      seen[href] = 1;
      var card = a.closest('li, article, div');
      var snippet = card ? txt(card).slice(0, 200) : '';
      out.push({ title: title.slice(0, 80), url: href, snippet: snippet });
    }
    return out;
  }
  function searchNaver() {
    try {
      var anchors = document.querySelectorAll('a.title_link, a.api_txt_lines, a[class*="title"], .view_wrap a, .total_wrap a');
      return JSON.stringify(hits(anchors, 5));
    } catch (e) { return '[]'; }
  }
  // 구글은 <a><h3>제목</h3></a> 구조: h3 의 부모 a 를 사용
  function searchGoogle() {
    try {
      var h3s = document.querySelectorAll('a h3'); var anchors = [];
      for (var i = 0; i < h3s.length; i++) anchors.push(h3s[i].closest('a'));
      return JSON.stringify(hits(anchors, 5));
    } catch (e) { return '[]'; }
  }
  function pageText() {
    try {
      var root = document.querySelector('article, .se-main-container, #postViewArea, main, #content') || document.body;
      var clone = root.cloneNode(true);
      clone.querySelectorAll('script, style, nav, header, footer, aside, iframe').forEach(function (n) { n.remove(); });
      return JSON.stringify({ title: document.title, text: txt(clone).slice(0, 4000) });
    } catch (e) { return '{}'; }
  }
  return { searchNaver: searchNaver, searchGoogle: searchGoogle, pageText: pageText };
})();
