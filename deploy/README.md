# Lightsail 운영 배포 (서울 / RAM 4GB / Ubuntu 24.04 LTS)

저장소 루트에서 실행한다. 운영 서비스는 DB, 앱, Caddy 3개다.
DB와 앱에는 호스트 포트가 없고, DB 볼륨과 Caddy 인증서는 재기동 후에도 유지된다.
`docker compose down -v`는 운영 데이터를 삭제하므로 사용하지 않는다.

## 1. 서버 준비

Lightsail 서울 4GB 인스턴스에 고정 IP를 연결하고 API 도메인의 A 레코드를 지정한다.
`deploy/.env.prod`의 `DOMAIN`에는 스킴/경로 없이 `api.example.com`을 넣는다.
도메인이 아직 없다면 `203-0-113-10.sslip.io`처럼 고정 공인 IP를 포함하는 임시 이름을
사용할 수 있다(예시 IP를 실제 서버 IP로 교체). 해당 이름이 서버로 해석되는지 확인한다.
임시 도메인도 공개 CA의 인증서 발급과 80/443 도달이 필요하며 Android에는 HTTPS 주소를 사용한다.
AAAA를 사용한다면 이 서버의 IPv6와 방화벽도 일치시킨다.
Lightsail 방화벽과 OS 방화벽에 공개할 인바운드는 TCP 80/443만 허용한다.
초기 설정/관리를 위한 SSH 22는 관리자 IP에만 제한한다. 5432/8080은 열지 않는다.

Docker 공식 Ubuntu 설치 절차로 Docker Engine과 Compose 플러그인을 설치한다:
<https://docs.docker.com/engine/install/ubuntu/>.
`docker version`, `docker compose version`으로 확인한다. Python 3도 설치한다.
저장소와 운영 CSV/덤프를 서버에 준비한다. 덤프는 gitignore 대상이므로 SCP 등으로 별도 업로드한다.
이미지 빌드와 덤프 staging을 위해 여유 디스크를 확보한다
(덤프, 추출 SQL, staging 데이터, 최종 데이터가 일시적으로 함께 존재한다).

```bash
sudo apt-get update
sudo apt-get install -y python3
cp deploy/.env.prod.example deploy/.env.prod
chmod 600 deploy/.env.prod
# 편집기로 deploy/.env.prod의 빈 값을 채운다. 실제 비밀은 커밋하지 않는다.
export COMPOSE_ENV_FILES=deploy/.env.prod
docker compose -f deploy/compose.prod.yaml config --quiet
```

**매 SSH 세션마다 루트에서 `export COMPOSE_ENV_FILES=deploy/.env.prod`를 실행한다.**
이 설정은 Compose의 `${...}` 치환에 쓰인다. 앱의 `env_file`도 `deploy/.env.prod`를 읽는다.
루트 개발용 `.env`는 편집하거나 source하지 않는다. 전체 `config` 출력에는 비밀이 있으므로 공유하지 않는다.
쉘의 기존 동명 환경변수는 `.env`보다 우선하므로 깨끗한 운영 셸을 사용한다.
비밀번호는 각 계정마다 다르게 생성한다. `$`를 포함한 값은 Compose `.env`의 작은따옴표로 감싼다.
JWT 키는 `openssl rand -base64 48`로 생성한 한 줄 값을 사용한다.
Kakao 앱 ID/관리자 키/Local REST 키, 허용 Origin을 실제 운영 앱에 맞춘다.
약관 3종은 `2026-11-06-v1`, 규칙 묶음은 `yongin-2026-11-v1`로 설정한다.
추가 주차 정책은 예제의 기본값을 검토한다. `SPRING_*`, 로깅, JVM 설정을 임의로 추가하지 않는다.

## 2. DB만 기동하고 역할·비밀번호 설정

```bash
docker compose -f deploy/compose.prod.yaml up -d --wait db
docker compose -f deploy/compose.prod.yaml cp scripts/db/bootstrap.sql db:/tmp/bootstrap.sql
docker compose -f deploy/compose.prod.yaml exec db sh -c \
  'psql -X -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f /tmp/bootstrap.sql'
docker compose -f deploy/compose.prod.yaml exec db sh -c \
  'psql -X -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
```

psql에서 다음 명령을 각각 실행하고 대화형으로 비밀번호를 입력한다.
`deploy/.env.prod`의 `DB_MIGRATION_PASSWORD`, `DB_APP_PASSWORD`, `ETL_DB_PASSWORD`와 일치시킨다.
평문 `ALTER ROLE ... PASSWORD`를 셸 히스토리에 남기지 않는다.

```text
\password parking_migrator
\password parking_api
\password parking_loader
\q
```

`POSTGRES_*`는 최초 빈 볼륨 초기화용이다. 이미 초기화한 DB 비밀번호는 `.env` 수정만으로 바뀌지 않는다.
앱은 `parking_api`, Flyway만 `parking_migrator`, ETL은 `parking_loader`를 사용한다.
운영 DB superuser나 migrator 권한을 API/loader에 부여하지 않는다.

