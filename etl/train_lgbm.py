"""6단계: LightGBM 시공간 예측 모델 → 베이스라인과 비교 → (더 좋을 때만) 새 버전으로 교체

사용법:
    python etl/train_lgbm.py --version lgbm-yongin-v1 --dry-run          # 학습·비교만 (파일·DB 안 씀)
    python etl/train_lgbm.py --version lgbm-yongin-v1                    # ① 학습 → etl/out/ 에 예측 파일 저장
    python etl/train_lgbm.py --version lgbm-yongin-v1 --load --activate  # ② 파일 → DB 적재·freeze, 더 좋으면 activate

학습과 DB 적재를 나눈 이유: 둘을 한 번에 하면 노트북 메모리가 모자라 멈출 수 있다.
①이 끝나면 파이썬이 메모리를 다 돌려주고, ②는 가벼운 상태에서 저장만 한다.

핵심 아이디어 (시간 순서를 절대 거꾸로 쓰지 않는다):
    "과거 기간의 신고 패턴(피처)"으로 "다음 기간의 신고 건수(정답)"를 맞히도록 학습한다.

      평가용   피처 1/1~5/31  →  정답 6/1~7/31      로 학습
               피처 1/1~7/31  →  8/1~9/25 를 예측해 채점   (베이스라인과 같은 시험지)
      서비스용 위 학습쌍 + (피처 1/1~7/31 → 정답 8/1~9/25) 로 다시 학습
               피처 1/1~9/25 로 앞으로의 위험도를 예측해 예측표로 적재

모델: LightGBM, objective = poisson (건수 예측의 정석), 일수(노출)는 init_score = log(일수)로 넣어
      "하루 평균 기대 건수"를 예측하게 한다. 출력 형식은 베이스라인과 같아서 판정 API 코드는 그대로다.
"""
from __future__ import annotations

import argparse
import json
import math
import time
import uuid
from datetime import date
from pathlib import Path

import lightgbm as lgb
import numpy as np
import pandas as pd
from scipy.spatial import cKDTree

from build_baseline import (CAUTION_TOP_PERCENT, CELL, SLOTS, SMOOTH_RADIUS_M, TARGET_RADIUS_M, baseline_table,
                            candidate_cells, day_counts, smoothing_offsets)
from db import connect
from reference_data import OBS_END, OBS_START, SOURCE_DATASET

WINDOWS = {
    "feat_a": (OBS_START, date(2025, 5, 31)), "label_a": (date(2025, 6, 1), date(2025, 7, 31)),
    "feat_b": (OBS_START, date(2025, 7, 31)), "label_b": (date(2025, 8, 1), OBS_END),
    "feat_serve": (OBS_START, OBS_END),
}
PARAMS = dict(objective="poisson", learning_rate=0.05, n_estimators=400, num_leaves=31,
              min_child_samples=200, subsample=0.8, subsample_freq=1, colsample_bytree=0.8,
              reg_lambda=1.0, random_state=42, n_jobs=4, verbose=-1)  # n_jobs: 맥에서 코어를 다 쓰면 오히려 느려짐
FEATURES = ["weekday", "time_slot", "is_weekend",
            "rate_ws", "rate_cell", "rate_cell_slot", "rate_cell_wd",
            "nb_ws", "nb_ring1", "nb_ring2", "global_ws", "dist_reported_m", "log_days_hist"]


_T0 = time.time()


def step(msg: str) -> None:
    """진행 상황 + 시작 후 경과 시간 (어디서 오래 걸리는지 보이게)"""
    print(f"[{time.time() - _T0:6.0f}초] {msg}", flush=True)


