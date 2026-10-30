#!/usr/bin/env bash
#
# Cut a Lossless release.
#
# Lossless's version is three dotted numbers that only ever go up — 0.1.0,
# 0.1.1, 0.1.2, … — and a versionCode that goes up by one with each release,
# because that is the number Android compares before it will let an APK replace
# an installed one. Two numbers that must agree, in a file a human edits, is a
# mistake waiting to happen; this script is the only thing that should write
# them.
#
#   tools/bump-version.sh            bump the patch: 0.1.0 -> 0.1.1
#   tools/bump-version.sh minor      bump the minor: 0.1.4 -> 0.2.0
#   tools/bump-version.sh major      bump the major: 0.4.2 -> 1.0.0
#   tools/bump-version.sh --print    show the current version and exit
#
# It edits the two `extra[…]` lines in the root build.gradle.kts, changes
# nothing else, and stops. Committing, tagging and pushing are yours to do —
# this only guarantees the numbers move together:
#
#   tools/bump-version.sh minor
#   git commit -am "Release 0.2.0"
#   git tag v0.2.0
#   git push && git push --tags
#
# The tag is what the in-app updater reads (it strips the leading "v"), and
# pushing it is what starts the release workflow that builds the APK.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRADLE_FILE="$ROOT/build.gradle.kts"

if [ ! -f "$GRADLE_FILE" ]; then
    echo "error: no build.gradle.kts at $GRADLE_FILE" >&2
    echo "       run this from inside the Lossless checkout" >&2
    exit 1
fi

current_code="$(sed -n 's/^extra\["losslessVersionCode"\] = \([0-9]*\).*/\1/p' "$GRADLE_FILE")"
current_name="$(sed -n 's/^extra\["losslessVersionName"\] = "\([^"]*\)".*/\1/p' "$GRADLE_FILE")"

if [ -z "$current_code" ] || [ -z "$current_name" ]; then
    echo "error: could not read the version out of $GRADLE_FILE" >&2
    echo "       expected the lines:" >&2
    echo '         extra["losslessVersionCode"] = 1' >&2
    echo '         extra["losslessVersionName"] = "0.1.0"' >&2
    exit 1
fi

if [ "${1:-}" = "--print" ] || [ "${1:-}" = "-p" ]; then
    echo "$current_name ($current_code)"
    exit 0
fi

part="${1:-patch}"
IFS='.' read -r major minor patch <<< "$current_name"

case "$part" in
    major) major=$((major + 1)); minor=0; patch=0 ;;
    minor) minor=$((minor + 1)); patch=0 ;;
    patch) patch=$((patch + 1)) ;;
    *)
        echo "error: unknown part '$part' — use patch, minor or major" >&2
        exit 1
        ;;
esac

new_name="$major.$minor.$patch"
new_code=$((current_code + 1))

# Both lines are rewritten in place, and the sed is anchored to the start of the
# line so the long comment above them — which also mentions a version — is left
# alone.
sed -i.bak \
    -e "s/^extra\[\"losslessVersionCode\"\] = [0-9]*/extra[\"losslessVersionCode\"] = $new_code/" \
    -e "s/^extra\[\"losslessVersionName\"\] = \"[^\"]*\"/extra[\"losslessVersionName\"] = \"$new_name\"/" \
    "$GRADLE_FILE"
rm -f "$GRADLE_FILE.bak"

echo "Lossless $current_name -> $new_name   (versionCode $current_code -> $new_code)"
echo
echo "Next, once the change is built and verified:"
echo "  git commit -am \"Release $new_name\""
echo "  git tag v$new_name"
echo "  git push && git push --tags"
