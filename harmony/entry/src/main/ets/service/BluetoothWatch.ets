import { connection, constant } from '@kit.ConnectivityKit';
import { BusinessError } from '@kit.BasicServicesKit';

export interface BtEvents {
  onConnected: (name: string) => void;
  onDisconnected: () => void;
}

/**
 * 蓝牙耳机连接监听
 *
 * 鸿蒙没有 Android 那种「A2DP 连上广播」，这里用轮询 profile 连接状态实现同一效果：
 * 每 3 秒看一次 A2DP / HFP 的连接状态，状态翻转时回调。
 * 轮询比监听注册更稳（不同厂商 ROM 对 profile 监听的支持差异很大），代价是 3 秒延迟。
 *
 * 说明：连接上耳机是「开始记录」的触发条件，不是必要条件——
 * 用户随时可以用今日页的手动按钮记录。
 *
 * API 命名空间（易错点，DevEco 首次 Sync 常在这里报错）：
 *   profile 状态查询在 connection 模块，枚举在 constant 模块：
 *   connection.getProfileConnectionState(constant.ProfileId.PROFILE_A2DP_SOURCE)
 */
export class BluetoothWatch {
  private events: BtEvents | null = null;
  private timerId: number = -1;
  private connected: boolean = false;
  private deviceName: string = '蓝牙耳机';

  start(events: BtEvents): void {
    this.events = events;
    this.stop();
    this.timerId = setInterval(() => {
      this.poll();
    }, 3000);
    this.poll();
  }

  stop(): void {
    if (this.timerId >= 0) {
      clearInterval(this.timerId);
      this.timerId = -1;
    }
  }

  isConnected(): boolean {
    return this.connected;
  }

  name(): string {
    return this.deviceName;
  }

  private poll(): void {
    let now = false;
    try {
      now = BluetoothWatch.a2dpConnected() || BluetoothWatch.hfpConnected();
    } catch (e) {
      // 未授权或设备不支持蓝牙：当作未连接，不影响手动录音
      now = false;
    }
    if (now === this.connected) {
      return;
    }
    this.connected = now;
    if (now) {
      this.deviceName = this.resolveName();
      if (this.events !== null) {
        this.events.onConnected(this.deviceName);
      }
    } else {
      if (this.events !== null) {
        this.events.onDisconnected();
      }
    }
  }

  private static a2dpConnected(): boolean {
    const st: constant.ProfileConnectionState = connection.getProfileConnectionState(
      constant.ProfileId.PROFILE_A2DP_SOURCE
    );
    return st === constant.ProfileConnectionState.STATE_CONNECTED;
  }

  private static hfpConnected(): boolean {
    const st: constant.ProfileConnectionState = connection.getProfileConnectionState(
      // 手机侧是 HFP 的音频网关（AG）角色；SDK 里没有 PROFILE_HFP_SOURCE 这个名字
      constant.ProfileId.PROFILE_HANDSFREE_AUDIO_GATEWAY
    );
    return st === constant.ProfileConnectionState.STATE_CONNECTED;
  }

  /**
   * 取已连耳机名称
   *
   * 系统没有「当前 A2DP 连接的是哪台设备」的公开查询，只有全局 profile 状态。
   * 因此只在「已配对设备恰好一台」时才敢用它的名字，多台一律回落到通用名，
   * 宁可显示「蓝牙耳机」，也不显示成另一台设备的型号。
   */
  private resolveName(): string {
    try {
      const paired: Array<string> = connection.getPairedDevices();
      if (paired.length !== 1) {
        return '蓝牙耳机';
      }
      const name: string = connection.getRemoteDeviceName(paired[0]);
      if (name !== undefined && name !== null && name.length > 0) {
        return name;
      }
    } catch (e) {
      const err = e as BusinessError;
      console.info(`取蓝牙设备名失败：${err.message}`);
    }
    return '蓝牙耳机';
  }
}