# ─── 데이터 읽기 ──────────────────────────────────────────
def load(cur):
    cur.execute("SELECT id FROM data_imports WHERE source_dataset = %s AND active", (SOURCE_DATASET,))
    imp = cur.fetchone()[0]
    cur.execute("""SELECT e.grid_id, (e.enforced_at AT TIME ZONE 'Asia/Seoul')
                     FROM enforcement_records e
                    WHERE e.import_id = %s AND e.is_citizen_report AND e.grid_id IS NOT NULL
                      AND e.location_precision IN ('PARCEL', 'ROAD')
                      AND (e.enforced_at AT TIME ZONE 'Asia/Seoul')::date BETWEEN %s AND %s""",
                (imp, OBS_START, OBS_END))
    ev = pd.DataFrame(cur.fetchall(), columns=["grid_id", "kst"])
    cur.execute("SELECT holiday_date FROM holidays")
    holidays = {r[0] for r in cur.fetchall()}
    ev["date"] = pd.to_datetime(ev["kst"]).dt.date
    ev["weekday"] = [7 if d in holidays else d.isoweekday() for d in ev["date"]]
    ev["time_slot"] = pd.to_datetime(ev["kst"]).dt.hour // 3

    # 대상 칸: 베이스라인과 똑같이 (신고 지번 200 m 이내)
    cur.execute("""SELECT ST_X(p), ST_Y(p) FROM (SELECT ST_Transform(location::geometry, 5186) AS p
                     FROM parcel_report_stats WHERE import_id = %s AND period_start = %s AND period_end = %s) t""",
                (imp, OBS_START, OBS_END))
    cells = candidate_cells(np.array(cur.fetchall(), dtype=float), TARGET_RADIUS_M)
    cur.execute("CREATE TEMP TABLE tmp_cells (grid_x int, grid_y int) ON COMMIT DROP")
    with cur.copy("COPY tmp_cells FROM STDIN") as cp:
        for gx, gy in cells:
            cp.write_row((gx, gy))
    cur.execute("""SELECT g.id, g.grid_x, g.grid_y FROM tmp_cells t JOIN spatial_grids g
                     ON g.projected_srid = 5186 AND g.cell_size_m = %s AND g.grid_x = t.grid_x
                    AND g.grid_y = t.grid_y AND g.in_model_area""", (CELL,))
    targets = pd.DataFrame(cur.fetchall(), columns=["grid_id", "grid_x", "grid_y"])

    # 평가용 대상 칸: 7/31까지 신고된 지번만으로 뽑는다 (8~9월 위치를 미리 알면 안 됨 → 리뷰 반영)
    cur.execute("""SELECT DISTINCT ST_X(ST_Transform(e.location::geometry, 5186)), ST_Y(ST_Transform(e.location::geometry, 5186))
                     FROM enforcement_records e
                    WHERE e.import_id = %s AND e.is_citizen_report AND e.location IS NOT NULL
                      AND e.location_precision IN ('PARCEL', 'ROAD')
                      AND (e.enforced_at AT TIME ZONE 'Asia/Seoul')::date BETWEEN %s AND %s""",
                (imp, OBS_START, WINDOWS["feat_b"][1]))
    train_cells = candidate_cells(np.array(cur.fetchall(), dtype=float), TARGET_RADIUS_M)
    targets_eval = targets[[c in train_cells for c in zip(targets["grid_x"], targets["grid_y"])]].reset_index(drop=True)

    return imp, ev, holidays, targets, targets_eval, latest_baseline_id(cur)


def data_fingerprint(cur, imp) -> dict:
    """학습에 쓴 원본 데이터의 지문. 학습(파일 저장) 시점과 DB 적재(--load) 시점에 각각 계산해 비교한다.
    - 시민 신고: load()와 같은 조건의 (id, 칸) 목록
    - 지번 통계: 대상 칸을 뽑는 parcel_report_stats(전체 기간)의 (지번, 건수) 목록
    둘 중 하나라도 바뀌면(재적재, AMBIGUOUS 처리 변경 등) 예측 파일이 지금 DB와 맞지 않는다."""
    cur.execute("""SELECT count(*), md5(coalesce(string_agg(e.id::text || ':' || e.grid_id::text, ',' ORDER BY e.id), ''))
                     FROM enforcement_records e
                    WHERE e.import_id = %s AND e.is_citizen_report AND e.grid_id IS NOT NULL
                      AND e.location_precision IN ('PARCEL', 'ROAD')
                      AND (e.enforced_at AT TIME ZONE 'Asia/Seoul')::date BETWEEN %s AND %s""",
                (imp, OBS_START, OBS_END))
    n_rep, h_rep = cur.fetchone()
    cur.execute("""SELECT count(*), md5(coalesce(string_agg(parcel_id::text || ':' || report_count::text, ',' ORDER BY parcel_id), ''))
                     FROM parcel_report_stats WHERE import_id = %s AND period_start = %s AND period_end = %s""",
                (imp, OBS_START, OBS_END))
    n_par, h_par = cur.fetchone()
    return {"source_import_id": str(imp), "citizen_reports": n_rep, "citizen_reports_md5": h_rep,
            "parcel_stats": n_par, "parcel_stats_md5": h_par}


