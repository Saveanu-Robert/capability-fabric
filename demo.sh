#!/usr/bin/env bash
# Capability Fabric: the whole guided demo in one command (Linux and macOS).
#
#   ./demo.sh                  remove any earlier demo system, build and start a fresh one, pair, admit the agents,
#                              run every demo step, then remove everything again
#   ./demo.sh --keep           leave the demo system running afterwards, to try it yourself
#   ./demo.sh --purge          also remove the pulled base images (NATS, Postgres, Ollama, JDK) when nothing uses them
#   ./demo.sh --bundled-ollama use the bundled Ollama container instead of Ollama on this machine
#   ./demo.sh --clean-only     only remove a demo system (for example one left running by --keep)
#
# The demo runs as its own compose project (fabric-demo) with its own image, ports, volumes and secrets folder
# (.demo/), so a Capability Fabric you already run here is never touched. It never reuses an earlier demo system:
# whatever is left of one is stopped and removed first. At the end everything the demo created is removed, also when
# it fails or is interrupted (unless --keep).
set -euo pipefail
cd "$(dirname "$0")"

KEEP=false
PURGE=false
BUNDLED=false
CLEAN_ONLY=false
for arg in "$@"; do
  case "$arg" in
    --keep) KEEP=true ;;
    --purge) PURGE=true ;;
    --bundled-ollama) BUNDLED=true ;;
    --clean-only) CLEAN_ONLY=true ;;
    -h|--help) sed -n '2,14p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown option: $arg (see ./demo.sh --help)" >&2; exit 2 ;;
  esac
done

PROJECT=fabric-demo
export FABRIC_IMAGE=capability-fabric:demo
export FABRIC_SECRETS_DIR=./.demo/secrets
export NATS_PORT="${DEMO_NATS_PORT:-14222}"
export GATEWAY_PORT="${DEMO_GATEWAY_PORT:-18443}"
DC=(docker compose -p "$PROJECT")
if $BUNDLED; then
  export OLLAMA_BASE_URL=http://ollama:11434
  DC+=(--profile ollama)
fi

say() { printf '\n\033[1m%s\033[0m\n' "$*"; }
fail() { printf '\n❌ %s\n' "$*" >&2; exit 1; }

# A setting of the services as compose resolves it (.env, environment, compose.yaml defaults).
compose_setting() {
  docker compose config 2>/dev/null | awk -v key="$1:" '$1 == key {print $2; exit}'
}

# The images the demo pulls: the services' images other than its own, and the Dockerfile's base images.
base_images() {
  docker compose --profile tools --profile extras --profile ollama config --images 2>/dev/null \
    | grep -v "^capability-fabric:" | sort -u
  grep -E '^FROM ' Dockerfile | awk '{print $2}' | sort -u
}

# Everything of a demo system: its containers (every profile), volumes, network, secrets folder and image.
remove_demo() {
  docker compose -p "$PROJECT" --profile tools --profile extras --profile ollama down -v --remove-orphans \
    >/dev/null 2>&1 || true
  if [[ -d .demo ]] && ! rm -rf .demo 2>/dev/null; then
    # Files a container wrote as root (Docker Engine on Linux) are removed from inside a container.
    docker run --rm --user 0 --entrypoint sh -v "$PWD/.demo:/demo" "$FABRIC_IMAGE" \
      -c 'rm -rf /demo/* /demo/.[!.]*' >/dev/null 2>&1 || true
    rm -rf .demo 2>/dev/null || echo "   ⚠️  .demo could not be removed: sudo rm -rf .demo"
  fi
  docker image rm "$FABRIC_IMAGE" >/dev/null 2>&1 || true
}

finish() {
  status=$?
  if $KEEP && ! $CLEAN_ONLY; then
    say "🔎 The demo system keeps running (project $PROJECT). Try it:"
    echo "   docker compose -p $PROJECT run --rm cli \"what should I do today?\""
    echo "   Remove it with: ./demo.sh --clean-only (the next ./demo.sh removes it too)"
    exit $status
  fi
  say "🧹 Stopping the demo system and removing its containers, volumes, network, image and secrets"
  remove_demo
  if $PURGE; then
    # The pulled images compose and the Dockerfile name; docker refuses to remove one a container still uses.
    for image in $(base_images); do
      docker image rm "$image" >/dev/null 2>&1 && echo "   removed $image" || true
    done
  fi
  echo "   ✔ nothing of the demo is left$($PURGE || echo " (base images are kept: add --purge to remove them too)")"
  exit $status
}

