"""7단계: 시설(소화전·버스정류소·어린이보호구역) 좌표를 facilities 표에 적재

사용법 (yongyong 폴더에서, 있는 파일만 넣으면 됨):
    python etl/load_facilities.py \
        --hydrant data/raw/소방용수시설(제공표준).csv \
        --bus     "data/raw/국토교통부_전국 버스정류장 위치정보_20261020.csv" \
        --school  data/raw/경기도_용인시_어린이보호구역.csv
    --dry-run 을 붙이면 DB에 쓰지 않고 몇 건이 들어갈지만 보여준다.

규칙
- 파일 1개 = data_imports 1건 (파일 해시로 구분). 같은 파일을 다시 돌려도 중복 적재되지 않는다.
- 같은 종류의 새 파일을 넣으면 그 import가 활성화되고 이전 것은 비활성화된다 (API는 활성 import만 조회).
- 용인시 것만 넣는다. 좌표가 용인 범위를 벗어나면 버린다.
- 소화전·버스정류소는 적재 후 zone_types.has_facility_data = true (1단계 규칙 판정 가능).
- 어린이보호구역은 시설(학교 등)의 점 좌표다. 11/6 회의 결정: 주 출입문 기준 반경 300m로 판정
  (법: 반경 300m 이내 도로 중 일정 구간을 지정) → has_facility_data = true. 반경 값은 zone_rules가 가진다.
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

# 용인시를 넉넉히 감싸는 위경도 범위 (잘못된 좌표 거르기용)
LAT_RANGE = (37.0, 37.45)
LON_RANGE = (126.95, 127.50)


def read_csv_any(path: Path) -> pd.DataFrame:
    """공공데이터 CSV는 cp949와 UTF-8(BOM)이 섞여 있다"""
    for enc in ("utf-8-sig", "cp949"):
        try:
            return pd.read_csv(path, encoding=enc, dtype=str)
        except UnicodeDecodeError:
            continue
    raise ValueError(f"인코딩을 알 수 없어요: {path}")


def to_date(s) -> date | None:
    if s is None or pd.isna(s):
        return None
    s = str(s).strip().replace("-", "")
    return date(int(s[:4]), int(s[4:6]), int(s[6:8])) if len(s) >= 8 and s[:8].isdigit() else None


def clean(v):
    if v is None or (isinstance(v, float) and pd.isna(v)) or pd.isna(v):
        return None
    v = str(v).strip()
    return v or None


def unique_keys(keys: pd.Series) -> pd.Series:
    """원본 번호가 겹치면 #2, #3을 붙여 (import_id, source_record_key) 유일 조건을 지킨다"""
    n = keys.groupby(keys).cumcount()
    return keys.where(n == 0, keys + "#" + (n + 1).astype(str))


def finish(df: pd.DataFrame, zone_type: str) -> tuple[pd.DataFrame, dict]:
    """위경도 숫자 변환·범위 검사 후 적재용 표로 정리"""
    df = df.copy()
    df["lat"] = pd.to_numeric(df["lat"], errors="coerce")
    df["lon"] = pd.to_numeric(df["lon"], errors="coerce")
    ok = df["lat"].between(*LAT_RANGE) & df["lon"].between(*LON_RANGE)
    report = {"zone_type": zone_type, "rows_in_yongin": int(len(df)), "bad_coordinate": int((~ok).sum())}
    df = df[ok].copy()
    df["source_record_key"] = unique_keys(df["source_record_key"].astype(str))
    report["loaded"] = int(len(df))
    return df[["source_record_key", "zone_type", "name", "lat", "lon", "attributes", "source_date"]], report


