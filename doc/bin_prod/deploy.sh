#!/bin/bash

set -Eeuo pipefail

PROC_NAME="WX_API"

BIN_DIR="$(cd "$(dirname "$0")" && pwd)"
BASE_DIR="$(cd "${BIN_DIR}/.." && pwd)"
SOURCE_DIR="/home/git/${PROC_NAME}"

SOURCE_JAR="${SOURCE_DIR}/dist/${PROC_NAME}.jar"
TARGET_JAR="${BASE_DIR}/${PROC_NAME}.jar"
STOP_SCRIPT="${BIN_DIR}/stop.sh"
START_SCRIPT="${BIN_DIR}/start.sh"
DEPLOY_LOG="${BASE_DIR}/logs/deploy.log"

FORCE_DEPLOY=false
STAGED_JAR=""

usage() {
    echo "Usage: $0 [--force]"
    echo "  --force  Deploy even when the source and target JAR files are identical."
}

fail() {
    echo >&2
    echo "Deploy failed: $1" >&2
    echo "Finished: $(date '+%Y-%m-%d %H:%M:%S')" >&2
    exit 1
}

cleanup() {
    if [ -n "$STAGED_JAR" ] && [ -f "$STAGED_JAR" ]; then
        rm -f -- "$STAGED_JAR"
    fi
}

trap cleanup EXIT

parse_arguments() {
    case "${1:-}" in
        "")
            ;;
        --force)
            FORCE_DEPLOY=true
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            usage
            exit 1
            ;;
    esac
    [ "$#" -le 1 ] || { usage; exit 1; }
}

setup_logging() {
    mkdir -p "$BIN_DIR" "${BASE_DIR}/logs"
    exec > >(tee -a "$DEPLOY_LOG") 2>&1
}

validate_environment() {
    local command_name

    [ -f "$SOURCE_JAR" ] || fail "Source fat JAR was not found: ${SOURCE_JAR}"
    [ -x "$STOP_SCRIPT" ] || fail "stop.sh is missing or not executable: ${STOP_SCRIPT}"
    [ -x "$START_SCRIPT" ] || fail "start.sh is missing or not executable: ${START_SCRIPT}"

    for command_name in cmp cp mktemp tee; do
        command -v "$command_name" >/dev/null 2>&1 || fail "Required command was not found: ${command_name}"
    done
}

is_deploy_required() {
    if [ "$FORCE_DEPLOY" = true ] || [ ! -f "$TARGET_JAR" ]; then
        return 0
    fi

    if cmp -s "$SOURCE_JAR" "$TARGET_JAR"; then
        echo "Source and target fat JAR files are identical. Nothing to deploy."
        echo "Use --force to deploy anyway."
        return 1
    fi

    return 0
}

prepare_jar() {
    STAGED_JAR=$(mktemp "${BIN_DIR}/.${PROC_NAME}.jar.XXXXXX")
    cp "$SOURCE_JAR" "$STAGED_JAR"
    cmp -s "$SOURCE_JAR" "$STAGED_JAR" || fail "Staged fat JAR verification failed."
}

confirm_deploy() {
    local answer

    echo
    echo "Source: ${SOURCE_JAR}"
    echo "Target: ${TARGET_JAR}"
    read -r -p "Stop and deploy ${PROC_NAME}? [y/N]: " answer || return 1
    [[ "$answer" == y || "$answer" == Y ]]
}

install_jar() {
    chmod --reference="$SOURCE_JAR" "$STAGED_JAR" 2>/dev/null || chmod 644 "$STAGED_JAR"
    mv -f -- "$STAGED_JAR" "$TARGET_JAR"
    STAGED_JAR=""
    cmp -s "$SOURCE_JAR" "$TARGET_JAR" || fail "Installed fat JAR verification failed."
    echo "Fat JAR installed: ${TARGET_JAR}"
}

start_application() {
    if ! "$START_SCRIPT"; then
        fail "Application startup failed."
    fi
}

main() {
    parse_arguments "$@"
    setup_logging

    echo
    echo "============================================================"
    echo "${PROC_NAME} deploy started: $(date '+%Y-%m-%d %H:%M:%S')"
    validate_environment

    if ! is_deploy_required; then
        exit 0
    fi

    prepare_jar
    if ! confirm_deploy; then
        echo "Deploy canceled."
        exit 0
    fi

    "$STOP_SCRIPT"
    install_jar
    start_application

    echo "${PROC_NAME} deploy completed: $(date '+%Y-%m-%d %H:%M:%S')"
    echo "Application log: ${BASE_DIR}/logs/root.txt"
    echo "============================================================"
}

main "$@"
