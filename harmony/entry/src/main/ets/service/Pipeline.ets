import { fileIo } from '@kit.CoreFileKit';
import { Store } from '../data/Store';
import { Repo, Keys } from '../data/Repo';
import { TimeUtil } from '../util/TimeUtil';
import { Session, Status, Src, Env, emptySession } from '../model/Models';
import { AsrClient } from './AsrClient';
import { SpeechLangs, LangDetect } from './SpeechLang';
import { TranscriptCleaner } from '../util/TranscriptCleaner';
import { ProjectMind, ProjectProfile } from './ProjectMind';
import { LlmClient } from './LlmClient';
import { CollabBridge } from './CollabBridge';

export interface PipelineListener {
  onPipelineChanged: (sessionId: number) => void;
}

/**
 * 整理流水线：录音落库 → 转写 → 摘要/待办 → 原音处理 → 自动外发
 *
 * 每一步都可跳过：没配转写就停在「待转写」，没配大模型就停在「已转写」，
 * 用户补上配置后可以点「重新整理」再跑一遍，不会因为一次缺配置就废掉这条记录。
 */
export class Pipeline {
  private static listeners: PipelineListener[] = [];
  private static running: Map<number, boolean> = new Map();

  static addListener(l: PipelineListener): void {
    if (Pipeline.listeners.indexOf(l) < 0) {
      Pipeline.listeners.push(l);
    }
  }

  static removeListener(l: PipelineListener): void {
    const idx = Pipeline.listeners.indexOf(l);
    if (idx >= 0) {
      Pipeline.listeners.splice(idx, 1);
    }
  }

  private static notify(sessionId: number): void {
    for (let i = 0; i < Pipeline.listeners.length; i++) {
      Pipeline.listeners[i].onPipelineChanged(sessionId);
    }
  }

  /** 录音分段保存后调用：新建记录并立即开始整理 */
  static async ingest(path: string, startAt: number, durationMs: number, index: number, reason: string): Promise<number> {
    const s = emptySession();
    s.title = Pipeline.defaultTitle(startAt, index, reason);
    s.audioPath = path;
    s.startAt = startAt;
    s.endAt = startAt + durationMs;
    s.durationMs = durationMs;
    s.source = Src.LIVE;
    s.envType = Env.UNKNOWN;
    s.status = Status.RECORDED;
    s.createdAt = Date.now();
    const id = await Store.insertSession(s);
    Pipeline.notify(id);
    // 不 await：让调用方（录音停止）立刻返回，整理在后台继续
    Pipeline.run(id);
    return id;
  }

  private static defaultTitle(startAt: number, index: number, reason: string): string {
    const base = `${TimeUtil.dayLabel(startAt)} ${TimeUtil.time(startAt)} 的记录`;
    if (reason === 'call') {
      return `${base}（通话前，第${index}段）`;
    }
    if (index > 1) {
      return `${base}（第${index}段）`;
    }
    return base;
  }

