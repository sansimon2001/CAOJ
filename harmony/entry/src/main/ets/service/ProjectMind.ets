import { Store, ProjectProfileRow } from '../data/Store';
import { Repo, Keys } from '../data/Repo';
import { ZhNormalizer } from '../util/ZhNormalizer';

/**
 * 项目档案与归属判定
 *
 * 为什么原来的做法必然归错：
 * 1) 「已有项目」清单来自 SELECT DISTINCT project FROM sessions——它是模型历史输出的回声，
 *    不是真实的项目列表。模型曾写下「城市之光项目」，清单里就多一个和「城市之光」并存的假项目；
 *    碎片越滚越多，模型面对 20 个近义名字只能瞎挑。
 * 2) 清单只有名字，没有任何可判别特征。模型不知道「盛懋中心」是写字楼、「城市之光」是公寓，
 *    更不知道各自涉及哪些客户、楼号、街道。名字越像，越容易张冠李戴。
 * 3) 提示词写「尽量复用已有项目名」，又把它放在正文第一行——模型于是倾向填一个，
 *    哪怕原文根本没提项目。这就是「这件事被记到另一个项目」的直接来源。
 *
 * 这里的做法：
 * - 项目升级为档案（规范名 + 别名 + 关键词 + 一句话说明 + 最近记录），改名只改一次，历史自动跟着走；
 * - 分析前按原文做候选预筛，只把相关的几个项目连特征一起交给模型，而不是丢一整串名字；
 * - 模型的输出必须能落到档案上（精确→归一化→别名→唯一包含），落不上就置空并标记待确认，
 *    绝不允许再产生「XXX项目」这类新碎片；
 * - 用户手动纠正时把上下文记成关键词，下次同类说法自动命中——越用越准。
 */
export class ProjectProfile {
  /** 规范名（唯一，落库用这个） */
  name: string = '';
  /** 别名：口语叫法、简称、英文名、客户内部叫法 */
  aliases: string[] = [];
  /** 关键词：楼栋、街道、客户、品牌、业态等只在谈这个项目时才会出现的词 */
  keywords: string[] = [];
  /** 一句话说明，帮助模型理解它是「什么项目」 */
  note: string = '';
  /** 最近几条记录的标题（运行时填充，作为语义参照） */
  recentTitles: string[] = [];

  /** 参与匹配的全部词面（规范名 + 别名） */
  surfaces(): string[] {
    const out: string[] = [];
    if (this.name.length > 0) {
      out.push(this.name);
    }
    for (let i = 0; i < this.aliases.length; i++) {
      if (this.aliases[i].length > 0) {
        out.push(this.aliases[i]);
      }
    }
    return out;
  }
}

/** 归属判定结果 */
export class ProjectMatch {
  /** 规范名；空字符串表示「未确认」（宁缺勿错） */
  name: string = '';
  /** 0-1，仅作提示，不作为是否采用的唯一依据 */
  confidence: number = 0;
  /** 模型抄回的原文依据句（可能为空） */
  evidence: string = '';
  /** 判定过程说明，展示给用户看「为什么归到这里」 */
  reason: string = '';

  confirmed(): boolean {
    return this.name.length > 0;
  }
}

export class ProjectMind {
  /** 参与比较前必须抹平的差异：空白、全半角、繁简、常见后缀 */
  private static readonly SUFFIX: string[] = ['项目组', '项目部', '项目', '工程', '楼盘', '小区', '案场'];

  /** 短词面归一化缓存上限；超过就整体丢弃重来（比维护 LRU 便宜且够用） */
  private static readonly CACHE_MAX: number = 512;

  private static cache: Map<string, string> = new Map<string, string>();
  private static profiles: ProjectProfile[] | null = null;

  /** 档案被改过（新建/改名/学习纠正）必须清缓存，否则下次判定还用旧档案 */
  static invalidate(): void {
    ProjectMind.profiles = null;
    ProjectMind.cache.clear();
  }

