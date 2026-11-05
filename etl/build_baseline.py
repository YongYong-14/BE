"""5단계: 베이스라인 예측표 (격자 과거 평균) → 동결 → (선택) 활성화

사용법:
    python etl/build_baseline.py --version baseline-yongin-v1                 # 적재 + 동결
    python etl/build_baseline.py --version baseline-yongin-v1 --activate      # + 서비스에 사용
    python etl/build_baseline.py --version test --dry-run                     # 계산·지표만, DB에 안 씀

베이스라인 정의 (prediction_versions 한 행으로 기록):
    rate(칸, 요일, 시간대) = 시민 신고 건수 / 그 요일의 관측 일수        (하루 평균 기대 건수 λ)
    smoothed = 주변 100 m 안 칸들의 rate 가중 평균 (가우시안, σ = 50 m)  (지번 대표점 쏠림 완화)
    top_percent = 같은 요일·시간대 대상 칸 중 smoothed가 자신 이상인 칸의 비율 × 100
    2단계 = top_percent ≤ caution_top_percent(10)

대상 칸 = 시민 신고 지번에서 200 m 안에 있는 용인 격자 (명세 #12 임시값)
평가 = 1~7월로 만든 표가 8/1~9/25 실제 신고를 얼마나 맞히는지 (시간 순 분할)
"""
from __future__ import annotations

import argparse
import json
import math
import uuid
from datetime import date, timedelta

import numpy as np
import pandas as pd

from db import connect
from reference_data import OBS_END, OBS_START, PERIODS, SOURCE_DATASET

CELL = 50
TARGET_RADIUS_M = 200
SMOOTH_RADIUS_M = 100
SMOOTH_SIGMA_M = 50
CAUTION_TOP_PERCENT = 10.0
SLOTS = [(w, s) for w in range(1, 8) for s in range(8)]   # 56 조합


# ─── 대상 칸 ─────────────────────────────────────────────
def candidate_cells(points: np.ndarray, radius: float) -> set[tuple[int, int]]:
    """점(5186 좌표)에서 radius 안에 걸치는 50 m 칸 번호 집합"""
    k = math.ceil(radius / CELL) + 1
    out: set[tuple[int, int]] = set()
    for x, y in points:
        cx, cy = math.floor(x / CELL), math.floor(y / CELL)
        for gx in range(cx - k, cx + k + 1):
            dx = max(gx * CELL - x, 0.0, x - (gx + 1) * CELL)
            for gy in range(cy - k, cy + k + 1):
                dy = max(gy * CELL - y, 0.0, y - (gy + 1) * CELL)
                if dx * dx + dy * dy <= radius * radius:
                    out.add((gx, gy))
    return out


def smoothing_offsets() -> list[tuple[int, int, float]]:
    k = SMOOTH_RADIUS_M // CELL
    offs = []
    for dx in range(-k, k + 1):
        for dy in range(-k, k + 1):
            d = math.hypot(dx, dy) * CELL
            if d <= SMOOTH_RADIUS_M:
                offs.append((dx, dy, math.exp(-(d * d) / (2 * SMOOTH_SIGMA_M ** 2))))
    return offs


# ─── 일수 (공휴일 → 일요일 7) ─────────────────────────────
def day_counts(start: date, end: date, holidays: set[date]) -> dict[int, int]:
    counts = {w: 0 for w in range(1, 8)}
    d = start
    while d <= end:
        counts[7 if d in holidays else d.isoweekday()] += 1
        d += timedelta(days=1)
    return counts


