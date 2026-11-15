import csv
import io
import os
import sys
from pathlib import Path

import psycopg
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from load_parking_lots import REQUIRED, SOURCE_DATASET, load, parse_csv


def csv_bytes(rows):
    out = io.StringIO(newline="")
    writer = csv.DictWriter(out, fieldnames=sorted(REQUIRED | {"소재지도로명주소", "소재지지번주소"}))
    writer.writeheader()
    writer.writerows(rows)
    return out.getvalue().encode("utf-8-sig")


def row(**changes):
    result = dict.fromkeys(REQUIRED, "")
    result.update({"주차장관리번호": "same", "주차장명": "one", "위도": "37.3", "경도": "127.1",
                   "주차장구분": "부설", "요금정보": "무료", "주차구획수": "20",
                   "평일운영시작시각": "22:00", "평일운영종료시각": "06:00"})
    result.update(changes)
    return result


def test_normal_attached_and_overnight():
    records, total, issues = parse_csv(csv_bytes([row(특기사항="09시 개방")]))
    assert (total, issues) == (1, [])
    assert records[0][0] == "same|one"
    assert records[0][5:7] == ("ATTACHED", "FREE")
    assert "09시 개방" in records[0][7]
    assert str(records[0][10]) == "22:00:00"
    assert str(records[0][11]) == "06:00:00"


@pytest.mark.parametrize("value,expected", [("무료", "FREE"), ("유료", "PAID"), ("혼합", "MIXED"), ("미상", None), ("", None)])
def test_fee_uses_only_source_category(value, expected):
    records, _, issues = parse_csv(csv_bytes([row(요금정보=value, 주차기본요금="1000")]))
    assert records[0][6] == expected
    assert bool(issues) == (expected is None)


def test_skip_missing_coordinates_and_keep_duplicate_numbers():
    records, total, issues = parse_csv(csv_bytes([row(), row(주차장명="two"), row(주차장명="missing", 위도="")]))
    assert total == 3 and len(records) == 2
    assert [r[0] for r in records] == ["same|one", "same|two"]
    assert issues == [{"row": 4, "reason": "missing_coordinates", "skipped": True}]


def test_composite_duplicate_fails_even_on_skipped_row():
    with pytest.raises(ValueError, match="duplicate composite"):
        parse_csv(csv_bytes([row(), row(위도="")]))


@pytest.mark.parametrize("start,end", [("bad", "18:00"), ("09:00", ""), ("", "18:00"), ("24:00", "24:00")])
def test_unreadable_or_incomplete_pair_is_null_with_reason(start, end):
    records, _, issues = parse_csv(csv_bytes([row(평일운영시작시각=start, 평일운영종료시각=end)]))
    assert records[0][10:12] == (None, None)
    assert issues[0]["reason"] == "invalid_time_pair"


@pytest.fixture
def isolated_connection():
    url = os.environ.get("PARKING_LOTS_TEST_DSN")
    if not url:
        pytest.skip("requires explicitly provisioned isolated parking_lots_verify DB")
    conn = psycopg.connect(url)
    with conn.cursor() as cur:
        cur.execute("SELECT current_database(),current_user,current_setting('verification.identity',true)")
        assert cur.fetchone() == ("parking_lots_verify", "parking_loader", os.environ["PARKING_LOTS_TEST_IDENTITY"])
    conn.commit()
    with conn:
        yield conn


def test_database_idempotency_snapshot_and_atomic_failure(isolated_connection, tmp_path):
    conn = isolated_connection
    path = tmp_path / "fixture.csv"
    path.write_bytes(csv_bytes([row(), row(주차장명="two"), row(주차장명="missing", 경도="")]))
    first = load(conn, path)
    second = load(conn, path)
    assert second["reused"] and first["import_id"] == second["import_id"]
    with conn.cursor() as cur:
        cur.execute("SELECT count(*) FROM parking_lots WHERE import_id=%s", (first["import_id"],))
        assert cur.fetchone()[0] == 2
    conn.commit()
    # Force a DB failure after the import INSERT. No partial rows/import/activation may survive.
    path.write_bytes(csv_bytes([row(), row(주차장명="bad", 주차구획수="99999999999999999")]))
    with pytest.raises(psycopg.Error):
        load(conn, path)
    with conn.cursor() as cur:
        cur.execute("SELECT count(*) FROM data_imports WHERE source_dataset=%s", (SOURCE_DATASET,))
        assert cur.fetchone()[0] == 1
        cur.execute("SELECT active FROM data_imports WHERE id=%s", (first["import_id"],))
        assert cur.fetchone()[0]
    conn.commit()
    path.write_bytes(csv_bytes([row(주차장명="replacement")]))
    replacement = load(conn, path)
    path.write_bytes(csv_bytes([row(), row(주차장명="two"), row(주차장명="missing", 경도="")]))
    assert load(conn, path)["active"] is False
    with conn.cursor() as cur:
        cur.execute("SELECT id::text FROM data_imports WHERE source_dataset=%s AND active", (SOURCE_DATASET,))
        assert cur.fetchone()[0] == replacement["import_id"]
    conn.commit()


