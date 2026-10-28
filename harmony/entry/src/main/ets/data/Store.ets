import { relationalStore } from '@kit.ArkData';
import { common } from '@kit.AbilityKit';
import { Session, TodoDraft, TodoItem, ProjectSummary, emptySession } from '../model/Models';

/**
 * 本地数据库（relationalStore）
 *
 * 与 Android 版同构：sessions / todos / project_insights / settings 四张表，
 * 字段名保持一致，方便将来两端导出的 SQLite 备份可以互读。
 */
export class Store {
  private static store: relationalStore.RdbStore | null = null;

  static isReady(): boolean {
    return Store.store !== null;
  }

  static async init(ctx: common.Context): Promise<void> {
    if (Store.store !== null) {
      return;
    }
    const config: relationalStore.StoreConfig = {
      name: 'caoji.db',
      securityLevel: relationalStore.SecurityLevel.S2
    };
    const db = await relationalStore.getRdbStore(ctx, config);
    await db.executeSql(`CREATE TABLE IF NOT EXISTS sessions (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      title TEXT NOT NULL DEFAULT '',
      transcript TEXT NOT NULL DEFAULT '',
      summary TEXT NOT NULL DEFAULT '',
      project TEXT NOT NULL DEFAULT '',
      source TEXT NOT NULL DEFAULT 'live',
      audioPath TEXT NOT NULL DEFAULT '',
      startAt INTEGER NOT NULL DEFAULT 0,
      endAt INTEGER NOT NULL DEFAULT 0,
      durationMs INTEGER NOT NULL DEFAULT 0,
      marked INTEGER NOT NULL DEFAULT 0,
      marks TEXT NOT NULL DEFAULT '',
      envType TEXT NOT NULL DEFAULT 'unknown',
      noiseDb REAL NOT NULL DEFAULT 0,
      speechRatio REAL NOT NULL DEFAULT 0,
      voiceSpeaker TEXT NOT NULL DEFAULT '',
      voiceScore REAL NOT NULL DEFAULT 0,
      voiceMargin REAL NOT NULL DEFAULT 0,
      lang TEXT NOT NULL DEFAULT 'zh',
      langNote TEXT NOT NULL DEFAULT '',
      detected TEXT NOT NULL DEFAULT '',
      transcriptStd TEXT NOT NULL DEFAULT '',
      quality TEXT NOT NULL DEFAULT 'ok',
      status TEXT NOT NULL DEFAULT 'recorded',
      createdAt INTEGER NOT NULL DEFAULT 0
    )`);
    await db.executeSql(`CREATE TABLE IF NOT EXISTS todos (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      sessionId INTEGER NOT NULL,
      text TEXT NOT NULL DEFAULT '',
      done INTEGER NOT NULL DEFAULT 0,
      dueDate TEXT NOT NULL DEFAULT '',
      createdAt INTEGER NOT NULL DEFAULT 0
    )`);
    await db.executeSql(`CREATE TABLE IF NOT EXISTS project_insights (
      project TEXT PRIMARY KEY,
      content TEXT NOT NULL DEFAULT '',
      updatedAt INTEGER NOT NULL DEFAULT 0
    )`);
    await db.executeSql(`CREATE TABLE IF NOT EXISTS settings (
      k TEXT PRIMARY KEY,
      v TEXT NOT NULL DEFAULT ''
    )`);
    /**
     * 项目档案
     *
     * 原来的「已有项目」清单是从 sessions 里 SELECT DISTINCT 出来的，那只是
     * 模型历次输出的回声——同一个项目很容易裂成「城市之光」「城市之光项目」三条。
     * 档案表才是唯一事实来源：规范名 + 别名 + 线索词，改名只改一次，历史自动跟着走。
     * aliases / keywords 用换行分隔存成字符串（架构简单、无需联表）。
     */
    await db.executeSql(`CREATE TABLE IF NOT EXISTS project_profiles (
      name TEXT PRIMARY KEY,
      aliases TEXT NOT NULL DEFAULT '',
      keywords TEXT NOT NULL DEFAULT '',
      note TEXT NOT NULL DEFAULT '',
      updatedAt INTEGER NOT NULL DEFAULT 0
    )`);
    await db.executeSql('CREATE INDEX IF NOT EXISTS idx_session_start ON sessions(startAt DESC)');
    await db.executeSql('CREATE INDEX IF NOT EXISTS idx_todo_session ON todos(sessionId)');
    await Store.migrate(db);
    Store.store = db;
  }

