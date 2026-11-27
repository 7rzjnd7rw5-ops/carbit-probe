package io.carbit.probe;

/**
 * Carbit / EasyConn 协议常量与报文构造。
 * 依据：MOTO-HUB（AGPL-3.0）tbox 层 EasyConnClientIdentity.kt / RideDaemonTransport.kt / EcBtpProtocol.kt。
 */
public final class Proto {

    /** 唤醒探针端口（mDNS respond）。 */
    public static final int WAKE_PORT = 10930;

    /** PXC 控制 / 媒体端口（官方配套 App 作为服务端占用这三个反连端口）。 */
    public static final int[] PXC_PORTS = {10920, 10921, 10922};

    /** RV（反向视频）通道：10922 握手成功后仪表才走这两个口，10711 控制、10710 数据。
     *  方向未定，故既监听也反向探测。 */
    public static final int[] RV_PORTS = {10711, 10710};

    /** CarWith（ICCOA）在 adb 模式下自开的服务端端口。 */
    public static final int ICCOA_PORT = 10113;

    /** 16 字节小端头：cmd(4) + totalLen(4) + (cmd xor totalLen)(4) + reserved(4)。 */
    public static final int WAKE_HEADER_SIZE = 16;

    public static final int CMD_MDNS_RESPOND = 0x70000010;
    public static final int CMD_MDNS_RESPOND_ACK = 0x70000011;

    /** PXC 串行帧：0x24 | CMD | LEN | payload… | XOR | 0x0A，LEN = payload.size + 4。 */
    public static final byte PXC_START = 0x24;
    public static final byte PXC_END = 0x0A;

    /**
     * 身份候选。顺序按"无极相关度"排：Carbit Ride 被实测证明能唤醒 Voge 仪表
     * （该仪表在 CLIENT_INFO 里把它报为 flavor 51），其次是无极机车 App 国内包名。
     *
     * 补充（v1.2，来自无极机车 1.3.4 反编译）：无极是春风系，自然车仪表若跑春风固件，
     * 其白名单里认的是 CFMOTO 系列的包名（见 FLAVOR_TABLE），所以春风包名排到第二位。
     */
    public static final String[] IDENTITIES = {
            "com.jczy.voge",             // v4.7：官方无极机车 APP（用户确认仍用它，walk service 端验 flavor）
            "net.easyconn.easyride.wws",
            "com.cfmoto.cfmotointernational",
            "com.lxt.cfmoto",
            "net.easyconn.carman",
            "com.carbit.easyconnect"
    };

    /** mDNS 服务类型，与官方 SDK 的 JmDNS 监听完全一致。 */
    public static final String MDNS_SERVICE = "_EasyConn._tcp.local.";

    // ---- PXC 连接建立握手（PXCService.handleConnect 反编译得出）----

    /** 仪表连入后发的第一条：cmdType = 0x10000，16 字节头，无体。 */
    public static final int CMD_CONNECT = 0x00010000;
    /** 手机必须回的应答：cmdType + 1。不回则仪表 20 秒后 abort。 */
    public static final int CMD_CONNECT_ACK = 0x00010001;
    /** 反向控制通道（仪表连入的另一类）。 */
    public static final int CMD_REVERSE_CTRL = 0x00020000;

    /** EC 帧头 16 字节：cmd(4) + totalLen(4) + (cmd^totalLen)(4) + reserved(4)，全小端。 */
    public static byte[] ecHeader(int cmd, int payloadLen) {
        int total = WAKE_HEADER_SIZE + payloadLen;
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(WAKE_HEADER_SIZE)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.putInt(0, cmd);
        b.putInt(4, total);
        b.putInt(8, cmd ^ total);
        b.putInt(12, 0);
        return b.array();
    }

    /** 16 字节头的语义名，日志里直接给人看。 */
    public static String cmdName(int cmd) {
        switch (cmd) {
            case CMD_CONNECT: return "CONNECT";
            case CMD_CONNECT_ACK: return "CONNECT_ACK";
            case CMD_REVERSE_CTRL: return "REVERSE_CTRL";
            case CMD_MDNS_RESPOND: return "MDNS_RESPOND";
            case CMD_MDNS_RESPOND_ACK: return "MDNS_RESPOND_ACK";
            case 16: return "RV_CONFIG_CAPTURE";
            case 17: return "RV_CONFIG_CAPTURE_RLY";
            case 32: return "SCREEN_EVENT";
            case 48: return "GET_VERSION";
            case 49: return "GET_VERSION_RLY";
            case 64: return "HEARTBEAT";
            case 80: return "OPERATE_SCREEN";
            case 96: return "CONFIGCAPTUREREXTEND";
            case 112: return "RV_DATA_START";
            case 114: return "RV_DATA_NEXT";
            case 128: return "START_H264";
            case 130: return "H264_DATA";
            case 144: return "START_MP4";
            case 146: return "MP4_DATA";
            case 768: return "DISPLAY_SIZE";
            case 32515: return "RLY_UN_AUTH";
            default: return "?";
        }
    }

