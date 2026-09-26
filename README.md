# Trip Tracker

차단기 트립 이력과 교체 구간별 발생 횟수를 관리하는 Android 앱.

현재 Debug 앱(0.4.0)은 관·층별 분전함 현황, 차단기별 펼치기 및 상세 이력 진입, 최초/기존 트립 등록, 검색, 구간별 횟수 조회, 개별 이력 수정과 선택 삭제를 지원한다. 밝은 배경과 산세리프 서체를 사용한 미니멀 디자인을 적용했다. 마지막 교체일자는 날짜 또는 ‘교체일 미상’으로 입력한다. 새 교체 등록 및 교체 구간 정정·삭제 화면은 후속 구현 대상이다.

## 기기 확인 순서

1. 기존 앱 위에 `app/build/outputs/apk/debug/app-debug.apk`를 업데이트 설치한다.
2. 시작 화면의 `Trip Tracker` 이름과 ‘분전함 현황’을 확인한다. ‘트립 등록’에서 관·층·분전함번호·차단기명·장소를 입력한다.
3. 처음 등록하는 차단기는 마지막 교체일자를 선택하거나 ‘교체일 미상’을 체크하고 저장한다.
4. 상세 조회에서 저장한 이력을 확인하고, 횟수 조회에서 1회를 확인한다.
5. 같은 차단기·같은 날짜로 다시 등록하면 기존 구간에 별도 이력으로 저장되어 2회가 된다.
6. 앱을 종료하고 다시 실행하여 이력과 횟수가 유지되는지 확인한다.
7. 상세 조회에서 이력을 열고 ‘수정’ → 장소·비고 등을 변경 → ‘수정 완료’를 선택한다. 선택한 이력만 바뀌고 이력번호·등록시각은 유지되는지 확인한다.
8. 목록의 ‘선택’을 누른 뒤 삭제할 이력의 체크박스를 선택하고 ‘선택 N건 삭제’를 누른다. 확인창에서 취소하면 보존되고, 삭제하면 선택한 이력만 없어지는지 확인한다.
9. 다른 조건으로 조회하면 삭제 선택이 해제되는지, 삭제 후 횟수가 줄고 마지막 이력 삭제 시 구간이 0회로 남는지 확인한다.
10. ‘현황’에서 관 필터 → 층별 분전함 → 차단기 펼치기 → 차단기 선택을 확인한다. 메인 횟수는 현재 구간 합계이며 차단기를 누르면 그 차단기의 전체 구간 이력이 표시된다. 같은 분전함의 `R1`과 `R10`이 섞이지 않는지 확인한다.
11. 상세·횟수 조회의 ‘검색’을 눌러 조건을 펼친다. 글자 크기를 키운 상태와 키보드가 열린 상태에서도 입력·스크롤·저장 버튼을 사용할 수 있는지 확인한다.

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
