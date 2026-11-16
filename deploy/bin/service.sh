#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
JAR="$ROOT/lib/certificate-manage.jar"
PID_FILE="$ROOT/run/certificate-manage.pid"
BASE_URL="${HEALTH_URL:-http://127.0.0.1:${PORT:-8080}}"
BASE_URL="${BASE_URL%/}"
START_TIMEOUT="${START_TIMEOUT:-60}"
STOP_TIMEOUT="${STOP_TIMEOUT:-30}"
ACTION="${1:-}"
case "$ACTION" in start|stop|restart|status) ;; *) echo 'Usage: service.sh start|stop|restart|status' >&2; exit 2 ;; esac
[[ "$START_TIMEOUT" =~ ^[1-9][0-9]*$ && "$STOP_TIMEOUT" =~ ^[1-9][0-9]*$ ]] || { echo 'Timeouts must be positive seconds.' >&2; exit 2; }
command -v flock >/dev/null || { echo 'flock (util-linux) is required.' >&2; exit 1; }
command -v curl >/dev/null || { echo 'curl is required for homepage/API checks.' >&2; exit 1; }
mkdir -p -- "$ROOT/run"
exec 9>"$ROOT/run/service.lock"
flock -n 9 || { echo 'Another maintenance command is running.' >&2; exit 1; }

# Match the exact -jar argument via /proc, not only a potentially reused PID.
is_service() {
  local process_id="$1" i
  local -a args=()
  [[ "$process_id" =~ ^[0-9]+$ ]] && kill -0 "$process_id" 2>/dev/null && [[ -r "/proc/$process_id/cmdline" ]] || return 1
  mapfile -d '' -t args < "/proc/$process_id/cmdline" 2>/dev/null || return 1
  for ((i=0; i+1<${#args[@]}; i++)); do
    if [[ "${args[i]}" == '-jar' && "${args[i+1]}" == "$JAR" ]]; then return 0; fi
  done
  return 1
}
find_service() {
  local saved file candidate found=''
  if [[ -r "$PID_FILE" ]]; then
    saved="$(cat -- "$PID_FILE")"
    if is_service "$saved"; then printf '%s\n' "$saved"; return 0; fi
  fi
  for file in /proc/[0-9]*/cmdline; do
    candidate="${file#/proc/}"; candidate="${candidate%/cmdline}"
    if is_service "$candidate"; then
      if [[ -n "$found" ]]; then echo 'Multiple matching application processes; inspect manually.' >&2; return 2; fi
      found="$candidate"
    fi
  done
  [[ -z "$found" ]] || printf '%s\n' "$found"
}
healthy() {
  local code csrf
  code="$(curl -sS --max-time 2 -o /dev/null -w '%{http_code}' "$BASE_URL/" 2>/dev/null)" || return 1
  [[ "$code" == 200 ]] || return 1
  csrf="$(curl -fsS --max-time 2 "$BASE_URL/api/v1/auth/csrf" 2>/dev/null)" || return 1
  [[ "$csrf" == *'"token"'* ]]
}
start_service() {
  local current java line child deadline
  local -a options=()
  current="$(find_service)" || return 1
  if [[ -n "$current" ]]; then
    if healthy; then echo "Already RUNNING, PID $current"; return; fi
    echo "RUNNING but unhealthy, PID $current; see logs." >&2; return 1
  fi
  [[ -f "$JAR" ]] || { echo "Application JAR missing: $JAR" >&2; return 1; }
  if curl -sS --max-time 1 "$BASE_URL/" -o /dev/null 2>/dev/null; then echo "HTTP address already responds: $BASE_URL; refusing to start another instance." >&2; return 1; fi
  java="${JAVA_HOME:+$JAVA_HOME/bin/}java"
  command -v "$java" >/dev/null || { echo 'Java 21 is required; check JAVA_HOME or PATH.' >&2; return 1; }
  mkdir -p -- "$ROOT/logs" "$ROOT/data"
  if [[ -f "$ROOT/config/jvm.options" ]]; then
    while IFS= read -r line || [[ -n "$line" ]]; do
      line="${line%$'\r'}"
      line="${line#"${line%%[![:space:]]*}"}"; line="${line%"${line##*[![:space:]]}"}"
      [[ -z "$line" || "$line" == \#* ]] || options+=("$line")
    done < "$ROOT/config/jvm.options"
  fi
  cd -- "$ROOT"
  nohup "$java" "${options[@]}" -jar "$JAR" "--spring.config.additional-location=file:$ROOT/config/" >>"$ROOT/logs/console.log" 2>>"$ROOT/logs/console-error.log" < /dev/null 9>&- &
  child=$!
  printf '%s\n' "$child" > "$PID_FILE"
  deadline=$((SECONDS + START_TIMEOUT))
  while ((SECONDS < deadline)); do
    if ! is_service "$child"; then
      # Allow the shell/nohup wrapper to become the JVM before checking failure.
      if ! kill -0 "$child" 2>/dev/null; then rm -f -- "$PID_FILE"; echo 'Java exited during startup; see logs.' >&2; return 1; fi
    elif healthy; then echo "RUNNING, PID $child; homepage and API healthy; logs: $ROOT/logs"; return; fi
    sleep 0.5
  done
  echo "Startup timed out; PID $child retained. Check status/logs or run stop.sh." >&2
  return 1
}
stop_service() {
  local current deadline
  current="$(find_service)" || return 1
  if [[ -z "$current" ]]; then rm -f -- "$PID_FILE"; echo 'STOPPED'; return; fi
  is_service "$current" || { echo 'Process identity changed; refusing to stop.' >&2; return 1; }
  kill -TERM "$current"
  deadline=$((SECONDS + STOP_TIMEOUT))
  while ((SECONDS < deadline)); do
    if ! is_service "$current"; then rm -f -- "$PID_FILE"; echo "STOPPED, PID $current"; return; fi
    sleep 0.5
  done
  echo "Stop timed out; PID $current retained. No forced kill performed." >&2
  return 1
}
case "$ACTION" in
  start) start_service ;;
  stop) stop_service ;;
  restart) stop_service; start_service ;;
  status)
    current="$(find_service)" || exit 1
    if [[ -z "$current" ]]; then echo 'STOPPED'; exit 3; fi
    if healthy; then echo "RUNNING, PID $current; homepage and API healthy"; else echo "RUNNING but unhealthy, PID $current"; exit 1; fi
    ;;
esac
