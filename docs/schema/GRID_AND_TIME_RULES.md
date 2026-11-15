# 격자·시간대 계산 규칙 (예측표 조회용)

작성: 김민지 · 2026-11-04 · 기준: ERD v5.1, API v0.6

예측표(`risk_predictions`)는 Python 적재 스크립트가 만들고, Spring 판정(`POST /parking-events`)이 조회한다.
두 쪽이 칸 번호와 요일·시간대를 똑같이 계산해야 조회가 맞는다. 이 문서가 그 기준이다.

## 1. 격자 칸 찾기

### 규칙

| 항목 | 값 |
| --- | --- |
| 입력 좌표 | WGS84 (EPSG:4326) 위도 `lat`, 경도 `lng` |
| 변환 좌표계 | EPSG:5186 (미터 단위) |
| 칸 크기 | 50 m |
| 칸 번호 | `grid_x = floor(x / 50)`, `grid_y = floor(y / 50)` (x = 동쪽 방향, y = 북쪽 방향) |
| 칸 ID | `spatial_grids.id` (DB가 붙이는 번호. 계산값이 아니라 조회해서 얻는다) |

### 계산은 PostGIS로 한다 (Java에서 직접 변환하지 않기)

Java 라이브러리와 PostGIS의 좌표 변환이 아주 미세하게 다르면, 칸 경계에 걸친 좌표가 다른 칸으로 들어갈 수 있다.
그래서 양쪽 모두 아래 SQL 식으로만 칸을 계산한다. Python 적재도 같은 식을 DB에서 실행한다.

```sql
-- 입력: :lat, :lng  (주의: ST_MakePoint는 경도, 위도 순서)
SELECT g.id AS grid_id, g.in_model_area
FROM spatial_grids g
WHERE g.projected_srid = 5186
  AND g.cell_size_m   = 50
  AND g.grid_x = floor(ST_X(ST_Transform(ST_SetSRID(ST_MakePoint(:lng, :lat), 4326), 5186)) / 50)::int
  AND g.grid_y = floor(ST_Y(ST_Transform(ST_SetSRID(ST_MakePoint(:lng, :lat), 4326), 5186)) / 50)::int;
```

저장된 geography 컬럼에서 바로 계산할 때는 `ST_Transform(confirmed_location::geometry, 5186)`을 쓴다.

### 확인용 예시 (pyproj 9.8.1로 계산. PostGIS 결과와 같은지 한 번 대조해 주세요)

| 위치 | lat | lng | x (5186) | y (5186) | grid_x | grid_y |
| --- | --- | --- | --- | --- | --- | --- |
| 수지구청 부근 | 37.3222 | 127.0978 | 208668.473 | 524775.411 | 4173 | 10495 |
| 풍덕천동 예시 | 37.3195 | 127.0950 | 208420.597 | 524475.502 | 4168 | 10489 |

```sql
SELECT floor(ST_X(p)/50)::int AS grid_x, floor(ST_Y(p)/50)::int AS grid_y
FROM (SELECT ST_Transform(ST_SetSRID(ST_MakePoint(127.0978, 37.3222), 4326), 5186) AS p) t;
-- 기대값: 4173, 10495
```

### `spatial_grids`에 들어 있는 칸

- 전체 버전: 용인시 행정경계와 겹치는 50 m 칸 전부 (`in_model_area = true`).
- 경계 밖 칸은 저장하지 않는다. → 조회 결과가 없으면 용인 밖.
- 샘플 버전(11/4~5): 풍덕천동 주변 칸만 들어 있다. 이 기간에는 다른 지역이 모두 "용인 밖"으로 나온다. 전체 버전이 활성화되면 자동으로 해결된다.

## 2. 요일·시간대

| 항목 | 규칙 |
| --- | --- |
| 기준 시각 | `detectedAt`(블루투스 해제 시각)을 Asia/Seoul로 바꾼 값 |
| `weekday_actual` | ISO 요일. 월 = 1 … 일 = 7 |
| `is_holiday` | 기준 시각의 한국 날짜가 `holidays` 테이블에 있으면 true |
| `weekday` (조회용) | 활성 버전의 `holiday_policy`가 `AS_SUNDAY`이고 공휴일이면 7, 아니면 `weekday_actual` |
| `time_slot` | 한국 시각의 `시(hour) / 3`의 몫. 00:00~02:59 = 0, 03:00~05:59 = 1, …, 21:00~23:59 = 7 |

언어별 같은 계산:

```java
ZonedDateTime t = detectedAt.atZoneSameInstant(ZoneId.of("Asia/Seoul"));
int weekdayActual = t.getDayOfWeek().getValue();   // 1~7
boolean isHoliday = holidayRepository.existsById(t.toLocalDate());
int weekday = (isHoliday && "AS_SUNDAY".equals(version.holidayPolicy)) ? 7 : weekdayActual;
int timeSlot = t.getHour() / 3;                    // 0~7
```

