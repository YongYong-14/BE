"""격자 생성 규칙 테스트 (DB 없이)"""
import math
import sys
from pathlib import Path

import shapely

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from build_grids import CELL, build_cells  # noqa: E402


def test_cells_cover_boundary_and_follow_floor_rule():
    # 5186 좌표로 120 m × 70 m 사각형 (칸 경계에 걸치게)
    boundary = shapely.box(208660, 524760, 208780, 524830)
    cells = build_cells(boundary)
    xs = set(cells.grid_x)
    ys = set(cells.grid_y)
    assert xs == {math.floor(208660 / CELL), 4174, math.floor(208780 / CELL)}   # 4173~4175
    assert ys == {10495, 10496}
    assert len(cells) == 6
    assert cells.crs.to_epsg() == 4326          # 저장은 표시용 4326
    assert cells.in_model_area.all()


def test_cell_outside_boundary_is_dropped():
    # L자 모양: 오른쪽 위 칸은 경계와 겹치지 않음
    boundary = shapely.Polygon([(0, 0), (100, 0), (100, 50), (50, 50), (50, 100), (0, 100)])
    cells = build_cells(boundary)
    assert (1, 1) not in set(zip(cells.grid_x, cells.grid_y))
