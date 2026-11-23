#!/bin/bash

set -Eeuo pipefail

PROC_NAME="WX_API"
BIN_DIR="$(cd "$(dirname "$0")" && pwd)"
STOP_SCRIPT="${BIN_DIR}/stop.sh"
START_SCRIPT="${BIN_DIR}/start.sh"

fail() {
    echo "Error: $1" >&2
    exit 1
}

main() {
    case "${1:-}" in
        ""|log)
            ;;
        -h|--help)
            echo "Usage: $0 [log]"
            exit 0
            ;;
        *)
            fail "Unsupported argument: $1 (Usage: $0 [log])"
            ;;
    esac
    [ "$#" -le 1 ] || fail "Only one argument is allowed. (Usage: $0 [log])"
    [ -x "$STOP_SCRIPT" ] || fail "stop.sh is missing or not executable: ${STOP_SCRIPT}"
    [ -x "$START_SCRIPT" ] || fail "start.sh is missing or not executable: ${START_SCRIPT}"

    echo "Restart ${PROC_NAME}"
    "$STOP_SCRIPT"
    "$START_SCRIPT" "$@"
}

main "$@"