  /**
   * 跑完整流水线
   *
   * 返回空字符串表示没有需要提示的问题；否则返回给用户看的提示。
   */
  static async run(sessionId: number): Promise<string> {
    if (Pipeline.running.get(sessionId) === true) {
      return '';
    }
    Pipeline.running.set(sessionId, true);
    let message = '';
    try {
      let s = await Store.getSession(sessionId);
      if (s === null) {
        return '记录不存在';
      }

      // ---------- 1) 转写 ----------
      if (s.transcript.length === 0 && s.audioPath.length > 0) {
        if (AsrClient.isConfigured()) {
          s.status = Status.TRANSCRIBING;
          await Store.updateSession(s);
          Pipeline.notify(sessionId);
          const r = await AsrClient.transcribe(s.audioPath);
          if (r.ok) {
            // 清洗放在落库前：语气词与重复行会带着模型跑题，事后清理不如一开始就不喂
            const cleaned = TranscriptCleaner.clean(r.text);
            s.transcript = cleaned.length > 0 ? cleaned : r.text;
            s.lang = r.langId;
            // 降级说明要落到记录上，详情页才显示得出「其实是按普通话认的」
            s.langNote = r.note;
            // 事后方言检测：用户选了普通话、文本里满是「唔/嘅/咗」，就提示可重转
            const det: LangDetect | null = SpeechLangs.detect(s.transcript);
            s.detected = det === null ? '' : det.langId;
            s.status = Status.TRANSCRIBED;
            await Store.updateSession(s);
            Pipeline.notify(sessionId);
          } else {
            s.status = Status.RECORDED;
            await Store.updateSession(s);
            message = r.message;
          }
        } else {
          message = '还没配置转写服务：音频已保存，配好后可重新整理';
        }
      } else if (s.transcript.length === 0 && s.audioPath.length === 0) {
        return '这条记录既没有音频也没有文字';
      }

      // ---------- 2) 摘要 + 待办 ----------
      if (s.transcript.length > 0 && s.status !== Status.ANALYZED && LlmClient.isConfigured()) {
        // ---------- 1.5) 方言口语 → 普通话书面语 ----------
        // 只对方言做，普通话录音白跑一次调用没有意义。结果单独存一栏，
        // 原文仍原样保留；归一化失败不影响主流程，摘要照常用原话。
        const lang = SpeechLangs.byId(s.lang);
        if (lang.isDialect && Repo.getBool(Keys.DIALECT_STD, true) && s.transcriptStd.length === 0) {
          const norm = await LlmClient.normalizeDialect(s.transcript, lang.label);
          if (norm.ok) {
            s.transcriptStd = norm.text;
            await Store.updateSession(s);
            Pipeline.notify(sessionId);
          }
        }
        // 方言稿优先用归一化后的普通话版本：同一件事，模型读普通话稿时
        // 待办与承诺的漏检率明显更低（方言词不会被当成语气词丢掉）
        const material = s.transcriptStd.length > 0 ? s.transcriptStd : s.transcript;
        // 候选预筛：把全部项目名丢给模型是「归错项目」的头号原因（名字越像越容易串）。
        // 这里只按原文词面命中的前 8 个候选，连别名与线索词一起交给模型。
        const cands: ProjectProfile[] = await ProjectMind.candidates(material, 8);
        const r = await LlmClient.analyze(material, cands, s.envType);
        if (r.ok) {
          const res = r.result;
          if (res.title.length > 0 && res.title !== '未命名记录') {
            s.title = res.title;
          }
          s.summary = res.summary;
          if (res.project.length > 0) {
            s.project = res.project;
            // 落档：已存在则原样不动（不要用 save 整行覆盖，会把别名与线索词抹掉）
            await ProjectMind.ensure(res.project);
          }
          s.quality = res.quality;
          s.status = Status.ANALYZED;
          await Store.updateSession(s);
          await Store.addTodos(s.id, res.todos);
          Pipeline.notify(sessionId);
          message = r.message;
        } else {
          message = r.message;
        }
      } else if (s.status === Status.TRANSCRIBED && !LlmClient.isConfigured()) {
        message = '还没配置大模型：文字已保存，配好后可生成摘要';
      }

      // ---------- 3) 原音处理 ----------
      const keepAudio = Repo.getBool(Keys.KEEP_AUDIO, false);
      if (!keepAudio && s.audioPath.length > 0 && s.status === Status.ANALYZED) {
        try {
          if (fileIo.accessSync(s.audioPath)) {
            fileIo.unlinkSync(s.audioPath);
          }
        } catch (e) {
          // 删不掉不影响文字
        }
        s.audioPath = '';
        await Store.updateSession(s);
      }

      // ---------- 4) 自动外发（Webhook / 群机器人） ----------
      if (s.status === Status.ANALYZED && Repo.getBool(Keys.WB_AUTO, true)) {
        const todos = await Store.todosOf(s.id);
        CollabBridge.autoPush(s, todos);
      }
      return message;
    } catch (e) {
      return `整理失败：${(e as Error).message}`;
    } finally {
      Pipeline.running.delete(sessionId);
    }
  }

  /** 用户改完文本后重跑（设置里补了 Key、或手动修正了转写） */
  static async retry(sessionId: number): Promise<string> {
    const s = await Store.getSession(sessionId);
    if (s === null) {
      return '记录不存在';
    }
    if (s.transcript.length > 0) {
      // 已有文字：直接重新生成摘要，把旧摘要清掉避免混淆
      s.summary = '';
      s.status = Status.TRANSCRIBED;
      await Store.updateSession(s);
    }
    return Pipeline.run(sessionId);
  }

  static async saveSession(s: Session): Promise<void> {
    await Store.updateSession(s);
    Pipeline.notify(s.id);
  }
}