def test_zero_valid_rows_rejected_without_replacing_active_import(isolated_connection, tmp_path):
    conn = isolated_connection
    valid = tmp_path / "valid.csv"
    valid.write_bytes(csv_bytes([row(주차장명="keep active")]))
    active = load(conn, valid)
    with conn.cursor() as cur:
        cur.execute("SELECT id,active,loaded_rows FROM data_imports ORDER BY id")
        imports_before = cur.fetchall()
        cur.execute("SELECT id,import_id,source_record_key FROM parking_lots ORDER BY id")
        lots_before = cur.fetchall()
    conn.commit()
    empty = tmp_path / "empty.csv"
    # Both an empty CSV and a nonempty CSV whose rows are all skipped must be rejected.
    for rows in ([], [row(주차장명="missing coordinates", 위도="")]):
        empty.write_bytes(csv_bytes(rows))
        with pytest.raises(ValueError, match="no valid parking lots"):
            load(conn, empty)
        with conn.cursor() as cur:
            cur.execute("SELECT id,active,loaded_rows FROM data_imports ORDER BY id")
            assert cur.fetchall() == imports_before
            cur.execute("SELECT id,import_id,source_record_key FROM parking_lots ORDER BY id")
            assert cur.fetchall() == lots_before
            cur.execute("SELECT id::text FROM data_imports WHERE source_dataset=%s AND active", (SOURCE_DATASET,))
            assert cur.fetchone()[0] == active["import_id"]
        conn.commit()


def test_activation_failure_restores_previous_active_import_and_all_rows(isolated_connection, tmp_path):
    conn = isolated_connection
    with conn.cursor() as cur:
        cur.execute("SELECT id,active,loaded_rows FROM data_imports ORDER BY id")
        imports_before = cur.fetchall()
        cur.execute("SELECT id,import_id,source_record_key FROM parking_lots ORDER BY id")
        lots_before = cur.fetchall()
    conn.commit()
    # Fixture DDL only: the actual loader still runs entirely as parking_loader.
    with psycopg.connect(os.environ["PARKING_LOTS_TEST_DSN"], user="parking_migrator") as owner:
        assert owner.execute("SELECT current_database(),current_user,current_setting('verification.identity',true)").fetchone() == (
            "parking_lots_verify", "parking_migrator", os.environ["PARKING_LOTS_TEST_IDENTITY"])
        owner.execute("""CREATE FUNCTION reject_test_activation() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN IF current_setting('verification.reject_activation',true)='on' AND NEW.active THEN
                RAISE EXCEPTION 'test activation rejected'; END IF; RETURN NEW; END $$""")
        owner.execute("CREATE TRIGGER reject_test_activation BEFORE UPDATE ON data_imports FOR EACH ROW EXECUTE FUNCTION reject_test_activation()")
    path = tmp_path / "activation-failure.csv"
    path.write_bytes(csv_bytes([row(주차장명="must roll back at activation")]))
    try:
        conn.execute("SET verification.reject_activation='on'")
        conn.commit()
        with pytest.raises(psycopg.errors.RaiseException, match="test activation rejected"):
            load(conn, path)
        with conn.cursor() as cur:
            cur.execute("SELECT id,active,loaded_rows FROM data_imports ORDER BY id")
            assert cur.fetchall() == imports_before
            cur.execute("SELECT id,import_id,source_record_key FROM parking_lots ORDER BY id")
            assert cur.fetchall() == lots_before
        conn.commit()
    finally:
        conn.rollback()
        conn.execute("RESET verification.reject_activation")
        conn.commit()
        with psycopg.connect(os.environ["PARKING_LOTS_TEST_DSN"], user="parking_migrator") as owner:
            owner.execute("DROP TRIGGER reject_test_activation ON data_imports; DROP FUNCTION reject_test_activation()")


def test_real_csv_twice(isolated_connection):
    path = Path(__file__).resolve().parents[2] / "data/raw/경기도_용인시_주차장정보.csv"
    if not path.exists():
        pytest.skip("local untracked source CSV is absent")
    conn = isolated_connection
    first = load(conn, path)
    with conn.cursor() as cur:
        cur.execute("SELECT id,source_record_key FROM parking_lots WHERE import_id=%s ORDER BY source_record_key", (first["import_id"],))
        before = cur.fetchall()
        cur.execute("SELECT count(*) FROM (SELECT split_part(source_record_key,'|',1) FROM parking_lots WHERE import_id=%s GROUP BY 1 HAVING count(*)=2) d", (first["import_id"],))
        # One of the seven source-number pairs loses its coordinate-less member.
        assert cur.fetchone()[0] == 6
        cur.execute("SELECT count(*) FROM parking_lots WHERE import_id=%s AND weekday_open='00:00' AND weekday_close='23:59' AND saturday_open='00:00' AND saturday_close='23:59' AND holiday_open='00:00' AND holiday_close='23:59'", (first["import_id"],))
        assert cur.fetchone()[0] == 212
        cur.execute("SELECT count(*) FROM data_imports")
        imports = cur.fetchone()[0]
    conn.commit()
    second = load(conn, path)
    assert (first["total"], first["loaded"], first["skipped"]) == (214, 212, 2)
    assert second["reused"] and first["import_id"] == second["import_id"]
    with conn.cursor() as cur:
        cur.execute("SELECT id,source_record_key FROM parking_lots WHERE import_id=%s ORDER BY source_record_key", (first["import_id"],))
        assert cur.fetchall() == before
        cur.execute("SELECT count(*) FROM data_imports")
        assert cur.fetchone()[0] == imports
    conn.commit()
    print(f"Actual CSV: total={first['total']}, loaded={first['loaded']}, skipped={first['skipped']}; repeated import and IDs unchanged")
