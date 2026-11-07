#!/usr/bin/env bash
# Decides where each upstream DitchOoM library comes from, and builds the ones that come from source.
# Driven by .github/actions/upstream-from-source; see that action for the inputs.
#
#   upstream-from-source.sh plan    writes build/key/paths to $GITHUB_OUTPUT and a step summary
#   upstream-from-source.sh build   publishes every planned library into ~/.m2 at the catalog version
#
# A library is built from source when its ref input is set, or (auto mode) when the version the
# catalog pins is not on Maven Central yet; .github/upstream-refs then names the ref to build.
# Central mode (the release) builds nothing and fails unless everything resolves from Central.
set -euo pipefail

CATALOG=gradle/libs.versions.toml
REFS_FILE=.github/upstream-refs
CENTRAL=https://repo1.maven.org/maven2/com/ditchoom
M2="$HOME/.m2/repository/com/ditchoom"
LIBS=(socket websocket)

# The artifact mqtt consumes from each library; its POM proves the version is published.
artifact() {
  case "$1" in
    buffer) echo buffer ;;
    socket) echo socket ;;
    websocket) echo websocket-tcp ;;
  esac
}

catalog_version() {
  sed -n "s/^$1 = \"\\(.*\\)\"$/\\1/p" "$CATALOG"
}

on_central() {
  local a
  a=$(artifact "$1")
  curl -sfIo /dev/null "$CENTRAL/$a/$2/$a-$2.pom"
}

# "<lib> <version> <ref>" lines; the ref applies only while the catalog pins that version.
default_ref() {
  [ -f "$REFS_FILE" ] || return 0
  awk -v lib="$1" -v ver="$2" '$1 == lib && $2 == ver { print $3 }' "$REFS_FILE"
}

resolve_sha() {
  local lib=$1 ref=$2 sha
  if [[ "$ref" =~ ^[0-9a-f]{40}$ ]]; then
    echo "$ref"
    return
  fi
  sha=$(git ls-remote "https://github.com/DitchOoM/$lib.git" "refs/heads/$ref" "refs/tags/$ref" | head -1 | cut -f1)
  [ -n "$sha" ] || { echo "::error::$ref is not a branch, tag or full commit of DitchOoM/$lib" >&2; exit 1; }
  echo "$sha"
}

ref_input() {
  case "$1" in
    socket) echo "${SOCKET_REF:-}" ;;
    websocket) echo "${WEBSOCKET_REF:-}" ;;
  esac
}

plan_central() {
  local lib version failed=0
  for lib in buffer "${LIBS[@]}"; do
    version=$(catalog_version "$lib")
    if [ -n "$(ref_input "$lib")" ]; then
      echo "::error::a release resolves $lib from Maven Central only; drop the $lib ref"
      failed=1
    elif ! on_central "$lib" "$version"; then
      echo "::error::a release needs com.ditchoom:$(artifact "$lib"):$version on Maven Central, and it is not there"
      failed=1
    fi
  done
  if env | grep -q '^ORG_GRADLE_PROJECT_ditchoom[A-Za-z]*Version='; then
    echo "::error::a release cannot override a sibling version ($(env | grep -o '^ORG_GRADLE_PROJECT_ditchoom[A-Za-z]*Version' | tr '\n' ' '))"
    failed=1
  fi
  if [ -d "$M2" ]; then
    echo "::error::$M2 exists before the release build, so mavenLocal could shadow Central"
    failed=1
  fi
  [ "$failed" -eq 0 ] || exit 1
  printf '## Upstream libraries\nRelease: buffer, socket and websocket all resolve from Maven Central.\n' >> "$GITHUB_STEP_SUMMARY"
}

