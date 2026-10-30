#!/usr/bin/env bash
set -Eeuo pipefail

find /tmp -maxdepth 1 -type f \
    \( -name 'screenshot_*.png' -o -name 'debug-*.png' \) \
    -mmin +10080 -delete
