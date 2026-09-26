# DB 상세 설계

기준: [requirements.md](requirements.md). Room 스키마 v1, 최초/기존 트립 저장·검색·집계, 개별 트립 수정·복수 삭제를 구현했다. 교체 구간 생성·정정·삭제의 Repository 작업은 후속 구현 대상이다. 미확정 화면 동작은 [교체 설계](replacement-flow.md)에 구분되어 있다.

## 1. 데이터 관계

```mermaid
erDiagram
    BREAKER ||--|{ REPLACEMENT_PERIOD : has
    REPLACEMENT_PERIOD ||--o{ TRIP_EVENT : contains
    BREAKER {
        long id PK
        int building
        string floor
        string panel_number
        string breaker_name
        long current_period_id FK
    }
    REPLACEMENT_PERIOD {
        long id PK
        long breaker_id FK
        long sequence
        int replacement_day "nullable"
        string creation_token UK
    }
    TRIP_EVENT {
        long id PK
        long period_id FK
        int trip_day
        string location
        string note
        long created_at
        string creation_token UK
    }
```

그림의 1개 이상 구간 관계는 정상 저장 완료 시의 상태다. 차단기의 current_period_id는 자신에게 속한 구간 하나를 가리킨다. 실제 발생 날짜, 입력시각, 구간 순서는 서로 다른 값이다.

## 2. 테이블 정의

| 테이블 | 필드 | 저장 및 제약 |
| --- | --- | --- |
| breaker | id | 자동 생성 Long, 기본키 |
| breaker | building, floor | 관 1~4, 확정된 층 코드. 저장 전 값 검증 |
| breaker | panel_number, breaker_name | 정규화한 필수 문자열 |
| breaker | current_period_id | 현재 구간 ID. 최초 생성 트랜잭션 중에는 null 허용, 정상 완료 시 반드시 지정 |
| replacement_period | id | 자동 생성 Long, 기본키. 날짜를 식별자로 쓰지 않음 |
| replacement_period | breaker_id | 소속 차단기 FK |
| replacement_period | sequence | 차단기 내 구간 순서. 날짜 정정·트립 입력으로 변경하지 않음 |
| replacement_period | replacement_day | 날짜를 일 단위 정수로 저장. null은 교체일 미상 |
| replacement_period | creation_token | 생성 요청 식별 문자열, 유일성 제약 |
| trip_event | id | 자동 생성 Long, 기본키·고유 이력번호 |
| trip_event | period_id | 소속 교체 구간 FK |
| trip_event | trip_day | 트립일자, 일 단위 정수, 필수 |
| trip_event | location | 해당 발생 건의 장소, 정규화한 필수 문자열 |
| trip_event | note | 빈 문자열 허용, 문장·줄바꿈 보존 |
| trip_event | created_at | 최초 등록시각, epoch milliseconds. 수정 시 유지 |
| trip_event | creation_token | 생성 요청 식별 문자열, 유일성 제약 |

교체일은 구간에서 가져오므로 트립마다 중복 저장하지 않는다. 장소는 트립별로 저장하여 한 건 수정이 다른 이력의 장소를 바꾸지 않게 한다. 트립횟수와 다음 교체일은 저장하지 않고 조회 시 계산한다.

구간 sequence는 같은 차단기 안에서 유일하게 관리한다. 삭제 후 남은 구간의 순서는 재번호 매기지 않는다. 화면용 고유 구간 번호에는 재사용하지 않는 구간 ID를 사용한다. 이력 ID도 새 기록에 재사용하지 않도록 생성 방식을 검증한다.

