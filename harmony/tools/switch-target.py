#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""在 HarmonyOS / OpenHarmony 两套构建配置之间切换。

为什么要有这个脚本
------------------
草记的目标平台是鸿蒙手机，正本配置必须是 HarmonyOS 那套：
  - compatibleSdkVersion "5.0.0(12)" + runtimeOS "HarmonyOS"
  - module.json5 里有 backgroundModes: [audioRecording]（长时任务）
  - deviceTypes: [phone, tablet]

但本机没有 DevEco / 华为账号，只能用公开的 OpenHarmony SDK 做命令行编译。
两套 schema 不一样，硬凑不进去：

  | 项目                | HarmonyOS              | OpenHarmony          |
  |---------------------|------------------------|----------------------|
  | runtimeOS           | "HarmonyOS"            | "OpenHarmony"        |
  | compatibleSdkVersion| "5.0.0(12)"（字符串）  | 12（整数）           |
  | compileSdkVersion   | 不需要                 | 必须配               |
  | backgroundModes     | 支持                   | schema 不认这个字段  |
  | deviceTypes         | phone / tablet         | default              |

所以：正本永远是 HarmonyOS（存在 *.harmony.bak），OpenHarmony 版由正本现算，
构建完再恢复。这样工程在 DevEco 里打开永远是正确的那一份。

用法
----
    python tools/switch-target.py openharmony   # 派生 OpenHarmony 版（构建前）
    python tools/switch-target.py harmony       # 从正本恢复（构建后）
    python tools/switch-target.py status        # 看当前是哪一套
"""

import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PROFILE = os.path.join(ROOT, 'build-profile.json5')
MODULE = os.path.join(ROOT, 'entry', 'src', 'main', 'module.json5')
PROFILE_BAK = PROFILE + '.harmony.bak'
MODULE_BAK = MODULE + '.harmony.bak'

# OpenHarmony 的 compatibleSdkVersion 必须是整数
SDK_VERSION = 12


def read(path):
    with open(path, 'r', encoding='utf-8') as f:
        return f.read()


def write(path, text):
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        f.write(text)


def ensure_backup(path, bak):
    """第一次运行时，把当前的 HarmonyOS 正本存下来。"""
    if not os.path.exists(bak):
        write(bak, read(path))


def to_openharmony_profile(src):
    s = src
    triple = ('"compileSdkVersion": %d,\n        "compatibleSdkVersion": %d,\n'
              '        "targetSdkVersion": %d,' % (SDK_VERSION, SDK_VERSION, SDK_VERSION))
    # 必须连着行尾逗号一起匹配，否则替换完会多出一个逗号（JSON5 直接语法错）
    if '"compatibleSdkVersion": "5.0.0(12)",' in s:
        s = s.replace('"compatibleSdkVersion": "5.0.0(12)",', triple)
    elif '"compatibleSdkVersion": "5.0.0(12)"' in s:
        s = s.replace('"compatibleSdkVersion": "5.0.0(12)"', triple)
    s = s.replace('"runtimeOS": "HarmonyOS"', '"runtimeOS": "OpenHarmony"')
    return s


def to_openharmony_module(src):
    s = src
    # 1) 去掉 backgroundModes（连同上面那两行注释）
    s = re.sub(
        r'\n(?:[ \t]*//[^\n]*\n)*[ \t]*"backgroundModes"[ \t]*:[ \t]*\[[^\]]*\][ \t]*,',
        '', s)
    # 2) deviceTypes 只能是 default
    s = re.sub(
        r'"deviceTypes"[ \t]*:[ \t]*\[[^\]]*\]',
        '"deviceTypes": [\n      "default"\n    ]', s)
    return s


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else 'status'

    if mode == 'status':
        cur = read(PROFILE)
        os_name = 'OpenHarmony' if '"runtimeOS": "OpenHarmony"' in cur else 'HarmonyOS'
        mod = read(MODULE)
        bg = '有' if 'backgroundModes' in mod else '无'
        print('当前 runtimeOS = %s，module.json5 的 backgroundModes = %s' % (os_name, bg))
        print('正本备份：%s' % ('存在' if os.path.exists(PROFILE_BAK) else '缺失'))
        return 0

    if mode == 'harmony':
        restored = []
        for path, bak in ((PROFILE, PROFILE_BAK), (MODULE, MODULE_BAK)):
            if os.path.exists(bak):
                write(path, read(bak))
                restored.append(os.path.basename(path))
        print('已恢复 HarmonyOS 正本：%s' % (', '.join(restored) if restored else '（无备份，跳过）'))
        return 0

    if mode == 'openharmony':
        ensure_backup(PROFILE, PROFILE_BAK)
        ensure_backup(MODULE, MODULE_BAK)
        # 每次都从正本重新派生，保证幂等
        write(PROFILE, to_openharmony_profile(read(PROFILE_BAK)))
        write(MODULE, to_openharmony_module(read(MODULE_BAK)))
        print('已切换到 OpenHarmony 构建配置（正本已备份为 *.harmony.bak）')
        return 0

    print('用法：switch-target.py [openharmony|harmony|status]')
    return 2


if __name__ == '__main__':
    sys.exit(main())
