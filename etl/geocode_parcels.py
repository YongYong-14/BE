"""2단계: 고유 지번 → 좌표 (카카오 로컬 주소 검색)

사용법:
    python etl/geocode_parcels.py --parcels etl/out/parcels.csv --out etl/out/geocode_cache.csv
    python etl/geocode_parcels.py ... --only 풍덕천동      # 일부만 먼저 해볼 때
    python etl/geocode_parcels.py ... --limit 5           # 5개만 시험

- 카카오 키는 etl/.env 의 KAKAO_REST_API_KEY 에서 읽는다 (화면에 출력하지 않음).
- 이미 결과가 있는 지번은 건너뛴다. 중간에 끊겨도 같은 명령을 다시 실행하면 이어서 한다.
- 시민 신고가 있는 지번만 변환한다 (citizen_rows > 0).
"""
from __future__ import annotations

import argparse
import csv
import json
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

import pandas as pd

KAKAO_URL = "https://dapi.kakao.com/v2/local/search/address.json"
PROVIDER = "kakao_local_address"
CACHE_COLUMNS = [
    "parcel_key", "normalized_address", "status", "match_level", "lat", "lng",
    "matched_address", "candidate_count", "failure_reason", "provider", "geocoded_at",
]

# 카카오 address_type → ERD geocoded_parcels.match_level
MATCH_LEVEL = {"REGION_ADDR": "PARCEL", "ROAD_ADDR": "ROAD", "REGION": "ADMIN", "ROAD": "ROAD"}


def load_env(path: Path) -> dict[str, str]:
    env: dict[str, str] = {}
    if path.exists():
        for line in path.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            env[k.strip()] = v.strip().strip("'\"")
    return env


def classify(response: dict) -> dict:
    """카카오 응답 한 건 → 상태·좌표. (네트워크와 분리해서 테스트한다)

    - 결과 0개: FAILED
    - 지번 정확 일치(REGION_ADDR) 1개: SUCCEEDED / PARCEL
    - 지번 일치가 여러 개: AMBIGUOUS (첫 번째 좌표는 참고용으로 남김)
    - 동 단위만 일치(REGION): SUCCEEDED / ADMIN → 학습·집계에서 제외 대상
    """
    docs = response.get("documents") or []
    if not docs:
        return {"status": "FAILED", "match_level": None, "lat": None, "lng": None,
                "matched_address": None, "candidate_count": 0, "failure_reason": "NO_RESULT"}

    parcels = [d for d in docs if d.get("address_type") == "REGION_ADDR"]
    pick = parcels[0] if parcels else docs[0]
    status = "AMBIGUOUS" if len(parcels) > 1 else "SUCCEEDED"
    return {
        "status": status,
        "match_level": MATCH_LEVEL.get(pick.get("address_type"), "ADMIN"),
        "lat": float(pick["y"]),   # 카카오: y = 위도
        "lng": float(pick["x"]),   #         x = 경도
        "matched_address": pick.get("address_name"),
        "candidate_count": len(docs),
        "failure_reason": "MULTIPLE_PARCELS" if status == "AMBIGUOUS" else None,
    }


def call_kakao(address: str, api_key: str, retries: int = 3) -> dict:
    query = urllib.parse.urlencode({"query": address, "analyze_type": "exact"})
    req = urllib.request.Request(f"{KAKAO_URL}?{query}", headers={"Authorization": f"KakaoAK {api_key}"})
    for attempt in range(retries):
        try:
            with urllib.request.urlopen(req, timeout=10) as r:
                return json.loads(r.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            if e.code in (401, 403):
                raise SystemExit(f"카카오 인증 실패({e.code}). etl/.env의 KAKAO_REST_API_KEY와 카카오맵 API 활성화를 확인하세요.")
            if e.code == 429 or e.code >= 500:
                time.sleep(2 ** attempt)
                continue
            raise
        except urllib.error.URLError:
            time.sleep(2 ** attempt)
    raise RuntimeError("카카오 요청 반복 실패")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--parcels", required=True, type=Path)
    ap.add_argument("--out", required=True, type=Path)
    ap.add_argument("--env", default=Path("etl/.env"), type=Path)
    ap.add_argument("--only", help="지번에 이 글자가 들어간 것만 (예: 풍덕천동)")
    ap.add_argument("--limit", type=int)
    ap.add_argument("--sleep", type=float, default=0.12, help="요청 사이 대기(초)")
    args = ap.parse_args()

    api_key = load_env(args.env).get("KAKAO_REST_API_KEY")
    if not api_key:
        raise SystemExit("etl/.env에 KAKAO_REST_API_KEY가 없어요.")

    parcels = pd.read_csv(args.parcels)
    parcels = parcels[parcels["citizen_rows"] > 0]
    if args.only:
        parcels = parcels[parcels["parcel_key"].str.contains(args.only)]

    done: set[str] = set()
    if args.out.exists():
        done = set(pd.read_csv(args.out)["parcel_key"])
    todo = parcels[~parcels["parcel_key"].isin(done)]
    if args.limit:
        todo = todo.head(args.limit)
    print(f"대상 {len(parcels):,}개 / 이미 완료 {len(done & set(parcels['parcel_key'])):,}개 / 이번에 {len(todo):,}개")

    new_file = not args.out.exists()
    counts: dict[str, int] = {}
    with args.out.open("a", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=CACHE_COLUMNS)
        if new_file:
            w.writeheader()
        for i, row in enumerate(todo.itertuples(index=False), 1):
            result = classify(call_kakao(row.normalized_address, api_key))
            w.writerow({"parcel_key": row.parcel_key, "normalized_address": row.normalized_address,
                        "provider": PROVIDER, "geocoded_at": datetime.now(timezone.utc).isoformat(), **result})
            counts[result["status"]] = counts.get(result["status"], 0) + 1
            if i % 100 == 0:
                f.flush()
                print(f"  {i:,}/{len(todo):,}  {counts}")
            time.sleep(args.sleep)

    print("이번 실행 결과:", counts)
    total = pd.read_csv(args.out)
    print("누적 상태:", total["status"].value_counts().to_dict())
    print("누적 정밀도:", total["match_level"].value_counts(dropna=False).to_dict())


if __name__ == "__main__":
    main()
