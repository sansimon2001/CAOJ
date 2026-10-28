#!/usr/bin/env bash
# 草记 · 命令行构建（OpenHarmony SDK）
#
# 用途：本机没有 DevEco Studio / 华为开发者账号时，也能真正编译一次，
#       用 ArkTS 编译器把类型错误、SDK API 误用全部揪出来。
#       产出的 hap 是 unsigned，装在真机上还需要签名。
#
# 用法：
#   tools/ohos-build.sh                # 构建 debug hap（默认 assembleHap）
#   tools/ohos-build.sh clean          # 清构建缓存
#   tools/ohos-build.sh assembleApp    # 打 App 包
#
# 构建结束后工程会自动恢复成 HarmonyOS 配置，DevEco 打开仍然是正确的那一份。

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# ---------- 工具链位置 ----------
# 默认按本机装好的路径走；换机器时用环境变量覆盖即可，不必改脚本：
#   CAOJI_JDK=C:/path/to/jdk17  CAOJI_OHOS_SDK=C:/path/to/ohos-sdk  tools/ohos-build.sh
JDK_WIN="${CAOJI_JDK:-C:/Users/simon/.workbuddy/binaries/jdk17/jdk-17.0.20.1+1}"
OHOS_WIN="${CAOJI_OHOS_SDK:-C:/Users/simon/ohos-sdk}"

# C:/foo → /c/foo，PATH 与 -x 判断都只认 Unix 形式
to_unix() { printf '%s' "$1" | sed -E 's|^([A-Za-z]):|/\L\1|'; }
JDK_UNIX="$(to_unix "$JDK_WIN")"
OHOS_HOME="$(to_unix "$OHOS_WIN")"
SDK_WIN="$OHOS_WIN/Sdk"
HVIGOR_ENV="$OHOS_WIN/hvigor-env"
HVIGOR="$HVIGOR_ENV/node_modules/@ohos/hvigor/bin/hvigor.js"

# ---------- 前置检查 ----------
fail() { echo "✗ $1" >&2; exit 1; }

[ -f "$HVIGOR" ] || fail "找不到 hvigor：$HVIGOR（重新执行 npm install @ohos/hvigor@5.19.8 那一步）"
[ -x "$JDK_UNIX/bin/java" ] || fail "找不到 JDK 17：$JDK_UNIX"
[ -d "$OHOS_HOME/Sdk/12/ets" ] || fail "找不到 OpenHarmony SDK：$OHOS_HOME/Sdk/12/ets"

# JAVA_HOME 给 node 子进程用（Windows 风格），PATH 给 bash 用（Unix 风格）
export JAVA_HOME="$JDK_WIN"
export PATH="$JDK_UNIX/bin:$PATH"
export NODE_PATH="$HVIGOR_ENV/node_modules"
export DEVECO_SDK_HOME="$SDK_WIN"

# ---------- 切到 OpenHarmony 配置，退出时无条件恢复 ----------
cd "$ROOT" || fail "进不去工程目录"

# local.properties 存的是本机 SDK 路径，不入库（见 .gitignore）。
# 缺了就按本次实际用的 SDK 位置补一份，否则 hvigor 直接报 00303208 找不到 sdk.dir。
LP="$ROOT/local.properties"
if [ ! -f "$LP" ] || ! grep -qE '^[[:space:]]*sdk\.dir=' "$LP"; then
  printf '# 由 tools/ohos-build.sh 自动生成，不入库\nsdk.dir=%s\n' "$SDK_WIN" > "$LP"
  echo "· 已补 local.properties → sdk.dir=$SDK_WIN"
fi

python "$ROOT/tools/switch-target.py" openharmony || fail "切换构建配置失败"
trap 'python "$ROOT/tools/switch-target.py" harmony >/dev/null 2>&1' EXIT INT TERM

TASK="${1:-assembleHap}"
echo "▶ hvigor $TASK"
node "$HVIGOR" "$TASK" --no-daemon
CODE=$?

if [ $CODE -eq 0 ]; then
  HAP="$ROOT/entry/build/default/outputs/default/entry-default-unsigned.hap"
  if [ -f "$HAP" ]; then
    echo ""
    echo "✓ 构建成功"
    echo "  产物：$HAP"
    echo "  大小：$(stat -c%s "$HAP") 字节"
  fi
else
  echo ""
  echo "✗ 构建失败（exit $CODE），上面是 ArkTS 编译器报的错"
fi

exit $CODE
