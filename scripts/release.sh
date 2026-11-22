#!/usr/bin/env bash
# Usage: scripts/release.sh <release-notes-file>
# Builds the release APK with the dedicated release key and publishes a GitHub release v<versionName>.
set -euo pipefail

NOTES="${1:-}"
[ -n "$NOTES" ] && [ -f "$NOTES" ] || { echo "usage: $0 <release-notes-file>" >&2; exit 2; }
NOTES="$(cd "$(dirname "$NOTES")" && pwd)/$(basename "$NOTES")" # resolve before leaving the caller's directory
cd "$(dirname "$0")/.."

VER=$(sed -n 's/.*versionName = "\([^"]*\)".*/\1/p' app/build.gradle.kts | head -1)
[ -n "$VER" ] || { echo "cannot read versionName" >&2; exit 1; }
TAG="v$VER"

[ -z "$(git status --porcelain --untracked-files=no)" ] || { echo "git tree is dirty; commit first" >&2; exit 1; }
git fetch -q origin main
[ "$(git rev-parse HEAD)" = "$(git rev-parse origin/main)" ] || { echo "HEAD is not origin/main; push (or check out main) first" >&2; exit 1; }
if git rev-parse -q --verify "refs/tags/$TAG" >/dev/null || git ls-remote --exit-code --tags origin "$TAG" >/dev/null 2>&1; then
  echo "tag $TAG already exists" >&2; exit 1
fi

./gradlew :app:assembleRelease

APK=app/build/outputs/apk/release/app-release.apk
BT=$(ls -d "${ANDROID_HOME:-$HOME/Android/Sdk}"/build-tools/*/ | sort -V | tail -1)
CERTS=$("${BT%/}/apksigner" verify --print-certs "$APK")
echo "$CERTS"
# Here-string, not a pipe: with pipefail, grep -q exiting early could SIGPIPE echo and hide a match.
if grep -q "CN=Android Debug" <<<"$CERTS"; then
  echo "APK is signed with the Android debug key; set the DALAN_RELEASE_* properties in ~/.gradle/gradle.properties" >&2
  exit 1
fi

OUT=$(mktemp -d)
cp "$APK" "$OUT/dalan-$VER.apk"
gh release create "$TAG" "$OUT/dalan-$VER.apk" --target "$(git rev-parse HEAD)" --title "Dalan $VER" --notes-file "$NOTES"
echo "Released $TAG"
