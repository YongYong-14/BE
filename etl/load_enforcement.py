"""4단계: 정제 결과 + 지오코딩 결과 → DB

사용법 (1·2·3단계 결과가 etl/out 에 있어야 함, spatial_grids 적재 후):
    python etl/load_enforcement.py \
        --input "data/raw/경기도 용인시_주정차단속현황_20251115.csv" --out-dir etl/out

넣는 표 (순서대로, 각 단계는 여러 번 실행해도 같은 결과):
    zone_types, holidays                 기준값
    data_imports, source_availability    원천 파일 기록, 시민 신고 관측 기간
    geocoded_parcels                     지번 좌표
    enforcement_records                  원본 전 행 (+ 지번·좌표·칸)
    parcel_report_stats                  지번별 시민 신고 건수 ("반경 내 N건")
    report_aggregates                    칸 × 요일 × 시간대 건수 (full / train / valid 기간)
"""
from __future__ import annotations

import argparse
import json
import uuid
from datetime import date
from pathlib import Path

import pandas as pd

from db import connect
from parse_enforcement import file_sha256
from reference_data import HOLIDAYS, OBS_END, OBS_START, PERIODS, SOURCE_DATASET, ZONE_TYPES

GRID_SQL = """
    UPDATE enforcement_records e
       SET grid_id = g.id
      FROM (SELECT id AS rid,
                   floor(ST_X(ST_Transform(location::geometry, 5186)) / 50)::int AS gx,
                   floor(ST_Y(ST_Transform(location::geometry, 5186)) / 50)::int AS gy
              FROM enforcement_records
             WHERE import_id = %(imp)s AND location IS NOT NULL) c
      JOIN spatial_grids g
        ON g.projected_srid = 5186 AND g.cell_size_m = 50 AND g.grid_x = c.gx AND g.grid_y = c.gy
     WHERE e.id = c.rid
"""

# 시민 신고 + 지번/도로 정밀도 + 관측 기간 안 (공휴일은 일요일 7로 치환, 한국 시간 기준)
CITIZEN_BASE = """
    SELECT e.id, e.parcel_id, e.grid_id, e.event_group_key,
           COALESCE(e.violation_type, 'OTHER') AS vt,
           (e.enforced_at AT TIME ZONE 'Asia/Seoul') AS kst
      FROM enforcement_records e
     WHERE e.import_id = %(imp)s
       AND e.is_citizen_report
       AND e.location_precision IN ('PARCEL', 'ROAD')
       AND (e.enforced_at AT TIME ZONE 'Asia/Seoul')::date BETWEEN %(ps)s AND %(pe)s
"""


def seed_reference(cur) -> None:
    cur.executemany(
        "INSERT INTO zone_types (zone_type, label, has_facility_data) VALUES (%s, %s, %s) "
        "ON CONFLICT (zone_type) DO NOTHING", ZONE_TYPES)
    cur.executemany(
        "INSERT INTO holidays (holiday_date, name) VALUES (%s, %s) ON CONFLICT (holiday_date) DO NOTHING", HOLIDAYS)


def ensure_import(cur, path: Path, report: dict) -> uuid.UUID:
    file_hash = file_sha256(path)
    cur.execute("SELECT id FROM data_imports WHERE file_hash = %s", (file_hash,))
    row = cur.fetchone()
    if row:
        return row[0]
    imp = uuid.uuid4()
    cur.execute(
        """INSERT INTO data_imports (id, source_dataset, file_name, file_hash, source_reference_date,
                                     total_rows, parse_failed_rows, note)
           VALUES (%s, %s, %s, %s, %s, %s, %s, %s)""",
        (imp, SOURCE_DATASET, path.name, file_hash, date(2025, 11, 15), report["total_rows"],
         report["citizen"]["rows"] - report["citizen"]["by_parse_status"].get("PARSED", 0),
         f"parser {report['parser_version']}, 차량번호 마스킹"),
    )
    cur.execute(
        """INSERT INTO source_availability (import_id, record_category, observed_start, observed_end, note)
           VALUES (%s, 'CITIZEN_REPORT', %s, %s, '안전신문고+새올민원. 2025-09-26 19시 이후 기록 없음')""",
        (imp, OBS_START, OBS_END),
    )
    return imp


