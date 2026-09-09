#!/bin/bash
# ============================================================
# build.sh — Build zngp-server Docker image
#
# Usage:
#   ./build.sh                  # default tag: zngp-server:latest
#   ./build.sh v1.0.0           # specify version
#   ./build.sh v1.0.0 --push    # build and push
#
# Flow:
#   1. Statically compile Go binary locally (Ubuntu)
#   2. Package into Alpine runtime image
#   3. Create release tar.gz (image + config + templates)
# ============================================================

set -euo pipefail

# ---- Config ----
IMAGE_NAME="${IMAGE_NAME:-zngp-server}"
REGISTRY="${REGISTRY:-}"                        # registry address
BINARY="${BINARY:-server}"

# ---- Color output ----
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
info()  { echo -e "${GREEN}[INFO]${NC}  $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
err()   { echo -e "${RED}[ERR]${NC}   $*"; }

# ---- Parse args ----
TAG=""
PUSH=false
while [[ $# -gt 0 ]]; do
    case "$1" in
        --push) PUSH=true ;;
        -h|--help)
            echo "Usage: $0 [<tag>] [--push]"
            echo ""
            echo "  <tag>      image tag, default: latest"
            echo "  --push     push to registry after build"
            echo ""
            echo "Env vars:"
            echo "  IMAGE_NAME   image name, default: zngp-server"
            echo "  REGISTRY     registry address"
            exit 0
            ;;
        *) TAG="$1" ;;
    esac
    shift
done

TAG="${TAG:-latest}"

if [[ -n "$REGISTRY" ]]; then
    FULL_IMAGE="${REGISTRY}/${IMAGE_NAME}:${TAG}"
else
    FULL_IMAGE="${IMAGE_NAME}:${TAG}"
fi

# ---- Prerequisites ----
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

if ! command -v go &>/dev/null; then
    err "go is not installed or not in PATH"
    exit 1
fi

if ! command -v docker &>/dev/null; then
    err "docker is not installed or not in PATH"
    exit 1
fi

# ---- Step 1: Compile ----
cd "$SCRIPT_DIR"

# Remove old binary
rm -f "$BINARY"

info "Compiling Go binary (CGO_ENABLED=0, garble obfuscation)..."
GOTOOLCHAIN=local CGO_ENABLED=0 go tool garble -literals build -ldflags="-s -w" -o "$BINARY" .

# Verify: ensure the binary is statically linked
if ! file "$BINARY" | grep -q "statically linked"; then
    err "Binary is not statically linked! Check CGO_ENABLED setting"
    exit 1
fi
info "Compile done: $SCRIPT_DIR/$BINARY (statically linked)"

# ---- Step 2: Prepare Docker build context ----
BUILD_DIR="$(mktemp -d -t zngp-server_build_XXXXXX)"
trap "rm -rf $BUILD_DIR" EXIT
info "Preparing build context: $BUILD_DIR"

cp "$SCRIPT_DIR/$BINARY" "$BUILD_DIR/"
cp "$SCRIPT_DIR/Dockerfile" "$BUILD_DIR/"
# Image only contains placeholder config (generated from cfg.yml.template).
# Real secrets are injected via runtime volume mounts, never baked into the image.
if [[ ! -f "$SCRIPT_DIR/cfg.yml.template" ]]; then
    err "cfg.yml.template not found, cannot generate default config"
    exit 1
fi
cp "$SCRIPT_DIR/cfg.yml.template" "$BUILD_DIR/cfg.yml"
cp -r "$SCRIPT_DIR/web" "$BUILD_DIR/web"
cp -r "$SCRIPT_DIR/seed" "$BUILD_DIR/seed"

# ---- Step 3: Build Docker image ----
info "Building image: $FULL_IMAGE"
docker build -t "$FULL_IMAGE" "$BUILD_DIR"
info "Image built: $FULL_IMAGE"

# ---- Step 4: Optional push ----
if $PUSH; then
    if [[ -z "$REGISTRY" ]]; then
        err "REGISTRY env var is required for push"
        exit 1
    fi
    info "Pushing image: $FULL_IMAGE"
    docker push "$FULL_IMAGE"
    info "Push done"
fi

# ---- Step 5: Package release ----
RELEASE_DIR="$SCRIPT_DIR/zngp-server-release/zngp-server"
RELEASE_TAR="$SCRIPT_DIR/zngp-server-release-${TAG}.tar.gz"
rm -rf "$RELEASE_DIR"
mkdir -p "$RELEASE_DIR"

info "Packaging release..."

# docker save image
docker save -o "$RELEASE_DIR/${IMAGE_NAME}.tar" "$FULL_IMAGE"

# Config file (use template, real secrets filled by deployer)
cp "$SCRIPT_DIR/cfg.yml.template" "$RELEASE_DIR/cfg.yml"

# Startup script
cat > "$RELEASE_DIR/start.sh" << 'STARTSCRIPT'
#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# Load image
if [ ! "$(docker images -q zngp-server:latest 2>/dev/null)" ]; then
    echo ">>> Loading Docker image..."
    docker load < "$SCRIPT_DIR/zngp-server.tar"
fi

# Create data dir (persistent)
mkdir -p "$SCRIPT_DIR/data/uploads"

# Stop old container
docker stop zngp-server 2>/dev/null || true
docker rm zngp-server 2>/dev/null || true

# Start container
echo ">>> Starting zngp-server..."
docker run -d \
    --name zngp-server \
    --restart always \
    -p 8080:8080 \
    -v "$SCRIPT_DIR/cfg.yml:/opt/zngp/cfg.yml:ro" \
    -v "$SCRIPT_DIR/data:/opt/zngp/data" \
    zngp-server:latest

echo ">>> Service started: https://localhost:8080"
echo ">>> View logs: docker logs -f zngp-server"
STARTSCRIPT
chmod +x "$RELEASE_DIR/start.sh"

# Package tar.gz
cd "$SCRIPT_DIR/zngp-server-release"
tar czf "$RELEASE_TAR" "zngp-server"
cd "$SCRIPT_DIR"
rm -rf "$RELEASE_DIR"

# ---- Image info ----
echo ""
info "========== Image Info =========="
docker images "$FULL_IMAGE" --format "table {{.Repository}}:{{.Tag}}\t{{.Size}}\t{{.CreatedAt}}"
echo ""
info "========== Release =========="
ls -lh "$RELEASE_TAR"
echo ""
info "Delivery steps:"
echo "  1. Copy $(basename "$RELEASE_TAR") to target machine"
echo "  2. Extract: tar xzf $(basename "$RELEASE_TAR")"
echo "  3. Edit cfg.yml and fill in ASR/LLM API keys"
echo "  4. Start: cd zngp-server && ./start.sh"
echo "  5. Open browser: https://<server-ip>:8080"
echo "  6. View logs: docker logs -f zngp-server"