"""실제 원본에서 나온 표기들로 만든 테스트. 실행: pytest etl/tests"""
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from enforcement_parser import classify_violation, district_of, mask_plate, parse_place  # noqa: E402


@pytest.mark.parametrize("raw, district, key, rest", [
    ("풍덕천동 786 횡단보도침범신고", "수지구", "수지구 풍덕천동 786", "횡단보도침범신고"),
    (" 풍덕천동 795 횡단보도침범신고", "수지구", "수지구 풍덕천동 795", "횡단보도침범신고"),
    ("처인구 삼가동 558-4 횡단보도", "처인구", "처인구 삼가동 558-4", "횡단보도"),
    ("모현읍 왕산리 459-7 횡단보도", "처인구", "처인구 모현읍 왕산리 459-7", "횡단보도"),
    (" 경기도 용인시 수지구 신봉동 1012  인도 침범 신고", "수지구", "수지구 신봉동 1012", "인도 침범 신고"),
    ("동천동 905인근 횡단보도 침범신고", "수지구", "수지구 동천동 905", "인근 횡단보도 침범신고"),
    ("중동 832-5 모퉁이", "기흥구", "기흥구 중동 832-5", "모퉁이"),
    ("상현동 1168-6 소화전 5m신고", "수지구", "수지구 상현동 1168-6", "소화전 5m신고"),
])
def test_parcel_parsed(raw, district, key, rest):
    p = parse_place(raw, district)
    assert p.parse_status == "PARSED"
    assert p.parcel_key == key
    assert p.rest == rest


def test_admin_dong_becomes_legal_dong():
    p = parse_place("풍덕천1동 123 횡단보도", "수지구")
    assert p.parcel_key == "수지구 풍덕천동 123"
    assert p.normalization_method == "RULE"


def test_mountain_parcel():
    assert parse_place("원삼면 사암리 산 12-3", "처인구").parcel_key == "처인구 원삼면 사암리 산 12-3"


@pytest.mark.parametrize("raw, status", [
    ("탑실로", "ROAD_ONLY"),
    ("영덕동 중부대로55번길", "ROAD_ONLY"),
    (" 547-1 횡단보도 신고", "FAILED"),
    ("신명아파트 사거리", "FAILED"),
])
def test_not_parsed(raw, status):
    p = parse_place(raw, "기흥구")
    assert p.parse_status == status
    assert p.parcel_key is None


@pytest.mark.parametrize("text, expected, multi", [
    ("횡단보도침범신고", "CROSSWALK", False),    # '보도'가 인도로 잡히지 않아야 함
    ("소화전 5m신고", "HYDRANT", False),
    ("횡단보도 및 모퉁이", "CROSSWALK", True),
    ("교차로 모투이", "INTERSECTION", False),   # 원본 오타
    ("버정류장", "BUS_STOP", False),
    ("인도(보도)신고", "SIDEWALK", False),
    ("어린이보호구역위반신고", "SCHOOL_ZONE", False),
    ("안전지대", "OTHER", False),
])
def test_violation_citizen(text, expected, multi):
    assert classify_violation(text, True) == (expected, multi)


def test_violation_cctv_without_keyword_is_none():
    assert classify_violation("탑실로", False) == (None, False)


def test_plate_is_masked():
    raw = "013179\t2025-01-20 18:50:00\t89저1446\t처인구 역북동 419 교차로모퉁이"
    assert "89저1446" not in mask_plate(raw)
    assert mask_plate("풍덕천동 786 횡단보도") == "풍덕천동 786 횡단보도"   # 지번은 건드리지 않음


def test_district():
    assert district_of("용인시 수지구") == "수지구"