def load_parcels(cur, out_dir: Path) -> None:
    parcels = pd.read_csv(out_dir / "parcels.csv")
    parcels = parcels[parcels["citizen_rows"] > 0]
    cache_path = out_dir / "geocode_cache.csv"
    cache = pd.read_csv(cache_path) if cache_path.exists() else pd.DataFrame(columns=["parcel_key"])
    df = parcels.merge(cache.drop(columns=["normalized_address"], errors="ignore"), on="parcel_key", how="left")
    df = df.astype(object).where(pd.notna(df), None)   # numpy 값 → 파이썬 값, NaN → None

    cur.execute("""CREATE TEMP TABLE tmp_parcels (parcel_key text, lng float8, lat float8, match_level text,
                   status text, normalization_method text, normalized_address text, provider text,
                   failure_reason text, geocoded_at timestamptz) ON COMMIT DROP""")
    with cur.copy("COPY tmp_parcels FROM STDIN") as cp:
        for r in df.itertuples(index=False):
            cp.write_row((r.parcel_key, r.lng, r.lat, r.match_level, r.status or "PENDING",
                          r.normalization_method, r.normalized_address, r.provider, r.failure_reason,
                          r.geocoded_at))
    cur.execute(
        """INSERT INTO geocoded_parcels (id, parcel_key, location, match_level, status, first_attempt_status,
                                         normalization_method, normalized_address, provider, failure_reason,
                                         geocoded_at)
           SELECT gen_random_uuid(), parcel_key,
                  CASE WHEN lng IS NULL THEN NULL ELSE ST_SetSRID(ST_MakePoint(lng, lat), 4326)::geography END,
                  match_level, status, status, normalization_method, normalized_address, provider, failure_reason,
                  geocoded_at
             FROM tmp_parcels
           ON CONFLICT (parcel_key) DO UPDATE
              SET location = EXCLUDED.location, match_level = EXCLUDED.match_level, status = EXCLUDED.status,
                  normalized_address = EXCLUDED.normalized_address, provider = EXCLUDED.provider,
                  failure_reason = EXCLUDED.failure_reason, geocoded_at = EXCLUDED.geocoded_at,
                  first_attempt_status = COALESCE(geocoded_parcels.first_attempt_status, EXCLUDED.status),
                  updated_at = now()""")
    cur.execute("SELECT status, count(*) FROM geocoded_parcels GROUP BY 1 ORDER BY 1")
    print("  geocoded_parcels:", dict(cur.fetchall()))


