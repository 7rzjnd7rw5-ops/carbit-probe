# Carbit 探测器（CarbitProbe）

自己当客户端，抓无极 DS625X 仪表在 Carbit/EasyConn 链路上的真实字节。
无 root 环境下的唯一可行抓包方式 —— 因为 Carbit 走 Wi‑Fi Direct，VpnService 类工具抓不到 P2P 链路的流量。

- 包名：`io.carbit.probe`
- 版本：1.0（debuggable；默认走 DDNS 域名、失败回退内网；新增“一键探测”；版本号改为从包读取）
- 签名：自签 `CN=CarbitProbe, O=DIY, C=CN`
- 权限：`INTERNET`、`ACCESS_NETWORK_STATE`、`ACCESS_WIFI_STATE`（都是普通权限，装完不需要点授权）
- 大小：252 KB（含 ZXing core；推送走 HTTP，不再依赖 commons-net）

## 内置的实车信息（从仪表屏二维码解出）

App 里已经硬编码了这套目标车的参数，装完不用再翻资料：

| 项 | 值 | 来源 |
|---|---|---|
| 热点 SSID | `VOGE-XXXX` | 二维码 `&ssid=` |
| 热点密码 | `00000000` | 二维码 `&pwd=` |
| 认证方式 | `WPA-PSK` | 二维码 `&auth=` |
| modelid | `45103` | 二维码 `&modelid=` |
| sn | `EXAMPLE-SN` | 二维码 `&sn=` |
| UUID | `CARBIT-EXAMPLE-UUID` | 仪表屏显示 |

二维码原始内容（宿主是 Carbit SDK 的下载页，`action=9`）：

```
http://www.carbit.com.cn/downsdk/664/665/_sdk?modelid=45103&sn=EXAMPLE-SN&action=9&ssid=VOGE-XXXX&pwd=00000000&auth=WPA-PSK&name=VOGE-XXXX
```

结论：**这块 DS625X 仪表自己开热点 `VOGE-XXXX`**，手机直接连即可，不需要官方 App 参与。
退出该热点的唯一代价是手机断网（正常现象，别开“自动切换移动数据”）。

## 界面上的十三个按钮

| 按钮 | 做什么 | 能回答什么问题 |
|---|---|---|
| 扫描二维码 | 相机取景实时解码，或从相册选一张图 | 自动读出 ssid / pwd / modelid / sn |
| 刷新网络 | 列出本机各网卡 IPv4 与网关，自动填进 IP 框 | 手机到底有没有接上仪表的网络 |
| 扫描端口 | 探 `10920` `10921` `10922` `10930` | 仪表在不在、哪些端口开着 |
| 发唤醒探针 | 依次用 5 个包名向 `10930` 发 81 字节探针，等应答 | **仪表认哪个身份** |
| 被动监听 | 同时监听 `10920–10922`，等仪表反向连入，逐帧记录 | 握手走到哪一步、发了什么 |
| 停止监听 | 关掉全部监听 socket | —— |
| 提取旧日志 | 扫描本 App 私有目录里所有 `carbit-*.txt`，全部复制到 `下载/CarbitProbe/` 并分享最新一份 | 升级安装后把以前那份捞出来 |
| 推送日志到NAS | HTTP POST 到 NAS 上的接收服务，落进 `/Download/CarbitProbe/logs/` | 内网/外网都能推 |
| NAS 设置 | 填服务地址与 token，存本地 | 只需填一次 |
| 清屏 | 清掉界面上的日志（不影响已导出文件） | —— |
| 导出日志 | 写进 `下载/CarbitProbe/` 并自动弹分享面板 | 手机文件管理器直接能看到，也能一键发出去 |
| 复制热点密码 | 把 `00000000` 塞进剪贴板 | 手动连 Wi‑Fi 时省得输错 |
| 去连热点 | 直接跳系统 Wi‑Fi 设置页 | —— |

## 探针身份顺序（可改 `Proto.java` 的 `IDENTITIES`）

1. `net.easyconn.easyride.wws` — Carbit Ride。**无极仪表在 CLIENT_INFO 里把它报为 flavor 51**，是实测被应答过的那个
2. `com.jczy.voge` — 无极机车 App 国内包名（v1.3.4）
3. `com.cfmoto.cfmotointernational` — 春风，MOTO-HUB 实测能用的那个
4. `net.easyconn.carman`
5. `com.carbit.easyconnect`

