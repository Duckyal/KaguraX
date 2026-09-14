package com.termux.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;
import android.widget.Toast;

import com.termux.R;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AdbPairAccessibilityService —— 「配对助手」：自动读系统页 + 自动完成配对与连接。
 *
 * ## 为什么必须靠无障碍（而不是浮窗）
 * 配对端口、配对码、连接端口都只在系统「无线调试」页上显示，且每次开启无线调试都会重新
 * 随机生成，系统没有任何公开 API 能读到。原方案用悬浮窗把这些值展示给用户填，但
 * Android 12 起系统「设置」持有 HIDE_OVERLAY_WINDOWS 权限，会主动隐藏第三方悬浮窗
 * （TYPE_APPLICATION_OVERLAY，防点击劫持）—— 浮窗在无线调试页**完全不可见**，方案作废。
 *
 * 所以改成：本服务读屏 + 自动点击 + 后台执行 adb，进度通过无障碍浮层 + 通知汇报
 * （见 {@link #showIndicator}、{@link AdbPairNotifier}）。用户只需在 App 里点一次「配对 / 连接」。
 * 走不通时的手动兜底是「可填写通知」（{@link AdbPairInputReceiver}）—— 那一页上既看得见、
 * 又能输入的地方只剩通知栏，用户在那里照抄系统页上的数字。
 *
 * ## 进度为什么用无障碍浮层（而不是 Toast）
 * 通知要下拉状态栏才看得见，而人在系统页干等时不会去下拉，所以进度必须直接显示在屏幕上。
 * 但 Toast 也不行：Android 11 起 Toast 是应用进程自己往 WindowManager 挂 TYPE_TOAST 窗口，
 * 系统对非前台应用的这类窗口有限制（会被丢弃或延迟），无障碍服务虽然不会被回收，却没有
 * 可见的 Activity —— 实测在系统「无线调试」页上根本不出现。
 * 换成 TYPE_ACCESSIBILITY_OVERLAY 就没这个问题：那是系统给无障碍服务预留的通道
 * （TalkBack、开关控制这类工具全靠它给用户显示信息），既不需要 SYSTEM_ALERT_WINDOW 权限，
 * 也不在 HIDE_OVERLAY_WINDOWS 管辖内 —— 那条权限只封 TYPE_APPLICATION_OVERLAY。
 *
 * ## 自动流程（事件驱动状态机）
 * <pre>
 *   WAIT_HOME    等待/进入系统「无线调试」页
 *                · 停在开发者选项列表页 → 点「无线调试」进去
 *                  （Android 没有公开 action 能直达该页，App 侧只能尽量直启，
 *                    直不了就退到列表页，由这里点进去，见 clickWirelessDebuggingEntry）
 *                · 开关关着 → 替用户打开（关着时页面不显示端口，什么都不点会一直等）
 *                · 没配对过 → 先记下主页端口，再点「使用配对码配对设备」进配对页
 *                · 配对过   → 直接用页面顶部的「IP 地址和端口」连接
 *   WAIT_PAIR    配对页出现 → 读 6 位配对码 + 配对端口
 *   PAIRING      后台 adb pair
 *   WAIT_CONNECT 兜底路径（配对页当入口、没经过主页时）→ 等系统退回主页读连接端口
 *   CONNECTING   后台 adb connect（loopback 不通会自动回退局域网 IP）
 *   DONE / FAILED 通知留痕 + Toast 汇报结果
 * </pre>
 *
 * ## 信息一读到手就把用户送回 App（不再陪着系统页等结果）
 * 端口/配对码只在系统页上显示，读到手之后继续留在那一页就没有意义了 —— 用户只能干看着
 * adb 跑完（几秒到十几秒）。所以一旦确认「剩下的事都不再需要看系统页」就立刻回主界面
 * （见 {@link #returnToAppIfReady}），配对/连接改在后台跑，结果回来用 Toast 汇报。
 * 判据只有一条 —— 系统页不再需要了：
 *   · 重连：读到主页端口即可回；
 *   · 首次配对：主页端口在进配对页之前就记下了，所以读到配对码就能回；
 *   · 只有「用配对页当入口」那种 ROM（没经过主页）拿不到连接端口，才必须等配对完、
 *     系统退回主页读到端口再回。
 * 回到 App 后，onResume 那次状态检测跑在 adb 结果之前（此时还是「未连接」），
 * 所以结果出来时由 finishAuto / failAuto 调 {@link AdbPairPanel#refreshActive()} 补刷一次。
 *
 * ## 隐私与性能约束
 *   · 只有自动流程进行中才读屏，平时完全不工作；
 *   · 跳过本应用自己的窗口；
 *   · 只提取「IP:端口」与「6 位数字」两种模式，不保存任何页面文本。
 */
public final class AdbPairAccessibilityService extends AccessibilityService {

    private static final String TAG = "AdbPairHelper";

    /** 形如 192.168.1.100:37015（允许冒号两侧有空白，包括节点间换行） */
    private static final Pattern IP_PORT =
            Pattern.compile("(\\d{1,3}(?:\\.\\d{1,3}){3})\\s*:\\s*(\\d{2,5})");

    /** 独立的 6 位数字：无线调试配对码（前后不能再接数字，避免截取长数字的一部分） */
    private static final Pattern SIX_DIGITS = Pattern.compile("(?<!\\d)(\\d{6})(?!\\d)");

    /** 判定「使用配对码配对设备」页的线索词（必须同时有 6 位数字才算，否则主页会误判） */
    private static final String[] PAIR_PAGE_KEYS = {"配对码", "pairing code", "pair with code"};

    /** 「使用配对码配对设备」入口的文案（中/英），用于自动点击进入配对页 */
    private static final String[] PAIR_ENTRY_KEYS = {
            "使用配对码配对设备", "使用配对码配对",
            "pair device with pairing code", "pair with code"
    };

    /** 「无线调试」的文案（中/英）：既用于点进该页，也用于判断页面是否相关 */
    private static final String[] WIRELESS_DEBUG_KEYS = {"无线调试", "wireless debugging"};

    /**
     * 判定「当前就是无线调试页」而非「开发者选项列表页」的线索词。
     *
     * 必须靠这些**只在无线调试页出现**的文案区分：光看「无线调试」四个字不行 ——
     * 开发者选项列表页里也有这个条目，而部分 ROM 的列表项自带开关，会把
     * 「USB 调试」之类的开关误当成无线调试总开关点掉。
     */
    private static final String[] WIRELESS_PAGE_MARKERS = {
            "使用配对码配对设备", "pair device with pairing code", "pair with code",
            "IP 地址和端口", "IP address", "无线调试的 IP"
    };

    /**
     * 单次扫描的节点数上限：防御异常页面（如无限列表）导致的遍历爆炸。
     *
     * 它同时是「单次扫描耗时」的上限：本服务的读屏跑在 **App 进程的主线程**上，每次
     * getChild 都是一次跨进程 IPC，扫几千个节点能占住主线程好几秒 —— 用户这时在主界面上
     * 点一下，就会撞上系统的「应用无响应」窗口（应用其实没崩）。一屏通常只有几十个节点，
     * 800 已足够覆盖开发者选项这类长列表。
     */
    private static final int MAX_NODES = 800;

    /** 两次扫描的最小间隔（毫秒）：系统页滚动/输入时事件极密集，节流避免重复遍历 */
    private static final long MIN_SCAN_INTERVAL_MS = 500;

    /**
     * 「等页面 / 点页面」阶段的超时（毫秒）：覆盖跳到系统页、点入口、读配对码的全过程。
     *
     * 只管页面阶段：后台命令阶段（配对 / 连接）另有自己的 30 秒命令超时，不受它约束 ——
     * 连接要在多个候选地址上重试，硬按页面超时收尾会把正在跑、马上要成的连接结果丢掉。
     */
    private static final long AUTO_TIMEOUT_MS = 90000;

    /** 系统「设置」的包名：只在这个包里才动手翻页，别在桌面或别的应用上乱翻 */
    private static final String SETTINGS_PACKAGE = "com.android.settings";

    /** 超时检查的轮询间隔（毫秒）：页面长时间没事件时也要能发现超时 */
    private static final long TIMEOUT_POLL_MS = 3000;

    /** 配对成功后等系统自动关掉配对页的时间（毫秒），超过则主动按返回 */
    private static final long BACK_AFTER_PAIR_MS = 2500;

    /**
     * 在开发者选项列表里最多往下翻几次。
     *
     * 为什么需要翻：「无线调试」在开发者选项列表里排在偏下的位置（"调试"分组里），而系统
     * 无障碍只能看到**当前屏幕上可见**的节点 —— 不翻就永远找不到它，用户得自己滑到那一屏
     * 才点得动（这正是"等我滑到了它才知道点"的原因）。
     * 设上限是兜底：万一压根不在开发者选项页（页面结构异常），翻到底就该停，不能一直滑到超时。
     */
    private static final int MAX_DEV_SCROLLS = 15;

    /** 自动流程步骤 */
    private enum Step {
        IDLE,          // 空闲：未在自动流程中
        WAIT_HOME,     // 等待「无线调试」主页
        WAIT_PAIR,     // 已点配对入口，等待配对页出现
        PAIRING,       // adb pair 执行中
        WAIT_CONNECT,  // 配对成功，等待回到主页读连接端口
        CONNECTING,    // adb connect 执行中
        DONE,          // 已连接成功
        FAILED         // 失败（等下一次点按钮重新开始）
    }

    /** 当前服务实例；无障碍未开启时为 null（自动流程入口据此判断可用性） */
    private static volatile AdbPairAccessibilityService sInstance;

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    /** 上次扫描时间（节流用） */
    private long mLastScanAt;

    // ---------- 自动流程状态 ----------
    private Step mStep = Step.IDLE;
    private boolean mNeedPair;            // 本次是否需要先配对（= 从未配对过）
    private String mPairIp;               // 系统配对页显示的 IP
    private String mPairPort;             // 系统配对页显示的配对端口
    private String mPairCode;             // 6 位配对码
    private String mConnectPort;          // 「无线调试」主页显示的连接端口
    private boolean mPairEntryClicked;    // 已点过「使用配对码配对设备」/ 已尝试过
    private boolean mDevEntryClicked;     // 已点过开发者选项列表里的「无线调试」条目
    private int mDevScrollCount;          // 在开发者选项列表里往下翻的次数（上限 MAX_DEV_SCROLLS）
    private boolean mSwitchToggled;       // 已尝试过打开「无线调试」总开关
    private boolean mLastScanOnPairPage;  // 最近一次扫描是否在配对页
    private long mAutoDeadline;           // 自动流程超时时刻
    private boolean mRepairAttempted;     // 本轮是否已自动重新配对过一次（防在同一堵墙上反复撞）

    /**
     * 是否已把用户送回 App（见 {@link #returnToAppIfReady}）。三个用途：
     * 避免重复跳转；决定反馈走 Toast（人已回来）还是无障碍浮层（人还停在系统页）；
     * 以及回到 App 后**彻底停掉无障碍动作**（见 {@link #onAccessibilityEvent}）。
     */
    private boolean mReturnedToApp;

    /**
     * 配对成功后「系统若没自己退回主页，就替用户按一次返回」的延迟任务。
     *
     * 必须持有引用：回到 App 时（{@link #returnToApp}）得把它撤掉 —— 它的判据是「最近一次
     * 扫描在配对页」，而配对成功后人已被送回 App、{@link #mLastScanOnPairPage} 仍停在 true，
     * 2.5 秒后这一下返回键就按在**主界面**上，会把用户直接按出应用。
     */
    private final Runnable mBackAfterPairTask = () -> {
        if (mReturnedToApp) return;                                     // 已回 App：绝不能再按
        if (mStep == Step.WAIT_CONNECT && mLastScanOnPairPage) {
            performGlobalAction(GLOBAL_ACTION_BACK);
        }
    };

    // ==================== 生命周期 ====================

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        sInstance = null;
        removeIndicator();   // 服务断开后不能再持有窗口，否则窗口泄漏
        super.onUnbind(intent);
        return false;
    }

    @Override
    public void onDestroy() {
        sInstance = null;
        resetAuto();
        removeIndicator();
        super.onDestroy();
    }

    @Override
    public void onInterrupt() {
        // 服务被系统临时中断（如用户关闭无障碍）：无需处理，恢复后事件会继续送达
    }

    // ==================== 无障碍事件分发 ====================

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        // 只有自动流程进行中才读屏：平时完全不工作，不产生隐私面与耗电
        if (!isAutoActive()) return;

        // 人已经回到 App：立刻停掉一切无障碍动作（读屏 / 点击 / 翻页 / 按返回）。
        // 两个理由：
        //   1. 回 App 之后用户是在自家界面上操作，无障碍再动手就是抢用户的点击；
        //   2. 读屏跑在 App 进程主线程上，继续扫别的应用会把主线程占满 —— 用户在主界面上
        //      随便点一下就会撞出「应用无响应」窗口（应用其实没崩）。
        // 后台 adb 的结果回调照常处理（只发通知/Toast，不碰无障碍）。
        if (mReturnedToApp) return;

        // 只扫系统「设置」：端口与配对码只在那里显示。别在桌面、通知栏或别的应用上白扫 ——
        // 每次扫描都是跨进程遍历节点树，扫无关应用纯属平白拖慢 App 主线程。
        if (!isSettingsPackage(event.getPackageName())) return;

        // 节流：系统页滚动/输入时事件极密集，避免重复遍历
        long now = System.currentTimeMillis();
        if (now - mLastScanAt < MIN_SCAN_INTERVAL_MS) return;
        mLastScanAt = now;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        if (!isSettingsPackage(root.getPackageName())) return;

        handleAutoPage(root);
    }

    /**
     * 这个窗口是不是系统「设置」的。
     *
     * 为什么必须过滤：本服务的读屏跑在 App 进程主线程上，扫一个无关应用（桌面、通知栏、
     * 系统 UI）就是白白占住主线程几百次跨进程调用 —— 用户这时点界面就会撞出「应用无响应」。
     * 而端口与配对码只可能出现在「设置」里，别处一扫都是白扫。
     *
     * 用 contains 而不是精确等于 {@link #SETTINGS_PACKAGE}：厂商把设置包改名的变体不少
     * （com.samsung.android.settings 之类），一律放过，总比让自动流程在某台 ROM 上彻底失灵强。
     */
    private static boolean isSettingsPackage(CharSequence pkg) {
        return pkg != null && pkg.toString().toLowerCase().contains("settings");
    }

    // ==================== 自动流程：入口与状态管理 ====================

    /** 无障碍服务是否已连接（自动流程可用的前提） */
    static boolean isAutoReady() {
        return sInstance != null;
    }

    /**
     * 启动自动流程。必须在跳到系统「无线调试」页**之前**调用（先开始监听，再跳页）。
     *
     * @param needPair true = 首次配对（需点进配对页取码）；false = 已配对过，只需重连
     */
    static void startAuto(boolean needPair) {
        AdbPairAccessibilityService service = sInstance;
        if (service == null) return;
        service.mMainHandler.post(() -> service.beginAuto(needPair));
    }

    /** 中止自动流程（用户改用手动填写时调用：手动填的数不能被自动点击/读屏搅乱） */
    static void cancelAuto() {
        AdbPairAccessibilityService service = sInstance;
        if (service == null) return;
        service.mMainHandler.post(service::resetAuto);
    }

    /** 自动流程是否仍在进行中 */
    private boolean isAutoActive() {
        return mStep != Step.IDLE && mStep != Step.DONE && mStep != Step.FAILED;
    }

    /** 主线程：开始一次自动流程 */
    private void beginAuto(boolean needPair) {
        mNeedPair = needPair;
        mPairIp = null;
        mPairPort = null;
        mPairCode = null;
        mConnectPort = null;
        mPairEntryClicked = false;
        mDevEntryClicked = false;
        mDevScrollCount = 0;
        mSwitchToggled = false;
        mLastScanOnPairPage = false;
        mRepairAttempted = false;
        mReturnedToApp = false;
        mLastScanAt = 0;                 // 允许下一条事件立即扫描，不等节流
        mAutoDeadline = System.currentTimeMillis() + AUTO_TIMEOUT_MS;
        mStep = Step.WAIT_HOME;

        Log.i(TAG, "自动流程开始，needPair=" + needPair);
        report(getString(R.string.adb_auto_waiting_page));
        scheduleTimeoutPoll();
    }

    /** 主线程：结束自动流程，回到「平时不读屏」的状态 */
    private void resetAuto() {
        if (mStep != Step.IDLE) {
            Log.i(TAG, "自动流程结束，最终状态=" + mStep);
        }
        mStep = Step.IDLE;
        mMainHandler.removeCallbacks(mTimeoutPoll);
        mMainHandler.removeCallbacks(mBackAfterPairTask);   // 流程结束了就别再按返回
    }

    /** 超时轮询：页面长时间没有事件时也能发现超时，避免流程永远挂着 */
    private final Runnable mTimeoutPoll = () -> {
        if (!isAutoActive()) return;
        if (pageDeadlineExceeded()) {
            failAuto(getString(R.string.adb_auto_timeout));
            return;
        }
        scheduleTimeoutPoll();
    };

    private void scheduleTimeoutPoll() {
        mMainHandler.removeCallbacks(mTimeoutPoll);
        mMainHandler.postDelayed(mTimeoutPoll, TIMEOUT_POLL_MS);
    }

    /**
     * 「等页面」阶段是否已超时。
     *
     * 后台命令阶段（PAIRING / CONNECTING）刻意不算：那时人已经不需要页面了，命令自己也有
     * 30 秒命令超时兜底。若在这里按页面超时收尾，会把一个正在进行、马上要成功的配对/连接
     * 判死 —— 而且结果回调进来时 mStep 已不是 PAIRING/CONNECTING，会被直接丢弃。
     */
    private boolean pageDeadlineExceeded() {
        if (mStep == Step.PAIRING || mStep == Step.CONNECTING) return false;
        return System.currentTimeMillis() > mAutoDeadline;
    }

    /** 主线程：自动流程失败，通知用户（并提示可改手动填写） */
    private void failAuto(String reason) {
        Log.w(TAG, "自动流程失败：" + reason);
        mStep = Step.FAILED;
        mMainHandler.removeCallbacks(mTimeoutPoll);
        AdbPairNotifier.fail(this, reason);
        // 还停在系统页就用浮层（他就在那儿，别让他非得下拉通知栏才知道失败）；
        // 已经回到 App 就用 Toast（浮层盖在自家界面上反而多余）
        notifyResult(reason);

        // 收尾：结束自动流程（不再读屏/点击），并把用户拉回 App。
        // 停在系统页上没有意义 —— 那一页本身不会有任何变化，浮层几秒后也会自己消失。
        resetAuto();
        returnToApp();
        AdbPairPanel.refreshActive();   // 结果已定，把主界面状态条同步一次
    }

    // ==================== 自动流程：事件驱动主体 ====================

    /** 主线程：按当前页面内容推进自动流程 */
    private void handleAutoPage(AccessibilityNodeInfo root) {
        if (pageDeadlineExceeded()) {
            failAuto(getString(R.string.adb_auto_timeout));
            return;
        }

        List<String> texts = new ArrayList<>();
        collectTexts(root, texts);
        if (texts.isEmpty()) return;

        // 节点间用换行连接：部分 ROM 把「192.168.1.100」与「:37015」拆成两个节点，
        // 换行也属于 \s，IP_PORT 里的 \s* 仍能跨节点匹配上
        String joined = TextUtils.join("\n", texts);

        String[] ipPort = firstIpPort(joined);
        String ip = ipPort == null ? null : ipPort[0];
        String port = ipPort == null ? null : ipPort[1];
        String code = containsAny(joined, PAIR_PAGE_KEYS) ? firstSixDigits(joined) : null;
        boolean onPairPage = code != null;
        mLastScanOnPairPage = onPairPage;

        switch (mStep) {
            case WAIT_HOME:
                if (onPairPage) {
                    // 页面本身就是配对页（部分 ROM 直达）：跳过点入口，直接用
                    readPairAndStart(ip, port, code);
                } else if (port != null) {
                    // 主页这行「IP 地址和端口」的端口就是连接端口，先记下来：
                    // 这样配对完就不用再回主页读它，人也就能提前回 App（见 returnToAppIfReady）
                    mConnectPort = port;
                    if (mNeedPair) {
                        clickPairEntry(root);   // 再进配对页读配对码
                    } else {
                        startConnect();
                    }
                } else if (isOnWirelessDebuggingPage(joined)) {
                    // 已经在无线调试页却没有端口：只能是总开关还关着（关着就不显示端口）
                    tryToggleWirelessDebugging(root, joined);
                } else {
                    // 多半还停在开发者选项列表页：替用户点进「无线调试」
                    clickWirelessDebuggingEntry(root);
                }
                break;

            case WAIT_PAIR:
                if (onPairPage) {
                    readPairAndStart(ip, port, code);
                }
                break;

            case WAIT_CONNECT:
                if (!onPairPage && port != null) {
                    mConnectPort = port;
                    startConnect();
                }
                break;

            default:
                // PAIRING / CONNECTING / DONE / FAILED：等后台结果，无需处理页面
                break;
        }
    }

    /**
     * 「无线调试」总开关关着时替用户打开。
     *
     * 关着时页面上不会显示「IP 地址和端口」，只等的话流程会一直挂到超时，所以主动点一下。
     * 只在当前页面确实是无线调试相关页、且存在未勾选的开关时才动手，避免误点别的设置项。
     * 不论点击成败都只尝试一次（{@link #mSwitchToggled}），防止开关状态反复时来回点。
     */
    private void tryToggleWirelessDebugging(AccessibilityNodeInfo root, String joined) {
        if (mSwitchToggled) return;
        if (!containsAny(joined, WIRELESS_DEBUG_KEYS)) return;

        AccessibilityNodeInfo switchNode = findUncheckedSwitch(root);
        if (switchNode == null) return;

        mSwitchToggled = true;
        if (clickNode(switchNode)) {
            Log.i(TAG, "已替用户打开「无线调试」开关");
            report(getString(R.string.adb_auto_enabling));
        }
    }

    /**
     * 从开发者选项列表页点进「无线调试」。
     *
     * 为什么需要它：Android 没有公开 action 能直达无线调试页 —— App 侧虽然会尽量直接启
     * Settings 的 WifiDebuggingActivity，但部分 ROM 禁止第三方启动，只能退到开发者选项
     * 列表页（见 AdbPairPanel.launchWirelessDebuggingPage）。那时若没人点，流程就会一直
     * 停在列表页直到超时，所以这里替用户点一下。
     *
     * 点击成功才置位 {@link #mDevEntryClicked}：点不动（节点不可点）时下次事件还能再试。
     */
    private void clickWirelessDebuggingEntry(AccessibilityNodeInfo root) {
        if (mDevEntryClicked) return;

        AccessibilityNodeInfo entry = findNodeByKey(root, WIRELESS_DEBUG_KEYS);
        if (entry != null) {
            if (clickNode(entry)) {
                mDevEntryClicked = true;
                Log.i(TAG, "已点击开发者选项列表里的「无线调试」");
                report(getString(R.string.adb_auto_entering_wireless));
            }
            return;
        }

        scrollTowardWirelessEntry(root);
    }

    /**
     * 「无线调试」没在当前屏幕上时，替用户把列表往下翻。
     *
     * 无障碍只能拿到当前可见的节点，而「无线调试」在开发者选项列表里排得偏下 —— 不翻的话
     * 用户得自己滑到那一屏服务才看得见它（这正是"等我滑到了它才知道点"的原因）。
     * 每翻一屏页面会刷新并送来新事件，下一轮扫描就能看到新露出来的条目，所以这里一次只翻
     * 一屏、由事件驱动往前推进，不需要在循环里翻到底。
     */
    private void scrollTowardWirelessEntry(AccessibilityNodeInfo root) {
        if (mDevScrollCount >= MAX_DEV_SCROLLS) return;

        // 只在系统「设置」里翻：点完按钮到跳页生效之前，用户可能还停在桌面或本应用上，
        // 在那儿翻会变成翻桌面 / 翻列表，纯属捣乱。
        if (!isSettingsPackage(root.getPackageName())) return;

        if (!scrollListForward(root)) return;

        mDevScrollCount++;
        Log.i(TAG, "「无线调试」不在当前屏，已向下翻第 " + mDevScrollCount + " 次");
        if (mDevScrollCount == 1) {
            // 只提示一次：翻页是过程，别把浮层刷成滚动计数器
            showIndicator(getString(R.string.adb_auto_scrolling_to_entry), INDICATOR_MS);
        }
    }

    /**
     * 当前页面是否**就是**无线调试页（而不是开发者选项列表页）。
     * 判据见 {@link #WIRELESS_PAGE_MARKERS}。
     */
    private boolean isOnWirelessDebuggingPage(String joined) {
        return containsAny(joined, WIRELESS_PAGE_MARKERS);
    }

    /**
     * 点「使用配对码配对设备」进入配对页。
     *
     * 找不到入口 / 点不动时退化为「直接连接」：可能是上次已配对成功、只是用户又点了一次按钮，
     * 此时页面上没有该入口，直接用主页端口连一次才是对的（连接端口已在 WAIT_HOME 记下）。
     * 只尝试一次（{@link #mPairEntryClicked}），避免页面刷新时反复点击。
     */
    private void clickPairEntry(AccessibilityNodeInfo root) {
        if (mPairEntryClicked) return;
        mPairEntryClicked = true;

        AccessibilityNodeInfo entry = findNodeByKey(root, PAIR_ENTRY_KEYS);
        if (entry != null && clickNode(entry)) {
            Log.i(TAG, "已点击「使用配对码配对设备」");
            mStep = Step.WAIT_PAIR;
            report(getString(R.string.adb_auto_entering_pair));
            return;
        }

        Log.w(TAG, "未找到配对入口，退化为直接连接");
        // 退化后这一轮不再需要配对：清掉标记，否则 returnToAppIfReady 会因为
        // 「缺配对码」而不肯把人提前送回 App
        mNeedPair = false;
        startConnect();
    }

    /** 配对页：读到端口与配对码就发起配对 */
    private void readPairAndStart(String ip, String port, String code) {
        if (port == null || code == null) return;
        mPairIp = ip;
        mPairPort = port;
        mPairCode = code;
        startPair();
    }

    // ==================== 自动流程：后台执行 ====================

    /**
     * 后台执行 adb pair（配对码配对）。
     *
     * 配对期间**故意不**把用户送回 App —— 这一点和 {@link #startConnect} 恰好相反。
     *
     * 根子在于系统「使用配对码配对设备」那一页抱着配对会话（AOSP 是它拉起的
     * AdbPairingService 在监听配对端口）：把人从那一页切走，不少 ROM 会就地关掉配对端口，
     * 而 adb pair 要等容器起来、好几秒后才真正发出去 —— 提前跳走等于自己把配对码作废，
     * 表现就是「码明明没敲错却配对不上」。所以配对期间人就留在那一页看进度浮层，
     * 只在**配对成功之后**才由 onPairSucceeded → startConnect 顺势把人送回去。
     * （手动流程同理，见 AdbPairInputReceiver.handlePair 的说明。）
     */
    private void startPair() {
        mStep = Step.PAIRING;

        final String port = mPairPort;
        final String ip = mPairIp;
        final String code = mPairCode;

        Log.i(TAG, "开始配对：端口=" + port + " 配对码=" + code);
        // 长时长：配对要等容器起来 + adb 握手，常常超过默认那 6 秒（见 INDICATOR_LONG_MS）
        report(getString(R.string.adb_auto_pairing, code), INDICATOR_LONG_MS);

        new Thread(() -> {
            final String output = AdbCmd.pair(port, code, ip);
            final boolean paired = AdbCmd.isSuccess(output, "successfully paired");
            mMainHandler.post(() -> {
                if (mStep != Step.PAIRING) return;   // 已被取消 / 已超时
                if (paired) {
                    onPairSucceeded();
                } else {
                    failAuto(getString(R.string.adb_auto_pair_failed, outputOrUnknown(output)));
                }
            });
        }, "adb-pair").start();
    }

    /** 配对成功（主线程）：接着去连接（连接端口已在进配对页之前记下） */
    private void onPairSucceeded() {
        setHasPaired(true);

        // 常规路径：连接端口在进配对页之前就从主页记下了，直接连 —— 不必再等系统页，
        // 人也早已回到 App（见 startPair 里的 returnToAppIfReady）
        if (mConnectPort != null) {
            report(getString(R.string.adb_auto_pair_ok_connecting));
            startConnect(false);   // 「正在连接」上面刚播报过，别再叠一条
            return;
        }

        // 兜底：配对页当入口的 ROM（没经过主页）拿不到连接端口，只能等系统退回主页再读
        mStep = Step.WAIT_CONNECT;
        Log.i(TAG, "配对成功，等待回到无线调试主页读连接端口");
        report(getString(R.string.adb_auto_pair_ok), INDICATOR_LONG_MS);

        // 配对成功后系统通常会自动关掉配对页退回主页；若仍停在配对页，主动返回一次。
        // 判断条件与「回到 App 时撤销」都收在 mBackAfterPairTask 里。
        mMainHandler.removeCallbacks(mBackAfterPairTask);
        mMainHandler.postDelayed(mBackAfterPairTask, BACK_AFTER_PAIR_MS);
    }

    /** 后台执行 adb connect */
    private void startConnect() {
        startConnect(true);
    }

    /**
     * 后台执行 adb connect。
     *
     * @param announce 是否先播报一条「正在连接（端口 x）…」。配对刚成功时传 false：
     *                 那时已经播报过「配对成功，正在连接…」，再来一条同义的纯属刷屏。
     */
    private void startConnect(boolean announce) {
        mStep = Step.CONNECTING;

        // 端口到手就够了：先回 App 再后台连，别让用户陪着系统页等结果
        returnToAppIfReady();

        final String port = mConnectPort;

        Log.i(TAG, "开始连接：端口=" + port);
        if (announce) {
            report(getString(R.string.adb_auto_connecting, port));
        }

        new Thread(() -> {
            final AdbCmd.ConnectResult result =
                    AdbCmd.connect(AdbCmd.HOST + ":" + port, getApplicationContext());
            mMainHandler.post(() -> {
                if (mStep != Step.CONNECTING) return;   // 已被取消 / 已超时
                if (result.success) {
                    finishAuto(AdbCmd.HOST + ":" + port);
                } else if (result.needsAuth) {
                    failAuto(getString(R.string.adb_auto_need_auth));
                } else if (result.textSaysConnected) {
                    // adb 文本回了 connected to、但设备始终没进 adb devices（常见于端口刚失效 /
                    // 无线调试正在重启）。这不是「网络不通」，别把用户往查网络上带 —— 手动流程
                    // 那边一直有这么一条分支，自动流程漏了，结果只能回显那句自相矛盾的
                    // "连接失败：connected to 127.0.0.1:33835"
                    failAuto(getString(R.string.adb_connect_not_ready));
                } else if (result.needsRePair) {
                    // 认证已失效：标记必须一起清掉，光报错没用。否则下次点按钮时 startAuto(!hasPaired())
                    // 仍拿到 false，又会跳过配对直接连接，在同一堵墙上反复撞 —— 手动流程那边是靠
                    // setRePairNeeded 清标记自愈的，自动流程不能只报错
                    setHasPaired(false);

                    // 能自己救的场面别丢给用户：adbd 在无线调试关开后会换掉 TLS 证书，而客户端
                    // 按 guid 存下的旧指纹永远匹配不上（握手被 CERTIFICATE_UNKNOWN 掐断，真原因
                    // 只在 server 的 trace 里，客户端只看到一句没原因的 failed to connect）。
                    // 这种失败重走一次配对就能恢复（AdbCmd.connect 的 stalePairing 判定），
                    // 所以直接自己重配，而不是让用户再点一次按钮。
                    if (!mRepairAttempted) {
                        Log.i(TAG, "判定配对已失效，自动重新配对一次");
                        report(getString(R.string.adb_auto_repairing));
                        restartPairingFlow();
                        return;   // 不发失败通知：用户看到的是「正在重新配对」
                    }
                    failAuto(getString(R.string.adb_auto_need_repair));
                } else {
                    failAuto(getString(R.string.adb_auto_connect_failed, outputOrUnknown(result.detail)));
                }
            });
        }, "adb-connect").start();
    }

    /**
     * 配对失效后的自救：原地把自动流程重跑一遍，这轮走完整配对（needPair=true）。
     *
     * 顺序与 App 里的「配对 / 连接」入口一致 —— 先 beginAuto 开始监听、再跳系统页：
     * 无障碍事件是流程的唯一驱动，页面没跳过去就收不到事件，流程会一直干等到超时。
     *
     * 只跳「开发者选项」列表页而不直达无线调试页：Android 没有公开 action 能直达后者，
     * 列表页那一跳各 ROM 都认，剩下的「点进无线调试」本来就是自动流程的活儿
     * （见 {@link #clickWirelessDebuggingEntry}）。
     */
    private void restartPairingFlow() {
        beginAuto(true);
        // beginAuto 会重置 mRepairAttempted，所以标记必须写在它之后，否则本轮就失去防重入
        mRepairAttempted = true;
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            // 个别 ROM 会拦后台跳转：跳不过去就按普通失败收尾，别把流程挂在这儿等超时
            Log.w(TAG, "重新配对时跳系统页失败，按失败收尾", e);
            failAuto(getString(R.string.adb_auto_need_repair));
        }
    }

    /** 连接成功：记住端口、通知用户、收工 */
    private void finishAuto(String address) {
        saveConnectPort(mConnectPort);
        mStep = Step.DONE;
        Log.i(TAG, "连接成功：" + address);
        AdbPairNotifier.success(this, address);
        notifyResult(getString(R.string.adb_notif_success, address));
        resetAuto();
        returnToApp();                  // 幂等：通常早就回来了，这里只是收尾
        AdbPairPanel.refreshActive();   // 结果已出：把主界面状态条刷成「已连接」
    }

    /**
     * 信息一读到手就把用户送回 App（幂等：已经回来过就什么都不做）。
     *
     * 判据只有一条 —— **系统页不再需要了**：
     *   · 重连：读主页「IP 地址和端口」即可；
     *   · 首次配对：主页端口在进配对页之前就已记下（见 handleAutoPage 的 WAIT_HOME 分支），
     *     所以读到配对码就够了；
     *   · 只有「用配对页当入口」那种 ROM（没经过主页）拿不到连接端口，那就得等配对完、
     *     系统退回主页读到端口才算齐（见 onPairSucceeded 的兜底分支）。
     * 之后的 adb pair / connect 全在后台跑，结果由 finishAuto / failAuto 用 Toast 汇报。
     */
    private void returnToAppIfReady() {
        if (mConnectPort == null) return;                                    // 还差连接端口
        if (mNeedPair && (mPairPort == null || mPairCode == null)) return;   // 还差配对码
        returnToApp();
    }

    /**
     * 把用户从系统页拉回 App。
     *
     * 为什么必须主动跳：流程一结束（或信息已读齐），用户还停在系统「无线调试」页上 ——
     * 那一页本身不会有任何变化，留在那儿等于什么都没看见，还得自己按返回键一层层退回桌面
     * 再重新点图标进来。这里直接拉起主界面收尾，成功与失败都要走（失败时通知里只是原因，
     * 用户还得回 App 点「手动填写」兜底）。
     *
     * Android 10+ 限制后台应用启动 Activity，但无障碍服务属于豁免情形（系统绑定、代表用户
     * 操作），可以正常拉起。MainActivity 是 singleTask，这里不会重建，只会走 onNewIntent
     * 并触发 onResume —— 顺带把状态条刷新一遍（AdbPairPanel.refresh）。
     *
     * 注意：这次 onResume 里的状态检测跑在 adb 结果之前，那时状态条还是「未连接」；
     * 结果出来后由 finishAuto / failAuto 调 {@link AdbPairPanel#refreshActive()} 补刷一次。
     */
    private void returnToApp() {
        if (mReturnedToApp) return;   // 收尾路径可能重复走到，别重复跳
        mReturnedToApp = true;

        // 人回到 App = 这一趟的「页面活儿」到此为止：撤掉进度浮层，撤掉还没执行的无障碍
        // 动作（尤其是配对成功后的那一下返回键）。之后再送来的事件由 onAccessibilityEvent
        // 直接丢弃 —— 不在自家界面上读屏/点击，也就不会再把 App 主线程占满
        // （这正是「弹应用无响应、但应用没崩」的根源）。
        removeIndicator();
        mMainHandler.removeCallbacks(mBackAfterPairTask);

        try {
            Intent intent = new Intent(this, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
        } catch (Exception e) {
            // 个别 ROM 会拦后台跳转：失败也不影响配对结果，通知栏里已经留了结果
            Log.w(TAG, "跳回应用失败", e);
        }
    }

    /** 后台输出为空时给个可读的占位，避免通知里出现「null」 */
    private String outputOrUnknown(String output) {
        String line = AdbCmd.firstMeaningfulLine(output);
        return TextUtils.isEmpty(line) ? getString(R.string.adb_auto_no_output) : line;
    }

    // ==================== 进度反馈（无障碍浮层 + 通知） ====================

    /**
     * 进度提示条停留时长（毫秒）。
     * 刻意给足 6 秒：用户是在 App 里点完按钮才跳到系统页的，跳页本身就要一两秒，
     * 加上眼睛从 App 挪到系统页的时间，太短会「刚好错过」。
     */
    private static final long INDICATOR_MS = 6000;

    /** 失败提示停留时长（毫秒）：原因长，还得留出读完的时间 */
    private static final long INDICATOR_FAIL_MS = 10000;

    /** 进度提示条（无障碍浮层）；null = 还没建或已被撤掉 */
    private TextView mIndicator;

    /** 到点自动隐藏提示条 */
    private final Runnable mHideIndicator = () -> {
        if (mIndicator != null) {
            mIndicator.setVisibility(View.GONE);
        }
    };

    /**
     * 统一的进度汇报：通知栏留痕 + 就近提示（浮层 或 Toast，见下）。
     *
     * 双份的原因：通知要下拉状态栏才看得见，而人在系统页干等时不会去下拉；
     * 浮层直接浮在当前页面上方，是唯一「不用操作就能看见」的反馈方式。
     *
     * 但「人已经回到 App」之后分工就反过来了：浮层会盖在自家界面上变成一块多余的浮窗，
     * 而 Toast 正当时（App 在前台，Toast 不受 Android 11 的后台弹 Toast 限制）。
     */
    /** 后台步骤（配对 / 连接）的提示停留时长（毫秒）：本身要跑好几秒，6 秒那档会提前消失 */
    private static final long INDICATOR_LONG_MS = 20000;

    private void report(String message) {
        report(message, INDICATOR_MS);
    }

    /**
     * 带自定义停留时长的进度汇报（其余同 {@link #report(String)}）。
     *
     * 配对 / 连接这类「后台要跑好几秒」的步骤用它：默认那 6 秒是给「刚跳到系统页，告诉用户
     * 我在这儿」这种瞬时提示定的，拿来当配对进度会提前消失 —— 用户眼里就是「浮层没了、
     * 页面也没变」，以为流程挂了。
     */
    private void report(String message, long durationMs) {
        AdbPairNotifier.progress(this, message);
        if (mReturnedToApp) {
            toast(message);
            return;
        }
        showIndicator(message, durationMs);
    }

    /**
     * 结果反馈：人已经回到 App 就用 Toast，人还停在系统页才用无障碍浮层。
     * （两者的分工同上，见 {@link #report}。）
     */
    private void notifyResult(String message) {
        if (mReturnedToApp) {
            toast(message);
        } else {
            showIndicator(message, INDICATOR_FAIL_MS);
        }
    }

    /**
     * 在系统页上方显示一条实时提示。
     *
     * 为什么不用 Toast：Android 11 起 Toast 由应用进程自己往 WindowManager 挂 TYPE_TOAST 窗口，
     * 系统对非前台应用的这类窗口有限制（丢弃或延迟）。无障碍服务虽然没有被回收，但它并没有
     * 可见的 Activity —— 实测在系统「无线调试」页上 Toast 根本不出现，所以不能指望它。
     *
     * 换成 TYPE_ACCESSIBILITY_OVERLAY 就没这个问题：这是系统给无障碍服务预留的通道
     * （TalkBack、开关控制这些工具全靠它给用户显示信息），添加窗口不需要 SYSTEM_ALERT_WINDOW，
     * 也不在 HIDE_OVERLAY_WINDOWS 管辖内 —— 那条权限只封 TYPE_APPLICATION_OVERLAY。
     *
     * 坑：必须用服务自己的 context 取 WindowManager。AccessibilityService 在 attachBaseContext
     * 时就把自己的 context 包成了一枚 TYPE_ACCESSIBILITY_OVERLAY 的 window context，
     * 换成 application context 拿到的 WindowManager 没有这枚 token，addView 会直接失败。
     */
    private void showIndicator(String message, long durationMs) {
        if (TextUtils.isEmpty(message)) return;

        if (!ensureIndicator()) {
            // 极少数 ROM 不给无障碍服务开浮层：退回 Toast（多半也看不到，聊胜于无）
            toast(message);
            return;
        }

        mIndicator.setText(message);
        mIndicator.setVisibility(View.VISIBLE);

        mMainHandler.removeCallbacks(mHideIndicator);
        mMainHandler.postDelayed(mHideIndicator, durationMs);
    }

    /** 首次显示时创建并挂上提示条窗口；窗口建不出来返回 false */
    private boolean ensureIndicator() {
        if (mIndicator != null) return true;

        WindowManager windowManager = getSystemService(WindowManager.class);
        if (windowManager == null) return false;

        float density = getResources().getDisplayMetrics().density;
        int paddingH = (int) (16 * density);
        int paddingV = (int) (10 * density);

        TextView view = new TextView(this);
        view.setBackgroundResource(R.drawable.bg_pair_indicator);
        view.setTextColor(Color.WHITE);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        view.setPadding(paddingH, paddingV, paddingH, paddingV);
        view.setGravity(Gravity.CENTER);
        view.setMaxLines(8);
        view.setMaxWidth((int) (320 * density));   // 长原因换行显示，别横着铺满整个屏幕

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                // 不抢焦点、不吃触摸：浮在最上层，但用户照常操作底下的系统页
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        // 靠在屏幕上方 1/4 处：既在状态栏之下，又不压住居中的配对码对话框
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.y = (int) (140 * density);

        try {
            windowManager.addView(view, params);
        } catch (Exception e) {
            Log.w(TAG, "进度浮层挂不上，退回 Toast", e);
            return false;
        }
        mIndicator = view;
        return true;
    }

    /** 撤掉提示条窗口：服务被关闭时必须做，否则窗口泄漏 */
    private void removeIndicator() {
        mMainHandler.removeCallbacks(mHideIndicator);

        TextView view = mIndicator;
        mIndicator = null;
        if (view == null) return;

        WindowManager windowManager = getSystemService(WindowManager.class);
        if (windowManager == null) return;
        try {
            windowManager.removeViewImmediate(view);
        } catch (Exception ignored) {
            // 已经被系统回收了：忽略
        }
    }

    /**
     * Toast 反馈。
     *
     * 两种场景：一是「人已经回到 App」后的进度与结果汇报（见 {@link #report} /
     * {@link #notifyResult}，这时 App 在前台，Toast 不受 Android 11 的后台限制，正常可见）；
     * 二是浮层根本挂不上时的最后兜底（这时多半也看不到，聊胜于无）。
     * 失败原因可能有好几行，用 LENGTH_LONG 多留点阅读时间。
     */
    private void toast(String message) {
        if (TextUtils.isEmpty(message)) return;
        try {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Log.w(TAG, "Toast 也弹不出来", e);
        }
    }

    // ==================== 文本与节点工具 ====================

    /** 广度优先收集窗口内所有节点的 text 与 contentDescription */
    private void collectTexts(AccessibilityNodeInfo root, List<String> out) {
        Deque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);

        int visited = 0;
        while (!queue.isEmpty() && visited++ < MAX_NODES) {
            AccessibilityNodeInfo node = queue.poll();
            if (node == null) continue;

            CharSequence text = node.getText();
            if (!TextUtils.isEmpty(text)) {
                out.add(text.toString());
            }
            CharSequence desc = node.getContentDescription();
            if (!TextUtils.isEmpty(desc)) {
                out.add(desc.toString());
            }

            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    queue.add(child);
                }
            }
        }
    }

    /** 取页面第一个「IP:端口」的 {ip, port}；没有则 null */
    private String[] firstIpPort(String joined) {
        Matcher matcher = IP_PORT.matcher(joined);
        return matcher.find() ? new String[]{matcher.group(1), matcher.group(2)} : null;
    }

    /** 取页面第一个独立的 6 位数字（配对码）；没有则 null */
    private String firstSixDigits(String joined) {
        Matcher matcher = SIX_DIGITS.matcher(joined);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** 文本是否命中任一关键词（大小写不敏感） */
    private boolean containsAny(String text, String[] keys) {
        String lower = text.toLowerCase();
        for (String key : keys) {
            if (lower.contains(key.toLowerCase())) return true;
        }
        return false;
    }

    /** BFS 找第一个 text/contentDescription 命中任一关键词的节点 */
    private AccessibilityNodeInfo findNodeByKey(AccessibilityNodeInfo root, String[] keys) {
        Deque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);

        int visited = 0;
        while (!queue.isEmpty() && visited++ < MAX_NODES) {
            AccessibilityNodeInfo node = queue.poll();
            if (node == null) continue;

            if (containsAnyOrEmpty(node.getText(), keys)
                    || containsAnyOrEmpty(node.getContentDescription(), keys)) {
                return node;
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    queue.add(child);
                }
            }
        }
        return null;
    }

    /** BFS 找第一个「可勾选但当前未勾选」的开关节点（无线调试的总开关） */
    private AccessibilityNodeInfo findUncheckedSwitch(AccessibilityNodeInfo root) {
        Deque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);

        int visited = 0;
        while (!queue.isEmpty() && visited++ < MAX_NODES) {
            AccessibilityNodeInfo node = queue.poll();
            if (node == null) continue;

            if (node.isCheckable() && !node.isChecked()) {
                return node;
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    queue.add(child);
                }
            }
        }
        return null;
    }

    /** 从节点自身向上找最近的可点击祖先并点击（列表项通常只有外层容器可点） */
    private boolean clickSelfOrAncestor(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        for (int depth = 0; depth < 6 && current != null; depth++) {
            if (current.isClickable() && current.isEnabled()
                    && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    /**
     * 点一个节点：优先用无障碍的 ACTION_CLICK（能点就不伪造触摸）；
     * 点不动时退到按屏幕坐标模拟点击。
     *
     * 为什么需要兜底：AOSP Settings 的列表项常常只有父容器带 clickable 标记，
     * 个别 ROM 连容器都没标，只靠 ACTION_CLICK 会一直点不动 —— 流程就卡在那儿到超时。
     */
    private boolean clickNode(AccessibilityNodeInfo node) {
        if (clickSelfOrAncestor(node)) return true;
        return tapCenter(node);
    }

    /** 按节点屏幕坐标模拟一次点击（依赖配置里的 canPerformGestures） */
    private boolean tapCenter(AccessibilityNodeInfo node) {
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (bounds.width() <= 0 || bounds.height() <= 0) return false;

        Path path = new Path();
        path.moveTo(bounds.centerX(), bounds.centerY());
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 50))
                .build();

        boolean dispatched = dispatchGesture(gesture, null, null);
        Log.i(TAG, "坐标点击(" + bounds.centerX() + "," + bounds.centerY() + ") 派发=" + dispatched);
        return dispatched;
    }

    /**
     * 把当前页面往下翻一屏。
     *
     * 先用无障碍的 ACTION_SCROLL_FORWARD：不依赖屏幕尺寸，也不会和别的手势打架。
     * 部分 ROM 的列表没把 scrollable 暴露给无障碍（或该 action 返回 false），
     * 这时退化成在屏幕中间模拟一次上滑手势（依赖配置里的 canPerformGestures）。
     */
    private boolean scrollListForward(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo scroller = findScrollable(root);
        if (scroller != null
                && scroller.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
            return true;
        }
        return swipeUp();
    }

    /** BFS 找第一个可滚动节点（开发者选项页整页就是一个大列表） */
    private AccessibilityNodeInfo findScrollable(AccessibilityNodeInfo root) {
        Deque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);

        int visited = 0;
        while (!queue.isEmpty() && visited++ < MAX_NODES) {
            AccessibilityNodeInfo node = queue.poll();
            if (node == null) continue;

            if (node.isScrollable()) return node;
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    queue.add(child);
                }
            }
        }
        return null;
    }

    /** 在屏幕中间模拟一次上滑（翻页手势），作为 ACTION_SCROLL_FORWARD 的兜底 */
    private boolean swipeUp() {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        float centerX = metrics.widthPixels / 2f;

        Path path = new Path();
        path.moveTo(centerX, metrics.heightPixels * 0.70f);
        path.lineTo(centerX, metrics.heightPixels * 0.30f);

        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 200))
                .build();

        boolean dispatched = dispatchGesture(gesture, null, null);
        Log.i(TAG, "翻页手势派发=" + dispatched);
        return dispatched;
    }

    /** 节点文本是否命中关键词（空文本返回 false） */
    private boolean containsAnyOrEmpty(CharSequence text, String[] keys) {
        return !TextUtils.isEmpty(text) && containsAny(text.toString(), keys);
    }

    // ==================== 偏好写入（与主界面共享同一份状态） ====================

    /** 写入「已配对过」标记：下次点按钮时可直接走连接步骤，不必再取配对码 */
    private void setHasPaired(boolean value) {
        getSharedPreferences(AdbPairPanel.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(AdbPairPanel.KEY_HAS_PAIRED, value)
                .apply();
    }

    /** 记录最近一次连接成功的端口（仅作记录；重连一律以系统页当前显示为准） */
    private void saveConnectPort(String port) {
        if (port == null) return;
        getSharedPreferences(AdbPairPanel.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(AdbPairPanel.KEY_LAST_CONNECT_PORT, port)
                .apply();
    }

    // ==================== 供界面调用的静态工具 ====================

    /** 「配对助手」是否已在系统设置里开启 */
    public static boolean isEnabled(Context context) {
        try {
            String enabled = Settings.Secure.getString(context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            ComponentName me = new ComponentName(context, AdbPairAccessibilityService.class);
            return enabled != null && serviceListContains(enabled, me);
        } catch (Exception ignored) {
            // 读不到就按未开启处理（下次点按钮会再引导一次）
        }
        return false;
    }

    /**
     * 系统记录的无障碍服务列表里是否包含本服务。
     *
     * 列表是冒号分隔的扁平组件名，同一个服务可能写成全类名（pkg/pkg.Cls）也可能是短类名
     * （pkg/.Cls），视写入方而定，所以两种形态都比一遍。
     */
    private static boolean serviceListContains(String enabledList, ComponentName service) {
        String full = service.flattenToString();
        String shortName = service.flattenToShortString();
        for (String item : enabledList.split(":")) {
            if (item.equalsIgnoreCase(full) || item.equalsIgnoreCase(shortName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 尽力把本服务重新写回系统「已开启的无障碍服务」列表，成功返回 true。
     *
     * 为什么要它：部分 ROM 在应用被一键清理、或从最近任务划掉之后，会顺手把它的无障碍服务
     * 从系统设置里摘掉，用户下次进来就得再去设置里手动开一遍 —— 这就是"每次退出应用都要
     * 重新打开无障碍权限"的来源。
     *
     * 这个权限不需要用户去敲 adb：应用一旦成功连上过设备，{@code AdbCmd.ensureSelfPermission}
     * 就会用那条连接自己执行 pm grant 授上（WRITE_SECURE_SETTINGS 的 protectionLevel 含
     * development 标记，shell 授得下来）。没拿到时 putString 会抛 SecurityException，这里吞掉
     * 并返回 false，调用方退回「弹对话框引导用户去系统设置」的老路，不会有副作用。
     *
     * 注意：写回去只是让系统重新读到配置并绑定服务，真正连上还要一两秒 —— 调用方得等。
     */
    static boolean tryReEnable(Context context) {
        try {
            ContentResolver resolver = context.getContentResolver();
            ComponentName me = new ComponentName(context, AdbPairAccessibilityService.class);

            String current = Settings.Secure.getString(resolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);

            // 本来就在列表里（那多半只是服务实例还没连上）：没什么可补的
            if (current != null && serviceListContains(current, me)) return false;

            String updated = (current == null || current.isEmpty())
                    ? me.flattenToString()
                    : current + ":" + me.flattenToString();

            Settings.Secure.putString(resolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated);
            Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            Log.i(TAG, "已自动把「配对助手」写回无障碍服务列表");
            return true;
        } catch (Exception e) {
            Log.w(TAG, "自动重开无障碍未成功（多半没有 WRITE_SECURE_SETTINGS）", e);
            return false;
        }
    }

    /** 打开系统「无障碍」设置页，让用户手动开启本服务 */
    public static void openSettings(Context context) {
        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);   // 可能从 application context 启动
        try {
            context.startActivity(intent);
        } catch (Exception ignored) {
            // 个别 ROM 没有该页面：忽略，用户可自行到设置里找
        }
    }
}