# ─── 파일별 변환 ─────────────────────────────────────────
def hydrant_rows(raw: pd.DataFrame) -> tuple[pd.DataFrame, dict]:
    """소방용수시설(제공표준). 시설유형코드는 원본 그대로 attributes에 보관"""
    y = raw[raw["시군구명"].fillna("").str.contains("용인")]
    attr_cols = ["시설유형코드", "상세위치", "안전센터명", "보호틀유무", "사용가능여부", "설치연도",
                 "소재지도로명주소", "소재지지번주소", "관할기관명"]
    df = pd.DataFrame({
        "source_record_key": y["시설번호"].str.strip(),
        "zone_type": "HYDRANT",
        "name": y["상세위치"].map(clean),
        "lat": y["위도"], "lon": y["경도"],
        "attributes": [json.dumps({c: clean(r.get(c)) for c in attr_cols if c in y.columns}, ensure_ascii=False)
                       for r in y.to_dict("records")],
        "source_date": y["데이터기준일자"].map(to_date),
    })
    return finish(df, "HYDRANT")


def bus_rows(raw: pd.DataFrame) -> tuple[pd.DataFrame, dict]:
    """국토교통부 전국 버스정류장 위치정보 → 용인시(도시코드 31190)만"""
    y = raw[raw["도시명"].fillna("").str.contains("용인")]
    df = pd.DataFrame({
        "source_record_key": y["정류장번호"].str.strip(),
        "zone_type": "BUS_STOP",
        "name": y["정류장명"].map(clean),
        "lat": y["위도"], "lon": y["경도"],
        "attributes": [json.dumps({"모바일단축번호": clean(r.get("모바일단축번호")),
                                   "도시코드": clean(r.get("도시코드")),
                                   "관리도시명": clean(r.get("관리도시명"))}, ensure_ascii=False)
                       for r in y.to_dict("records")],
        "source_date": y["정보수집일"].map(to_date),
    })
    return finish(df, "BUS_STOP")


def school_rows(raw: pd.DataFrame) -> tuple[pd.DataFrame, dict]:
    """경기도 용인시 어린이보호구역. 원본에 번호가 없어 '종류|이름|지번주소'를 키로 쓴다"""
    y = raw
    key = (y["시설종류"].fillna("") + "|" + y["대상시설명"].fillna("") + "|" + y["소재지지번주소"].fillna(""))
    attr_cols = ["시설종류", "소재지도로명주소", "소재지지번주소", "관리기관명", "관할경찰서명",
                 "CCTV설치여부", "CCTV설치대수", "보호구역도로폭"]
    df = pd.DataFrame({
        "source_record_key": key,
        "zone_type": "SCHOOL_ZONE",
        "name": y["대상시설명"].map(clean),
        "lat": y["위도"], "lon": y["경도"],
        "attributes": [json.dumps({c: clean(r.get(c)) for c in attr_cols if c in y.columns}, ensure_ascii=False)
                       for r in y.to_dict("records")],
        "source_date": y["데이터기준일자"].map(to_date),
    })
    return finish(df, "SCHOOL_ZONE")


SOURCES = {
    # 인자이름: (source_dataset, 변환함수, 적재 후 has_facility_data를 true로 바꿀지)
    "hydrant": ("facility_hydrant_yongin", hydrant_rows, True),
    "bus": ("facility_bus_stop_molit", bus_rows, True),
    "school": ("facility_school_zone_yongin", school_rows, True),   # 회의 결정: 300m 판정
}