  /**
   * 归一化用于比较，不用于落库
   *
   * 「城市之光 」「城市之光项目」「城市之光工程」应被视为同一个名字；
   * 但落库仍然写规范名，避免把一个正经项目名改坏。
   */
  static norm(raw: string): string {
    const s = raw.trim();
    if (s.length === 0) {
      return '';
    }
    const cached = ProjectMind.cache.get(s);
    if (cached !== undefined && s.length <= 64) {
      return cached;
    }
    let out = '';
    // 全角→半角（只处理常见区间，不做全量转换）
    for (let i = 0; i < s.length; i++) {
      const code = s.charCodeAt(i);
      const ch = s.charAt(i);
      if (code >= 0xFF01 && code <= 0xFF5E) {
        out += String.fromCharCode(code - 0xFEE0);
      } else if (ch === '\u3000') {
        out += ' ';
      } else {
        out += ch;
      }
    }
    // 纯简体文本跳过逐字映射（大表遍历是这里唯一的开销大头）
    if (ZhNormalizer.hasTraditional(out)) {
      out = ZhNormalizer.toSimplified(out);
    }
    // 去掉所有空白与常见分隔噪音
    out = out.replace(new RegExp('[\\s·・\\-—_()（）\\[\\]【】]', 'g'), '');
    // 去尾缀：只在长度足够时才去，避免「XX项目」本身就是全名时被削成空
    for (let i = 0; i < ProjectMind.SUFFIX.length; i++) {
      const suf = ProjectMind.SUFFIX[i];
      if (out.length > suf.length + 1 && out.endsWith(suf)) {
        out = out.substring(0, out.length - suf.length);
        break;
      }
    }
    out = out.toLowerCase();
    if (s.length <= 64) {
      if (ProjectMind.cache.size >= ProjectMind.CACHE_MAX) {
        ProjectMind.cache.clear();
      }
      ProjectMind.cache.set(s, out);
    }
    return out;
  }

  // ==================== 档案读写 ====================

  static async load(): Promise<ProjectProfile[]> {
    const cached = ProjectMind.profiles;
    if (cached !== null) {
      return cached;
    }
    await ProjectMind.seedOnce();
    const rows = await Store.allProjectProfiles();
    const list: ProjectProfile[] = [];
    for (let i = 0; i < rows.length; i++) {
      list.push(ProjectMind.fromRow(rows[i]));
    }
    ProjectMind.profiles = list;
    return list;
  }

  /**
   * 首次建档案：把历史项目名导入，并顺手治好存量碎片
   *
   * 这一步对老用户尤其重要——原来的项目名是模型历次输出的回声，同一个项目很可能
   * 已经裂成「城市之光」「城市之光项目」「城市之光 」三条，每条各挂一半记录，
   * 任何统计都对不上。这里按归一化后的名字分组：记录最多的写法当规范名，
   * 其余并入并把旧写法记成别名，从此模型写哪种都能认到同一条。
   */
  private static async seedOnce(): Promise<void> {
    if (Repo.getBool(Keys.PROJECT_SEEDED, false)) {
      return;
    }
    try {
      const summaries = await Store.projectSummaries(500);
      // 分组：归一化后相同即视为同一个项目
      const keys: string[] = [];
      const groups: Map<string, string[]> = new Map<string, string[]>();
      const counts: Map<string, number> = new Map<string, number>();
      for (let i = 0; i < summaries.length; i++) {
        const s = summaries[i];
        if (s.project.length === 0) {
          continue;
        }
        const key = ProjectMind.norm(s.project);
        if (key.length === 0) {
          continue;
        }
        if (!groups.has(key)) {
          groups.set(key, []);
          counts.set(key, 0);
          keys.push(key);
        }
        const arr = groups.get(key) as string[];
        arr.push(s.project);
        counts.set(key, (counts.get(key) as number) + s.count);
      }

      const profiles: ProjectProfile[] = [];
      for (let i = 0; i < keys.length; i++) {
        const key = keys[i];
        const names = groups.get(key) as string[];
        // 记录最多的写法最可能是用户真正在用的名字
        let canonical = names[0];
        let best = -1;
        for (let j = 0; j < names.length; j++) {
          let c = 0;
          for (let k = 0; k < summaries.length; k++) {
            if (summaries[k].project === names[j]) {
              c = summaries[k].count;
              break;
            }
          }
          if (c > best) {
            best = c;
            canonical = names[j];
          }
        }
        const aliases: string[] = [];
        for (let j = 0; j < names.length; j++) {
          if (names[j] !== canonical && aliases.indexOf(names[j]) < 0) {
            aliases.push(names[j]);
            // 会话一起迁到规范名下，避免「改了一半」
            await Store.renameProjectEverywhere(names[j], canonical);
          }
        }
        const p = new ProjectProfile();
        p.name = canonical;
        p.aliases = aliases;
        profiles.push(p);
      }
      for (let i = 0; i < profiles.length; i++) {
        const row = ProjectMind.toRow(profiles[i]);
        await Store.upsertProjectProfile(row);
      }
      await Repo.setBool(Keys.PROJECT_SEEDED, true);
      // 自举写入的档案要让 load() 重新读，不能用旧缓存
      ProjectMind.invalidate();
    } catch (e) {
      // 自举失败不该阻塞使用：退回「没有档案」，判定时会如实留空
    }
  }

