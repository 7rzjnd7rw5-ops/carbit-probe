#!/bin/bash
# Carbit 探测器 构建脚本（无 Gradle，直接用 build-tools 手工打包）
# 依赖: JDK (javac) + build-tools(aapt2/d8/zipalign/apksigner) + android.jar
set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"
JDK="${JDK_HOME:?请先 export JDK_HOME（JDK 根目录）}"
export PATH="$JDK/bin:$PATH"
BT="${BUILD_TOOLS:?请先 export BUILD_TOOLS（build-tools 目录）}"
ANDROID_JAR="${ANDROID_JAR:?请先 export ANDROID_JAR（android.jar）}"
ZXING_JAR="${ZXING_JAR:?请先 export ZXING_JAR（zxing.jar）}"
OUT="$ROOT/build"

rm -rf "$OUT"
mkdir -p "$OUT/res" "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "== 1/6 资源编译 =="
"$BT/aapt2" compile --dir "$ROOT/res" -o "$OUT/res/resources.zip"

echo "== 2/6 资源链接 =="
"$BT/aapt2" link \
  -o "$OUT/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$ROOT/AndroidManifest.xml" \
  --java "$OUT/gen" \
  --auto-add-overlay \
  "$OUT/res/resources.zip"

echo "== 3/6 javac =="
find "$ROOT/src" -name '*.java' > "$OUT/sources.txt"
"$JDK/bin/javac" -encoding UTF-8 -source 8 -target 8 -nowarn \
  -bootclasspath "$ANDROID_JAR" -classpath "$ZXING_JAR" \
  -d "$OUT/classes" @"$OUT/sources.txt"

echo "== 4/6 dex =="
java -cp "${R8_JAR:-/tmp/cpbuild/r8.jar}" com.android.tools.r8.D8 --min-api 26 --lib "$ANDROID_JAR" --release \
  --output "$OUT/dex" $(find "$OUT/classes" -name '*.class') "$ZXING_JAR"

echo "== 5/6 合入 dex =="
cd "$OUT" && cp base.apk unsigned.apk
zip -q -j unsigned.apk dex/classes.dex

echo "== 6/6 对齐 + 签名 =="
"$BT/zipalign" -f -p 4 unsigned.apk aligned.apk
# 用自己的 keystore 签名：导出 KS / KS_PASS / KS_ALIAS 后再跑本脚本
KS="${KS:-$ROOT/keystore.jks}"
case "$KS" in /*) ;; *) KS="$ROOT/$KS" ;; esac   # 相对路径一律按工程根解析（这时的 cwd 是 $OUT）
KS_PASS="${KS_PASS:?请设置 KS_PASS（keystore 密码）}"
KS_ALIAS="${KS_ALIAS:-carbit}"
if [ ! -f "$KS" ]; then
  echo "找不到 keystore：$KS 。先 keytool -genkeypair 生成一个（release 签名要用自己的，"
  echo "并把它对应的 SHA1 填到高德/第三方后台，否则校验不过）"
  exit 1
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --ks-key-alias "$KS_ALIAS" --out "$ROOT/CarbitProbe.apk" aligned.apk

echo
echo "OK -> $ROOT/CarbitProbe.apk"
ls -lh "$ROOT/CarbitProbe.apk"
