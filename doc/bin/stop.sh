#!/bin/bash

# 오류가 발생하면 스크립트를 즉시 종료한다.
set -e

# stop.sh가 위치한 배포 루트를 기준으로 경로를 설정한다.
BASE_DIR="$(cd "$(dirname "$0")" && pwd)"

# 입력된 프로세스명이 없으면 배포 루트 디렉터리명을 사용한다.
PROC_NAME="${PROC_NAME:-$(basename "$BASE_DIR")}"
PID_FILE="${BASE_DIR}/${PROC_NAME}.pid"

# 종료 설정
GRACEFUL_TIMEOUT=10
FORCE_SHUTDOWN=false

# 사용 방법을 출력한다.
usage() {
    cat <<EOF
사용법: $0 [옵션]

옵션:
  -f, --force         즉시 강제 종료(SIGKILL)
  -t, --timeout 초    정상 종료 대기 시간(기본값: ${GRACEFUL_TIMEOUT}초)
  -h, --help          도움말 출력

예시:
  $0
  $0 --timeout 30
  $0 --force
  PROC_NAME=TX24_CUSTOM $0
EOF
}

# 명령행 옵션을 처리한다.
parse_arguments() {
    while [ $# -gt 0 ]; do
        case "$1" in
            -f|--force)
                FORCE_SHUTDOWN=true
                shift
                ;;
            -t|--timeout)
                if [ $# -lt 2 ] || ! [[ "$2" =~ ^[1-9][0-9]*$ ]]; then
                    echo "오류: 종료 대기 시간은 1 이상의 정수여야 합니다."
                    exit 1
                fi
                GRACEFUL_TIMEOUT="$2"
                shift 2
                ;;
            -h|--help)
                usage
                exit 0
                ;;
            *)
                echo "오류: 알 수 없는 옵션입니다: $1"
                usage
                exit 1
                ;;
        esac
    done
}

# 지정한 PID의 프로세스가 실행 중인지 확인한다.
is_running() {
    local pid="$1"
    ps -p "$pid" >/dev/null 2>&1
}

# PID가 start.sh에서 실행한 프로세스인지 확인한다.
is_target_process() {
    local pid="$1"
    local command_line
    command_line=$(ps -p "$pid" -o args= 2>/dev/null || true)
    [[ "$command_line" == *"-DPROC=${PROC_NAME}"* ]]
}

# PID 파일을 읽고 유효한 대상 프로세스인지 확인한다.
resolve_pid() {
    if [ ! -f "$PID_FILE" ]; then
        echo "${PROC_NAME}이 실행 중이지 않습니다. PID 파일 없음: ${PID_FILE}"
        exit 0
    fi

    PID=$(cat "$PID_FILE" 2>/dev/null || true)
    if ! [[ "$PID" =~ ^[1-9][0-9]*$ ]]; then
        echo "오류: PID 파일의 값이 올바르지 않습니다: ${PID_FILE}"
        rm -f "$PID_FILE"
        exit 1
    fi

    if ! is_running "$PID"; then
        echo "${PROC_NAME}이 이미 종료되었습니다. 이전 PID=${PID}"
        rm -f "$PID_FILE"
        exit 0
    fi

    if ! is_target_process "$PID"; then
        echo "오류: PID ${PID}는 ${PROC_NAME} 프로세스가 아닙니다."
        echo "안전을 위해 프로세스를 종료하지 않습니다."
        exit 1
    fi
}

# SIGTERM을 전송하고 제한 시간 동안 정상 종료를 기다린다.
graceful_shutdown() {
    echo "${PROC_NAME}에 정상 종료 신호를 전송합니다. PID=${PID}"
    kill -TERM "$PID"

    local elapsed=0
    while [ "$elapsed" -lt "$GRACEFUL_TIMEOUT" ]; do
        if ! is_running "$PID"; then
            rm -f "$PID_FILE"
            echo "정상 종료 완료: ${elapsed}초"
            return 0
        fi

        sleep 1
        elapsed=$((elapsed + 1))
    done

    echo "정상 종료 대기 시간이 초과되었습니다: ${GRACEFUL_TIMEOUT}초"
    return 1
}

# SIGKILL을 전송하여 프로세스를 강제로 종료한다.
force_shutdown() {
    echo "${PROC_NAME}을 강제로 종료합니다. PID=${PID}"
    kill -KILL "$PID"

    local elapsed=0
    while [ "$elapsed" -lt 5 ]; do
        if ! is_running "$PID"; then
            rm -f "$PID_FILE"
            echo "강제 종료 완료"
            return 0
        fi

        sleep 1
        elapsed=$((elapsed + 1))
    done

    echo "오류: 프로세스를 종료하지 못했습니다. PID=${PID}"
    return 1
}

# 종료 절차를 실행한다.
main() {
    parse_arguments "$@"
    resolve_pid

    if [ "$FORCE_SHUTDOWN" = true ]; then
        force_shutdown
        return
    fi

    if ! graceful_shutdown; then
        echo "강제 종료로 전환합니다."
        force_shutdown
    fi
}

main "$@"
