import { pasteboard } from '@kit.BasicServicesKit';
import { picker } from '@kit.CoreFileKit';
import { fileIo } from '@kit.CoreFileKit';
import { util } from '@kit.ArkTS';
import { promptAction } from '@kit.ArkUI';
import { common } from '@kit.AbilityKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { Session, TodoItem } from '../model/Models';
import { TimeUtil } from '../util/TimeUtil';

/**
 * 导出与分发
 *
 * 鸿蒙上「把文字送出去」的三条通路，按可靠性从高到低：
 * 1) 导出为 .md/.txt 文件（DocumentViewPicker，保存到任意目录/网盘同步目录）
 * 2) 复制到剪贴板（粘到微信、备忘录、任何输入框）
 * 3) 群机器人直推（飞书 / 钉钉，见 CollabBridge）
 *
 * 没有做 Android 那种 ACTION_SEND 直发某个 App：鸿蒙的跨应用投递需要对方声明
 * 可接收的 utd 类型，各家 App 的声明很难统一，做出来经常「点开一片空白」。
 * 先把稳的三条做扎实。
 */
export class ExportService {
  /** 单条记录的 Markdown，与 Android 版 sessionMarkdown 逐字一致 */
  static sessionMarkdown(s: Session, todos: TodoItem[]): string {
    let md = `# ${s.title.length > 0 ? s.title : '未命名记录'}\n\n`;
    md += `> ${TimeUtil.dateFull(s.startAt)} ${TimeUtil.time(s.startAt)} · ${TimeUtil.duration(s.durationMs)}`;
    if (s.project.length > 0) {
      md += ` · 项目：${s.project}`;
    }
    md += '\n\n';
    if (s.summary.length > 0) {
      md += `## 摘要\n\n${s.summary.trim()}\n\n`;
    }
    const open: TodoItem[] = [];
    for (let i = 0; i < todos.length; i++) {
      if (!todos[i].done) {
        open.push(todos[i]);
      }
    }
    if (open.length > 0) {
      md += '## 待办\n\n';
      for (let i = 0; i < open.length; i++) {
        md += `- [ ] ${open[i].text}`;
        if (open[i].dueDate.length > 0) {
          md += `（${open[i].dueDate}）`;
        }
        md += '\n';
      }
      md += '\n';
    }
    if (s.transcript.length > 0) {
      md += `## 原文\n\n${s.transcript.trim()}\n\n`;
    }
    md += `---\n由「草记」整理导出 · ${TimeUtil.dateFull(Date.now())}\n`;
    return md;
  }

  static todayMarkdown(sessions: Session[], dateLabel: string): string {
    let md = `# 草记 · ${dateLabel}\n\n`;
    for (let i = 0; i < sessions.length; i++) {
      const s = sessions[i];
      md += `## ${s.title}\n\n`;
      md += `> ${TimeUtil.time(s.startAt)} · ${TimeUtil.duration(s.durationMs)}`;
      if (s.project.length > 0) {
        md += ` · ${s.project}`;
      }
      md += '\n\n';
      if (s.summary.length > 0) {
        md += `${s.summary}\n\n`;
      }
      if (s.transcript.length > 0) {
        md += `原文：\n\n${s.transcript}\n\n`;
      }
      md += '---\n\n';
    }
    md += `由「草记」整理导出 · ${TimeUtil.dateFull(Date.now())}\n`;
    return md;
  }

  /** 保存为文件：让用户选目录，不申请存储权限 */
  static async saveToFile(ctx: common.UIAbilityContext, content: string, fileName: string): Promise<string> {
    try {
      const docPicker = new picker.DocumentViewPicker(ctx);
      const options: picker.DocumentSaveOptions = {
        newFileNames: [fileName]
      };
      const uris = await docPicker.save(options);
      if (uris.length === 0) {
        return '';
      }
      const file = fileIo.openSync(uris[0], fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.TRUNC);
      try {
        fileIo.writeSync(file.fd, new util.TextEncoder().encodeInto(content).buffer as ArrayBuffer);
      } finally {
        fileIo.closeSync(file);
      }
      return `已保存：${fileName}`;
    } catch (e) {
      const err = e as BusinessError;
      return `保存失败：${err.message}`;
    }
  }

  static async copyToClipboard(text: string): Promise<void> {
    const data = pasteboard.createData(pasteboard.MIMETYPE_TEXT_PLAIN, text);
    await pasteboard.getSystemPasteboard().setData(data);
  }

  static toast(message: string): void {
    promptAction.showToast({ message: message, duration: 2000 });
  }

  /** 导出文件名：带日期与标题片段，便于在文件管理器里分辨 */
  static fileNameFor(s: Session): string {
    const day = TimeUtil.dateFull(s.startAt).replace(/-/g, '');
    const safe = s.title.replace(/[\\/:*?"<>|\n\r]/g, '_').substring(0, 20);
    return `草记-${day}-${safe}.md`;
  }
}