# ─── 예측 ────────────────────────────────────────────────
def baseline_table(targets: pd.DataFrame, agg: pd.DataFrame, days: dict[int, int]) -> pd.DataFrame:
    """targets: grid_id, grid_x, grid_y / agg: grid_id, weekday, time_slot, report_count
    → 대상 칸 × 56 조합 전부 (expected_count, smoothed_count, top_percent)"""
    full = targets[["grid_id", "grid_x", "grid_y"]].merge(
        pd.DataFrame(SLOTS, columns=["weekday", "time_slot"]), how="cross")
    a = agg[["grid_id", "weekday", "time_slot", "report_count"]]
    full = full.merge(a, on=["grid_id", "weekday", "time_slot"], how="left").fillna({"report_count": 0})
    full["expected_count"] = full["report_count"] / full["weekday"].map(days)

    # 주변 칸 가중 평균: 칸 번호를 옮겨 붙이는 방식 (대상 밖 칸은 0으로 본다)
    key = full.set_index(["grid_x", "grid_y", "weekday", "time_slot"])["expected_count"]
    num = np.zeros(len(full))
    den = 0.0
    for dx, dy, w in smoothing_offsets():
        idx = pd.MultiIndex.from_arrays([full["grid_x"] + dx, full["grid_y"] + dy, full["weekday"], full["time_slot"]])
        num += w * key.reindex(idx).fillna(0).to_numpy()
        den += w
    full["smoothed_count"] = num / den

    # top_percent (GE_SHARE): 같은 요일·시간대에서 값이 자신 이상인 칸 수 / 전체 칸 수 × 100
    n = full.groupby(["weekday", "time_slot"])["smoothed_count"].transform("size")
    ge = full.groupby(["weekday", "time_slot"])["smoothed_count"].rank(method="max", ascending=False)
    full["top_percent"] = 100.0 * ge / n
    return full


def evaluate(targets: pd.DataFrame, agg_train: pd.DataFrame, agg_valid: pd.DataFrame,
             days_train: dict[int, int], days_valid: dict[int, int]) -> dict:
    """1~7월 표로 8/1~9/25를 맞히는 정도 (시간 순 분할)"""
    pred = baseline_table(targets, agg_train, days_train)
    v = agg_valid[["grid_id", "weekday", "time_slot", "report_count"]].rename(columns={"report_count": "y"})
    m = pred.merge(v, on=["grid_id", "weekday", "time_slot"], how="left").fillna({"y": 0})
    m["mu"] = m["smoothed_count"] * m["weekday"].map(days_valid)

    total = m["y"].sum()
    flagged = m["top_percent"] <= CAUTION_TOP_PERCENT
    # 비교 기준(아무 정보 없음): 같은 요일·시간대 평균을 모든 칸에 똑같이
    m["mu0"] = m.groupby(["weekday", "time_slot"])["y"].transform("mean")

    def deviance(y, mu):
        mu = np.maximum(mu, 1e-6)
        with np.errstate(divide="ignore", invalid="ignore"):
            t = np.where(y > 0, y * np.log(y / mu), 0.0)
        return float(2 * np.sum(t - (y - mu)))

    d_model, d_null = deviance(m["y"].to_numpy(), m["mu"].to_numpy()), deviance(m["y"].to_numpy(), m["mu0"].to_numpy())
    return {
        "split": {"train": [str(PERIODS["train"][0]), str(PERIODS["train"][1])],
                  "valid": [str(PERIODS["valid"][0]), str(PERIODS["valid"][1])]},
        "valid_reports_in_targets": int(total),
        "flagged_cell_share": round(float(flagged.mean()), 4),
        "capture_at_caution": round(float(m.loc[flagged, "y"].sum() / total), 4) if total else None,
        "poisson_deviance": round(d_model, 1),
        "poisson_deviance_null": round(d_null, 1),
        "deviance_improvement_vs_null": round(1 - d_model / d_null, 4) if d_null else None,
    }


# ─── DB ──────────────────────────────────────────────────
def fetch_inputs(cur):
    cur.execute("SELECT id FROM data_imports WHERE source_dataset = %s AND active", (SOURCE_DATASET,))
    row = cur.fetchone()
    if not row:
        raise SystemExit("활성화된 단속 원천이 없어요. load_enforcement.py를 먼저 실행하세요.")
    imp = row[0]

    cur.execute("""SELECT ST_X(p), ST_Y(p) FROM (SELECT ST_Transform(location::geometry, 5186) AS p
                     FROM parcel_report_stats WHERE import_id = %s AND period_start = %s AND period_end = %s) t""",
                (imp, OBS_START, OBS_END))
    points = np.array(cur.fetchall(), dtype=float)

    cells = candidate_cells(points, TARGET_RADIUS_M)
    cur.execute("CREATE TEMP TABLE tmp_cells (grid_x int, grid_y int) ON COMMIT DROP")
    with cur.copy("COPY tmp_cells FROM STDIN") as cp:
        for gx, gy in cells:
            cp.write_row((gx, gy))
    cur.execute("""SELECT g.id, g.grid_x, g.grid_y FROM tmp_cells t
                     JOIN spatial_grids g ON g.projected_srid = 5186 AND g.cell_size_m = %s
                      AND g.grid_x = t.grid_x AND g.grid_y = t.grid_y AND g.in_model_area""", (CELL,))
    targets = pd.DataFrame(cur.fetchall(), columns=["grid_id", "grid_x", "grid_y"])

    cur.execute("""SELECT grid_id, weekday, time_slot, period_start, period_end, report_count
                     FROM report_aggregates WHERE import_id = %s""", (imp,))
    agg = pd.DataFrame(cur.fetchall(), columns=["grid_id", "weekday", "time_slot", "ps", "pe", "report_count"])

    cur.execute("SELECT holiday_date FROM holidays")
    holidays = {r[0] for r in cur.fetchall()}
    return imp, len(points), targets, agg, holidays


