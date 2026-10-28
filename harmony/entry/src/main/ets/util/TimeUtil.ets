/** 时间格式化：统一手写，不依赖 Intl（鸿蒙上 Intl 的时区行为随系统设置变化，容易出现跨端不一致） */
export class TimeUtil {
  private static pad(n: number): string {
    return n < 10 ? `0${n}` : `${n}`;
  }

  static dateFull(ts: number): string {
    const d = new Date(ts);
    return `${d.getFullYear()}-${TimeUtil.pad(d.getMonth() + 1)}-${TimeUtil.pad(d.getDate())}`;
  }

  static time(ts: number): string {
    const d = new Date(ts);
    return `${TimeUtil.pad(d.getHours())}:${TimeUtil.pad(d.getMinutes())}`;
  }

  static dateTime(ts: number): string {
    return `${TimeUtil.dateFull(ts)} ${TimeUtil.time(ts)}`;
  }

  /** 今天 00:00 的时间戳 */
  static todayStart(): number {
    const d = new Date();
    d.setHours(0, 0, 0, 0);
    return d.getTime();
  }

  static today(): string {
    return TimeUtil.dateFull(Date.now());
  }

  /** 12 分 30 秒 / 45 秒 / 1 小时 03 分 */
  static duration(ms: number): string {
    if (ms <= 0) {
      return '0 秒';
    }
    const total = Math.floor(ms / 1000);
    const h = Math.floor(total / 3600);
    const m = Math.floor((total % 3600) / 60);
    const s = total % 60;
    if (h > 0) {
      return `${h} 小时 ${TimeUtil.pad(m)} 分`;
    }
    if (m > 0) {
      return `${m} 分 ${TimeUtil.pad(s)} 秒`;
    }
    return `${s} 秒`;
  }

  /** 计时器用 00:12 / 1:02:33 */
  static clock(ms: number): string {
    const total = Math.floor(ms / 1000);
    const h = Math.floor(total / 3600);
    const m = Math.floor((total % 3600) / 60);
    const s = total % 60;
    if (h > 0) {
      return `${h}:${TimeUtil.pad(m)}:${TimeUtil.pad(s)}`;
    }
    return `${TimeUtil.pad(m)}:${TimeUtil.pad(s)}`;
  }

  /** 今天 / 昨天 / 10月20日 */
  static dayLabel(ts: number): string {
    const start = TimeUtil.todayStart();
    if (ts >= start) {
      return '今天';
    }
    if (ts >= start - 86400000) {
      return '昨天';
    }
    const d = new Date(ts);
    return `${d.getMonth() + 1}月${d.getDate()}日`;
  }

  static dayOfWeek(ts: number): string {
    const names: string[] = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'];
    return names[new Date(ts).getDay()];
  }

  /** 在 YYYY-MM-DD 上加减天数，用于「明天到期」判断 */
  static shiftDay(date: string, days: number): string {
    const parts = date.split('-');
    if (parts.length !== 3) {
      return date;
    }
    const d = new Date(Number(parts[0]), Number(parts[1]) - 1, Number(parts[2]));
    d.setDate(d.getDate() + days);
    return TimeUtil.dateFull(d.getTime());
  }

  static daysBetween(from: string, to: string): number {
    const a = new Date(`${from}T00:00:00`).getTime();
    const b = new Date(`${to}T00:00:00`).getTime();
    return Math.round((b - a) / 86400000);
  }
}
