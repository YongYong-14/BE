# ETL (데이터 적재·예측 모델)

Python 3.11 이상. 저장소 루트에서 실행한다.

```bash
python3 -m venv etl/.venv && source etl/.venv/bin/activate
pip install -r etl/requirements.txt
pytest etl/tests
```

## 1단계: 단속 CSV 정제 (DB 불필요)

```bash
python etl/parse_enforcement.py \
  --input "data/raw/경기도 용인시_주정차단속현황_20251115.csv" \
  --out etl/out
```

| 결과 파일 | 내용 |
| --- | --- |
| `enforcement_parsed.csv` | 원본 전 행 + 지번·위반유형·중복 후보 (`enforcement_records` 적재용) |
| `parcels.csv` | 고유 지번 목록 (지오코딩 대상) |
| `parse_report.json` | 건수 요약 (`data_imports` 기록·발표 자료용) |

규칙은 `enforcement_parser.py`, 칸·시간 계산 기준은 `docs/GRID_AND_TIME_RULES.md`.
`etl/out/`과 `etl/.env`는 커밋하지 않는다.
