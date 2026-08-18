# ============================================================
# zngp-server 运行时镜像 (Alpine)
# 二进制在宿主机 (Ubuntu) 上编译，此处只打包运行
# ============================================================
FROM alpine:3.21

# 国内 Alpine 镜像源（加速 apk 安装）
RUN echo "https://mirrors.aliyun.com/alpine/v3.21/main" > /etc/apk/repositories && \
    echo "https://mirrors.aliyun.com/alpine/v3.21/community" >> /etc/apk/repositories

RUN apk add --no-cache \
        ca-certificates tzdata \
    && cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime \
    && echo "Asia/Shanghai" > /etc/timezone

ENV LANG=C.UTF-8 \
    LC_ALL=C.UTF-8

WORKDIR /opt/zngp

COPY server .
COPY config.yaml ./config.yaml
COPY web ./web
COPY seed ./seed

RUN mkdir -p data/uploads

EXPOSE 8080

CMD ["./server"]