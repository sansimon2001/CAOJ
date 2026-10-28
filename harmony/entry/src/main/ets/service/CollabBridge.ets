import { http } from '@kit.NetworkKit';
import { util } from '@kit.ArkTS';
import { cryptoFramework } from '@kit.CryptoArchitectureKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { Repo, Keys } from '../data/Repo';
import { Store } from '../data/Store';
import { Session, TodoItem } from '../model/Models';
import { TimeUtil } from '../util/TimeUtil';

export class Platform {
  static readonly FEISHU: string = 'feishu';
  static readonly DINGTALK: string = 'dingtalk';
}

export interface PushResult {
  ok: boolean;
  message: string;
}

interface FeishuResp {
  code?: number;
  msg?: string;
  StatusCode?: number;
  StatusMessage?: string;
}

interface DingResp {
  errcode?: number;
  errmsg?: string;
}

/**
 * 飞书 / 钉钉群机器人 + WorkBuddy Webhook
 *
 * 只用「群自定义机器人 Webhook」这一种接入方式：
 * 企业自建应用要企业管理员建应用、配权限、走审核，个人用户根本走不通。
 *
 * 两个易错点（也是绝大多数「发了但群里没消息」的原因）：
 * 1) 两家失败时也返回 HTTP 200，必须看业务码（飞书 code / 钉钉 errcode）；
 * 2) 两家加签规则不同 —— 飞书对空串做 HMAC、sign 放 body；钉钉用 secret 做 HMAC 且 sign 拼在 URL 上。
 */
export class CollabBridge {
  static isReady(platform: string): boolean {
    if (platform === Platform.FEISHU) {
      return Repo.getStr(Keys.FS_HOOK).length > 0;
    }
    return Repo.getStr(Keys.DT_HOOK).length > 0;
  }

  static label(platform: string): string {
    return platform === Platform.FEISHU ? '飞书群' : '钉钉群';
  }

  /** 只发摘要 + 待办：群里没人读几千字逐字稿，也避免过度外泄 */
  static composeText(s: Session, todos: TodoItem[]): string {
    let text = `【草记】${s.title}\n`;
    text += `${TimeUtil.dateTime(s.startAt)} · ${TimeUtil.duration(s.durationMs)}`;
    if (s.project.length > 0) {
      text += ` · 项目：${s.project}`;
    }
    text += '\n';
    if (s.summary.length > 0) {
      text += `\n${s.summary}\n`;
    }
    const open: string[] = [];
    for (let i = 0; i < todos.length; i++) {
      if (!todos[i].done) {
        const due = todos[i].dueDate.length > 0 ? `（${todos[i].dueDate}）` : '';
        open.push(`· ${todos[i].text}${due}`);
      }
    }
    if (open.length > 0) {
      text += `\n待办 ${open.length} 项：\n${open.join('\n')}\n`;
    }
    if (s.marked) {
      text += `\n已标重点 ${s.marks.length > 0 ? s.marks : ''}`;
    }
    text += `\n原文 ${s.transcript.length} 字（在草记里查看）`;
    return text;
  }

  // ==================== 对外 ====================

  static async pushSession(platform: string, s: Session, todos: TodoItem[]): Promise<PushResult> {
    if (!CollabBridge.isReady(platform)) {
      return { ok: false, message: `还没配置${CollabBridge.label(platform)}的 Webhook` };
    }
    return CollabBridge.send(platform, CollabBridge.composeText(s, todos));
  }

  static async pushRecent(platform: string, limit: number): Promise<PushResult> {
    if (!CollabBridge.isReady(platform)) {
      return { ok: false, message: `还没配置${CollabBridge.label(platform)}的 Webhook` };
    }
    const list = await Store.recent(limit);
    const ready: Session[] = [];
    for (let i = 0; i < list.length; i++) {
      if (list[i].summary.length > 0) {
        ready.push(list[i]);
      }
    }
    if (ready.length === 0) {
      return { ok: false, message: '还没有已整理好的记录可推送' };
    }
    let text = `【草记】最近 ${ready.length} 条记录\n`;
    for (let i = 0; i < ready.length; i++) {
      const s = ready[i];
      const todos = await Store.todosOf(s.id);
      let open = 0;
      for (let j = 0; j < todos.length; j++) {
        if (!todos[j].done) {
          open += 1;
        }
      }
      text += `\n${i + 1}. ${s.title}（${TimeUtil.dayLabel(s.startAt)}${s.project.length > 0 ? ` · ${s.project}` : ''}）\n`;
      text += `${s.summary.substring(0, 120)}${s.summary.length > 120 ? '…' : ''}\n`;
      if (open > 0) {
        text += `待办 ${open} 项\n`;
      }
    }
    return CollabBridge.send(platform, text);
  }

