/**
 * 转写稿解析
 *
 * 第三方录音产品导出的文稿形态差别很大，这里覆盖三种最常见的：
 * 1) SRT / VTT：按 cue 切分，同一说话人的连续 cue 合并成一段（否则一句一行，读起来全是碎片）
 * 2) 钉钉闪记式纯文本：说话人单独一行、台词在下一行（或 `说话人 00:12` 作段落头）
 * 3) JSON：各家字段名不同，依次试 transcript / text / segments / sentences / utterances
 */
export class TranscriptParser {
  /** 单条记录最多收 20 万字：再多也不适合一次喂给模型，且会拖慢列表 */
  static readonly MAX_CHARS: number = 200000;

  private static readonly SPEAKER_LINE: RegExp = new RegExp(
    '^\\s*(?:\\[[^\\]]{1,12}\\]\\s*)?([\\u4e00-\\u9fa5A-Za-z][\\u4e00-\\u9fa5A-Za-z0-9·._-]{0,11})\\s*[：:]\\s*'
  );

  static parse(raw: string, fileName: string): string {
    const lower = fileName.toLowerCase();
    let text = '';
    if (lower.endsWith('.srt') || lower.endsWith('.vtt')) {
      text = TranscriptParser.parseCues(raw);
    } else if (lower.endsWith('.json')) {
      text = TranscriptParser.parseJson(raw);
    } else {
      text = TranscriptParser.normalizePlain(raw);
    }
    text = text.replace(/\r/g, '').replace(/\n{3,}/g, '\n\n').trim();
    if (text.length > TranscriptParser.MAX_CHARS) {
      text = text.substring(0, TranscriptParser.MAX_CHARS) + '\n\n（内容过长，已截断）';
    }
    return text;
  }

  // ==================== SRT / VTT ====================

  private static parseCues(raw: string): string {
    const lines = raw.replace(/\r/g, '').split('\n');
    const out: string[] = [];
    let speaker = '';
    let buffer = '';
    const flush = (): void => {
      if (buffer.trim().length === 0) {
        return;
      }
      out.push(speaker.length > 0 ? `${speaker}：${buffer.trim()}` : buffer.trim());
      buffer = '';
    };

    for (let i = 0; i < lines.length; i++) {
      let line = lines[i].trim();
      if (line.length === 0) {
        continue;
      }
      if (line === 'WEBVTT') {
        continue;
      }
      if (line.indexOf('-->') >= 0) {
        continue;
      }
      if (new RegExp('^\\d{1,6}$').test(line)) {
        continue;
      }
      // [00:00:12] 张三：内容 —— 常见于 VTT 的行内时间戳
      line = line.replace(/^\[?\d{1,2}:\d{2}(:\d{2})?([.,]\d{1,3})?\]?\s*/, '').trim();
      if (line.length === 0) {
        continue;
      }
      const m = TranscriptParser.SPEAKER_LINE.exec(line);
      const sp = m === null ? '' : m[1];
      const content = m === null ? line : line.substring(m[0].length).trim();
      if (content.length === 0) {
        continue;
      }
      if (sp.length > 0 && sp !== speaker) {
        flush();
        speaker = sp;
      }
      buffer = buffer.length > 0 ? `${buffer} ${content}` : content;
      // 同一段太长就断开，避免整场会议合成一个超大段落
      if (buffer.length > 800) {
        flush();
      }
    }
    flush();
    return out.join('\n\n');
  }

  // ==================== 纯文本 ====================

  private static normalizePlain(raw: string): string {
    const out: string[] = [];
    let pending = '';
    const timeOnly = new RegExp('^\\[?\\d{1,2}:\\d{2}(:\\d{2})?\\]?$');
    const timePrefix = new RegExp('^\\[?\\d{1,2}:\\d{2}(:\\d{2})?\\]?\\s*');

    const lines = raw.replace(/\r/g, '').split('\n');
    for (let i = 0; i < lines.length; i++) {
      let t = lines[i].trim();
      if (t.length === 0) {
        continue;
      }
      if (timeOnly.test(t)) {
        continue;
      }
      t = t.replace(timePrefix, '').trim();
      if (t.length === 0) {
        continue;
      }
      const m = TranscriptParser.SPEAKER_LINE.exec(t);
      if (m !== null) {
        const sp = m[1];
        const rest = t.substring(m[0].length).trim();
        if (rest.length === 0) {
          // 只有说话人名的行 → 与下一行台词合并（钉钉闪记的做法）
          pending = sp;
          continue;
        }
        out.push(`${sp}：${rest}`);
        pending = '';
      } else if (pending.length > 0) {
        out.push(`${pending}：${t}`);
        pending = '';
      } else {
        out.push(t);
      }
    }
    return out.join('\n');
  }

  // ==================== JSON ====================

  private static parseJson(raw: string): string {
    let obj: object;
    try {
      obj = JSON.parse(raw) as object;
    } catch (e) {
      return TranscriptParser.normalizePlain(raw);
    }
    // 1) 直接是长文本字段
    const direct = TranscriptParser.pickString(obj, 'transcript');
    if (direct.length > 0) {
      return direct;
    }
    const text = TranscriptParser.pickString(obj, 'text');
    if (text.length > 0) {
      return TranscriptParser.normalizePlain(text);
    }
    // 2) 段落数组：segments / sentences / utterances
    const arrays: string[] = ['segments', 'sentences', 'utterances'];
    for (let i = 0; i < arrays.length; i++) {
      const arr = TranscriptParser.pickArray(obj, arrays[i]);
      if (arr !== null && arr.length > 0) {
        return TranscriptParser.joinSegments(arr);
      }
    }
    return '';
  }

  private static joinSegments(arr: Object[]): string {
    const out: string[] = [];
    let speaker = '';
    let buffer = '';
    for (let i = 0; i < arr.length; i++) {
      const item = arr[i];
      if (typeof item === 'string') {
        if (buffer.length > 0) {
          out.push(speaker.length > 0 ? `${speaker}：${buffer}` : buffer);
          buffer = '';
        }
        out.push((item as string).trim());
        continue;
      }
      const sp = TranscriptParser.pickString(item, 'speaker');
      let tx = TranscriptParser.pickString(item, 'text');
      if (tx.length === 0) {
        tx = TranscriptParser.pickString(item, 'content');
      }
      if (tx.length === 0) {
        continue;
      }
      if (sp.length > 0 && sp !== speaker) {
        if (buffer.length > 0) {
          out.push(speaker.length > 0 ? `${speaker}：${buffer}` : buffer);
        }
        speaker = sp;
        buffer = tx;
      } else {
        buffer = buffer.length > 0 ? `${buffer} ${tx}` : tx;
      }
    }
    if (buffer.length > 0) {
      out.push(speaker.length > 0 ? `${speaker}：${buffer}` : buffer);
    }
    return out.join('\n');
  }

  private static pickString(obj: object, key: string): string {
    const rec = obj as Record<string, Object>;
    const v = rec[key];
    if (v === undefined || v === null) {
      return '';
    }
    return typeof v === 'string' ? (v as string).trim() : '';
  }

  private static pickArray(obj: object, key: string): Object[] | null {
    const rec = obj as Record<string, Object>;
    const v = rec[key];
    if (v === undefined || v === null) {
      return null;
    }
    return Array.isArray(v) ? (v as Object[]) : null;
  }
}
