#!/usr/bin/env bash
# Starts the PhoneAgent shell-uid helper on a device over adb (USB or emulator) and waits until the
# app has admitted it (docs/design/phase1-architecture.md §2.2).
#
#   scripts/start-helper.sh -s <serial> [-p <package>] [--orphan-grace-ms <ms>] [--timeout <s>]
#
# The one-time nonce is generated here and reaches the helper only through stdin: other shell-uid
# processes can read every command line on the device, so only the nonce's SHA-256 is passed to the
# app (`content call ... --method arm`), and the remote sh reads the nonce into a variable and pipes
# it to the helper with the builtin echo.
#
# Exit status: 0 admitted; 1 failed (the reason and the helper's log are printed).
set -euo pipefail

usage() {
  sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'
}

die() {
  echo "start-helper: $*" >&2
  exit 1
}

serial="${ANDROID_SERIAL:-}"
package="dev.phoneagent"
grace=""
timeout_s=20
while [ $# -gt 0 ]; do
  case "$1" in
    -s) serial="${2:?-s needs a serial}"; shift 2 ;;
    -p) package="${2:?-p needs a package}"; shift 2 ;;
    --orphan-grace-ms) grace="${2:?--orphan-grace-ms needs a value}"; shift 2 ;;
    --timeout) timeout_s="${2:?--timeout needs seconds}"; shift 2 ;;
    -h | --help) usage; exit 0 ;;
    *) die "unknown argument '$1' (see --help)" ;;
  esac
done

# Never guess the device: with several attached, a bare `adb` hits the wrong one or fails.
[ -n "$serial" ] || die "no device: pass -s <serial> or set ANDROID_SERIAL"
[[ "$package" =~ ^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$ ]] || die "invalid package name '$package'"
[ -z "$grace" ] || [[ "$grace" =~ ^[0-9]+$ ]] || die "--orphan-grace-ms must be a number of milliseconds"
[[ "$timeout_s" =~ ^[0-9]+$ ]] || die "--timeout must be a number of seconds"

adbs() { adb -s "$serial" "$@"; }

sha256() {
  if command -v sha256sum > /dev/null 2>&1; then sha256sum; else shasum -a 256; fi
}

authority="content://$package.helper"
name="$package:helper"
main_class="dev.phoneagent.privileged.helper.HelperMain"

# 1. The helper's code must come from the installed APK; it checks this itself and refuses otherwise.
apk="$(adbs shell pm path "$package" | tr -d '\r' | sed -n 's/^package://p' | grep '/base\.apk$' | head -n 1 || true)"
[ -n "$apk" ] || die "$package is not installed on $serial"
[[ "$apk" != *"'"* ]] || die "unexpected quote in APK path '$apk'"

# 2. Nonce: 32 random bytes as 64 lowercase hex characters.
nonce="$(od -An -N32 -tx1 /dev/urandom | tr -d ' \n')"
[ "${#nonce}" -eq 64 ] || die "could not generate a nonce"
hash="$(printf '%s' "$nonce" | sha256 | cut -d ' ' -f 1)"

# 3. Arm the app with the nonce's hash (this also starts the app process if needed).
armed="$(adbs shell content call --uri "$authority" --method arm --arg "$hash" | tr -d '\r')"
[[ "$armed" == *"accepted=true"* ]] || die "the app did not arm the nonce: $armed"

# 4. Launch. setsid detaches the helper from the adb session; `sleep 1` keeps the session open
#    until it has, because adbd kills the session's process group when the session ends.
before=" $(adbs shell pidof "$name" | tr -d '\r' || true) "
extra=""
[ -z "$grace" ] || extra=" --orphan-grace-ms=$grace"
remote="read n; (echo \"\$n\" | CLASSPATH='$apk' setsid app_process /system/bin --nice-name='$name' $main_class '$package'$extra > /dev/null 2>&1 &); sleep 1"
printf '%s\n' "$nonce" | adbs shell "$remote"

# 5. Wait until the app reports a new helper pid as connected.
started=$SECONDS
while [ $((SECONDS - started)) -lt "$timeout_s" ]; do
  status="$(adbs shell content call --uri "$authority" --method status | tr -d '\r')"
  pid="$(sed -n 's/.*helperPid=\([0-9-]*\).*/\1/p' <<< "$status")"
  if [[ "$status" == *"state=CONNECTED"* && -n "$pid" && "$before" != *" $pid "* ]]; then
    echo "start-helper: helper pid $pid admitted by $package on $serial"
    exit 0
  fi
  alive="$(adbs shell pidof "$name" | tr -d '\r' || true)"
  new=""
  for p in $alive; do [[ "$before" == *" $p "* ]] || new="$p"; done
  if [ -z "$new" ] && [ $((SECONDS - started)) -ge 3 ]; then
    echo "start-helper: the helper exited without being admitted ($status). Its log:" >&2
    adbs logcat -d -s PhoneAgentHelper:* PhoneAgentHelperClient:* | tail -n 15 >&2
    exit 1
  fi
  sleep 0.5
done
echo "start-helper: timed out after ${timeout_s}s ($status). Log:" >&2
adbs logcat -d -s PhoneAgentHelper:* PhoneAgentHelperClient:* | tail -n 15 >&2
exit 1