```python
t = detected_at.astimezone(ZoneInfo("Asia/Seoul"))
weekday_actual = t.isoweekday()
weekday = 7 if (t.date() in holidays and holiday_policy == "AS_SUNDAY") else weekday_actual
time_slot = t.hour // 3
```

확인용 예시:

| detectedAt | 한국 시각 | weekday_actual | time_slot | 비고 |
| --- | --- | --- | --- | --- |
| `2026-11-07T19:05:12+09:00` | 토 19:05 | 6 | 6 | 시나리오 기준 |
| `2026-11-07T10:05:12Z` | 토 19:05 | 6 | 6 | UTC로 와도 같은 결과 |
| `2026-11-08T02:59:59+09:00` | 일 02:59 | 7 | 0 | |
| `2026-11-08T03:00:00+09:00` | 일 03:00 | 7 | 1 | 경계는 다음 칸 |

`holidays`에는 2025년(학습용)과 2026년(서비스용) 공휴일을 모두 넣는다(적재: 민지).

## 3. 예측 조회 순서와 결과 상태

1단계(시설 법정 거리) 판정이 먼저이고, 1단계가 아닐 때 아래 순서로 조회한다.

| 순서 | 확인 | 결과 |
| --- | --- | --- |
| 1 | 활성 버전 `SELECT * FROM prediction_versions WHERE active` 가 없음 | PARTIAL · `NO_ACTIVE_VERSION` |
| 2 | 1절 조회 결과 없음, 또는 `in_model_area = false` | PARTIAL · `OUT_OF_AREA` |
| 3 | `prediction_target_grids`에 (버전, 칸)이 없음 | PARTIAL · `NOT_TARGET` |
| 4 | 대상 칸인데 `risk_predictions` 행이 없음 | FAILED · `MISSING_ROW` (장애) |
| 5 | 행이 있음 | SUCCEEDED · `OK` → `top_percent <= caution_top_percent`면 2단계, 아니면 3단계 |

```sql
SELECT rp.smoothed_count, rp.top_percent
FROM risk_predictions rp
WHERE rp.version_id = :versionId AND rp.grid_id = :gridId
  AND rp.weekday = :weekday AND rp.time_slot = :timeSlot;
```

- `risk_assessments.predicted_count` ← `smoothed_count`, `top_percent` ← `top_percent`
- 2단계 경계(`caution_top_percent`)는 코드에 고정하지 말고 활성 버전 행에서 읽는다(현재 10).
- 활성 버전은 요청 시작 때 한 번 읽고 끝까지 같은 값을 쓴다(명세 4절).

## 4. "반경 내 신고 N건"

```sql
SELECT COALESCE(SUM(s.report_count), 0)      AS report_count,
       COALESCE(SUM(s.event_group_count), 0) AS event_group_count,
       MIN(s.period_start) AS period_start, MAX(s.period_end) AS period_end
FROM parcel_report_stats s
WHERE s.import_id = :enforcementImportId
  AND ST_DWithin(s.location, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, 50);
```

- `:enforcementImportId` = `data_imports`에서 `source_dataset = 'yongin_enforcement'` 이고 `active = true` 인 행의 id
- 반경 50 m는 명세 #12 임시값
- 기간은 시민 신고 관측 기간 2025-01-01 ~ 2025-09-25

## 5. 버전 일정

| 버전 (`model_version`) | 범위 | 활성화 목표 | 주의 |
| --- | --- | --- | --- |
| `baseline-sample-pdc-v1` | 풍덕천동 주변만 | 11/5 | 상위 N%가 풍덕천동 안에서만 매긴 값이라 판정 흐름 테스트용 |
| `baseline-yongin-v1` | 용인 전체(신고 칸 + 200 m) | 11/6 | 실제 값 |
| `lgbm-yongin-v1` | 용인 전체 | 11/12 (베이스라인보다 좋을 때만) | 코드 변경 없음. 활성 버전만 바뀜 |

새 버전이 활성화되면 백엔드 채널에 버전 이름과 대상 칸 수를 공유한다.

## 6. 발표일 참고

2026-11-15(발표일)는 일요일이다. 실시간으로 시연하면 `weekday = 7`로 조회된다.
"토요일 저녁" 시나리오를 보여주려면 일요일 같은 시간대 결과도 미리 확인해 두거나, 시연 시각에 맞는 칸을 골라 둔다
(`detectedAt`이 30분보다 오래되면 알림이 나가지 않으므로 과거 시각을 넣는 방식은 쓰지 않는다).