  static async test(platform: string): Promise<PushResult> {
    if (!CollabBridge.isReady(platform)) {
      return { ok: false, message: '请先填 Webhook 地址' };
    }
    const now = TimeUtil.dateTime(Date.now());
    return CollabBridge.send(platform, `【草记】这是一条测试消息\n发送时间：${now}\n\n看到这条说明配置已通，之后整理完的记录会自动推到这个群。`);
  }

  /** 整理完成后自动外发：失败不打扰用户（结果留在设置页状态里） */
  static autoPush(s: Session, todos: TodoItem[]): void {
    if (CollabBridge.isReady(Platform.FEISHU)) {
      CollabBridge.pushSession(Platform.FEISHU, s, todos).then(() => {
      });
    }
    if (CollabBridge.isReady(Platform.DINGTALK)) {
      CollabBridge.pushSession(Platform.DINGTALK, s, todos).then(() => {
      });
    }
    if (Repo.getStr(Keys.WB_WEBHOOK).length > 0) {
      CollabBridge.pushWorkBuddy(s, todos).then(() => {
      });
    }
  }

  /** WorkBuddy 风格的 Webhook：结构化 JSON，便于对方做二次加工 */
  static async pushWorkBuddy(s: Session, todos: TodoItem[]): Promise<PushResult> {
    const hook = Repo.getStr(Keys.WB_WEBHOOK);
    if (hook.length === 0) {
      return { ok: false, message: '未配置 Webhook' };
    }
    if (!hook.startsWith('https://')) {
      return { ok: false, message: 'Webhook 必须是 https 地址' };
    }
    const items: string[] = [];
    for (let i = 0; i < todos.length; i++) {
      items.push(JSON.stringify({
        text: todos[i].text,
        done: todos[i].done,
        dueDate: todos[i].dueDate
      }));
    }
    const body = `{"app":"草记","schema":1,"session":{`
      + `"id":${s.id},"title":${JSON.stringify(s.title)},`
      + `"project":${JSON.stringify(s.project)},`
      + `"startAt":${s.startAt},"durationMs":${s.durationMs},`
      + `"summary":${JSON.stringify(s.summary)},`
      + `"quality":${JSON.stringify(s.quality)},`
      + `"transcriptLength":${s.transcript.length}},`
      + `"todos":[${items.join(',')}]}`;
    return CollabBridge.post(hook, body, Repo.getStr(Keys.WB_TOKEN));
  }

  // ==================== 发送实现 ====================

  private static async send(platform: string, text: string): Promise<PushResult> {
    if (platform === Platform.FEISHU) {
      return CollabBridge.sendFeishu(text);
    }
    return CollabBridge.sendDingtalk(text);
  }

  private static async sendFeishu(text: string): Promise<PushResult> {
    const hook = Repo.getStr(Keys.FS_HOOK);
    const secret = Repo.getStr(Keys.FS_SECRET);
    if (!hook.startsWith('https://')) {
      return { ok: false, message: '飞书 Webhook 必须是 https 地址' };
    }
    let body = '';
    if (secret.length > 0) {
      const ts = Math.floor(Date.now() / 1000).toString();
      const sign = await CollabBridge.hmacBase64('', `${ts}\n${secret}`);
      body = `{"timestamp":"${ts}","sign":"${sign}","msg_type":"text","content":{"text":${JSON.stringify(text)}}}`;
    } else {
      body = `{"msg_type":"text","content":{"text":${JSON.stringify(text)}}}`;
    }
    const res = await CollabBridge.post(hook, body, '');
    if (!res.ok) {
      return res;
    }
    let code = -1;
    let msg = '';
    try {
      const r = JSON.parse(res.message) as FeishuResp;
      code = r.code === undefined ? -1 : r.code;
      msg = r.msg === undefined ? '' : r.msg;
    } catch (e) {
      return { ok: false, message: '飞书返回内容无法解析' };
    }
    if (code === 0) {
      return { ok: true, message: '已发送到飞书群' };
    }
    return { ok: false, message: CollabBridge.explainFeishu(code, msg) };
  }

