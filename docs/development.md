# 개발 하네스 및 도구

개발 대상은 Android 우선이며 iOS는 후속 확장 계획이다. 단일 기기 저장·조회를 기준으로 한 기술 스택 비교와 구현 순서는 [앱 개발 계획안](app-plan.md)을 참고한다.

[DB 상세 설계](data-model.md)를 바탕으로 최초/기존 트립 등록·검색·상세·횟수 조회와 트립 수정·단일/복수 삭제를 구현했다. Compose 화면은 ViewModel의 불변 UI 상태와 StateFlow를 관찰하며, 애플리케이션 단위 Room DB를 사용한다. 교체 구간 관리는 [구현 작업 목록](implementation-plan.md)의 후속 작업이다.

## 현재 구성

프로젝트 로컬 JDK·SDK를 설치하고 Android 앱과 테스트 의존성을 구성했다. 상세 검증 결과는 아래 검증 상태에 기록한다.

| 구성 | 역할 | 상태 |
| --- | --- | --- |
| Codex + 루트 AGENTS.md | 요구사항 확인 → 테스트 → 구현 → 검증 작업 규칙 | 기존 지침 유지, 보충 문서 연결 |
| PowerShell | Windows 환경 진단 및 검증 진입점 | scripts/doctor.ps1, scripts/build_and_test.ps1 |
| Bash | Linux/macOS 및 향후 CI 검증 진입점 | scripts/build_and_test.sh |
| Git / EditorConfig | 변경 검토, 인코딩·줄바꿈 통일 | 설정 완료 |
| Android SDK | 컴파일·패키징·기기 연결 | .tools/android-sdk에 API 36, Build-Tools 36.0.0, ADB 설치 |
| Android Studio / Emulator | 미리보기·기기 테스트 | 별도 설치 및 기기 연결 필요 |
| JDK | Gradle 실행 | .tools/jdk에 Temurin 17 설치, 시스템 Java 11 설정 유지 |
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

Debug APK: `app/build/outputs/apk/debug/app-debug.apk` (0.4.0, versionCode 4). 분전함 현황·차단기별 펼치기·선택 모드와 미니멀 디자인을 적용했다. 0.2.0은 사용자 확인을 받았으며 새 기능의 기기 검증은 별도다. 기존 앱 위에 업데이트 설치할 수 있고 Room 스키마는 v1을 유지한다.

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