plan_auto() {
  local lib version ref sha build="" key="upstream-${RUNNER_OS}-${RUNNER_ARCH}" paths=""
  local summary="## Upstream libraries\n| library | version | source |\n|---|---|---|\n"
  for lib in "${LIBS[@]}"; do
    version=$(catalog_version "$lib")
    ref=$(ref_input "$lib")
    if [ -z "$ref" ]; then
      if on_central "$lib" "$version"; then
        summary+="| $lib | $version | Maven Central |\n"
        continue
      fi
      ref=$(default_ref "$lib" "$version")
      [ -n "$ref" ] || {
        echo "::error::the catalog pins $lib $version, which is not on Maven Central; name the ref to build it from in $REFS_FILE or with the $lib-ref input"
        exit 1
      }
    fi
    sha=$(resolve_sha "$lib" "$ref")
    build+="$lib=$sha=$version "
    key+="-$lib-$version-$sha"
    case "$lib" in
      socket) paths+="$M2/socket/$version"$'\n'"$M2/socket-*/$version"$'\n'"$M2/network-monitor*/$version"$'\n' ;;
      websocket) paths+="$M2/websocket*/$version"$'\n' ;;
    esac
    summary+="| $lib | $version | source: \`$ref\` @ \`${sha:0:9}\` |\n"
  done
  {
    echo "build=${build% }"
    echo "key=$key"
    echo "paths<<UPSTREAM_PATHS"
    printf '%s' "$paths"
    echo "UPSTREAM_PATHS"
  } >> "$GITHUB_OUTPUT"
  printf '%b' "$summary" >> "$GITHUB_STEP_SUMMARY"
}

# websocket reads HostManager.host at configuration time, which throws on a linux aarch64 host.
# There it configures no native target anyway (only an Apple or linux x64 host adds them), so the
# arm64 build names that host directly.
is_linux_arm64() {
  [ "$(uname -s)" = Linux ] && [ "$(uname -m)" = aarch64 ]
}

patch_linux_arm64_host() {
  grep -rl --include=build.gradle.kts 'HostManager\.host$' . | while read -r f; do
    sed -i 's/org\.jetbrains\.kotlin\.konan\.target\.HostManager\.host$/org.jetbrains.kotlin.konan.target.KonanTarget.LINUX_ARM64/' "$f"
  done
}

build_one() {
  local lib=$1 sha=$2 version=$3 dir="$RUNNER_TEMP/upstream-$1" pom
  local init="$GITHUB_WORKSPACE/.github/upstream-pin-version.init.gradle.kts"
  rm -rf "$dir"
  git init -q "$dir"
  git -C "$dir" fetch -q --depth 1 "https://github.com/DitchOoM/$lib.git" "$sha"
  git -C "$dir" checkout -q FETCH_HEAD
  (
    cd "$dir"
    # GITHUB_REPOSITORY makes the build a release-style (non-snapshot) one; a non-main GITHUB_REF
    # keeps signing and Central publishing off whatever branch mqtt runs on.
    export GITHUB_REPOSITORY=local/local GITHUB_REF=refs/heads/upstream-from-source LOCAL_SINGLE_HOST_PUBLISH=true
    local gradle=(./gradlew -x test -Pversion="$version" -PupstreamVersion="$version" -I "$init" --no-scan --no-configuration-cache)
    case "$lib" in
      socket)
        # socket's linuxArm64 target cross-compiles liburing on a linux x64 host.
        if [ "$(uname -s)" = Linux ] && [ "$(uname -m)" = x86_64 ] && ! command -v aarch64-linux-gnu-gcc > /dev/null; then
          sudo apt-get update -q && sudo apt-get install -yq gcc-aarch64-linux-gnu
        fi
        "${gradle[@]}" :publishToMavenLocal :network-monitor:publishToMavenLocal
        ;;
      websocket)
        if is_linux_arm64; then
          patch_linux_arm64_host
          # Android's build tools are x86_64-only, so this host publishes only what its leg resolves.
          "${gradle[@]}" publishJvmPublicationToMavenLocal publishJsPublicationToMavenLocal publishKotlinMultiplatformPublicationToMavenLocal
        else
          "${gradle[@]}" publishToMavenLocal
        fi
        ;;
    esac
  )
  rm -rf "$dir"
  pom="$M2/$(artifact "$lib")/$version/$(artifact "$lib")-$version.pom"
  [ -f "$pom" ] || { echo "::error::building $lib at ${sha:0:9} did not publish $(artifact "$lib") $version to mavenLocal"; exit 1; }
}

build() {
  local entry lib sha version
  : "${BUILD:?build needs BUILD, the build output of the plan step}"
  for entry in $BUILD; do
    IFS='=' read -r lib sha version <<< "$entry"
    echo "::group::$lib $version from ${sha:0:9}"
    build_one "$lib" "$sha" "$version"
    echo "::endgroup::"
  done
}

case "${1:-}" in
  plan)
    case "${MODE:-auto}" in
      auto) plan_auto ;;
      central) plan_central ;;
      *) echo "::error::mode must be auto or central, not ${MODE}"; exit 1 ;;
    esac
    ;;
  build) build ;;
  *) echo "usage: $0 plan|build" >&2; exit 2 ;;
esac
