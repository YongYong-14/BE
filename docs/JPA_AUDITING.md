# 공통 시간 엔티티와 JPA Auditing (#9)

기준은 [ERD v5.1](schema/yongin_parking_erd_v5.1.dbml)과
`src/main/resources/db/migration/`의 V1~V5다. 25개 테이블의 컬럼 이름/타입을 대조했고,
created_at/updated_at 13컬럼은 양쪽 모두 `timestamptz NOT NULL DEFAULT now()`로 일치한다.
이번 작업은 기존 SQL, 테이블 구조, DB 기본값을 변경하지 않는다.

## 선택적 상속

`global.entity.BaseCreatedEntity`에 createdAt과 `AuditingEntityListener`를 한 번 선언한다.
`BaseTimeEntity extends BaseCreatedEntity`는 updatedAt만 추가한다.
두 클래스는 추상 `@MappedSuperclass`이며 ID, deletedAt, version, 사용자 참조를 포함하지 않는다.
Lombok `@Getter`, protected 기본 생성자를 사용하고 public setter는 제공하지 않는다.
createdAt은 `nullable=false, updatable=false`, updatedAt은 `nullable=false`다.
부모 자체의 테이블은 생기지 않으며 모든 엔티티에 상속을 강제하지 않는다.

아래 표는 실제 스키마를 기준으로 한다. 기타 시각 컬럼은 부모를 상속하더라도 자식에서 별도 매핑한다.

| 실제 테이블 | 시간 컬럼 | 선택할 부모/처리 |
| --- | --- | --- |
| users | created_at, updated_at; last_login_at | BaseTimeEntity |
| refresh_tokens | created_at; expires_at, last_used_at, revoked_at | BaseCreatedEntity |
| user_agreements | occurred_at | 공통 부모 적용 안 함 |
| vehicles | created_at, updated_at; deleted_at | BaseTimeEntity |
| notification_settings | updated_at만 있음 | 개별 매핑 |
| parking_events | created_at; detected_at, confirmed_at, last_known_at | BaseCreatedEntity |
| risk_assessments | created_at, updated_at; reference_time, delivered_at, evaluated_at, report_period_start/end | BaseTimeEntity |
| assessment_reasons | 시간 컬럼 없음 | 공통 부모 적용 안 함 |
| location_access_logs | accessed_at | 공통 부모 적용 안 함 |
| zone_types | 시간 컬럼 없음 | 공통 부모 적용 안 함 |
| zone_rule_sets | created_at; published_at | BaseCreatedEntity |
| zone_rules | created_at | BaseCreatedEntity |
| facilities | imported_at, source_date | 공통 부모 적용 안 함 |
| parking_lots | imported_at, source_date; weekday/saturday/holiday의 open/close 6컬럼 | 공통 부모 적용 안 함 |
| holidays | holiday_date | 공통 부모 적용 안 함 |
| data_imports | imported_at, source_reference_date | 공통 부모 적용 안 함 |
| source_availability | observed_start, observed_end | 공통 부모 적용 안 함 |
| geocoded_parcels | created_at, updated_at; geocoded_at | BaseTimeEntity |
| enforcement_records | enforced_at | 공통 부모 적용 안 함 |
| parcel_report_stats | period_start, period_end | 공통 부모 적용 안 함 |
| spatial_grids | 시간 컬럼 없음 | 공통 부모 적용 안 함 |
| report_aggregates | generated_at, period_start, period_end | 공통 부모 적용 안 함 |
| prediction_versions | generated_at, frozen_at, validated_at; training_start/end, validation_start/end, observed_end | 공통 부모 적용 안 함 |
| prediction_target_grids | 시간 컬럼 없음 | 공통 부모 적용 안 함 |
| risk_predictions | 시간 컬럼 없음 | 공통 부모 적용 안 함 |

합계: BaseCreatedEntity 4개, BaseTimeEntity 4개, updated_at 개별 매핑 1개, 공통 부모 미적용 16개다.
occurred_at/imported_at/generated_at 등을 created_at으로 간주하지 않는다.
notification_settings에 BaseTimeEntity를 상속하면 존재하지 않는 created_at을 요구하므로 상속하지 않는다.
updated_at만 따로 Auditing하려면 해당 필드에 `@LastModifiedDate`와 리스너를 개별 선언한다.

