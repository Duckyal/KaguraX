package com.termux.app;

import com.termux.R;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.lang.ref.WeakReference;

/**
 * AdbPairPanel —— 主界面内嵌的 ADB 无线调试面板（极简形态）。
 *
 * 界面结构（activity_main.xml 的 ADB 区块）只有四样东西：
 *   - 顶部状态条：App 进程内执行 adb devices，实时显示「已连接：<地址>」（绿）
 *     /「未连接 · 请先配对」/「未连接 · 请点「配对 / 连接」」等
 *   - 一段说明文字
 *   - 唯一入口按钮「配对 / 连接」
 *   - 右下角小字「自动失败？手动填写」（手动兜底入口）
 *
 * ## 为什么主页不留端口输入框，也不靠浮窗
 * 端口、配对码、连接端口三者都只在系统「无线调试」页上显示，且每次开关无线调试都会重新
 * 随机生成 —— 放主页只会逼用户在两个应用之间来回手抄。
 *
 * 悬浮窗也不行：Android 12 起系统「设置」持有 HIDE_OVERLAY_WINDOWS 权限，会主动隐藏第三方
 * TYPE_APPLICATION_OVERLAY 窗口 —— 浮窗在「无线调试」页上**根本不可能出现**（这跟「显示在
 * 其他应用上层」权限无关，给了也一样）。那一页上唯一既看得见、又能输入的载体是通知栏，
 * 所以手动兜底做成了「可填写通知」：{@link AdbPairNotifier#manualAskPair} 发一条带输入框的
 * 通知，用户在通知栏里照着系统页填配对端口与配对码，由 {@link AdbPairInputReceiver} 接住执行。
 *
 * 所以主流程是：点按钮 → 跳系统页 → 无障碍服务（{@link AdbPairAccessibilityService}）
 * 读屏 + 自动点击 + 后台执行 adb 完成配对与连接 → 进度通过通知（{@link AdbPairNotifier}）
 * 汇报。用户全程只需点一次按钮，不用看、不用填。
 *
 * 按钮按当前状态自适应，用户不用自己判断该配对还是该连接：
 *   - 从未配对过 → 自动流程从「点进配对页取配对码」开始
 *   - 已配对过   → 直接读主页端口连接
 *
 * 设计要点：
 *   1. 状态检测在 App 进程内命令式探测（走 {@link AdbCmd#probeConnected}）：在同一次容器
 *      会话里用记下的端口重连 + adb devices 校验，只认实时事实、不做内存缓存。
 *      （不能只跑 adb devices —— 容器会话不跨 exec 保留，那样永远看到空列表，界面会一直
 *      停在「未连接」上，见 {@link AdbCmd} 类注释第 6 条）
 *   2. 无障碍是自动流程的硬前提：未开启时先引导开启
 *      （{@link AdbPairAccessibilityService#isEnabled}）；
 *   3. 自动走不通时的兜底是「可填写通知」（见上）：不需要无障碍、不需要悬浮窗权限，
 *      用户在通知栏里照抄系统页上的数字即可；
 *   4. MainActivity.onResume 时调用 {@link #refresh()}，回到主界面即重新检测连接状态，
 *      并顺带补做「去配对」时因缺权限/未授权而挂起的流程；
 *   5. 端口一读到手就把用户送回主界面（见 {@link AdbPairAccessibilityService#returnToAppIfReady}），
 *      配对/连接改在后台跑、结果用 Toast 汇报；结果出来时再调 {@link #refreshActive()}
 *      补刷一次状态条 —— 否则界面会停在上一步的「未连接」上。
 */
public final class AdbPairPanel {

    /** SharedPreferences 文件名（App 私有，无需权限；与无障碍自动流程、通知输入共用同一份状态） */
    public static final String PREFS_NAME = "adb_pair_prefs";

