"""LightGBM 피처·채점 규칙 테스트 (DB 없이 작은 가짜 데이터로)"""
import sys
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from train_lgbm import build_features, score  # noqa: E402


def tiny():
    targets = pd.DataFrame({"grid_id": [1, 2, 3], "grid_x": [0, 1, 5], "grid_y": [0, 0, 0]})
    # 칸 1: 토요일(2025-01-04) 19시에 2건, 칸 2: 월요일 9시 1건
    ev = pd.DataFrame({"grid_id": [1, 1, 2],
                       "date": [date(2025, 1, 4), date(2025, 1, 4), date(2025, 1, 6)],
                       "weekday": [6, 6, 1], "time_slot": [6, 6, 3]})
    return targets, ev


def test_features_use_only_history_and_rates_per_day():
    targets, ev = tiny()
    f = build_features(targets, ev, (date(2025, 1, 1), date(2025, 1, 14)), holidays={date(2025, 1, 1)})
    assert len(f) == 3 * 56                                  # 칸 × 56 조합 전부
    sat = f[(f.grid_id == 1) & (f.weekday == 6) & (f.time_slot == 6)].iloc[0]
    assert sat.rate_ws == 2 / 2                              # 1/1~1/14 토요일 2일 → 하루 1건
    assert sat.dist_reported_m == 0                          # 자기 칸에 신고 있음
    far = f[(f.grid_id == 3)].iloc[0]
    assert far.dist_reported_m == 4 * 50                     # 가장 가까운 신고 칸(2)까지 4칸
    assert f[f.grid_id == 2].nb_ring1.iloc[0] > 0            # 옆 칸(1)의 신고가 이웃 피처로 들어옴


def test_score_compares_same_number_of_cells():
    pred = pd.DataFrame({"weekday": [6] * 20, "time_slot": [6] * 20, "rate": np.arange(20, 0, -1, dtype=float)})
    y = pd.Series([5] + [0] * 19)                            # 신고는 1등 칸에만
    m = score(pred, "rate", y, pd.Series([1.0] * 20))
    assert m["capture_top5"] == 1.0                          # 상위 5% = 1칸이 전부 잡음
    assert m["capture_top10"] == 1.0


def test_score_counts_reports_outside_targets_as_missed():
    pred = pd.DataFrame({"weekday": [6] * 20, "time_slot": [6] * 20, "rate": np.arange(20, 0, -1, dtype=float)})
    y = pd.Series([5] + [0] * 19)
    m = score(pred, "rate", y, pd.Series([1.0] * 20), total=10)   # 대상 칸 밖에서 5건 더 나옴
    assert m["capture_top5"] == 0.5
    assert m["reports_in_targets_share"] == 0.5


def test_load_refuses_when_source_data_changed():
    import pytest
    from train_lgbm import check_same_data
    now = {"source_import_id": "a", "citizen_reports": 10, "citizen_reports_md5": "x",
           "parcel_stats": 3, "parcel_stats_md5": "y"}
    check_same_data(dict(now), now)                                   # 같으면 통과
    with pytest.raises(SystemExit):
        check_same_data({**now, "citizen_reports": 11}, now)          # 재적재 등으로 바뀌면 거부
    with pytest.raises(SystemExit):
        check_same_data(None, now)                                    # 지문 없는 옛 파일도 거부