## 팀원 사용 예시

다음은 문서 예시이며 운영 도메인 엔티티를 이번 작업에서 추가하지 않았다.
import와 나머지 도메인 매핑은 담당 엔티티에서 작성한다.

```java
@Entity
@Table(name = "users")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseTimeEntity {
    @Id
    private UUID id; // ID 전략은 자식이 결정

    @Column(name = "kakao_id", nullable = false, unique = true)
    private Long kakaoId;

    public User(UUID id, Long kakaoId) {
        this.id = id;
        this.kakaoId = kakaoId;
    }
    // createdAt/updatedAt 및 AuditingEntityListener를 다시 선언하지 않는다.
}

@Entity
@Table(name = "zone_rule_sets")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ZoneRuleSet extends BaseCreatedEntity {
    @Id
    @Column(name = "rule_set", length = 30)
    private String ruleSet;

    public ZoneRuleSet(String ruleSet) {
        this.ruleSet = ruleSet;
    }
    // createdAt만 상속한다. updatedAt은 추가하지 않는다.
}
```

같은 컬럼을 자식에서 다시 선언하거나 리스너를 중복 등록하지 않는다.
Auditing은 `persist`/flush 시 콜백에서 값을 넣으므로 생성자 직후에는 시각이 null일 수 있다.
실제 변경 감지로 UPDATE가 발생할 때 updatedAt이 바뀐다. 변경 없는 조회/flush 자체로 갱신을 기대하지 않는다.

## 타입과 기록 주체

Java 21 / Spring Boot 4.1.2 / Hibernate 7.4.13.Final을 유지한다.
PostgreSQL timestamptz에 저장할 절대 시각으로 `Instant`를 사용한다.
timestamptz는 원래 입력의 시간대 이름을 보관하지 않으며 세션 시간대에 따라 표시가 달라질 수 있다.
API의 Asia/Seoul 표시는 저장 시각과 별개다. 예를 들어 `instant.atZone(ZoneId.of("Asia/Seoul"))`로 변환한다.
운영시간 time, 업무 날짜 date까지 Instant로 바꾸지 않는다.

`global.config.JpaAuditingConfig`가 유일한 `@EnableJpaAuditing` 설정이다.
`modifyOnCreate=true`로 INSERT 때 createdAt과 updatedAt 모두 기록한다.
Spring Data 기본 시간 공급자가 LocalDateTime을 제공하므로, JVM 기본 시간대 변환을 피하기 위해
`auditingDateTimeProvider`에서 `Instant.now()`를 직접 반환한다. 선택한 필드 타입과 공급 타입이 같다.
시간 기록만 필요해 AuditorAware나 사용자 참조는 추가하지 않았다.

기본적으로 Auditing은 활성화된다. 기존 DB 없는 HTTP/컨텍스트 테스트만
`src/test/resources/application.properties`의 `app.jpa-auditing.enabled=false`로 제외한다.
이 테스트들은 이미 DataSource/Hibernate/Flyway 자동 구성도 제외하므로 JPA 메타모델이 없다.
local/prod 설정에는 이 opt-out을 넣지 않는다. 실제 JPA 테스트는 별도 source set에서 Auditing을 활성화한다.
운영에서 이 옵션을 false로 설정하지 않는다.
향후 src/test에 JPA 테스트를 추가한다면 해당 테스트에서 `app.jpa-auditing.enabled=true`와
JpaAuditingConfig import를 명시하거나 기존 postgresTest 구성을 따른다.

JPA INSERT에는 Auditing 값이 명시적으로 전달된다. 기존 `DEFAULT now()`는 직접 SQL INSERT의
기본값으로 유지하며, **UPDATE마다 updated_at을 자동 변경하는 기능은 아니다.**
부모에는 `insertable=false`, DB 생성 전용 어노테이션, @CreationTimestamp/@UpdateTimestamp를 섞지 않는다.

