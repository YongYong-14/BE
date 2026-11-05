"""경계 SHP 안에 용인시가 어떻게 들어 있는지 확인만 한다 (파일을 만들지 않음).

사용법:
    python etl/inspect_boundary.py --shp data/raw/BND_SIGUNGU_PG

결과 글자를 그대로 복사해서 공유하면 된다 (개인정보 없음).
"""
from __future__ import annotations

import argparse
from pathlib import Path

import geopandas as gpd
import pandas as pd


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--shp", required=True, type=Path, help="SHP 폴더 또는 .shp 파일")
    args = ap.parse_args()

    path = args.shp
    if path.is_dir():
        path = next(path.glob("*.shp"))

    gdf = gpd.read_file(path)
    print("파일:", path.name)
    print("행 수:", len(gdf))
    print("좌표계:", gdf.crs)
    print("칸 이름:", [c for c in gdf.columns if c != "geometry"])

    mask = pd.Series(False, index=gdf.index)
    for c in gdf.columns:
        if c != "geometry":
            mask |= gdf[c].astype(str).str.contains("용인|처인|기흥|수지", na=False)
    yongin = gdf[mask]
    print("\n용인 관련 행:", len(yongin))
    print(yongin.drop(columns="geometry").to_string())

    if len(yongin):
        merged = yongin.to_crs(5186).dissolve()
        print("\n합친 면적(㎢):", round(float(merged.area.iloc[0]) / 1e6, 1))
        print("경계 범위(EPSG:5186):", [round(v) for v in merged.total_bounds])
        print("유효한 도형:", bool(merged.is_valid.iloc[0]))


if __name__ == "__main__":
    main()