    /** 是否完成过首次配对 key（决定流程从「取配对码」还是「直接用端口连接」开始） */
    public static final String KEY_HAS_PAIRED = "has_paired";

    /** 最近一次连接成功的端口 key（仅作记录，重连一律以系统页当前显示为准） */
    public static final String KEY_LAST_CONNECT_PORT = "last_connect_port";

    /** 通知权限（Android 13+）的 requestCode */
    private static final int REQUEST_NOTIFICATION_PERM = 1003;

    /** 等无障碍服务连上的总时长（毫秒）：应用进程刚被系统回收时，系统重绑需要一两秒 */
    private static final long SERVICE_WAIT_MAX_MS = 4000;

    /** 等待无障碍服务连上时的轮询间隔（毫秒） */
    private static final long SERVICE_POLL_MS = 200;

    /** 日志标签 */
    private static final String LOG_TAG = "AdbPairPanel";

    /** 系统「设置」的包名：无线调试页在它里面 */
    private static final String SETTINGS_PACKAGE = "com.android.settings";

    /**
     * 「无线调试」页在各 ROM 上的组件名（按命中率排序）。
     *
     * Android 没有公开的 action 能直达这一页 —— 只有 ACTION_APPLICATION_DEVELOPMENT_SETTINGS
     * 指向「开发者选项」列表，用户还得再点一层。所以只能赌组件名，逐个试：
     * 类原生与多数 ROM 用 Settings$WifiDebuggingActivity，其余是几个已知变体。
     * 注意「能解析」不等于「能启动」：部分 ROM 把这些类声明成未导出，
     * startActivity 会抛 SecurityException，所以每个都得实际试一次（见 openIntent）。
     */
    private static final String[] WIFI_DEBUGGING_COMPONENTS = {
            "com.android.settings.Settings$WifiDebuggingActivity",
            "com.android.settings.Settings$WifiDebuggingSettingsActivity",
            "com.android.settings.wifi.debugging.WifiDebuggingActivity",
            "com.android.settings.development.WifiDebuggingActivity",
    };

    /** 状态刷新序号：用于丢弃过期的状态检测结果（避免慢线程覆盖新结果） */
    private int mStatusSeq;

    /**
     * 用户被引导去系统设置（悬浮窗权限 / 无障碍授权）时置位：返回后由 {@link #refresh()}
     * 自动接着往下走，不用让用户自己再点一遍按钮。
     */
    private boolean mPendingLaunch;

    /**
     * 通知权限（Android 13+）结果还没回来、自动流程在等它，见 {@link #startPairingFlow()}。
     * 不能在申请权限后立刻跳页 —— 权限对话框会被跳转压掉，用户永远授权不了。
     */
    private boolean mPendingAutoStart;

    /** 等无障碍服务实例的轮询序号：新一轮点按钮会把它顶掉，避免多轮等待同时往下走 */
    private int mServiceWaitSeq;

    private final Activity activity;          // 页面 Activity（找控件/跑 UI/跳系统页）
    private final Context appContext;         // Application context（Toast/偏好，防泄漏）
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /**
     * 当前活着的面板实例，只服务于 {@link #refreshActive()} 一件事。
     * 用弱引用，页面销毁后不再持有它。
     */
    private static WeakReference<AdbPairPanel> sActive;

    // 控件引用（activity_main.xml ADB 区块）
    private TextView tvStatus;                // 顶部连接状态
    private Button btnGoPair;                 // 唯一入口：「配对 / 连接」
    private TextView btnManualPair;           // 手动兜底入口：「自动失败？手动填写」

    /**
     * 构造：绑定主界面 ADB 区块的控件、设置监听并做一次状态检测。
     * 必须在 {@code setContentView(R.layout.activity_main)} 之后调用。
     */
    public AdbPairPanel(Activity activity) {
        this.activity = activity;
        this.appContext = activity.getApplicationContext();

        bindViews(activity);
        setListeners();
        sActive = new WeakReference<>(this);
        refresh();                         // 绑定后立即检测一次连接状态
    }

