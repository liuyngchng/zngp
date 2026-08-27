#!/bin/bash
# ============================================================
# build.sh — 构建 zngp-server Docker 镜像
#
# 用法:
#   ./build.sh                  # 默认 tag: zngp-server:latest
#   ./build.sh v1.0.0           # 指定版本
#   ./build.sh v1.0.0 --push    # 构建并推送
#
# 流程:
#   1. 本地 (Ubuntu) 静态编译 Go 二进制
#   2. 打包进 Alpine 运行时镜像
#   3. 打 release tar.gz 包（含镜像 + 配置 + 模板）
# ============================================================

set -euo pipefail

# ---- 配置 ----
IMAGE_NAME="${IMAGE_NAME:-zngp-server}"
REGISTRY="${REGISTRY:-}"                        # 镜像仓库地址
BINARY="${BINARY:-server}"

# ---- 颜色输出 ----
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
info()  { echo -e "${GREEN}[INFO]${NC}  $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
err()   { echo -e "${RED}[ERR]${NC}   $*"; }

# ---- 解析参数 ----
TAG=""
PUSH=false
while [[ $# -gt 0 ]]; do
    case "$1" in
        --push) PUSH=true ;;
        -h|--help)
            echo "用法: $0 [<tag>] [--push]"
            echo ""
            echo "  <tag>      镜像标签，默认 latest"
            echo "  --push     构建后推送到仓库"
            echo ""
            echo "环境变量:"
            echo "  IMAGE_NAME   镜像名，默认 zngp-server"
            echo "  REGISTRY     仓库地址"
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

# ---- 检查前提 ----
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

if ! command -v go &>/dev/null; then
    err "go 未安装或不在 PATH 中"
    exit 1
fi

if ! command -v docker &>/dev/null; then
    err "docker 未安装或不在 PATH 中"
    exit 1
fi

# ---- Step 1: 本地编译 ----
cd "$SCRIPT_DIR"

# 删除旧二进制
rm -f "$BINARY"

info "本地编译 Go 二进制 (CGO_ENABLED=0)..."
CGO_ENABLED=0 go build -ldflags="-s -w" -o "$BINARY" .

# 自检：确认产物是静态链接
if ! file "$BINARY" | grep -q "statically linked"; then
    err "编译产物不是静态链接！请检查 CGO_ENABLED 设置"
    exit 1
fi
info "编译完成: $SCRIPT_DIR/$BINARY（静态链接）"

# ---- Step 2: 准备 Docker 构建上下文 ----
BUILD_DIR="$(mktemp -d -t zngp-server_build_XXXXXX)"
trap "rm -rf $BUILD_DIR" EXIT
info "准备构建上下文: $BUILD_DIR"

cp "$SCRIPT_DIR/$BINARY" "$BUILD_DIR/"
cp "$SCRIPT_DIR/Dockerfile" "$BUILD_DIR/"
cp "$SCRIPT_DIR/cfg.yml" "$BUILD_DIR/"
cp -r "$SCRIPT_DIR/web" "$BUILD_DIR/web"
cp -r "$SCRIPT_DIR/seed" "$BUILD_DIR/seed"

# ---- Step 3: 打 Docker 镜像 ----
info "构建镜像: $FULL_IMAGE"
docker build -t "$FULL_IMAGE" "$BUILD_DIR"
info "镜像构建完成: $FULL_IMAGE"

# ---- Step 4: 可选推送 ----
if $PUSH; then
    if [[ -z "$REGISTRY" ]]; then
        err "推送需要设置 REGISTRY 环境变量"
        exit 1
    fi
    info "推送镜像: $FULL_IMAGE"
    docker push "$FULL_IMAGE"
    info "推送完成"
fi

# ---- Step 5: 打包 release ----
RELEASE_DIR="$SCRIPT_DIR/zngp-server-release/zngp-server"
RELEASE_TAR="$SCRIPT_DIR/zngp-server-release-${TAG}.tar.gz"
rm -rf "$RELEASE_DIR"
mkdir -p "$RELEASE_DIR"

info "打包 release..."

# docker save 镜像
docker save -o "$RELEASE_DIR/${IMAGE_NAME}.tar" "$FULL_IMAGE"

# 配置文件
cp "$SCRIPT_DIR/cfg.yml" "$RELEASE_DIR/cfg.yml"

# 启动脚本
cat > "$RELEASE_DIR/start.sh" << 'STARTSCRIPT'
#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# 加载镜像
if [ ! "$(docker images -q zngp-server:latest 2>/dev/null)" ]; then
    echo ">>> 加载 Docker 镜像..."
    docker load < "$SCRIPT_DIR/zngp-server.tar"
fi

# 创建数据目录（持久化）
mkdir -p "$SCRIPT_DIR/data/uploads"

# 停止旧容器
docker stop zngp-server 2>/dev/null || true
docker rm zngp-server 2>/dev/null || true

# 启动容器
echo ">>> 启动 zngp-server..."
docker run -d \
    --name zngp-server \
    --restart always \
    -p 8080:8080 \
    -v "$SCRIPT_DIR/cfg.yml:/opt/zngp/cfg.yml:ro" \
    -v "$SCRIPT_DIR/data:/opt/zngp/data" \
    zngp-server:latest

echo ">>> 服务已启动: http://localhost:8080"
echo ">>> 查看日志: docker logs -f zngp-server"
STARTSCRIPT
chmod +x "$RELEASE_DIR/start.sh"

# 打包 tar.gz
cd "$SCRIPT_DIR/zngp-server-release"
tar czf "$RELEASE_TAR" "zngp-server"
cd "$SCRIPT_DIR"
rm -rf "$RELEASE_DIR"

# ---- 镜像信息 ----
echo ""
info "========== 镜像信息 =========="
docker images "$FULL_IMAGE" --format "table {{.Repository}}:{{.Tag}}\t{{.Size}}\t{{.CreatedAt}}"
echo ""
info "========== Release =========="
ls -lh "$RELEASE_TAR"
echo ""
info "交付步骤:"
echo "  1. 将 $(basename "$RELEASE_TAR") 拷贝到目标机器"
echo "  2. 解压: tar xzf $(basename "$RELEASE_TAR")"
echo "  3. 编辑 cfg.yml 填写 ASR/LLM 的 API Key"
echo "  4. 启动: cd zngp-server && ./start.sh"
echo "  5. 打开浏览器 http://<服务器IP>:8080"
echo "  6. 查看日志: docker logs -f zngp-server"