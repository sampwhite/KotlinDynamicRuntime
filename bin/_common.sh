#!/usr/bin/env bash
# Shared helper for the bin/ convenience scripts. Sourced by them, not run directly.
#
# Resolves GRADLE_ROOT: the nearest ancestor of this bin/ directory that is a Gradle build root -- i.e. holds
# a `settings.gradle.kts` (or `settings.gradle`). In this project that is the (non-versioned) deployment
# directory holding the KotlinDynamicRuntime checkout, one level above the repo. We key on the settings file
# rather than `gradlew` because the repo also carries a stray `gradlew` that is NOT a build root -- running it
# there fails, since the settings live one level up. Deriving the root from the script's own location (not the
# caller's working directory) is what lets the scripts run from anywhere.

# Absolute path of this file's directory (bin/), resolving a symlink to the file if there is one.
__src="${BASH_SOURCE[0]}"
if [ -L "$__src" ]; then __src="$(readlink "$__src")"; fi
__bin_dir="$(cd -P "$(dirname "$__src")" && pwd)"

GRADLE_ROOT="$__bin_dir"
while [ "$GRADLE_ROOT" != "/" ] &&
    [ ! -f "$GRADLE_ROOT/settings.gradle.kts" ] && [ ! -f "$GRADLE_ROOT/settings.gradle" ]; do
    GRADLE_ROOT="$(dirname "$GRADLE_ROOT")"
done
if { [ ! -f "$GRADLE_ROOT/settings.gradle.kts" ] && [ ! -f "$GRADLE_ROOT/settings.gradle" ]; } ||
    [ ! -x "$GRADLE_ROOT/gradlew" ]; then
    echo "bin: could not find a Gradle build root (settings.gradle[.kts] + gradlew) above $__bin_dir" >&2
    exit 1
fi

# Run the wrapper from the build root, replacing this process so Ctrl-C reaches Gradle directly (important
# for the long-running server tasks). All arguments are passed through.
run_gradle() {
    cd "$GRADLE_ROOT" && exec ./gradlew "$@"
}