  /**
   * 老库补列
   *
   * CREATE TABLE IF NOT EXISTS 对已存在的表是空操作，装过旧版的用户升级上来
   * 表里不会有新列，随后 insert 里带上新列会直接抛错——表现为「新装正常、升级异常」，
   * 这是最容易被漏掉的一类问题。所以每次启动都对着 PRAGMA 结果补齐。
   */
  private static async migrate(db: relationalStore.RdbStore): Promise<void> {
    await Store.ensureColumn(db, 'sessions', 'lang', "TEXT NOT NULL DEFAULT 'zh'");
    await Store.ensureColumn(db, 'sessions', 'langNote', "TEXT NOT NULL DEFAULT ''");
    await Store.ensureColumn(db, 'sessions', 'detected', "TEXT NOT NULL DEFAULT ''");
    await Store.ensureColumn(db, 'sessions', 'transcriptStd', "TEXT NOT NULL DEFAULT ''");
  }

  private static async ensureColumn(db: relationalStore.RdbStore, table: string,
    column: string, decl: string): Promise<void> {
    const rs = await db.querySql(`PRAGMA table_info(${table})`);
    let exists = false;
    try {
      const idx = rs.getColumnIndex('name');
      while (rs.goToNextRow()) {
        if (rs.getString(idx) === column) {
          exists = true;
          break;
        }
      }
    } finally {
      rs.close();
    }
    if (!exists) {
      await db.executeSql(`ALTER TABLE ${table} ADD COLUMN ${column} ${decl}`);
    }
  }

  private static db(): relationalStore.RdbStore {
    const db = Store.store;
    if (db === null) {
      throw new Error('数据库尚未初始化');
    }
    return db;
  }

  // ==================== sessions ====================

  static async insertSession(s: Session): Promise<number> {
    const db = Store.db();
    await db.executeSql(
      `INSERT INTO sessions (title, transcript, summary, project, source, audioPath, startAt, endAt,
        durationMs, marked, marks, envType, noiseDb, speechRatio, voiceSpeaker, voiceScore, voiceMargin,
        lang, langNote, detected, transcriptStd,
        quality, status, createdAt) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`,
      [
        s.title, s.transcript, s.summary, s.project, s.source, s.audioPath, s.startAt, s.endAt,
        s.durationMs, s.marked ? 1 : 0, s.marks, s.envType, s.noiseDb, s.speechRatio,
        s.voiceSpeaker, s.voiceScore, s.voiceMargin,
        s.lang, s.langNote, s.detected, s.transcriptStd,
        s.quality, s.status,
        s.createdAt > 0 ? s.createdAt : Date.now()
      ]
    );
    const rs = await db.querySql('SELECT last_insert_rowid() AS id');
    let id = 0;
    if (rs.goToFirstRow()) {
      id = rs.getLong(rs.getColumnIndex('id'));
    }
    rs.close();
    return id;
  }

  static async updateSession(s: Session): Promise<void> {
    const db = Store.db();
    await db.executeSql(
      `UPDATE sessions SET title=?, transcript=?, summary=?, project=?, source=?, audioPath=?,
        startAt=?, endAt=?, durationMs=?, marked=?, marks=?, envType=?, noiseDb=?, speechRatio=?,
        voiceSpeaker=?, voiceScore=?, voiceMargin=?, lang=?, langNote=?, detected=?, transcriptStd=?,
        quality=?, status=? WHERE id=?`,
      [
        s.title, s.transcript, s.summary, s.project, s.source, s.audioPath, s.startAt, s.endAt,
        s.durationMs, s.marked ? 1 : 0, s.marks, s.envType, s.noiseDb, s.speechRatio,
        s.voiceSpeaker, s.voiceScore, s.voiceMargin, s.lang, s.langNote, s.detected, s.transcriptStd,
        s.quality, s.status, s.id
      ]
    );
  }

