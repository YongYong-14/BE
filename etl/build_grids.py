"""3단계: 용인시 경계와 겹치는 50 m 격자 → spatial_grids

사용법:
    python etl/build_grids.py --shp data/raw/BND_SIGUNGU_PG             # 파일만 만들어 확인
    python etl/build_grids.py --shp data/raw/BND_SIGUNGU_PG --load      # DB에도 적재

규칙 (docs/GRID_AND_TIME_RULES.md):
    EPSG:5186, grid_x = floor(x / 50), grid_y = floor(y / 50)
    용인 경계와 면이 조금이라도 겹치는 칸 = in_model_area true. 경계 밖 칸은 만들지 않는다.
    boundary 컬럼은 표시용이라 4326으로 바꿔 저장한다.

이미 있는 칸은 건너뛴다(유니크 키). 여러 번 실행해도 같은 결과.
"""
from __future__ import annotations

import argparse
import math
from pathlib import Path

import geopandas as gpd
import numpy as np
import shapely

SRID = 5186
CELL = 50
YONGIN = "용인시"


def load_boundary(shp: Path):
    path = next(shp.glob("*.shp")) if shp.is_dir() else shp
    gdf = gpd.read_file(path)
    yongin = gdf[gdf["SIGUNGU_NM"].astype(str).str.startswith(YONGIN)]
    if len(yongin) != 3:
        raise SystemExit(f"용인시 구가 3개가 아니에요: {yongin['SIGUNGU_NM'].tolist()}")
    return yongin.to_crs(SRID).geometry.union_all()


def build_cells(boundary) -> gpd.GeoDataFrame:
    minx, miny, maxx, maxy = boundary.bounds
    xs = np.arange(math.floor(minx / CELL), math.floor(maxx / CELL) + 1)
    ys = np.arange(math.floor(miny / CELL), math.floor(maxy / CELL) + 1)
    gx, gy = (a.ravel() for a in np.meshgrid(xs, ys))
    boxes = shapely.box(gx * CELL, gy * CELL, (gx + 1) * CELL, (gy + 1) * CELL)

    shapely.prepare(boundary)
    # 1) 경계 안에 완전히 들어가는 칸: 바로 포함 (빠름)
    inside = shapely.contains_properly(boundary, boxes)
    # 2) 경계선에 걸친 칸만 따로: 면이 실제로 겹치면 포함, 변·꼭짓점만 닿으면 제외
    edge_idx = np.flatnonzero(~inside & shapely.intersects(boundary, boxes))
    edge_keep = ~shapely.touches(boundary, boxes[edge_idx])
    keep = inside.copy()
    keep[edge_idx[edge_keep]] = True
    cells = gpd.GeoDataFrame(
        {"grid_x": gx[keep].astype(int), "grid_y": gy[keep].astype(int)},
        geometry=boxes[keep], crs=SRID,
    )
    cells["projected_srid"] = SRID
    cells["cell_size_m"] = CELL
    cells["in_model_area"] = True
    return cells.to_crs(4326)


def load_to_db(cells: gpd.GeoDataFrame) -> None:
    from db import connect

    with connect() as conn, conn.cursor() as cur:
        cur.execute("CREATE TEMP TABLE tmp_grids (grid_x int, grid_y int, wkt text) ON COMMIT DROP")
        with cur.copy("COPY tmp_grids (grid_x, grid_y, wkt) FROM STDIN") as cp:
            for x, y, geom in zip(cells["grid_x"], cells["grid_y"], cells.geometry):
                cp.write_row((int(x), int(y), geom.wkt))
        cur.execute(
            """
            INSERT INTO spatial_grids (projected_srid, cell_size_m, grid_x, grid_y, in_model_area, boundary)
            SELECT %s, %s, grid_x, grid_y, true, ST_GeomFromText(wkt, 4326)
            FROM tmp_grids
            ON CONFLICT (projected_srid, cell_size_m, grid_x, grid_y) DO NOTHING
            """,
            (SRID, CELL),
        )
        inserted = cur.rowcount
        cur.execute("SELECT count(*) FROM spatial_grids WHERE projected_srid = %s AND cell_size_m = %s", (SRID, CELL))
        total = cur.fetchone()[0]
    print(f"DB 적재: 새로 {inserted:,}칸 / spatial_grids 전체 {total:,}칸")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--shp", required=True, type=Path)
    ap.add_argument("--load", action="store_true", help="DB에 적재")
    args = ap.parse_args()

    boundary = load_boundary(args.shp)
    print(f"용인 경계 면적 {boundary.area / 1e6:.1f}㎢")
    cells = build_cells(boundary)
    print(f"격자 {len(cells):,}칸 (50 m × 50 m), grid_x {cells.grid_x.min()}~{cells.grid_x.max()}, "
          f"grid_y {cells.grid_y.min()}~{cells.grid_y.max()}")

    # 확인용: 수지구청 부근 칸이 들어 있어야 한다 (GRID_AND_TIME_RULES.md 예시)
    has_example = ((cells.grid_x == 4173) & (cells.grid_y == 10495)).any()
    print("예시 칸 (4173, 10495) 포함:", bool(has_example))

    if args.load:
        load_to_db(cells)


if __name__ == "__main__":
    main()
