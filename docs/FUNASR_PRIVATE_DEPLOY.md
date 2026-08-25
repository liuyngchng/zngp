# FunASR 私有化部署资源配置清单

## 一、部署架构概览

```
┌──────────────┐     ┌──────────────────┐     ┌─────────────────┐
│  Android/iOS │     │  zngp-server     │     │  FunASR Server  │
│  (离线ASR)    │     │  (Go, :8080)     │────▶│  (Docker,:10095) │
│  Sherpa-ONNX │     │  上传/管理/质检    │ WS  │  WebSocket API  │
└──────────────┘     └──────────────────┘     └─────────────────┘
```

当前项目 `server/` 通过阿里云百炼 API 调用 FunASR。私有化部署后，Go 服务端改为直连本地 FunASR WebSocket 服务。

---

## 二、硬件资源配置

### 2.1 最低配置（开发/测试，并发 ≤ 5 路）

| 组件 | 规格 | 备注 |
|------|------|------|
| CPU | 4 核 (x86_64) | Intel Xeon / AMD EPYC |
| 内存 | 8 GB | 模型加载约 3-4GB，剩余给推理 |
| 磁盘 | 50 GB SSD | 模型文件 ~2GB，Docker 镜像 ~3GB |
| 网络 | 100 Mbps | 内网互通即可 |

### 2.2 推荐配置（生产环境，并发 10-20 路）

| 组件 | 规格 | 备注 |
|------|------|------|
| CPU | 8 核以上 (x86_64) | 建议 Intel Xeon Gold 或 AMD EPYC 7002+ |
| 内存 | 16 GB | 每路并发约 200-500MB 额外开销 |
| 磁盘 | 100 GB SSD | 含音频文件存储空间 |
| 网络 | 1 Gbps | 内网低延迟 |

### 2.3 GPU 加速配置（高并发 / 低延迟场景）

| 组件 | 规格 | 备注 |
|------|------|------|
| GPU | NVIDIA T4 (16GB) 或 A10 (24GB) | 单卡支持 50+ 并发 |
| CPU | 8 核 | 数据预处理 |
| 内存 | 32 GB | GPU 推理仍需 CPU 内存缓冲 |
| 磁盘 | 100 GB SSD | |
| CUDA | 11.4+ | 需安装 nvidia-docker2 |

> **注意**: GPU 部署需使用 `funasr:runtime-sdk-gpu-0.4.7` 镜像，而非 CPU 版本。

---

## 三、软件环境

### 3.1 操作系统

| 系统 | 版本 | 备注 |
|------|------|------|
| Ubuntu | 20.04 / 22.04 LTS | 推荐 |
| CentOS / RHEL | 7.9+ / 8.x | 需安装 Docker CE |
| Debian | 11+ | |

### 3.2 依赖组件

| 软件 | 最低版本 | 用途 |
|------|----------|------|
| Docker | 20.10+ | 容器运行时 |
| Docker Compose | 2.0+ | 可选，多服务编排 |
| nvidia-docker2 | 最新 | GPU 部署必需 |

---

## 四、模型文件清单

FunASR 服务端需要以下模型，均从 ModelScope 自动下载，或手动下载后挂载到容器。

### 4.1 核心模型

| 模型 | ModelScope ID | 大小 | 用途 |
|------|---------------|------|------|
| Paraformer-large (ASR) | `damo/speech_paraformer-large-vad-punc_asr_nat-zh-cn-16k-common-vocab8404-onnx` | ~1.2 GB | 语音识别 |
| FSMN-VAD | `damo/speech_fsmn_vad_zh-cn-16k-common-onnx` | ~50 MB | 语音活动检测 |
| CT-Transformer (标点) | `damo/punc_ct-transformer_cn-en-common-vocab471067-large-onnx` | ~300 MB | 标点恢复 |
| FST ITN | `thuduj12/fst_itn_zh` | ~10 MB | 逆文本正则化 |
| N-gram LM | `damo/speech_ngram_lm_zh-cn-ai-wesp-fst` | ~100 MB | 语言模型评分 |