    /**
     * 页面销毁时解绑（由 MainActivity.onDestroy 调用）：
     * 配对结果是由无障碍服务 / 通知输入接收者异步回报的，页面没了就不能再往它身上刷状态。
     */
    public void detach() {
        AdbPairPanel current = sActive == null ? null : sActive.get();
        if (current == this) {
            sActive = null;
        }
    }

    /**
     * 供无障碍自动流程 / 通知输入接收者调用：配对（连接）在后台出结果后，把主界面状态条刷一遍。
     *
     * 为什么需要它：现在「端口一读到就回 App」，onResume 里那次 refresh() 跑在 adb 结果之前，
     * 那时状态还是「未连接」；结果回来若不补刷，界面会一直停在错的状态上，直到用户切走再回来。
     *
     * 面板不在（页面已销毁 / 从没建过）就静默忽略；可以在任意线程调用。
     */
    public static void refreshActive() {
        AdbPairPanel panel = sActive == null ? null : sActive.get();
        if (panel != null) {
            panel.mainHandler.post(panel::refresh);
        }
    }

    // ==================== 绑定与监听 ====================

    /** 绑定布局控件（id 与 activity_main.xml ADB 区块一一对应） */
    private void bindViews(Activity activity) {
        tvStatus = activity.findViewById(R.id.tv_adb_status);
        btnGoPair = activity.findViewById(R.id.btn_go_pair);
        btnManualPair = activity.findViewById(R.id.btn_manual_pair);
    }

    /** 设置监听 */
    private void setListeners() {
        btnGoPair.setOnClickListener(v -> startPairingFlow());
        btnManualPair.setOnClickListener(v -> showManualInput());
    }

    // ==================== 状态检测 ====================

    /** 页面回到前台（onResume）或配对/连接结束后调用：重新检测一次连接状态 */
    public void refresh() {
        // 补做被中断的流程：用户从系统设置授权返回后自动继续（详见 resumePendingLaunch）
        resumePendingLaunch();

        // 顺手把被系统或手机管家摘掉的「配对助手」写回来。这个问题的表现就是「每次进 App 都要
        // 去无障碍里手动开一遍」，一进界面就自己修好，用户压根不会看到那个引导框。
        // 只在拿到 WRITE_SECURE_SETTINGS 后才写得动（见 AdbCmd.ensureSelfPermission），
        // 没拿到时这里会静默失败，不影响后续流程。
        if (!AdbPairAccessibilityService.isEnabled(activity)) {
            AdbPairAccessibilityService.tryReEnable(activity);
        }

        tvStatus.setText(R.string.adb_loading);
        final int seq = ++mStatusSeq;
        new Thread(() -> {
            // 子线程：执行命令并解析（可能耗时数秒，绝不能放主线程）
            final boolean initialized = MainActivity.isDebianInitialized();
            // 用 probeConnected（同会话重连+校验）而不是裸 adb devices：容器内 adb server
            // 不跨 exec 保留，裸 devices 恒为空列表，界面会永远显示「未连接」
            final String connectedDevice = initialized
                    ? AdbCmd.probeConnected(lastConnectPort())
                    : null;
            mainHandler.post(() -> {
                if (seq != mStatusSeq) return;   // 已有更新的检测结果，丢弃本次过期结果
                updateStatusText(initialized, connectedDevice);
            });
            // 已经连着（升级后第一次进来往往正是这种状态）就顺手把系统权限补上 —— 这是无障碍
            // 被系统摘掉后能自己开回来的前提。放在状态刷新之后，免得拖慢界面上的状态显示。
            if (connectedDevice != null) {
                AdbCmd.ensureSelfPermission(connectedDevice, appContext);
            }
        }).start();
    }

