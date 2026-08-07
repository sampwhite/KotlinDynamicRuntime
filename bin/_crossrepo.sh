#!/usr/bin/env bash
# Shared helper (sourced, not run) for the cross-repo safety warning: the "kd2's command silently acting on
# kd1's files" trap. Sourced by both _common.sh (the Gradle-task wrappers) and kdr-run (the Kotlin commands), so
# the check lives in one place rather than drifting between the two families.
#
# kdr_crossrepo_warn <home-workspace> <original-KDR_WORKSPACE_DIR>
#   home-workspace           the workspace the command resolved to (the build root it will act on)
#   original-KDR_WORKSPACE_DIR  the user's KDR_WORKSPACE_DIR *before* the command exported its own -- a set
#                            value means the user chose a workspace deliberately, so we stay silent
#
# Emits one line to stderr only when the current directory is inside a *different* KDR workspace than the
# command's home, and the user neither set KDR_WORKSPACE_DIR nor opted out via KDR_ALLOW_CROSSREPO. Silent
# otherwise -- including when the current directory is not inside any KDR workspace at all.
kdr_crossrepo_warn() {
    if [ -n "${KDR_ALLOW_CROSSREPO:-}" ]; then return 0; fi   # user opted out of the nag
    if [ -n "${2:-}" ]; then return 0; fi                     # explicit KDR_WORKSPACE_DIR -> deliberate
    local home="$1" cwd_ws="$PWD"
    while [ "$cwd_ws" != / ] && [ ! -f "$cwd_ws/settings.gradle.kts" ]; do
        cwd_ws="$(dirname "$cwd_ws")"
    done
    if [ ! -f "$cwd_ws/settings.gradle.kts" ]; then return 0; fi   # not inside any KDR workspace
    if [ "$cwd_ws" = "$home" ]; then return 0; fi                  # same workspace -> fine
    printf "kdr: note: running %s's commands while inside %s -- 'kdr-use %s' to switch, or set KDR_ALLOW_CROSSREPO=1\n" \
        "$home" "$cwd_ws" "$cwd_ws" >&2
    return 0
}