  static async deleteSession(id: number): Promise<void> {
    const db = Store.db();
    await db.executeSql('DELETE FROM sessions WHERE id=?', [id]);
    await db.executeSql('DELETE FROM todos WHERE sessionId=?', [id]);
  }

  static async getSession(id: number): Promise<Session | null> {
    const list = await Store.querySessions('id=?', [id], 1);
    return list.length > 0 ? list[0] : null;
  }

  static async querySessions(where: string, args: relationalStore.ValueType[], limit: number): Promise<Session[]> {
    const sql = `SELECT * FROM sessions${where.length > 0 ? ` WHERE ${where}` : ''} ORDER BY startAt DESC LIMIT ${limit}`;
    const rs = await Store.db().querySql(sql, args);
    const out: Session[] = [];
    while (rs.goToNextRow()) {
      out.push(Store.rowToSession(rs));
    }
    rs.close();
    return out;
  }

  static async recent(limit: number): Promise<Session[]> {
    return Store.querySessions('', [], limit);
  }

  static async between(from: number, to: number): Promise<Session[]> {
    return Store.querySessions('startAt>=? AND startAt<?', [from, to], 500);
  }

  static async byProject(project: string): Promise<Session[]> {
    return Store.querySessions('project=?', [project], 200);
  }

  /** 项目列表：条数、最近时间、最近一条摘要（用于项目卡片） */
  static async projectSummaries(limit: number): Promise<ProjectSummary[]> {
    const rs = await Store.db().querySql(
      `SELECT project, COUNT(*) AS c, MAX(startAt) AS lastAt FROM sessions
       WHERE project<>'' GROUP BY project ORDER BY lastAt DESC LIMIT ${limit}`
    );
    const out: ProjectSummary[] = [];
    while (rs.goToNextRow()) {
      const name = rs.getString(rs.getColumnIndex('project'));
      out.push({
        project: name,
        count: rs.getLong(rs.getColumnIndex('c')),
        lastAt: rs.getLong(rs.getColumnIndex('lastAt')),
        summary: ''
      });
    }
    rs.close();
    // 摘要单独取最近一条（放同一条 SQL 里会引入窗口函数，低版本不支持）
    for (let i = 0; i < out.length; i++) {
      const one = await Store.querySessions('project=?', [out[i].project], 1);
      if (one.length > 0) {
        out[i].summary = one[0].summary;
      }
    }
    return out;
  }

  // ==================== todos ====================

  static async addTodos(sessionId: number, drafts: TodoDraft[]): Promise<void> {
    const db = Store.db();
    for (let i = 0; i < drafts.length; i++) {
      const d = drafts[i];
      if (d.text.length === 0) {
        continue;
      }
      await db.executeSql(
        'INSERT INTO todos (sessionId, text, done, dueDate, createdAt) VALUES (?,?,0,?,?)',
        [sessionId, d.text, d.due, Date.now()]
      );
    }
  }

  static async todosOf(sessionId: number): Promise<TodoItem[]> {
    const rs = await Store.db().querySql(
      'SELECT * FROM todos WHERE sessionId=? ORDER BY done ASC, id ASC', [sessionId]
    );
    const out: TodoItem[] = [];
    while (rs.goToNextRow()) {
      out.push(Store.rowToTodo(rs));
    }
    rs.close();
    return out;
  }

  static async allTodos(limit: number): Promise<TodoItem[]> {
    const rs = await Store.db().querySql(
      `SELECT * FROM todos ORDER BY done ASC, (dueDate='') ASC, dueDate ASC LIMIT ${limit}`
    );
    const out: TodoItem[] = [];
    while (rs.goToNextRow()) {
      out.push(Store.rowToTodo(rs));
    }
    rs.close();
    return out;
  }

  static async setTodoDone(id: number, done: boolean): Promise<void> {
    await Store.db().executeSql('UPDATE todos SET done=? WHERE id=?', [done ? 1 : 0, id]);
  }

  static async setTodoDue(id: number, due: string): Promise<void> {
    await Store.db().executeSql('UPDATE todos SET dueDate=? WHERE id=?', [due, id]);
  }

