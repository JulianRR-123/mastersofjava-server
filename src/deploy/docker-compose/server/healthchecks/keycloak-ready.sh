#!/usr/bin/env bash
# Management readiness and imported realm availability use separate interfaces.
set -euo pipefail
probe="$(dirname "$0")/http-ready.sh"
HTTP_READY_PORT=9000 /bin/bash "$probe" /health/ready
HTTP_READY_PORT=8080 /bin/bash "$probe" /realms/moj/.well-known/openid-configuration
