# Sandbox for long gauntlet runs: Java 25 (JDK only, no build tools) + Claude Code behind an egress firewall.
FROM eclipse-temurin:25-jdk AS jdk

FROM node:22-bookworm

ARG TZ=Europe/Stockholm
ENV TZ="$TZ"

RUN apt-get update && apt-get install -y --no-install-recommends \
    git less procps sudo unzip jq gh vim nano tmux \
    iptables ipset iproute2 dnsutils aggregate curl ca-certificates \
  && apt-get clean && rm -rf /var/lib/apt/lists/*

# JDK 25 (Temurin) from the official image
ENV JAVA_HOME=/opt/java/openjdk
COPY --from=jdk /opt/java/openjdk $JAVA_HOME

ENV PATH=$JAVA_HOME/bin:$PATH

ENV DEVCONTAINER=true
RUN mkdir -p /workspace /home/node/.claude /commandhistory \
  && chown -R node:node /workspace /home/node/.claude /commandhistory

# Claude Code
ARG CLAUDE_CODE_VERSION=latest
ENV NPM_CONFIG_PREFIX=/usr/local/share/npm-global
ENV PATH=$PATH:/usr/local/share/npm-global/bin
RUN mkdir -p $NPM_CONFIG_PREFIX && chown node:node $NPM_CONFIG_PREFIX
USER node
RUN npm install -g @anthropic-ai/claude-code@${CLAUDE_CODE_VERSION}
RUN echo "export PROMPT_COMMAND='history -a'; export HISTFILE=/commandhistory/.bash_history" >> ~/.bashrc

# Firewall: node may run exactly this script as root, nothing else
USER root
COPY init-firewall.sh allowed-domains.txt /usr/local/bin/
RUN chmod +x /usr/local/bin/init-firewall.sh \
  && echo "node ALL=(root) NOPASSWD: /usr/local/bin/init-firewall.sh" > /etc/sudoers.d/node-firewall \
  && chmod 0440 /etc/sudoers.d/node-firewall
USER node
WORKDIR /workspace
