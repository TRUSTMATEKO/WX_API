#!/bin/bash

set -Eeuo pipefail

PROC_NAME="WX_API"
MAIN_CLASS="kr.tx24.inet.server.INetServer"
BASE_PACKAGE="kr.tx24.wx.api.ctl"

BIN_DIR="$(cd "$(dirname "$0")" && pwd)"
BASE_DIR="$(cd "${BIN_DIR}/.." && pwd)"
CONF_DIR="${BASE_DIR}/conf"
LOG_DIR="${BASE_DIR}/logs"

FAT_JAR="${BASE_DIR}/${PROC_NAME}.jar"
PID_FILE="${BIN_DIR}/${PROC_NAME}.pid"
CONSOLE_LOG="${LOG_DIR}/console.log"
APP_LOG="${LOG_DIR}/root.txt"

REDIS_HOST="192.168.78.10:6379/0"
LOG_REDIS_HOST="192.168.78.10:6379/0"
LOG_LEVEL="DEBUG"
LOG_MAXDAY="90"
LOGGER="false,true,true"
JVM_REPORT="true"
NLB_CONFIG="/home/git/nlb.json"
DB_CONFIG="${CONF_DIR}/db.json"
EXECUTOR_THRESHOLD_WARN="true"
EXECUTOR_THRESHOLD_MILLIES="5000"

JVM_OPTS=(
    "-Dfile.encoding=UTF-8"
    "-Djava.net.preferIPv4Stack=true"
    "-Dio.lettuce.core.iouring=false"
    "-Xss1m"
    "-Xms512m"
    "-Xmx1g"
    "-XX:+UseG1GC"
    "-XX:MaxGCPauseMillis=200"
    "-XX:+HeapDumpOnOutOfMemoryError"
    "-XX:HeapDumpPath=${LOG_DIR}"
    "-XX:+ExitOnOutOfMemoryError"
    "-XX:ErrorFile=${LOG_DIR}/hs_err_pid%p.log"
    "-Xlog:gc*,safepoint:file=${LOG_DIR}/gc.log:time,uptime,level,tags:filecount=10,filesize=20M"
)

APP_OPTS=(
    "-DPROC=${PROC_NAME}"
    "-DCONF=${CONF_DIR}"
    "-DLOG_DIR=${LOG_DIR}"
    "-DLOG_LEVEL=${LOG_LEVEL}"
    "-DLOG_MAXDAY=${LOG_MAXDAY}"
    "-DLOGGER=${LOGGER}"
    "-DJVM_REPORT=${JVM_REPORT}"
    "-DREDIS=${REDIS_HOST}"
    "-DLOG_REDIS=${LOG_REDIS_HOST}"
    "-DNLB=${NLB_CONFIG}"
    "-DDBSET=${DB_CONFIG}"
    "-Dasync.threshold.warn=${EXECUTOR_THRESHOLD_WARN}"
    "-Dasync.threshold.millies=${EXECUTOR_THRESHOLD_MILLIES}"
    "-DbasePackage=${BASE_PACKAGE}"
)

fail() {
    echo "Error: $1" >&2
    exit 1
}

resolve_java() {
    if [ -n "${JAVA:-}" ]; then
        return
    fi

    if [ -n "${JAVA_HOME:-}" ]; then
        JAVA="${JAVA_HOME}/bin/java"
    else
        JAVA="java"
    fi
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

validate_environment() {
    local java_version

    if [[ "$JAVA" == */* ]]; then
        [ -x "$JAVA" ] || fail "Java executable is unavailable: ${JAVA}"
    elif ! command -v "$JAVA" >/dev/null 2>&1; then
        fail "Java command was not found: ${JAVA}"
    fi

    java_version=$("$JAVA" -version 2>&1) || fail "Unable to read the Java version: ${JAVA}"
    grep -q 'version "17\.' <<< "$java_version" || fail "Java 17 is required: $(head -n 1 <<< "$java_version")"
    grep -qi 'Corretto' <<< "$java_version" || fail "Amazon Corretto 17 is required: $(head -n 1 <<< "$java_version")"
    command -v pgrep >/dev/null 2>&1 || fail "Required command was not found: pgrep"
    [ -f "$FAT_JAR" ] || fail "Fat JAR was not found: ${FAT_JAR}"

    mkdir -p "$LOG_DIR"
}

check_process() {
    local pid
    local running_processes

    if [ -f "$PID_FILE" ]; then
        pid=$(cat "$PID_FILE" 2>/dev/null || true)
        if [[ "$pid" =~ ^[1-9][0-9]*$ ]] && is_running "$pid"; then
            is_expected_process "$pid" && fail "${PROC_NAME} is already running. PID=${pid}"
            fail "PID ${pid} belongs to another process: ${PID_FILE}"
        fi

        echo "Removing stale PID file: ${PID_FILE}"
        rm -f -- "$PID_FILE"
    fi

    running_processes=$(find_running_processes)
    if [ -n "$running_processes" ]; then
        echo "$running_processes" >&2
        fail "${PROC_NAME} is running without the expected PID file."
    fi
}

print_options() {
    echo
    echo "Start ${PROC_NAME}"
    echo "  - Java       : $("$JAVA" -version 2>&1 | head -n 1)"
    echo "  - Main Class : ${MAIN_CLASS}"
    echo "  - Fat JAR    : ${FAT_JAR}"
    echo "  - PID File   : ${PID_FILE}"
    echo "  - Console Log: ${CONSOLE_LOG}"
}

start_application() {
    local pid
    local second
    local pid_temp="${PID_FILE}.tmp.$$"

    cd "$BIN_DIR"
    nohup "$JAVA" \
        "${JVM_OPTS[@]}" \
        "${APP_OPTS[@]}" \
        -cp "$FAT_JAR" \
        "$MAIN_CLASS" \
        >>"$CONSOLE_LOG" 2>&1 &

    pid=$!
    if ! printf '%s\n' "$pid" > "$pid_temp" || ! mv -f "$pid_temp" "$PID_FILE"; then
        kill -TERM "$pid" 2>/dev/null || true
        rm -f -- "$pid_temp"
        fail "Unable to create PID file: ${PID_FILE}"
    fi

    for second in 1 2 3; do
        sleep 1
        if ! is_running "$pid"; then
            rm -f -- "$PID_FILE"
            echo "${PROC_NAME} failed to start. Console log:" >&2
            tail -n 50 "$CONSOLE_LOG" >&2 || true
            return 1
        fi
    done

    echo "${PROC_NAME} started. PID=${pid}"
}

main() {
    local show_log=false

    case "${1:-}" in
        "")
            ;;
        log)
            show_log=true
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

    resolve_java
    validate_environment
    check_process
    print_options
    start_application

    if [ "$show_log" = true ]; then
        echo "Press Ctrl+C to stop following the log; ${PROC_NAME} will keep running: ${APP_LOG}"
        tail --retry -F "$APP_LOG"
    fi
}

main "$@"
