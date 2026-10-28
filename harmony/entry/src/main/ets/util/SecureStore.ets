import { cryptoFramework } from '@kit.CryptoArchitectureKit';
import { preferences } from '@kit.ArkData';
import { fileIo } from '@kit.CoreFileKit';
import { util } from '@kit.ArkTS';
import { common } from '@kit.AbilityKit';

/**
 * 敏感配置加密存储（API Key / 机器人与 Webhook 密钥）
 *
 * 做法：AES-256-CBC + 每次随机 IV，密文以 `iv:cipher` 的 base64 存进 preferences。
 * 主密钥 32 字节随机生成，首次启动写入应用沙箱 `filesDir/caoji.key`，
 * 只有在 init() 时读进内存，之后不落盘任何明文。
 *
 * 说明：鸿蒙应用沙箱本身位于设备加密分区（EL2），卸载即随沙箱销毁。
 * 若后续要提升到「密钥不出 TEE」，把 readKey/createKey 换成 HUKS 的
 * generateKeyItem + initSession/updateSession 即可，上层接口不用改。
 */
export class SecureStore {
  private static cache: Map<string, string> = new Map();
  private static symKey: cryptoFramework.SymKey | null = null;
  private static pref: preferences.Preferences | null = null;
  private static ready: boolean = false;
  private static readonly PREF_NAME: string = 'caoji_secure';
  private static readonly KEY_FILE: string = '/caoji_master.key';

  /** 启动时调用一次：准备好主密钥并批量解密到内存 */
  static async init(ctx: common.Context): Promise<void> {
    if (SecureStore.ready) {
      return;
    }
    SecureStore.pref = await preferences.getPreferences(ctx, SecureStore.PREF_NAME);
    SecureStore.symKey = await SecureStore.loadOrCreateKey(ctx);
    const all = await SecureStore.pref.getAll();
    const table = all as Record<string, Object>;
    const keys = Object.keys(table);
    for (let i = 0; i < keys.length; i++) {
      const name = keys[i];
      const raw = table[name];
      if (typeof raw !== 'string') {
        continue;
      }
      const plain = await SecureStore.decrypt(raw as string);
      if (plain.length > 0) {
        SecureStore.cache.set(name, plain);
      }
    }
    SecureStore.ready = true;
  }

  /** 同步读（内存缓存），供 Repo 这类同步调用点使用 */
  static get(name: string): string {
    const v = SecureStore.cache.get(name);
    return v === undefined ? '' : v;
  }

  static has(name: string): boolean {
    return SecureStore.get(name).length > 0;
  }

  static async put(name: string, value: string): Promise<void> {
    if (value.length === 0) {
      await SecureStore.remove(name);
      return;
    }
    SecureStore.cache.set(name, value);
    const enc = await SecureStore.encrypt(value);
    if (SecureStore.pref !== null) {
      await SecureStore.pref.put(name, enc);
      await SecureStore.pref.flush();
    }
  }

  static async remove(name: string): Promise<void> {
    SecureStore.cache.delete(name);
    if (SecureStore.pref !== null) {
      await SecureStore.pref.delete(name);
      await SecureStore.pref.flush();
    }
  }

  // ---------------- 内部实现 ----------------

  private static async loadOrCreateKey(ctx: common.Context): Promise<cryptoFramework.SymKey> {
    const path = ctx.filesDir + SecureStore.KEY_FILE;
    const gen = cryptoFramework.createSymKeyGenerator('AES256');
    if (fileIo.accessSync(path)) {
      const stat = fileIo.statSync(path);
      const buf = new ArrayBuffer(stat.size);
      const file = fileIo.openSync(path, fileIo.OpenMode.READ_ONLY);
      try {
        fileIo.readSync(file.fd, buf);
      } finally {
        fileIo.closeSync(file);
      }
      const blob: cryptoFramework.DataBlob = { data: new Uint8Array(buf) };
      return await gen.convertKey(blob);
    }
    const key = await gen.generateSymKey();
    const encoded = key.getEncoded();
    const file = fileIo.openSync(path, fileIo.OpenMode.CREATE | fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.TRUNC);
    try {
      fileIo.writeSync(file.fd, encoded.data.buffer as ArrayBuffer);
    } finally {
      fileIo.closeSync(file);
    }
    return key;
  }

  private static async encrypt(plain: string): Promise<string> {
    const key = SecureStore.symKey;
    if (key === null) {
      return '';
    }
    const iv = SecureStore.randomIv();
    const cipher = cryptoFramework.createCipher('AES256|CBC|PKCS7');
    const spec: cryptoFramework.IvParamsSpec = {
      iv: { data: iv },
      algName: 'IvParamsSpec'
    };
    await cipher.init(cryptoFramework.CryptoMode.ENCRYPT_MODE, key, spec);
    const input: cryptoFramework.DataBlob = { data: new util.TextEncoder().encodeInto(plain) };
    const out = await cipher.doFinal(input);
    const helper = new util.Base64Helper();
    return `${helper.encodeToStringSync(iv)}:${helper.encodeToStringSync(out.data)}`;
  }

  private static async decrypt(payload: string): Promise<string> {
    const key = SecureStore.symKey;
    if (key === null || payload.length === 0) {
      return '';
    }
    const idx = payload.indexOf(':');
    if (idx <= 0) {
      return '';
    }
    try {
      const helper = new util.Base64Helper();
      const iv = helper.decodeSync(payload.substring(0, idx));
      const cipherBytes = helper.decodeSync(payload.substring(idx + 1));
      const cipher = cryptoFramework.createCipher('AES256|CBC|PKCS7');
      const spec: cryptoFramework.IvParamsSpec = {
        iv: { data: iv },
        algName: 'IvParamsSpec'
      };
      await cipher.init(cryptoFramework.CryptoMode.DECRYPT_MODE, key, spec);
      const out = await cipher.doFinal({ data: cipherBytes });
      return util.TextDecoder.create('utf-8').decodeWithStream(out.data);
    } catch (e) {
      // 主密钥被换过（如清除数据后又恢复备份）：旧密文无法解，直接丢掉而不是抛给界面
      return '';
    }
  }

  private static randomIv(): Uint8Array {
    const iv = new Uint8Array(16);
    const rand = cryptoFramework.createRandom();
    const blob = rand.generateRandomSync(16);
    iv.set(blob.data);
    return iv;
  }
}
