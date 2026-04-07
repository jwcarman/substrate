#!/usr/bin/env bash
# =============================================================================
# RALPH LOOP — Autonomous Software Evolution
# =============================================================================
# Bash is the outer layer. Bash controls when Claude lives and dies.
# Claude does NOT control this loop.
#
# The feedback loop is the codebase itself:
#   write code → run tests → read output → fix → commit → next spec
#
# Usage:
#   ./ralph-loop.sh              # run until NEEDS_HUMAN or idle limit reached
#   ./ralph-loop.sh --once       # run exactly one iteration
#   ./ralph-loop.sh --dry-run    # print what would run, don't execute
#   ./ralph-loop.sh --no-idle    # stop immediately when specs/ is empty
#
# Env vars:
#   MAX_ITERATIONS=100     default: 100
#   PAUSE_SECONDS=10       default: 10
#   MAX_IDLE_ATTEMPTS=10   default: 10  (ignored with --no-idle)
# =============================================================================

set -euo pipefail

trap 'echo; log "Interrupted. Exiting."; kill 0 2>/dev/null; exit 130' INT TERM

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MAX_ITERATIONS="${MAX_ITERATIONS:-100}"
PAUSE_SECONDS="${PAUSE_SECONDS:-10}"
MAX_IDLE_ATTEMPTS="${MAX_IDLE_ATTEMPTS:-10}"
ONCE=false
DRY_RUN=false
IDLE=true

mkdir -p "$SCRIPT_DIR/logs"
LOG_FILE="$SCRIPT_DIR/logs/loop-$(date +%Y%m%d-%H%M%S).log"

for arg in "$@"; do
  case $arg in
    --once)    ONCE=true ;;
    --dry-run) DRY_RUN=true ;;
    --no-idle) IDLE=false ;;
  esac
done

# -----------------------------------------------------------------------------

log() {
  local msg="[$(date '+%Y-%m-%d %H:%M:%S')] $*"
  echo "$msg" | tee -a "$LOG_FILE"
}

check_deps() {
  local missing=()
  for cmd in claude git; do
    command -v "$cmd" &>/dev/null || missing+=("$cmd")
  done
  if [ ${#missing[@]} -gt 0 ]; then
    log "ERROR: missing required commands: ${missing[*]}"
    log "Install Claude Code: https://docs.anthropic.com/en/docs/claude-code"
    exit 1
  fi
}

check_prd() {
  if [ ! -f "$SCRIPT_DIR/PRD.md" ]; then
    log "ERROR: PRD.md not found."
    log "Copy PRD.example.md to PRD.md and fill it out, then run again."
    exit 1
  fi
}

has_work() {
  compgen -G "$SCRIPT_DIR/specs/*.md" > /dev/null 2>&1
}

needs_human() {
  grep -q "^STATUS: NEEDS_HUMAN" "$SCRIPT_DIR/progress.txt" 2>/dev/null
}

init_git() {
  cd "$SCRIPT_DIR"
  if [ ! -d ".git" ]; then
    git init -q
    git add -A
    git commit -q -m "ralph: initial state" 2>/dev/null || true
    log "Git repo initialized"
  fi
}

git_pull() {
  cd "$SCRIPT_DIR"
  if git remote get-url origin &>/dev/null; then
    git pull --quiet 2>/dev/null || log "WARNING: git pull failed — continuing with local state"
  fi
}

commit_iteration() {
  local iteration=$1
  cd "$SCRIPT_DIR"
  if ! git diff --quiet 2>/dev/null || ! git diff --staged --quiet 2>/dev/null; then
    git add -A
    git commit -q -m "ralph: iteration $iteration — $(date '+%Y-%m-%d %H:%M:%S')"
    log "Changes committed (iteration $iteration)"
  else
    log "No changes in iteration $iteration"
  fi
}

next_spec() {
  compgen -G "$SCRIPT_DIR/specs/*.md" > /dev/null 2>&1 && \
    ls "$SCRIPT_DIR/specs/"*.md 2>/dev/null | sort | head -1 | xargs basename
}

run_iteration() {
  local iteration=$1
  local spec
  spec=$(next_spec)
  log "--- Iteration $iteration — ${spec:-unknown} ---"

  if $DRY_RUN; then
    log "DRY RUN — would run: claude --dangerously-skip-permissions --print \"/ralph-plugin:run\""
    return 0
  fi

  claude \
    --dangerously-skip-permissions \
    --print \
    "/ralph-plugin:run" \
    2>&1 | tee -a "$LOG_FILE"
}

# -----------------------------------------------------------------------------

check_deps
check_prd
init_git

log "Ralph Loop starting — $(basename "$SCRIPT_DIR")"
log "MAX_ITERATIONS=$MAX_ITERATIONS  PAUSE_SECONDS=$PAUSE_SECONDS  MAX_IDLE_ATTEMPTS=$MAX_IDLE_ATTEMPTS  IDLE=$IDLE"

iteration=0
idle_count=0
idle_sleep=60

while true; do
  iteration=$((iteration + 1))

  if [ "$iteration" -gt "$MAX_ITERATIONS" ]; then
    log "STOPPING: reached MAX_ITERATIONS ($MAX_ITERATIONS)"
    break
  fi

  if needs_human; then
    log "STOPPING: NEEDS_HUMAN set in progress.txt"
    log "Run /ralph-plugin:resume to resolve the blocker, then restart."
    break
  fi

  if ! has_work; then
    if ! $IDLE; then
      log "DONE: specs/ is empty. All work complete."
      break
    fi

    if [ "$idle_count" -ge "$MAX_IDLE_ATTEMPTS" ]; then
      log "DONE: no new specs after $MAX_IDLE_ATTEMPTS idle checks. Exiting."
      break
    fi

    idle_count=$((idle_count + 1))
    log "IDLE ($idle_count/$MAX_IDLE_ATTEMPTS): no specs found. Sleeping ${idle_sleep}s then checking for new work..."
    sleep "$idle_sleep"
    git_pull
    idle_sleep=$(( idle_sleep * 2 > 480 ? 480 : idle_sleep * 2 ))
    iteration=$((iteration - 1))  # don't burn an iteration on an idle check
    continue
  fi

  # New work found — reset idle state
  if [ "$idle_count" -gt 0 ]; then
    log "New spec found after $idle_count idle check(s). Resuming."
    idle_count=0
    idle_sleep=60
  fi

  run_iteration "$iteration"
  commit_iteration "$iteration"

  if $ONCE; then
    log "Single iteration done (--once)."
    break
  fi

  log "Pausing ${PAUSE_SECONDS}s..."
  sleep "$PAUSE_SECONDS"
done

log "Loop exited after $iteration iteration(s)."
