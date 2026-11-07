"""베이스라인 평가 규칙 테스트 (DB 없이)"""
import sys
from pathlib import Path

import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from build_baseline import evaluate  # noqa: E402


def test_reports_outside_eval_targets_count_as_missed():
    # 평가 대상 20칸(서로 멀리 떨어짐) 중 1번 칸만 신고가 있어 상위 칸이 된다.
    # 검증 기간 신고는 1번 칸 3건 + 평가 대상 밖 99번 칸 1건
    targets = pd.DataFrame({"grid_id": range(1, 21), "grid_x": [i * 10 for i in range(20)], "grid_y": [0] * 20})
    agg_train = pd.DataFrame({"grid_id": [1], "weekday": [6], "time_slot": [6], "report_count": [2]})
    agg_valid = pd.DataFrame({"grid_id": [1, 99], "weekday": [6, 6], "time_slot": [6, 6], "report_count": [3, 1]})
    days = {d: 10 for d in range(1, 8)}
    m = evaluate(targets, agg_train, agg_valid, days, days)
    assert m["valid_reports_total"] == 4
    assert m["valid_reports_in_targets"] == 3
    assert m["capture_at_caution"] == 0.75        # 대상 밖 1건은 놓친 것
