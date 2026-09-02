#!/usr/bin/env bash
# ============================================
# 糖果熊 QQ Bot 一键部署脚本
# 直接运行: ./deploy.sh
# 前提: 本机能 SSH 到 154.8.213.134，且 SSH 密钥已配置
# 服务器需安装: apt install -y maven openjdk-17-jdk-headless screen
# ============================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${SCRIPT_DIR}"

# ---- 服务器配置（已填好，直接可用）----
REMOTE_USER="root"
REMOTE_HOST="154.8.213.134"
REMOTE_DIR="/opt/qq-bot"
JAR_NAME="untitled-1.0-SNAPSHOT.jar"
SCREEN_NAME="qq-bot"

# ---- 自动找 SSH 密钥 ----
if [[ -f "$HOME/.ssh/id_ed25519" ]]; then
  SSH_KEY="$HOME/.ssh/id_ed25519"
elif [[ -f "$HOME/.ssh/id_rsa" ]]; then
  SSH_KEY="$HOME/.ssh/id_rsa"
else
  echo "❌ 未找到 SSH 私钥 (~/.ssh/id_ed25519 或 ~/.ssh/id_rsa)"
  exit 1
fi
SSH_OPTS="-i ${SSH_KEY} -o StrictHostKeyChecking=no -o ConnectTimeout=10"
SCP_OPTS="-i ${SSH_KEY} -o StrictHostKeyChecking=no -o ConnectTimeout=10"

echo "🚀 部署到 ${REMOTE_USER}@${REMOTE_HOST}:${REMOTE_DIR}"
echo ""

# ---- 预检 SSH 连通性 ----
echo "🔗 检测 SSH 连通性..."
if ! ssh ${SSH_OPTS} -o ConnectTimeout=5 "${REMOTE_USER}@${REMOTE_HOST}" "echo OK" 2>/dev/null | grep -q "OK"; then
  echo "❌ 无法连接到 ${REMOTE_USER}@${REMOTE_HOST}"
  echo "   请确认 SSH 密钥已配置，服务器可访问"
  exit 1
fi
echo "  ✅ SSH 连通正常"
echo ""

# ======== [1/7] 打包 ========
echo "==> [1/7] Maven 测试并打包..."
if ! mvn clean test package -q; then
  echo "❌ Maven 打包失败"
  exit 1
fi
if [[ ! -f "target/${JAR_NAME}" ]]; then
  echo "❌ 打包失败，未找到 target/${JAR_NAME}"
  exit 1
fi
LOCAL_SHA=$(sha256sum "target/${JAR_NAME}" | awk '{print $1}')
echo "  SHA256: ${LOCAL_SHA}"
echo "  ✅ 打包完成"

# ======== [2/7] 上传 JAR ========
echo "==> [2/7] 上传 JAR 到服务器..."
if ! scp ${SCP_OPTS} "target/${JAR_NAME}" "${REMOTE_USER}@${REMOTE_HOST}:${REMOTE_DIR}/${JAR_NAME}.new"; then
  echo "❌ 上传失败"
  exit 1
fi
REMOTE_SHA=$(ssh ${SSH_OPTS} "${REMOTE_USER}@${REMOTE_HOST}" "sha256sum '${REMOTE_DIR}/${JAR_NAME}.new' | awk '{print \$1}'")
if [[ "${LOCAL_SHA}" != "${REMOTE_SHA}" ]]; then
  echo "❌ 上传文件校验失败：本地=${LOCAL_SHA} 服务器=${REMOTE_SHA}"
  exit 1
fi
echo "  ✅ 上传完成"

# ======== [3/7] 上传 Python 脚本 ========
echo "==> [3/7] 上传 Python 脚本到服务器..."
if ! scp ${SCP_OPTS} src/main/resources/screenshot.py src/main/resources/edge_tts.py "${REMOTE_USER}@${REMOTE_HOST}:${REMOTE_DIR}/"; then
  echo "❌ 上传 Python 脚本失败"
  exit 1
fi
echo "  ✅ Python 脚本上传完成"