    /**
     * 主线程：按检测结果更新状态文案与颜色。
     *
     * 未连接不再只显示干巴巴的「未连接」，而是按上下文给出下一步引导：
     *   - 容器未初始化 → 「容器未初始化」
     *   - 从未配对过   → 「未连接 · 请先配对」
     *   - 配对过       → 「未连接 · 请点「配对 / 连接」」（重连场景，多数是端口变了）
     *   - 已连接       → 绿色「已连接：<ip:port>」
     */
    private void updateStatusText(boolean initialized, String connectedDevice) {
        if (connectedDevice != null) {
            tvStatus.setText(activity.getString(R.string.adb_status_connected, connectedDevice));
            tvStatus.setTextColor(Color.parseColor("#2E7D32"));   // 已连接：深绿（白卡片上清晰）
        } else if (!initialized) {
            tvStatus.setText(R.string.adb_status_not_init);
            tvStatus.setTextColor(Color.GRAY);
        } else if (hasPaired()) {
            tvStatus.setText(R.string.adb_status_click_connect);
            tvStatus.setTextColor(Color.GRAY);
        } else {
            tvStatus.setText(R.string.adb_status_need_pair);
            tvStatus.setTextColor(Color.GRAY);
        }
    }

    // ==================== 配对 / 连接入口 ====================

    /**
     * 「配对 / 连接」入口：走无障碍全自动流程。
     *
     * 不再依赖悬浮窗：Android 12 起系统「设置」持有 HIDE_OVERLAY_WINDOWS，会隐藏第三方
     * 悬浮窗 —— 而端口与配对码只在系统「无线调试」页显示，浮窗在那一页根本看不见。
     * 现在改成：无障碍读屏 + 自动点击 + 后台执行 adb，进度走通知。
     *
     * 两道前置检查：
     *   1. 通知权限（Android 13+）：不授权也能跑完，只是看不到进度，所以只顺手要一次、不阻塞；
     *   2. 配对助手（无障碍）：没有它读不到系统页上的端口与配对码，这是硬前提，未开启就引导。
     */
    private void startPairingFlow() {
        if (!MainActivity.isDebianInitialized()) {
            toast(R.string.adb_not_initialized);
            return;
        }

        // 新一轮开始：先把上一轮留着的失败/成功通知收掉。它不会被新进度顶掉（不同 id），
        // 留着只会让用户分不清哪个结论是这次的。
        AdbPairNotifier.clearResult(appContext);

        // 通知权限（Android 13+）先要、并且等结果回来再跳页：
        // 立刻跳页的话权限对话框会被压掉，用户看到了也点不了（详见 onRequestPermissionsResult）
        if (needNotificationPermission()) {
            mPendingAutoStart = true;
            ActivityCompat.requestPermissions(activity,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATION_PERM);
            return;
        }
        continuePairingFlow();
    }

    /**
     * 权限结果回调（由 MainActivity 转发）。
     * 无论授权还是拒绝都继续往下走 —— 拒绝只是看不到通知进度，配对本身照样能在后台完成。
     */
    public void onRequestPermissionsResult(int requestCode) {
        if (requestCode != REQUEST_NOTIFICATION_PERM || !mPendingAutoStart) return;
        mPendingAutoStart = false;
        continuePairingFlow();
    }

    /** 真正的流程起点：检查无障碍（自动流程的硬前提）→ 启动自动流程 + 跳系统页 */
    private void continuePairingFlow() {
        if (!AdbPairAccessibilityService.isEnabled(activity)
                && !AdbPairAccessibilityService.tryReEnable(activity)) {
            // 自己写不回去（多半是没授 WRITE_SECURE_SETTINGS）：照旧引导用户去系统设置手动开
            mPendingLaunch = true;
            showAccessibilityGuideDialog();
            return;
        }
        if (AdbPairAccessibilityService.isAutoReady()) {
            launchAutoPairing();
            return;
        }
        // 无障碍在系统里是开着的，只是服务实例还没连上 —— 最常见于应用进程刚被系统回收过：
        // 系统会重新绑定服务，只差一两秒。这里自己等一下，别让用户再点一次
        // （看到「还没就绪」多半会以为得去系统里重新开一遍无障碍，白跑一趟设置）。
        waitForServiceThenLaunch(SERVICE_WAIT_MAX_MS);
    }