def check_same_data(saved: dict | None, now: dict) -> None:
    if not saved:
        raise SystemExit("예측 파일에 원본 데이터 지문이 없어요(예전 버전 파일). 학습부터 다시 실행하세요.")
    diff = [k for k in now if saved.get(k) != now[k]]
    if diff:
        raise SystemExit("학습 이후 DB의 원본 데이터가 바뀌어서 이 예측 파일을 올릴 수 없어요 "
                         f"(다른 항목: {', '.join(diff)}). 학습부터 다시 실행하세요.")


def latest_baseline_id(cur):
    """베이스라인은 LightGBM이 켜지면 꺼지므로 active가 아니라 가장 최근 동결본을 찾는다"""
    cur.execute("SELECT id FROM prediction_versions WHERE model_type = 'BASELINE_MEAN' AND frozen_at IS NOT NULL "
                "ORDER BY frozen_at DESC LIMIT 1")
    row = cur.fetchone()
    return row[0] if row else None


def window(ev: pd.DataFrame, name: str) -> pd.DataFrame:
    s, e = WINDOWS[name]
    return ev[(ev["date"] >= s) & (ev["date"] <= e)]


def counts(ev_w: pd.DataFrame) -> pd.DataFrame:
    return ev_w.groupby(["grid_id", "weekday", "time_slot"]).size().rename("report_count").reset_index()


# ─── 피처 ────────────────────────────────────────────────
def shift_sum(base: pd.DataFrame, value: pd.Series, offsets) -> np.ndarray:
    """칸 번호를 (dx, dy)만큼 옮긴 이웃 값의 가중합. base: grid_x, grid_y (+ 키), value: 같은 키 인덱스

    속도: 좌표를 numpy 격자 배열에 펼쳐 놓고 배열 인덱싱으로 이웃을 더한다
    (pandas MultiIndex reindex보다 수십 배 빠르고 메모리도 적게 씀)."""
    extra = [c for c in value.index.names if c not in ("grid_x", "grid_y")]
    vx = value.index.get_level_values("grid_x").to_numpy(dtype=np.int64)
    vy = value.index.get_level_values("grid_y").to_numpy(dtype=np.int64)
    bx = base["grid_x"].to_numpy(dtype=np.int64)
    by = base["grid_y"].to_numpy(dtype=np.int64)
    pad = max(max(abs(dx), abs(dy)) for dx, dy, _ in offsets)
    x0, y0 = min(vx.min(), bx.min()) - pad, min(vy.min(), by.min()) - pad
    w_, h_ = max(vx.max(), bx.max()) + pad - x0 + 1, max(vy.max(), by.max()) + pad - y0 + 1

    # 요일·시간대 같은 추가 키는 0..K-1 번호로 바꾼다
    if extra:
        vcode = np.zeros(len(value), dtype=np.int64)
        bcode = np.zeros(len(base), dtype=np.int64)
        for c in extra:
            vcode = vcode * 1000 + value.index.get_level_values(c).to_numpy(dtype=np.int64)
            bcode = bcode * 1000 + base[c].to_numpy(dtype=np.int64)
        cats, inv = np.unique(np.concatenate([vcode, bcode]), return_inverse=True)
        vk, bk, k_ = inv[:len(vcode)], inv[len(vcode):], len(cats)
    else:
        vk, bk, k_ = np.zeros(len(value), dtype=np.int64), np.zeros(len(base), dtype=np.int64), 1

    grid = np.zeros((w_, h_, k_), dtype=np.float64)
    grid[vx - x0, vy - y0, vk] = np.nan_to_num(value.to_numpy(dtype=np.float64))
    out = np.zeros(len(base))
    for dx, dy, w in offsets:
        out += w * grid[bx - x0 + dx, by - y0 + dy, bk]
    return out


