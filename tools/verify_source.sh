#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
echo '===== Go regression ====='
(cd server && go test -race -count=1 ./...)
echo '===== Java core ====='
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
javac -encoding UTF-8 -d "$OUT" android/app/src/main/java/com/familyguard/app/{AccuracyPolicy,CoordinateReference,ServerAddress}.java android/tests/{AccuracyPolicySelfTest,CoordinateReferenceSelfTest,ServerAddressSelfTest}.java
for name in AccuracyPolicySelfTest CoordinateReferenceSelfTest ServerAddressSelfTest; do
    java -cp "$OUT" com.familyguard.app."$name"
done
javac -encoding UTF-8 -d "$OUT" tools/JavaSyntaxCheck.java
mapfile -t sources < <(find android/app/src/main/java -name '*.java' -print | sort)
java -cp "$OUT" JavaSyntaxCheck "${sources[@]}"
echo 'Note: full assembleDebug requires Android SDK and Gradle dependencies.'