say "🔍 Checking what the demo needs"
command -v docker >/dev/null || fail "Docker is not installed (see Quick start in README.md)"
docker info >/dev/null 2>&1 \
  || fail "Docker does not answer: start it (Linux: sudo systemctl start docker, and add your user to the docker group; macOS with Colima: colima start)"
docker compose version >/dev/null 2>&1 || fail "the Docker Compose plugin is missing"
echo "   ✔ Docker $(docker version --format '{{.Server.Version}}') with Compose $(docker compose version --short)"

if $CLEAN_ONLY; then
  trap finish EXIT
  exit 0
fi

CHAT_MODEL=$(compose_setting FABRIC_CHAT_MODEL)
EMBEDDING_MODEL=$(compose_setting FABRIC_EMBEDDING_MODEL)
if ! $BUNDLED; then
  tags=$(curl -fsS http://localhost:11434/api/tags 2>/dev/null) \
    || fail "Ollama does not answer on http://localhost:11434: start it (ollama serve), or run ./demo.sh --bundled-ollama"
  for model in "$CHAT_MODEL" "$EMBEDDING_MODEL"; do
    grep -q "\"$model\"" <<<"$tags" || fail "the model $model is missing: ollama pull $model"
  done
  if [[ "$(uname -s)" == Linux ]]; then
    # The containers reach Ollama through the Docker bridge; Ollama on Linux listens on localhost only by default.
    bridge=$(docker network inspect bridge -f '{{(index .IPAM.Config 0).Gateway}}' 2>/dev/null || true)
    if [[ -n "$bridge" ]] && ! curl -fsS "http://$bridge:11434/api/tags" >/dev/null 2>&1; then
      fail "the containers cannot reach Ollama on $bridge:11434: let it listen there (OLLAMA_HOST=$bridge:11434, see Model server in README.md), or run ./demo.sh --bundled-ollama"
    fi
  fi
  echo "   ✔ Ollama with $CHAT_MODEL and $EMBEDDING_MODEL"
fi

say "🧽 Making sure the demo starts fresh"
if [[ -n "$(docker ps -aq --filter "label=com.docker.compose.project=$PROJECT")" ]] || [[ -d .demo ]] \
    || docker volume ls -q --filter "label=com.docker.compose.project=$PROJECT" | grep -q .; then
  echo "   an earlier demo system was found: stopping it and removing it"
  remove_demo
  echo "   ✔ removed"
else
  echo "   ✔ no earlier demo system"
fi

trap finish EXIT
# Created here so the folder belongs to you, not to the container that writes the secrets into it.
mkdir -p .demo/secrets

say "🏗️  Building the image and starting the demo system (the first build takes a few minutes)"
"${DC[@]}" up -d --build 2>&1 | grep -E "Built|Started|Healthy|Error|error" | awk '!seen[$0]++' | sed 's/^/   /' \
  || true
if $BUNDLED; then
  say "⬇️  Pulling the models into the bundled Ollama (once per demo run)"
  "${DC[@]}" run --rm ollama-pull
fi

say "🔑 Pairing this demo's CLI with the kernel (the owner's device)"
code=""
for _ in $(seq 1 60); do
  code=$("${DC[@]}" logs kernel 2>/dev/null | grep -oE 'fabric --pair [0-9]{4}-[0-9]{4}' | tail -1 \
    | awk '{print $3}' || true)
  [[ -n "$code" ]] && break
  sleep 3
done
[[ -n "$code" ]] || fail "the kernel printed no pairing code (see: docker compose -p $PROJECT logs kernel)"
"${DC[@]}" --progress quiet run --rm -T cli --pair "$code" | sed 's/^/   /'

say "🤝 Admitting the agents (each one waits for the owner's yes)"
admitted=0
for _ in $(seq 1 10); do
  sleep 5
  n=$(yes y | head -n 12 | "${DC[@]}" --progress quiet run --rm -T cli 2>/dev/null | grep -c "is admitted" || true)
  admitted=$((admitted + n))
  if [[ $admitted -ge 6 ]]; then
    break
  fi
done
echo "   ✔ $admitted agents admitted"
sleep 10

say "🎬 Running the guided demo"
set +e
"${DC[@]}" --progress quiet run --rm -T cli --demo
demo_status=$?
set -e
exit $demo_status
