#!/usr/bin/env bash
# Cuts a release: sets VERSION in framework/Build.java, builds the release assets (all tests must be green),
# commits, and creates the tag v<version>. Pushing the tag starts .github/workflows/release.yml,
# which builds again from the tag and publishes a GitHub release with the jars and SHA256SUMS.
#
#   scripts/release.sh 0.2.0          prepare locally; prints the push command
#   scripts/release.sh 0.2.0 --push   also push main and the tag
#
# Versions (0.x while APIs still settle): MINOR for a new module or a breaking change,
# PATCH for fixes. A suffix (1.0.0-rc.1) makes a pre-release.
set -euo pipefail
cd "$(dirname "$0")/.."

version=${1:-}; push=${2:-}
semver='^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-[0-9A-Za-z]+(\.[0-9A-Za-z]+)*)?$'
if [[ ! "$version" =~ $semver || ( -n "$push" && "$push" != "--push" ) ]]; then
  echo "usage: scripts/release.sh <version> [--push]   e.g. scripts/release.sh 0.2.0" >&2; exit 1
fi
tag="v$version"

[[ "$(git branch --show-current)" == "main" ]] || { echo "Release from main (now on $(git branch --show-current))." >&2; exit 1; }
[[ -z "$(git status --porcelain)" ]] || { echo "The working tree is not clean; commit or stash first." >&2; exit 1; }
! git rev-parse -q --verify "refs/tags/$tag" >/dev/null || { echo "Tag $tag already exists." >&2; exit 1; }
# The build wipes framework/out/; doing that under a running loop gives its builder a false red.
! pgrep -f scripts/gauntlet.sh >/dev/null || { echo "The gauntlet loop is running; stop it first (scripts/gauntlet.sh stop)." >&2; exit 1; }

# VERSION lives in Build.java: guardrails allows only .java and .md files in framework/.
sed -i -E 's/^(static final String VERSION = ")[^"]*(";)$/\1'"$version"'\2/' framework/Build.java
actual=$(cd framework && java Build.java version 2>/dev/null)
[[ "$actual" == "$version" ]] || { echo "Could not set VERSION in framework/Build.java (got '$actual')." >&2; exit 1; }
(cd framework && java Build.java release)

if [[ -n "$(git status --porcelain framework/Build.java)" ]]; then
  git add framework/Build.java
  git commit -q -m "chore(release): $tag"
fi
git tag -a "$tag" -m "Spola $version"
echo "Tagged $tag. Assets for a local check: framework/out/release/"

if [[ "$push" == "--push" ]]; then
  git push origin main "$tag"
  echo "Pushed. The release workflow publishes $tag on GitHub."
else
  echo "Publish with: git push origin main $tag"
fi