  private static async sendDingtalk(text: string): Promise<PushResult> {
    const hook = Repo.getStr(Keys.DT_HOOK);
    const secret = Repo.getStr(Keys.DT_SECRET);
    if (!hook.startsWith('https://')) {
      return { ok: false, message: '钉钉 Webhook 必须是 https 地址' };
    }
    let url = hook;
    if (secret.length > 0) {
      const ts = Date.now().toString();
      const sign = await CollabBridge.hmacBase64(secret, `${ts}\n${secret}`);
      url += `&timestamp=${ts}&sign=${encodeURIComponent(sign)}`;
    }
    const body = `{"msgtype":"text","text":{"content":${JSON.stringify(text)}}}`;
    const res = await CollabBridge.post(url, body, '');
    if (!res.ok) {
      return res;
    }
    let code = -1;
    let msg = '';
    try {
      const r = JSON.parse(res.message) as DingResp;
      code = r.errcode === undefined ? -1 : r.errcode;
      msg = r.errmsg === undefined ? '' : r.errmsg;
    } catch (e) {
      return { ok: false, message: '钉钉返回内容无法解析' };
    }
    if (code === 0) {
      return { ok: true, message: '已发送到钉钉群' };
    }
    return { ok: false, message: CollabBridge.explainDing(code, msg) };
  }

  private static async post(url: string, body: string, token: string): Promise<PushResult> {
    const client = http.createHttp();
    try {
      const header: Record<string, string> = { 'Content-Type': 'application/json' };
      if (token.length > 0) {
        header['Authorization'] = `Bearer ${token}`;
      }
      const res = await client.request(url, {
        method: http.RequestMethod.POST,
        header: header,
        extraData: body,
        connectTimeout: 15000,
        readTimeout: 30000
      });
      const raw = typeof res.result === 'string' ? (res.result as string) : JSON.stringify(res.result);
      if (res.responseCode !== 200) {
        return { ok: false, message: `HTTP ${res.responseCode}：${raw.substring(0, 100)}` };
      }
      return { ok: true, message: raw };
    } catch (e) {
      const err = e as BusinessError;
      return { ok: false, message: `网络不通：${err.message}（${err.code}）` };
    } finally {
      client.destroy();
    }
  }

  /**
   * HMAC-SHA256 → base64
   *
   * 飞书：key 为空字符串，待签内容 "ts\nsecret"
   * 钉钉：key 为 secret 本身，待签内容同样是 "ts\nsecret"
   */
  private static async hmacBase64(key: string, data: string): Promise<string> {
    const encoder = new util.TextEncoder();
    const keyBytes = encoder.encodeInto(key.length > 0 ? key : '\u0000');
    const gen = cryptoFramework.createSymKeyGenerator('HMAC');
    const symKey = await gen.convertKey({ data: keyBytes });
    const mac = cryptoFramework.createMac('SHA256');
    await mac.init(symKey);
    // Mac 的 doFinal 有一堆重载，ArkTS 解析 await 版会合并成 void & Promise<DataBlob>，
    // 干脆走同步链路：updateSync 喂数据 + doFinalSync 取结果，类型完全确定
    const blob: cryptoFramework.DataBlob = { data: encoder.encodeInto(data) };
    mac.updateSync(blob);
    const out: cryptoFramework.DataBlob = mac.doFinalSync();
    const helper = new util.Base64Helper();
    return helper.encodeToStringSync(out.data);
  }

  private static explainFeishu(code: number, msg: string): string {
    if (code === 19021) {
      return '加签校验失败（19021）：检查「加签密钥」是否复制完整';
    }
    if (code === 19024 || code === 19022) {
      return `机器人不可用（${code}）：可能在群里被移除了，或 Webhook 已重置`;
    }
    if (code === 9499) {
      return '请求过于频繁（9499），稍后再试';
    }
    return `飞书返回错误 ${code}${msg.length > 0 ? `：${msg}` : ''}`;
  }

  private static explainDing(code: number, msg: string): string {
    if (code === 310000) {
      let tip = '安全设置不匹配（310000）：';
      tip += '若机器人选了「自定义关键词」，消息里必须包含该关键词（建议设为「草记」）；';
      tip += '若选了「加签」，把密钥填上；也可能是 IP 白名单把手机挡了';
      return tip;
    }
    if (code === 300001) {
      return 'access_token 无效（300001）：Webhook 地址复制不完整，或机器人已被移除';
    }
    if (code === 400101 || code === 400013) {
      return '机器人未启用或已停用';
    }
    return `钉钉返回错误 ${code}${msg.length > 0 ? `：${msg}` : ''}`;
  }
}