# ─── DB ─────────────────────────────────────────────────
def load_one(cur, path: Path, source_dataset: str, rows: pd.DataFrame, report: dict, flip: bool) -> None:
    file_hash = file_sha256(path)
    cur.execute("SELECT id FROM data_imports WHERE file_hash = %s", (file_hash,))
    row = cur.fetchone()
    if row:
        imp = row[0]
        cur.execute("SELECT count(*) FROM facilities WHERE import_id = %s", (imp,))
        if cur.fetchone()[0]:
            print(f"  이미 적재된 파일이라 건너뜀 (import {imp})")
            if flip:   # 판정 가능 여부는 정책이 바뀔 수 있어 건너뛰어도 맞춰 둔다
                cur.execute("UPDATE zone_types SET has_facility_data = true WHERE zone_type = %s",
                            (report["zone_type"],))
            return
    else:
        imp = uuid.uuid4()
        src_date = rows["source_date"].dropna().max() if rows["source_date"].notna().any() else None
        cur.execute(
            """INSERT INTO data_imports (id, source_dataset, file_name, file_hash, source_reference_date,
                                         total_rows, parse_failed_rows, note)
               VALUES (%s, %s, %s, %s, %s, %s, %s, %s)""",
            (imp, source_dataset, path.name, file_hash, src_date, report["rows_in_yongin"],
             report["bad_coordinate"], "용인시만 적재, 좌표 범위 밖 행 제외"))

    with cur.copy("COPY facilities (id, import_id, source_record_key, zone_type, name, spatial_data, "
                  "attributes, source_date) FROM STDIN") as cp:
        for r in rows.itertuples(index=False):
            cp.write_row((uuid.uuid4(), imp, r.source_record_key, r.zone_type, r.name,
                          f"SRID=4326;POINT({r.lon} {r.lat})", r.attributes, r.source_date))

    cur.execute("UPDATE data_imports SET active = false WHERE source_dataset = %s AND id <> %s AND active",
                (source_dataset, imp))
    cur.execute("UPDATE data_imports SET active = true, loaded_rows = %s WHERE id = %s", (len(rows), imp))
    if flip:
        cur.execute("UPDATE zone_types SET has_facility_data = true WHERE zone_type = %s", (report["zone_type"],))


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--hydrant", type=Path, help="소방용수시설(제공표준).csv")
    ap.add_argument("--bus", type=Path, help="국토교통부_전국 버스정류장 위치정보_*.csv")
    ap.add_argument("--school", type=Path, help="경기도_용인시_어린이보호구역.csv")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    jobs = []
    for name, (dataset, fn, flip) in SOURCES.items():
        path = getattr(args, name)
        if path is None:
            continue
        rows, report = fn(read_csv_any(path))
        print(f"{report['zone_type']:12s} 용인 {report['rows_in_yongin']:,}건 → 적재 {report['loaded']:,}건 "
              f"(좌표 없음·범위 밖 {report['bad_coordinate']}건 제외)")
        jobs.append((path, dataset, rows, report, flip))
    if not jobs:
        raise SystemExit("--hydrant / --bus / --school 중 하나 이상 넣어 주세요")
    if args.dry_run:
        print("dry-run: DB에 쓰지 않음")
        return

    with connect() as conn, conn.cursor() as cur:
        for path, dataset, rows, report, flip in jobs:
            print(f"{report['zone_type']} 적재 중...")
            load_one(cur, path, dataset, rows, report, flip)
            conn.commit()

        # 확인: 활성 import 기준 종류별 건수, 모델 영역(용인 경계) 안에 있는 비율
        cur.execute("""SELECT f.zone_type, count(*),
                              count(*) FILTER (WHERE EXISTS (
                                  SELECT 1 FROM spatial_grids g
                                   WHERE g.projected_srid = 5186 AND g.cell_size_m = 50 AND g.in_model_area
                                     AND g.grid_x = floor(ST_X(ST_Transform(f.spatial_data::geometry, 5186)) / 50)::int  -- ::int 없으면 인덱스를 못 써서 매우 느림
                                     AND g.grid_y = floor(ST_Y(ST_Transform(f.spatial_data::geometry, 5186)) / 50)::int))
                         FROM facilities f JOIN data_imports d ON d.id = f.import_id AND d.active
                        GROUP BY f.zone_type ORDER BY 1""")
        print("활성 시설 (종류, 건수, 용인 격자 안):")
        for r in cur.fetchall():
            print("  ", r)
        cur.execute("SELECT zone_type, has_facility_data FROM zone_types ORDER BY zone_type")
        print("zone_types:", cur.fetchall())


if __name__ == "__main__":
    main()