  private static fromRow(r: ProjectProfileRow): ProjectProfile {
    const p = new ProjectProfile();
    p.name = r.name;
    p.aliases = ProjectMind.splitLines(r.aliases);
    p.keywords = ProjectMind.splitLines(r.keywords);
    p.note = r.note;
    return p;
  }

  private static toRow(p: ProjectProfile): ProjectProfileRow {
    const r = new ProjectProfileRow();
    r.name = p.name;
    r.aliases = p.aliases.join('\n');
    r.keywords = p.keywords.join('\n');
    r.note = p.note;
    return r;
  }

  private static splitLines(s: string): string[] {
    const out: string[] = [];
    if (s.length === 0) {
      return out;
    }
    const parts = s.split('\n');
    for (let i = 0; i < parts.length; i++) {
      const t = parts[i].trim();
      if (t.length > 0) {
        out.push(t);
      }
    }
    return out;
  }

  static async save(p: ProjectProfile): Promise<void> {
    if (p.name.trim().length === 0) {
      return;
    }
    await Store.upsertProjectProfile(ProjectMind.toRow(p));
    ProjectMind.invalidate();
  }

  /**
   * 确保档案存在（已存在则原样不动）
   *
   * 手动归入某个项目时用这个，不要直接用 save：save 是整行覆盖，
   * 用一个只有名字的空档案去 save，会把该档案已积累的别名与线索词抹掉，
   * 项目判定立刻退化回「只能靠名字精确匹配」。
   */
  static async ensure(name: string): Promise<void> {
    const n = name.trim();
    if (n.length === 0) {
      return;
    }
    const exist = await Store.getProjectProfile(n);
    if (exist !== null) {
      return;
    }
    const p = new ProjectProfile();
    p.name = n;
    await ProjectMind.save(p);
  }

  /** 新建/重命名：把旧名作为别名保留，历史记录一并迁移 */
  static async rename(oldName: string, newName: string): Promise<void> {
    const to = newName.trim();
    if (to.length === 0 || to === oldName) {
      return;
    }
    const old = await Store.getProjectProfile(oldName);
    const p = new ProjectProfile();
    p.name = to;
    if (old !== null) {
      p.aliases = ProjectMind.splitLines(old.aliases);
      p.keywords = ProjectMind.splitLines(old.keywords);
      p.note = old.note;
    }
    if (oldName.length > 0 && p.aliases.indexOf(oldName) < 0) {
      p.aliases.push(oldName);
    }
    await ProjectMind.save(p);
    if (oldName.length > 0) {
      await Store.renameProjectEverywhere(oldName, to);
      await Store.deleteProjectProfile(oldName);
    }
    ProjectMind.invalidate();
  }

  // ==================== 候选预筛 ====================

