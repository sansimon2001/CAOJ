import socket from '@ohos.net.socket';
import { connection } from '@kit.NetworkKit';
import { BusinessError } from '@kit.BasicServicesKit';

/**
 * UDP 收包回调参数。
 * SDK 里 `on('message')` 给的是匿名结构 `{ message: ArrayBuffer, remoteInfo: SocketRemoteInfo }`，
 * 并没有 `SocketMessageInfo` 这个具名类型 —— 照着写会被推断成 any，ArkTS 直接报错。
 */
interface UdpMessage {
  message: ArrayBuffer;
  remoteInfo: socket.SocketRemoteInfo;
}

/**
 * 手表遥控 · 手机侧
 *
 * ## 为什么不用官方那条路
 *
 * Wear OS 手表与 Android 手机之间的标准通道是 Google Play 服务里的
 * Wearable Data Layer（MessageClient / DataClient）。但鸿蒙手机没有 GMS，
 * 也装不了 Wear OS 配套应用 —— 这条路在鸿蒙上根本不存在。
 *
 * 所以自建一条：**同一 Wi-Fi 下的 UDP**。
 *
 *   - 手表 → 手机：`{"c":"ping"}`（顺便用来发现手机 IP）
 *                  `{"c":"start"}` / `{"c":"stop"}` / `{"c":"mark"}`
 *   - 手机 → 手表：`{"ok":true,"st":"rec|idle|wait","el":毫秒,"seg":段号,"mk":打点数}`
 *
 * 为什么是 UDP 而不是 BLE：鸿蒙这边的 BLE 外设（GATT Server）在 API 12 上限制多、
 * 各机型差异大，还要配对；UDP 两边各十几行就能跑通，指令只有几个字节，
 * 丢了重发一次就行（手表每收到一次状态才知道，所以它本来就要定时问）。
 *
 * 为什么不是 HTTP：手机端在鸿蒙上开一个 HTTP 服务器需要 node 侧能力，
 * 而 UDP 用 `@kit.NetworkKit` 的 socket 就够，且广播包天然能当「发现」用。
 *
 * 局限写在明处：**手表和手机得在同一个 Wi-Fi**。手机开热点让手表连上也行。
 * 录音时手机已经有 `audioRecording` 长时任务，进程不会被冻结，UDP 收得到。
 */

/** 手表指令 */
export class WatchCmd {
  static readonly PING: string = 'ping';
  static readonly START: string = 'start';
  static readonly STOP: string = 'stop';
  static readonly MARK: string = 'mark';
  static readonly STATUS: string = 'status';
}

export interface WatchLinkEvents {
  onStart: () => void;
  onStop: () => void;
  onMark: () => void;
  /** 给界面显示「手表遥控：已开启（192.168.1.5:47321）」 */
  onStatus: (active: boolean, info: string) => void;
  onError: (msg: string) => void;
}

/** 手表发来的包 */
interface WatchPacket {
  c: string;
}

/** 回给手表的状态包 */
interface WatchReply {
  ok: boolean;
  st: string;
  el: number;
  seg: number;
  mk: number;
  name: string;
}

export class WatchLink {
  private static inst: WatchLink | null = null;

  static get(): WatchLink {
    if (WatchLink.inst === null) {
      WatchLink.inst = new WatchLink();
    }
    return WatchLink.inst;
  }

  static readonly PORT: number = 47321;

  private udp: socket.UDPSocket | null = null;
  private events: WatchLinkEvents | null = null;
  private running: boolean = false;
  /** 最近一次问过状态的手表地址：状态一变就单播回去，手表不用等下一个心跳 */
  private peerAddress: string = '';
  private peerPort: number = 0;
  private localIp: string = '';

  /** 当前录音快照，由 Index 在状态/计时变化时写进来 */
  private snapState: string = 'idle';
  private snapElapsed: number = 0;
  private snapSegment: number = 1;
  private snapMarks: number = 0;
  private deviceName: string = '草记';

  bind(events: WatchLinkEvents): void {
    this.events = events;
  }

  isRunning(): boolean {
    return this.running;
  }

  localAddress(): string {
    return this.localIp.length > 0 ? `${this.localIp}:${WatchLink.PORT}` : '';
  }

  /**
   * 更新快照（每秒都会被调一次，不推送）
   *
   * 秒表每秒走一格，如果每格都发一个 UDP 包，手表一天要多醒几百次——
   * 手表端自己按本地时间跳动就行，只有「状态翻转」才值得推。
   */
  update(state: string, elapsedMs: number, segment: number, marks: number): void {
    this.snapState = state;
    this.snapElapsed = elapsedMs;
    this.snapSegment = segment;
    this.snapMarks = marks;
  }

  /** 状态翻转时主动推一次：手表上那个圈圈要立刻变色，不能等下一次心跳 */
  pushNow(): void {
    this.pushToPeer();
  }

  // ==================== 开关 ====================

  async start(): Promise<boolean> {
    if (this.running) {
      return true;
    }
    try {
      const udp = socket.constructUDPSocketInstance();
      await udp.bind({ address: '0.0.0.0', port: WatchLink.PORT, family: 1 });
      udp.on('message', (info: UdpMessage) => {
        this.onPacket(info);
      });
      udp.on('error', (err: BusinessError) => {
        this.emitError(`手表遥控出错：${err.message}`);
      });
      this.udp = udp;
      this.running = true;
      this.localIp = await WatchLink.readLocalIp();
      this.emitStatus();
      return true;
    } catch (e) {
      const err = e as BusinessError;
      // 端口被占（比如上一次没释放干净）：明确说清，别静默失败
      this.emitError(`手表遥控没开起来：${err.message}（${err.code}）`);
      this.udp = null;
      this.running = false;
      this.emitStatus();
      return false;
    }
  }