**模型总大小: ~1.7 GB**

### 4.2 模型下载方式

```bash
# 方式一：启动容器时自动下载（首次启动慢，需访问 ModelScope）
# 容器内 /workspace/models 会自动缓存，下次启动复用

# 方式二：手动下载到宿主机目录
mkdir -p ./funasr-runtime-resources/models

# 使用 modelscope CLI 下载
pip install modelscope
modelscope download --model damo/speech_paraformer-large-vad-punc_asr_nat-zh-cn-16k-common-vocab8404-onnx --local_dir ./funasr-runtime-resources/models/damo/speech_paraformer-large-vad-punc_asr_nat-zh-cn-16k-common-vocab8404-onnx
# ... 依次下载其他模型
```

---

## 五、Docker 部署配置

### 5.1 镜像

| 镜像 | 用途 |
|------|------|
| `registry.cn-hangzhou.aliyuncs.com/funasr_repo/funasr:runtime-sdk-cpu-0.4.7` | CPU 推理 |
| `registry.cn-hangzhou.aliyuncs.com/funasr_repo/funasr:runtime-sdk-gpu-0.4.7` | GPU 推理 |

### 5.2 Docker Compose 配置（推荐）

```yaml
# docker-compose.yml
version: "3.8"

services:
  funasr:
    image: registry.cn-hangzhou.aliyuncs.com/funasr_repo/funasr:runtime-sdk-cpu-0.4.7
    container_name: funasr-server
    restart: unless-stopped
    ports:
      - "10095:10095"
    volumes:
      # 模型目录（持久化，避免反复下载）
      - ./funasr-runtime-resources/models:/workspace/models
      # 日志目录
      - ./funasr-runtime-resources/logs:/workspace/logs
    environment:
      - TZ=Asia/Shanghai
    command: >
      bash -c "
      cd /workspace/FunASR/runtime/websocket/build/bin &&
      ./funasr-wss-server
        --model-dir /workspace/models/damo/speech_paraformer-large-vad-punc_asr_nat-zh-cn-16k-common-vocab8404-onnx
        --vad-dir /workspace/models/damo/speech_fsmn_vad_zh-cn-16k-common-onnx
        --punc-dir /workspace/models/damo/punc_ct-transformer_cn-en-common-vocab471067-large-onnx
        --itn-dir /workspace/models/thuduj12/fst_itn_zh
        --lm-dir /workspace/models/damo/speech_ngram_lm_zh-cn-ai-wesp-fst
        --port 10095
        --certfile ''
        --decoder-thread-num 4
        --io-thread-num 2
        --model-thread-num 2
        > /workspace/logs/server.log 2>&1
      "
    healthcheck:
      test: ["CMD", "bash", "-c", "echo > /dev/tcp/127.0.0.1/10095"]
      interval: 30s
      timeout: 5s
      retries: 3
      start_period: 60s

  # 可选：GPU 版本
  # funasr-gpu:
  #   image: registry.cn-hangzhou.aliyuncs.com/funasr_repo/funasr:runtime-sdk-gpu-0.4.7
  #   container_name: funasr-server-gpu
  #   restart: unless-stopped
  #   ports:
  #     - "10095:10095"
  #   volumes:
  #     - ./funasr-runtime-resources/models:/workspace/models
  #     - ./funasr-runtime-resources/logs:/workspace/logs
  #   environment:
  #     - TZ=Asia/Shanghai
  #   deploy:
  #     resources:
  #       reservations:
  #         devices:
  #           - driver: nvidia
  #             count: 1
  #             capabilities: [gpu]
  #   command: >
  #     bash -c "
  #     cd /workspace/FunASR/runtime/websocket/build/bin &&
  #     ./funasr-wss-server
  #       --model-dir /workspace/models/damo/speech_paraformer-large-vad-punc_asr_nat-zh-cn-16k-common-vocab8404-onnx
  #       --vad-dir /workspace/models/damo/speech_fsmn_vad_zh-cn-16k-common-onnx
  #       --punc-dir /workspace/models/damo/punc_ct-transformer_cn-en-common-vocab471067-large-onnx
  #       --itn-dir /workspace/models/thuduj12/fst_itn_zh
  #       --lm-dir /workspace/models/damo/speech_ngram_lm_zh-cn-ai-wesp-fst
  #       --port 10095
  #       --decoder-thread-num 8
  #       --io-thread-num 2
  #       > /workspace/logs/server.log 2>&1
  #     "
```