# ======== [4/7] 停止旧进程 ========
echo "==> [4/7] 停止服务器上的旧进程..."
STOP_OUT=$(ssh ${SSH_OPTS} "${REMOTE_USER}@${REMOTE_HOST}" bash -s <<'REMOTE_STOP'
set -e
cd /opt/qq-bot || { echo "FATAL: /opt/qq-bot 不存在"; exit 1; }

# 停止 screen 会话
if command -v screen &>/dev/null && screen -list 2>/dev/null | grep -q "qq-bot"; then
  echo "  关闭 screen 会话..."
  screen -S qq-bot -X quit || true
  sleep 2
  echo "  screen 会话已关闭"
else
  echo "  无需关闭 screen（无运行中会话）"
fi

# 杀掉残留进程
if pgrep -f "java.*untitled-1.0-SNAPSHOT.jar" >/dev/null 2>&1; then
  echo "  杀掉残留 Java 进程..."
  pkill -f "java.*untitled-1.0-SNAPSHOT.jar" || true
  sleep 2
  echo "  残留进程已清理"
else
  echo "  无残留进程"
fi

echo "STOP_OK"
REMOTE_STOP
)

if [[ "$STOP_OUT" != *"STOP_OK"* ]]; then
  echo "❌ 停止旧进程失败:"
  echo "$STOP_OUT"
  exit 1
fi
echo "  ✅ 旧进程已停止"

# ======== [5/7] 替换 JAR ========
echo "==> [5/7] 替换 JAR（保留当前版本和1个回滚备份）..."
REPLACE_OUT=$(ssh ${SSH_OPTS} "${REMOTE_USER}@${REMOTE_HOST}" bash -s <<'REMOTE_REPLACE'
set -e
cd /opt/qq-bot || { echo "FATAL: /opt/qq-bot 不存在"; exit 1; }

if [[ -f "untitled-1.0-SNAPSHOT.jar" ]]; then
  cp "untitled-1.0-SNAPSHOT.jar" "untitled-1.0-SNAPSHOT.jar.bak.$(date +%Y%m%d_%H%M%S)"
  echo "  旧 JAR 已备份"
fi

mv "untitled-1.0-SNAPSHOT.jar.new" "untitled-1.0-SNAPSHOT.jar"

# 只保留最近1个回滚备份；逐个按 find 结果处理，避免 xargs 在空格文件名上误删。
mapfile -t backups < <(find . -maxdepth 1 -type f -name 'untitled-1.0-SNAPSHOT.jar.bak.*' -printf '%T@ %p\n' | sort -nr | cut -d' ' -f2-)
for old_backup in "${backups[@]:1}"; do
  rm -f -- "${old_backup}"
done

echo "REPLACE_OK"
REMOTE_REPLACE
)

if [[ "$REPLACE_OUT" != *"REPLACE_OK"* ]]; then
  echo "❌ 替换 JAR 失败:"
  echo "$REPLACE_OUT"
  exit 1
fi
echo "  ✅ JAR 已替换"

# ======== [6/7] 启动 ========
echo "==> [6/7] 启动糖果熊..."
START_OUT=$(ssh ${SSH_OPTS} "${REMOTE_USER}@${REMOTE_HOST}" bash -s <<'REMOTE_START'
set -e
cd /opt/qq-bot || { echo "FATAL: /opt/qq-bot 不存在"; exit 1; }

# 加载环境变量
if [[ -f .env ]]; then
  set -a
  source .env
  set +a
  echo "  环境变量已加载"
else
  echo "  ⚠ .env 未找到，使用默认配置"
fi

JAVA_OPTS="${JAVA_OPTS:--Xms256m -Xmx768m}"

# 启动
screen -S qq-bot -d -m bash -c "
  source /opt/qq-bot/.env 2>/dev/null || true
  java ${JAVA_OPTS} -jar /opt/qq-bot/untitled-1.0-SNAPSHOT.jar >> /opt/qq-bot/qq-bot.log 2>&1
"

sleep 3

if screen -list 2>/dev/null | grep -q "qq-bot"; then
  echo "START_OK"
else
  echo "❌ screen 会话未出现"
  echo "--- 最后 40 行日志 ---"
  tail -40 /opt/qq-bot/qq-bot.log 2>/dev/null || echo "(日志为空)"
  exit 1
fi
REMOTE_START
)

if [[ "$START_OUT" != *"START_OK"* ]]; then
  echo "❌ 启动失败:"
  echo "$START_OUT"
  exit 1
fi
echo "  ✅ 糖果熊启动成功"

echo ""
echo "========================================="
echo "  ✅ 部署完成！"
echo ""
echo "  查看实时日志: ssh root@154.8.213.134 \"tail -f /opt/qq-bot/qq-bot.log\""
echo "  进入控制台:   ssh root@154.8.213.134 \"screen -r qq-bot\""
echo "  查看状态:     ssh root@154.8.213.134 \"screen -list\""
echo "========================================="