    /**
     * 等无障碍服务连上再继续：连上了往下走，等超时才提示用户。
     *
     * 为什么要等：服务实例（{@code sInstance}）要等系统绑定完成才非空。用户刚从后台把应用
     * 调回来、或刚在系统设置里开启无障碍时，这个空窗期真实存在 —— 不等的话用户会以为白开了。
     */
    private void waitForServiceThenLaunch(long remainingMs) {
        if (AdbPairAccessibilityService.isAutoReady()) {
            launchAutoPairing();
            return;
        }
        if (remainingMs <= 0) {
            toast(R.string.adb_auto_not_ready);
            return;
        }
        final int seq = ++mServiceWaitSeq;
        mainHandler.postDelayed(() -> {
            if (seq != mServiceWaitSeq) return;   // 这轮等待已被新的一次点按钮取代
            waitForServiceThenLaunch(remainingMs - SERVICE_POLL_MS);
        }, SERVICE_POLL_MS);
    }

    /** 启动无障碍自动流程 + 跳系统「无线调试」页（顺序：先开始监听，再跳页） */
    private void launchAutoPairing() {
        // 开发者选项还没开时，这一步的职责只是引导用户去开（跳「关于手机」连点版本号）。
        // 此时**不能**启动自动流程：无障碍服务一旦跑起来就会读屏、翻页、点击，
        // 和用户在「关于手机」页连点版本号的操作直接打架，还会把那一页翻得乱七八糟。
        // 引导完就交给用户自己操作，开好后回 App 再点一次按钮即可走完整自动流程。
        if (!isDevelopmentSettingsEnabled()) {
            launchWirelessDebuggingPage(false);   // 内部弹引导框，并跳「关于手机」
            return;
        }

        AdbPairAccessibilityService.startAuto(!hasPaired());
        launchWirelessDebuggingPage(false);
        toast(R.string.adb_auto_started);
    }

