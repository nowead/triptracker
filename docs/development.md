# 개발 하네스 및 도구

개발 대상은 Android 우선이며 iOS는 후속 확장 계획이다. 단일 기기 저장·조회를 기준으로 한 기술 스택 비교와 구현 순서는 [앱 개발 계획안](app-plan.md)을 참고한다.

[DB 상세 설계](data-model.md)의 Room v3를 바탕으로 분전함·차단기 마스터, 트립이력, 건수조회와 구간 상세 팝업, 차단기 위치조회를 구현했다. Compose의 ManagementScreen은 ManagementViewModel의 불변 상태와 StateFlow를 관찰한다. 교체·설치일 정정·빈 구간 삭제, 반복 입력 목록, 마스터 수정·삭제 제한을 포함한다. 기존 화면 테스트는 회귀 검증으로 유지한다.

## 현재 구성

프로젝트 로컬 JDK·SDK를 설치하고 Android 앱과 테스트 의존성을 구성했다. 상세 검증 결과는 아래 검증 상태에 기록한다.

| 구성 | 역할 | 상태 |
| --- | --- | --- |
| Codex + 루트 AGENTS.md | 요구사항 확인 → 테스트 → 구현 → 검증 작업 규칙 | 기존 지침 유지, 보충 문서 연결 |
| PowerShell | Windows 환경 진단 및 검증 진입점 | scripts/doctor.ps1, scripts/build_and_test.ps1 |
| Bash | Linux/macOS 및 향후 CI 검증 진입점 | scripts/build_and_test.sh |
| Git / EditorConfig | 변경 검토, 인코딩·줄바꿈 통일 | 설정 완료 |
| Android SDK | 컴파일·패키징·기기 연결 | .tools/android-sdk에 API 36, Build-Tools 36.0.0, ADB 설치 |
| Android Emulator | Mac 화면 미리보기 | Apple Silicon용 Android 36 Google APIs 이미지와 Pixel 6 가상 기기, scripts/run_emulator.sh |
| JDK | Gradle 실행 | macOS arm64용 Temurin 17.0.20.1을 .tools/jdk에 준비. 시스템 Java 설정은 변경하지 않음 |
| Gradle Wrapper | 프로젝트별 재현 가능한 빌드 | 공식 9.3.1 Wrapper 및 배포 SHA-256 고정 |

## 초기화 설정

| 설정 | 값 |
| --- | --- |
| 개발용 앱 이름 / applicationId | Trip Tracker / com.triptracker.app |
| 최소 Android / compileSdk / targetSdk | Android 8.0 (API 26) / 36 / 36 |
| AGP / Kotlin | 9.1.1 / AGP 내장 Kotlin 2.2.10, Compose Compiler 같은 버전 |
| Compose BOM / Room / KSP | 2025.12.01 / 2.8.4 / 2.3.6 |
| Room 로컬 테스트 | Robolectric 4.16 / API 28, 실제 Room 구현·SQLite 사용 |
| 빌드 캐시 / Android 사용자 파일 | .tools/gradle-user-home / .tools/android-user-home |

