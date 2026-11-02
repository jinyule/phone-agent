#!/usr/bin/env bash
# Prints the Gradle argument that turns on the append-only check of privileged/protocol-history.txt
# against $BASE_SHA, or nothing when there is no baseline. Run in the clone by the CI
# privileged-build job; tested by scripts/test/protocol-base-ref-test.sh.
#
# BASE_SHA is pull_request.base.sha, or `before` for a push (CI runs pushes on main only): empty
# for workflow_dispatch and all zeros for the first push of main, which have no baseline.
# actions/checkout with fetch-depth: 0 fetches branches and tags only, so a `before` that a
# force-push removed from main is missing; it is fetched by sha. That fetch is anonymous (the job
# checks out with persist-credentials: false): a private repository needs a token for it.
# A base that cannot be fetched fails the job: an empty baseline would let any history pass.
#
# Exit status: 0 with the argument or nothing on stdout; 1 with a GitHub ::error:: on stderr.
set -euo pipefail

base="${BASE_SHA:-}"
if [[ -z "$base" || "$base" =~ ^0+$ ]]; then exit 0; fi
if [[ ! "$base" =~ ^([0-9a-f]{40}|[0-9a-f]{64})$ ]]; then
  echo "::error::BASE_SHA '$base' is not a full commit sha" >&2
  exit 1
fi
if ! git cat-file -e "$base^{commit}" 2> /dev/null; then
  echo "base commit $base is not in the clone (force-pushed away from main?); fetching it by sha" >&2
  if ! git fetch --quiet --no-tags origin "$base" >&2 || ! git cat-file -e "$base^{commit}" 2> /dev/null; then
    echo "::error::base commit $base cannot be fetched from origin (gone after a force-push, or a private" \
      "repository without a token); the append-only check of privileged/protocol-history.txt needs it" >&2
    exit 1
  fi
fi
echo "-PprivilegedProtocolBaseRef=$base"