  /** 待办统计：[未完成, 已完成] */
  static async todoStats(): Promise<number[]> {
    const rs = await Store.db().querySql('SELECT done, COUNT(*) AS c FROM todos GROUP BY done');
    let open = 0;
    let done = 0;
    while (rs.goToNextRow()) {
      const isDone = rs.getLong(rs.getColumnIndex('done')) === 1;
      const c = rs.getLong(rs.getColumnIndex('c'));
      if (isDone) {
        done = c;
      } else {
        open = c;
      }
    }
    rs.close();
    return [open, done];
  }

  /** 逾期与今日到期数量：[逾期, 今天] */
  static async todoDueStats(today: string): Promise<number[]> {
    const rs = await Store.db().querySql(
      `SELECT
        COALESCE(SUM(CASE WHEN dueDate<>'' AND dueDate<? THEN 1 ELSE 0 END), 0) AS overdue,
        COALESCE(SUM(CASE WHEN dueDate=? THEN 1 ELSE 0 END), 0) AS today
       FROM todos WHERE done=0`, [today, today]
    );
    let overdue = 0;
    let dueToday = 0;
    if (rs.goToFirstRow()) {
      overdue = rs.getLong(rs.getColumnIndex('overdue'));
      dueToday = rs.getLong(rs.getColumnIndex('today'));
    }
    rs.close();
    return [overdue, dueToday];
  }

  // ==================== project insights ====================

  static async saveProjectInsight(project: string, content: string): Promise<void> {
    await Store.db().executeSql(
      'INSERT OR REPLACE INTO project_insights (project, content, updatedAt) VALUES (?,?,?)',
      [project, content, Date.now()]
    );
  }

  static async getProjectInsight(project: string): Promise<string> {
    const rs = await Store.db().querySql('SELECT content FROM project_insights WHERE project=?', [project]);
    let content = '';
    if (rs.goToFirstRow()) {
      content = rs.getString(rs.getColumnIndex('content'));
    }
    rs.close();
    return content;
  }

  static async clearAll(): Promise<void> {
    const db = Store.db();
    await db.executeSql('DELETE FROM sessions');
    await db.executeSql('DELETE FROM todos');
    await db.executeSql('DELETE FROM project_insights');
  }

  // ==================== 行映射 ====================

  private static rowToSession(rs: relationalStore.ResultSet): Session {
    const s = emptySession();
    s.id = rs.getLong(rs.getColumnIndex('id'));
    s.title = rs.getString(rs.getColumnIndex('title'));
    s.transcript = rs.getString(rs.getColumnIndex('transcript'));
    s.summary = rs.getString(rs.getColumnIndex('summary'));
    s.project = rs.getString(rs.getColumnIndex('project'));
    s.source = rs.getString(rs.getColumnIndex('source'));
    s.audioPath = rs.getString(rs.getColumnIndex('audioPath'));
    s.startAt = rs.getLong(rs.getColumnIndex('startAt'));
    s.endAt = rs.getLong(rs.getColumnIndex('endAt'));
    s.durationMs = rs.getLong(rs.getColumnIndex('durationMs'));
    s.marked = rs.getLong(rs.getColumnIndex('marked')) === 1;
    s.marks = rs.getString(rs.getColumnIndex('marks'));
    s.envType = rs.getString(rs.getColumnIndex('envType'));
    s.noiseDb = rs.getDouble(rs.getColumnIndex('noiseDb'));
    s.speechRatio = rs.getDouble(rs.getColumnIndex('speechRatio'));
    s.voiceSpeaker = rs.getString(rs.getColumnIndex('voiceSpeaker'));
    s.voiceScore = rs.getDouble(rs.getColumnIndex('voiceScore'));
    s.voiceMargin = rs.getDouble(rs.getColumnIndex('voiceMargin'));
    // 老库里这三列可能刚由 migrate() 补上，DEFAULT 已保证有值
    s.lang = rs.getString(rs.getColumnIndex('lang'));
    s.langNote = rs.getString(rs.getColumnIndex('langNote'));
    s.detected = rs.getString(rs.getColumnIndex('detected'));
    s.transcriptStd = rs.getString(rs.getColumnIndex('transcriptStd'));
    s.quality = rs.getString(rs.getColumnIndex('quality'));
    s.status = rs.getString(rs.getColumnIndex('status'));
    s.createdAt = rs.getLong(rs.getColumnIndex('createdAt'));
    return s;
  }

