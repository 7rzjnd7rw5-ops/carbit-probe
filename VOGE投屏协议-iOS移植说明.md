# VOGE DS625X 仪表投屏协议 · iOS（CarWithin）移植说明

> 从 Android 端 `Rig.java`（App「极享 · 车机投屏」，已在实车跑通）提取。
> 目标：在 iPhone 上跑 CarWithin，把 CarPlay 画面按这套协议投到 DS625X 的 7 寸 TFT 仪表。
> 仪表侧身份：无极 VOGE，亿连 EasyConn / PXC + RV 投屏（Carbit 同族）。

---

## 0. 先决条件（这条决定能不能做）

**iOS 没有 Wi-Fi Direct。** Android 端是把手机当 P2P 组员、靠 `WifiP2pManager` 加入 `VOGE-XXXX` 这个组（组主 `192.168.13.1`、手机 `192.168.13.20`）。

iPhone 唯一能走的等价路径是：**仪表同时广播一个普通 Wi-Fi 热点**，iPhone 用 `NEHotspotConfiguration` 连它。

所以第一步先做验证，不通过就别往下写代码：

1. iPhone 打开「设置 → 无线局域网」，看列表里有没有 `VOGE-XXXX`；
2. 有 → 用密码 `00000000` 连上，记下 iPhone 分到的 IP（`设置 → 无线局域网 → ⓘ`）；
3. 用任意 TCP 工具（App Store 的 iNetTools 之类）对 `<仪表IP>:10921` 做一次连接，能连上就说明这条路通。

- 能连上 → 直接照本文写 `VogeOutput.swift`；
- 连不上 / 列表里根本没有 → 仪表只做 P2P 不做 AP，**iPhone 这条路走不通**，只能回头继续 Android。

---

## 1. 端口与角色

| 端口 | 方向 | 用途 |
|---|---|---|
| 10922 | 仪表 → 手机 | PXC 主控（握手、授权、能力交换、心跳、对时、MIRROR_START） |
| 10921 | 仪表 → 手机 | RV 投屏控制（拉流配置、心跳、开流/停流） |
| 10920 | 仪表 → 手机 | RV 投屏数据（每帧一问一答） |
| 10930 | 手机 → 仪表 | 唤醒/mDNS 辅助（可选，不通也不影响） |
| 10710/10711 | 双向 | 老式 RV 通道（本仪表未使用） |

角色：**手机 = 车机端**。手机先开监听，仪表授权通过后主动连回来（三个口都是仪表发起的）。我们只负责应答与推帧。

---

## 2. PXC 主控（10922）——握手

帧格式（16 字节头，全部小端）：

```
u32 cmdType
u32 totalLength     // = 16 + body
u32 checkWord       // = cmdType ^ totalLength
u8[4] reserved      // 全 0
body[totalLength-16]
```

### 2.1 必答序列

| 仪表发来 | 手机回 | 说明 |
|---|---|---|
| `0x00010000` CONNECT | `0x00010001`，body 空 | 建立会话 |
| `0x00010010` 车机能力 JSON | `0x00010011` + 手机信息 JSON | **关键**：不回这条，仪表只握手不投屏 |
| `0x00030010` AUTH_HUID | `0x00030011`，body 空 | 仪表用我们的公钥加密数据，我们用私钥解；解不开就吃 AUTH_FAIL |
| `0x00030020` MIRROR_START | 不回 | 仪表宣告要投屏了（body: u32 width, u32 controlType） |
| `0x00040000` | 不回 | 仪表开 RV 通道 |
| `0x00020000` / `0x00030000` / `0x00033000` / `0x00034000` / `0x00080000` | `cmd+1`，body 空 | 反向控制通道建立；`0x20000` 之后再补两条下行（见 2.3） |
| `0x000103E0` CHECK_SN | `0x000201C0` 授权通过 JSON，紧接 `0x00020480 {"page":1}` | 见 2.4 |
| `0x00010450` QUERY_TIME | `0x00010451` 对时 JSON | 见 2.5 |
| 奇数 cmd | **一律不回** | 仪表的 ACK 是奇数，回 ACK 等于协议错 |
| `0x8xxxxxxx` | **一律不回** | 错误码族（`0x80000010` = AUTH_FAIL） |

### 2.2 手机信息 JSON（`0x10011` body）

