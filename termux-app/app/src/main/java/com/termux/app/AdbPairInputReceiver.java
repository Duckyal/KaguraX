package com.termux.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.core.app.RemoteInput;

import com.termux.R;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AdbPairInputReceiver —— 接收「可填写通知」里用户敲进来的配对信息 / 连接端口。
 *
 * ## 为什么从悬浮窗搬到通知
 * 配对端口与配对码只在系统「无线调试」页上显示，原本是在那一页上浮个小窗让用户照抄。但 Android 12
 * 起系统「设置」持有 HIDE_OVERLAY_WINDOWS，会隐藏第三方 TYPE_APPLICATION_OVERLAY 窗口 ——
 * 配对小窗在那一页上**从来就没出现过**，那条路是死的（跟「显示在其他应用上层」权限无关，给了也没用）。
 * 通知栏不受该限制（它是 SystemUI），配一个 RemoteInput 就能在通知里直接输入，于是搬到这里。
 *
 * ## 输入格式（两条通知都支持「长按复制整行再粘贴」）
 * RemoteInput 一次只给一个文本字段，而配对需要两个值，所以约定一起填、由 {@link #parsePair}
 * 宽松拆开：只挑数字段，取**最后两段**当「配对端口 + 配对码」。下面这些写法都能过：
 * <pre>
 *   37015 123456                 照通知里的提示直接敲
 *   37015,123456 / 37015:123456  分隔符随便
 *   192.168.1.5:37015 123456     把系统页那行整个粘过来，再补个空格和配对码
 *   37015123456                  没分隔符，按「后 6 位是配对码」切
 * </pre>
 * 连接那条更省事：系统页顶部那行「IP 地址和端口」长按能**整行复制**，所以直接粘
 * {@code 192.168.1.5:43911} 即可（{@link #parseAddress}）；只填端口也认。粘进来的 IP 会**当真
 * 拿去连**，而不是一律压回 127.0.0.1 —— 有些 ROM 的 adbd 只绑 wlan0，用页面上那个 IP 比
 * loopback 更容易连上。
 *
 * ## 什么时候回 App：配对要等成功，连接拿到就走
 * 两条链路的节奏故意不一样，根子在**系统「使用配对码配对设备」那个页面抱着配对会话**：
 * <pre>
 *   配对：填完先别动（见 handlePair）。把用户从那一页切走，不少 ROM 会就地关掉配对端口，
 *         而 adb pair 要等容器起来、好几秒后才真正发出去 —— 提前跳走等于自己把码作废掉，
 *         表现就是「码明明没敲错却配对失败」。所以配对期间人就留在通知栏看「正在配对」，
 *         成功之后再拉起主界面；失败也留在原地，通知保留输入框，正好照系统页上的新数字重填。
 *   连接：不涉及任何会话，地址一解析出来就立刻回 App（{@link #returnToApp}），adb 在后台跑，
 *         结果用 Toast 汇报，并由 {@link AdbPairPanel#refreshActive()} 把状态条刷到最新。
 * </pre>
 *
 * 认不出来的情况例外：那说明我们要的数字还没到手，原地留着输入框、趁用户还在页面上让他改
 * 才是对的（这时也不该把人拽走）。
 *
 * 配对成功后还不能直接连 —— 连接端口只显示在「无线调试」主页上，得用户自己回去抄（好在长按
 * 能复制）。所以那条成功 Toast 会把这一步交代清楚，同时通知原地换成「填连接端口」等着他（见
 * {@link AdbPairNotifier#manualAskConnectPort}）。
 *
 * ## 为什么必须 goAsync
 * adb pair / connect 是子进程调用（要走 proot 容器），耗时几秒到几十秒。onReceive 同步返回后本
 * 广播接收者所在进程随时可能被回收，流程会无声中断 —— 所以先 goAsync 把广播挂住再干活。
 * 系统给的时限约 10 秒，正常配对远小于它；万一超时进程被杀，通知会停在「正在…」那一步，
 * 用户再填一次即可，不会留下半执行状态。
 */
public final class AdbPairInputReceiver extends BroadcastReceiver {

    /** 用户在通知里填「配对端口 + 6 位配对码」 */
    public static final String ACTION_PAIR = "com.termux.app.action.ADB_PAIR_INPUT";

    /** 用户在通知里填「连接端口」 */
    public static final String ACTION_CONNECT = "com.termux.app.action.ADB_CONNECT_INPUT";

    /** RemoteInput 的取值 key（两条通知共用一个，靠 action 区分是哪一步） */
    static final String KEY_INPUT = "adb_pair_input";

    /** 配对码固定 6 位：多一位少一位都算输错，不猜 */
    private static final int PAIR_CODE_LENGTH = 6;

    /** 端口上限（端口号 1-65535），下限由「不能为 0」保证 */
    private static final int MAX_PORT = 65535;

    /** 每段纯数字（其余字符一律当分隔符，所以 "192.168.1.5:37015" 会被切成 5 段） */
    private static final Pattern DIGITS = Pattern.compile("\\d+");

    /**
     * 点分十进制 IPv4 的形状（各段范围另由 {@link #isIpv4} 校验）。
     * 用户长按复制过来的整行里就带着它，捞出来当 host / 配对候选地址比一律用 loopback 靠谱。
     */
    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(?:\\.\\d{1,3}){3}");

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;

        final String action = intent.getAction();
        final String input = readInput(intent);
        final Context appContext = context.getApplicationContext();

        final PendingResult pendingResult = goAsync();
        new Thread(() -> {
            try {
                if (ACTION_PAIR.equals(action)) {
                    handlePair(appContext, input);
                } else if (ACTION_CONNECT.equals(action)) {
                    handleConnect(appContext, input);
                }
            } finally {
                pendingResult.finish();
            }
        }, "adb-manual-input").start();
    }

    /** 取出用户在通知里敲的内容（没有则空串） */
    private static String readInput(Intent intent) {
        Bundle results = RemoteInput.getResultsFromIntent(intent);
        if (results == null) return "";
        CharSequence text = results.getCharSequence(KEY_INPUT);
        return text == null ? "" : text.toString().trim();
    }

    // ==================== 配对 ====================

    /**
     * 后台执行配对；成功后把同一条通知换成「填连接端口」，并把用户送回 App。
     *
     * 与 {@link #handleConnect} 的关键差别：**配对不提前回 App**。系统「使用配对码配对设备」
     * 那个页面抱着配对会话，把人从那一页切走、不少 ROM 会就地关掉配对端口；而 adb pair 要等
     * 容器起来、好几秒后才真正发出去 —— 提前跳走等于自己把配对码作废掉（详见类注释）。
     * 所以配对期间用户就停在通知栏（通知原地显示「正在配对」），只在**成功之后**才
     * {@link #returnToApp}；失败也原地不动，通知换成重填提示 —— 他抬眼就是系统页上的新数字。
     */
    private static void handlePair(Context context, String input) {
        if (!MainActivity.isDebianInitialized()) {
            AdbPairNotifier.manualRetry(context, true,
                    context.getString(R.string.adb_not_initialized));
            return;
        }

        String[] pair = parsePair(input);
        if (pair == null) {
            // 认不出来就别执行：原地保留输入框并说清该怎么填，用户改一下再提交
            AdbPairNotifier.manualRetry(context, true,
                    context.getString(R.string.adb_manual_pair_bad_input));
            return;
        }

        final String port = pair[0];
        final String code = pair[1];

        // 先别跳：只把通知刷成「正在配对」（见方法注释 —— 这一跳会把配对会话带走）
        AdbPairNotifier.manualRunning(context, true, port);

        // 用户在通知栏里粘贴，我们没读屏；但粘进来的整行若带着配对页的 IP，就顺手拿它当候选
        // 地址之一 —— 有些 ROM 的配对端口只绑 wlan0，比只剩 loopback / 局域网 IP 多一条路
        final String output = AdbCmd.pair(port, code, parsePageIp(input));

        if (AdbCmd.isSuccess(output, "successfully paired")) {
            setHasPaired(context);
            // 配对大功告成，系统这才自己关掉配对页退到「无线调试」主页 —— 此时把人送回 App
            // 不会再打断任何会话。连接端口就在那一页顶部，人已经看不到了，所以除了把通知换成
            // 「填连接端口」，还得用 Toast 说清下一步：回那一页长按复制、回来粘贴。
            returnToApp(context);
            AdbPairNotifier.manualAskConnectPort(context, true);
            toast(context, context.getString(R.string.adb_manual_pair_ok_next));
        } else {
            // 失败留在原地：通知里的输入框还在，系统页也还在眼前，用户看一眼新端口/新码就能重填
            final String reason = context.getString(R.string.adb_manual_pair_failed,
                    outputOrUnknown(context, output));
            AdbPairNotifier.manualRetry(context, true, reason);
            toast(context, reason);
        }

        // 结果已定：主界面状态条刷一次 —— 配对状态变了，状态条上的提示文案跟着变
        AdbPairPanel.refreshActive();
    }

    /**
     * 拆「配对端口 + 配对码」。
     *
     * 取**最后两段**数字（而不是前两段）：这样用户把系统页上的整行
     * 「192.168.1.5:37015」粘进来再补配对码时，前面那几段 IP 会被跳过（那几段另有用途，
     * 见 {@link #parsePageIp}）。
     */
    static String[] parsePair(String input) {
        List<String> groups = digitGroups(input);

        // 一个分隔符都没敲的连写形态：端口 4-5 位 + 配对码 6 位 = 10-11 位，按「后 6 位是码」切
        if (groups.size() == 1) {
            String all = groups.get(0);
            if (all.length() >= 10 && all.length() <= 11) {
                String port = all.substring(0, all.length() - PAIR_CODE_LENGTH);
                if (isPort(port)) {
                    return new String[]{port, all.substring(all.length() - PAIR_CODE_LENGTH)};
                }
            }
            return null;
        }

        if (groups.size() < 2) return null;

        String code = groups.get(groups.size() - 1);
        String port = groups.get(groups.size() - 2);
        if (code.length() != PAIR_CODE_LENGTH || !isPort(port)) return null;
        return new String[]{port, code};
    }

    // ==================== 连接 ====================

    /** 后台执行连接；结果分类与自动流程保持一致（都是 {@link AdbCmd#connect} 给的结论） */
    private static void handleConnect(Context context, String input) {
        if (!MainActivity.isDebianInitialized()) {
            AdbPairNotifier.manualRetry(context, false,
                    context.getString(R.string.adb_not_initialized));
            return;
        }

        String address = parseAddress(input);
        if (address == null) {
            // 地址还没到手：不能把人提前拽回 App，保留输入框让他改
            AdbPairNotifier.manualRetry(context, false,
                    context.getString(R.string.adb_manual_connect_bad_port));
            return;
        }

        // 地址到手就够了：先把人送回 App，连接在后台跑，结果用 Toast 汇报。
        // 连接不像配对那样有会话要保（人在不在系统页都不影响握手），所以照旧提前跳。
        // 上面两个分支（容器没初始化 / 没认出地址）都已经返回，不会把人白拽一趟。
        returnToApp(context);

        AdbPairNotifier.manualRunning(context, false, address);
        final AdbCmd.ConnectResult result = AdbCmd.connect(address, context);

        // 结果已定：把主界面状态条刷一次 —— onResume 那次检测跑在连接完成之前，还停在「未连接」
        AdbPairPanel.refreshActive();

        if (result.success) {
            // 只记端口号（该 key 仅作记录）；host 可能是粘贴来的 IP，不必留
            saveConnectPort(context, address.substring(address.lastIndexOf(':') + 1));
            AdbPairNotifier.success(context, address);
            toast(context, context.getString(R.string.adb_notif_success, address));
            return;
        }

        if (result.needsRePair) {
            // 配对已失效：清掉标记并把通知换回配对输入，用户直接重新填一次配对信息
            setRePairNeeded(context);
            AdbPairNotifier.manualAskPairAgain(context);
            toast(context, context.getString(R.string.adb_auto_need_repair));
            return;
        }

        // 其余失败：原因留在通知里（输入框也留着，用户可改端口再试），同时 Toast 一份
        final String reason;
        if (result.needsAuth) {
            reason = context.getString(R.string.adb_hint_unauthorized);
        } else if (result.textSaysConnected) {
            reason = context.getString(R.string.adb_connect_not_ready);
        } else if (result.hint != null) {
            reason = result.hint;
        } else if (result.detail != null) {
            reason = context.getString(R.string.adb_connect_failed_detail, result.detail);
        } else {
            reason = context.getString(R.string.adb_connect_failed);
        }
        AdbPairNotifier.manualRetry(context, false, reason);
        toast(context, reason);
    }

    // ==================== 结果反馈 ====================

    /** 主线程 Handler：Toast 与 startActivity 都发到主线程（onReceive 里我们是在子线程干活） */
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /**
     * 把用户送回 App：端口已经拿到，剩下的 adb 连接在后台跑，结果用 Toast 报。
     *
     * 为什么能跳：这次广播是通知上「回复」的 PendingIntent 触发的，Android 10+ 对
     * 「收到系统 PendingIntent 后短时间内启动 Activity」有豁免；个别 ROM 拦下来也无妨 ——
     * 结果照样会发通知。
     */
    private static void returnToApp(Context context) {
        // 回 App 之前先把可能还在跑的无障碍自动流程停掉：它会读屏、自己在页面上翻页/点击，
        // 继续动手既会和用户的操作打架，也会把 App 进程主线程占满 —— 表现就是「应用无响应」
        // 弹窗（应用其实没崩）。手动流程不依赖无障碍，停了没有任何副作用。
        AdbPairAccessibilityService.cancelAuto();

        final Context appContext = context.getApplicationContext();
        MAIN.post(() -> {
            try {
                Intent intent = new Intent(appContext, MainActivity.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                appContext.startActivity(intent);
            } catch (Exception e) {
                // 后台启动被拦：忽略，结果仍会通过通知与 Toast 呈现
            }
        });
    }

    /** 结果反馈用 Toast（这时用户已经回到 App，Toast 不受 Android 11 的后台弹 Toast 限制） */
    private static void toast(Context context, String message) {
        if (TextUtils.isEmpty(message)) return;
        final Context appContext = context.getApplicationContext();
        MAIN.post(() -> {
            try {
                Toast.makeText(appContext, message, Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                // 通知权限等异常场景：忽略，结果已经在通知里留了一份
            }
        });
    }

    /**
     * 解析连接地址，返回 {@code host:port}；解析不出端口返回 null。
     *
     * 用户多半是**长按复制**系统页顶部那行「IP 地址和端口」再粘过来，所以这里尽量把 IP 也用上：
     *   · 有 IP → 用它当 host（那正是 adbd 在听的网卡地址，比 loopback 可靠）；
     *   · 只有端口 → 补 {@link AdbCmd#HOST}，即 loopback。
     * 两种写法 {@link AdbCmd#connect} 都会依次尝试（它自己还会补一个局域网地址兜底）。
     */
    static String parseAddress(String input) {
        String port = parsePort(input);
        if (port == null) return null;
        String ip = parsePageIp(input);
        return (ip == null ? AdbCmd.HOST : ip) + ":" + port;
    }

    /** 抽连接端口：取最后一段数字，允许用户直接粘「192.168.1.5:43911」整行 */
    static String parsePort(String input) {
        List<String> groups = digitGroups(input);
        if (groups.isEmpty()) return null;
        String port = groups.get(groups.size() - 1);
        return isPort(port) ? port : null;
    }

    /**
     * 从输入里抠出 IPv4（没有则 null）。
     *
     * 只服务于「用户粘了整行」这一种情况：配对时把配对页的 IP 交给 {@link AdbCmd#pair}
     * 当候选地址，连接时当 host。抠不到也没关系 —— 两条路各自都有 loopback 兜底。
     */
    static String parsePageIp(String input) {
        if (TextUtils.isEmpty(input)) return null;
        Matcher matcher = IPV4.matcher(input);
        while (matcher.find()) {
            String ip = matcher.group();
            if (isIpv4(ip)) return ip;
        }
        return null;
    }

    /** 是否是合法 IPv4（每段 0-255）：正则只管形状，范围得自己校验 */
    private static boolean isIpv4(String text) {
        for (String part : text.split("\\.")) {
            try {
                if (Integer.parseInt(part) > 255) return false;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }

    // ==================== 解析小工具 ====================

    /** 按连续数字把文本切成若干段（其余字符一律当分隔符） */
    private static List<String> digitGroups(String input) {
        List<String> groups = new ArrayList<>();
        if (TextUtils.isEmpty(input)) return groups;
        Matcher matcher = DIGITS.matcher(input);
        while (matcher.find()) {
            groups.add(matcher.group());
        }
        return groups;
    }

    /** 是否像端口：1-65535（已由 \d+ 保证是纯数字） */
    private static boolean isPort(String text) {
        if (text == null || text.isEmpty() || text.length() > 5) return false;
        int value;
        try {
            value = Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return false;
        }
        return value > 0 && value <= MAX_PORT;
    }

    /** 输出为空时给个可读的占位，别让通知里出现「null」 */
    private static String outputOrUnknown(Context context, String output) {
        String line = AdbCmd.firstMeaningfulLine(output);
        return TextUtils.isEmpty(line) ? context.getString(R.string.adb_auto_no_output) : line;
    }

    // ==================== 偏好（与主界面、自动流程共用同一份） ====================

    /** 记下「已配对过」：下次主界面点按钮可直接走连接步骤 */
    private static void setHasPaired(Context context) {
        prefs(context).edit().putBoolean(AdbPairPanel.KEY_HAS_PAIRED, true).apply();
    }

    /** 认证失效：把配对标记清掉，主界面状态条随之改回「请先配对」 */
    private static void setRePairNeeded(Context context) {
        prefs(context).edit().putBoolean(AdbPairPanel.KEY_HAS_PAIRED, false).apply();
    }

    /** 记录最近一次连接成功的端口（仅作记录；重连一律以系统页当前显示为准） */
    private static void saveConnectPort(Context context, String port) {
        prefs(context).edit().putString(AdbPairPanel.KEY_LAST_CONNECT_PORT, port).apply();
    }

    private static android.content.SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(AdbPairPanel.PREFS_NAME, Context.MODE_PRIVATE);
    }
}
