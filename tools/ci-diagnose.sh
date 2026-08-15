#!/usr/bin/env bash
set -Eeuo pipefail
mkdir -p build/ci-diagnostics
exec > >(tee build/ci-diagnostics/diagnostic.log) 2>&1

log() { printf '\n===== %s =====\n' "$1"; }
log 'environment'
java -version || true
gradle --version || true
printf 'PWD=%s\n' "$PWD"

log 'project files'
find . -maxdepth 3 -type f \( -name '*.kts' -o -name 'gradle.properties' -o -name 'AndroidManifest.xml' \) -print | sort

log 'dependency and plugin model'
gradle --no-daemon --stacktrace --warning-mode all :app:dependencies --configuration debugCompileClasspath > build/ci-diagnostics/dependencies.txt

log 'kotlin and android compile'
set +e
gradle --no-daemon --stacktrace --info --warning-mode all :app:compileDebugKotlin 2>&1 | tee build/ci-diagnostics/compileDebugKotlin.log
status=${PIPESTATUS[0]}
set -e

if (( status != 0 )); then
  log 'compiler diagnostics annotations'
  # Surface complete compiler diagnostic lines in GitHub annotations so failures remain
  # diagnosable even when raw Actions logs or artifact downloads are unavailable.
  grep -E '(^|[[:space:]])(e: |error: |Caused by:)' build/ci-diagnostics/compileDebugKotlin.log \
    > build/ci-diagnostics/compiler-errors.txt || true
  if [[ ! -s build/ci-diagnostics/compiler-errors.txt ]]; then
    tail -n 200 build/ci-diagnostics/compileDebugKotlin.log \
      > build/ci-diagnostics/compiler-errors.txt
  fi
  while IFS= read -r diagnostic; do
    escaped=${diagnostic//'%'/'%25'}
    escaped=${escaped//$'\r'/'%0D'}
    escaped=${escaped//$'\n'/'%0A'}
    printf '::error title=Kotlin compiler diagnostic::%s\n' "$escaped"
  done < build/ci-diagnostics/compiler-errors.txt
fi

log 'generated reports'
find app/build -maxdepth 5 -type f \( -name '*.xml' -o -name '*.html' -o -name '*.txt' \) -print 2>/dev/null | sort > build/ci-diagnostics/generated-reports.txt || true
exit "$status"