def load_records(cur, imp: uuid.UUID, out_dir: Path, total_rows: int, reload: bool) -> None:
    cur.execute("SELECT count(*) FROM enforcement_records WHERE import_id = %s", (imp,))
    existing = cur.fetchone()[0]
    if existing == total_rows and not reload:
        print(f"  enforcement_records: 이미 {existing:,}행 있음 → 좌표·칸만 다시 연결")
    else:
        cur.execute("DELETE FROM enforcement_records WHERE import_id = %s", (imp,))
        df = pd.read_csv(out_dir / "enforcement_parsed.csv", dtype={"parcel_key": str, "event_group_key": str})
        cols = ["source_row_no", "enforced_at", "enforcement_type", "is_citizen_report", "district", "place_raw",
                "parse_status", "parcel_key", "violation_type", "violation_type_multi", "parser_version",
                "event_group_key", "is_exact_duplicate"]
        cur.execute("""CREATE TEMP TABLE tmp_er (source_row_no int, enforced_at timestamptz, enforcement_type text,
                       is_citizen_report bool, district text, place_raw text, parse_status text, parcel_key text,
                       violation_type text, violation_type_multi bool, parser_version text, event_group_key text,
                       is_exact_duplicate bool) ON COMMIT DROP""")
        with cur.copy(f"COPY tmp_er ({', '.join(cols)}) FROM STDIN") as cp:
            rows = df[cols].astype(object).where(pd.notna(df[cols]), None)   # NaN → None, numpy → 파이썬 값
            for r in rows.itertuples(index=False):
                cp.write_row(r)
        cur.execute(
            """INSERT INTO enforcement_records (import_id, source_row_no, enforced_at, enforcement_type,
                   is_citizen_report, district, place_raw, parse_status, violation_type, violation_type_multi,
                   parser_version, event_group_key, is_exact_duplicate)
               SELECT %s, source_row_no, enforced_at, enforcement_type, is_citizen_report, district, place_raw,
                      parse_status, violation_type, violation_type_multi, parser_version, event_group_key,
                      is_exact_duplicate
                 FROM tmp_er""", (imp,))
        cur.execute(
            """UPDATE enforcement_records e SET parcel_id = p.id
                 FROM tmp_er t JOIN geocoded_parcels p ON p.parcel_key = t.parcel_key
                WHERE e.import_id = %s AND e.source_row_no = t.source_row_no""", (imp,))
        print(f"  enforcement_records: {len(df):,}행 적재")

    # 지번 좌표 복사 (지번 좌표가 바뀌었을 수 있으니 매번 다시 연결)
    # AMBIGUOUS(같은 지번이 여러 곳에서 검색됨)는 위치가 틀렸을 수 있어 좌표를 쓰지 않는다 → 통계·예측에서 빠짐.
    # 참고용 첫 번째 좌표는 geocoded_parcels에만 남는다.
    cur.execute(
        """UPDATE enforcement_records e
              SET location = CASE WHEN p.status = 'AMBIGUOUS' THEN NULL ELSE p.location END,
                  location_precision = CASE WHEN p.location IS NULL OR p.status = 'AMBIGUOUS' THEN 'NONE'
                                            ELSE p.match_level END,
                  grid_id = NULL
             FROM geocoded_parcels p
            WHERE e.import_id = %s AND e.parcel_id = p.id""", (imp,))
    cur.execute(
        "UPDATE enforcement_records SET location_precision = 'NONE' WHERE import_id = %s AND parcel_id IS NULL",
        (imp,))
    cur.execute(GRID_SQL, {"imp": imp})
    cur.execute(
        """SELECT count(*) FILTER (WHERE is_citizen_report),
                  count(*) FILTER (WHERE is_citizen_report AND location IS NOT NULL),
                  count(*) FILTER (WHERE is_citizen_report AND grid_id IS NOT NULL)
             FROM enforcement_records WHERE import_id = %s""", (imp,))
    c, loc, grid = cur.fetchone()
    cur.execute(
        """SELECT count(*) FROM enforcement_records e JOIN geocoded_parcels p ON p.id = e.parcel_id
            WHERE e.import_id = %s AND e.is_citizen_report AND p.status = 'AMBIGUOUS'""", (imp,))
    amb = cur.fetchone()[0]
    print(f"  시민 신고 {c:,}행 → 좌표 {loc:,}행 → 용인 칸 연결 {grid:,}행 (AMBIGUOUS {amb:,}행 제외)")


