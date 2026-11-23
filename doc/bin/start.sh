#!/bin/bash

# 오류가 발생하면 스크립트를 즉시 종료한다.
set -e

# start.sh가 위치한 배포 루트를 기준으로 경로를 설정한다.
BASE_DIR="$(cd "$(dirname "$0")" && pwd)"

# 입력된 프로세스명이 없으면 배포 루트 디렉터리명을 사용한다.
PROC_NAME="${PROC_NAME:-$(basename "$BASE_DIR")}"
MAIN_CLASS="kr.tx24.inet.server.INetServer2"
BASE_PACKAGE="kr.tx24.wx.api.ctl"

# 애플리케이션 경로 설정
CONF_DIR="${BASE_DIR}/conf"
LOG_DIR="${BASE_DIR}/logs"
FAT_JAR="${BASE_DIR}/${PROC_NAME}.jar"
PID_FILE="${BASE_DIR}/${PROC_NAME}.pid"
CONSOLE_LOG="${LOG_DIR}/console.log"
ROOT_LOG="${LOG_DIR}/root.txt"

# Java 실행 파일 설정
if [ -z "${JAVA:-}" ]; then
    if [ -n "${JAVA_HOME:-}" ]; then
        JAVA="${JAVA_HOME}/bin/java"
    else
        JAVA="java"
    fi
fi

# JDK 17용 JVM 옵션
JVM_OPTS=(
    "-Dfile.encoding=UTF-8"
    "-Djava.net.preferIPv4Stack=true"
    "-Xms256m"
    "-Xmx512m"
    "-Xlog:gc*,safepoint:file=${LOG_DIR}/gc.log:time,uptime,level,tags:filecount=5,filesize=20M"
    "-XX:+HeapDumpOnOutOfMemoryError"
    "-XX:HeapDumpPath=${LOG_DIR}"
    "-XX:+ExitOnOutOfMemoryError"
    "-XX:ErrorFile=${LOG_DIR}/hs_err_pid%p.log"
)

# 애플리케이션 시스템 속성
REDIS_HOST="112.175.48.155:6379/0"
LOG_REDIS_HOST="112.175.48.155:6379/0"
LOG_LEVEL="INFO"
LOG_MAXDAY="90"
LOGGER="false,true,true"
JVM_REPORT="true"
NLB_CONFIG="${CONF_DIR}/nlb.json"

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
    "-DbasePackage=${BASE_PACKAGE}"
)

# Java와 Fat JAR가 실행 가능한 상태인지 확인한다.
check_environment() {
    if ! command -v "$JAVA" >/dev/null 2>&1 && [ ! -x "$JAVA" ]; then
        echo "오류: Java 실행 파일을 찾을 수 없습니다: ${JAVA}"
        exit 1
    fi

    local java_major
    java_major=$("$JAVA" -version 2>&1 | awk -F '[\".]' '/version/ {print $2; exit}')
    if [ -z "$java_major" ] || [ "$java_major" -lt 17 ]; then
        echo "오류: JDK 17 이상이 필요합니다."
        "$JAVA" -version
        exit 1
    fi

    if [ ! -f "$FAT_JAR" ]; then
        echo "오류: Fat JAR를 찾을 수 없습니다: ${FAT_JAR}"
        exit 1
    fi
}

# 기존 프로세스가 실행 중인지 확인한다.
check_process() {
    if [ ! -f "$PID_FILE" ]; then
        return
    fi

    local old_pid
    old_pid=$(cat "$PID_FILE" 2>/dev/null || true)
    if [ -n "$old_pid" ] && ps -p "$old_pid" >/dev/null 2>&1; then
        echo "오류: ${PROC_NAME}이 이미 실행 중입니다. PID=${old_pid}"
        exit 1
    fi

    rm -f "$PID_FILE"
}

# 적용되는 실행 옵션을 출력한다.
print_options() {
    echo ""
    echo "${PROC_NAME} 실행 설정"
    echo "Main Class: ${MAIN_CLASS}"
    echo "Fat JAR: ${FAT_JAR}"
    echo "Java: ${JAVA}"
    echo ""
    echo "JVM 옵션:"
    printf '  %s\n' "${JVM_OPTS[@]}"
    echo ""
    echo "애플리케이션 옵션:"
    printf '  %s\n' "${APP_OPTS[@]}"
    echo ""
}

# Fat JAR를 classpath에 등록하여 애플리케이션을 실행한다.
start_application() {
    mkdir -p "$LOG_DIR"

    echo "${PROC_NAME}을 시작합니다."
    nohup "$JAVA" \
        "${JVM_OPTS[@]}" \
        "${APP_OPTS[@]}" \
        -cp "$FAT_JAR" \
        "$MAIN_CLASS" \
        >>"$CONSOLE_LOG" 2>&1 &

    local pid=$!
    echo "$pid" > "$PID_FILE"

    sleep 2
    if ps -p "$pid" >/dev/null 2>&1; then
        echo "시작 완료: PID=${pid}"
        echo "애플리케이션 로그: ${ROOT_LOG}"
        return
    fi

    echo "오류: 애플리케이션 시작에 실패했습니다."
    rm -f "$PID_FILE"
    tail -n 30 "$CONSOLE_LOG" 2>/dev/null || true
    exit 1
}

# log 인자가 지정되면 애플리케이션 로그를 계속 출력한다.
show_log() {
    if [ ! -f "$ROOT_LOG" ]; then
        echo "로그 파일이 생성되기를 기다립니다: ${ROOT_LOG}"
        while [ ! -f "$ROOT_LOG" ]; do
            sleep 1
        done
    fi

    tail -f "$ROOT_LOG"
}

# 시작 절차를 실행한다.
main() {
    check_environment
    check_process
    print_options
    start_application

    if [ "${1:-}" = "log" ]; then
        show_log
    fi
}

main "$@"