  private static rowToTodo(rs: relationalStore.ResultSet): TodoItem {
    return {
      id: rs.getLong(rs.getColumnIndex('id')),
      sessionId: rs.getLong(rs.getColumnIndex('sessionId')),
      text: rs.getString(rs.getColumnIndex('text')),
      done: rs.getLong(rs.getColumnIndex('done')) === 1,
      dueDate: rs.getString(rs.getColumnIndex('dueDate')),
      createdAt: rs.getLong(rs.getColumnIndex('createdAt'))
    };
  }

  // ==================== 项目档案 ====================

  static async allProjectProfiles(): Promise<ProjectProfileRow[]> {
    const rs = await Store.db().querySql('SELECT * FROM project_profiles ORDER BY updatedAt DESC');
    const out: ProjectProfileRow[] = [];
    while (rs.goToNextRow()) {
      out.push(Store.rowToProfile(rs));
    }
    rs.close();
    return out;
  }

  static async getProjectProfile(name: string): Promise<ProjectProfileRow | null> {
    const rs = await Store.db().querySql('SELECT * FROM project_profiles WHERE name=?', [name]);
    let row: ProjectProfileRow | null = null;
    if (rs.goToFirstRow()) {
      row = Store.rowToProfile(rs);
    }
    rs.close();
    return row;
  }

  static async upsertProjectProfile(p: ProjectProfileRow): Promise<void> {
    await Store.db().executeSql(
      `INSERT INTO project_profiles (name, aliases, keywords, note, updatedAt) VALUES (?,?,?,?,?)
       ON CONFLICT(name) DO UPDATE SET aliases=?, keywords=?, note=?, updatedAt=?`,
      [p.name, p.aliases, p.keywords, p.note, Date.now(),
        p.aliases, p.keywords, p.note, Date.now()]
    );
  }

  static async deleteProjectProfile(name: string): Promise<void> {
    await Store.db().executeSql('DELETE FROM project_profiles WHERE name=?', [name]);
  }

  /** 改名迁移：会话里出现的旧名一并改到新名下，避免「改了一半」 */
  static async renameProjectEverywhere(from: string, to: string): Promise<void> {
    await Store.db().executeSql('UPDATE sessions SET project=? WHERE project=?', [to, from]);
  }

  /** 最近活跃的项目名（按最后一条记录的时间） */
  static async recentProjects(limit: number): Promise<string[]> {
    const rs = await Store.db().querySql(
      `SELECT project, MAX(startAt) AS lastAt FROM sessions WHERE project<>''
       GROUP BY project ORDER BY lastAt DESC LIMIT ${limit}`
    );
    const out: string[] = [];
    while (rs.goToNextRow()) {
      out.push(rs.getString(rs.getColumnIndex('project')));
    }
    rs.close();
    return out;
  }

  /** 某项目最近几条记录的标题，作为「这个项目平时聊什么」的语义参照 */
  static async recentTitlesOfProject(project: string, limit: number): Promise<string[]> {
    const rs = await Store.db().querySql(
      `SELECT title FROM sessions WHERE project=? AND title<>'' ORDER BY startAt DESC LIMIT ${limit}`,
      [project]
    );
    const out: string[] = [];
    while (rs.goToNextRow()) {
      out.push(rs.getString(rs.getColumnIndex('title')));
    }
    rs.close();
    return out;
  }

  /** 手动指定/纠正项目归属 */
  static async setSessionProject(sessionId: number, project: string): Promise<void> {
    await Store.db().executeSql('UPDATE sessions SET project=? WHERE id=?', [project, sessionId]);
  }

  private static rowToProfile(rs: relationalStore.ResultSet): ProjectProfileRow {
    const row = new ProjectProfileRow();
    row.name = rs.getString(rs.getColumnIndex('name'));
    row.aliases = rs.getString(rs.getColumnIndex('aliases'));
    row.keywords = rs.getString(rs.getColumnIndex('keywords'));
    row.note = rs.getString(rs.getColumnIndex('note'));
    return row;
  }
}

/** 项目档案的一行（aliases / keywords 以换行分隔） */
export class ProjectProfileRow {
  name: string = '';
  aliases: string = '';
  keywords: string = '';
  note: string = '';
}
