import { picker } from '@kit.CoreFileKit';
import { fileIo } from '@kit.CoreFileKit';
import { media } from '@kit.MediaKit';
import { util } from '@kit.ArkTS';
import { common } from '@kit.AbilityKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { Store } from '../data/Store';
import { Pipeline } from '../service/Pipeline';
import { TranscriptParser } from '../util/TranscriptParser';
import { TimeUtil } from '../util/TimeUtil';
import { Status, Src, Env, emptySession } from '../model/Models';

interface ImportOutcome {
  ok: boolean;
  message: string;
  sessionId: number;
}

/**
 * 外部录音与文稿导入
 *
 * 鸿蒙的沙箱同样读不到钉钉闪记 / 飞书妙记的私有目录，它们也没有把音频交出来的接口，
 * 所以适配方式是：在这些产品里「导出/下载」文件 → 用系统文件选择器导入草记。
 *
 * 音频走完整的转写 + 摘要流水线；转写稿（srt/vtt/txt/md/json）直接入库，跳过 ASR 省钱。
 * 全程用 picker 选文件，不申请任何存储权限。
 */
export class Importer {
  private static readonly AUDIO_EXT: string[] = [
    'm4a', 'mp3', 'wav', 'aac', 'amr', 'ogg', 'opus', 'flac', '3gp', 'mp4', 'mka', 'webm'
  ];
  private static readonly TEXT_EXT: string[] = ['txt', 'md', 'markdown', 'srt', 'vtt', 'json'];

  static async pickAndImport(ctx: common.UIAbilityContext): Promise<ImportOutcome> {
    let uris: string[] = [];
    try {
      const docPicker = new picker.DocumentViewPicker(ctx);
      const options: picker.DocumentSelectOptions = {
        maxSelectNumber: 5,
        fileSuffixFilters: ['.m4a', '.mp3', '.wav', '.aac', '.amr', '.ogg', '.flac',
          '.mp4', '.txt', '.md', '.srt', '.vtt', '.json']
      };
      uris = await docPicker.select(options);
    } catch (e) {
      const err = e as BusinessError;
      return { ok: false, message: `打开文件选择器失败：${err.message}`, sessionId: 0 };
    }
    if (uris.length === 0) {
      return { ok: false, message: '', sessionId: 0 };
    }

    let okCount = 0;
    let lastId = 0;
    let failNote = '';
    for (let i = 0; i < uris.length; i++) {
      const name = Importer.fileNameOf(uris[i]);
      const ext = Importer.extOf(name);
      if (Importer.AUDIO_EXT.indexOf(ext) >= 0) {
        const r = await Importer.importAudio(ctx, uris[i], name);
        if (r.ok) {
          okCount += 1;
          lastId = r.sessionId;
        } else {
          failNote = r.message;
        }
      } else if (Importer.TEXT_EXT.indexOf(ext) >= 0) {
        const r = await Importer.importText(ctx, uris[i], name);
        if (r.ok) {
          okCount += 1;
          lastId = r.sessionId;
        } else {
          failNote = r.message;
        }
      } else {
        failNote = `不支持的文件类型：${name}`;
      }
    }
    if (okCount === 0) {
      return { ok: false, message: failNote.length > 0 ? failNote : '没有导入任何文件', sessionId: 0 };
    }
    return { ok: true, message: `已导入 ${okCount} 个文件，正在整理…`, sessionId: lastId };
  }

