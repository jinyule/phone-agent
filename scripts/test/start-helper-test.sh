#!/usr/bin/env bash
# Tests scripts/start-helper.sh against a fake adb on PATH; no device is touched.
#
#   bash scripts/test/start-helper-test.sh
#
# Exit status: 0 when every case passes; 1 otherwise (each failure is printed).
set -uo pipefail

script="$(cd "$(dirname "$0")/.." && pwd)/start-helper.sh"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/bin"
export FAKE_ADB_DIR="$work"

# The fake adb logs every invocation (one line of arguments) and plays a device on which the app
# admits the helper. `arm` and `status` answer with the content of $FAKE_ADB_DIR/arm and /status.
cat > "$work/bin/adb" <<'FAKE'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$FAKE_ADB_DIR/calls"
[ "${1:-}" = "-s" ] || { echo "fake adb: called without -s" >&2; exit 2; }
shift 2
case "$1 ${2:-}" in
  "shell pm")
    echo "package:/data/app/~~a==/dev.phoneagent-b==/base.apk"
    echo "package:/data/app/~~a==/dev.phoneagent-b==/split_config.en.apk" ;;
  "shell content")
    case "$*" in
      *"--method arm"*) cat "$FAKE_ADB_DIR/arm" ;;
      *"--method status"*) cat "$FAKE_ADB_DIR/status" ;;
    esac ;;
  "shell pidof")
    if [ -f "$FAKE_ADB_DIR/launched" ]; then echo 4321; else exit 1; fi ;;
  "logcat "*) ;;
  *)
    # The launch: `shell <remote command>` with the nonce on stdin.
    cat > "$FAKE_ADB_DIR/stdin"
    touch "$FAKE_ADB_DIR/launched" ;;
esac
FAKE
chmod +x "$work/bin/adb"
export PATH="$work/bin:$PATH"

failures=0
fail() {
  echo "FAIL $case_name: $*"
  failures=$((failures + 1))
}

# run <name> <expected exit status> <args...>: runs the script on a fresh fake device.
run() {
  case_name="$1"
  local expected="$2"
  shift 2
  rm -f "$work/calls" "$work/stdin" "$work/launched"
  touch "$work/calls"
  echo "${reply:-Result: Bundle[{state=CONNECTED, helperPid=4321}]}" > "$work/status"
  echo "${arm_reply:-Result: Bundle[{accepted=true}]}" > "$work/arm"
  output="$(bash "$script" "$@" 2>&1)"
  status=$?
  [ "$status" -eq "$expected" ] || fail "exit status $status, expected $expected: $output"
}

no_device_calls() {
  [ ! -s "$work/calls" ] || fail "touched the device before rejecting the arguments: $(tr '\n' '|' < "$work/calls")"
}

sha256() {
  if command -v sha256sum > /dev/null 2>&1; then sha256sum; else shasum -a 256; fi
}

# Arguments are rejected before any device side effect.
for bad in "--timeout 0" "--timeout -1" "--timeout 3601" "--timeout 99999999999999999999" "--timeout ten" \
  "--orphan-grace-ms 0" "--orphan-grace-ms 600001" "--package-check-ms 999" "--package-check-ms 600001" \
  "--package-check-ms 1e4"; do
  # shellcheck disable=SC2086 # word splitting of the flag and its value is intended
  run "rejects $bad" 1 -s emulator-fake $bad
  no_device_calls
  [[ "$output" == *"${bad%% *}"* ]] || fail "the message does not name ${bad%% *}: $output"
done

run "rejects a call without a device" 1 --timeout 5
no_device_calls

# A normal launch: the nonce goes only to stdin, its hash only to arm, the options to the helper.
run "launches and waits for the admission" 0 -s emulator-fake --orphan-grace-ms 2000 --package-check-ms 5000 --timeout 5
nonce="$(cat "$work/stdin" 2> /dev/null)"
[[ "$nonce" =~ ^[0-9a-f]{64}$ ]] || fail "stdin was '$nonce', expected a 64-hex nonce"
! grep -q "$nonce" "$work/calls" || fail "the nonce appeared in an adb argument"
hash="$(printf '%s' "$nonce" | sha256 | cut -d ' ' -f 1)"
grep -q -- "--method arm --arg $hash" "$work/calls" || fail "arm did not carry the nonce's SHA-256"
grep -q -- "--orphan-grace-ms=2000 --package-check-ms=5000" "$work/calls" || fail "the helper options were not passed"
grep -q "CLASSPATH='/data/app/~~a==/dev.phoneagent-b==/base.apk'" "$work/calls" || fail "not launched from base.apk"
[[ "$output" == *"helper pid 4321 admitted"* ]] || fail "unexpected output: $output"

# A helper that is still running but not admitted when the timeout ends is reported with the last status.
reply="Result: Bundle[{state=NOT_CONNECTED, helperPid=-1}]"
run "times out with the last status" 1 -s emulator-fake --timeout 1
reply=""
[[ "$output" == *"timed out after 1s (Result: Bundle[{state=NOT_CONNECTED"* ]] || fail "unexpected output: $output"

# An app that refuses to arm (here: no BOOT_COUNT) stops the script before the launch, with its reason.
arm_reply="Result: Bundle[{accepted=false, reason=Settings.Global.BOOT_COUNT is unavailable on this device}]"
run "an arm refusal stops before the launch and shows the reason" 1 -s emulator-fake --timeout 5
arm_reply=""
[[ "$output" == *"BOOT_COUNT is unavailable on this device"* ]] || fail "the app's reason is not shown: $output"
[ ! -f "$work/launched" ] || fail "the helper was launched although the app refused to arm"

if [ "$failures" -eq 0 ]; then
  echo "start-helper-test: all cases passed"
else
  echo "start-helper-test: $failures failure(s)"
  exit 1
fi