## 3. 앱 기동과 Flyway

```bash
docker compose -f deploy/compose.prod.yaml up -d --build app
docker compose -f deploy/compose.prod.yaml logs --tail=100 app
```

Flyway V1~V6 완료와 앱 시작을 확인한다. `application-prod.yaml`이 환경변수로 DB/Flyway에
접속하며 이력·데이터 스키마는 `public`, JPA는 validate를 사용한다.
배포 전용 Docker ignore가 개발 `.env`와 덤프를 빌드 컨텍스트에서 제외한다.
이미지는 Java 21로 bootJar를 빌드하고 Java 21 JRE에서 UID 10001, `-Xmx512m`으로 실행한다.
기본 health는 HTTP 응답만 확인하며 DB/외부 API 준비 상태까지 보장하지 않는다.

## 4. 참조 데이터 16개 복원 (최초 빈 DB에서 한 번)

외부 트래픽을 받기 전에 앱을 멈춘다. DDL, Flyway 이력, 역할/ACL, 사용자 데이터는 복원하지 않는다.
아래 스크립트는 이전 검증과 같이 `pg_restore --data-only --no-owner --no-privileges`로
정확히 16개 TABLE DATA를 선택하고 staging으로 읽은 뒤, FK 순서대로 loader 권한으로 삽입한다.
트리거와 제약을 끄지 않으며 버전 데이터 적재 후 freeze/activate한다.
규칙 공개는 아래 검토 단계까지 미룬다. 두 시퀀스는 기존 값보다 낮추지 않고 max(id)에 맞춰 보정한다.
기준 절차는 `build/dump-v2-review/RESULTS.md`이며, `build/`는 서버에 없을 수 있어
동일한 추출/staging/loader INSERT 절차를 배포 스크립트로 제공한다.
보정 대상은 `enforcement_records_id_seq`, `spatial_grids_id_seq`다.

```bash
docker compose -f deploy/compose.prod.yaml stop app
python3 deploy/restore_reference.py /absolute/path/yongin_parking_v2_final.dump
```

복원 테이블:
`data_imports`, `source_availability`, `geocoded_parcels`, `enforcement_records`,
`parcel_report_stats`, `spatial_grids`, `report_aggregates`, `prediction_versions`,
`prediction_target_grids`, `risk_predictions`, `zone_types`, `zone_rule_sets`,
`zone_rules`, `facilities`, `parking_lots`, `holidays`.

스크립트는 컬럼 일치와 최종 행 수를 검사한다. 대상 테이블이나 회원/주차 데이터가 있으면 중단한다.
실패 시 원인을 먼저 확인한다. staging 잔재나 이미 적재한 데이터를 자동 삭제/덮어쓰기하지 않는다.
원본 덤프를 보관하고, 운영 데이터가 생긴 후에는 별도의 백업/복구 절차를 사용한다.

## 4-1. 규칙 검토 후 마지막에 publish

덤프의 `zone_rule_sets`도 공개하지 않고 복원한다. 먼저 아래 psql에서 기존 규칙을 확인한다.

```bash
docker compose -f deploy/compose.prod.yaml cp docs/rules_v1.sql db:/tmp/rules_v1.sql
docker compose -f deploy/compose.prod.yaml exec db sh -c \
  'psql -X -v ON_ERROR_STOP=1 -U parking_migrator -d "$POSTGRES_DB"'
```

```sql
SELECT rule_set, published_at FROM zone_rule_sets;
SELECT * FROM zone_rules WHERE rule_set='yongin-2026-11-v1';
-- 해당 rule_set이 없을 때만 적용 (INSERT 스크립트이므로 중복 실행 금지):
\i /tmp/rules_v1.sql
SELECT zone_type, geometry_rule, legal_distance_m, fine_amount_car
FROM zone_rules WHERE rule_set='yongin-2026-11-v1';
```

이미 동일한 rule_set이 있으면 `\i`를 실행하지 않고 파일과 DB의 3행을 대조한다.
다르면 공개 전에 원인을 해결한다. 소화전 5m/8만원, 정류소 10m/4만원,
OTHER COVERS/4만원과 메시지까지 검토한다. 규칙 적용·검토 단계의 마지막에 실행한다.
**공개 후 수정·삭제와 공개 취소는 DB가 차단한다. 변경 시 새 rule_set이 필요하다.**

```sql
UPDATE zone_rule_sets SET published_at=now()
WHERE rule_set='yongin-2026-11-v1' AND published_at IS NULL;
\q
```

`APP_PARKING_RULE_SET=yongin-2026-11-v1`을 확인하고 전체 서비스를 기동한다.

```bash
docker compose -f deploy/compose.prod.yaml up -d
docker compose -f deploy/compose.prod.yaml ps
```

