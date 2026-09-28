# Trip Tracker

차단기 트립 이력과 교체 구간별 발생 횟수를 관리하는 Android 앱.

현재 Debug 앱(0.8.0)은 분전함·차단기 마스터와 트립 이력을 관리한다. 마스터 등록·수정·삭제, 설치 구간별 건수·상세 팝업, 장소(부하)로 위치 조회, 반복 입력 목록 선택을 지원한다. 교체 시 새 구간은 0건으로 시작하고 이전 이력을 보존한다. 기존 비고는 특기사항으로 유지하며 트립사유는 별도로 입력한다.

## Mac에서 화면 확인

현재 Apple Silicon Mac에 준비된 Android 에뮬레이터에서 실행한다.

```bash
bash scripts/run_emulator.sh
```

앱을 빌드하고 가상 휴대폰 창을 열어 설치·실행한다. 가상 기기의 데이터는 `.tools/avd`에 유지한다. 처음에는 분전함 → 차단기 → 트립 순서로 등록한다. [환경 준비와 실행 안내](docs/development.md#mac에서-앱-화면-열기)

## 기기 확인 순서

1. `app/build/outputs/apk/debug/app-debug.apk`를 설치한다. 기존 앱 업데이트는 동일 서명이 필요하며 기존 데이터를 보존하는 Room v1→v2→v3 마이그레이션이 포함된다.
2. 시작 화면 ‘분전함 마스터’에서 관·층·번호·위치를 입력하고 저장한다.
3. ‘차단기’에서 소속 분전함·번호·종류·port 수·정격전류·설치일자 또는 미상·장소(부하)를 저장한다.
4. ‘건수조회’에서 관·층을 선택하고 조회하면 새 차단기가 0건으로 표시되는지 확인한다.
5. ‘트립이력 → 새 트립’에서 차단기와 설치 구간을 선택하고 트립일자·장소·사유를 입력한다. 특기사항은 선택이다.
6. 같은 날 두 번 등록하면 두 이력과 2건으로 표시되는지 확인한다. 건수 행을 선택하고 헤더를 눌러 해당 구간의 상세 팝업을 확인한다.
7. 새 등록 폼의 ‘목록’에서 저장된 종류·위치·부하·사유 등을 다시 선택한다. 앱 재실행 후에도 목록과 이력이 유지되는지 확인한다.
8. ‘위치조회’에서 장소(부하) 일부를 입력해 관·층·분전함번호·분전함위치·차단기번호를 찾는다.
9. 트립 상세의 ‘수정’과 ‘삭제 요청’, 목록의 ‘선택 N건 삭제’를 확인한다. 삭제 확인에서 취소하면 이력이 보존되어야 한다.
10. 차단기의 ‘설치 구간 → 교체 등록’으로 새 구간을 생성하면 현재 0건·이전 구간 건수가 유지되는지 확인한다. ‘날짜 정정’은 건수를 초기화하지 않는다. 빈 현재 구간 삭제 시 복원 대상과 건수가 안내되어야 한다.
11. 소속 차단기가 있는 분전함과 트립 이력이 있는 차단기는 삭제가 제한되는지 확인한다. 글자 배율·키보드·뒤로 가기 및 기존 데이터 업데이트 보존도 확인한다.

## Windows 개발

```powershell
python scripts/bootstrap_tools.py
. ./scripts/use_local_tools.ps1
& "$env:ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager.bat" "--sdk_root=$env:ANDROID_HOME" 'platform-tools' 'platforms;android-36' 'build-tools;36.0.0'
powershell -NoProfile -File scripts/doctor.ps1
powershell -NoProfile -File scripts/build_and_test.ps1
```

SDK 설치 중에는 표시되는 라이선스를 확인한다. bootstrap은 JDK·SDK 명령줄 도구·공식 Gradle Wrapper를 다운로드하고 체크섬을 검사한다. 시스템 환경변수는 변경하지 않는다. `.tools`와 `local.properties`는 커밋하지 않는다.

Android Studio에서는 루트 폴더를 열고 Gradle JDK를 `.tools/jdk`, SDK 경로를 `.tools/android-sdk`로 지정한다. SDK 경로는 필요하면 `local.properties`에 `sdk.dir=C\:/.../triptracker/.tools/android-sdk` 형식으로 설정한다.

## 검증

```powershell
powershell -NoProfile -File scripts/build_and_test.ps1
# 에뮬레이터 또는 실제 기기가 연결된 경우
powershell -NoProfile -File scripts/build_and_test.ps1 -Device
```

Git Bash에서는 `bash scripts/build_and_test.sh`를 실행한다. Linux/macOS는 별도로 호환 JDK와 Android SDK를 설치한 후 같은 Bash 명령을 사용한다. Python bootstrap은 Windows용이다.

Room 로컬 테스트는 Robolectric 4.16 / API 28 런타임을 사용한다. 최초 테스트 실행 시 Gradle이 테스트 런타임을 다운로드하며 `.tools`에 보관한다. 실제 기기 테스트와는 별개다.

## 문서

- [확정 요구사항](docs/requirements.md)
- [개발 환경과 검증 상태](docs/development.md)
- [구현 작업 목록](docs/implementation-plan.md)
- [DB 상세 설계](docs/data-model.md)
- [검증 계획](docs/test-plan.md)
- [화면 디자인](docs/ui-design.md)

## 기본 데이터

사용자 제공 엑셀의 분전함마스터 164개와 차단기마스터 171개를 앱에 동봉한다. 과거 이력 시트는 사용하지 않으며 새 차단기는 트립 0건으로 시작한다. 최초 적용 후 사용자 수정·삭제를 유지하고 기존 앱 데이터도 보존한다. [변환 및 적용 방식](docs/excel-import-review.md).

마스터 목록은 콤팩트하게 표시하며 점 세 개 메뉴에서 상세 보기·수정·삭제·설치 구간을 이용한다. 검색·필터를 눌러 조건을 펼친다. 미사용 초기 SPARE는 제외한다.

분전함 항목을 누르면 해당 분전함의 차단기를 바로 등록할 수 있다. 차단기 등록·수정은 관 → 층 → 분전함 순서로 선택하며, 검색도 관·층에 맞는 분전함 목록을 제공한다. 조회하면 필터가 접히고 공통 관·층은 결과 위에 한 번 표시한다.
