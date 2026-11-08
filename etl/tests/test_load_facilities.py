"""시설 적재 변환 규칙 테스트 (DB 없이)"""
import sys
from datetime import date
from pathlib import Path

import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from load_facilities import bus_rows, hydrant_rows, school_rows, to_date, unique_keys  # noqa: E402


def test_bus_keeps_only_yongin_and_reads_date():
    raw = pd.DataFrame({"정류장번호": ["A1", "B1"], "정류장명": ["골드프라자", "서울역"],
                        "위도": ["37.278", "37.55"], "경도": ["127.154", "126.97"],
                        "정보수집일": ["2026-10-20", "2026-10-20"], "모바일단축번호": ["1", "2"],
                        "도시코드": ["31190", "11000"], "도시명": ["경기도 용인시", "서울특별시"], "관리도시명": ["경기BIS", "서울"]})
    rows, rep = bus_rows(raw)
    assert list(rows["source_record_key"]) == ["A1"]
    assert rows["source_date"].iloc[0] == date(2026, 10, 20)
    assert rep == {"zone_type": "BUS_STOP", "rows_in_yongin": 1, "bad_coordinate": 0, "loaded": 1}


def test_hydrant_drops_missing_coordinates_and_dedupes_keys():
    raw = pd.DataFrame({"시설번호": ["H1", "H1", "H2"], "시설유형코드": ["1", "1", "6"], "시군구명": ["용인시"] * 3,
                        "위도": ["37.2", "37.21", None], "경도": ["127.1", "127.11", None],
                        "상세위치": ["앞", "뒤", "옆"], "데이터기준일자": ["20240207"] * 3})
    rows, rep = hydrant_rows(raw)
    assert list(rows["source_record_key"]) == ["H1", "H1#2"]      # 원본 번호 중복 → #2
    assert rep["bad_coordinate"] == 1                            # 좌표 없는 H2 제외


def test_school_key_and_helpers():
    raw = pd.DataFrame({"시설종류": ["초등학교"], "대상시설명": ["용인초"], "소재지지번주소": ["김량장동 1"],
                        "위도": ["37.23"], "경도": ["127.20"], "데이터기준일자": ["2026-09-18"]})
    rows, _ = school_rows(raw)
    assert rows["source_record_key"].iloc[0] == "초등학교|용인초|김량장동 1"
    assert to_date("20240207") == date(2024, 2, 7) and to_date(None) is None
    assert list(unique_keys(pd.Series(["a", "a", "a"]))) == ["a", "a#2", "a#3"]
