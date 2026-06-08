#!/data/data/com.termux/files/usr/bin/bash
# WechatBot 自启动脚本
# 由 WechatBotApp 在开机时自动调用

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
LOG_FILE="$SCRIPT_DIR/bot.log"

echo "[Boot] $(date) - 正在启动微信AI助手..." >> "$LOG_FILE"

# 获取 wake lock 防止休眠
termux-wake-lock 2>/dev/null

# 确保 Python 可用
if ! command -v python &> /dev/null; then
    echo "[Boot] 错误: Python 未安装" >> "$LOG_FILE"
    exit 1
fi

# 安装依赖（如果需要）
pip install pycryptodome pilk 2>/dev/null

# 启动 bot
cd "$SCRIPT_DIR"
python ZynWechatBot_decrypted.py >> "$LOG_FILE" 2>&1 &

BOT_PID=$!
echo "[Boot] Bot 已启动, PID: $BOT_PID" >> "$LOG_FILE"

# 等待进程结束
wait $BOT_PID
echo "[Boot] Bot 已退出, 退出码: $?" >> "$LOG_FILE"
