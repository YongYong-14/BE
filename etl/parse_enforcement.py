"""1단계: 단속 CSV → 정제 결과 파일 (DB 없이 실행)

사용법:
    python etl/parse_enforcement.py \
        --input "data/raw/경기도_용인시_주정차단속현황_20251115.csv" \
        --out etl/out

만드는 파일:
    enforcement_parsed.csv  원본 전 행 + 정제 결과 (enforcement_records 적재용)
    parcels.csv             고유 지번 목록 (지오코딩 대상, 시민 신고 건수 순)
    parse_report.json       건수 요약 (data_imports 기록·발표 자료용)
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

import pandas as pd

from enforcement_parser import (
    CITIZEN_REPORT_TYPES,
    PARSER_VERSION,
    classify_violation,
    district_of,
    mask_plate,
    normalized_address,
    parse_place,
)

SOURCE_DATASET = "yongin_enforcement"
ENCODING = "cp949"  # 공공데이터 원본 인코딩


def file_sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def parse_file(path: Path) -> pd.DataFrame:
    raw = pd.read_csv(path, encoding=ENCODING, dtype=str, keep_default_na=False)
    df = pd.DataFrame()
    df["source_row_no"] = range(1, len(raw) + 1)
    df["enforced_at"] = pd.to_datetime(raw["단속일시"], format="%Y-%m-%d %H:%M:%S").dt.tz_localize("Asia/Seoul")
    df["enforcement_type"] = raw["단속구분"].str.strip()
    df["is_citizen_report"] = df["enforcement_type"].isin(CITIZEN_REPORT_TYPES)
    df["district"] = raw["시군명"].map(district_of)
    df["place_raw"] = raw["단속장소"].map(mask_plate)

    parsed = [parse_place(p, d) for p, d in zip(df["place_raw"], df["district"])]
    df["parse_status"] = [p.parse_status for p in parsed]
    df["parcel_key"] = [p.parcel_key for p in parsed]
    df["normalization_method"] = [p.normalization_method for p in parsed]

    vt = [classify_violation(p.rest, c) for p, c in zip(parsed, df["is_citizen_report"])]
    df["violation_type"] = [v[0] for v in vt]
    df["violation_type_multi"] = [v[1] for v in vt]
    df["parser_version"] = PARSER_VERSION

    # 중복 "후보" 그룹: 분 단위 시각 + 지번 (신고 1회로 확정하지 않음)
    minute = df["enforced_at"].dt.strftime("%Y-%m-%dT%H:%M")
    df["event_group_key"] = (minute + "|" + df["parcel_key"]).where(df["parcel_key"].notna())

    # 완전 중복: 시각·장소·단속구분이 모두 같은 행. 첫 행은 false, 이후 행만 true
    df["is_exact_duplicate"] = df.duplicated(["enforced_at", "place_raw", "enforcement_type"], keep="first")
    return df


def build_parcels(df: pd.DataFrame) -> pd.DataFrame:
    p = df[df["parcel_key"].notna()]
    g = p.groupby("parcel_key").agg(
        district=("district", "first"),
        total_rows=("source_row_no", "size"),
        citizen_rows=("is_citizen_report", "sum"),
        normalization_method=("normalization_method", lambda s: "RULE" if (s == "RULE").any() else "NONE"),
    ).reset_index()
    g["normalized_address"] = g["parcel_key"].map(normalized_address)
    return g.sort_values(["citizen_rows", "total_rows"], ascending=False)


def build_report(path: Path, df: pd.DataFrame, parcels: pd.DataFrame) -> dict:
    cit = df[df["is_citizen_report"]]
    return {
        "source_dataset": SOURCE_DATASET,
        "file_name": path.name,
        "file_hash": file_sha256(path),
        "parser_version": PARSER_VERSION,
        "total_rows": int(len(df)),
        "enforced_at_range": [str(df["enforced_at"].min()), str(df["enforced_at"].max())],
        "by_enforcement_type": df["enforcement_type"].value_counts().to_dict(),
        "by_parse_status": df["parse_status"].value_counts().to_dict(),
        "citizen": {
            "rows": int(len(cit)),
            "by_parse_status": cit["parse_status"].value_counts().to_dict(),
            "parsed_rate": round(float((cit["parse_status"] == "PARSED").mean()), 4),
            "by_violation_type": cit["violation_type"].value_counts().to_dict(),
            "violation_type_multi": int(cit["violation_type_multi"].sum()),
            "exact_duplicates": int(cit["is_exact_duplicate"].sum()),
            "event_groups": int(cit["event_group_key"].nunique()),
            "failed_samples": cit.loc[cit["parse_status"] != "PARSED", "place_raw"].head(20).tolist(),
        },
        "parcels": {
            "unique": int(len(parcels)),
            "with_citizen_reports": int((parcels["citizen_rows"] > 0).sum()),
            "admin_dong_normalized": int((parcels["normalization_method"] == "RULE").sum()),
            "by_district": parcels.groupby("district").size().to_dict(),
        },
    }


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", required=True, type=Path)
    ap.add_argument("--out", required=True, type=Path)
    args = ap.parse_args()

    args.out.mkdir(parents=True, exist_ok=True)
    df = parse_file(args.input)
    parcels = build_parcels(df)
    report = build_report(args.input, df, parcels)

    df.to_csv(args.out / "enforcement_parsed.csv", index=False, encoding="utf-8")
    parcels.to_csv(args.out / "parcels.csv", index=False, encoding="utf-8")
    (args.out / "parse_report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    c = report["citizen"]
    print(f"전체 {report['total_rows']:,}행 / 시민 신고 {c['rows']:,}행")
    print(f"시민 신고 지번 추출 {c['parsed_rate']:.1%}  (실패·도로명 {c['rows'] - c['by_parse_status'].get('PARSED', 0)}건)")
    print(f"고유 지번 {report['parcels']['unique']:,}개 (시민 신고 있는 지번 {report['parcels']['with_citizen_reports']:,}개)")
    print(f"결과: {args.out}")


if __name__ == "__main__":
    main()
