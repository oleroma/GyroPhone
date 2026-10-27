#!/usr/bin/env bash
# Shared helpers for the scripts in .agents/. Sourced by them, never run on its own.
# A script ends by printing "result: <word>" and a short summary. Everything
# else, including each command it runs, goes to its log in build/logs/.

set -euo pipefail
shopt -s inherit_errexit

AGENTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORKTREE="$(git -C "$AGENTS_DIR" rev-parse --show-toplevel)"
# The main checkout, which holds build/, also when running from the build/src worktree.
MAIN_REPO="$(dirname "$(git -C "$WORKTREE" rev-parse --path-format=absolute --git-common-dir)")"
BUILD_DIR="$MAIN_REPO/build"
SCRIPT_NAME="$(basename "$0")"
mkdir -p "$BUILD_DIR/logs"
LOG_FILE="$BUILD_DIR/logs/$SCRIPT_NAME-$(date +%Y%m%d-%H%M%S).log"

# fd 3 keeps the terminal for the result; stdout and stderr go to the log.
exec 3>&1 >>"$LOG_FILE" 2>&1
set -x

FINISHED=0

# Scripts redefine this to undo things such as a lock; it runs on every exit.
cleanup() { :; }

# finish <word> [summary line...]: print the result and exit.
finish() {
  set +x
  FINISHED=1
  local word="$1" out
  shift
  out="result: $word"
  if [ $# -gt 0 ]; then out="$out"$'\n'"$(printf '%s\n' "$@")"; fi
  out="$out"$'\n'"log: $LOG_FILE"
  printf '%s\n' "$out"
  printf '%s\n' "$out" >&3
  case "$word" in
    done) exit 0 ;;
    failed) exit 1 ;;
    *) exit 3 ;;
  esac
}

die() { finish failed "$@"; }

on_exit() {
  local code=$?
  set +x
  cleanup || true
  if [ "$FINISHED" = 0 ]; then
    printf 'result: failed\nUnexpected error (exit %s); the last command in the log failed.\nlog: %s\n' "$code" "$LOG_FILE" >&3
    exit 1
  fi
}
trap on_exit EXIT

# Sets BRANCH to the current branch, or stops if it isn't a feature branch.
require_feature_branch() {
  BRANCH="$(git -C "$WORKTREE" symbolic-ref --quiet --short HEAD)" || die "Not on a branch."
  case "$BRANCH" in
    main) die "On $BRANCH: run this on a feature branch." ;;
  esac
}
