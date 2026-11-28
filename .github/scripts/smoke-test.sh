#!/usr/bin/env bash
# Starts the image as it runs in production, hardened as docs/betrieb.md describes it (read-only
# root file system with a tmpfs on /tmp only, no capabilities, no new privileges), configured by a
# mounted /config/application.yaml without any argument, with invented credentials and hosts that
# do not answer. It checks that the native binary finds the file, binds its configuration, serves
# its health, answers on the probe port only the two probes, keeps the main port on loopback, and
# stops at a missing configuration and at a broken mapping with the message of the configuration
# check.
set -euo pipefail

image=$1
port=18089
probe_port=18090
work=$(mktemp -d)
container=bankbote-smoke-$$
trap 'docker rm -f "$container" > /dev/null 2>&1 || true; rm -rf "$work"' EXIT

hardened=(--read-only --tmpfs /tmp --cap-drop ALL --security-opt no-new-privileges)

fail() {
  echo "::error::$1"
  exit 1
}

# Waits until the address answers with success; shows the end of the log if it never does.
await() {
  if ! curl --fail --silent --retry 30 --retry-delay 1 --retry-all-errors "$1" > /dev/null; then
    docker logs "$container" 2>&1 | tail -n 40
    fail "$1 did not answer"
  fi
}

openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -days 2 \
  -subj /CN=hibiscus.example.com -keyout "$work/hibiscus.key" -out "$work/hibiscus.pem" 2> /dev/null
rm "$work/hibiscus.key"
printf 'smoke-master-password\n' > "$work/password"
printf 'smoke-key\n' > "$work/key"
printf 'smoke-secret\n' > "$work/secret"
cat > "$work/application.yaml" << EOF
server:
  port: $port
bankbote:
  hibiscus:
    url: https://127.0.0.1:9
    certificate: /config/hibiscus.pem
    password-file: /config/password
    connect-timeout: 2s
  targets:
    - name: company-a
      url: https://erp-a.example.com
      api-key-file: /config/key
      api-secret-file: /config/secret
  accounts:
    - iban: DE17123456780000000001
      target: company-a
EOF
chmod 0755 "$work"
chmod 0644 "$work"/*

# The configuration file alone, on the host network, so that the loopback port is reachable here.
docker run --detach --name "$container" --network host "${hardened[@]}" \
  --volume "$work:/config:ro" "$image" > /dev/null

await "http://127.0.0.1:$port/actuator/health/liveness"

health=""
for _ in $(seq 1 30); do
  health=$(curl --silent "http://127.0.0.1:$port/actuator/health")
  if grep -q '"lastRun"' <<< "$health"; then
    break
  fi
  sleep 1
done
grep -q '"sourceError":"Hibiscus Server cannot be reached' <<< "$health" ||
  fail "the health output does not show the failed run: $health"
status=$(curl --silent --output /dev/null --write-out '%{http_code}' -X POST "http://127.0.0.1:$port/run")
[[ $status == 202 ]] || fail "POST /run answered $status"

logs=$(docker logs "$container" 2>&1)
# The banner is a resource of the native image; without it the start prints no project address.
grep -q 'https://github.com/encircle360-oss/bankbote' <<< "$logs" ||
  fail "the start banner is missing from the log"
for secret in smoke-master-password smoke-key smoke-secret DE17123456780000000001; do
  if grep -q "$secret" <<< "$logs"; then
    fail "the log shows $secret"
  fi
done
docker rm -f "$container" > /dev/null

# The probe port on a network of its own, reached from outside the namespace as a kubelet would.
# Environment variables add to the file and override it: the main port moves to 18088.
docker run --detach --name "$container" "${hardened[@]}" --volume "$work:/config:ro" \
  --env BANKBOTE_PROBES_PORT="$probe_port" --env SERVER_PORT=18088 "$image" > /dev/null
address=$(docker inspect --format '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$container")
[[ -n $address ]] || fail "the container has no address"

await "http://$address:$probe_port/actuator/health/readiness"
for _ in $(seq 1 30); do
  if docker logs "$container" 2>&1 | grep -q 'failed: Hibiscus Server cannot be reached'; then
    break
  fi
  sleep 1
done
docker logs "$container" 2>&1 | grep -q 'Tomcat started on ports 18088 (http), 18090 (http)' ||
  fail "the main port of the environment and the probe port are not both started"
# The run failed, so the health component delivery is down; the probes do not depend on it.
for probe in liveness readiness; do
  body=$(curl --silent --show-error --fail "http://$address:$probe_port/actuator/health/$probe")
  [[ $body == '{"status":"UP"}' ]] || fail "the probe $probe answered $body"
done
for call in "POST /run" "POST /notify" "GET /actuator/health" "GET /actuator/health/delivery" \
  "GET /actuator/env" "POST /actuator/health/liveness"; do
  read -r method path <<< "$call"
  status=$(curl --silent --output /dev/null --write-out '%{http_code}' -X "$method" \
    "http://$address:$probe_port$path")
  [[ $status == 404 ]] || fail "$call on the probe port answered $status instead of 404"
done
# Nothing listens on the main port at the address of the namespace: the connection is refused.
code=0
curl --silent --max-time 5 --output /dev/null "http://$address:18088/actuator/health" || code=$?
[[ $code == 7 ]] || fail "the main port answered outside the loopback address (curl exit code $code)"
code=0
curl --silent --max-time 5 --output /dev/null -X POST "http://$address:18088/run" || code=$?
[[ $code == 7 ]] || fail "POST /run reached the main port outside the loopback address (curl exit code $code)"
docker rm -f "$container" > /dev/null

# Without a configuration file the image starts and stops at the configuration check.
if docker run --name "$container" --network none "${hardened[@]}" "$image" > "$work/empty.log" 2>&1; then
  fail "Bankbote started without a configuration"
fi
grep -q 'bankbote.hibiscus is missing' "$work/empty.log" || {
  cat "$work/empty.log"
  fail "the start without a configuration did not name the missing keys"
}
docker rm -f "$container" > /dev/null

# Command-line arguments override the file; a list such as bankbote.accounts comes whole from one
# source, so the account is given here with its IBAN and its target.
if docker run --name "$container" --network host "${hardened[@]}" --volume "$work:/config:ro" \
  "$image" --bankbote.accounts[0].iban=DE17123456780000000001 \
  --bankbote.accounts[0].target=company-x > "$work/broken.log" 2>&1; then
  fail "Bankbote started with an account mapped to an unknown target"
fi
grep -q 'is mapped to the unknown target company-x' "$work/broken.log" || {
  cat "$work/broken.log"
  fail "the start did not name the broken mapping"
}
echo "smoke test passed"
