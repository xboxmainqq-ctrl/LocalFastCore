#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
JAVA=${JAVA:-java}
mkdir -p ./nexora-data
if [ ! -f ./nexora-data/server.properties ]; then
  cp ./server.properties ./nexora-data/server.properties
fi
exec "$JAVA" -Xms1G -Xmx2G -jar nexora-server.jar --data-dir ./nexora-data "$@"
