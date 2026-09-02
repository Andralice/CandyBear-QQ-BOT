#!/bin/bash
# Restart the candybear Java process in screen qq-bot.
set -euo pipefail

APP_DIR=/opt/qq-bot
JAR=${APP_DIR}/untitled-1.0-SNAPSHOT.jar
SCREEN_NAME=qq-bot

cd "$APP_DIR"
test -s "$JAR"

echo "=== stop old process ==="
screen -S "$SCREEN_NAME" -X quit >/dev/null 2>&1 || true
sleep 2

mapfile -t JAVA_PIDS < <(ps -eo pid=,comm=,args= | awk '$2 == "java" && index($0, "untitled-1.0-SNAPSHOT.jar") {print $1}')
if ((${#JAVA_PIDS[@]} > 0)); then
    kill -TERM "${JAVA_PIDS[@]}" || true
    sleep 3
    mapfile -t JAVA_PIDS < <(ps -eo pid=,comm=,args= | awk '$2 == "java" && index($0, "untitled-1.0-SNAPSHOT.jar") {print $1}')
    if ((${#JAVA_PIDS[@]} > 0)); then
        kill -KILL "${JAVA_PIDS[@]}" || true
    fi
fi

echo "=== start new process ==="
screen -dmS "$SCREEN_NAME" bash -c "source ${APP_DIR}/.env 2>/dev/null || true; exec java -Xms256m -Xmx768m -jar ${JAR} >> ${APP_DIR}/qq-bot.log 2>&1"

for i in 1 2 3 4 5; do
    sleep 5
    if pgrep -f "$JAR" >/dev/null 2>&1; then
        echo "Java process is running after $((i * 5))s"
        break
    fi
    if [ "$i" -eq 5 ]; then
        echo "ERROR: Java process did not start"
        tail -40 "$APP_DIR/qq-bot.log" || true
        exit 1
    fi
done

echo "=== status ==="
screen -list 2>&1 | grep -F "$SCREEN_NAME" || true
pgrep -af "$JAR"
tail -20 "$APP_DIR/qq-bot.log"
