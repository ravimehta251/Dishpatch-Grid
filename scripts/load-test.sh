#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
test -f .local/demo-secrets.json || { echo 'Run python scripts/demo.py --prepare-load first.' >&2; exit 1; }
mkdir -p performance/results
API_KEY=$(python3 -c 'import json; print(json.load(open(".local/demo-secrets.json"))["apiKey"])')
export API_KEY
RUN_ID=$(date -u +%Y%m%dT%H%M%SZ)
docker run --rm --network dispatchgrid_default -e API_KEY -e BASE_URL=http://api:8080 \
  -e "PROFILE=${PROFILE:-sustained}" -e "RATE=${RATE:-5}" -e "DURATION=${DURATION:-30s}" -e "RUN_ID=$RUN_ID" \
  -e "SUMMARY_PATH=/results/$RUN_ID-summary.json" -v "$PWD/performance/k6:/scripts:ro" -v "$PWD/performance/results:/results" \
  grafana/k6:1.0.0 run /scripts/ingestion.js --out "json=/results/$RUN_ID-raw.json"
unset API_KEY
echo "Saved real k6 outputs under performance/results/$RUN_ID-*.json"