    /**
     * flavor → 允许的包名，逐条抄自 ECP_C2P_CLIENT_INFO.checkFlavor() 与
     * ProjectConfigUtil.requestConfig()（春风/无极相关部分）。
     *
     * 关键结论：无极（VOGE）隶属春风，仪表侧对应的 flavor 只会是 65536(0x10000)、
     * 65540(0x10004) 或 65585；其中 65540 同时放行 com.cfmoto.cfmotointernational
     * 与 cfmoto.ridesync，是三组里最宽的一个。
     */
    public static final int FLAVOR_CFMOTO = 65536;
    public static final int FLAVOR_CFMOTO_WWS = 65540;
    public static final int FLAVOR_CFMOTO_EXT = 65585;

    public static final String[][] FLAVOR_TABLE = {
            {"65536", "com.lxt.cfmoto"},
            {"65536", "com.lxt.cfmoto.dev"},
            {"65537", "com.cfmoto"},
            {"65537", "com.cfmoto.dev"},
            {"65540", "com.cfmoto.cfmotointernational"},
            {"65540", "cfmoto.ridesync"},
            {"65540", "com.cfmoto.zeeho"},
            {"65585", "com.cfmoto.cfmotointernational"},
            {"65552", "com.jczy.cyclone"},
            {"65552", "com.jczy.senlan"},
    };

    // ---- 从仪表屏二维码解出的实车信息（VOGE DS625X，UUID CARBIT-EXAMPLE-UUID）----

    /** 仪表自建热点 SSID。 */
    public static final String DASH_SSID = "VOGE-XXXX";
    /** 该热点密码（WPA-PSK）。 */
    public static final String DASH_PWD = "00000000";
    /** 二维码里的 modelid，Carbit 用它区分仪表型号。 */
    public static final String DASH_MODEL_ID = "45103";
    /** 二维码里的 sn。 */
    public static final String DASH_SN = "EXAMPLE-SN";
    /** 仪表屏显示的 UUID。 */
    public static final String DASH_UUID = "CARBIT-EXAMPLE-UUID";

    private Proto() {
    }

    /** 探针 JSON 体。Carbit Ride 身份时恰好 65 字节，加 16 字节头共 81 字节。 */
    public static String probeBody(String identity) {
        return "{\"phoneType\":\"Android\",\"packageName\":\"" + identity + "\"}";
    }

    /** 构造 16 字节小端头 + JSON 体的完整探针帧。 */
    public static byte[] wakeFrame(String identity) {
        String body = probeBody(identity);
        byte[] payload = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int totalLen = WAKE_HEADER_SIZE + payload.length;
        java.nio.ByteBuffer header = java.nio.ByteBuffer.allocate(WAKE_HEADER_SIZE)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        header.putInt(0, CMD_MDNS_RESPOND);
        header.putInt(4, totalLen);
        header.putInt(8, CMD_MDNS_RESPOND ^ totalLen);
        // 最后 4 字节保持 0（reserved）
        byte[] frame = new byte[totalLen];
        System.arraycopy(header.array(), 0, frame, 0, WAKE_HEADER_SIZE);
        System.arraycopy(payload, 0, frame, WAKE_HEADER_SIZE, payload.length);
        return frame;
    }

    /** 校验并解析一条唤醒应答；返回应答 payload，非应答返回 null。 */
    public static String parseWakeAck(byte[] header, byte[] payload) {
        if (header == null || header.length < WAKE_HEADER_SIZE) return null;
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(header)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int cmd = buf.getInt(0);
        int totalLen = buf.getInt(4);
        int magic = buf.getInt(8);
        if ((cmd ^ totalLen) != magic) return null;
        if (cmd != CMD_MDNS_RESPOND_ACK) return null;
        if (payload == null) return null;
        return new String(payload, java.nio.charset.StandardCharsets.UTF_8);
    }

    public static String hex(byte[] data, int off, int len) {
        return hex(java.util.Arrays.copyOfRange(data, off, Math.min(data.length, off + len)));
    }

    public static String hex(byte[] data) {
        if (data == null) return "(null)";
        StringBuilder sb = new StringBuilder(data.length * 3);
        for (byte b : data) {
            sb.append(String.format("%02X ", b & 0xFF));
        }
        return sb.toString().trim();
    }

    /** 把一段字节尽力解释成 UTF-8（去掉控制字符），用于日志可读性。 */
    public static String text(byte[] data) {
        if (data == null) return "";
        StringBuilder sb = new StringBuilder();
        for (byte b : data) {
            int c = b & 0xFF;
            if (c == '\n' || c == '\r') sb.append(' ');
            else if (c >= 0x20 && c < 0x7F) sb.append((char) c);
            else if (c >= 0x80) sb.append((char) c);
            else sb.append('.');
        }
        return sb.toString();
    }
}
