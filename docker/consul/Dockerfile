# 使用最新版本的 Ubuntu 作为基础镜像
FROM ubuntu:latest

# 设置维护者信息
LABEL maintainer="your_email@example.com"

# 更新系统并安装所需的依赖
RUN apt-get update && apt-get install -y \
    gnupg \
    software-properties-common \
    curl \
    && rm -rf /var/lib/apt/lists/*

# 添加 HashiCorp 的 GPG 密钥
RUN curl -fsSL https://apt.releases.hashicorp.com/gpg | gpg --dearmor -o /usr/share/keyrings/hashicorp-archive-keyring.gpg

# 添加 HashiCorp 的 APT 源
RUN echo "deb [arch=$(dpkg --print-architecture) signed-by=/usr/share/keyrings/hashicorp-archive-keyring.gpg] https://apt.releases.hashicorp.com $(lsb_release -cs) main" > /etc/apt/sources.list.d/hashicorp.list

# 更新 APT 并安装 Consul
RUN apt-get update && apt-get install -y consul && rm -rf /var/lib/apt/lists/*

# 创建 Consul 的工作目录
RUN mkdir -p /consul/data

# 设置工作目录
WORKDIR /consul

# 暴露 Consul 的默认端口
EXPOSE 8500 8301 8302 8600

# 添加默认的入口点
CMD ["consul","agent", "-dev"]