def build_features(targets: pd.DataFrame, ev_hist: pd.DataFrame, hist: tuple[date, date], holidays) -> pd.DataFrame:
    days = day_counts(hist[0], hist[1], holidays)
    total_days = sum(days.values())
    full = targets.merge(pd.DataFrame(SLOTS, columns=["weekday", "time_slot"]), how="cross")
    c = counts(ev_hist)
    full = full.merge(c, on=["grid_id", "weekday", "time_slot"], how="left").fillna({"report_count": 0})
    full["rate_ws"] = full["report_count"] / full["weekday"].map(days)

    per_cell = full.groupby("grid_id")["report_count"].transform("sum")
    full["rate_cell"] = per_cell / total_days
    full["rate_cell_slot"] = full.groupby(["grid_id", "time_slot"])["report_count"].transform("sum") / total_days
    full["rate_cell_wd"] = full.groupby(["grid_id", "weekday"])["report_count"].transform("sum") / full["weekday"].map(days)

    # 이웃 칸 (베이스라인과 같은 가우시안 평활 = 같은 요일·시간대)
    key_ws = full.set_index(["grid_x", "grid_y", "weekday", "time_slot"])["rate_ws"]
    offs = smoothing_offsets()
    full["nb_ws"] = shift_sum(full, key_ws, offs) / sum(w for *_, w in offs)

    # 이웃 고리: 바로 옆 8칸 / 그 바깥 16칸의 하루 평균 (요일·시간대 무관)
    cell_rate = full.drop_duplicates("grid_id").set_index(["grid_x", "grid_y"])["rate_cell"]
    ring1 = [(dx, dy, 1.0) for dx in (-1, 0, 1) for dy in (-1, 0, 1) if (dx, dy) != (0, 0)]
    ring2 = [(dx, dy, 1.0) for dx in range(-2, 3) for dy in range(-2, 3) if max(abs(dx), abs(dy)) == 2]
    full["nb_ring1"] = shift_sum(full, cell_rate, ring1) / 8
    full["nb_ring2"] = shift_sum(full, cell_rate, ring2) / 16

    full["global_ws"] = full.groupby(["weekday", "time_slot"])["rate_ws"].transform("mean")
    full["is_weekend"] = (full["weekday"] >= 6).astype(int)

    # 가장 가까운 '신고가 있었던 칸'까지 거리 (자기 칸에 신고가 있으면 0)
    reported = targets[targets["grid_id"].isin(c["grid_id"].unique())][["grid_x", "grid_y"]].to_numpy()
    if len(reported):
        d, _ = cKDTree(reported).query(full[["grid_x", "grid_y"]].to_numpy(), k=1)
        full["dist_reported_m"] = d * CELL
    else:
        full["dist_reported_m"] = 9999.0
    full["log_days_hist"] = np.log(full["weekday"].map(days))
    keep = ["grid_id", "grid_x", "grid_y"] + FEATURES
    out = full[keep].copy()
    num = [f for f in FEATURES if f not in ("weekday", "time_slot")]   # 키 열은 정수로 유지
    out[num] = out[num].astype("float32")                              # 메모리 절반
    return out


def with_label(feat: pd.DataFrame, ev_label: pd.DataFrame, label: tuple[date, date], holidays) -> pd.DataFrame:
    days = day_counts(label[0], label[1], holidays)
    y = counts(ev_label).rename(columns={"report_count": "y"})
    out = feat.merge(y, on=["grid_id", "weekday", "time_slot"], how="left").fillna({"y": 0})
    out["label_days"] = out["weekday"].map(days)
    return out


# ─── 학습·예측·채점 ───────────────────────────────────────
def fit(train: pd.DataFrame) -> lgb.LGBMRegressor:
    model = lgb.LGBMRegressor(**PARAMS)
    model.fit(train[FEATURES], train["y"], init_score=np.log(train["label_days"]))
    return model


def predict_rate(model: lgb.LGBMRegressor, feat: pd.DataFrame) -> np.ndarray:
    # init_score 없이 예측 → exp(점수) = 하루 평균 기대 건수
    return model.predict(feat[FEATURES])


def with_top_percent(df: pd.DataFrame, col: str) -> pd.Series:
    n = df.groupby(["weekday", "time_slot"])[col].transform("size")
    ge = df.groupby(["weekday", "time_slot"])[col].rank(method="max", ascending=False)
    return 100.0 * ge / n


