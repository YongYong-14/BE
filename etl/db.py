"""DB 접속 (parking_loader 계정). 접속 정보는 etl/.env 에서 읽는다."""
from __future__ import annotations

from pathlib import Path

import psycopg

from geocode_parcels import load_env

ENV_PATH = Path(__file__).resolve().parent / ".env"


def connect() -> psycopg.Connection:
    env = load_env(ENV_PATH)
    missing = [k for k in ("ETL_DB_NAME", "ETL_DB_USER", "ETL_DB_PASSWORD") if not env.get(k)]
    if missing:
        raise SystemExit(f"etl/.env에 {', '.join(missing)} 값이 없어요.")
    return psycopg.connect(
        host=env.get("ETL_DB_HOST", "localhost"),
        port=int(env.get("ETL_DB_PORT", "5433")),
        dbname=env["ETL_DB_NAME"],
        user=env["ETL_DB_USER"],
        password=env["ETL_DB_PASSWORD"],
    )
