#!/usr/bin/env bash
# Shared helper for the bin/ convenience scripts. Sourced by them, not run directly.
#
# Resolves GRADLE_ROOT: the KDR workspace directory -- the Gradle build root that holds a `settings.gradle.kts`
# (or `settings.gradle`). In this project that is the (non-versioned) workspace directory holding the
# KotlinDynamicRuntime checkout, one level above the repo.
#
# It is resolved from the `KDR_WORKSPACE_DIR` environment variable if set, otherwise from the nearest ancestor
# of this bin/ directory that holds the settings file. Setting KDR_WORKSPACE_DIR in a shell therefore controls
# every script consistently -- handy with multiple checkouts of the same repository. Either way GRADLE_ROOT is
# exported back into KDR_WORKSPACE_DIR, so the launched JVM (AppPaths) keys off the identical directory.
#
# We key on the settings file rather than `gradlew` because the repo also carries a stray `gradlew` that is
# NOT a build root -- running it there fails, since the settings live one level up. Deriving the root from the
# script's own location (not the caller's working directory) is what lets the scripts run from anywhere.

# Absolute path of this file's directory (bin/), resolving a symlink to the file if there is one.
__src="${BASH_SOURCE[0]}"
if [ -L "$__src" ]; then __src="$(readlink "$__src")"; fi
__bin_dir="$(cd -P "$(dirname "$__src")" && pwd)"

if [ -n "${KDR_WORKSPACE_DIR:-}" ]; then
    GRADLE_ROOT="$(cd -P "$KDR_WORKSPACE_DIR" 2>/dev/null && pwd || true)"
    if [ -z "$GRADLE_ROOT" ]; then
        echo "bin: KDR_WORKSPACE_DIR=$KDR_WORKSPACE_DIR does not exist" >&2
        exit 1
    fi
else
    GRADLE_ROOT="$__bin_dir"
    while [ "$GRADLE_ROOT" != "/" ] &&
        [ ! -f "$GRADLE_ROOT/settings.gradle.kts" ] && [ ! -f "$GRADLE_ROOT/settings.gradle" ]; do
        GRADLE_ROOT="$(dirname "$GRADLE_ROOT")"
    done
fi
if { [ ! -f "$GRADLE_ROOT/settings.gradle.kts" ] && [ ! -f "$GRADLE_ROOT/settings.gradle" ]; } ||
    [ ! -x "$GRADLE_ROOT/gradlew" ]; then
    if [ -n "${KDR_WORKSPACE_DIR:-}" ]; then
        echo "bin: KDR_WORKSPACE_DIR=$KDR_WORKSPACE_DIR is not a Gradle build root (needs settings.gradle[.kts] + gradlew)" >&2
    else
        echo "bin: could not find a Gradle build root (settings.gradle[.kts] + gradlew) above $__bin_dir" >&2
    fi
    exit 1
fi
export KDR_WORKSPACE_DIR="$GRADLE_ROOT"

# Run the wrapper from the build root, replacing this process so Ctrl-C reaches Gradle directly (important
# for the long-running server tasks). All arguments are passed through.
run_gradle() {
    cd "$GRADLE_ROOT" && exec ./gradlew "$@"
}