def score(pred: pd.DataFrame, rate_col: str, y: pd.Series, label_days: pd.Series, total: float | None = None) -> dict:
    """베이스라인 평가(build_baseline.evaluate)와 같은 지표.
    total: 검증 기간 전체 신고 수. 대상 칸 밖에서 나온 신고는 '놓친 것'으로 분모에 들어간다."""
    mu = pred[rate_col].to_numpy() * label_days.to_numpy()
    yv = y.to_numpy()
    top = with_top_percent(pred, rate_col)
    flagged = (top <= CAUTION_TOP_PERCENT).to_numpy()
    mu0 = pd.Series(yv).groupby([pred["weekday"].to_numpy(), pred["time_slot"].to_numpy()]).transform("mean").to_numpy()

    def dev(y_, m_):
        m_ = np.maximum(m_, 1e-6)
        with np.errstate(divide="ignore", invalid="ignore"):
            t = np.where(y_ > 0, y_ * np.log(y_ / m_), 0.0)
        return float(2 * np.sum(t - (y_ - m_)))

    d, d0 = dev(yv, mu), dev(yv, mu0)
    total = yv.sum() if total is None else total

    # 공정한 비교: 요일·시간대마다 예측 상위 k% 칸을 똑같은 개수로 골라 실제 신고를 몇 % 잡는지
    order = pred.groupby(["weekday", "time_slot"])[rate_col].rank(method="first", ascending=False)
    n = pred.groupby(["weekday", "time_slot"])[rate_col].transform("size")

    def capture_at(k):
        pick = (order <= np.ceil(n * k / 100)).to_numpy()
        return round(float(yv[pick].sum() / total), 4) if total else None

    return {"capture_top5": capture_at(5), "capture_top10": capture_at(10),
            "flagged_cell_share": round(float(flagged.mean()), 4),
            "reports_in_targets_share": round(float(yv.sum() / total), 4) if total else None,
            "capture_at_caution": round(float(yv[flagged].sum() / total), 4) if total else None,
            "poisson_deviance": round(d, 1), "poisson_deviance_null": round(d0, 1),
            "deviance_improvement_vs_null": round(1 - d / d0, 4) if d0 else None}


def write_version(cur, imp, baseline_id, version_name, targets, table, metrics) -> uuid.UUID:
    vid = uuid.uuid4()
    cur.execute(
        """INSERT INTO prediction_versions
               (id, model_type, model_version, training_start, training_end, validation_start, validation_end,
                target_definition, source_import_id, observed_end, holiday_policy, projected_srid, cell_size_m,
                smoothing_radius_m, percentile_basis, tie_method, caution_top_percent, target_cell_count, metrics,
                baseline_version_id, description)
           VALUES (%s, 'LGBM_POISSON', %s, %s, %s, %s, %s, 'ROW_COUNT', %s, %s, 'AS_SUNDAY', 5186, %s, %s,
                   'SAME_SLOT', 'GE_SHARE', %s, %s, %s, %s, %s)""",
        (vid, version_name, OBS_START, OBS_END, WINDOWS["label_b"][0], WINDOWS["label_b"][1], imp, OBS_END, CELL,
         SMOOTH_RADIUS_M, CAUTION_TOP_PERCENT, len(targets), json.dumps(metrics, ensure_ascii=False), baseline_id,
         "LightGBM poisson. 과거 기간 패턴 → 다음 기간 건수. 피처: " + ", ".join(FEATURES)))
    with cur.copy("COPY prediction_target_grids (version_id, grid_id) FROM STDIN") as cp:
        for g in targets["grid_id"]:
            cp.write_row((vid, int(g)))
    with cur.copy("COPY risk_predictions (version_id, grid_id, weekday, time_slot, expected_count, "
                  "smoothed_count, top_percent) FROM STDIN") as cp:
        g, w, t = (table[c].to_numpy().astype(int).tolist() for c in ("grid_id", "weekday", "time_slot"))
        rate, top = table["rate"].to_numpy().tolist(), table["top_percent"].to_numpy().tolist()
        for i in range(len(g)):
            cp.write_row((vid, g[i], w[i], t[i], rate[i], rate[i], top[i]))
    return vid


OUT = Path(__file__).resolve().parent / "out"