생성된 스키마는 `app/schemas/com.triptracker.app.data.local.TripDatabase/1.json`에 보관한다. 세 테이블의 ID는 AUTOINCREMENT이며, 트립일자에는 유일성 제약이 없다. [Room Entity 공식 문서](https://developer.android.com/training/data-storage/room/defining-data)

## 3. 제약 및 인덱스

| 대상 | 설계 |
| --- | --- |
| 같은 차단기 중복 생성 | breaker의 관·층·분전함번호·차단기명 복합 유일 인덱스 |
| 같은 차단기의 구간 순서 중복 | replacement_period의 breaker_id + sequence 유일 인덱스 |
| 트립의 날짜 중복 | 허용. period_id + trip_day에 유일성 제약을 두지 않음 |
| 관계 삭제 | 연결된 자식이 있으면 FK 삭제 제한. 트립 CASCADE 삭제를 사용하지 않음 |
| 구간별 트립 조회·대표 장소 | period_id + trip_day + created_at + id 인덱스 |
| 날짜별 전체 조회 | trip_day + created_at + id 인덱스 |
| 현재 구간 참조 | FK 존재 여부와 함께 소속 차단기 일치를 Repository에서 검증 |

현재 구간 존재·소속 일치·유일한 구간 삭제 제한·날짜 경계는 단일 FK만으로 보장되지 않는다. Repository의 트랜잭션에서 검사하고, DAO를 UI에 직접 노출하지 않는다. 각 제약의 DB 검증과 업무 검증을 구분하여 테스트한다.

## 4. 저장 작업의 원자성

| 작업 | 한 트랜잭션 안에서 처리할 내용 |
| --- | --- |
| 최초 트립 저장 | 차단기 확인/생성 → 최초 구간 생성 → current_period_id 지정 → 트립 저장. 한 트랜잭션으로 구현 |
| 기존 구간 트립 저장 | 구간 소속·날짜 검증 → 트립 생성 |
| 실제 교체 | 현재 구간 재확인·날짜 검증 → 다음 sequence로 구간 생성 → 현재 구간 참조 변경 |
| 교체일 정정 | 날짜 변경 후 경계 검증 → 동일 구간 날짜 갱신. 기존 트립·현재 참조 유지 |
| 트립 수정 | 대상 ID 재확인 → 대상 구간·날짜 검증 → 같은 ID의 필드와 구간 연결 수정 |
| 트립 복수 삭제 | 선택한 ID들만 삭제. 일부만 지워진 상태로 완료하지 않음 |
| 빈 현재 구간 삭제 | 트립 0건·다른 구간 존재 확인 → 직전 구간으로 현재 참조 변경 → 빈 구간 삭제 |
| 빈 과거 구간 삭제 | 트립 0건·유일 구간 아님 확인 → 구간 삭제, 현재 참조 유지 |

생성 중 실패하면 신규 차단기나 구간만 남기지 않는다. 현재 구간 삭제에서는 참조를 먼저 옮긴 뒤 삭제해 FK를 유지한다.

동일 저장 요청의 재시도에는 같은 creation_token을 사용하고, 별도로 등록하는 실제 발생 건에는 새 토큰을 부여하는 기술안을 사용한다. 저장 중 버튼 비활성화와 DB 유일성 검사를 함께 적용한다. 같은 토큰이 이미 저장됐다면 기존 ID와 동일 요청 내용을 확인하여 결과를 반환하고 덮어쓰지 않는다. 삭제된 기록에 대한 과거 요청을 자동 재실행하는 영구 작업 큐는 만들지 않는다.

## 5. 조회 구현 계약

| 조회 | 반환 기준 |
| --- | --- |
| 상세 검색 | trip → period → breaker 연결, 선택 조건 AND, 코드 포함 검색은 바인딩 값 사용 |
| 상세 정렬 | trip_day DESC → created_at DESC → id DESC |
| 구간별 집계 | period를 기준으로 트립을 LEFT JOIN, COUNT(trip.id). 트립이 없어도 0회 |
| 현재·이전 필터 | breaker.current_period_id와 구간 ID 비교 |
| 대표 장소 | 해당 구간의 trip_day DESC → created_at DESC → id DESC 첫 이력. 없으면 null, UI에서 ‘—’ |
| 구간 조회 | 현재 구간 먼저, 나머지는 sequence DESC |
| 다음 교체일 | 같은 차단기의 다음 sequence 구간에서 조회. 미상 날짜는 미상으로 유지 |

집계는 반드시 구간 ID로 묶는다. COUNT(*)를 사용해 트립 없는 구간을 1회로 세거나, 날짜로 묶어 같은 날 두 발생 건을 하나로 합치지 않는다. 대표 장소 조회 때문에 집계 행이 중복되지 않도록 장소는 단일 행으로 선택한다.

문자열 부분 검색은 정규화한 검색어를 바인딩한 `instr`로 구현했다. %, _ 및 역슬래시도 문자 자체로 처리하며 빈 검색어는 제한하지 않는다. 포함 검색 성능은 데이터량에 따라 실제 조회 계획을 확인하며, 일반 인덱스가 모든 포함 검색을 빠르게 만든다고 가정하지 않는다.

변경 결과는 DAO의 Flow로 전달하고, UI는 ViewModel의 상태를 관찰한다. 저장은 suspend 작업으로 수행한다. [Room 비동기 조회 공식 문서](https://developer.android.com/training/data-storage/room/async-queries)

## 6. 수정 범위

트립 한 건의 관·층·분전함번호·차단기명 수정은 대상 차단기 및 구간을 다시 지정하는 방식으로 구현했다. 공유하는 breaker 행은 이름 변경하지 않는다. 대상 차단기가 없으면 확정된 최초 등록 폼에서 마지막 교체일자 또는 미상을 입력해 대상 차단기·최초 구간을 만들고 기존 트립 ID를 연결한다. 신규 트립은 생성하지 않으며 실패 시 대상 생성도 롤백한다.

트립 UPDATE는 period_id·trip_day·location·note만 변경하여 ID·created_at·creation_token을 보존한다. 복수 삭제는 확인한 ID 집합을 한 트랜잭션에서 처리한다. 삭제 실패 시 앞서 삭제한 행도 복원하며, 마지막 트립 삭제 후에도 차단기·구간은 0회로 유지한다.

교체일 정정은 공통 구간 정보의 변경이므로 해당 구간의 여러 이력에 정정된 날짜가 표시되는 것이 의도된 동작이다. 트립의 장소·비고 수정은 그 한 건에만 적용한다.

## 7. 변경·마이그레이션

Room 스키마를 내보내 버전 관리하고, 스키마 변경 시 이전 데이터를 가진 DB에서 마이그레이션을 검증한다. 개발 편의를 위한 DB 삭제로 운영 이력을 잃지 않도록 파괴적 마이그레이션 fallback을 사용하지 않는다. [Room 마이그레이션 공식 문서](https://developer.android.com/training/data-storage/room/migrating-db-versions)

## 8. 구현 검증 우선순위

| 우선 검증 | 연결되는 기존 테스트 |
| --- | --- |
| 같은 날 두 건 보존·정확한 집계 | REG-05, COUNT-02 |
| 미상 구간·0회 구간 조회 | REG-09, COUNT-03, COUNT-08 |
| 현재 참조의 소속·존재, 교체 후 보존 | COUNT-04~05, PERIOD-06~07 |
| 한 건 수정·삭제가 다른 행에 영향 없음 | EDIT-02, DEL-02, PERIOD-08 |
| 부분 저장·중복 요청 방지 | INIT-02, SAVE-01, PERIOD-09 |
| 날짜 정정과 인접 구간 검증 | PERIOD-01~03 |
| 최신 장소 선택·집계 행 중복 없음 | DISPLAY-01~02 |

추가 DB 계약 테스트: 잘못된 부모 ID 거절, 다른 차단기의 구간을 현재로 지정하는 요청 거절, 차단기 생성 경쟁 시 중복 방지, FK 삭제 제한, ID 재사용 방지. 실행 가능한 테스트는 앱 기반 구성 후 먼저 작성한다.