    /** Android 13+ 是否还缺通知权限（自动流程的进度与失败原因都发在通知栏） */
    private boolean needNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false;
        return ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED;
    }

    /**
     * 配对助手（无障碍）未开启时的引导。
     *
     * 自动流程完全依赖它（端口与配对码只在系统页上，只能读屏），所以这是硬前提；
     * 但仍留「手动填写」这个出口：不想开无障碍时，改从通知栏照抄系统页上的数字。
     */
    private void showAccessibilityGuideDialog() {
        new AlertDialog.Builder(activity)
                .setTitle(R.string.adb_accessibility_guide_title)
                .setMessage(R.string.adb_accessibility_guide_message)
                .setPositiveButton(R.string.adb_accessibility_guide_go, (d, w) -> {
                    // 去开无障碍：回来后由 refresh() → resumePendingLaunch() 自动接着走
                    mPendingLaunch = true;
                    AdbPairAccessibilityService.openSettings(activity);
                })
                .setNegativeButton(R.string.adb_accessibility_guide_skip, (d, w) -> showManualInput())
                .show();
    }

    /**
     * 手动兜底：把输入框发到通知栏，由用户照系统页自己填。
     *
     * 为什么不是浮窗：Android 12 起系统「设置」持有 HIDE_OVERLAY_WINDOWS，会隐藏第三方
     * TYPE_APPLICATION_OVERLAY 窗口 —— 浮窗在「无线调试」页上根本不可见，那条路已作废。
     * 通知栏不受该限制（它是 SystemUI），配 RemoteInput 就能直接在里面输入，
     * 详见 {@link AdbPairInputReceiver}。
     *
     * 从哪一步起步看配对状态，与自动流程的分流保持一致（{@code startAuto(!hasPaired())}）：
     *   · 没配对过 / 认证已失效 —— 先填「配对端口 + 配对码」；
     *   · 已配对过 —— 只要「连接端口」：配对码早用不上了，没必要逼用户再抄一遍。
     * 万一认证其实已经失效也不会卡住：连接失败会被判成 needsRePair，通知原地换成配对输入
     * （见 {@link AdbPairInputReceiver#handleConnect}）。
     */
    private void showManualInput() {
        mPendingLaunch = false;
        // 手动接管前先停掉自动流程：它还在读屏/自动点击的话，会和用户手填的数打架
        AdbPairAccessibilityService.cancelAuto();

        // 先发通知、再跳系统页：跳页会把本应用切到后台，输入框必须在那之前就位，
        // 顺序反了就会出现「人都站到系统页上了，通知还没发出来」的空窗。
        if (hasPaired()) {
            AdbPairNotifier.manualAskConnectPort(appContext, false);
        } else {
            AdbPairNotifier.manualAskPair(appContext);
        }
        Toast.makeText(appContext, R.string.adb_manual_posted, Toast.LENGTH_LONG).show();

        // 手动流程也得把人送到系统页去：端口和配对码只在「无线调试」页上显示，让用户自己翻
        // 设置一层层找是白费功夫 —— 自动流程怎么跳，这里就怎么跳（manual=true 只改提示语）。
        launchWirelessDebuggingPage(true);
    }

    /**
     * 用户去系统「无障碍」授权返回后（onResume → refresh），自动接着走自动流程。
     * 前置条件仍未满足（无障碍还是没开）时保持挂起，不重复弹窗打扰。
     */
    private void resumePendingLaunch() {
        if (!mPendingLaunch) return;
        if (!AdbPairAccessibilityService.isEnabled(activity)) {
            return;     // 还是没开：保持 pending，等真正开启后再继续
        }
        mPendingLaunch = false;
        // 刚在系统设置里开启时，服务还要一两秒才连得上，等一下再走（否则用户以为白开了）
        waitForServiceThenLaunch(SERVICE_WAIT_MAX_MS);
    }

    /**
     * 跳到系统「无线调试」页面。
     *
     * 系统没有公开的「无线调试」Intent action，各 ROM 也不统一，所以按可靠性依次尝试：
     *   1. 部分 ROM 提供的 pairing action（能直达「使用配对码配对设备」）
     *   2. AOSP Settings 的无线调试页组件名（类原生与多数 ROM 沿用同一个组件）
     *   3. 退到开发者选项首页（用户再点一下「无线调试」）
     * 跳转前先探开发者选项是否开启：没开就引导怎么开，而不是跳一个空白/不存在的页面。
     *
     * @param manual 是否来自手动填写流程（那条路上无障碍已经被停掉）。差别只在一处提示语：
     *               退到开发者选项列表页时，自动流程能靠无障碍在 1 秒内自己点进「无线调试」，
     *               手动流程没有它，得让用户自己点 —— 所以不能沿用「会自动进去」的说法。
     */
    private void launchWirelessDebuggingPage(boolean manual) {
        if (!isDevelopmentSettingsEnabled()) {
            showDevOptionsOffDialog();
            return;
        }
        if (openWirelessDebuggingPage()) {
            // 手动流程不在这里再报一次：上面那条 adb_manual_posted 已经把「下拉通知栏点回复填写」
            // 交代完了，页面也确实就在眼前，再叠一条只会排在它后面刷屏
            if (!manual) toast(R.string.adb_auto_page_ready);
            return;
        }
        // 直达不到就退到开发者选项列表页 —— 但这不是终点：「配对助手」会在 1 秒内自动点进
        // 「无线调试」（见 AdbPairAccessibilityService.clickWirelessDebuggingEntry），
        // 所以自动流程的提示语要说清「会自己走」，而不是让用户手动点
        if (openIntent(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))) {
            Toast.makeText(appContext,
                    manual ? R.string.adb_manual_dev_options_fallback
                            : R.string.adb_auto_dev_options_fallback,
                    Toast.LENGTH_LONG).show();
            return;
        }
        // 最后兜底：系统设置首页
        openIntent(new Intent(Settings.ACTION_SETTINGS));
    }

    /** 逐级尝试打开无线调试页；任一成功即返回 true */
    private boolean openWirelessDebuggingPage() {
        // 1) 少数 ROM 提供的直达配对页 action（不存在时会抛异常，被 openIntent 吞掉）
        if (openIntent(new Intent("android.settings.WIRELESS_DEBUGGING_PAIRING"))) {
            Log.i(LOG_TAG, "直达无线调试页：WIRELESS_DEBUGGING_PAIRING");
            return true;
        }
        // 2) 各 ROM 的无线调试页组件名，逐个试
        for (String className : WIFI_DEBUGGING_COMPONENTS) {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(SETTINGS_PACKAGE, className));
            if (!canResolve(intent)) continue;   // 这个 ROM 上没这个类：跳过，别白走一趟异常路径
            if (openIntent(intent)) {
                Log.i(LOG_TAG, "直达无线调试页：" + className);
                return true;
            }
            // 类存在但启不动：多半被 ROM 声明成未导出，继续试下一个变体
            Log.w(LOG_TAG, "该组件无法外部启动：" + className);
        }
        return false;
    }

    /**
     * 组件能否被解析到。
     * 注意：能解析不等于能启动 —— 解析不检查 exported，未导出的类要到 startActivity 时才抛异常，
     * 所以这里只用来过滤「类根本不存在」的情况。
     */
    private boolean canResolve(Intent intent) {
        try {
            return activity.getPackageManager().resolveActivity(intent, 0) != null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 开发者选项是否已开启。
     * 读不到（个别 ROM 限读该 Settings.Global 项）时按「已开启」处理，不拦着用户走后面的跳转尝试。
     */
    private boolean isDevelopmentSettingsEnabled() {
        try {
            return Settings.Global.getInt(activity.getContentResolver(),
                    Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1;
        } catch (Exception e) {
            return true;
        }
    }

    /** 开发者选项未开启：说明开启步骤，并提供一键去「关于手机」（连点版本号 7 次） */
    private void showDevOptionsOffDialog() {
        new AlertDialog.Builder(activity)
                .setTitle(R.string.adb_pair_dev_options_off_title)
                .setMessage(R.string.adb_pair_dev_options_off_message)
                .setPositiveButton(R.string.adb_pair_dev_options_go, (d, w) ->
                        openIntent(new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)))
                .setNegativeButton(R.string.adb_pair_later, null)
                .show();
    }

    /** 尝试启动一个系统页面；目标不存在/无权限/被 ROM 禁用时返回 false（不抛给调用方） */
    private boolean openIntent(Intent intent) {
        try {
            activity.startActivity(intent);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== 小工具 ====================

    /** 是否完成过首次配对（与自动流程、通知输入写入的状态同步） */
    private boolean hasPaired() {
        return appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_HAS_PAIRED, false);
    }

    /**
     * 最近一次连接成功的端口（prefs 记录）；从没连过返回 null。
     * 供 {@link AdbCmd#probeConnected} 在状态检测时重连用 —— 无线调试的连接端口在同一次
     * 开关内不变，所以它可以当「当前该连哪儿」的依据；端口变了（关过无线调试）时探测会
     * 失败并如实显示未连接。
     */
    private String lastConnectPort() {
        return appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_LAST_CONNECT_PORT, null);
    }

    /** Toast 快捷方法 */
    private void toast(int resId) {
        Toast.makeText(appContext, resId, Toast.LENGTH_SHORT).show();
    }
}
