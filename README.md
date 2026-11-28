# CarbitProbe（极享）

把手机画面投到摩托车仪表上的一个"假车机"。跑在手机上，对端是 **VOGE DS625X 的 7 寸 TFT 仪表**。

不是官方 App，是自己逆向协议后写出来的一套最小实现，用来让手机（小米 CarWith / CarLife 生态）以为接了一台真的车机。

## 它做什么

- **模拟 CarLife 车机端**：起 Cmd / Video / Touch / Media 四条通道（7240 / 8240 / 9340 / 9240），下发 `VIDEO_ENCODER_INIT` 让手机按指定尺寸编码，然后把 H.264 原样推给仪表。
- **模拟亿连（EasyConn）车机端**：监听 PXC 10921 / 10922、RV 数据口 10920 / 10710，应答 `RV_CONFIG_CAPTURE`，处理 `REQ_RV_DATA_NEXT` 拉帧。
- **扫码连仪表**：相机实时解仪表屏上的二维码，抠出 `ssid / pwd / modelid / sn`，自动连热点或走 Wi-Fi Direct。
- **反向触控**：仪表点屏幕 → 换算成手机坐标 → 无障碍注入或回灌 CarLife 触摸通道。
- **本机语音 / 日志上报**：语音走手机本机（不占仪表麦克风），运行日志可推到自建 HTTP 接收器（`server.py`）。

## 目录

```
src/io/carbit/probe/      App 源码（无 Gradle，纯 javac）
res/                      资源
build.sh                  手工打包脚本（aapt2 + d8 + zipalign + apksigner）
server.py                 日志接收器（Python 3，单文件，无依赖）
carbit-upload.html        网页版日志上传/查看页
carbit-version.json       在线更新用的版本描述模板
CarbitProbe说明.md         功能与操作说明
无极机车投屏逆向报告.md      协议逆向过程与结论
VOGE投屏协议-iOS移植说明.md  同一套协议在 iOS 上怎么接
```

## 构建

前置：JDK 17+、Android build-tools、`android.jar`（API 34）、`zxing.jar`（二维码）。

```bash
export JDK_HOME=/path/to/jdk
export BUILD_TOOLS=/path/to/build-tools/34.0.0
export ANDROID_JAR=/path/to/platforms/android-34/android.jar
export ZXING_JAR=/path/to/zxing.jar
# 用自己的 release keystore，别名与密码按自己的填
export KS=./keystore.jks KS_PASS='***' KS_ALIAS=carbit
./build.sh
```

产物是 `CarbitProbe.apk`，没有 Gradle、没有 AndroidX，全部手写 View。

## 最新版本：8.34（vc 172）

- `KeepAliveService` 持 WifiLock（`WIFI_MODE_FULL_LOW_LATENCY`，失败退 `HIGH_PERF`）+ `PARTIAL_WAKE_LOCK`
- 灭屏后每 5 秒打一行**熄屏观测**：取流字节/帧数、队列与已推、网卡与 IPv4、省电白名单状态
- 实测同一 Wi-Fi 灭屏 56 秒不中断：CarWith 取流 520 → 2175 帧（≈29.5fps），已推 178 → 2166，全程无重连
- 系统省电策略仍可能单独掐掉，需把应用加入「不受限制」白名单

## 脱敏说明

这是脱敏后的公开副本，下面这些在原工程里是真实值，这里一律换成了占位符，跑起来必须自己填：

| 位置 | 占位符 |
|---|---|
| NAS 外网地址 | `YOUR-NAS-HOST` |
| NAS 内网地址 | `192.168.1.10` |
| 日志上报令牌 | `YOUR_TOKEN_HERE` |
| 仪表热点名 / 密码 | `VOGE-XXXX` / `00000000` |
| 仪表 SN | `EXAMPLE-SN` |
| 仪表 UUID | `CARBIT-EXAMPLE-UUID` |

签名密钥（`.jks`）与 NAS 部署脚本不是本仓库内容。`dist/` 里放了一份由本仓库源码编译的 8.34 可执行包，与自用版同包名同签名，装一个会覆盖另一个。

## 免责

仅用于自己设备上的互操作研究。CarLife、CarWith、EasyConn、高德等名称与协议归各自权利人所有。代码按 MIT 协议开源，按原样提供，出问题自负。