  private static async importAudio(ctx: common.UIAbilityContext, uri: string, name: string): Promise<ImportOutcome> {
    try {
      const dir = `${ctx.filesDir}/imports`;
      if (!fileIo.accessSync(dir)) {
        fileIo.mkdirSync(dir);
      }
      const safeName = name.replace(/[\\/:*?"<>|\n\r]/g, '_');
      const dest = `${dir}/${Date.now()}_${safeName}`;
      // picker 给的是 uri，不是普通路径：先按 uri 打开拿到 fd，再按 fd 复制
      const src = fileIo.openSync(uri, fileIo.OpenMode.READ_ONLY);
      try {
        fileIo.copyFileSync(src.fd, dest);
      } finally {
        fileIo.closeSync(src);
      }
      const duration = await Importer.durationOf(dest);
      const s = emptySession();
      s.title = `导入：${Importer.stripExt(name)}`;
      s.audioPath = dest;
      s.source = Src.IMPORT;
      s.envType = Env.UNKNOWN;
      s.status = Status.RECORDED;
      s.startAt = Date.now();
      s.endAt = Date.now() + duration;
      s.durationMs = duration;
      s.createdAt = Date.now();
      const id = await Store.insertSession(s);
      if (id <= 0) {
        return { ok: false, message: '写入数据库失败', sessionId: 0 };
      }
      Pipeline.run(id);
      return { ok: true, message: '', sessionId: id };
    } catch (e) {
      const err = e as BusinessError;
      return { ok: false, message: `导入音频失败：${err.message}`, sessionId: 0 };
    }
  }

  private static async importText(ctx: common.UIAbilityContext, uri: string, name: string): Promise<ImportOutcome> {
    try {
      const raw = Importer.readText(uri);
      if (raw.length === 0) {
        return { ok: false, message: `无法读取 ${name}`, sessionId: 0 };
      }
      const text = TranscriptParser.parse(raw, name);
      if (text.trim().length < 6) {
        return { ok: false, message: `${name} 里没有可用的文字`, sessionId: 0 };
      }
      const s = emptySession();
      s.title = Importer.stripExt(name);
      s.transcript = text;
      s.source = Src.IMPORT_TEXT;
      s.status = Status.TRANSCRIBED;
      s.startAt = Date.now();
      s.endAt = Date.now();
      s.createdAt = Date.now();
      const id = await Store.insertSession(s);
      if (id <= 0) {
        return { ok: false, message: '写入数据库失败', sessionId: 0 };
      }
      Pipeline.run(id);
      return { ok: true, message: '', sessionId: id };
    } catch (e) {
      const err = e as BusinessError;
      return { ok: false, message: `导入文稿失败：${err.message}`, sessionId: 0 };
    }
  }

  /** 外部应用分享进来的文本（EntryAbility 收到 want 时调用） */
  static async importSharedText(ctx: common.UIAbilityContext, text: string): Promise<number> {
    const body = text.trim();
    if (body.length === 0) {
      return 0;
    }
    const firstLine = body.split('\n')[0].trim();
    const s = emptySession();
    s.title = firstLine.length === 0 ? '外部分享'
      : (firstLine.length <= 30 ? firstLine : `${firstLine.substring(0, 30)}…`);
    s.transcript = body.substring(0, TranscriptParser.MAX_CHARS);
    s.source = Src.SHARE;
    s.status = Status.TRANSCRIBED;
    s.startAt = Date.now();
    s.endAt = Date.now();
    s.createdAt = Date.now();
    const id = await Store.insertSession(s);
    if (id > 0) {
      Pipeline.run(id);
    }
    return id;
  }

  private static readText(uri: string): string {
    const file = fileIo.openSync(uri, fileIo.OpenMode.READ_ONLY);
    try {
      const stat = fileIo.statSync(file.fd);
      if (stat.size > 8 * 1024 * 1024) {
        return '';
      }
      const buf = new ArrayBuffer(stat.size);
      fileIo.readSync(file.fd, buf);
      return util.TextDecoder.create('utf-8').decodeWithStream(new Uint8Array(buf));
    } finally {
      fileIo.closeSync(file);
    }
  }

  /** 读媒体时长：必须先把 fdSrc 指到文件上，否则 fetchMetadata 拿不到任何东西 */
  private static async durationOf(path: string): Promise<number> {
    let file: fileIo.File | null = null;
    let extractor: media.AVMetadataExtractor | null = null;
    try {
      const stat = fileIo.statSync(path);
      file = fileIo.openSync(path, fileIo.OpenMode.READ_ONLY);
      extractor = await media.createAVMetadataExtractor();
      extractor.fdSrc = { fd: file.fd, offset: 0, length: stat.size };
      const meta = await extractor.fetchMetadata();
      const d = meta.duration;
      return d === undefined ? 0 : Number(d);
    } catch (e) {
      return 0;
    } finally {
      if (extractor !== null) {
        try {
          await extractor.release();
        } catch (e) {
          // 释放失败不影响导入
        }
      }
      if (file !== null) {
        try {
          fileIo.closeSync(file);
        } catch (e) {
          // 同上
        }
      }
    }
  }

  private static fileNameOf(uri: string): string {
    const seg = uri.substring(uri.lastIndexOf('/') + 1);
    try {
      return decodeURIComponent(seg);
    } catch (e) {
      return seg;
    }
  }

  private static extOf(name: string): string {
    const idx = name.lastIndexOf('.');
    return idx < 0 ? '' : name.substring(idx + 1).toLowerCase();
  }

  private static stripExt(name: string): string {
    const idx = name.lastIndexOf('.');
    const base = idx < 0 ? name : name.substring(0, idx);
    return base.length > 0 ? base : TimeUtil.dateFull(Date.now());
  }
}
