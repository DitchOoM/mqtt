#!/usr/bin/env bash
# Starts one interop broker and exports the matrix cell for BrokerInteropTest.
#   start-broker.sh <mosquitto|emqx|hivemq> <tcp|tls|ws> <4|5>
# Every broker's image tag, ports and WebSocket path live here and nowhere else.
set -euo pipefail
broker=$1 transport=$2 protocol=$3
here=$(cd "$(dirname "$0")" && pwd)
certs=$(mktemp -d)

# A fresh self-signed certificate for localhost, valid for this run only.
openssl req -x509 -newkey rsa:2048 -nodes -days 2 -subj "/CN=localhost" \
  -addext "subjectAltName=DNS:localhost,IP:127.0.0.1" \
  -keyout "$certs/server.key" -out "$certs/server.crt" 2>/dev/null
openssl pkcs12 -export -in "$certs/server.crt" -inkey "$certs/server.key" \
  -out "$certs/server.p12" -name server -passout pass:interop
chmod 0755 "$certs" && chmod 0644 "$certs"/*

case "$broker" in
  mosquitto)
    image=eclipse-mosquitto:2.0.22
    tcp=1883 tls=8883 ws=8080
    docker run -d --name broker -p 1883:1883 -p 8883:8883 -p 8080:8080 \
      -v "$here/mosquitto.conf:/mosquitto/config/mosquitto.conf:ro" -v "$certs:/certs:ro" "$image"
    ;;
  emqx)
    image=emqx/emqx:5.8.9
    tcp=1883 tls=8883 ws=8083
    docker run -d --name broker -p 1883:1883 -p 8883:8883 -p 8083:8083 \
      -v "$certs:/certs:ro" \
      -e EMQX_LISTENERS__SSL__DEFAULT__SSL_OPTIONS__CERTFILE=/certs/server.crt \
      -e EMQX_LISTENERS__SSL__DEFAULT__SSL_OPTIONS__KEYFILE=/certs/server.key \
      -e EMQX_MQTT__MAX_INFLIGHT=10 \
      "$image"
    ;;
  hivemq)
    image=hivemq/hivemq-ce:2026.5
    tcp=1883 tls=8883 ws=8000
    docker run -d --name broker -p 1883:1883 -p 8883:8883 -p 8000:8000 \
      -v "$here/hivemq.xml:/opt/hivemq/conf/config.xml:ro" -v "$certs:/certs:ro" "$image"
    ;;
  *) echo "unknown broker $broker" >&2; exit 2 ;;
esac

case "$transport" in
  tcp) port=$tcp ;;
  tls) port=$tls ;;
  ws) port=$ws ;;
  *) echo "unknown transport $transport" >&2; exit 2 ;;
esac

# Ready when the cell's port accepts connections (brokers open listeners last).
for _ in $(seq 1 90); do
  if (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null; then ready=1; break; fi
  sleep 1
done
if [ "${ready:-0}" != 1 ]; then docker logs broker; echo "broker $broker not ready on $port" >&2; exit 1; fi
sleep 3 # listeners on the other ports come up alongside; give the broker a moment to settle

{
  echo "MQTT_INTEROP_BROKER=$broker ($image)"
  echo "MQTT_INTEROP_PROTOCOL=$protocol"
  echo "MQTT_INTEROP_TRANSPORT=$transport"
  echo "MQTT_INTEROP_PORT=$port"
  echo "MQTT_INTEROP_WS_PATH=/mqtt"
} >> "${GITHUB_ENV:-/dev/stdout}"