  /**
   * 从原文里预筛候选项目
   *
   * 这一步解决的是「清单太长导致模型乱选」：与本次无关的项目根本不该出现在提示词里。
   * 打分只看词面命中（确定、可解释），不做语义猜测——语义判断交给后面的大模型，
   * 这里只负责把范围缩小。
   */
  static async candidates(text: string, max: number = 8): Promise<ProjectProfile[]> {
    const all = await ProjectMind.load();
    if (all.length === 0) {
      return [];
    }
    const hay = ProjectMind.norm(text);
    if (hay.length === 0) {
      return all.slice(0, max);
    }

    const scored: ProjectProfile[] = [];
    const scores: Map<string, number> = new Map<string, number>();
    for (let i = 0; i < all.length; i++) {
      const p = all[i];
      let score = 0;
      const surfaces = p.surfaces();
      for (let j = 0; j < surfaces.length; j++) {
        const n = ProjectMind.norm(surfaces[j]);
        if (n.length < 2) {
          continue;
        }
        if (hay.indexOf(n) >= 0) {
          // 名字越长越具体，命中越可信
          const s = n === hay ? 100 : 60 + Math.min(n.length * 2, 20);
          score = Math.max(score, s);
        }
      }
      for (let j = 0; j < p.keywords.length; j++) {
        const n = ProjectMind.norm(p.keywords[j]);
        if (n.length >= 2 && hay.indexOf(n) >= 0) {
          score += 12;
        }
      }
      if (score > 0) {
        scored.push(p);
        scores.set(p.name, score);
      }
    }

    scored.sort((a: ProjectProfile, b: ProjectProfile) => {
      const sa = scores.get(a.name) as number;
      const sb = scores.get(b.name) as number;
      return sb - sa;
    });
    const picked: ProjectProfile[] = [];
    for (let i = 0; i < scored.length; i++) {
      if (picked.length >= max) {
        break;
      }
      picked.push(scored[i]);
    }
    // 命中太少时补最近的活跃项目：模型需要一个「大概率范围」作为参照，
    // 但提示词里会明确写「没有证据就留空」，不会因为出现了就硬选
    if (picked.length < 2) {
      const fill = await Store.recentProjects(max - picked.length);
      for (let i = 0; i < fill.length; i++) {
        if (picked.length >= max) {
          break;
        }
        let dup = false;
        for (let j = 0; j < picked.length; j++) {
          if (picked[j].name === fill[i]) {
            dup = true;
            break;
          }
        }
        if (dup) {
          continue;
        }
        for (let j = 0; j < all.length; j++) {
          if (all[j].name === fill[i]) {
            picked.push(all[j]);
            break;
          }
        }
      }
    }
    // 补最近记录标题，作为「这个项目平时聊什么」的语义参照
    for (let i = 0; i < picked.length; i++) {
      picked[i].recentTitles = await Store.recentTitlesOfProject(picked[i].name, 3);
    }
    return picked;
  }

  /**
   * 生成给模型的候选项目说明块
   *
   * 每条都带别名与关键词，模型才能判断「原文提到的是哪一个」，
   * 而不是在「城市之光 / 城市之光二期」之间掷硬币。
   */
  static hintBlock(cands: ProjectProfile[]): string {
    if (cands.length === 0) {
      return '';
    }
    let sb = '候选项目（project 只能从这里选，或留空）：\n';
    for (let i = 0; i < cands.length; i++) {
      const p = cands[i];
      sb += '- ' + p.name;
      if (p.aliases.length > 0) {
        sb += '｜别名：' + p.aliases.join('、');
      }
      if (p.keywords.length > 0) {
        sb += '｜线索词：' + p.keywords.join('、');
      }
      if (p.note.length > 0) {
        sb += '｜说明：' + p.note;
      }
      sb += '\n';
      if (p.recentTitles.length > 0) {
        sb += '  平时聊：' + p.recentTitles.join('；') + '\n';
      }
    }
    return sb;
  }

  // ==================== 强校验 ====================