## 报文格式（照 MOTO-HUB 源码实现，AGPL-3.0）

唤醒探针 = 16 字节小端头 + JSON 体：

```
offset 0..3   CMD      = 0x70000010 (CMD_MDNS_RESPOND)
offset 4..7   totalLen = 16 + body.size
offset 8..11  CMD xor totalLen
offset 12..15 0
body = {"phoneType":"Android","packageName":"<身份>"}
```

`net.easyconn.easyride.wws` 时 body 65 字节，整帧 81 字节 —— 与车主抓包一致。
应答校验：`cmd=0x70000011`、`(cmd XOR totalLen) == magic`、body 含 `true`。

PXC 串行帧（`10920–10922` 上可能出现）：

```
0x24 | CMD | LEN | payload… | XOR | 0x0A      LEN = payload.size + 4
XOR = XOR(0x24, CMD, LEN, payload…)
```

## 推日志到 NAS

NAS 上跑了一个 Docker 接收器（容器名 `carbit-receiver`，镜像 `python:3.12-alpine`），
再用现有 nginx（`nas-homepage`，宿主 8088）反代到 `/carbit/`，所以外网也能推。

| 项 | 值 |
|---|---|
| 容器 | `carbit-receiver`，宿主监听 `8899` |
| 反代 | `http://<NAS>:8088/carbit/` → `http://192.168.1.10:8899/` |
| 落盘目录 | `/share/Download/CarbitProbe/logs/` |
| 外网地址（默认，优先） | `http://YOUR-NAS-HOST:8088/carbit` |
| 内网地址（回退） | `http://192.168.1.10:8088/carbit` |
| 令牌 | `YOUR_TOKEN_HERE`（可在“NAS 设置”里改） |

接口：

```
POST /carbit/upload?name=xxx.txt&token=XXX    body 即日志内容
GET  /carbit/list?token=XXX                   JSON 列表
GET  /carbit/health                           健康检查
```

**前提：手机得能路由到 NAS。** 连着仪表热点 `VOGE-XXXX` 时手机是断网的，所以顺序是
“探测完 → 切回家里 Wi-Fi（或直接用外网地址）→ 点推送”。

## 日志保存在哪

两处同时写：

| 位置 | 能否在手机文件管理器看到 | 用途 |
|---|---|---|
| `/sdcard/Download/CarbitProbe/carbit-<时间>.txt` | **能**（“文件管理 → 下载”） | 日常取日志 |
| `/sdcard/Android/data/io.carbit.probe/files/` | 不能（Android 11+ 隐藏） | `adb pull` 兜底 |

点“导出日志”后会自动弹出系统分享面板，可以直接把 txt 发到微信/文件传输助手。

旧的 Android/data 路径想手动取，电脑上跑：

```
adb pull /storage/emulated/0/Android/data/io.carbit.probe/files/
```

## 扫码是怎么实现的

- 相机：Camera2 `YUV_420_888`，取 Y 平面做灰度，`PlanarYUVLuminanceSource` 直接喂给 ZXing，**不转 Bitmap、不联网**
- 相册：`RGBLuminanceSource` 路径，同一套解码器
- 识别到 URL 后用 `Uri.getQueryParameter` 抠 `ssid / pwd / modelid / sn`，把密码直接塞进剪贴板

## 使用顺序

1. 先让手机接上仪表的网络（Wi‑Fi Direct 组网或仪表热点），**不起网就没有后面的一切**
2. 刷新网络 → 看是否拿到非 `192.168.1.x` 的地址（仪表网段通常 192.168.49.x 或 192.168.1.x）
3. 扫描端口 → 确认 `10930` 是不是开着
4. 发唤醒探针 → 看哪个身份被应答
5. 被应答后，点被动监听，然后在仪表上按按键进入投屏/扫码流程
6. 导出日志，把 txt 发回来

## 重新构建

```bash
./build.sh          # 需要 JDK、build-tools、android.jar，路径在脚本顶部
```

构建链：`aapt2 compile/link` → `javac` → `D8`（r8 9.5.33，8.2.2 在 JDK 21 上会 NPE）→ `zipalign` → `apksigner`。
