"""카카오 응답 판정 테스트 (네트워크 없이 가짜 응답으로)"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from geocode_parcels import classify, load_env  # noqa: E402


def doc(address_type, x="127.0968", y="37.3221", name="경기 용인시 수지구 풍덕천동 786"):
    return {"address_type": address_type, "x": x, "y": y, "address_name": name}


def test_no_result_is_failed():
    r = classify({"documents": []})
    assert r["status"] == "FAILED" and r["lat"] is None


def test_exact_parcel():
    r = classify({"documents": [doc("REGION_ADDR")]})
    assert r["status"] == "SUCCEEDED" and r["match_level"] == "PARCEL"
    assert (r["lat"], r["lng"]) == (37.3221, 127.0968)   # y=위도, x=경도


def test_multiple_parcels_is_ambiguous():
    r = classify({"documents": [doc("REGION_ADDR"), doc("REGION_ADDR", x="127.1")]})
    assert r["status"] == "AMBIGUOUS" and r["candidate_count"] == 2


def test_dong_only_is_admin():
    r = classify({"documents": [doc("REGION")]})
    assert r["match_level"] == "ADMIN"


def test_load_env_strips_quotes(tmp_path):
    p = tmp_path / ".env"
    p.write_text("# c\nKAKAO_REST_API_KEY='abc'\n", encoding="utf-8")
    assert load_env(p)["KAKAO_REST_API_KEY"] == "abc"
