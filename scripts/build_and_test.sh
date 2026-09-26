#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -f .tools/jdk/bin/java.exe || -f .tools/jdk/bin/java ]]; then
  export JAVA_HOME="$PWD/.tools/jdk"
fi
if [[ -d .tools/android-sdk ]]; then
  export ANDROID_HOME="$PWD/.tools/android-sdk"
fi
export GRADLE_USER_HOME="$PWD/.tools/gradle-user-home"
export ANDROID_USER_HOME="$PWD/.tools/android-user-home"
if command -v cygpath >/dev/null 2>&1; then
  [[ -z "${JAVA_HOME:-}" ]] || export JAVA_HOME="$(cygpath -m "$JAVA_HOME")"
  [[ -z "${ANDROID_HOME:-}" ]] || export ANDROID_HOME="$(cygpath -m "$ANDROID_HOME")"
  export GRADLE_USER_HOME="$(cygpath -m "$GRADLE_USER_HOME")"
  export ANDROID_USER_HOME="$(cygpath -m "$ANDROID_USER_HOME")"
fi
if [[ ! -f gradlew ]]; then
  echo 'Gradle Wrapper missing. Create the Android project first; see docs/development.md.' >&2
  exit 1
fi
tasks=(:app:testDebugUnitTest :app:lintDebug :app:assembleDebug)
if [[ "${1:-}" == '--device' ]]; then
  tasks+=(:app:connectedDebugAndroidTest)
elif [[ $# -gt 0 ]]; then
  echo 'Usage: bash scripts/build_and_test.sh [--device]' >&2
  exit 2
fi
bash ./gradlew --console=plain "${tasks[@]}"
