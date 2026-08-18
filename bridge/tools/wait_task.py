#!/usr/bin/env python3
"""Claim the next pending voice task for the agent session.

Watches the pending queue, atomically claims the oldest task (rename),
prints it as JSON on stdout, and exits 0.

Usage:
    python3 wait_task.py [timeout_seconds]   # default 240s

Exit codes:
    0  task claimed (JSON on stdout)
    2  timed out, no task
    1  error

Reply contract (agent -> bridge):
    Write /tmp/voice-bridge-tasks/replies/<task_id>.json with:
        {"reply_text": "..."}
"""
import glob
import json
import os
import sys
import time

TASK_DIR = os.environ.get("VOICE_AGENT_TASK_DIR", "/tmp/voice-bridge-tasks")
PENDING = os.path.join(TASK_DIR, "pending")
PROCESSING = os.path.join(TASK_DIR, "processing")
REPLIES = os.path.join(TASK_DIR, "replies")


def main() -> int:
    timeout = float(sys.argv[1]) if len(sys.argv) > 1 else 240.0
    deadline = time.time() + timeout
    os.makedirs(PENDING, exist_ok=True)
    os.makedirs(PROCESSING, exist_ok=True)

    while time.time() < deadline:
        candidates = sorted(glob.glob(os.path.join(PENDING, "*.json")))
        for path in candidates:
            claimed = os.path.join(PROCESSING, os.path.basename(path))
            try:
                os.rename(path, claimed)  # atomic claim
            except FileNotFoundError:
                continue  # already claimed
            with open(claimed, encoding="utf-8") as f:
                task = json.load(f)
            print(json.dumps(task, ensure_ascii=False))
            return 0
        time.sleep(0.3)

    # No task. Report any orphaned (late) replies so the agent can fold them
    # into the next voice answer.
    orphans = sorted(glob.glob(os.path.join(REPLIES, "*.json")))
    if orphans:
        print("ORPHANED_REPLIES (deliver these on the next voice turn):")
        for p in orphans:
            with open(p, encoding="utf-8") as f:
                print(f.read().strip())
    print("TIMEOUT: no task received", file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main())
