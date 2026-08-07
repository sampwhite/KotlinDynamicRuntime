# @desc: Point this shell's PATH (and workspace) at a chosen KotlinDynamicRuntime checkout.
#
# A shell FUNCTION, not an executable command: only code running in your current shell can change that shell's
# PATH, so this file is meant to be *sourced* -- kdr-install offers to add it to your shell rc -- not run.
#
# `kdr-use <dir>` re-points the current console at a chosen checkout: it puts that checkout's bin/ on PATH
# (dropping any KDR bin already there) and clears KDR_WORKSPACE_DIR, so every kdr command self-resolves its
# workspace from that same checkout. The script you run and the files it touches then always live in one repo --
# no "kd2's script operating on kd1's files". With no argument it uses the current directory (a checkout or
# workspace root).
kdr-use() {
    local dir; dir="$(cd -P "${1:-.}" 2>/dev/null && pwd)" \
        || { printf 'kdr-use: no such directory: %s\n' "${1:-.}" >&2; return 1; }
    local bin
    if   [ -x "$dir/bin/kdr-run" ];                      then bin="$dir/bin"                       # a checkout root
    elif [ -x "$dir/KotlinDynamicRuntime/bin/kdr-run" ]; then bin="$dir/KotlinDynamicRuntime/bin"  # a workspace root
    else printf 'kdr-use: no KotlinDynamicRuntime/bin under %s\n' "$dir" >&2; return 1; fi
    # Drop any KDR bin already on PATH (same test kdr-install uses), then prepend the chosen one.
    PATH="$(printf '%s' "$PATH" | tr ':' '\n' | grep -vE '/KotlinDynamicRuntime/bin/?$' | paste -sd ':' -)"
    export PATH="$bin:$PATH"
    unset KDR_WORKSPACE_DIR   # let each command self-resolve its workspace from $bin -- no cross-checkout drift
    printf 'kdr: using %s\n' "$bin"
}