def write_version(cur, imp, args, targets: pd.DataFrame, table: pd.DataFrame, metrics: dict) -> uuid.UUID:
    vid = uuid.uuid4()
    cur.execute(
        """INSERT INTO prediction_versions
               (id, model_type, model_version, training_start, training_end, target_definition, source_import_id,
                observed_end, holiday_policy, projected_srid, cell_size_m, smoothing_radius_m, percentile_basis,
                tie_method, caution_top_percent, target_cell_count, metrics, description)
           VALUES (%s, 'BASELINE_MEAN', %s, %s, %s, 'ROW_COUNT', %s, %s, 'AS_SUNDAY', 5186, %s, %s, 'SAME_SLOT',
                   'GE_SHARE', %s, %s, %s, %s)""",
        (vid, args.version, OBS_START, OBS_END, imp, OBS_END, CELL, SMOOTH_RADIUS_M, CAUTION_TOP_PERCENT,
         len(targets), json.dumps(metrics, ensure_ascii=False),
         f"격자 과거 평균 + 가우시안 평활(σ={SMOOTH_SIGMA_M}m). 대상 = 신고 지번 {TARGET_RADIUS_M}m 이내"))
    with cur.copy("COPY prediction_target_grids (version_id, grid_id) FROM STDIN") as cp:
        for g in targets["grid_id"]:
            cp.write_row((vid, int(g)))
    cols = ["grid_id", "weekday", "time_slot", "expected_count", "smoothed_count", "top_percent"]
    with cur.copy(f"COPY risk_predictions (version_id, {', '.join(cols)}) FROM STDIN") as cp:
        for r in table[cols].itertuples(index=False):
            cp.write_row((vid, int(r[0]), int(r[1]), int(r[2]), float(r[3]), float(r[4]), float(r[5])))
    return vid


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--version", required=True, help="model_version 이름 (예: baseline-yongin-v1)")
    ap.add_argument("--activate", action="store_true")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    with connect() as conn, conn.cursor() as cur:
        imp, n_points, targets, agg, holidays = fetch_inputs(cur)
        print(f"신고 지번 {n_points:,}개 → 대상 칸 {len(targets):,}개 (× 56 = {len(targets) * 56:,}행)")

        def period(name):
            ps, pe = PERIODS[name]
            return agg[(agg["ps"] == ps) & (agg["pe"] == pe)], day_counts(ps, pe, holidays)

        (a_full, d_full), (a_tr, d_tr), (a_va, d_va) = period("full"), period("train"), period("valid")
        metrics = evaluate(targets, a_tr, a_va, d_tr, d_va)
        metrics["days_full"] = d_full
        print("평가(1~7월 → 8/1~9/25):", json.dumps(metrics, ensure_ascii=False))

        table = baseline_table(targets, a_full, d_full)
        lvl2 = table["top_percent"] <= CAUTION_TOP_PERCENT
        print(f"2단계 칸 비율 {lvl2.mean():.1%} (요일·시간대 평균)")
        if args.dry_run:
            print("dry-run: DB에 쓰지 않음")
            return

        vid = write_version(cur, imp, args, targets, table, metrics)
        cur.execute("SELECT freeze_prediction_version(%s)", (vid,))
        if args.activate:
            cur.execute("SELECT activate_prediction_version(%s)", (vid,))
        conn.commit()
        cur.execute("SELECT model_version, target_cell_count, loaded_row_count, frozen_at IS NOT NULL, active "
                    "FROM prediction_versions WHERE id = %s", (vid,))
        print("버전:", cur.fetchone())
        print("version_id:", vid)


if __name__ == "__main__":
    main()
