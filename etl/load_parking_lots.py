"""Load a parking CSV atomically; retain snapshots and activate one per dataset.

Uses the existing db.connect (etl/.env) and never modifies the source CSV.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import io
import json
import logging
import math
import re
import uuid
from datetime import date, time
from pathlib import Path

from db import connect

SOURCE_DATASET = "yongin_parking_lots"
LOG = logging.getLogger(__name__)
TIME_FIELDS = [f"{day}운영{edge}시각" for day in ("평일", "토요일", "공휴일") for edge in ("시작", "종료")]
FEE_FIELDS = ("요금정보", "주차기본시간", "주차기본요금", "추가단위시간", "추가단위요금",
              "1일주차권요금적용시간", "1일주차권요금", "월정기권요금", "결제방법", "특기사항")
REQUIRED = {"주차장관리번호", "주차장명", "위도", "경도", "주차장구분", "주차구획수", "데이터기준일자", *TIME_FIELDS, *FEE_FIELDS}


def parse_csv(content: bytes):
    reader = csv.DictReader(io.StringIO(content.decode("utf-8-sig"), newline=""))
    if not REQUIRED.issubset(reader.fieldnames or []):
        raise ValueError("Missing required CSV headers")
    records, issues, keys = [], [], set()
    total = 0
    for row_no, raw in enumerate(reader, 2):
        total += 1
        if None in raw or any(v is None for v in raw.values()):
            raise ValueError(f"row {row_no}: malformed CSV")
        row = {k: v.strip() for k, v in raw.items()}
        number, name = row["주차장관리번호"], row["주차장명"]
        if not number or not name or "|" in number or "|" in name:
            raise ValueError(f"row {row_no}: invalid source identity")
        key = number + "|" + name
        if key in keys:
            raise ValueError(f"row {row_no}: duplicate composite source key")
        keys.add(key)
        if len(key) > 150 or len(name) > 200:
            raise ValueError(f"row {row_no}: source identity too long")
        if not row["위도"] or not row["경도"]:
            issues.append({"row": row_no, "reason": "missing_coordinates", "skipped": True})
            continue
        lat, lng = float(row["위도"]), float(row["경도"])
        if not math.isfinite(lat) or not math.isfinite(lng) or not -90 <= lat <= 90 or not -180 <= lng <= 180:
            raise ValueError(f"row {row_no}: invalid coordinates")
        hours = []
        for a, b in zip(TIME_FIELDS[::2], TIME_FIELDS[1::2]):
            try:
                pair = [time.fromisoformat(row[k]) if re.fullmatch(r"\d{2}:\d{2}(?::\d{2})?", row[k]) else None for k in (a, b)]
                if any(row[k] for k in (a, b)) and (None in pair):
                    raise ValueError()
            except ValueError:
                pair = [None, None]
                issues.append({"row": row_no, "reason": "invalid_time_pair", "fields": [a, b], "raw": [raw[a], raw[b]]})
            hours.extend(pair)
        fee = {"무료": "FREE", "유료": "PAID", "혼합": "MIXED"}.get(row["요금정보"])
        if fee is None:
            issues.append({"row": row_no, "reason": "unknown_fee_type", "raw": raw["요금정보"]})
        kind = {"공영": "PUBLIC", "민영": "PRIVATE", "부설": "ATTACHED"}.get(row["주차장구분"])
        if kind is None:
            issues.append({"row": row_no, "reason": "unknown_parking_type", "raw": raw["주차장구분"]})
        fee_info = "; ".join(f"{k}: {raw[k]}" for k in FEE_FIELDS if row[k]) or None
        records.append((key, name, row.get("소재지도로명주소") or row.get("소재지지번주소") or None,
                        lng, lat, kind, fee, fee_info,
                        int(row["주차구획수"]) if row["주차구획수"] else None,
                        date.fromisoformat(row["데이터기준일자"]) if row["데이터기준일자"] else None, *hours))
    return records, total, issues


def load(conn, path: Path):
    # Hash and parse the same bytes, even if the on-disk file changes during loading.
    content = path.read_bytes()
    records, total, issues = parse_csv(content)
    digest = hashlib.sha256(content).hexdigest()
    skipped = sum(x.get("skipped", False) for x in issues)
    for issue in issues:
        LOG.warning("CSV issue: %s", json.dumps(issue, ensure_ascii=False))
    if not records:
        raise ValueError("CSV has no valid parking lots; active import is unchanged")
    with conn.transaction(), conn.cursor() as cur:
        # Serialize dataset activation and same-file concurrent loads (including the first load).
        cur.execute("SELECT pg_advisory_xact_lock(hashtextextended(%s,0))", (SOURCE_DATASET,))
        cur.execute("SELECT id,source_dataset,active,loaded_rows FROM data_imports WHERE file_hash=%s FOR UPDATE", (digest,))
        existing = cur.fetchone()
        if existing:
            imp, dataset, active, loaded = existing
            if dataset != SOURCE_DATASET:
                raise ValueError("File hash belongs to a different dataset")
            cur.execute("SELECT count(*) FROM parking_lots WHERE import_id=%s", (imp,))
            if loaded != len(records) or cur.fetchone()[0] != len(records):
                raise ValueError("Existing import is incomplete; explicit repair is required")
            # An old file rerun must not silently replace a newer active snapshot.
            return {"import_id": str(imp), "total": total, "loaded": loaded, "skipped": skipped, "reused": True, "active": active}
        imp = uuid.uuid4()
        dates = {r[9] for r in records}
        cur.execute("""INSERT INTO data_imports(id,source_dataset,file_name,file_hash,source_reference_date,
                       total_rows,loaded_rows,parse_failed_rows,note)
                       VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s)""",
                    (imp, SOURCE_DATASET, path.name, digest, next(iter(dates)) if len(dates) == 1 else None,
                     total, len(records), skipped, json.dumps({"issues": issues}, ensure_ascii=False)))
        cur.executemany("""INSERT INTO parking_lots(id,import_id,source_record_key,name,address,location,parking_type,
                            fee_type,fee_info,capacity,source_date,weekday_open,weekday_close,saturday_open,saturday_close,holiday_open,holiday_close)
                            VALUES (%s,%s,%s,%s,%s,ST_SetSRID(ST_MakePoint(%s,%s),4326)::geography,
                                    %s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)""",
                        [(uuid.uuid4(), imp, *r) for r in records])
        cur.execute("UPDATE data_imports SET active=false WHERE source_dataset=%s AND active", (SOURCE_DATASET,))
        cur.execute("UPDATE data_imports SET active=true WHERE id=%s", (imp,))
    return {"import_id": str(imp), "total": total, "loaded": len(records), "skipped": skipped, "reused": False, "active": True}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO)
    with connect() as conn:
        result = load(conn, args.input)
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