  /**
   * 把模型给的名字落到档案上
   *
   * 顺序：精确 → 归一化 → 别名 → 唯一包含。
   * 全部落不上就返回未确认——这一步是「脏项目不再新增」的闸门。
   * 模型给出「城市之光项目」而档案里叫「城市之光」时，这里会把它纠正成规范名；
   * 给出一个档案里完全没有的名字时，宁可留空让用户手动指定，也不新建。
   */
  static async resolve(raw: string, cands: ProjectProfile[]): Promise<ProjectMatch> {
    const m = new ProjectMatch();
    const name = raw.trim();
    if (name.length === 0) {
      return m;
    }
    const pool = cands.length > 0 ? cands : await ProjectMind.load();
    if (pool.length === 0) {
      m.reason = '还没有项目档案';
      return m;
    }

    // 1) 精确
    for (let i = 0; i < pool.length; i++) {
      if (pool[i].name === name) {
        m.name = pool[i].name;
        m.confidence = 1.0;
        m.reason = '与项目名完全一致';
        return m;
      }
    }
    // 2) 归一化后相等（吃空格/繁简/后缀差异）
    const n = ProjectMind.norm(name);
    if (n.length > 0) {
      for (let i = 0; i < pool.length; i++) {
        if (ProjectMind.norm(pool[i].name) === n) {
          m.name = pool[i].name;
          m.confidence = 0.95;
          m.reason = `名称归一化后一致（原写作「${name}」）`;
          return m;
        }
      }
      for (let i = 0; i < pool.length; i++) {
        for (let j = 0; j < pool[i].aliases.length; j++) {
          if (ProjectMind.norm(pool[i].aliases[j]) === n) {
            m.name = pool[i].name;
            m.confidence = 0.9;
            m.reason = `命中别名「${name}」`;
            return m;
          }
        }
      }
    }
    // 3) 唯一包含：模型多写了限定词，或原文用简称
    if (n.length >= 2) {
      const hits: ProjectProfile[] = [];
      for (let i = 0; i < pool.length; i++) {
        const surfaces = pool[i].surfaces();
        let hit = false;
        for (let j = 0; j < surfaces.length; j++) {
          const ns = ProjectMind.norm(surfaces[j]);
          if (ns.length >= 2 && (ns.indexOf(n) >= 0 || n.indexOf(ns) >= 0)) {
            hit = true;
            break;
          }
        }
        if (hit) {
          hits.push(pool[i]);
        }
      }
      if (hits.length === 1) {
        m.name = hits[0].name;
        m.confidence = 0.8;
        m.reason = `名称包含匹配（原写作「${name}」）`;
        return m;
      }
      if (hits.length > 1) {
        // 多个都像 → 不猜
        const names: string[] = [];
        for (let i = 0; i < hits.length && i < 3; i++) {
          names.push(hits[i].name);
        }
        m.confidence = 0;
        m.reason = `「${name}」同时像 ${names.join('、')}，已留空待确认`;
        return m;
      }
    }
    m.confidence = 0;
    m.reason = `「${name}」不在项目档案中，已留空（可在详情页手动指定）`;
    return m;
  }

  /**
   * 判定「模型抄回来的依据」是否真的在原文里
   *
   * 只做宽松校验：取依据中 6 字以上的片段看是否出现。
   * 方言归一化会改字，所以不能要求逐字相等；但完全找不到说明模型在编，
   * 这时候不能再用它的归属结论。
   */
  static evidenceInText(evidence: string, text: string): boolean {
    const e = ProjectMind.norm(evidence);
    const t = ProjectMind.norm(text);
    if (e.length === 0 || t.length === 0) {
      return false;
    }
    if (t.indexOf(e) >= 0) {
      return true;
    }
    // 逐段回扣：按标点切成短语，任意一段（≥6 字）出现即算有据
    const parts = evidence.split(new RegExp('[，。！？；、,.!?;\\n]'));
    for (let i = 0; i < parts.length; i++) {
      const pn = ProjectMind.norm(parts[i]);
      if (pn.length >= 6 && t.indexOf(pn) >= 0) {
        return true;
      }
    }
    return false;
  }

