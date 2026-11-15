#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
JAVA=${JAVA:-java}
exec "$JAVA" -Xms1G -Xmx2G -jar nexora-server.jar --data-dir ./nexora-data "$@"