### 5.3 线程参数调优

| 参数 | 说明 | CPU 建议值 | GPU 建议值 |
|------|------|-----------|-----------|
| `--decoder-thread-num` | 解码线程数 | CPU 核数 / 2 | 4-8 |
| `--io-thread-num` | IO 线程数 | 2 | 2 |
| `--model-thread-num` | 模型推理线程数 | CPU 核数 / 4 | 1-2 |

---

## 六、网络与安全

### 6.1 端口规划

| 服务 | 端口 | 协议 | 说明 |
|------|------|------|------|
| FunASR WebSocket | 10095 | TCP/WS | 语音识别 WebSocket 服务 |
| zngp-server | 8080 | TCP/HTTP | 业务 Web 服务 |

### 6.2 防火墙规则

```bash
# 如果 FunASR 和 zngp-server 在同一台机器
# 10095 只需绑定 127.0.0.1，不对外暴露
docker run ... -p 127.0.0.1:10095:10095 ...

# 如果分开部署，需开放内网访问
iptables -A INPUT -p tcp --dport 10095 -s 192.168.0.0/16 -j ACCEPT
```

### 6.3 安全建议

- FunASR WebSocket 服务本身无认证，建议：
  - 仅绑定内网 IP，不暴露到公网
  - 或在前端加 Nginx 反向代理 + Basic Auth
- 定期更新 Docker 镜像
- 模型文件设置只读权限

---

## 七、Go 服务端适配

### 7.1 配置变更 (`cfg.yml`)

```yaml
asr:
  provider: "funasr_local"           # 从 aliyun_bailian 改为 funasr_local
  # 新增私有化部署配置
  funasr:
    ws_url: "ws://127.0.0.1:10095"   # FunASR WebSocket 地址
    mode: "2pass"                     # 识别模式: offline / 2pass / online
    chunk_size: [5, 10, 5]           # 2pass 模式的分块大小（秒）
    hotwords: ""                      # 可选：热词文件路径
```

### 7.2 代码改造要点

需要在 `server/internal/service/asr.go` 中新增 FunASR WebSocket 客户端实现，替代当前的阿里云 API 调用：

```go
// 伪代码示意
func TranscribeAudioLocal(audioPath string) (string, error) {
    // 1. 读取音频文件
    // 2. 连接 ws://127.0.0.1:10095
    // 3. 发送握手消息 {"mode": "2pass", "chunk_size": [5,10,5], "wav_name": "xxx"}
    // 4. 分块发送 PCM 音频数据
    // 5. 发送 {"is_speaking": false} 结束
    // 6. 接收并拼接识别结果
    // 7. 返回完整文本
}
```

参考现有 `scripts/test_funasr.py` 中的 WebSocket 协议实现。

---

## 八、扩容方案

### 8.1 单机扩容

```bash
# 多实例部署（不同端口）
docker run -d --name funasr-1 -p 10095:10095 ...
docker run -d --name funasr-2 -p 10096:10095 ...
docker run -d --name funasr-3 -p 10097:10095 ...

# 在 Go 服务端做负载均衡（轮询）
```

### 8.2 Nginx 反向代理 + 负载均衡

```nginx
upstream funasr_backend {
    server 127.0.0.1:10095 max_fails=3 fail_timeout=30s;
    server 127.0.0.1:10096 max_fails=3 fail_timeout=30s;
    server 127.0.0.1:10097 max_fails=3 fail_timeout=30s;
}

server {
    listen 10095;
    location / {
        proxy_pass http://funasr_backend;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_read_timeout 3600s;
    }
}
```