def files_for(version: str) -> tuple[Path, Path]:
    return OUT / f"{version}_predictions.csv.gz", OUT / f"{version}_metrics.json"


def load_to_db(args) -> None:
    """2단계: 저장해 둔 예측 파일을 DB에 적재 → freeze → (더 좋으면) activate. 학습은 다시 안 한다."""
    pred_path, metrics_path = files_for(args.version)
    if not pred_path.exists() or not metrics_path.exists():
        raise SystemExit(f"예측 파일이 없어요. 먼저 학습을 실행하세요: python etl/train_lgbm.py --version {args.version}")
    metrics = json.loads(metrics_path.read_text(encoding="utf-8"))
    serve = pd.read_csv(pred_path, dtype={"grid_id": "int64", "weekday": "int16", "time_slot": "int16",
                                          "rate": "float64", "top_percent": "float64"})
    targets = pd.DataFrame({"grid_id": serve["grid_id"].unique()})
    print(f"파일 읽음: 칸 {len(targets):,}개, 예측 {len(serve):,}행")

    with connect() as conn, conn.cursor() as cur:
        cur.execute("SELECT id FROM data_imports WHERE source_dataset = %s AND active", (SOURCE_DATASET,))
        imp = cur.fetchone()[0]
        # 학습 때와 지금 DB의 원본 데이터가 같은지 확인 (리뷰 반영: 다르면 적재하지 않음)
        check_same_data(metrics.get("source_data"), data_fingerprint(cur, imp))
        step("원본 데이터 일치 확인")
        baseline_id = latest_baseline_id(cur)

        step("DB에 줄 넣는 중 (몇 분 걸릴 수 있어요)")
        vid = write_version(cur, imp, baseline_id, args.version, targets, serve, metrics)
        step("줄 넣기 끝. 검증(freeze) 중")
        # 방금 넣은 212만 행은 통계가 없어서 DB가 '칸마다 전체를 훑는' 느린 방법을 고른다 → 막아 둔다
        cur.execute("SET LOCAL enable_nestloop = off")
        cur.execute("SET LOCAL work_mem = '256MB'")
        cur.execute("SELECT freeze_prediction_version(%s)", (vid,))
        step("검증 끝")
        if args.activate and metrics["better_than_baseline"]:
            cur.execute("SELECT activate_prediction_version(%s)", (vid,))
        elif args.activate:
            print("베이스라인보다 못한 지표가 있어 activate하지 않았어요 (freeze만 됨)")
        conn.commit()
        cur.execute("SELECT model_version, target_cell_count, loaded_row_count, frozen_at IS NOT NULL, active "
                    "FROM prediction_versions WHERE id = %s", (vid,))
        print("버전:", cur.fetchone())
        print("version_id:", vid)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--version", required=True)
    ap.add_argument("--dry-run", action="store_true", help="학습·비교만 하고 파일도 안 만듦")
    ap.add_argument("--load", action="store_true", help="학습 없이, 저장된 예측 파일을 DB에 적재")
    ap.add_argument("--activate", action="store_true", help="(--load와 함께) 베이스라인보다 좋을 때만 activate")
    args = ap.parse_args()

    if args.load:
        load_to_db(args)
        return

    # 데이터만 읽고 DB 연결은 바로 닫는다 (학습 중에 연결을 붙잡고 있지 않게)
    step("DB에서 데이터 읽는 중")
    with connect() as conn, conn.cursor() as cur:
        imp, ev, holidays, targets, targets_eval, baseline_id = load(cur)
        fingerprint = data_fingerprint(cur, imp)
    step(f"읽기 끝. 시민 신고 {len(ev):,}건, 대상 칸 {len(targets):,}개 (평가용: 7/31까지 지번 기준 {len(targets_eval):,}개)")

    # 1) 평가: 피처(1/1~5/31) → 정답(6/1~7/31)으로 학습, 피처(1/1~7/31)로 8/1~9/25 채점
    step("평가용 피처 만드는 중")
    train_a = with_label(build_features(targets_eval, window(ev, "feat_a"), WINDOWS["feat_a"], holidays),
                         window(ev, "label_a"), WINDOWS["label_a"], holidays)
    test_b = with_label(build_features(targets_eval, window(ev, "feat_b"), WINDOWS["feat_b"], holidays),
                        window(ev, "label_b"), WINDOWS["label_b"], holidays)
    step("평가용 모델 학습 중")
    model = fit(train_a)
    test_b["rate"] = predict_rate(model, test_b)
    total_valid = len(window(ev, "label_b"))   # 8/1~9/25 신고 전체 (평가 대상 칸 밖 포함)
    m_lgbm = score(test_b, "rate", test_b["y"], test_b["label_days"], total_valid)

    # 같은 시험지로 베이스라인 채점 (1/1~7/31 과거 평균)
    base = baseline_table(targets_eval, counts(window(ev, "feat_b")), day_counts(*WINDOWS["feat_b"], holidays))
    base = base.merge(test_b[["grid_id", "weekday", "time_slot", "y", "label_days"]],
                      on=["grid_id", "weekday", "time_slot"])
    m_base = score(base, "smoothed_count", base["y"], base["label_days"], total_valid)

    print("베이스라인:", json.dumps(m_base, ensure_ascii=False))
    print("LightGBM :", json.dumps(m_lgbm, ensure_ascii=False))
    # 판정은 '같은 개수의 칸'으로 비교한 포착률(상위 5%)과 건수 예측 개선율 두 가지로 한다
    better = (m_lgbm["capture_top5"] or 0) >= (m_base["capture_top5"] or 0) and \
             (m_lgbm["deviance_improvement_vs_null"] or -9) >= (m_base["deviance_improvement_vs_null"] or -9)
    print("판정:", "LightGBM이 두 지표 모두 같거나 좋음" if better else "베이스라인보다 못한 지표가 있음")

    imp_gain = dict(sorted(zip(FEATURES, model.booster_.feature_importance("gain").round(1).tolist()),
                           key=lambda kv: -kv[1]))
    total_gain = sum(imp_gain.values()) or 1
    print("피처 중요도(gain 비율):", {k: round(v / total_gain, 3) for k, v in imp_gain.items()})
    if args.dry_run:
        print("dry-run: 파일·DB 모두 쓰지 않음")
        return

    # 2) 서비스용: 두 학습쌍을 모두 써서 다시 학습 → 전체 기간 피처로 예측
    del base, model
    step("서비스용 모델 학습 중 (가장 오래 걸리는 단계)")
    cols = FEATURES + ["y", "label_days"]
    train_all = pd.concat([train_a[cols], test_b[cols]], ignore_index=True)
    del train_a, test_b
    final = fit(train_all)
    del train_all
    step("서비스용 예측 만드는 중")
    serve = build_features(targets, window(ev, "feat_serve"), WINDOWS["feat_serve"], holidays)
    serve["rate"] = predict_rate(final, serve)
    del final
    serve["top_percent"] = with_top_percent(serve, "rate")
    print(f"2단계 칸 비율 {(serve['top_percent'] <= CAUTION_TOP_PERCENT).mean():.1%}")

    metrics = {"source_data": fingerprint,
               "eval_targets": "7/31까지 신고 지번 200m 이내 칸 (검증 기간 위치 미반영), 대상 밖 신고는 놓친 것으로 계산",
               "eval_lgbm": m_lgbm, "eval_baseline_same_split": m_base, "better_than_baseline": better,
               "eval_split": {k: [str(v[0]), str(v[1])] for k, v in WINDOWS.items()},
               "feature_importance_gain_share": {k: round(v / total_gain, 4) for k, v in imp_gain.items()},
               "params": {k: v for k, v in PARAMS.items() if k != "verbose"}}

    # 3) 파일로 저장하고 끝낸다 → 파이썬이 끝나면 메모리가 비워짐. DB 적재는 --load로 따로.
    OUT.mkdir(exist_ok=True)
    pred_path, metrics_path = files_for(args.version)
    serve[["grid_id", "weekday", "time_slot", "rate", "top_percent"]].to_csv(pred_path, index=False)
    metrics_path.write_text(json.dumps(metrics, ensure_ascii=False, indent=2), encoding="utf-8")
    step(f"저장: {pred_path.name} ({len(serve):,}행), {metrics_path.name}")
    print(f"다음 단계: python etl/train_lgbm.py --version {args.version} --load --activate")


if __name__ == "__main__":
    main()
