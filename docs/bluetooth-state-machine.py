# -*- coding: utf-8 -*-
"""按 Kotlin 里 BluetoothWatcher 的逻辑复刻状态机，用虚拟时钟推演各种断连场景。"""

CONNECT_GRACE = 500     # ms
DISCONNECT_GRACE = 3000 # ms
ST_CONNECTED, ST_DISCONNECTED, ST_CONNECTING, ST_DISCONNECTING = 2, 0, 1, 3


class Watcher:
    def __init__(self):
        self.hfp = {}
        self.a2dp = {}
        self.last = False
        self.pending_connect = False
        self.pending_disconnect = False
        self.events = []       # (time, kind)
        self.timers = []       # (due, kind)
        self.now = 0

    def _any(self):
        return bool(self.hfp) or bool(self.a2dp)

    def _name(self):
        return next(iter(self.a2dp.values()), None) or next(iter(self.hfp.values()), None) or "蓝牙耳机"

    def _remove_timer(self, kind):
        self.timers = [(d, k) for (d, k) in self.timers if k != kind]

    def broadcast(self, profile, state, addr="AA:BB", name="FreeBuds"):
        m = self.hfp if profile == "HFP" else self.a2dp
        if state == ST_CONNECTED:
            m[addr] = name
        elif state == ST_DISCONNECTED:
            m.pop(addr, None)
        else:
            return          # 中间态直接忽略（修复点①）
        self._evaluate()

    def _evaluate(self):
        now = self._any()
        if now:
            if self.pending_disconnect:
                self.pending_disconnect = False
                self._remove_timer("disc")
            if not self.last and not self.pending_connect:
                self.pending_connect = True
                self.timers.append((self.now + CONNECT_GRACE, "conn"))
        else:
            if self.pending_connect:
                self.pending_connect = False
                self._remove_timer("conn")
            if self.last and not self.pending_disconnect:
                self.pending_disconnect = True
                self.timers.append((self.now + DISCONNECT_GRACE, "disc"))

    def advance(self, ms):
        """推进时间，按到期顺序触发定时器"""
        target = self.now + ms
        while True:
            due = [t for t in self.timers if t[0] <= target]
            if not due:
                break
            due.sort()
            d, k = due[0]
            self.timers.remove((d, k))
            self.now = d
            if k == "conn":
                self.pending_connect = False
                if self._any() and not self.last:
                    self.last = True
                    self.events.append((self.now, "CONNECTED:" + self._name()))
            else:
                self.pending_disconnect = False
                if not self._any() and self.last:
                    self.last = False
                    self.events.append((self.now, "DISCONNECTED"))
        self.now = target

    def bt_off(self):
        self.hfp.clear()
        self.a2dp.clear()
        self.pending_connect = self.pending_disconnect = False
        self.timers = []
        if self.last:
            self.last = False
            self.events.append((self.now, "DISCONNECTED(蓝牙关闭)"))


def case(title, script, expect):
    w = Watcher()
    script(w)
    got = [k for (_, k) in w.events]
    ok = got == expect
    print("\n%s" % title)
    print("   期望事件：%s" % (expect or "（无）"))
    print("   实际事件：%s" % (got or "（无）"))
    print("   结论：%s" % ("通过 ✅" if ok else "未通过 ❌"))
    return ok


results = []

# 场景1（主因复现）：录音中系统切音频通路
def s1(w):
    w.broadcast("A2DP", ST_CONNECTED); w.advance(600)      # 连上 → 触发
    w.broadcast("A2DP", ST_DISCONNECTING)                  # 开始录音，系统切通路
    w.broadcast("A2DP", ST_DISCONNECTED)
    w.advance(1500)                                        # 1.5 秒后 HFP 才连上
    w.broadcast("HFP", ST_CONNECTED)
    w.advance(4000)                                        # 等超过宽限期
results.append(case("① 录音中系统把 A2DP 切到 HFP（中间 1.5s 两侧都不是 CONNECTED）",
                    s1, ["CONNECTED:FreeBuds"]))

# 场景2：耳机真的拔掉
def s2(w):
    w.broadcast("A2DP", ST_CONNECTED); w.broadcast("HFP", ST_CONNECTED); w.advance(600)
    w.broadcast("A2DP", ST_DISCONNECTED)
    w.broadcast("HFP", ST_DISCONNECTED)
    w.advance(4000)
results.append(case("② 耳机真的拔掉（两路都断开，3 秒后确认）",
                    s2, ["CONNECTED:FreeBuds", "DISCONNECTED"]))

# 场景3：短暂断连 2 秒内恢复 → 不应停止录音
def s3(w):
    w.broadcast("A2DP", ST_CONNECTED); w.advance(600)
    w.broadcast("A2DP", ST_DISCONNECTED)
    w.advance(2000)
    w.broadcast("A2DP", ST_CONNECTED)      # 宽限期内恢复
    w.advance(5000)
results.append(case("③ 短暂断连 2 秒内自动恢复（宽限期内撤销）",
                    s3, ["CONNECTED:FreeBuds"]))

# 场景4：一路断开但另一路还在
def s4(w):
    w.broadcast("A2DP", ST_CONNECTED); w.broadcast("HFP", ST_CONNECTED); w.advance(600)
    w.broadcast("A2DP", ST_DISCONNECTED)   # HFP 仍连着
    w.advance(5000)
results.append(case("④ 只断 A2DP、HFP 仍连接（不应判定断开）",
                    s4, ["CONNECTED:FreeBuds"]))

# 场景5：连接时两路先后上报 → 只触发一次
def s5(w):
    w.broadcast("HFP", ST_CONNECTED)
    w.advance(200)
    w.broadcast("A2DP", ST_CONNECTED)      # 500ms 内两路到齐
    w.advance(3000)
results.append(case("⑤ 连接时 A2DP / HFP 相继上报（只触发一次，不重复开始录音）",
                    s5, ["CONNECTED:FreeBuds"]))

# 场景6：蓝牙直接关掉 → 立即断开，不等宽限期
def s6(w):
    w.broadcast("A2DP", ST_CONNECTED); w.advance(600)
    w.bt_off()
    w.advance(100)
results.append(case("⑥ 关掉蓝牙（立即判定断开，不进宽限期）",
                    s6, ["CONNECTED:FreeBuds", "DISCONNECTED(蓝牙关闭)"]))

# 场景7：重连后再次断开 → 正常再来一次
def s7(w):
    w.broadcast("A2DP", ST_CONNECTED); w.advance(600)
    w.broadcast("A2DP", ST_DISCONNECTED); w.advance(3500)
    w.broadcast("A2DP", ST_CONNECTED); w.advance(600)
    w.broadcast("A2DP", ST_DISCONNECTED); w.advance(3500)
results.append(case("⑦ 断开→重连→再断开（每次都正确触发）",
                    s7, ["CONNECTED:FreeBuds", "DISCONNECTED",
                         "CONNECTED:FreeBuds", "DISCONNECTED"]))

print("\n" + "=" * 60)
print("总体：%d/%d 通过 %s" % (sum(results), len(results), "✅" if all(results) else "❌"))