Python/ETL/직접 SQL/native update/JPQL bulk update는 엔티티 Auditing 콜백을 거치지 않는다.
직접 INSERT는 컬럼을 생략하면 기존 DEFAULT를 쓰고, 명시적 NULL은 NOT NULL 오류가 된다.
UPDATE 시 필요한 updated_at이나 업무 시각은 호출자가 직접 지정한다. 예:

```sql
UPDATE users SET nickname = :nickname, updated_at = now() WHERE id = :id;
```

JPQL bulk update도 `updatedAt = :now`를 명시하고 적절히 flush/clear해 오래된 영속성 컨텍스트를 피한다.
native SQL/ETL 수정도 같은 원칙이다. 기존 frozen 예측/규칙 등 DB 보호를 우회하려고
시각을 변경하면 안 되며, 시각 공급 방식이 기존 트리거를 대체하지 않는다.

## 검증 실행

```powershell
./gradlew.bat test bootJar --no-daemon
./scripts/db/Verify-Auditing.ps1 -JavaHome '자신의 Java 21 JDK 경로'
```

기본 `test`/`check`/`build`에는 Docker를 추가로 요구하지 않는다.
선택 실행인 `postgresTest`는 `src/postgresTest/`만 사용하며 기본 테스트 리소스를 공유하지 않는다.
직접 `postgresTest`를 호출할 때는 명시적인 전용 DB 입력이 필요하고, 없으면 통과/skip하지 않고 실패한다.
권장 실행기는 새 tmpfs PostgreSQL 컨테이너와 임의 포트를 준비해 이 입력을 전달한다.
부모 환경은 변경하지 않고 Spring/JVM/Gradle 옵션을 자식 환경에서 제외한다.
DB명·로그인·임의 표식을 확인한 후에만 `auditing_test` 스키마와 두 테스트 테이블을 생성한다.
Flyway에는 테스트 테이블을 추가하지 않는다. 테스트 엔티티도 운영 JAR에 포함되지 않는다.

테스트는 운영 JpaAuditingConfig/공통 클래스를 사용하되 DateTimeProvider만 제어한다.
마이크로초에 맞춘 고정 Instant 두 개를 사용해 sleep과 PostgreSQL 정밀도 반올림 문제를 피한다.
created-only 생성/유지, 생성 시 두 값 기록, 실제 필드 변경 후 flush·clear·재조회,
JPQL bulk update 우회/명시적 시각 처리를 검증한다.

## 실제 검증 결과 (2026-11-03)

| 검증 | 결과 |
| --- | --- |
| `test bootJar compilePostgresTestJava` | 기존 테스트 35개 실패 0, JAR/통합 테스트 컴파일 성공 |
| `Verify-Auditing.ps1` | PostgreSQL 16.15에서 Auditing 테스트 4개 통과, 실패/오류/skip 0 |
| 시간 검증 | 생성 시각 기록, 생성 시 두 필드 NOT NULL, 실제 필드 수정 후 flush/clear/재조회, 생성 시각 유지/수정 시각 갱신 |
| 상속/우회 | TimeEntity INSERT/UPDATE에 공급자 1회씩 호출, JPQL bulk update 시 콜백 미실행과 명시적 시각 갱신 확인 |
| `Verify-Database.ps1` | local 설정의 앱 기동, Flyway 전체/재실행/이관/삭제 보호, 기존 SQL 회귀 36개와 동시성 C1/C2 통과 |
| 변경 범위 | V1~V5/DBML 변경 없음, 테스트 엔티티·테스트 DDL의 운영 JAR 포함 0개, 문서 링크 정상 |

상세 기록은 Git 제외 경로에 있다.

- Auditing: `build/auditing-verification/parking-auditing-28027c7954/` (`tests.xml`, `gradle.log`, `database.log`)
- 앱/DB 회귀: `build/db-verification/parking-flyway-verify-8487ecbedd/`

개발 DB/볼륨은 변경하지 않았다. 실제 IDE 조작, 아직 구현하지 않은 도메인 전체의 JPA 매핑,
Python/ETL/native update 연동 검증은 수행하지 않았다. 후자는 위 기록 주체 원칙을 따라 후속 구현 시 검증한다.
