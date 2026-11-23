#!/bin/bash

set -Eeuo pipefail

PROC_NAME="WX_API"
BIN_DIR="$(cd "$(dirname "$0")" && pwd)"
PID_FILE="${BIN_DIR}/${PROC_NAME}.pid"

GRACEFUL_TIMEOUT=15
FORCE_SHUTDOWN=false
PROCESS_PID=""

usage() {
    echo "Usage: $0 [-f|--force] [-t|--timeout SEC]"
}

fail() {
    echo "Error: $1" >&2
    exit 1
}

parse_arguments() {
    while [ "$#" -gt 0 ]; do
        case "$1" in
            -f|--force)
                FORCE_SHUTDOWN=true
                shift
                ;;
            -t|--timeout)
                [ "$#" -ge 2 ] || fail "A timeout value is required."
                [[ "$2" =~ ^[1-9][0-9]*$ ]] || fail "Timeout must be a positive integer: $2"
                GRACEFUL_TIMEOUT=$2
                shift 2
                ;;
            -h|--help)
                usage
                exit 0
                ;;
            *)
                fail "Unsupported argument: $1"
                ;;
        esac
    done
}

is_running() {
    local pid=$1
    local state

    kill -0 "$pid" 2>/dev/null || return 1
    state=$(ps -p "$pid" -o stat= 2>/dev/null || true)
    [[ "$state" != Z* ]]
}

is_expected_process() {
    local pid=$1
    local command_line

    command_line=$(ps -p "$pid" -o args= 2>/dev/null || true)
    [[ "$command_line" == *"-DPROC=${PROC_NAME}"* ]]
}

find_running_processes() {
    pgrep -af -- "[j]ava.*-DPROC=${PROC_NAME}([[:space:]]|$)" 2>/dev/null || true
}

confirm_not_running() {
    local running_processes

    running_processes=$(find_running_processes)
    if [ -n "$running_processes" ]; then
        echo "$running_processes" >&2
        fail "${PROC_NAME} is running without the expected PID file."
    fi

    echo "${PROC_NAME} is not running."
    return 1
}

get_pid() {
    local pid

    if [ ! -f "$PID_FILE" ]; then
        confirm_not_running
        return $?
    fi

    pid=$(cat "$PID_FILE" 2>/dev/null || true)
    if ! [[ "$pid" =~ ^[1-9][0-9]*$ ]]; then
        echo "Removing invalid PID file: ${PID_FILE}"
        rm -f -- "$PID_FILE"
        confirm_not_running
        return $?
    fi

    if ! is_running "$pid"; then
        echo "Removing stale PID file: ${PID_FILE}"
        rm -f -- "$PID_FILE"
        confirm_not_running
        return $?
    fi

    is_expected_process "$pid" || fail "PID ${pid} does not belong to ${PROC_NAME}."
    PROCESS_PID=$pid
}

wait_for_stop() {
    local pid=$1
    local timeout=$2
    local elapsed

    for ((elapsed = 1; elapsed <= timeout; elapsed++)); do
        sleep 1
        if ! is_running "$pid"; then
            rm -f -- "$PID_FILE"
            echo "${PROC_NAME} stopped. (${elapsed}s)"
            return 0
        fi
        if [ $((elapsed % 5)) -eq 0 ]; then
            echo "Waiting for shutdown... (${elapsed}/${timeout}s)"
        fi
    done
    return 1
}

force_shutdown() {
    local pid=$1

    echo "Sending SIGKILL: PID=${pid}"
    kill -KILL "$pid" 2>/dev/null || true
    wait_for_stop "$pid" 3 || fail "Unable to kill PID ${pid}."
}

graceful_shutdown() {
    local pid=$1

    echo "Sending SIGTERM: PID=${pid}"
    kill -TERM "$pid" 2>/dev/null || fail "Unable to send SIGTERM to PID ${pid}."
    if ! wait_for_stop "$pid" "$GRACEFUL_TIMEOUT"; then
        echo "Graceful shutdown timed out after ${GRACEFUL_TIMEOUT}s."
        force_shutdown "$pid"
    fi
}

main() {
    parse_arguments "$@"
    command -v pgrep >/dev/null 2>&1 || fail "Required command was not found: pgrep"

    if ! get_pid; then
        exit 0
    fi

    echo "Stop ${PROC_NAME}. PID=${PROCESS_PID}"
    if [ "$FORCE_SHUTDOWN" = true ]; then
        force_shutdown "$PROCESS_PID"
    else
        graceful_shutdown "$PROCESS_PID"
    fi
}

main "$@"
