/**
 * 领域模型
 *
 * 与 Android 版（Kotlin）字段一一对应，保证两端的导出文件、Webhook 报文、
 * 群机器人消息格式完全一致——同一账号在手机和鸿蒙设备上看到的是同一套口径。
 */

export interface Session {
  id: number;
  title: string;
  transcript: string;
  summary: string;
  project: string;
  source: string;
  audioPath: string;
  startAt: number;
  endAt: number;
  durationMs: number;
  marked: boolean;
  marks: string;
  envType: string;
  noiseDb: number;
  speechRatio: number;
  voiceSpeaker: string;
  voiceScore: number;
  voiceMargin: number;
  /** 本次转写用的语言 id（见 SpeechLang）：详情页「按粤语重转」要沿用上次的选择 */
  lang: string;
  /** 语言降级说明（服务端不支持所选方言时写在这里，空表示没降级） */
  langNote: string;
  /** 事后从文本反推出的疑似方言 id（空表示没检测到） */
  detected: string;
  /**
   * 方言稿归一化后的普通话版本
   *
   * 与 transcript 分开存：原文是凭证，要能原样查；摘要与待办用标准语版本，
   * 模型读普通话稿时待办与承诺的漏检率明显更低（方言词不会被当成语气词丢掉）。
   */
  transcriptStd: string;
  quality: string;
  status: string;
  createdAt: number;
}

export interface TodoItem {
  id: number;
  sessionId: number;
  text: string;
  done: boolean;
  dueDate: string;
  createdAt: number;
}

export interface TodoDraft {
  text: string;
  due: string;
}

export interface AnalyzeResult {
  title: string;
  summary: string;
  todos: TodoDraft[];
  project: string;
  insight: string;
  quality: string;
  /** 模型抄回的原文依据句：代码侧要回原文比对，抄不出就不采信它的项目归属 */
  projectEvidence: string;
  /** 模型自评的归属把握（0-1），< 0.6 一律标待确认 */
  projectConfidence: number;
}

export interface ProjectSummary {
  project: string;
  count: number;
  lastAt: number;
  summary: string;
}

export interface ChatMessage {
  role: string;
  content: string;
}

export class Env {
  static readonly QUIET: string = 'quiet';
  static readonly MEETING: string = 'meeting';
  static readonly STREET: string = 'street';
  static readonly UNKNOWN: string = 'unknown';
}

export class Src {
  static readonly LIVE: string = 'live';
  static readonly CALL: string = 'call';
  static readonly VOICE: string = 'voice';
  static readonly SHARE: string = 'share';
  static readonly IMPORT: string = 'import';
  static readonly IMPORT_TEXT: string = 'import_text';
}

export class Status {
  static readonly RECORDED: string = 'recorded';
  static readonly TRANSCRIBING: string = 'transcribing';
  static readonly TRANSCRIBED: string = 'transcribed';
  static readonly ANALYZED: string = 'analyzed';
}

export class Quality {
  static readonly OK: string = 'ok';
  static readonly REVIEW: string = 'review';
}

/** 新建一条空白记录，避免各调用点各写一遍默认值 */
export function emptySession(): Session {
  return {
    id: 0,
    title: '未命名记录',
    transcript: '',
    summary: '',
    project: '',
    source: Src.LIVE,
    audioPath: '',
    startAt: 0,
    endAt: 0,
    durationMs: 0,
    marked: false,
    marks: '',
    envType: Env.UNKNOWN,
    noiseDb: 0,
    speechRatio: 0,
    voiceSpeaker: '',
    voiceScore: 0,
    voiceMargin: 0,
    lang: 'zh',
    langNote: '',
    detected: '',
    transcriptStd: '',
    quality: Quality.OK,
    status: Status.RECORDED,
    createdAt: 0
  };
}

export function sourceLabel(source: string): string {
  if (source === Src.CALL) {
    return '通话录音';
  }
  if (source === Src.VOICE) {
    return '语音速记';
  }
  if (source === Src.SHARE) {
    return '外部导入';
  }
  if (source === Src.IMPORT) {
    return '录音导入';
  }
  if (source === Src.IMPORT_TEXT) {
    return '文稿导入';
  }
  return '耳机记录';
}

export function statusLabel(s: Session): string {
  if (s.status === Status.ANALYZED) {
    return s.quality === Quality.REVIEW ? '待确认' : '已整理';
  }
  if (s.status === Status.TRANSCRIBED) {
    return '已转写';
  }
  if (s.status === Status.TRANSCRIBING) {
    return '转写中';
  }
  return '待转写';
}

/** 解析重点时间点（毫秒偏移） */
export function parseMarks(json: string): number[] {
  if (json.length === 0) {
    return [];
  }
  try {
    const arr = JSON.parse(json) as number[];
    const out: number[] = [];
    for (let i = 0; i < arr.length; i++) {
      const v = arr[i];
      if (typeof v === 'number' && v > 0) {
        out.push(v);
      }
    }
    return out;
  } catch (e) {
    return [];
  }
}
