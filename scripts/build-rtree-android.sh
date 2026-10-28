#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Thin wrapper: builds and stages rtree via the shared accelerator build script.
set -euo pipefail
exec "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/build-mesh2step-accelerators-android.sh" rtree "$@"
