#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
手表端联调工具：在电脑上**假装自己是手机**

手表装好后如果连不上，最麻烦的是分不清「手表没发出来」还是「手机没回」。
这个脚本让电脑顶替手机：手表会把它当成手机连上来，每一句指令都打出来。

用法：
    python tools/probe-watch.py

然后手表上打开草记手表端（手机端可以先关掉，免得抢包），
看到 `[手表] ping` 就说明手表发得出去；
按开始/结束会分别打印 start / stop。

注意：
  · 电脑要和手表在同一个 Wi-Fi；
  · 手表是广播找手机的，Windows 防火墙第一次会弹窗问，**要允许**
    否则包进不来（这是最常见的原因）；
  · 手机端开着的时候也在监听同一个端口，两边会抢——调手表时把手机端开关关掉。
"""

import json
import socket
import sys
import time

PORT = 47321


def main() -> int:
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        sock.bind(("0.0.0.0", PORT))
    except OSError as e:
        print(f"端口 {PORT} 绑不上：{e}")
        print("可能是草记手机端还开着（它也监听这个端口），先把它关掉。")
        return 1

    print(f"我在 UDP {PORT} 上等手表…（Ctrl+C 退出）")
    print("手表上打开草记手表端；如果它已经在找手机，等它自己找到或拔腕重开。\n")

    state = "idle"
    elapsed = 0
    seg = 1
    marks = 0
    t0 = 0.0
    last_ping = 0.0

    while True:
        try:
            data, addr = sock.recvfrom(512)
        except KeyboardInterrupt:
            print("\n退出。")
            return 0
        except OSError:
            continue

        text = data.decode("utf-8", "ignore")
        try:
            cmd = json.loads(text).get("c", "")
        except json.JSONDecodeError:
            cmd = text.strip()

        now = time.time()
        if state == "rec":
            elapsed = int((now - t0) * 1000)

        if cmd == "start":
            state, seg, marks = "rec", 1, 0
            t0 = now
            print(f"[手表] start   ← {addr[0]}")
        elif cmd == "stop":
            elapsed = int((now - t0) * 1000) if state == "rec" else elapsed
            state = "idle"
            print(f"[手表] stop    ← {addr[0]}  本次 {elapsed // 1000} 秒")
        elif cmd == "mark":
            marks += 1
            print(f"[手表] mark    ← {addr[0]}  第 {marks} 处")
        else:
            # ping 很密（每 3 秒一次），只在第一次和断线重连时打，免刷屏
            if now - last_ping > 10:
                print(f"[手表] ping    ← {addr[0]}")
            last_ping = now

        reply = {
            "ok": True,
            "st": state,
            "el": elapsed,
            "seg": seg,
            "mk": marks,
            "name": "电脑（联调）",
        }
        body = json.dumps(reply, ensure_ascii=False).encode("utf-8")
        sock.sendto(body, addr)


if __name__ == "__main__":
    sys.exit(main())
