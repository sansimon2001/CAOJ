/**
 * 转写文本清洗与分块（对应「AI 总结跑题乱写」的前置治理）
 *
 * 问题根源：原始转写里有大量重复、语气词、无意义的短句与长上下文，
 * 模型容易被带跑，或把闲聊当成重点。这里做两件事：
 * 1) 清洗：去重复行、去纯语气词、合并过碎片段
 * 2) 分块：超过阈值时切成若干块，走「先抽要点、再写摘要」的 map-reduce，
 *    避免一次塞太长的文本导致抓不住重点
 */
export class TranscriptCleaner {
  /** 单块目标字数（中文按字符算） */
  static readonly CHUNK_SIZE: number = 1800;

  /** 纯语气词/填充词，整行只有这些则丢弃 */
  private static readonly FILLER: string[] = [
    '嗯', '啊', '哦', '呃', '额', '欸', '这个', '那个', '就是', '然后', '对吧', '是吧',
    '好的', '行', '嗯嗯', '哦哦', '对的', '是的', '哈哈', '呵呵'
  ];

  /** 常见无信息量片段（出现即整行丢弃） */
  private static readonly NOISE: string[] = [
    '喂喂', '能听到吗', '听得到吗', '信号不好', '稍等', '等一下啊'
  ];

  private static readonly SPEAKER_TAG: RegExp = new RegExp('^\\[[^\\]]{1,12}\\]\\s*');
  private static readonly PUNCT: RegExp = new RegExp('[，。！？、,.!?~\\s]', 'g');

  static clean(transcript: string): string {
    if (transcript.trim().length === 0) {
      return '';
    }
    const rawLines = transcript.split('\n');
    const lines: string[] = [];
    for (let i = 0; i < rawLines.length; i++) {
      const t = rawLines[i].trim();
      if (t.length > 0) {
        lines.push(t);
      }
    }

    const kept: string[] = [];
    for (let i = 0; i < lines.length; i++) {
      const line = lines[i];
      // 去掉说话人标记后的纯内容，用于判断信息量
      const content = line.replace(TranscriptCleaner.SPEAKER_TAG, '').trim();
      if (content.length === 0) {
        continue;
      }
      const stripped = content.replace(TranscriptCleaner.PUNCT, '');
      if (stripped.length === 0) {
        continue;
      }
      if (TranscriptCleaner.isFiller(stripped)) {
        continue;
      }
      // 无信息片段：短句里出现「能听到吗」这类，整行丢弃
      if (content.length < 16 && TranscriptCleaner.hasNoise(content)) {
        continue;
      }
      // 连续重复行去重（同一句被 ASR 重复输出）
      if (kept.length > 0 && kept[kept.length - 1] === line) {
        continue;
      }
      kept.push(line);
    }

    // 相邻高度相似行折叠（相似度用「短的一半是长的子串」近似）
    const collapsed: string[] = [];
    for (let i = 0; i < kept.length; i++) {
      const line = kept[i];
      if (collapsed.length > 0) {
        const prev = collapsed[collapsed.length - 1];
        const a = prev.replace(TranscriptCleaner.SPEAKER_TAG, '');
        const b = line.replace(TranscriptCleaner.SPEAKER_TAG, '');
        const shorter = a.length <= b.length ? a : b;
        const longer = a.length <= b.length ? b : a;
        if (shorter.length >= 6 && longer.indexOf(shorter) >= 0) {
          continue;
        }
      }
      collapsed.push(line);
    }
    return collapsed.join('\n');
  }

  /** 按段落/句末切块，尽量不把一句话截断 */
  static chunk(text: string, size: number = TranscriptCleaner.CHUNK_SIZE): string[] {
    if (text.length <= size) {
      return [text];
    }
    const chunks: string[] = [];
    let buf = '';
    // 先按行切成单元，行过长再按句号切
    const units: string[] = [];
    const lines = text.split('\n');
    for (let i = 0; i < lines.length; i++) {
      const line = lines[i];
      if (line.length <= size) {
        units.push(line);
      } else {
        let rest = line;
        while (rest.length > size) {
          let cut = rest.lastIndexOf('。', size);
          cut = cut > 0 ? cut + 1 : size;
          units.push(rest.substring(0, cut));
          rest = rest.substring(cut);
        }
        if (rest.trim().length > 0) {
          units.push(rest);
        }
      }
    }

    for (let i = 0; i < units.length; i++) {
      const u = units[i];
      if (buf.length + u.length + 1 > size && buf.length > 0) {
        chunks.push(buf.trim());
        buf = '';
      }
      if (buf.length > 0) {
        buf += '\n';
      }
      buf += u;
    }
    if (buf.trim().length > 0) {
      chunks.push(buf.trim());
    }
    return chunks;
  }

  /** 有效字数（去掉说话人标记与标点） */
  static effectiveLength(text: string): number {
    const noTag = text.replace(new RegExp('\\[[^\\]]{1,12}\\]', 'g'), '');
    const noPunct = noTag.replace(TranscriptCleaner.PUNCT, '');
    return noPunct.length;
  }

  private static isFiller(s: string): boolean {
    for (let i = 0; i < TranscriptCleaner.FILLER.length; i++) {
      if (TranscriptCleaner.FILLER[i] === s) {
        return true;
      }
    }
    return false;
  }

  private static hasNoise(content: string): boolean {
    for (let i = 0; i < TranscriptCleaner.NOISE.length; i++) {
      if (content.indexOf(TranscriptCleaner.NOISE[i]) >= 0) {
        return true;
      }
    }
    return false;
  }
}