```json
{"pxcVersion":"1.0.2",
 "phoneUUID":"869274050000000",
 "phoneBrand":"Xiaomi","phoneModel":"24122RKC7C",
 "phoneOsVersion":34,"phoneOs":"Android",
 "package":"com.caigetuxun.app.gugu",
 "versionCode":50,"token":0,
 "pubkey":"<RSA-2048 公钥，Base64 DER>",
 "encryptedHUID":"<用自己私钥 RSA/ECB/PKCS1Padding 加密 CARBIT-EXAMPLE-UUID 后的 Base64>",
 "bluetoothName":"",
 "supportH264IFrame":true,
 "supportFunction":128,
 "appVersionFingerPrint":"V:1.3.4(50)-voge-release",
 "haveSysScreenInSetting":true}
```

- `package` 必须是咕咕行那个 `com.caigetuxun.app.gugu`；`com.jczy.voge` 会直接吃 `AUTH_FAIL(0x80000010)`（实测）；
- `encryptedHUID` 用的是**私钥加密**（不是签名），仪表用我们给的公钥解密比对 `CARBIT-EXAMPLE-UUID`；
- 密钥对必须**持久化**（Android 存 SharedPreferences；iOS 存 Keychain）。每次启动换钥匙，仪表会当成陌生人。

### 2.3 `0x20000` 之后的下行

```
0x00020020  {"displayRotation":1,"width":1280,"height":720,
             "enableAccessibility":false,"enableAOAHid":false,"mode":1}
```
每 3 秒重发一次（仪表靠这个刷“投屏中”状态）。
`0x00020590 MIRROR_CONTROL` 只在仪表 `supportFunction` 含 `0x40000000` 时才发——本仪表是 `128`，**不要发**。

### 2.4 CHECK_SN 应答

仪表 body：`{"sn":"EXAMPLE-DEVICE-SN", "client_set":"?"}`

手机回 `0x201C0`：
```json
{"isOk":true,"errCode":0,"errMsg":"","id":"<sn>","client_set":"<原样，? 替换成空>"}
```
紧接着发 `0x20480 {"page":1}`（JUMP_TO_CAR_PAGE）——仪表不回 ACK 也照发，不发就一直挂在车机页不动。

### 2.5 对时应答（`0x10451`）

```json
{"time":<nowMs+时区偏移>,"currentTime":<nowMs>,
 "currentTimeZone":"Asia/Shanghai","dateTime":"dd.MM.yyyy HH:mm:ss:SSS"}
```

### 2.6 心跳

- 手机每 4 秒发 `0x70000000`（空 body）保活（拿到能力 JSON 之后才开始发，握手前多发会被掐）；
- 仪表侧若 15 秒无心跳会判死并整会话重连。

---

## 3. RV 投屏控制（10921）

帧格式与前文**不同**，是 8 字节小端头：

```
u16 cmdType
u16 cmdLength
u8[4] token     // 固定 0
body[cmdLength]
```

| 仪表发来 | 手机回 | 说明 |
|---|---|---|
| `0x0040` REQ_HEARTBEAT | `0x0041`，body 空 | 心跳，必须答 |
| `0x0070` REQ_RV_DATA_START | `0x0071`，body 空 | 仪表要开始拉流，答完等它连 10920 |
| `0x0010` REQ_RV_CONFIG_CAPTURE（212B） | `0x0011` RLY_RV_CONFIG_CAPTURE | 见下 |

`0x0010` body 关键字段：

| 偏移 | 类型 | 值（实测） |
|---|---|---|
| 0 | u16 | deviceWidth = 1024 |
| 2 | u16 | deviceHeight = 608 |
| 4 | u32 | fps hint = 0（CarWith 实际按 30 出流） |
| 8 | u32 | wantEncoder = 2（H264） |
| 20 | u32 | bitrate = 4194304 |
| 26 | u8 | touchMode = 2 |
| 27 | u8 | orientation = 1 |

`0x0011` 应答（8B ReqBase + 8B RlyConfigCapture）：

```
u16 0x0011 | u16 length=8 | u32 token=0
i32 encoder=2 | u16 width(capture) | u16 height(capture)     // w、h 都按 16 向下取整
```

---

## 4. RV 投屏数据（10920）——最关键的一条

仪表发 `0x0072`（REQ_RV_DATA_NEXT）**要一帧**，手机必须立刻回答**一帧 H264**：

```
u32 frameLength (小端)
H264 Annex-B 裸流（每帧开头 00 00 00 01 或 00 00 01）
```

规则（全部是实车踩出来的，违反就黑屏/断流）：

