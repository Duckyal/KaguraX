package com.termux.app;

import android.content.Context;
import android.util.Log;

import com.termux.R;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * AdbCmd —— 在 App 进程内静默执行「容器内 adb」命令的工具类（纯后台，无 UI）。
 *
 * 为什么独立成类：配对、连接、状态检测三处都要在 Debian 容器里跑 adb
 * （主界面 AdbPairPanel、自动流程 AdbPairAccessibilityService、通知输入 AdbPairInputReceiver），
 * 命令构造、环境注入、超时保护、输出解析完全一致，集中在这里避免两套实现漂移。
 *
 * 关键实现约束（都是踩过的坑，勿轻易改）：
 *   1. Runtime.exec 默认 PATH 不含 Termux 目录，必须显式 export {@link #TERMUX_BIN}，
 *      否则系统找不到 proot-distro；
 *   2. App 进程（zygote 继承）没有 Termux 的 LD_LIBRARY_PATH 及 proot-distro 必需变量：
 *      缺 LD_LIBRARY_PATH 时 bash/proot 全部 CANNOT LINK；缺 TERMUX_APP__PACKAGE_NAME /
 *      TERMUX__PREFIX 时 proot-distro 回落官方 com.termux 前缀导致路径错乱、登录失败。
 *      症状：配对/连接点了没反应（命令静默失败或超时）；
 *   3. 必须先 waitForProcess 再 readLine：命令卡住时 readLine 会永久阻塞，超时保护根本
 *      执行不到，子线程永久挂起、按钮永远禁用、无任何 Toast；
 *   4. adb connect 回 "connected to" 只代表 TCP+adb 握手建立，不代表设备可用（未授权的
 *      设备同样回这句），必须以同一次容器会话里 adb devices 的真实状态为准。
 *   5. 整串命令必须交给「容器内 shell」执行（bash -c '…'）：容器里才有 adb，宿主 Termux
 *      并没有装。若不带 bash -c 直接跟在 proot-distro login 后面，命令串里的分号会被
 *      宿主 bash 当成命令分隔符吃在宿主上执行 —— 例如
 *      "proot-distro login debian -- adb connect X; adb devices" 里的 adb devices 实际
 *      跑在宿主（command not found），于是「连接明明已经成功」也会被判成未就绪。
 *      （2026-09 修复：这是「无线调试怎么都连不上」的真实原因）
 *   6. 一次 exec = 一次容器会话，会话退出时容器内的 adb server 随之被 proot 带走，server
 *      侧的 transport / 设备列表**不跨 exec 保留**。所以「连接 → 验证 → 自授权」这类有
 *      前后依赖的命令必须写在同一条命令串里顺序执行；拆成两次 exec 时第二次必然报
 *      "device '…' not found"（2026-09 修复：这是「WRITE_SECURE_SETTINGS 永远拿不到、
 *      无障碍每次都得手动开」的真实原因）。
 *   7. exec 必须全局串行：并发调用会各自拉起一个 adb server 互抢 5037 端口，日志里表现为
 *      "ADB server didn't ACK"，并让状态检测偶发误判成「未连接」。
 */
public final class AdbCmd {

    /** Termux 自带 bash 的绝对路径（Runtime.exec 不加载 Termux 环境，需显式指定） */
    private static final String TERMUX_BASH = "/data/data/duckyal.KaguraX/files/usr/bin/bash";

    /** Termux 可执行目录（proot-distro 等命令所在的 PATH 前缀） */
    private static final String TERMUX_BIN = "/data/data/duckyal.KaguraX/files/usr/bin";

    /** Termux 前缀目录（含 lib/tmp 等） */
    private static final String TERMUX_PREFIX = "/data/data/duckyal.KaguraX/files/usr";

    /** Termux 主目录（bash 的 HOME，proot_env.sh 也在其中） */
    private static final String TERMUX_HOME = "/data/data/duckyal.KaguraX/files/home";

    /** 应用包名（proot-distro 用它定位 App 数据目录，必须与实际包名一致） */
    private static final String PACKAGE_NAME = "duckyal.KaguraX";

    /** 日志标签 */
    private static final String TAG = "AdbCmd";

    /**
     * 是否已经给自己授过 WRITE_SECURE_SETTINGS（见 {@link #ensureSelfPermission}）。
     * 成功一次就永久有效，所以置位后不再重复执行 —— 写 secure settings 是重操作。
     */
    private static volatile boolean sSelfPermissionGranted;

    /**
     * 自授权段在命令串里的分隔标记。
     *
     * 为什么要标记：自授权必须挂在 connect 那次会话的尾巴上（见类注释第 6 条），但它的输出
     * 不能和 connect 的输出混在一起解析 —— 否则 pm grant 的报错会冒充「连接失败原因」。
     * 命令串里 echo 一行标记，解析时按标记切成两段各管各的。
     */
    private static final String PERM_MARKER = "__KAGURAX_PERM__";

    /** 容器命令的全局互斥锁（见类注释第 7 条） */
    private static final Object EXEC_LOCK = new Object();

    /**
     * 被控端地址：恒为本机 loopback。
     *
     * 本项目连的就是本机的无线调试（容器里的程序要 adb 控制同一台手机），proot 容器与 Android
     * 共享网络栈，容器内 adb 走 127.0.0.1 即可连上，所以界面上不需要 IP、只填端口。
     */
    public static final String HOST = "127.0.0.1";

    /** 等待 adb devices 之类轻量命令的最长时间（毫秒），防止进程挂起卡死子线程 */
    public static final long EXEC_TIMEOUT_MS = 20000;

    /** 等待配对/连接命令的最长时间（毫秒）：adb 网络操作可能稍慢 */
    public static final long OP_TIMEOUT_MS = 30000;

    /** adb connect 的最大尝试次数（含首次）：配对刚成功后立即 connect 偶发失败，重试兜底 */
    private static final int MAX_CONNECT_ATTEMPTS = 3;

    /** 两次 connect 尝试之间的间隔（毫秒） */
    private static final long CONNECT_RETRY_DELAY_MS = 2000;

    /** connect 后等待设备出现在 adb devices 的固定时长（秒，与 connect 同会话内 sleep） */
    private static final String CONNECT_SETTLE_SLEEP_S = "1.5";

    /**
     * 端口探测的成败标记（见 {@link #anyCandidatePortOpen}）：容器内 bash 用 /dev/tcp 试连，
     * 通了打 OPEN、没通打 CLOSED。不用 nc —— 容器里不保证装了 netcat，而 /dev/tcp 是 bash
     * 自带的重定向，一定有。
     */
    private static final String PORT_OPEN_MARKER = "__KAGURAX_PORT_OPEN__";
    private static final String PORT_CLOSED_MARKER = "__KAGURAX_PORT_CLOSED__";

    private AdbCmd() {
        // 纯工具类，禁止实例化
    }

    // ==================== 命令执行 ====================

    /**
     * 在 App 进程内静默执行容器内 adb 命令（不打开任何终端）。
     *
     * 命令形态：export PATH=<Termux bin> && source proot_env.sh
     *           && proot-distro login debian -- bash -c '<adb 命令串>'
     *
     * @param adbArgs  容器内 shell 命令串：必须自带 adb 前缀，允许含分号等 shell 语法，
     *                 如 "adb devices"、"adb pair 127.0.0.1:37015 123456"、
     *                 "adb connect 127.0.0.1:43911; sleep 1.5; adb devices"
     * @param timeoutMs 最长等待毫秒数，超时强制销毁进程
     * @return 命令完整输出（stdout+stderr 合并）；容器未初始化/执行异常/超时返回 null
     */
    public static String exec(String adbArgs, long timeoutMs) {
        // 串行执行（类注释第 7 条）：抢锁的等待远小于并发导致的两个 adb server 互杀；
        // 所有调用方都在子线程（状态检测 / 配对 / 连接各自 new Thread），不会阻塞主线程。
        synchronized (EXEC_LOCK) {
            return execLocked(adbArgs, timeoutMs);
        }
    }

    /** exec 的实际实现；调用前必须已持有 {@link #EXEC_LOCK} */
    private static String execLocked(String adbArgs, long timeoutMs) {
        if (!MainActivity.isDebianInitialized()) {
            return null;    // 容器未初始化时直接返回，不浪费时间执行命令
        }

        try {
            // source ~/proot_env.sh 自愈 proot 运行环境（/system/bin/sh wrapper + PD_PROOT_BIN），
            // 否则 proot-distro 在干净环境下启动 proot 报 CANNOT LINK。
            String command = "export PATH=" + TERMUX_BIN + ":$PATH && "
                    + ". " + TERMUX_HOME + "/proot_env.sh 2>/dev/null || true; "
                    + "proot-distro login debian -- bash -c " + shellQuote(adbArgs);

            ProcessBuilder pb = new ProcessBuilder(TERMUX_BASH, "-c", command);
            pb.redirectErrorStream(true);   // stderr 并入 stdout，避免只读一个流导致管道阻塞死锁

            // 注入 Termux 运行环境（与 TermuxShellEnvironment 注入的变量保持一致）
            Map<String, String> env = pb.environment();
            env.put("HOME", TERMUX_HOME);
            env.put("PREFIX", TERMUX_PREFIX);
            env.put("TERM", "dumb");
            env.put("LD_LIBRARY_PATH", TERMUX_PREFIX + "/lib");
            env.put("TERMUX_APP__PACKAGE_NAME", PACKAGE_NAME);
            env.put("TERMUX__PREFIX", TERMUX_PREFIX);
            env.put("TERMUX_APP__DATA_DIR", "/data/data/" + PACKAGE_NAME);
            env.put("TERMUX_APP__LEGACY_DATA_DIR", "/data/data/" + PACKAGE_NAME);
            env.put("PROOT_TMP_DIR", TERMUX_PREFIX + "/tmp");

            Process process = pb.start();

            // 顺序关键：先等待进程结束（带超时强制销毁），再读输出。
            // 反过来则命令卡住时 readLine 永久阻塞，超时保护执行不到。
            waitForProcess(process, timeoutMs);

            // 进程已结束（或已销毁），readLine 立即读到 EOF；销毁前写入管道的输出仍可读出
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }
            // 「连不上」时全靠这条日志定位到底是哪一侧的问题：adb logcat -s AdbCmd 就能看到
            // 每一条命令与它吐回来的第一行实质输出
            Log.i(TAG, "adb " + adbArgs + " → " + firstMeaningfulLine(output.toString()));
            return output.toString();
        } catch (Exception e) {
            return null;    // 命令不存在 / IO 异常等：按失败处理
        }
    }

    /**
     * 把一串命令安全地包成「容器内 shell 的单条命令」：整体用单引号包裹，
     * 串内单引号按 POSIX 规则转义为 '\'' 。
     *
     * 为什么不能把参数直接跟在 proot-distro login 后面：那样命令串里的分号会被宿主 bash
     * 当成命令分隔符抢先在宿主上执行（详见类注释第 5 条，正是「连不上」的根因）。
     */
    private static String shellQuote(String command) {
        return "'" + command.replace("'", "'\\''") + "'";
    }

    /**
     * 等待进程结束（兼容 API 24：不用 waitFor(long, TimeUnit)，改轮询）。
     * 超过超时时间直接销毁进程，避免子线程永久卡死。
     */
    private static void waitForProcess(Process process, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                process.exitValue();    // 进程已结束时返回退出码，否则抛 IllegalThreadStateException
                return;
            } catch (IllegalThreadStateException e) {
                Thread.sleep(200);      // 进程还在运行，休眠后继续轮询
            }
        }
        process.destroy();              // 超时：强制结束
    }

    // ==================== connect（含重试与真伪校验） ====================

    /** adb connect 的结果：由 {@link #connect} 在子线程中构造，交给 UI 线程做提示。 */
    public static final class ConnectResult {

        /** 真连接成功（connect 文本成功 且 adb devices 中该地址状态为 device） */
        public final boolean success;

        /** 连接已建立但被控端未授权（需在被控端点「允许」） */
        public final boolean needsAuth;

        /** connect 文本回了 connected to，但设备始终没进 adb devices（假连接成功） */
        public final boolean textSaysConnected;

        /** 认证类失败（unauthorized / 认证失败）→ 上次配对已失效，需重新配对 */
        public final boolean needsRePair;

        /** 可操作的中文提示；无法识别失败原因时为 null */
        public final String hint;

        /** 失败时 adb 的实质输出行（用于展示具体原因）；无则 null */
        public final String detail;

        ConnectResult(boolean success, boolean needsAuth, boolean textSaysConnected,
                      boolean needsRePair, String hint, String detail) {
            this.success = success;
            this.needsAuth = needsAuth;
            this.textSaysConnected = textSaysConnected;
            this.needsRePair = needsRePair;
            this.hint = hint;
            this.detail = detail;
        }
    }

    /**
     * 后台静默执行 adb connect，带回「是否真的连上」的结论。
     *
     * 加固要点（针对「反复开关无线调试才连得上」）：
     *  1) 重试前先 adb disconnect 同一地址，清掉 adb server 里残留的 offline / unauthorized
     *     旧连接，让下次 connect 重新完整握手（避免旧状态导致误判）；
     *  2) connect 与 devices 验证放在同一次容器会话内顺序执行，规避 adb server daemon 在两次
     *     exec 之间被杀/漂移（新旧 daemon 各自查不到对方建的 transport）导致的假成功；
     *  3) 失败原因按 adb 输出分类成「该做什么」的中文提示，而不是回显英文原文；
     *  4) 地址按 {@link #candidatesOf} 依次尝试：先 loopback，再本机局域网 IP —— 有些 ROM 的
     *     无线调试 adbd 只绑 wlan0 地址，此时 127.0.0.1 必然 connection refused，必须回退。
     *
     * @param address 形如 "127.0.0.1:43911"（端口取自系统「无线调试」页当前显示值）
     * @param context 用于取分类提示文案（传 application context）
     */
    public static ConnectResult connect(String address, Context context) {
        String output = null;
        String verifiedState = null;   // adb devices 中该地址的真实状态：device/unauthorized/其他
        boolean success = false;
        String connectedAddress = null;   // 真正连上的候选地址（自授权要指定 -s，不能用入参）

        for (String candidate : candidatesOf(address)) {
            for (int attempt = 1; attempt <= MAX_CONNECT_ATTEMPTS; attempt++) {
                Log.i(TAG, "连接尝试 " + candidate + "（第 " + attempt + "/" + MAX_CONNECT_ATTEMPTS + " 次）");

                // 一条命令串里做完「清残留 → connect → 等 transport → devices 验证（→ 自授权）」。
                // 必须同会话：每次 exec 都是新的容器会话，会话一退容器内 adb server 就被 proot
                // 带走，上一次会话建的 transport / 设备列表不复存在（类注释第 6 条），所以断开、
                // 验证、授权都不能另起一次 exec。
                StringBuilder cmd = new StringBuilder();
                if (attempt > 1) {
                    // 清掉 adb server 里残留的 offline / unauthorized 旧 transport，让本次重新完整握手
                    cmd.append("adb disconnect ").append(candidate).append("; sleep 0.5; ");
                }
                cmd.append("adb connect ").append(candidate)
                        .append("; sleep ").append(CONNECT_SETTLE_SLEEP_S)
                        .append("; adb devices");
                // 自授权搭在这次会话的尾巴上（成功一次即永久有效，之后不再附加）
                final boolean wantGrant = !sSelfPermissionGranted && context != null;
                if (wantGrant) {
                    cmd.append("; echo ").append(PERM_MARKER)
                            .append("; adb -s ").append(candidate)
                            .append(" shell pm grant ").append(context.getPackageName())
                            .append(" android.permission.WRITE_SECURE_SETTINGS 2>&1");
                }

                output = exec(cmd.toString(), OP_TIMEOUT_MS);
                if (wantGrant) {
                    applyGrantResult(output);   // 严格判定：pm grant 成功时完全没有输出
                }
                // 连接判定只看标记之前的正文，pm grant 的报错不能冒充「连接失败原因」
                String body = bodyOf(output);
                if (body != null && isSuccess(body, "connected to", "already connected")) {
                    // 假阳性防护：connect 文本成功≠设备可用（未授权设备也回 "connected to"）
                    verifiedState = deviceStateOf(body, candidate);
                    if ("device".equals(verifiedState)) {
                        success = true;
                        connectedAddress = candidate;
                        break;
                    }
                    if ("unauthorized".equals(verifiedState)) {
                        break;   // 已握手但被控端未授权：停下等用户在被控端点「允许」，不误报成功
                    }
                    // offline / 列表里还没有该地址：transport 未就绪，下一轮先 disconnect 再重连
                }
                // 该地址压根没在监听：重试多少次都一样，直接换下一个候选地址
                if (isConnectionRefused(body)) {
                    break;
                }
                if (attempt < MAX_CONNECT_ATTEMPTS && !sleepQuietly(CONNECT_RETRY_DELAY_MS)) {
                    break;
                }
            }
            // 已得出结论（连上 / 待授权）就没必要再试别的地址
            if (success || "unauthorized".equals(verifiedState)) {
                break;
            }
        }

        Log.i(TAG, "连接结论：success=" + success + "，adb devices 状态=" + verifiedState
                + "，已连地址=" + connectedAddress);

        // 自授权已经在连接会话里顺带做掉了（循环内的 wantGrant）；这里只做结论解析，
        // 且一律只看标记之前的正文 —— 自授权段的报错不能冒充连接失败原因
        final String body = bodyOf(output);
        final boolean needsAuth = "unauthorized".equals(verifiedState);
        final boolean textSaysConnected = !success
                && isSuccess(body, "connected to", "already connected");
        // 配对已失效（TLS 证书换了）也是「连不上」的一大来源，而 adb 对它的输出与端口失效
        // 一模一样（都是一句没原因的 failed to connect），只能靠「端口到底在不在听」区分。
        final boolean stalePairing = !success && !needsAuth && !textSaysConnected
                && anyCandidatePortOpen(address);
        return new ConnectResult(
                success,
                needsAuth,
                textSaysConnected,
                needsRePair(body) || stalePairing,
                connectHint(context, body),
                failureLine(body));
    }

    /**
     * 入参地址的候选里，是否有端口真的在监听（TCP 层可达）。
     *
     * 用途是把两种「连不上」分开，它们的 adb 输出完全相同（都是没有原因的
     * {@code failed to connect to …}）：
     *   · 端口没人听 → 端口/开关问题（端口变了、无线调试关了），重试与重配对都白搭；
     *   · 端口听着却握不上手 → **身份对不上**：adbd 在无线调试关开后会换掉 TLS 证书，
     *     而客户端 {@code adb_known_hosts.pb} 里按 guid 存的是旧指纹，握手会被
     *     {@code SSLV3_ALERT_CERTIFICATE_UNKNOWN} 掐断（真原因只在 server 进程的 trace 里，
     *     客户端那句输出看不到）。这种情况重走一次配对即可恢复。
     */
    private static boolean anyCandidatePortOpen(String address) {
        for (String candidate : candidatesOf(address)) {
            int colon = candidate.lastIndexOf(':');
            if (colon <= 0) continue;
            String ip = candidate.substring(0, colon);
            String port = candidate.substring(colon + 1);
            String output = exec("(exec 3<>/dev/tcp/" + ip + "/" + port + ") >/dev/null 2>&1"
                    + " && echo " + PORT_OPEN_MARKER + " || echo " + PORT_CLOSED_MARKER,
                    EXEC_TIMEOUT_MS);
            if (output != null && output.contains(PORT_OPEN_MARKER)) return true;
        }
        return false;
    }

    /**
     * 用已建立的 adb 连接给自己授 WRITE_SECURE_SETTINGS（成功一次即永久有效）。
     *
     * 为什么要它：部分系统（或手机管家）会在应用被一键清理、从最近任务划掉之后，顺手把这个
     * 应用的无障碍服务从系统设置里摘掉，用户下次进来必须手动再开一遍 —— 这正是「每次都要重新
     * 开无障碍」的来源。拿到这个权限后，{@code AdbPairAccessibilityService.tryReEnable} 就能
     * 自己把开关写回 Settings.Secure，用户从此无感。
     *
     * 为什么它授得下来：WRITE_SECURE_SETTINGS 的 protectionLevel 是
     * {@code signature|privileged|development}，带 development 标记，所以 shell 的 pm grant
     * 可以授给普通应用（Shizuku / Termux 生态的标准手法）。
     *
     * 纯尽力而为：没连上、被 ROM 挡住、命令超时都只记一条日志，不打扰用户 —— 缺了它只是
     * 退回「引导用户去系统设置手动开」的老路，功能本身不受影响。
     *
     * @param address 已连上的设备地址；必须用 -s 指定，否则设备不唯一时 adb 会直接拒绝执行
     */
    public static void ensureSelfPermission(String address, Context context) {
        if (sSelfPermissionGranted || address == null || context == null) return;
        // 双检 + 与 exec 同一把锁（可重入）：界面 refresh 与自动流程可能同时走到这里，
        // 串行化后第二次进来直接看到已置位就返回，不会白跑一条数秒的容器命令
        synchronized (EXEC_LOCK) {
            if (sSelfPermissionGranted) return;
            ensureSelfPermissionLocked(address, context);
        }
    }

    /** ensureSelfPermission 的实际执行；调用前必须已持有 {@link #EXEC_LOCK} */
    private static void ensureSelfPermissionLocked(String address, Context context) {
        try {
            // 必须先 connect 再 grant，而且写在同一条命令串里（类注释第 6 条）：这次 exec 是
            // 新的容器会话、新的 adb server，里面没有该设备的 transport，直接 pm grant 只会
            // 得到 "device '…' not found"。老实现正是这样拿到失败输出、却把它判成成功，
            // 于是 sSelfPermissionGranted 被永久置位，无障碍再也开不回来。
            String output = exec("adb connect " + address + "; sleep 1.2; echo " + PERM_MARKER
                    + "; adb -s " + address + " shell pm grant " + context.getPackageName()
                    + " android.permission.WRITE_SECURE_SETTINGS 2>&1", OP_TIMEOUT_MS);
            applyGrantResult(output);
        } catch (Exception e) {
            Log.w(TAG, "自授权 WRITE_SECURE_SETTINGS 异常", e);
        }
    }

    /**
     * 按「标记之后的输出」判定自授权结果。
     *
     * 判据只有一条：pm grant 成功时**完全没有输出**，失败一定会打印点什么
     * （`device '…' not found` / `not allowed` / SecurityException …）。老实现是看输出里
     * 有没有 Exception/Error/not allowed/Permission 这几个词，而真实的失败文案
     * （device not found、write failure during connection: Connection reset by peer）
     * 一个都不含 —— 失败被判成成功，还永久置位缓存，连重试的机会都没有了。
     */
    private static void applyGrantResult(String output) {
        if (output == null) return;      // 命令没跑起来（容器未就绪 / 超时）：保持未授权，下次再试
        int idx = output.indexOf(PERM_MARKER);
        if (idx < 0) return;             // 没走到授权段（中途超时/被销毁）：同理，不置位
        String permOut = output.substring(idx + PERM_MARKER.length()).trim();
        if (permOut.isEmpty()) {
            sSelfPermissionGranted = true;
            Log.i(TAG, "已取得 WRITE_SECURE_SETTINGS：无障碍被系统摘掉后可自动写回");
        } else {
            Log.w(TAG, "自授权 WRITE_SECURE_SETTINGS 未成功：" + firstMeaningfulLine(permOut));
        }
    }

    /** 取命令输出里「自授权标记」之前的正文；没有标记时原样返回 */
    private static String bodyOf(String output) {
        if (output == null) return null;
        int idx = output.indexOf(PERM_MARKER);
        return idx < 0 ? output : output.substring(0, idx);
    }

    /**
     * 提取「为什么没连上」的那一行，用于界面提示。
     *
     * 不能直接用 {@link #firstMeaningfulLine}：connect 最终没连上时，第一行往往照样是
     * "connected to …"（TCP 通了但设备没进列表），拿它当失败原因等于告诉用户「连接正常」。
     * 所以先跳过 connect/disconnect 的过程输出，优先返回带失败关键词的行。
     */
    public static String failureLine(String output) {
        if (output == null) return null;
        String fallback = null;
        for (String line : output.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("*")) continue;   // 跳过 adb daemon 提示
            String lower = trimmed.toLowerCase();
            if (lower.startsWith("connected to") || lower.startsWith("already connected")
                    || lower.startsWith("disconnected")) {
                if (fallback == null) fallback = trimmed;
                continue;
            }
            if (isFailureText(lower)) return trimmed;
            if (fallback == null) fallback = trimmed;
        }
        return fallback;
    }

    /** 该行是否写着失败原因（连接/授权类命令的失败文案特征） */
    private static boolean isFailureText(String lower) {
        return lower.contains("fail") || lower.contains("error") || lower.contains("refused")
                || lower.contains("unauthorized") || lower.contains("offline")
                || lower.contains("cannot") || lower.contains("not found")
                || lower.contains("unable") || lower.contains("denied")
                || lower.contains("exception");
    }

    /**
     * 候选地址列表：先传入的地址（loopback），再本机局域网 IP 的同端口地址。
     *
     * 为什么必须回退：无线调试的 adbd 监听地址各 ROM 不同 —— 监听 0.0.0.0 的用 127.0.0.1 就通，
     * 只绑 wlan0 地址的则必须用手机自己的 192.168.x.x。两个都试，才能不挑 ROM。
     * 拿不到局域网地址（没连 WiFi）时列表里只有传入地址。
     */
    private static List<String> candidatesOf(String address) {
        List<String> candidates = new ArrayList<>();
        candidates.add(address);

        String port = portOf(address);
        String lanIp = localLanIpv4();
        if (port != null && lanIp != null) {
            String alt = lanIp + ":" + port;
            if (!alt.equals(address)) {
                candidates.add(alt);
            }
        }
        return candidates;
    }

    /** 取 "ip:port" 的端口部分；形式不对返回 null */
    private static String portOf(String address) {
        if (address == null) return null;
        int colon = address.lastIndexOf(':');
        if (colon <= 0 || colon == address.length() - 1) return null;
        String port = address.substring(colon + 1);
        return port.matches("\\d+") ? port : null;
    }

    /**
     * 本机第一个局域网 IPv4 地址（手机的 192.168.x.x / 10.x.x.x），wlan 网卡优先。
     * 取不到（未连 WiFi、无权限枚举网卡）返回 null。
     */
    private static String localLanIpv4() {
        String fallback = null;
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
                    if (!(addr instanceof Inet4Address) || addr.isLoopbackAddress()
                            || !addr.isSiteLocalAddress()) {
                        continue;
                    }
                    String ip = addr.getHostAddress();
                    if (nif.getName() != null && nif.getName().startsWith("wlan")) {
                        return ip;      // 无线调试一般就绑在这张网卡上，优先用它
                    }
                    if (fallback == null) {
                        fallback = ip;
                    }
                }
            }
        } catch (Exception ignored) {
            // 枚举网卡失败：当作没有局域网地址，只用 loopback
        }
        return fallback;
    }

    /** connect 输出是否表明「目标地址根本没监听」（这种失败重试无意义，直接换地址） */
    private static boolean isConnectionRefused(String output) {
        if (output == null) return false;
        String lower = output.toLowerCase();
        return lower.contains("connection refused")
                || lower.contains("cannot connect to")
                || lower.contains("connection reset");
    }

    /** 休眠指定毫秒；被中断返回 false（调用方据此中止重试） */
    private static boolean sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            return false;
        }
    }

    // ==================== pair（配对码配对） ====================

    /**
     * 后台静默执行 adb pair（配对码配对）。
     *
     * 地址同样多点尝试：先 loopback，再系统配对页上显示的 IP，最后本机局域网 IP ——
     * 配对服务与连接服务的监听地址在不同 ROM 上并不一致，全试一遍才不挑 ROM。
     *
     * 与 connect 的差别：pair 成功标志是输出里的 "Successfully paired to ..."，
     * 没有再需要二次验证的状态（配对成功后 adb server 立即持有该设备的认证密钥）。
     *
     * @param port   系统配对页显示的端口
     * @param code   系统配对页显示的 6 位配对码
     * @param pageIp 系统配对页显示的 IP（可空：拿不到就只试 loopback 与局域网 IP）
     * @return adb 完整输出；全部候选都失败时返回最后一次的输出（供上层展示原因）
     */
    public static String pair(String port, String code, String pageIp) {
        String firstOutput = null;   // 第一个候选的输出：实在没有更好的原因时用它
        String causeOutput = null;   // 带着实质原因的那条（配对码错 / 认证被拒），优先展示

        for (String address : pairCandidates(port, pageIp)) {
            String output = exec("adb pair " + address + " " + code, OP_TIMEOUT_MS);
            if (isSuccess(output, "successfully paired")) {
                return output;
            }
            if (firstOutput == null) {
                firstOutput = output;
            }
            if (causeOutput == null && explainsPairFailure(output)) {
                causeOutput = output;
            }
        }
        // 展示顺序讲究：多个候选试下来，最后一条多半只是「那个地址没监听」（Connection refused），
        // 拿它当原因会把用户往网络问题上带 —— 真正的原因（码不对/认证被拒）往往在别的候选那一条上
        return causeOutput != null ? causeOutput : firstOutput;
    }

    /**
     * 这条输出里是否带着「为什么配不上」的实质原因。
     *
     * 单纯连不上（Connection refused）说明的是某个候选地址没在监听，不是配对本身的问题；
     * 只有密码/认证类字样才真的解释了失败，值得优先摆给用户看。
     */
    private static boolean explainsPairFailure(String output) {
        if (output == null) return false;
        String lower = output.toLowerCase();
        return lower.contains("password") || lower.contains("authenticate")
                || lower.contains("failed to pair") || lower.contains("denied");
    }

    /**
     * pair 的候选地址（host:port）：loopback → 页面显示 IP → 本机局域网 IP。
     * 去重且保序，重复地址不重复尝试。
     */
    private static List<String> pairCandidates(String port, String pageIp) {
        List<String> candidates = new ArrayList<>();
        addCandidate(candidates, HOST + ":" + port);
        if (pageIp != null && !pageIp.isEmpty()) {
            addCandidate(candidates, pageIp + ":" + port);
        }
        String lanIp = localLanIpv4();
        if (lanIp != null) {
            addCandidate(candidates, lanIp + ":" + port);
        }
        return candidates;
    }

    /** 加入候选地址（已存在则跳过） */
    private static void addCandidate(List<String> candidates, String address) {
        if (!candidates.contains(address)) {
            candidates.add(address);
        }
    }

    // ==================== 状态探测 ====================

    /**
     * 探测「现在到底连着没有」，返回已连接的设备地址；未连接返回 null。
     *
     * 为什么不能直接跑 `adb devices`：每次 exec 都是一次新的容器会话，会话一退出容器内的
     * adb server 就被 proot 带走（类注释第 6 条），新会话拿到的是**空设备列表** —— 于是出现
     * 「刚提示连接成功、状态条却显示未连接」，用户以为自己没配对成功。无线调试的连接端口在
     * 同一次开关内是稳定的（只有关掉再开才会换），所以在同一次会话里用记下的端口重连一次，
     * 再看 adb devices，得到的才是真实状态。
     *
     * @param port 最近一次连接成功的端口（prefs 里的 last_connect_port）；
     *             为空 / 不是纯数字时退化成单纯的 adb devices
     *             （只能反映「本次会话内」有没有设备，通常为空）
     */
    public static String probeConnected(String port) {
        if (port == null || !port.matches("\\d+")) {
            return firstDevice(exec("adb devices", EXEC_TIMEOUT_MS));
        }
        String candidate = HOST + ":" + port;
        // 端口已失效（无线调试关过又开）时 connect 会失败、devices 也是空列表，
        // 于是照常返回 null，不会把「连不上」误报成「已连接」
        String output = exec("adb connect " + candidate + "; sleep 1.0; adb devices", EXEC_TIMEOUT_MS);
        return firstDevice(bodyOf(output));
    }

    // ==================== 输出解析 ====================

    /**
     * 解析 adb devices 输出，找第一台状态为 device 的设备。
     *
     * 规则：第二列必须精确等于 device（offline/unauthorized 不算）；
     * 标题行 "List of devices attached" 第二列是 "of"，天然被排除。
     *
     * @return 形如 "127.0.0.1:43911" 的设备地址；无有效设备返回 null
     */
    public static String firstDevice(String output) {
        if (output == null) return null;
        for (String line : output.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] parts = trimmed.split("\\s+");
            if (parts.length >= 2 && "device".equals(parts[1])) {
                return parts[0];    // 返回第一台有效设备
            }
        }
        return null;
    }

    /**
     * 在 adb 命令输出中查找指定序列号(ip:port)所在行并返回其状态。
     * 未出现在输出中返回 null。
     */
    public static String deviceStateOf(String output, String address) {
        if (output == null) return null;
        for (String line : output.split("\n")) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length >= 2 && address.equals(parts[0])) {
                return parts[1];
            }
        }
        return null;
    }

    /** 判断命令输出是否命中任一成功标志（大小写不敏感） */
    public static boolean isSuccess(String output, String... markers) {
        if (output == null) return false;
        String lower = output.toLowerCase();
        for (String marker : markers) {
            if (lower.contains(marker.toLowerCase())) return true;
        }
        return false;
    }

    /** 从命令输出中提取用于提示的错误详情：取第一个非空、非 adb 常规提示的行 */
    public static String firstMeaningfulLine(String output) {
        if (output == null) return null;
        for (String line : output.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            if (trimmed.startsWith("*")) continue;   // 跳过 "* daemon not running" 等常规提示
            return trimmed;
        }
        return null;
    }

    /** 按 adb connect 失败输出分类出「该做什么」的可操作提示；无法识别返回 null */
    public static String connectHint(Context context, String output) {
        if (output == null) return null;
        String lower = output.toLowerCase();
        if (lower.contains("unauthorized")) {
            return context.getString(R.string.adb_hint_unauthorized);
        }
        if (lower.contains("failed to authenticate")
                || lower.contains("authentication failed")
                || lower.contains("failed to authenticate to")) {
            return context.getString(R.string.adb_hint_re_authenticate);
        }
        if (lower.contains("offline")) {
            return context.getString(R.string.adb_hint_offline);
        }
        if (lower.contains("cannot connect") || lower.contains("connection refused")
                || lower.contains("failed to connect") || lower.contains("timed out")
                || lower.contains("no route to host") || lower.contains("unable to connect")) {
            return context.getString(R.string.adb_hint_cannot_connect);
        }
        return null;
    }

    /** 认证类失败（unauthorized / 认证失败）说明上次配对已失效，需要重新配对 */
    public static boolean needsRePair(String output) {
        if (output == null) return false;
        String lower = output.toLowerCase();
        return lower.contains("unauthorized")
                || lower.contains("failed to authenticate")
                || lower.contains("authentication failed");
    }
}