Caddy가 도메인의 공개 인증서를 자동 발급한다. DNS/80/443 도달 가능성이 필요하다.
Caddy access log는 비활성이고 일반 runtime 요청 오류 로그도 억제해 위치 쿼리 노출을 막는다.
앱에도 URL/본문 로깅이나 debug를 켜지 않는다.

## 5. 주차장 CSV 적재 (parking_loader)

DB 호스트 포트를 열지 않고 Python 일회용 컨테이너를 같은 DB 네트워크에서 실행한다.
다음은 운영 CSV가 저장소의 `data/raw/parking_lots.csv`에 있는 예다.
기존 `etl/.env`를 읽지 않도록 임시 작업 디렉터리에 ETL 코드만 복사한다.
비밀번호는 대화형으로 입력하며 디스크에 남는 ETL 설정은 컨테이너 tmpfs에만 생성한다.

```bash
docker build -f deploy/Dockerfile.etl -t parking-etl:local .
DB_ID=$(docker compose -f deploy/compose.prod.yaml ps -q db)
DB_NETWORK=$(docker inspect "$DB_ID" --format '{{range $k, $v := .NetworkSettings.Networks}}{{$k}}{{end}}')
read -rsp 'parking_loader password: ' ETL_DB_PASSWORD; echo
export ETL_DB_PASSWORD
# POSTGRES_DB 값과 일치시킨다.
export ETL_DB_NAME=parking
docker run --rm --network "$DB_NETWORK" \
  -e ETL_DB_PASSWORD -e ETL_DB_NAME \
  -v "$PWD/etl:/source:ro" -v "$PWD/data/raw:/input:ro" \
  --tmpfs /work:rw,nosuid,nodev --workdir /work parking-etl:local sh -ec '
    cp /source/*.py /source/requirements.txt /work/
    python -c '\''import os; from pathlib import Path; Path(".env").write_text("ETL_DB_HOST=db\nETL_DB_PORT=5432\nETL_DB_USER=parking_loader\nETL_DB_NAME="+os.environ["ETL_DB_NAME"]+"\nETL_DB_PASSWORD="+os.environ["ETL_DB_PASSWORD"]+"\n")'\''
    python load_parking_lots.py --input /input/parking_lots.csv
  '
unset ETL_DB_PASSWORD ETL_DB_NAME
```


출력의 loaded/skipped와 활성 import를 검토한다. 같은 파일 재실행은 기존 import를 재사용한다.

## 6. Kakao 콘솔

운영 Kakao 앱의 로그인 설정에 클라이언트가 실제로 사용하는 HTTPS 리디렉트 URI를 등록한다.
Android SDK 로그인은 앱의 패키지명/키 해시와 SDK 리디렉트 설정도 확인한다.
서버에는 로그인 콜백 API가 없으므로 임의의 서버 경로를 리디렉트 URI로 만들지 않는다.
연결 끊기 웹훅은 `https://<DOMAIN>/api/v1/webhooks/kakao/unlink`로 등록하고,
`KAKAO_APP_ID`와 `KAKAO_PRIMARY_ADMIN_KEY`가 같은 운영 앱의 값인지 확인한다.

## 7. 동작 확인

```bash
curl --fail https://api.example.com/api/v1/health
# {"success":true,"data":{"status":"UP"},"error":null}
```

실제 운영 앱으로 Kakao 로그인 → 세 약관 동의 → 차량 등록을 완료한다.
위치 동의가 반영된 access token으로 지도 조회와 주차 저장을 확인한다.
실사용 위치 대신 아래 고정 검증 좌표를 사용하고 토큰/위치가 담긴 명령은 히스토리에 남기지 않는다.

```bash
read -rsp 'Access token: ' TOKEN; echo
curl --fail -H "Authorization: Bearer $TOKEN" \
  'https://api.example.com/api/v1/map/risk-zones?minLat=37.324&minLng=127.096&maxLat=37.325&maxLng=127.097'
# 등록한 vehicleId를 넣고 detectedAt/confirmedAt은 현재 ISO 8601 시각으로 갱신한다.
curl --fail -X POST https://api.example.com/api/v1/parking-events \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  --data '{"clientEventId":"<새 UUID>","vehicleId":"<등록 차량 UUID>","detectedAt":"<현재 시각>","confirmedAt":"<현재 시각>","confirmationMethod":"CONFIRMED","confirmedLocation":{"lat":37.324450,"lng":127.096184},"remeasureCount":0}'
unset TOKEN
```

지도 응답의 시설/격자/모델 기간과 주차 저장 성공·latest 조회를 확인한다.
같은 clientEventId 재요청의 중복 방지도 확인한다. health 성공만으로 데이터 적재 완료를 판단하지 않는다.
`docker compose logs caddy`에 위 지도 쿼리가 남지 않는지도 확인한다.