### 8.3 多机扩容

| 设备 | 角色 | 数量 | 规格 |
|------|------|------|------|
| 服务器 A | FunASR 节点 1 | 1 | 8C/16G/100G SSD |
| 服务器 B | FunASR 节点 2 | 1 | 8C/16G/100G SSD |
| 服务器 C | 负载均衡 + zngp-server | 1 | 4C/8G/50G SSD |

---

## 九、资源用量估算

### 9.1 单路推理资源

| 指标 | CPU 模式 | GPU 模式 |
|------|----------|----------|
| 实时率 (RTF) | 0.02-0.05 | 0.005-0.01 |
| 内存占用 | 200-500 MB | 200-500 MB |
| CPU 占用 | 1-2 核 | 0.5-1 核 |

### 9.2 并发能力估算

| 配置 | 最大并发 | 日处理量 (8h) |
|------|----------|---------------|
| 4C/8G | 5 路 | ~240 小时音频 |
| 8C/16G | 15 路 | ~720 小时音频 |
| 16C/32G | 30 路 | ~1440 小时音频 |
| GPU T4 | 50+ 路 | ~2400 小时音频 |

### 9.3 存储估算

| 数据类型 | 单条大小 | 1000 条 | 10000 条 |
|----------|---------|----------|----------|
| 原始音频 (WAV 16kHz) | ~11 MB/min | 取决于时长 | 取决于时长 |
| 转写文本 | < 10 KB | < 10 MB | < 100 MB |
| 模型文件 | 1.7 GB | 固定 | 固定 |

---

## 十、部署检查清单

### 阶段一：环境准备

- [ ] 确认服务器 CPU 架构为 x86_64（不支持 ARM）
- [ ] 安装 Docker 20.10+
- [ ] 配置 Docker 镜像加速（国内环境）
- [ ] 创建模型存储目录 `funasr-runtime-resources/models/`
- [ ] 配置防火墙规则

### 阶段二：模型准备

- [ ] 下载 5 个模型文件到 `funasr-runtime-resources/models/`
- [ ] 验证模型目录结构正确
- [ ] 验证模型文件完整性（SHA256）

### 阶段三：服务部署

- [ ] 拉取 FunASR Docker 镜像
- [ ] 启动容器，验证服务监听 10095 端口
- [ ] 运行 `scripts/test_funasr.py` 验证 WebSocket 连通性
- [ ] 测试真实音频识别，确认返回结果正确

### 阶段四：业务集成

- [ ] 修改 `cfg.yml` ASR 配置
- [ ] 改造 `server/internal/service/asr.go` 支持 WebSocket 客户端
- [ ] 端到端测试：上传音频 → 转写 → 结果展示
- [ ] 压测验证并发能力

### 阶段五：生产加固

- [ ] 配置 Docker 容器自动重启 (`--restart=unless-stopped`)
- [ ] 配置日志轮转（防止磁盘写满）
- [ ] 配置监控告警（CPU/内存/磁盘/服务存活）
- [ ] 编写运维文档（启动/停止/升级/回滚流程）

---

## 十一、常见问题

### Q1: 容器启动后模型下载失败？

内网环境无法访问 ModelScope，需通过代理或手动下载模型后挂载。

```bash
# 设置代理
export HTTP_PROXY=http://proxy3.bj.petrochina:8080
export HTTPS_PROXY=http://proxy3.bj.petrochina:8080
```

### Q2: ARM 架构服务器（如 Apple Silicon、鲲鹏）能否部署？

FunASR runtime SDK 官方镜像仅支持 x86_64。ARM 设备需自行编译或使用 App 端离线方案（Sherpa-ONNX）。

### Q3: 如何确认服务是否正常？

```bash
# 检查端口
netstat -tlnp | grep 10095

# 检查日志
docker logs -f funasr-server

# 运行测试脚本
cd scripts && python test_funasr.py
```

### Q4: 内存不足怎么办？

- 减少 `--decoder-thread-num` 参数
- 使用 GPU 版本（模型加载到显存）
- 限制并发连接数