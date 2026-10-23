#!/usr/bin/env bash
set -euo pipefail
publish_root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec bash "$publish_root/scripts/publish-artifacts.sh" central "$@"