  /**
   * 用户纠正时学习：把「这次说的叫法」记成线索词
   *
   * 只学项目名在原文里出现位置的前后各 2 字，形成「XX城市之光YY」式线索词；
   * 词表有上限（12 个），不自动无限扩张，避免学脏。
   */
  static async learnFromCorrection(project: string, text: string): Promise<void> {
    if (project.length === 0 || text.length === 0) {
      return;
    }
    const row = await Store.getProjectProfile(project);
    if (row === null) {
      return;
    }
    const p = ProjectMind.fromRow(row);
    const n = ProjectMind.norm(project);
    if (n.length < 2) {
      return;
    }
    const plain = ProjectMind.norm(text);
    const idx = plain.indexOf(n);
    if (idx < 0) {
      return;
    }
    const start = Math.max(0, idx - 2);
    const end = Math.min(plain.length, idx + n.length + 2);
    const ctxWord = plain.substring(start, end);
    if (ctxWord.length <= n.length) {
      return; // 前后无字，学不到东西
    }
    for (let i = 0; i < p.keywords.length; i++) {
      if (ProjectMind.norm(p.keywords[i]) === ctxWord) {
        return; // 已有
      }
    }
    if (p.keywords.length >= 12) {
      return;
    }
    p.keywords.push(ctxWord);
    await ProjectMind.save(p);
  }

  /**
   * 带证据的归属判定（三道关）
   *
   * 1) 名字必须落档（resolve：精确→归一化→别名→唯一包含），落不上直接留空；
   * 2) 模型抄回的 evidence 必须真的出现在原文里——找不到就判定这次归属不可信，
   *    整体留空。大模型最常见的失败不是「不知道」，而是「知道该填但又没证据，于是编一句」；
   * 3) 没有依据时把置信度压到 0.45 以下，界面标成「待确认」，提示用户复核。
   *
   * 这三步都不依赖模型自觉，任何一条不满足就地降级，因此不会再出现
   * 「这件事明明在谈 A，被记到 B」而用户还完全不知情的情况。
   */
  static async resolveWithEvidence(raw: string, evidence: string, modelConf: number,
    originalText: string, cands: ProjectProfile[]): Promise<ProjectMatch> {
    const m = new ProjectMatch();
    const name = raw.trim();
    const ev = evidence.trim();
    const conf = isNaN(modelConf) ? 0 : modelConf;
    if (name.length === 0) {
      m.reason = '未提到可归属的项目';
      return m;
    }
    const r = await ProjectMind.resolve(name, cands);
    if (!r.confirmed()) {
      m.reason = r.reason;
      return m;
    }
    // 依据校验：编造的依据 → 整个归属作废
    if (ev.length > 0 && !ProjectMind.evidenceInText(ev, originalText)) {
      m.reason = `给出的依据「${ev.substring(0, 20)}」在原文中找不到，已留空待确认`;
      return m;
    }
    if (ev.length === 0) {
      // 没抄依据说明它并没有真的在原文里看到线索，按「疑似」处理
      const mc = Math.min(Math.max(conf, 0), 1);
      m.name = r.name;
      m.confidence = Math.min(Math.max(r.confidence, mc), 0.45);
      m.evidence = '';
      m.reason = `${r.reason}；模型未给出原文依据，建议复核`;
      return m;
    }
    const mc = Math.min(Math.max(conf, 0), 1);
    m.name = r.name;
    m.confidence = Math.min(Math.max(r.confidence, mc), 1);
    m.evidence = ev;
    m.reason = `${r.reason}；依据：「${ev.substring(0, 40)}」`;
    return m;
  }

  /** 供详情页展示的一句话归属说明 */
  static describe(m: ProjectMatch): string {
    if (!m.confirmed()) {
      return m.reason.length > 0 ? m.reason : '未归属项目';
    }
    let sb = `归入「${m.name}」`;
    if (m.reason.length > 0) {
      sb += `（${m.reason}）`;
    }
    if (m.evidence.length > 0) {
      sb += `：${m.evidence.substring(0, 40)}`;
    }
    return sb;
  }
}
