# API 공유 문서

현재 계약 기준은 **API v0.6 / ERD v5.1**이며, 다음 DOCX를 보존한다.

- [API 명세 v0.6](<용인 불법주정차 알림 — API 명세 v0.6 ERD v5.1 기준.docx>)

저장소의 문서·링크와 DOCX의 외부 링크를 확인했지만 별도 공유 원본 URL은 확인되지 않았다.
따라서 DOCX를 임의로 삭제하지 않는다. 외부 공유본이 없다고 단정하는 것은 아니다.
후속 정리 시 팀이 관리하는 공유 원본의 URL·관리자·버전·접근 권한을 확인하고,
팀원이 접근 가능한 영구 링크와 버전 기록을 남긴 뒤 DOCX 보관 여부를 결정한다.
외부 문서의 접근성을 보장하기 어렵다면 DOCX를 버전별 스냅샷으로 유지한다.

SQL 기준은 `src/main/resources/db/migration/`, ERD 기준은
[`docs/schema/yongin_parking_erd_v5.1.dbml`](../schema/yongin_parking_erd_v5.1.dbml)이다.
중복 참고 SQL·DBML과 참고용 V4는 제거했으며, 변경 전 내용은 Git 이력에 남아 있다.
회귀 테스트는 `scripts/db/tests_v5.sql`, 동시성 테스트는 `scripts/db/tests_concurrency.sh`에 있다.
실행 방법은 [DB 가이드](../DATABASE.md)를 따른다.
