"""
Android 启动器 - 自动运行 ZynWechatBot 并跳过终端交互循环
将 stdout/stderr 重定向到日志文件供 Android 端读取
"""
import sys
import os
import time
import threading

# ── 日志重定向 ──────────────────────────────────────────────
class AndroidLogWriter:
    """将输出同时写入日志文件和原始 stdout"""
    def __init__(self, log_path, original):
        self._log_path = log_path
        self._original = original
        self._file = open(log_path, 'a', encoding='utf-8')

    def write(self, s):
        if not s:
            return
        try:
            self._file.write(s)
            if not s.endswith('\n'):
                self._file.write('\n')
            self._file.flush()
        except Exception:
            pass
        try:
            self._original.write(s)
        except Exception:
            pass

    def flush(self):
        try:
            self._file.flush()
        except Exception:
            pass
        try:
            self._original.flush()
        except Exception:
            pass

def setup_logging():
    """重定向 stdout/stderr 到日志文件"""
    log_dir = os.environ.get('HOME', '/data/data/com.sioboot.wechatbot/files')
    log_path = os.path.join(log_dir, 'python_stdout.log')
    sys.stdout = AndroidLogWriter(log_path, sys.__stdout__)
    sys.stderr = AndroidLogWriter(log_path, sys.__stderr__)
    print(f"[Android] 日志文件: {log_path}")

# ── 修补 input() 以避免阻塞 ──────────────────────────────────
def _android_input(prompt=""):
    """Android 环境下无终端，input() 返回空字符串"""
    if prompt:
        print(prompt)
    # 等待直到进程被终止
    try:
        while True:
            time.sleep(3600)
    except KeyboardInterrupt:
        return ""

# ── 主逻辑 ──────────────────────────────────────────────────
def main():
    setup_logging()

    print("=" * 60)
    print("[Android] WeChat Bot Android 版启动中...")
    print(f"[Android] 工作目录: {os.getcwd()}")
    print(f"[Android] Python: {sys.version}")
    print("=" * 60)

    # 替换 input() 为阻塞等待（Android 无终端）
    import builtins
    builtins.input = _android_input

    # 禁止 _open_browser 在 Python 端调用（由 Android 端处理）
    os.environ['ANDROID_APP'] = '1'

    # 导入并运行主脚本
    script_dir = os.path.dirname(os.path.abspath(__file__))
    main_script = os.path.join(script_dir, 'ZynWechatBot_decrypted.py')

    print(f"[Android] 加载主脚本: {main_script}")

    with open(main_script, 'r', encoding='utf-8') as f:
        code = f.read()

    # 在全局命名空间中执行
    exec(compile(code, main_script, 'exec'), {'__name__': '__main__', '__file__': main_script})

if __name__ == '__main__':
    main()
