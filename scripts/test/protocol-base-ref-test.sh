#!/usr/bin/env bash
# Tests scripts/ci/protocol-base-ref.sh against throwaway git repositories; nothing leaves $work.
#
#   bash scripts/test/protocol-base-ref-test.sh
#
# Exit status: 0 when every case passes; 1 otherwise (each failure is printed).
set -uo pipefail

script="$(cd "$(dirname "$0")/.." && pwd)/ci/protocol-base-ref.sh"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

g() {
  git -c user.name=test -c user.email=test@example.invalid -c commit.gpgsign=false -c init.defaultBranch=main "$@"
}

# origin plays GitHub: main was force-pushed, so its old head `gone` is on no branch any more but
# can still be fetched by sha (GitHub serves any reachable-by-sha object; upload-pack needs a flag).
g init -q "$work/origin"
g -C "$work/origin" commit -q --allow-empty -m base
kept="$(g -C "$work/origin" rev-parse HEAD)"
g -C "$work/origin" commit -q --allow-empty -m "before the force-push"
gone="$(g -C "$work/origin" rev-parse HEAD)"
g -C "$work/origin" reset -q --hard "$kept"
g -C "$work/origin" commit -q --allow-empty -m "after the force-push"
g -C "$work/origin" config uploadpack.allowAnySHA1InWant true
never="$(printf 'never pushed' | g hash-object --stdin)" # A well-formed sha no repository has as a commit.

failures=0
fail() {
  echo "FAIL $case_name: $*"
  failures=$((failures + 1))
}

# run <name> <expected exit status> <BASE_SHA>: runs the script in a fresh full clone of origin.
run() {
  case_name="$1"
  local expected="$2"
  rm -rf "$work/clone"
  g clone -q --no-local "file://$work/origin" "$work/clone"
  output="$(cd "$work/clone" && BASE_SHA="$3" bash "$script" 2> "$work/stderr")"
  status=$?
  errors="$(cat "$work/stderr")"
  [ "$status" -eq "$expected" ] || fail "exit status $status, expected $expected: $output $errors"
}

has_commit() {
  g -C "$work/clone" cat-file -e "$1^{commit}" 2> /dev/null
}

run "no baseline for workflow_dispatch" 0 ""
[ -z "$output" ] || fail "printed '$output'"
run "no baseline for the first push of main" 0 "0000000000000000000000000000000000000000"
[ -z "$output" ] || fail "printed '$output'"

run "a base in the clone is passed as is" 0 "$kept"
[ "$output" = "-PprivilegedProtocolBaseRef=$kept" ] || fail "printed '$output'"

run "a base that a force-push removed from main is fetched by sha" 0 "$gone"
[ "$output" = "-PprivilegedProtocolBaseRef=$gone" ] || fail "printed '$output'"
has_commit "$gone" || fail "the base commit is still not in the clone"
# A shallow fetch (--depth) would turn the full clone into a shallow one and cut its history.
[ "$(g -C "$work/clone" rev-parse --is-shallow-repository)" = false ] || fail "the fetch made the clone shallow"

run "a base that cannot be fetched fails instead of dropping the baseline" 1 "$never"
[ -z "$output" ] || fail "printed '$output'"
[[ "$errors" == *"::error::"*"$never"* ]] || fail "no ::error:: naming the sha: $errors"

run "a base that is not a full sha fails" 1 "${kept:0:12}"
[ -z "$output" ] || fail "printed '$output'"
[[ "$errors" == *"::error::"* ]] || fail "no ::error::: $errors"

if [ "$failures" -eq 0 ]; then
  echo "protocol-base-ref-test: all cases passed"
else
  echo "protocol-base-ref-test: $failures failure(s)"
  exit 1
fi
