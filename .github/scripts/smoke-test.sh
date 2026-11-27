#!/usr/bin/env bash
# Starts the image as it runs in production, with invented credentials and hosts that do not
# answer, and checks that the native binary binds its configuration, serves its health and stops
# at a broken mapping with the message of the configuration check.
set -euo pipefail

image=$1
port=18089
work=$(mktemp -d)
container=bankbote-smoke-$$
trap 'docker rm -f "$container" > /dev/null 2>&1 || true; rm -rf "$work"' EXIT

openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -days 2 \
  -subj /CN=hibiscus.example.com -keyout "$work/hibiscus.key" -out "$work/hibiscus.pem" 2> /dev/null
rm "$work/hibiscus.key"
printf 'smoke-master-password\n' > "$work/password"
printf 'smoke-key\n' > "$work/key"
printf 'smoke-secret\n' > "$work/secret"
chmod 0755 "$work"
chmod 0644 "$work"/*

common=(
  --server.port="$port"
  --bankbote.hibiscus.url=https://127.0.0.1:9
  --bankbote.hibiscus.certificate=/config/hibiscus.pem
  --bankbote.hibiscus.password-file=/config/password
  --bankbote.hibiscus.connect-timeout=2s
  --bankbote.targets[0].name=company-a
  --bankbote.targets[0].url=https://erp-a.example.com
  --bankbote.targets[0].api-key-file=/config/key
  --bankbote.targets[0].api-secret-file=/config/secret
  --bankbote.accounts[0].iban=DE17123456780000000001
)

docker run --detach --name "$container" --network host --volume "$work:/config:ro" "$image" \
  "${common[@]}" --bankbote.accounts[0].target=company-a > /dev/null

curl --fail --silent --show-error --retry 30 --retry-delay 1 --retry-all-errors \
  "http://127.0.0.1:$port/actuator/health/liveness" > /dev/null

health=""
for _ in $(seq 1 30); do
  health=$(curl --silent "http://127.0.0.1:$port/actuator/health")
  if grep -q '"lastRun"' <<< "$health"; then
    break
  fi
  sleep 1
done
grep -q '"sourceError":"Hibiscus Server cannot be reached' <<< "$health" || {
  echo "::error::the health output does not show the failed run: $health"
  exit 1
}
status=$(curl --silent --output /dev/null --write-out '%{http_code}' -X POST "http://127.0.0.1:$port/run")
[[ $status == 202 ]] || { echo "::error::POST /run answered $status"; exit 1; }

logs=$(docker logs "$container" 2>&1)
for secret in smoke-master-password smoke-key smoke-secret DE17123456780000000001; do
  if grep -q "$secret" <<< "$logs"; then
    echo "::error::the log shows $secret"
    exit 1
  fi
done
docker rm -f "$container" > /dev/null

if docker run --name "$container" --network host --volume "$work:/config:ro" "$image" \
  "${common[@]}" --bankbote.accounts[0].target=company-x > "$work/broken.log" 2>&1; then
  echo "::error::Bankbote started with an account mapped to an unknown target"
  exit 1
fi
grep -q 'is mapped to the unknown target company-x' "$work/broken.log" || {
  echo "::error::the start did not name the broken mapping"
  cat "$work/broken.log"
  exit 1
}
echo "smoke test passed"
