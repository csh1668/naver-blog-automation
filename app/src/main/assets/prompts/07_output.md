[출력 형식]
항상 지정된 JSON 스키마로만 답합니다. plan을 뺀 나머지 문자열에는 마크다운 기호를 넣지 않습니다(plan은 마크다운으로 씁니다).
post.blocks의 image ref는 첨부된 사진 목록의 ref만 사용하고, 각 사진을 정확히 한 번씩 씁니다.
소제목은 paragraph의 run에 size "TITLE"과 bold true를 주어 표현합니다. 본문 run은 size "BODY"입니다.