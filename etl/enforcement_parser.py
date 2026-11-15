"""단속 원본 한 줄을 ERD v5.1 enforcement_records 형태로 바꾸는 규칙.

DB나 파일을 건드리지 않는 순수 함수만 둔다. 그래서 테스트하기 쉽고,
규칙을 바꾸면 PARSER_VERSION을 올린다.
"""
from __future__ import annotations

import re
from dataclasses import dataclass

PARSER_VERSION = "v1"

# 시민 신고 = 안전신문고 + 새올민원 (ERD 설계 기준)
CITIZEN_REPORT_TYPES = {"안전신문고", "새올민원"}

# 위반유형 키워드. 우선순위 순서 그대로 검사한다 (ERD enforcement_records.violation_type 주석).
# 어린이보호구역 > 소화전 > 횡단보도 > 교차로 > 버스정류소 > 인도 > 기타
VIOLATION_RULES: list[tuple[str, re.Pattern]] = [
    ("SCHOOL_ZONE", re.compile(r"어린이|스쿨존")),
    ("HYDRANT", re.compile(r"소화전|소방")),
    ("CROSSWALK", re.compile(r"횡단보도")),
    ("INTERSECTION", re.compile(r"교차로|모퉁이|모투이")),
    ("BUS_STOP", re.compile(r"버스|정류")),
    ("SIDEWALK", re.compile(r"인도|보도")),
]

# 주소 앞부분에 붙어 오는 시·구 이름
_PREFIX = re.compile(r"^(?:경기도\s*)?(?:용인시\s*)?(?:(?:처인|기흥|수지)구\s*)?")

# [읍면] 동/리 [산] 본번[-부번][번지] 나머지
_PARCEL = re.compile(
    r"^(?:(?P<eup>[가-힣]+?[읍면])\s*)?"
    r"(?P<dong>[가-힣]+?\d*(?:동|리))\s*"
    r"(?P<san>산\s*)?"
    r"(?P<main>\d+)(?:\s*-\s*(?P<sub>\d+))?(?:번지)?"
    r"(?P<rest>.*)$"
)

# 행정동 표기(풍덕천1동, 상현2동)를 법정동(풍덕천동)으로. 용인 법정동 이름에는 숫자가 없다.
_ADMIN_DONG = re.compile(r"^([가-힣]+?)\d+동$")

# 지번이 없고 도로명만 있는 경우 (주로 CCTV 단속)
_ROAD = re.compile(r"[가-힣0-9]+(?:로|길)(?:\d+번길)?")

# 차량번호 (예: 89저1446, 123가4567). 원본의 깨진 행에 섞여 들어온 경우가 있어 저장 전에 가린다.
_PLATE = re.compile(r"\d{2,3}\s?[가-힣]\s?\d{4}")


def mask_plate(text: str) -> str:
    return _PLATE.sub("[차량번호]", text or "")


@dataclass(frozen=True)
class ParsedPlace:
    parse_status: str             # PARSED / ROAD_ONLY / FAILED
    parcel_key: str | None        # "수지구 풍덕천동 786"
    normalization_method: str | None  # NONE / RULE (행정동 → 법정동)
    rest: str                     # 주소를 떼고 남은 글자 (위반유형 판단용)


def district_of(sigun_name: str) -> str | None:
    """'용인시 처인구' → '처인구'"""
    m = re.search(r"(처인|기흥|수지)구", sigun_name or "")
    return m.group(0) if m else None


def parse_place(place_raw: str, district: str | None) -> ParsedPlace:
    text = re.sub(r"\s+", " ", (place_raw or "").strip())
    text = _PREFIX.sub("", text).strip()

    m = _PARCEL.match(text)
    if m and district:
        dong = m.group("dong")
        method = "NONE"
        a = _ADMIN_DONG.match(dong)
        if a:
            dong = a.group(1) + "동"
            method = "RULE"
        parts = [district]
        if m.group("eup"):
            parts.append(m.group("eup"))
        parts.append(dong)
        number = m.group("main") + (f"-{m.group('sub')}" if m.group("sub") else "")
        parts.append(("산 " if m.group("san") else "") + number)
        return ParsedPlace("PARSED", " ".join(parts), method, m.group("rest").strip())

    status = "ROAD_ONLY" if _ROAD.search(text) else "FAILED"
    return ParsedPlace(status, None, None, text)


def classify_violation(text: str, is_citizen_report: bool) -> tuple[str | None, bool]:
    """(위반유형, 키워드 2개 이상 일치 여부)

    '횡단보도'의 '보도'가 인도(SIDEWALK)로 잘못 잡히지 않게 먼저 지우고 검사한다.
    아무 키워드도 없으면 시민 신고는 OTHER, CCTV 등은 None(유형 정보 없음).
    """
    hits = []
    for code, pattern in VIOLATION_RULES:
        target = text.replace("횡단보도", "") if code == "SIDEWALK" else text
        if pattern.search(target):
            hits.append(code)
    if hits:
        return hits[0], len(hits) > 1
    return ("OTHER" if is_citizen_report else None), False


def normalized_address(parcel_key: str) -> str:
    """지오코딩에 보낼 전체 주소"""
    return f"경기도 용인시 {parcel_key}"