1. **一问一答，不能空回**。空队列时不回 = 仪表停拉 2 秒后整会话重连（已验证两次）；
2. 队列空时**原样重发上一帧**保活；
3. 只在**关键帧**前面补 SPS/PPS（只抽 nal type 7 / 8 两个 NAL），普通 P 帧补了反而让仪表解码器重新初始化；
4. **绝不能**在流中间插“投屏准备中”这种非连续帧 —— 仪表解码器会直接停摆；
5. 首帧要带 SPS/PPS 的 IDR，否则解码器创建失败；
6. 推帧之间不要人为丢 P 帧（参考链断裂 = 花屏）；
7. 队列建议 60 帧上限，满了丢最老的 P 帧、保住关键帧。

---

## 5. 触控与输入（iOS 侧的差别）

Android 端的触控是：手机 App 里的预览框 → CarLife Touch 通道（`TOUCH_DOWN=425986 / UP=425987 / MOVE=425988`）→ CarWith 自己的窗口。
换成 CarWithin 后，画面源是 CarPlay，触控要走 CarWithin 自己的 CarPlay 触控注入（它的 `CarPlayControls` / HID 那条路），**不能照搬 CarLife 那套**。

仪表本身 `supportScreenTouch=false`（没有触摸屏），所以触控只发生在 iPhone 上，这点反而更简单。

---

## 6. 移植落点（CarWithin 代码结构）

参照它已有的三个输出模块（`ZeehoOutput.swift`、`NinebotOutput.swift`、`TailingOutput.swift`）：

```
CarWithin/App/VogeOutput.swift        // 新增：PXC 握手 + 10921/10920 服务 + H264 推帧
```

最小实现骨架（按它的既有约定）：

```swift
final class VogeOutput {
    var onLog: ((String) -> Void)?
    var onState: ((String) -> Void)?
    var onKeyframeRequest: (() -> Void)?     // 仪表要新 IDR 时回调编码器
    func start(localAddress: String, targetAddress: String)
    func append(_ data: Data, codec: VideoCodec)   // 编码好的 H264（Annex-B）喂进来
    func stop()
}
```

- `append` 进来的帧进一个环形队列（60），`0x0072` 到了就从队列取一帧写 `4B 小端长度 + 帧`；
- 队列空 → 重发上一份负载（这个“上一份”要包含补过 SPS/PPS 之后的完整负载）；
- 关键帧时，若手上存着 SPS/PPS 且该帧本身没带，就把 SPS/PPS 前缀上去再存为 `lastPayload`；
- `onKeyframeRequest`：仪表停拉超过 200ms，或首帧迟迟不来时，让 CarPlay 编码器强制出一个 IDR；
- 需要 `Network.framework` 的 `NWListener`（三个口 10922/10921/10920），或沿用项目里已有的 POSIX socket 封装。

接入点：`ReceiverViewController.swift` 的 `headUnitOutput` 选项里加一项「无极 DS625X」，
并在 `outputMode` 分支里实例化 `VogeOutput`，把编码后的 H264 走 `append` 而不是浏览器/CarLife 输出。

---

## 7. 已知坑（照抄会踩）

| 现象 | 原因 | 处理 |
|---|---|---|
| `AUTH_FAIL 0x80000010` | package 用了 `com.jczy.voge`；或 pubkey/encryptedHUID 空、密钥每次变 | 用 `com.caigetuxun.app.gugu`；密钥持久化 |
| 握手通过但一直不投 | 没回 `0x10011`，或没发 `0x20480 JUMP_TO_CAR_PAGE` | 两条都补齐 |
| 投一会断、仪表重连 | `0x0072` 空回 | 空队列重发上一帧 |
| 花屏 / 闪 | P 帧断链、或关键帧补了整帧 | 只补 SPS/PPS 两个 NAL，别丢 P 帧 |
| 解码器停摆 | 流中间插占位帧 | 不要插 |
| 心跳被判死 | 只在握手后发 `0x70000000`，4 秒一次 | 握手前一个字节都别多发 |

---

## 8. 交付顺序建议

1. 先做 §0 的 AP 连通性验证（5 分钟，决定这条路线成立与否）；
2. 通了就先手写一个 macOS 命令行小程序（同协议）连实车验证握手与推帧，再搬进 iOS App —— iOS 上调试 socket 与 App 生命周期（后台/锁屏）要麻烦得多；
3. 最后接 CarWithin 的 H264 输出与 CarPlay 触控。