  async stop(): Promise<void> {
    const udp = this.udp;
    this.udp = null;
    this.running = false;
    this.peerAddress = '';
    this.peerPort = 0;
    if (udp !== null) {
      try {
        udp.off('message');
      } catch (e) {
        // 忽略
      }
      try {
        await udp.close();
      } catch (e) {
        // 忽略
      }
    }
    this.emitStatus();
  }

  // ==================== 收包 ====================

  private onPacket(info: UdpMessage): void {
    // 记下来源，状态变化时可以直接回推，不用广播
    const remote = info.remoteInfo;
    if (remote !== undefined && remote !== null) {
      this.peerAddress = remote.address;
      this.peerPort = remote.port;
    }
    let text = '';
    try {
      text = WatchLink.bufToText(info.message);
    } catch (e) {
      return;
    }
    if (text.length === 0 || text.length > 200) {
      return;
    }
    let cmd = '';
    try {
      const p = JSON.parse(text) as WatchPacket;
      cmd = p.c;
    } catch (e) {
      // 不是 JSON 就当裸指令（"start" 这种），方便用 nc 之类直接测
      cmd = text.trim();
    }
    const ev = this.events;
    if (cmd === WatchCmd.PING || cmd === WatchCmd.STATUS) {
      this.pushToPeer();
      return;
    }
    if (ev === null) {
      return;
    }
    if (cmd === WatchCmd.START) {
      ev.onStart();
    } else if (cmd === WatchCmd.STOP) {
      ev.onStop();
    } else if (cmd === WatchCmd.MARK) {
      ev.onMark();
    }
    // 指令处理完立刻回一次状态，手表那边按钮才不用等
    this.pushToPeer();
  }

  private replyJson(): string {
    const r: WatchReply = {
      ok: true,
      st: this.snapState,
      el: this.snapElapsed,
      seg: this.snapSegment,
      mk: this.snapMarks,
      name: this.deviceName
    };
    return JSON.stringify(r);
  }

  private pushToPeer(): void {
    if (!this.running || this.peerAddress.length === 0) {
      return;
    }
    const payload = this.replyJson();
    this.send(payload, this.peerAddress, this.peerPort);
  }

  private send(text: string, address: string, port: number): void {
    const udp = this.udp;
    if (udp === null) {
      return;
    }
    // UDPSendOptions.address 要的是 NetAddress 对象（ip/port/family 一起给），不是裸字符串
    const target: connection.NetAddress = { address: address, port: port, family: 1 };
    const opt: socket.UDPSendOptions = {
      data: text,
      address: target
    };
    udp.send(opt).catch((err: BusinessError) => {
      // 手表走开了、IP 变了：不打扰用户，下一个 ping 会重新登记
      console.info(`回手表失败：${err.message}`);
    });
  }

  // ==================== 工具 ====================

  /** ArrayBuffer → UTF-8 字符串（鸿蒙没有现成的 TextDecoder 简写，手搓一遍） */
  private static bufToText(buf: ArrayBuffer): string {
    const bytes = new Uint8Array(buf);
    let out = '';
    let i = 0;
    while (i < bytes.length) {
      const b0 = bytes[i];
      if (b0 < 0x80) {
        out += String.fromCharCode(b0);
        i += 1;
      } else if (b0 >= 0xC0 && b0 < 0xE0 && i + 1 < bytes.length) {
        out += String.fromCharCode(((b0 & 0x1F) << 6) | (bytes[i + 1] & 0x3F));
        i += 2;
      } else if (b0 >= 0xE0 && b0 < 0xF0 && i + 2 < bytes.length) {
        out += String.fromCharCode(
          ((b0 & 0x0F) << 12) | ((bytes[i + 1] & 0x3F) << 6) | (bytes[i + 2] & 0x3F)
        );
        i += 3;
      } else {
        i += 1;
      }
    }
    return out;
  }

  /**
   * 取本机在当前网络下的 IPv4
   *
   * 不用 wifiManager.getIpInfo()：那要多申请一个 GET_WIFI_INFO 权限，
   * 而这里只是要一个「给手表输入的地址」，走默认网络的链路地址就够。
   */
  static async readLocalIp(): Promise<string> {
    try {
      const net = await connection.getDefaultNet();
      if (net === undefined || net === null || net.netId === 0) {
        return '';
      }
      const props = await connection.getConnectionProperties(net);
      const addrs = props.linkAddresses;
      if (addrs === undefined || addrs === null) {
        return '';
      }
      for (let i = 0; i < addrs.length; i++) {
        const a = addrs[i].address;
        if (a !== undefined && a !== null && a.address.length > 0
          && a.family === 1 && a.address !== '127.0.0.1') {
          return a.address;
        }
      }
    } catch (e) {
      const err = e as BusinessError;
      console.info(`取本机 IP 失败：${err.message}`);
    }
    return '';
  }

  private emitStatus(): void {
    const ev = this.events;
    if (ev !== null) {
      ev.onStatus(this.running, this.localAddress());
    }
  }

  private emitError(msg: string): void {
    const ev = this.events;
    if (ev !== null) {
      ev.onError(msg);
    }
  }
}