def build_stats(cur, imp: uuid.UUID) -> None:
    # 방금 넣은 표는 통계가 없어 DB가 건수를 매우 작게 추정하고 중첩 반복으로 조인한다(수십 분).
    # 이 트랜잭션에서만 해시 조인을 쓰게 해 수 초로 줄인다.
    cur.execute("SET LOCAL enable_nestloop = off")
    ps, pe = PERIODS["full"]
    cur.execute("DELETE FROM parcel_report_stats WHERE import_id = %s", (imp,))
    cur.execute(
        f"""WITH base AS ({CITIZEN_BASE}),
                 vt AS (SELECT parcel_id, jsonb_object_agg(vt, n) AS j
                          FROM (SELECT parcel_id, vt, count(*) AS n FROM base GROUP BY 1, 2) x GROUP BY 1),
                 agg AS (SELECT parcel_id, count(*) AS n, count(DISTINCT event_group_key) AS g
                           FROM base GROUP BY 1)
            INSERT INTO parcel_report_stats (import_id, parcel_id, location, period_start, period_end,
                                             report_count, event_group_count, count_by_violation_type)
            SELECT %(imp)s, a.parcel_id, p.location, %(ps)s, %(pe)s, a.n, a.g, vt.j
              FROM agg a JOIN geocoded_parcels p ON p.id = a.parcel_id JOIN vt ON vt.parcel_id = a.parcel_id""",
        {"imp": imp, "ps": ps, "pe": pe})
    print(f"  parcel_report_stats: {cur.rowcount:,}개 지번 ({ps} ~ {pe})")

    cur.execute("DELETE FROM report_aggregates WHERE import_id = %s", (imp,))
    for name, (ps, pe) in PERIODS.items():
        cur.execute(
            f"""WITH base AS ({CITIZEN_BASE}),
                     k AS (SELECT b.grid_id, b.event_group_key, b.vt,
                                  CASE WHEN h.holiday_date IS NOT NULL THEN 7
                                       ELSE extract(isodow FROM b.kst)::int END AS wd,
                                  extract(hour FROM b.kst)::int / 3 AS slot
                             FROM base b LEFT JOIN holidays h ON h.holiday_date = b.kst::date
                            WHERE b.grid_id IS NOT NULL),
                     days AS (SELECT CASE WHEN h.holiday_date IS NOT NULL THEN 7
                                          ELSE extract(isodow FROM d)::int END AS wd, count(*) AS n
                                FROM generate_series(%(ps)s::date, %(pe)s::date, interval '1 day') d
                                LEFT JOIN holidays h ON h.holiday_date = d::date
                               GROUP BY 1),
                     vt AS (SELECT grid_id, wd, slot, jsonb_object_agg(vt, n) AS j
                              FROM (SELECT grid_id, wd, slot, vt, count(*) AS n FROM k GROUP BY 1, 2, 3, 4) x
                             GROUP BY 1, 2, 3),
                     agg AS (SELECT grid_id, wd, slot, count(*) AS n, count(DISTINCT event_group_key) AS g
                               FROM k GROUP BY 1, 2, 3)
                INSERT INTO report_aggregates (import_id, grid_id, weekday, time_slot, period_start, period_end,
                                               day_count, report_count, event_group_count, count_by_violation_type)
                SELECT %(imp)s, a.grid_id, a.wd, a.slot, %(ps)s, %(pe)s, d.n, a.n, a.g, vt.j
                  FROM agg a JOIN days d ON d.wd = a.wd
                  JOIN vt ON (vt.grid_id, vt.wd, vt.slot) = (a.grid_id, a.wd, a.slot)""",
            {"imp": imp, "ps": ps, "pe": pe})
        print(f"  report_aggregates[{name}]: {cur.rowcount:,}행 ({ps} ~ {pe})")


def activate_import(cur, imp: uuid.UUID, report: dict) -> None:
    # AMBIGUOUS는 좌표를 쓰지 않으므로 실패 쪽으로 센다
    cur.execute("SELECT count(*) FILTER (WHERE status = 'SUCCEEDED'), "
                "count(*) FILTER (WHERE status IN ('FAILED', 'AMBIGUOUS')) FROM geocoded_parcels")
    ok, failed = cur.fetchone()
    cur.execute("UPDATE data_imports SET active = false WHERE source_dataset = %s AND id <> %s AND active",
                (SOURCE_DATASET, imp))
    cur.execute(
        """UPDATE data_imports SET active = true, loaded_rows = %s, geocode_succeeded = %s, geocode_failed = %s
            WHERE id = %s""", (report["total_rows"], ok, failed, imp))


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", required=True, type=Path)
    ap.add_argument("--out-dir", default=Path("etl/out"), type=Path)
    ap.add_argument("--reload", action="store_true", help="enforcement_records를 지우고 다시 넣기")
    args = ap.parse_args()

    report = json.loads((args.out_dir / "parse_report.json").read_text(encoding="utf-8"))
    with connect() as conn, conn.cursor() as cur:
        # 단계마다 저장한다. 중간에 실패해도 앞 단계는 남고, 다시 실행하면 이어서 맞춰진다.
        print("1) 기준값"); seed_reference(cur); conn.commit()
        print("2) 원천 파일"); imp = ensure_import(cur, args.input, report); conn.commit()
        print(f"  import_id {imp}")
        print("3) 지번 좌표"); load_parcels(cur, args.out_dir); conn.commit()
        print("4) 단속 원본"); load_records(cur, imp, args.out_dir, report["total_rows"], args.reload); conn.commit()
        print("5) 통계·집계"); build_stats(cur, imp); conn.commit()
        activate_import(cur, imp, report)
    print("완료. data_imports 활성화:", SOURCE_DATASET)


if __name__ == "__main__":
    main()