버전은 [libs.versions.toml](../gradle/libs.versions.toml)에 고정한다. AGP 9의 내장 Kotlin을 사용하므로 kotlin-android 플러그인은 별도 적용하지 않는다. [AGP 내장 Kotlin 공식 안내](https://developer.android.com/build/migrate-to-built-in-kotlin)

## 검증 상태

2026-09-28 **0.8.0**: `bash scripts/build_and_test.sh --device` 성공. 로컬 **83개**, Android 36 에뮬레이터 **4개** 통과, Lint 오류 0건. 분전함 선택으로 차단기 등록 화면의 관·층·부모 자동 입력, 상위 선택 변경 시 하위 해제, 맞지 않는 부모 저장 거절, 관·층별 검색 후보와 정확한 분전함 선택을 검증했다. 조회 후 필터 접기·적용 조건 요약·공통 소속 생략, 초안 변경 중 기존 결과 유지·상세 전체 소속 표시를 확인했다. 건수조회 필수 조건 안내는 필터 안에 표시해 작은 화면의 선택 버튼을 가리지 않는다. Room 스키마는 v3를 유지한다.

PC 미리보기에도 0.8.0을 설치했다. 계측 테스트 전 보관한 DB를 복원한 뒤 분전함 164개·차단기 171개·설치 구간 171개·트립 0개·적재 기록 2개의 전체 값과 FK 무결성을 대조해 보존을 확인했다. 분전함에서 등록 폼 진입 및 `1관 · B2층 · A-LE-B2` 검색 후 공통 정보가 생략된 11개 결과를 직접 조작·확인했다. 캡처: `.tools/emulator/hierarchy-child-form.png`, `.tools/emulator/hierarchy-filtered-breakers.png`. 실제 휴대폰의 업데이트 설치는 별도 확인 대상이다.

2026-09-27 **0.7.0**: `bash scripts/build_and_test.sh --device` 성공. 로컬 **78개**, Android 36 기기 **3개** 통과, Lint 오류 0건. 초기 SPARE 87개를 제외해 차단기 171개를 제공한다. 기존 원문 그대로의 미사용 SPARE 정리·수정/이력 보존과 재실행 방지, 점 세 개 메뉴로 수정·삭제·설치 구간 접근 및 삭제 취소를 검증했다. 마스터는 콤팩트 카드와 접을 수 있는 검색 조건을 제공한다. PC 에뮬레이터의 기존 DB에 업데이트 적용 후 SPARE 87개 제거, 남은 차단기 171개의 원래 값·분전함·트립 보존을 실제 DB 사본으로 대조했다. 화면 캡처: `.tools/emulator/compact-masters.png`.

2026-09-27 **0.6.0**: `bash scripts/build_and_test.sh --device` 성공. 로컬 테스트 **76개**, Android 36 에뮬레이터 테스트 **3개** 통과, Lint 오류 0건. 초기 적재 테스트는 MasterSeed 미구현으로 컴파일 실패를 확인한 뒤 구현·통과했다. `initial_masters.json`에 두 마스터만 동봉하며 [적재 규칙](excel-import-review.md)에 따라 한 번 적용한다. Room v3 마이그레이션과 기존 데이터 보존, 사용자 수정·삭제 유지, 실패 시 롤백을 검증했다. PC 미리보기 앱도 0.6.0으로 업데이트했으며 실제 DB에서 분전함 164개·차단기 258개·설치 구간 258개·트립 0개, 적용 완료 기록과 FK 무결성을 확인했다. 화면 캡처는 `.tools/emulator/initial-masters.png`다.

2026-09-27 엑셀 변환 준비 후 검증: `bash scripts/build_and_test.sh --device` 성공. 로컬 **71개**, Android 36 계측 **2개** 통과, Lint 오류 0건. 1관 12·13층 허용과 관별 범위 제한, PH까지의 마스터·집계 정렬을 추가 검증했다. 새 `floorsFor` API 미구현으로 테스트 컴파일 실패를 확인한 뒤 구현했다. 이 단계는 변환 준비 기록이며 이후 0.6.0에서 두 마스터만 적재하도록 범위를 확정했다.

2026-09-27, 0.5.0 검증: `bash scripts/build_and_test.sh` 성공. 로컬 테스트 **69개 통과**, Lint **오류 0건**(고정 의존성 버전 알림 16건), Debug APK 생성. 최초 테스트 실행은 JDK 부재로 실행하지 못했으며 도구 준비 후 다시 검증했다. 추가한 미상 설치일의 알려진 날짜 경계 테스트는 실패 확인 후 수정·통과했다.

| 신규 검증 | 결과 |
| --- | --- |
| MasterRepositoryTest | 7개 통과: 0건 마스터, 삭제 제한, 설치 구간·이력 보존, 마스터 수정, 메타데이터 검증, 트립사유 필수, 미상 날짜 경계 |
| MasterMigrationTest | 1개 통과: v1→v2 Room 스키마 검증, ID/구간/횟수/특기사항 보존, 새 필드 미입력 및 FK 확인 |
| ManagementScreenTest | 5개 통과: 큰 글자 1.3배·360dp에서 입력/오류/스크롤/메뉴 접근, 필수 조회 조건과 구간별 상세 팝업, 마스터→트립 생성, 반복 값 목록 선택, 한 건 수정 및 삭제 확인·취소 |
| 0.5.0 기기 검증 | 디자인 개선 후 `bash scripts/build_and_test.sh --device` 성공. Android 36 ARM64 에뮬레이터에서 AppLaunchTest·TripDatabaseDeviceTest 2개 통과. 실제 휴대폰 검증은 별도 |
| Mac 에뮬레이터 화면 확인 | Android Emulator 37.1.11 ARM64 / Android 36에서 0.5.0 설치·실행 성공. 분전함 마스터 첫 화면과 다섯 메뉴를 화면 캡처로 확인. 큰 글자 로컬 캡처는 app/build/reports/ui/management-large-text.png |

아래는 기존 0.4.0 검증 기록이다. 신규 13개와 기존 56개를 함께 실행했다.

| 검증 | 결과 |
| --- | --- |
| doctor.ps1 | JDK·SDK·ADB·Wrapper 확인 통과 |
| 입력 규칙 JUnit | 미구현 상태에서 11개 실패 확인 후 구현, 11개 통과 |
| Room Repository 로컬 테스트 | 29개. 저장·검색·집계·영속성과 선택 이력만 수정·구간 이동·신규 대상 생성·검증 실패·수정/삭제 롤백·0회 구간 보존 검증 |
| ViewModel 로컬 테스트 | 13개. 현황 그룹·0회·독립 갱신·정확한 차단기 진입·명시적 선택 모드와 입력·검색과 수정 초기 구간·조건 유지, 삭제 확인/취소·재조회 선택 해제·실패 후 선택 보존 검증 |
| Compose 로컬 테스트 | 3개. 현황 펼치기·차단기 상세 진입·삭제 후 현황 갱신과 최초/반복 등록 1→2회, 선택 이력 수정·복수 삭제 취소/확정·마지막 이력 삭제 후 0회 확인. 로컬 테스트 총 56개 |
| Bash 전체 검증 | 단위 테스트·Lint·Debug APK 빌드 성공 |
| APK 빌드 | 앱 APK·기기 테스트 APK 빌드 성공 |
| Lint | 오류 0건. 고정한 의존성의 새 버전 알림은 남겨 둠 |
| 실제 기기 수동 확인 | 2026-09-15 사용자 확인: Debug APK 설치·실행 성공, 시작 화면에 ‘트립트래커’ 표시 |
| 자동 계측 테스트 | AppLaunchTest·TripDatabaseDeviceTest 컴파일·테스트 APK 생성 완료. `--device` 실행은 연결 기기 없음으로 실패했으며 계측 테스트 미실행 |

현재 Debug APK: `app/build/outputs/apk/debug/app-debug.apk` (0.8.0, versionCode 8). Room 스키마는 v3이며 앱 DB 구성에 MIGRATION_1_2·MIGRATION_2_3와 초기 마스터 적재 콜백을 등록했다. 기존 데이터의 보존은 로컬 마이그레이션 테스트로 확인했으며 실제 기기의 업데이트 설치는 별도 검증 대상이다.

Robolectric의 Android 런타임은 Gradle `prepareRobolectricSdk` 작업이 `.tools/robolectric-runtime`에 준비한다. 테스트 실행 중의 자체 다운로드를 끄고 버전을 고정하여 런타임 다운로드 오류를 방지한다. 테스트 사용자 폴더와 임시 파일도 `.tools/test-user-home`, `.tools/test-temp`로 분리한다. 최초 준비에는 네트워크가 필요하다. [Robolectric 공식 설정 안내](https://robolectric.org/getting-started/)

초기 Manifest는 자동 백업·기기 이전을 사용하지 않도록 명시했다. 백업·복원은 별도 요구사항 확정 후 구현한다. Android Studio에서 수동 실행하거나 기기를 연결해 `-Device` 검증을 수행할 수 있다.

## 선택한 개발 구성

| 영역 | 도구 / 방침 | 적용 시점 |
| --- | --- | --- |
| 앱 | Kotlin, Compose, ViewModel, StateFlow, Room, Coroutines | 앱 초기화 |
| 구조 | 단일 app 모듈 내부 domain / data / presentation 분리, Clean Architecture + MVVM | 앱 초기화 |
| 의존성 | Gradle Kotlin DSL, libs.versions.toml에 호환되는 안정 버전 고정, 동적 버전 금지 | 앱 초기화 |
| 로컬 단위 테스트 | JUnit, kotlinx-coroutines-test, Fake Repository, 주입 가능한 Clock | 도메인 / ViewModel 구현 전 |
| DB 테스트 | AndroidX Test + Room 인메모리 DB, MigrationTestHelper | Room 구현 / 스키마 변경 |
| UI 테스트 | Compose UI Test + AndroidJUnitRunner | 화면 구현 |
| 정적 검사 | Android Lint | 앱 초기화 |
| 기기 진단 | ADB, Logcat, Android Studio Database Inspector | SDK 설치 후 |

앱 규모에 맞게 단일 모듈로 시작한다. 의존성 주입은 생성자 주입으로 시작하고, 필요가 생기면 별도 프레임워크를 검토한다. 현재 작업에는 추가 외부 서비스나 MCP 연결이 필요하지 않다.

## 개발 환경 준비

1. Android Studio를 설치하고 SDK Manager에서 프로젝트 compileSdk에 맞는 SDK Platform, Build-Tools, Platform-Tools를 설치한다. 기기 테스트에는 에뮬레이터 시스템 이미지 또는 USB 디버깅이 가능한 실제 기기를 준비한다.
2. 선택한 Android Gradle Plugin과 Gradle이 지원하는 JDK를 지정한다. JAVA_HOME과 Android Studio의 Gradle JDK를 일치시킨다. Java 11은 현행 Android 빌드 준비 기준에 부족하다.
3. SDK 경로를 ANDROID_HOME으로 지정한다. 일반적인 Windows 경로는 `%LOCALAPPDATA%\Android\Sdk`다. Android Studio가 생성하는 local.properties는 커밋하지 않는다.
4. 앱 초기화 시 applicationId, minSdk를 확정하고 Compose 프로젝트와 공식 Gradle Wrapper를 생성한다. Gradle 배포 체크섬을 고정하고 Wrapper JAR도 커밋한다.
5. 아래 진단과 검증 명령을 실행한다. 스크립트는 설치나 라이선스 수락을 자동으로 처리하지 않는다.

## 실행 명령

### Mac에서 앱 화면 열기

Apple Silicon Mac에서는 다음 명령으로 최신 Debug 앱을 빌드하고 별도 Android 에뮬레이터 창에서 실행한다. Android Studio를 열 필요는 없다.

```bash
bash scripts/run_emulator.sh
```

전용 가상 기기 이름은 `TripTracker_Preview`, ADB 식별자는 `emulator-5556`이다. 화면에서 마우스로 조작하며 분전함 → 차단기 → 트립 순서로 등록한다. 입력 데이터는 `.tools/avd`에 보관하므로 창을 닫았다가 같은 명령으로 다시 열어도 유지한다. 스크립트는 기존 가상 기기를 초기화하지 않는다.

가상 휴대폰 창을 닫으면 실행 스크립트도 종료된다. 이미 같은 가상 기기가 열려 있으면 재사용하여 앱을 업데이트·실행한다. `bash -n scripts/run_emulator.sh`, 실제 APK 빌드·설치·실행으로 런처 동작을 확인했다.

새 Mac에서 최초 준비할 때는 로컬 JDK·SDK 구성 후 에뮬레이터와 시스템 이미지를 설치한다. SDK 명령줄 도구는 `.tools/android-sdk/cmdline-tools/latest`에 둔다.

```bash
ANDROID_USER_HOME="$PWD/.tools/android-user-home" \
  .tools/android-sdk/cmdline-tools/latest/bin/android --sdk="$PWD/.tools/android-sdk" \
  sdk --platform=mac_arm64 install emulator
JAVA_HOME="$PWD/.tools/jdk" ANDROID_USER_HOME="$PWD/.tools/android-user-home" \
  .tools/android-sdk/cmdline-tools/latest/bin/sdkmanager \
  --sdk_root="$PWD/.tools/android-sdk" 'system-images;android-36;google_apis;arm64-v8a'
```

에뮬레이터는 `mac_arm64` 플랫폼을 명시한다. 자동 선택이 Intel 바이너리를 설치하면 ARM64 이미지가 부팅되지 않는다. 부팅 실패 로그는 `.tools/emulator/preview.log`에서 확인한다. 이 스크립트는 현재 Apple Silicon Mac용이다.

### 빌드와 테스트

```powershell
powershell -NoProfile -File .\scripts\doctor.ps1
powershell -NoProfile -File .\scripts\build_and_test.ps1
powershell -NoProfile -File .\scripts\build_and_test.ps1 -Device
```

```bash
bash scripts/build_and_test.sh
bash scripts/build_and_test.sh --device
```

기본 검증은 단위 테스트 → Lint → Debug APK 빌드다. Device 옵션은 연결된 에뮬레이터/기기에서 계측 테스트를 추가한다. 실행 실패는 비정상 종료 코드로 전달한다. Wrapper가 없을 때 성공으로 처리하지 않는다.

| 결과 | 생성 위치 (앱 생성 및 검증 실행 후) |
| --- | --- |
| 단위 테스트 보고서 | app/build/reports/tests/testDebugUnitTest/index.html |
| Lint 보고서 | app/build/reports/lint-results-debug.html |
| Debug APK | app/build/outputs/apk/debug/ |
| 계측 테스트 보고서 | app/build/reports/androidTests/connected/ |

## 작업 완료 기준

요구사항과 테스트의 대응은 [test-plan.md](test-plan.md)에서 관리한다. 코드 변경 시 실패하는 테스트 확인 후 구현하고 해당 검증을 실행한다. 문서·설정만 변경한 작업은 구문 및 실제 진입점 동작을 확인한다. 실행하지 못한 테스트는 통과로 보고하지 않는다.

## 공식 참고 자료

- [Codex 프로젝트 지침](https://learn.chatgpt.com/docs/agent-configuration/agents-md)
- [Android 빌드 JDK 선택](https://developer.android.com/build/jdks)
- [Android 테스트 기본 구조](https://developer.android.com/training/testing/fundamentals)
