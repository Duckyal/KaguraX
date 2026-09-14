package com.termux.app;

import com.termux.R;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.termux.shared.android.PermissionUtils;
import com.termux.shared.termux.shell.TermuxShellManager;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;

/**
 * 项目管理器主界面（MainActivity）。
 *
 * 布局结构（activity_main.xml）：
 *   - 右上角「终端」按钮：新开终端会话并前置，直接进入 Debian 容器
 *   - 中部卡片：ADB 配对内嵌面板（连接状态/地址输入/首次配对直接铺在页面上，
 *     逻辑见 AdbPairPanel）+ 「启动 / 终止项目」大按钮
 *   - 底部小按钮：一键初始化 / 检查更新
 *
 * 悬浮窗联动：项目启动(端口就绪)后自动挂起悬浮球，直到「终止项目」或应用进程停止；
 * 应用退后台时若项目仍在运行，TermuxApplication 也会兜底拉起悬浮球。
 *
 * 关键设计（对应需求）：
 *   1. 一键初始化：启动时检测 Debian 容器根文件系统目录是否存在，
 *      未安装则底部「一键初始化」按钮可用（点击直接新开会话执行安装），
 *      已安装则按钮禁用显示「已初始化」，不再弹窗打扰。
 *   2. 项目状态检测：用 Socket 真实探测 127.0.0.1:8000 能否建立 TCP 连接，
 *      能连通 = 运行中。每次刷新（onResume / 启停后）都以真实探测结果
 *      同步按钮文案，不依赖任何内存变量记忆状态。
 *   3. 网络探测在子线程执行，通过 Handler 回到主线程更新 UI。
 */
public class MainActivity extends Activity {

    private static final String LOG_TAG = "MainActivity";

    /** requestDisplayOverOtherAppsPermission() 的 requestCode */
    private static final int REQUEST_OVERLAY_PERM = 1001;

    /** Termux 属性文件路径：这里需要写入 allow-external-apps=true，
     *  否则 RunCommandService 会把本 App 自己发起的 RUN_COMMAND 当外部应用拒绝 */
    private static final String TERMUX_PROPERTIES_FILE =
            "/data/data/duckyal.KaguraX/files/home/.termux/termux.properties";

    /** Termux 崩溃日志文件：Termux 自带崩溃处理（TermuxCrashUtils/CrashHandler）
     *  在进程闪退前会自动把完整堆栈写到 crash_log.md，
     *  本页启动时检查到就弹窗展示，方便用户直接复制给开发者定位 */
    private static final String TERMUX_CRASH_LOG_FILE =
            "/data/data/duckyal.KaguraX/files/home/crash_log.md";
    private static final String TERMUX_CRASH_LOG_BACKUP_FILE =
            "/data/data/duckyal.KaguraX/files/home/crash_log_backup.md";

    /**
     * Debian 容器根文件系统可能存在的两个路径（任一存在即视为容器已安装）。
     * proot-distro 5.x 起把根文件系统从 installed-rootfs 迁移到 containers/<发行版>/rootfs，
     * 两个路径都检测以兼容新旧版本（spec 中给出的路径是旧版 installed-rootfs/debian）。
     */
    private static final String[] DEBIAN_ROOTFS_DIRS = {
            "/data/data/duckyal.KaguraX/files/usr/var/lib/proot-distro/installed-rootfs/debian",
            "/data/data/duckyal.KaguraX/files/usr/var/lib/proot-distro/containers/debian/rootfs"
    };

    /**
     * 项目目录（容器内 /root/app）在宿主侧的对应路径。
     * init_container.sh 的 git clone 会把它建出来，start.sh 的 cd /root/app 依赖它。
     * 任一存在即视为「项目已初始化」。
     */
    private static final String[] PROJECT_APP_DIRS = {
            "/data/data/duckyal.KaguraX/files/usr/var/lib/proot-distro/containers/debian/rootfs/root/app",
            "/data/data/duckyal.KaguraX/files/usr/var/lib/proot-distro/installed-rootfs/debian/root/app"
    };

    /** 项目服务监听地址 */
    private static final String SERVICE_HOST = "127.0.0.1";

    /** 项目服务监听端口 */
    private static final int SERVICE_PORT = 8000;

    /** Socket 连接超时（毫秒）：超过即视为服务未运行 */
    private static final int SOCKET_TIMEOUT_MS = 1500;

    /** 右上角「终端」按钮 */
    private Button btnTerminal;

    /** 中部卡片「启动项目 / 终止项目」按钮（文案随真实状态切换） */
    private Button btnProject;

    /** 底部「一键初始化」按钮（容器未安装时可用） */
    private Button btnInit;

    /** 「更新分支」偏好存储：App 私有，记住用户选择要更新的分支（如 dev，便于在手机上测分支代码） */
    private static final String UPDATE_PREFS_NAME = "update_prefs";

    /** 偏好中「更新分支」的 key */
    private static final String KEY_UPDATE_BRANCH = "update_branch";

    /** 底部「检查更新」按钮 */
    private Button btnCheckUpdate;

    /** 主界面内嵌的 ADB 配对面板：状态检测/连接/首次配对直接铺在页面上（非弹窗） */
    private AdbPairPanel mAdbPanel;

    /** 主线程 Handler：子线程的探测结果通过它回到 UI 线程更新控件 */
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** 启动项目时悬浮窗权限未开：记 pending，用户去系统设置授权返回后 onResume 补开悬浮球 */
    private boolean mFloatPendingStart = false;

    /** 启动遮罩：冷启动初始化期间盖住页面，显示「缓冲圈 + 当前在干什么」 */
    private View startupOverlay;

    /** 遮罩上的状态文字（随初始化步骤推进，见 setStartupStatus） */
    private TextView tvStartupStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();            // 1. 绑定布局控件
        setListeners();         // 2. 设置点击监听

        // 3. 首帧优先：把初始化整段推迟到首帧绘制之后再跑。
        //    此前这些步骤（释放脚本 / 写属性 / 扫容器目录 / 读崩溃日志）全排在
        //    setContentView 之后同步执行，首帧一直画不出来，屏幕上只剩主题的
        //    windowBackground —— 夜间是 #121212，观感就是「黑屏卡住」。
        //    现在先让「缓冲圈 + 状态文字」这一屏绘制出去，再开始干活。
        //
        //    页面重建（旋转 / 分屏）时不走遮罩：内容早已在内存里，再挡一层反而多余，
        //    初始化本身是幂等的，直接跑第二段即可（遮罩保持布局里的 gone 状态）。
        if (savedInstanceState != null) {
            runStartupTasksOnMain();
        } else {
            startupOverlay.setVisibility(View.VISIBLE);
            getWindow().getDecorView().post(this::runStartupTasks);
        }
    }

    /**
     * 初始化第一段：把最重的一步挪到子线程，主线程空出来把启动遮罩画出去。
     *
     * AssetsUtils.ensureScripts 要读 6 个 assets（含 init_container.sh 等）+ 算 12 次
     * SHA-256，是全流程里唯一明显耗时的同步 IO；其余步骤都是几十毫秒量级的文件检查，
     * 留在主线程按序跑即可。状态文字要真被用户看见，主线程就不能被这一步堵住。
     */
    private void runStartupTasks() {
        setStartupStatus(R.string.startup_release_scripts);
        new Thread(() -> {
            AssetsUtils.ensureScripts(this);
            mainHandler.post(this::runStartupTasksOnMain);
        }, "startup-assets").start();
    }

    /** 初始化第二段（主线程）：轻量步骤按序跑完，撤掉遮罩后再弹各类引导窗 */
    private void runStartupTasksOnMain() {
        // 3.5 确保 Termux 允许外部应用调用 RUN_COMMAND：
        //     把 allow-external-apps=true 写入 termux.properties 并重载到内存。
        //     否则 RunCommandService 会拒绝本 App 发起的命令（默认策略），
        //     这也是此前「终端/启动项目/一键初始化」闪退的根因之一。
        setStartupStatus(R.string.startup_prepare_env);
        ensureAllowExternalAppsEnabled();

        // 3.6 确保 Termux 基础环境（bootstrap）已安装。
        //     原版 Termux 在 TermuxActivity 首次打开时自动安装 bootstrap，
        //     但本 App 以 MainActivity 为启动页，用户不会进入 TermuxActivity，
        //     导致 /data/data/duckyal.KaguraX/files/usr/bin（bash、pkg 等）不存在，
        //     「终端」「一键初始化」会因找不到 bash 直接报错（此前终端 150 报错的根因）。
        //     这里主动触发：未安装时弹出下载进度框（用户可见进度），
        //     安装完成后回调；已安装则立即回调。
        TermuxInstaller.setupBootstrapIfNeeded(this, () -> {
            // bootstrap 安装完成后 Termux home 目录才存在:补释放一次 assets 脚本
            // (清除数据后首次启动时 home 尚不存在,onCreate 里的释放会静默失败),
            // 再刷新项目与初始化按钮状态。
            AssetsUtils.ensureScripts(this);
            runOnUiThread(this::refreshProjectStatus);
            runOnUiThread(this::refreshInitStatus);
        });

        // 4. 同步底部「一键初始化」按钮状态（容器未安装时可用，已安装则禁用）
        setStartupStatus(R.string.startup_check_status);
        refreshInitStatus();

        // 5. 主界面启动时的静默更新检查（有新版才弹窗，不打扰；内部起线程）
        checkUpdateSilently();

        // 6. 首次探测项目运行状态，同步按钮文案（内部起线程）
        refreshProjectStatus();

        // 界面已就绪：先撤遮罩再弹窗，遮罩和对话框不能叠在一起
        hideStartupOverlay();

        // 4.5 首次初始化引导：容器未安装时弹窗引导「一键初始化」
        //     （弹窗被「暂不」后仍可通过底部「一键初始化」按钮再次进入）
        showInitGuideDialogIfNeeded();

        // 6.5 崩溃日志展示：上次闪退的堆栈会自动写入 crash_log.md，
        //     有则弹窗显示，方便直接复制给开发者定位
        showCrashLogIfAny();

        // 7. 运行时权限引导：Android 10+ 要求后台服务启动前台 Activity 必须有
        //    「显示在其他应用上层」权限；否则 TermuxService 启动会话时
        //    startTermuxActivity() 会直接 toast 警告并卡住进程（截图中的报错）
        checkOverlayPermission();
    }

    /** 更新启动遮罩上的状态文字（遮罩已撤则静默忽略，不做无效刷新） */
    private void setStartupStatus(int stringRes) {
        if (startupOverlay == null || tvStartupStatus == null) return;
        if (startupOverlay.getVisibility() != View.VISIBLE) return;
        tvStartupStatus.setText(stringRes);
    }

    /** 撤掉启动遮罩：初始化结束，露出主界面 */
    private void hideStartupOverlay() {
        if (startupOverlay != null) startupOverlay.setVisibility(View.GONE);
    }

    /**
     * 检查 Termux 崩溃日志文件，存在则弹窗展示堆栈，并提供「复制」「清除」。
     * Termux 的 CrashHandler 在闪退时会把完整堆栈写入 crash_log.md，
     * 因此用户下次打开本页就能看到上次闪退原因，无需 adb logcat。
     */
    private void showCrashLogIfAny() {
        File[] candidates = {
                new File(TERMUX_CRASH_LOG_FILE),
                new File(TERMUX_CRASH_LOG_BACKUP_FILE)
        };
        File foundFile = null;
        for (File f : candidates) {
            if (f.exists() && f.length() > 0) { foundFile = f; break; }
        }
        if (foundFile == null) return;
        // lambda 中引用的变量必须是 final，这里显式固化
        final File logFile = foundFile;

        final String content;
        try {
            content = readFile(logFile);
        } catch (IOException e) {
            Log.w(LOG_TAG, "read crash log failed: " + e.getMessage());
            return;
        }

        // 堆栈可能很长，弹窗只显示前 4000 字符，完整内容留在剪贴板
        final String display = content.length() > 4000
                ? content.substring(0, 4000) + "\n...\n(已截断，完整堆栈见剪贴板)"
                : content;

        new AlertDialog.Builder(this)
                .setTitle(R.string.crash_dialog_title)
                .setMessage(display)
                .setPositiveButton(R.string.crash_dialog_copy, (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("crash", content));
                    Toast.makeText(this, R.string.crash_dialog_copied, Toast.LENGTH_LONG).show();
                    logFile.delete();  // 复制后清除，避免下次启动再弹
                })
                .setNegativeButton(R.string.crash_dialog_delete, (d, w) -> logFile.delete())
                .setNeutralButton(R.string.crash_dialog_later, null)
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 每次回到主界面（包括从终端会话/网页界面返回）都重新真实探测，
        // 保证按钮状态与事实一致 —— 这是「不靠内存变量」的核心体现
        refreshProjectStatus();
        // 悬浮球补开：启动项目时因缺悬浮窗权限挂了 pending，从系统设置授权返回后，
        // 若项目仍在运行则自动把悬浮球挂上。
        if (mFloatPendingStart && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && Settings.canDrawOverlays(this)) {
            mFloatPendingStart = false;
            new Thread(() -> {
                if (!isPortOpen()) return;      // 项目已停/没起来则不挂
                mainHandler.post(() -> {
                    if (!FloatingWindowService.isRunning()) FloatingWindowService.start(this);
                });
            }, "resume-start-float").start();
        }
        // 每次回到主界面也重新检测悬浮窗权限（含用户从系统设置返回）；
        // 悬浮球改由「退后台自动开启」，只需保证权限已授权
        checkOverlayPermission();
        // 每次回到主界面同步「一键初始化」按钮状态（初始化会话结束后返回即刷新）
        refreshInitStatus();
        // ADB 连接状态：回到主界面重新检测（系统/网页侧配对变化也能同步显示）
        if (mAdbPanel != null) {
            mAdbPanel.refresh();
        }
        // 自动化/排障入口：files/home/.reinit_trigger 存在时自动重跑初始化(幂等)
        maybeAutoReinitFromTrigger();
    }

    @Override
    protected void onDestroy() {
        // ADB 面板解绑：配对结果是无障碍服务/通知输入接收者异步回报的（AdbPairPanel.refreshActive），
        // 页面销毁后不能再往它身上刷状态
        if (mAdbPanel != null) {
            mAdbPanel.detach();
            mAdbPanel = null;
        }
        super.onDestroy();
    }

    /**
     * 自动化/排障触发入口：检测到 <files>/home/.reinit_trigger 标志文件时，
     * 静默删除标志并自动执行初始化（幂等，等价于用户点「重新初始化」并确认）。
     * 正常用户路径不会创建该文件，仅用于 adb 自动化验证与故障恢复。
     */
    private void maybeAutoReinitFromTrigger() {
        File trigger = new File(getFilesDir(), "home/.reinit_trigger");
        if (!trigger.isFile()) return;
        trigger.delete();
        if (hasTermuxSession("init")) return; // 已有初始化会话在跑, 不重复触发
        Toast.makeText(this, R.string.init_reinit_triggered, Toast.LENGTH_SHORT).show();
        doStartInit();
    }

    /**
     * 检查并引导用户开启「显示在其他应用上层」（SYSTEM_ALERT_WINDOW）权限。
     * 该权限是 INSTALL-time 授权，但 Android 6+ 起需要用户在运行时主动开关 Settings.canDrawOverlays。
     * 悬浮球依赖它；Android 10+ 起后台服务启动前台 Activity 也是硬性要求，没开启会导致
     * 「终端 / 启动项目 / 一键初始化」按钮 newSession=true 时 session 卡住。
     */
    private void checkOverlayPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;  // Android 6 以下无此权限
        if (PermissionUtils.checkDisplayOverOtherAppsPermission(this)) return;  // 已开启，静默放行

        // 避免用户连续点击按钮时反复弹窗：复用同一个对话框
        if (mOverlayPermDialog != null && mOverlayPermDialog.isShowing()) return;

        mOverlayPermDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.overlay_perm_title)
                .setMessage(R.string.overlay_perm_message)
                .setPositiveButton(R.string.overlay_perm_action, (d, w) -> {
                    // 跳转到系统设置 → 悬浮窗权限管理页（针对本应用）
                    PermissionUtils.requestDisplayOverOtherAppsPermission(this, REQUEST_OVERLAY_PERM);
                })
                .setNegativeButton(R.string.overlay_perm_later, null)
                .setCancelable(false)
                .show();
    }

    /** 当前正在显示的悬浮窗权限引导对话框，用于避免重复弹窗；dismiss 后会被回收为 null */
    private AlertDialog mOverlayPermDialog = null;

    /** 绑定布局中的控件 */
    /**
     * 权限请求结果回调：转交给 ADB 面板。
     *
     * 为什么必须转发并等回调：Android 13+ 的通知权限对话框挂在当前 Activity 上，
     * 如果在 requestPermissions 之后立刻 startActivity 跳到系统「无线调试」页，
     * 对话框会被压在后台直接消失、回调收到「拒绝」—— 用户永远授权不了，也就看不到
     * 自动配对的通知进度。所以 ADB 面板的流程是「先申请 → 回调里再跳转」。
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (mAdbPanel != null) {
            mAdbPanel.onRequestPermissionsResult(requestCode);
        }
    }

    private void initViews() {
        btnTerminal = findViewById(R.id.btn_terminal);
        btnProject = findViewById(R.id.btn_project);
        btnInit = findViewById(R.id.btn_init);
        btnCheckUpdate = findViewById(R.id.btn_check_update);
        // 启动遮罩（默认可见；初始化跑完由 hideStartupOverlay 撤掉）
        startupOverlay = findViewById(R.id.startup_overlay);
        tvStartupStatus = findViewById(R.id.tv_startup_status);
        // ADB 配对直接内嵌在主界面卡片上（构造即绑定控件并做一次状态检测）
        mAdbPanel = new AdbPairPanel(this);
    }

    /** 设置所有按钮的点击监听 */
    private void setListeners() {
        btnTerminal.setOnClickListener(v -> openTerminal());
        btnProject.setOnClickListener(v -> toggleProject());
        btnInit.setOnClickListener(v -> startInit());
        btnCheckUpdate.setOnClickListener(v -> checkUpdate());
        // 长按「检查更新」= 直接选择更新分支（如切到 dev 测试分支代码）；
        // 「已是最新」时没有弹窗可跳，这条入口保证任何时候都能切分支。
        btnCheckUpdate.setOnLongClickListener(v -> {
            showBranchPickerDialog();
            return true;    // 消费长按，避免松手后又触发一次点击检查
        });
    }

    // ==================== 右上角「终端」按钮 ====================

    /**
     * 打开终端页，优先复用已有会话，绝不无谓新建。
     *
     * 策略（按用户优先级）：
     *   1. 已存在名为 "project" 的存活会话（= 项目正在运行）→ 打开终端页并强制切到
     *      project 会话（把它的 handle 写入 SharedPreferences，TermuxActivity 每次
     *      onStart 都会切到 stored 会话，即使它原来显示的是 debian）。
     *   2. 否则已存在名为 "debian" 的存活会话 → 直接打开终端页复用（TermuxActivity
     *      会自动切到最近使用的会话，即 debian），不发送 RUN_COMMAND。
     *   3. 都没有 → 发送 RUN_COMMAND 创建 debian 会话，并带上 shellName=debian +
     *      NO_SHELL_WITH_NAME 模式兜底（并发下重复点击同名会话也只会建一个）。
     *
     * 点击时立即 Toast 提示，让用户知道按钮生效了。
     */
    private void openTerminal() {
        Toast.makeText(this, R.string.terminal_opening, Toast.LENGTH_SHORT).show();

        // 优先复用 project 会话（启动项目后，用户点终端最想回到服务运行中的终端）
        if (hasTermuxSession("project")) {
            setStoredSession("project");
            TermuxActivity.startTermuxActivity(this);
            return;
        }

        if (hasTermuxSession("debian")) {
            // 已有 debian 会话：仅打开终端页复用，不再发 RUN_COMMAND
            TermuxActivity.startTermuxActivity(this);
            return;
        }

        // 无同名会话：创建（NO_SHELL_WITH_NAME 兜底防重复）
        // 登录前 source proot_env.sh 自愈（确保 /system/bin/sh 版 wrapper 并
        // 通过 PD_PROOT_BIN 让 proot-distro 走它），修复 proot-distro 干净环境下
        // proot CANNOT LINK libtalloc / bash 缺 libandroid-support 的问题。
        TermuxRunner.run(this, ". ~/proot_env.sh 2>/dev/null || true; proot-distro login debian", true,
                "debian", TermuxRunner.SHELL_CREATE_MODE_NO_SHELL_WITH_NAME);
    }

    /**
     * 把指定名称的会话设为「最近使用」，使 TermuxActivity 打开时优先切到它。
     *
     * 原理：TermuxActivity 每次 onStart 都会执行
     *   setCurrentSession(getCurrentStoredSessionOrLast())，
     * 其中 stored 来自 SharedPreferences 的 current session handle；
     * 这里直接写入目标会话的 mHandle，与 TermuxActivity 内部的
     * setCurrentStoredSession() 机制一致，从而覆盖原 stored（可能是 debian）。
     */
    private void setStoredSession(String name) {
        try {
            TermuxShellManager manager = TermuxShellManager.getShellManager();
            if (manager == null) return;   // 服务未启动，跳过（打开后回退到 last 会话）
            for (TermuxSession session : manager.mTermuxSessions) {
                String shellName = session.getExecutionCommand().shellName;
                if (shellName != null && shellName.equals(name)) {
                    TermuxAppSharedPreferences prefs = TermuxAppSharedPreferences.build(this, false);
                    if (prefs != null) prefs.setCurrentSession(session.getTerminalSession().mHandle);
                    return;
                }
            }
        } catch (Exception e) {
            Log.w(LOG_TAG, "setStoredSession failed: " + e.getMessage());
        }
    }

    /**
     * 判断当前进程中是否已有指定名称的存活终端会话。
     * TermuxShellManager 在 Application.onCreate 时 init 为进程内静态单例，
     * 其 mTermuxSessions 是公开列表，App 进程内可直接读取；
     * 已退出会话会被 TermuxService 从列表移除，所以查到的必然是存活会话。
     */
    private static boolean hasTermuxSession(String name) {
        try {
            TermuxShellManager manager = TermuxShellManager.getShellManager();
            if (manager == null) return false;   // 服务未启动（进程刚启动等），视为无会话
            for (TermuxSession session : manager.mTermuxSessions) {
                String shellName = session.getExecutionCommand().shellName;
                if (shellName != null && shellName.equals(name)) return true;
            }
        } catch (Exception e) {
            Log.w(LOG_TAG, "hasTermuxSession failed: " + e.getMessage());
        }
        return false;
    }

    // ==================== 首次初始化引导 ====================

    /**
     * 确保 termux.properties 中 allow-external-apps=true 并重载到内存。
     *
     * 背景：RunCommandService（RUN_COMMAND）按设计是给「外部插件」用的，
     * 默认策略会拒绝一切调用（allow-external-apps 默认 false），包括本 App 自己。
     * 被拒绝后服务进入错误通知流程，旧代码 PendingIntent 缺 FLAG 导致闪退。
     * 这里在 App 每次启动时把属性写死为 true（Termux 官方允许的显式授权），
     * 使本 App 的所有 RUN_COMMAND 调用都能被接受。
     */
    private void ensureAllowExternalAppsEnabled() {
        try {
            File propsFile = new File(TERMUX_PROPERTIES_FILE);
            String key = "allow-external-apps";
            String content = "";
            if (propsFile.exists()) {
                content = readFile(propsFile);
            } else if (propsFile.getParentFile() != null && !propsFile.getParentFile().exists()) {
                propsFile.getParentFile().mkdirs();  // 首次运行，.termux 目录还不存在
            }

            // 逐行处理：已有该属性则强制写 true，没有则追加一行
            boolean found = false;
            StringBuilder out = new StringBuilder();
            for (String line : content.split("\n")) {
                if (line.trim().startsWith(key + "=")) {
                    out.append(key).append("=true\n");
                    found = true;
                } else {
                    out.append(line).append("\n");
                }
            }
            if (!found) out.append(key).append("=true\n");
            writeFile(propsFile, out.toString());

            // 属性已写入磁盘，但内存缓存还是旧值，需重载才立即生效
            TermuxAppSharedProperties properties = TermuxAppSharedProperties.getProperties();
            if (properties == null) {
                properties = TermuxAppSharedProperties.init(this);
            }
            if (properties != null) {
                properties.loadTermuxPropertiesFromDisk();
            }
        } catch (Exception e) {
            // 写属性失败不致命：最多是 RUN_COMMAND 被拒并显示通知（已不再闪退）
            Log.w(LOG_TAG, "ensureAllowExternalAppsEnabled failed: " + e.getMessage());
        }
    }

    /** 读文件全部内容（逐行拼接，行尾统一 \n） */
    private static String readFile(File file) throws IOException {
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = new BufferedReader(new FileReader(file));
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append("\n");
        }
        reader.close();
        return sb.toString();
    }

    /** 写文件全部内容 */
    private static void writeFile(File file, String content) throws IOException {
        FileWriter writer = new FileWriter(file);
        writer.write(content);
        writer.close();
    }

    /**
     * Debian 容器是否已就绪：根文件系统目录存在且关键文件完整。
     * 只查目录会把「安装中断留下的空壳 rootfs」误判为已安装，导致跳过容器安装、
     * login 时容器内 /bin/bash 因缺 glibc 库报
     * "error while loading shared libraries: libc.so: cannot open shared object file"。
     * 判定条件：bin/bash、etc/os-release 存在，且 lib 下能找到 libc.so.6。
     */
    public static boolean isDebianInitialized() {
        for (String dir : DEBIAN_ROOTFS_DIRS) {
            File rootfs = new File(dir);
            if (!rootfs.isDirectory()) continue;
            if (new File(rootfs, "bin/bash").isFile()
                    && new File(rootfs, "etc/os-release").isFile()
                    && hasRootfsLibc(rootfs)) {
                return true;
            }
        }
        return false;
    }

    /** rootfs 是否包含 glibc 动态库（lib/<arch>/libc.so.6 或 usr/lib/<arch>/libc.so.6） */
    private static boolean hasRootfsLibc(File rootfs) {
        File[] archDirs = new File(rootfs, "lib").listFiles();
        if (archDirs != null) {
            for (File d : archDirs) {
                if (d.isDirectory() && new File(d, "libc.so.6").isFile()) return true;
            }
        }
        File[] usrArchDirs = new File(rootfs, "usr/lib").listFiles();
        if (usrArchDirs != null) {
            for (File d : usrArchDirs) {
                if (d.isDirectory() && new File(d, "libc.so.6").isFile()) return true;
            }
        }
        return false;
    }

    /** 项目是否已初始化：容器内 /root/app 是否存在（start.sh 的 cd /root/app 依赖它） */
    public static boolean isProjectInitialized() {
        for (String dir : PROJECT_APP_DIRS) {
            if (new File(dir).isDirectory()) return true;
        }
        return false;
    }

    /**
     * 同步底部「一键初始化」按钮状态。区分三个状态（核心是解决「容器已装但项目没装」时
     * 按钮被禁用、用户无法补初始化的死胡同——之前只看 rootfs 目录，导致启动项目必然报
     * /root/app 不存在且无入口修复）：
     *   - 项目已初始化（/root/app 存在）→ 按钮禁用，文案「已初始化」
     *   - 容器已装但项目未装 → 按钮可用，文案「初始化项目」（init_container.sh 幂等，跳过容器安装）
     *   - 容器未装 → 按钮可用，文案「一键初始化」
     * 每次 onCreate / onResume 时调用，初始化会话结束后返回主界面即自动刷新。
     */
    private void refreshInitStatus() {
        if (isProjectInitialized()) {
            // 项目已就绪：按钮可点击重跑（幂等），便于初始化中断后重试 / 手动更新依赖
            btnInit.setEnabled(true);
            btnInit.setText(R.string.action_reinit);
        } else if (isDebianInitialized()) {
            btnInit.setEnabled(true);
            btnInit.setText(R.string.action_init_project);
        } else {
            btnInit.setEnabled(true);
            btnInit.setText(R.string.action_init);
        }
    }

    /**
     * 首次初始化引导：项目未初始化时弹窗提示（区分「容器未装」与「容器已装但项目未装」）。
     * 弹窗只出现一次（每次冷启动 onCreate 时判断一次）；
     * 用户选「暂不」后，仍可通过底部「一键初始化/初始化项目」按钮再次进入。
     */
    private void showInitGuideDialogIfNeeded() {
        if (isProjectInitialized()) return; // 项目已就绪，无需引导

        final boolean containerInstalled = isDebianInitialized();
        new AlertDialog.Builder(this)
                .setTitle(R.string.init_guide_title)
                .setMessage(containerInstalled
                        ? R.string.init_guide_message_project
                        : R.string.init_guide_message)
                // 「一键初始化/初始化项目」：直接新开会话开始安装
                .setPositiveButton(containerInstalled
                        ? R.string.action_init_project : R.string.action_init,
                        (dialog, which) -> startInit())
                // 「暂不」：关闭弹窗，用户可稍后点底部按钮
                .setNegativeButton(R.string.init_guide_later, null)
                .setCancelable(false) // 禁止点外部关闭，避免误触跳过
                .show();
    }

    /**
     * 新开会话执行一键初始化命令（用户可见进度）。
     *
     * 按容器状态分流：
     *   - 容器未装：优先尝试恢复包(~/restore_container.sh, 本地包/下载/解压即用,
     *     内含 rootfs + 项目依赖 + 宿主 proot 运行时), 成功则跳过全部在线安装;
     *     失败(无恢复包/下载失败)再 pkg update && pkg install proot-distro &&
     *     proot-distro install <镜像> && ~/init_container.sh 兜底。
     *   - 容器已装但项目未装（/root/app 不存在）：直接跑 ~/init_container.sh（其内部幂等：
     *     换源/装工具/装 uv 已装则跳过，git clone 已存在则跳过），补全项目初始化。
     *
     * 关于 debian 镜像参数（真实设备实测调整）：
     *   proot-distro 5.x 默认从 Docker Hub（registry-1.docker.io）拉取 OCI 镜像，
     *   国内网络实测报 <urlopen error [Errno 101] Network is unreachable>（Termux 层 pkg 走
     *   清华镜像正常，但 Docker Hub 不可达）。因此改用 DaoCloud 代理镜像
     *   docker.m.daocloud.io/library/debian（容器名仍为 debian），
     *   并在容器目录已存在时跳过安装（幂等，兼容 installed-rootfs 与 containers 两个路径）。
     */
    private void startInit() {
        // 项目已就绪时重跑是幂等的(容器健康自检 + 依赖重装 + git 更新)，
        // 但为避免误触，先弹确认对话框。
        if (isProjectInitialized()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.init_reinit_title)
                    .setMessage(R.string.init_reinit_message)
                    .setPositiveButton(R.string.action_reinit, (dialog, which) -> showInitBranchPicker())
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }
        // 首次初始化：先问用哪个分支安装（默认勾选当前分支），选完才真正开跑
        showInitBranchPicker();
    }

    /**
     * 初始化前的分支询问。
     *
     * 为什么要问：init_container.sh 的 git clone 是按 ~/repo_branch.txt 决定克隆哪条
     * 分支的，所以「初始化」是唯一能决定装出来是 main 还是 dev 的时机——装好之后再想
     * 换分支就得走「切换分支」（那条路径会自动 fetch + checkout，见 applyUpdateBranch）。
     *
     * 复用「切换分支」的选择器（远端列表 + 自定义输入），只把选完后的动作用回调换成
     * doStartInit；点「使用当前分支」则不改分支直接开跑。
     */
    private void showInitBranchPicker() {
        showBranchPickerDialog(this::doStartInit);
    }

    /** startInit 实际执行体（确认后或首次初始化直接进入） */
    private void doStartInit() {
        // 已有 init 会话在跑(如初始化进行中退出终端页再返回、按钮被 onResume 恢复,
        // 或 App 重启后会话仍在 TermuxService 里):直接切到已有会话,绝不重复新开。
        if (hasTermuxSession("init")) {
            Toast.makeText(this, R.string.init_already_running, Toast.LENGTH_SHORT).show();
            TermuxActivity.startTermuxActivity(this);   // 打开终端页会自动切到最近使用的 init 会话
            return;
        }

        // 用户选过更新分支时，先把分支写入 ~/repo_branch.txt：
        // 容器内 init_container.sh 会按它 git clone 对应分支，避免初始化出来的仍是默认分支。
        syncBranchRecordBeforeInit();

        // 立即禁用按钮，防止初始化会话期间重复点击
        btnInit.setEnabled(false);
        btnInit.setText(R.string.action_init_running);

        // 全新安装场景下 proot 由会话内的 pkg install 安装，App 启动时(bootstrap 阶段)
        // 它还不存在，ensureProotWrapper 建不出 wrapper/proot-real。这里启动轮询，
        // 等 proot 落盘后补做备份与 RUNPATH 修复（proot_env.sh 会等它 30 秒）。
        startProotFixPolling();

        // 开头 echo 一行立即反馈,避免新会话冷启动时终端看起来"没反应";
        // 换源失败不阻断后续步骤;其余步骤严格串联,任一步失败即中止并直接看到错误。
        // 注: pkg install proot 之后必须先 source ~/proot_env.sh 再执行
        // proot-distro install/login —— proot-distro 以干净环境启动 proot,
        // 只有 proot_env.sh 生成的 wrapper(PD_PROOT_BIN)才能免 LD_LIBRARY_PATH 启动。
        final String command;
        if (isDebianInitialized()) {
            // 容器已就绪：只初始化项目，跳过宿主侧 pkg update/install 与 proot-distro install
            command = "echo '[init] 容器已就绪,开始初始化项目(首次约 5-10 分钟)…'; "
                    + ". ~/proot_env.sh 2>/dev/null || true; "
                    + "~/init_container.sh";
        } else {
            // 恢复包优先：先跑 ~/restore_container.sh（本地包 → 下载 → 解压，内含
            // rootfs + 项目依赖 + 宿主 proot 运行时），成功即跳过换源 / pkg update /
            // proot-distro install 全部在线长流程；脚本失败(exit 2, 无恢复包或下载失败)
            // 再走在线安装兜底。脚本依赖 curl/tar（bootstrap 自带），无需先装 proot。
            command = "echo '[init] 开始初始化,请稍候(首次约 5-10 分钟)…'; "
                    + "if [ -f ~/restore_container.sh ] && ~/restore_container.sh; then "
                    + "  echo '[init] 恢复包解压成功, 跳过在线安装'; "
                    + "  . ~/proot_env.sh 2>/dev/null || true; "
                    + "  ~/init_container.sh; "
                    + "else "
                    + "  echo '[init] 未使用恢复包, 走在线安装(较慢)…'; "
                    + "  if [ -f ~/setup_mirror.sh ]; then ~/setup_mirror.sh || echo '[init] 换源失败,继续使用官方源'; fi; "
                    + "  pkg update -y && pkg install -y proot-distro proot libtalloc && "
                    + "  { . ~/proot_env.sh 2>/dev/null || true; } && "
                    + "  if [ ! -d \"$PREFIX/var/lib/proot-distro/containers/debian\" ] && "
                    + "     [ ! -d \"$PREFIX/var/lib/proot-distro/installed-rootfs/debian\" ]; then "
                    + "    proot-distro install docker.m.daocloud.io/library/debian; fi && "
                    + "  ~/init_container.sh; "
                    + "fi";
        }
        // 会话名固定为 "init"，与「终端」按钮的 debian 会话分工明确。
        // 创建模式用 NO_SHELL_WITH_NAME 兜底:与 hasTermuxSession 守卫双保险,
        // 即使并发/竞态下重复点击,同名运行中会话也只会被复用前置,不会无限堆积。
        // 会话正常退出后(初始化完成/失败)列表移除,再次点击会新建,重新初始化不受影响。
        TermuxRunner.run(this, command, true, "init", TermuxRunner.SHELL_CREATE_MODE_NO_SHELL_WITH_NAME);
    }

    // ==================== proot 运行环境修复轮询（仅一键初始化时启用） ====================

    /** Termux fork 真实前缀（与 proot_env.sh 中 PREFIX_DIR 保持一致） */
    private static final String TERMUX_PREFIX_PATH =
            "/data/data/duckyal.KaguraX/files/usr";

    /** 挂在进程级 main looper 上，Activity 销毁也不中断（init 会话在 TermuxService 里继续跑） */
    private static final Handler appMainHandler = new Handler(Looper.getMainLooper());

    /** 轮询任务（static，避免持有 Activity 引用造成泄漏） */
    private static Runnable sProotFixPollTask = null;

    private static int sProotFixPollTicks = 0;

    /**
     * 全新安装时序修复：bootstrap 安装完成时 proot 尚未安装，ensureForkCompatConfig()
     * 里的 ensureProotWrapper() 会因 proot 文件不存在而直接返回。这里轮询等待
     * pkg install proot 落盘（真 ELF、无 proot-real）后，立即调用
     * TermuxInstaller.ensureProotRuntime() 补做备份 wrapper + DT_RUNPATH 修复。
     * proot-real 出现即停止；最多轮询 2400 次（1.5s × 2400 = 60 分钟）。
     * 注意: 清除数据后 pkg update + install 需下载数十个包, 慢网下可能超过
     * 15 分钟, 原 600 次(15 分钟)上限会导致 proot 落盘时轮询已死, 从而
     * proot_env.sh 等不到 proot-real、wrapper 退化泄漏 LD_LIBRARY_PATH。
     */
    private static void startProotFixPolling() {
        if (sProotFixPollTask != null) return;
        sProotFixPollTicks = 0;
        sProotFixPollTask = () -> {
            File prootReal = new File(TERMUX_PREFIX_PATH + "/bin/proot-real");
            if (prootReal.isFile() || ++sProotFixPollTicks > 2400) {
                sProotFixPollTask = null;   // 修复完成或超时，停止轮询
                return;
            }
            File proot = new File(TERMUX_PREFIX_PATH + "/bin/proot");
            if (proot.isFile() && !prootReal.exists()) {
                // pkg install proot 刚落盘（仍是 ELF），子线程修复，避免阻塞 UI
                new Thread(() -> TermuxInstaller.ensureProotRuntime(TERMUX_PREFIX_PATH)).start();
            }
            appMainHandler.postDelayed(sProotFixPollTask, 1500);
        };
        appMainHandler.postDelayed(sProotFixPollTask, 1500);
    }

    // ==================== 中央「启动项目 / 终止项目」按钮 ====================

    /**
     * 点击启停按钮。
     * 每次点击都先做一次真实的端口探测，用探测结果决定是启动还是终止，
     * 完全不用内存变量记忆上一次状态（符合需求「每次以真实探测结果同步」）。
     */
    private void toggleProject() {
        // 探测期间禁用按钮并显示「检测中…」，防止用户连点造成歧义
        btnProject.setEnabled(false);
        btnProject.setText(R.string.action_checking);

        new Thread(() -> {
            final boolean running = isPortOpen();   // 子线程：真实探测端口
            mainHandler.post(() -> {                // 回到主线程决策动作
                btnProject.setEnabled(true);
                if (running) {
                    stopProject();                  // 探测到运行中 → 终止
                } else {
                    startProject();                 // 探测到未运行 → 启动
                }
            });
        }).start();
    }

    /**
     * 新开会话执行 ~/start.sh 启动项目（用户可见日志）。
     *
     * 会话名固定为 "project"：避免出现无名「bash」会话（旧版不传 shellName 时，
     * 会话自动以可执行文件 basename 命名，用户看到的是一个陌生的 bash 终端，
     * 与「终端」按钮的 debian 容器会话显得割裂）。
     * 注意这里不用 NO_SHELL_WITH_NAME：该模式复用到同名会话时只会切换前置、
     * 不会执行新命令，而启动项目必须真正执行 start.sh，因此总是新建（有名字）。
     */
    private void startProject() {
        TermuxRunner.run(this, "~/start.sh", true, "project", null);
        // 项目启动后悬浮球自动挂起：端口就绪(最多等 90 秒)即开悬浮窗
        ensureFloatingWindowWhenUp();
        // 项目启动需要几秒，6 秒后真实探测并刷新按钮文案
        mainHandler.postDelayed(this::refreshProjectStatus, 6000);
    }

    /** 后台静默执行 pkill 终止项目（无需用户观看，BACKGROUND 模式） */
    private void stopProject() {
        // 模式用 [m]ain.py 方括号技巧：pkill 的命令行本身包含该字符串，
        // 若不避开会把自己(宿主 bash)匹配并杀掉(实测 exit 143 自杀)。
        // "main.py" 同时命中容器内两个进程: uv(uv run python main.py) 与
        // python3(python3 main.py), 杀 python3 后 uv 收到 SIGTERM 一并退出。
        // 宿主 pkill 与容器内进程同 uid(u0_a269), 可正常终止。|| true 容错。
        TermuxRunner.run(this, "pkill -f \"[m]ain.py\" || true", false);
        // 项目终止 → 悬浮球一并关闭（悬浮窗生命周期与项目运行绑定）
        try {
            stopService(new Intent(this, FloatingWindowService.class));
        } catch (Exception e) {
            Log.w(LOG_TAG, "stop floating window failed: " + e.getMessage());
        }
        // 等 2 秒让进程退出，再真实探测刷新按钮文案
        mainHandler.postDelayed(this::refreshProjectStatus, 2000);
    }

    /**
     * 刷新按钮状态：子线程探测 127.0.0.1:8000 连通性，
     * 运行中 → 按钮显示「终止项目」，未运行 → 显示「启动项目」。
     */
    private void refreshProjectStatus() {
        new Thread(() -> {
            final boolean running = isPortOpen();
            mainHandler.post(() -> {
                if (!btnProject.isEnabled()) {
                    return; // 若正在探测/操作中则跳过，避免覆盖中间态文案
                }
                btnProject.setText(running
                        ? R.string.action_project_stop
                        : R.string.action_project_start);
            });
        }).start();
    }

    /**
     * 真实探测端口是否可连通（项目运行状态的唯一权威来源）。
     * 用 try-with-resources 确保 Socket 自动关闭，不泄漏文件描述符。
     */
    private boolean isPortOpen() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(SERVICE_HOST, SERVICE_PORT), SOCKET_TIMEOUT_MS);
            return true;    // 能建立 TCP 连接 = 服务正在监听
        } catch (Exception e) {
            return false;   // 连接被拒 / 超时 / 网络异常均视为未运行
        }
    }

    // ==================== 悬浮窗与项目生命周期联动 ====================

    /**
     * 启动项目后自动挂悬浮球：在后台线程等 127.0.0.1:8000 端口就绪（最多 90 秒），
     * 就绪后回主线程：有悬浮窗权限直接开启 FloatingWindowService；无权限则弹窗引导，
     * 并记 mFloatPendingStart，用户去系统设置授权返回后由 onResume 补开。
     */
    private void ensureFloatingWindowWhenUp() {
        new Thread(() -> {
            long deadline = System.currentTimeMillis() + 90_000;
            boolean up = false;
            while (System.currentTimeMillis() < deadline) {
                if (isPortOpen()) { up = true; break; }
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException e) {
                    return;    // 页面销毁等场景：放弃等待
                }
            }
            final boolean ready = up;
            mainHandler.post(() -> {
                if (!ready) return;                       // 服务始终没起来：到终端会话里看日志
                if (FloatingWindowService.isRunning()) return;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        && !Settings.canDrawOverlays(this)) {
                    mFloatPendingStart = true;            // 授权返回后在 onResume 补开
                    checkOverlayPermission();
                    return;
                }
                FloatingWindowService.start(this);
            });
        }, "await-project-float").start();
    }

    // ==================== 底部「检查更新」按钮 ====================

    // ---------- 更新分支的选择与持久化 ----------

    /** 用户显式保存过的更新分支；从未选过返回 null */
    private String getSavedUpdateBranch() {
        SharedPreferences prefs = getSharedPreferences(UPDATE_PREFS_NAME, MODE_PRIVATE);
        String saved = prefs.getString(KEY_UPDATE_BRANCH, null);
        return (saved != null && !saved.trim().isEmpty()) ? saved.trim() : null;
    }

    /**
     * 当前生效的更新分支，优先级：
     *   1. 用户在 App 里选过 → 用它（可随时切换，便于在手机上测分支代码）
     *   2. 容器内记录的分支（~/repo_branch.txt，由更新命令/初始化脚本写入）
     *   3. 仓库默认分支 main
     */
    private String getUpdateBranch() {
        String saved = getSavedUpdateBranch();
        if (saved != null) return saved;

        String local = UpdateChecker.readLocalBranch();
        if (local != null && UpdateChecker.isValidBranchName(local)) return local;

        return UpdateChecker.DEFAULT_BRANCH;
    }

    /** 保存用户选择的分支，并同步写入容器内记录文件（初始化脚本据此 clone 分支） */
    private void setUpdateBranch(String branch) {
        String safe = UpdateChecker.sanitizeBranch(branch);
        getSharedPreferences(UPDATE_PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(KEY_UPDATE_BRANCH, safe)
                .apply();
        writeBranchRecordFile(safe);
    }

    /**
     * 把分支写入 ~/repo_branch.txt（Termux home，App 私有目录，可直接写）。
     * 与容器内脚本共用该文件：初始化脚本按它 clone 分支，更新命令按它记录当前分支。
     */
    private void writeBranchRecordFile(String branch) {
        try (FileWriter writer = new FileWriter(UpdateChecker.BRANCH_FILE_PATH, false)) {
            writer.write(branch + "\n");
        } catch (IOException e) {
            Log.w(LOG_TAG, "写入分支记录失败: " + e.getMessage());
        }
    }

    /** 初始化前同步分支记录：只在用户显式选过分支时写，避免覆盖容器内已有记录 */
    private void syncBranchRecordBeforeInit() {
        String saved = getSavedUpdateBranch();
        if (saved != null && UpdateChecker.isValidBranchName(saved)) {
            writeBranchRecordFile(saved);
        }
    }

    /**
     * 「检查更新」按钮入口。
     * 子线程做网络检测（HttpURLConnection 请求 GitHub API），
     * 结果回到主线程后按情况处理：
     *   - 未安装 / 已最新 / 无网络 / 限流 → Toast 提示
     *   - 有新提交 → 弹窗列出提交清单，提供「更新」「暂不」
     */
    private void checkUpdate() {
        Toast.makeText(this, R.string.update_checking, Toast.LENGTH_SHORT).show();
        runCheckUpdate();
    }

    /**
     * 主界面启动时的静默检查（不显示「正在检查」提示，有更新才弹窗）。
     * 需求「初始化时检测」：每次进入 App 都检查一次，发现新版本再打扰用户。
     */
    private void checkUpdateSilently() {
        runCheckUpdate();
    }

    /** 统一的检测流程：子线程按「当前更新分支」检测 + 主线程分发结果 */
    private void runCheckUpdate() {
        final String branch = getUpdateBranch();
        new Thread(() -> {
            final UpdateChecker.CheckResult result = UpdateChecker.check(branch);
            mainHandler.post(() -> handleUpdateResult(result));
        }).start();
    }

    /**
     * 主线程：根据检测结果决定 UI 行为。
     *
     * 优先级（需求：App 与项目都有更新时，优先引导下载最新的 App）：
     *   1. 网络/接口失败 → Toast，结束
     *   2. App 有新版（apkHasUpdate）→ 一律先引导装新版 App：
     *        · 项目同时有新提交 → showAppFirstDialog（下载 App 为主按钮，项目更新为副操作）
     *        · 项目未安装/无新提交 → showApkUpdateDialog（只提示 App）
     *   3. 只有项目有新提交 → showUpdateDialog（更新项目）
     *   4. 都没有 → 「已是最新」
     */
    private void handleUpdateResult(UpdateChecker.CheckResult result) {
        // 1. 网络/接口失败（APK 字段此时为未知，不做提示）
        if (!result.success) {
            int messageId = result.rateLimited
                    ? R.string.update_rate_limited       // 403 限流，单独文案提示
                    : R.string.update_network_error;     // 无网络等一般错误
            Toast.makeText(this, messageId, Toast.LENGTH_SHORT).show();
            return;
        }

        // 2. App 有新版：无论项目状态如何，一律优先引导下载/安装新版 App
        if (result.apkHasUpdate) {
            if (result.localSha != null && !result.newCommits.isEmpty()) {
                // 2a. App 与项目同时有更新：合并弹窗，下载 App 作为主按钮
                showAppFirstDialog(result);
            } else {
                // 2b. 项目未安装或没有新提交：只弹 App 更新窗
                showApkUpdateDialog(result);
            }
            return;
        }

        // 3. 本地无版本记录 = 项目未安装（首次初始化尚未完成）
        if (result.localSha == null) {
            Toast.makeText(this, R.string.update_not_installed, Toast.LENGTH_SHORT).show();
            return;
        }

        // 4. 项目有新提交 → 弹「更新项目」窗；否则已是最新（都带上当前分支，便于确认在测哪个分支）
        if (result.newCommits.isEmpty()) {
            Toast.makeText(this, getString(R.string.update_up_to_date, result.branch),
                    Toast.LENGTH_SHORT).show();
        } else {
            showUpdateDialog(result);
        }
    }

    /** 把新提交列表拼成多行文本（各更新弹窗共用） */
    private String buildCommitListText(List<UpdateChecker.CommitInfo> commits) {
        StringBuilder sb = new StringBuilder();
        for (UpdateChecker.CommitInfo info : commits) {
            // sha 只取前 7 位，简短易读（GitHub 默认的短 sha 惯例）
            String shortSha = info.sha.length() > 7
                    ? info.sha.substring(0, 7) : info.sha;
            sb.append("\u2022 ").append(info.message)
                    .append(" (").append(info.date).append(")\n")
                    .append("  ").append(shortSha).append("\n");
        }
        return sb.toString();
    }

    /**
     * App 与项目同时有新版本时的合并弹窗（App 优先）：
     *   标题「发现新版 App（项目同步更新）」
     *   内容：App 当前/最新版本 + 项目新提交清单（含当前要更新的分支）
     *   按钮：主按钮「下载最新 App」→ 系统浏览器下载（优先引导装新版 App），
     *         副按钮「仅更新项目」→ 终端里按所选分支 git fetch/checkout + uv sync，
     *         「暂不」→ 关闭
     */
    private void showAppFirstDialog(UpdateChecker.CheckResult result) {
        String message = getString(R.string.update_app_first_message,
                result.apkLocalVersion, result.apkLatestVersion,
                result.newCommits.size(), buildCommitListText(result.newCommits), result.branch);
        new AlertDialog.Builder(this)
                .setTitle(R.string.update_app_first_title)
                .setMessage(message)
                .setPositiveButton(R.string.update_app_first_download,
                        (dialog, which) -> openApkDownload(result.apkDownloadUrl))
                .setNeutralButton(R.string.update_app_first_project_only,
                        (dialog, which) -> performUpdate())
                .setNegativeButton(R.string.update_button_later, null)
                .setCancelable(true)
                .show();
    }

    /**
     * 只有项目有新版本时弹 AlertDialog：
     *   标题「发现新版本」
     *   内容：当前更新分支 + 从新到旧列出所有新提交（「• 描述 (yyyy-MM-dd)\n  sha」）
     *   按钮「更新」「切换分支」「暂不」
     */
    private void showUpdateDialog(UpdateChecker.CheckResult result) {
        String message = getString(R.string.update_available_message,
                result.branch, result.newCommits.size(), buildCommitListText(result.newCommits));
        new AlertDialog.Builder(this)
                .setTitle(R.string.update_available_title)
                .setMessage(message)
                .setPositiveButton(R.string.update_button_now,
                        (dialog, which) -> performUpdate())
                // 副操作：切换要拉取的分支（如 dev），切换后立刻按新分支重新检测
                .setNeutralButton(R.string.update_branch_switch,
                        (dialog, which) -> showBranchPickerDialog())
                .setNegativeButton(R.string.update_button_later, null)
                .setCancelable(true)
                .show();
    }

    /**
     * 只有 APK 更新时的弹窗：显示当前/最新版本，提供「下载 APK」和「切换分支」。
     */
    private void showApkUpdateDialog(UpdateChecker.CheckResult result) {
        String message = getString(R.string.update_apk_message,
                result.apkLocalVersion, result.apkLatestVersion);
        new AlertDialog.Builder(this)
                .setTitle(R.string.update_apk_title)
                .setMessage(message)
                .setPositiveButton(R.string.update_apk_download,
                        (dialog, which) -> openApkDownload(result.apkDownloadUrl))
                // 项目更新入口在这类弹窗里没有，切换分支仍要给到（便于下次更新项目时用 dev）
                .setNeutralButton(R.string.update_branch_switch,
                        (dialog, which) -> showBranchPickerDialog())
                .setNegativeButton(R.string.update_button_later, null)
                .setCancelable(true)
                .show();
    }

    // ==================== 更新分支选择（便于在手机上测试分支代码） ====================

    /** 「检查更新」里的切换分支入口：选完分支即切过去并拉代码（见 applyUpdateBranch） */
    private void showBranchPickerDialog() {
        showBranchPickerDialog(null);
    }

    /**
     * 打开分支选择：子线程拉远端分支列表，回到主线程弹单选列表。
     * 拉取失败（无网/限流）不阻断，直接转手动输入分支名。
     *
     * @param onPicked 选完分支后的回调；null = 更新场景，非 null = 初始化场景
     *                 （见 showInitBranchPicker）。两种场景共用列表/输入部分，
     *                 差别只在「选完之后干什么」。
     */
    private void showBranchPickerDialog(Runnable onPicked) {
        Toast.makeText(this, R.string.update_branch_loading, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            final List<String> branches = UpdateChecker.listBranches();
            mainHandler.post(() -> {
                if (branches == null) {
                    Toast.makeText(this, R.string.update_branch_fetch_failed, Toast.LENGTH_SHORT).show();
                    showCustomBranchDialog(getUpdateBranch(), onPicked);
                    return;
                }
                showBranchListDialog(getUpdateBranch(), branches, onPicked);
            });
        }).start();
    }

    /**
     * 分支单选列表：远端分支 + 保证「当前分支」「默认分支」一定在列表里，
     * 末尾追加「自定义输入…」。
     *
     * 负按钮按场景区分：初始化场景是「使用当前分支」（点了不换分支直接开跑），
     * 更新场景仍是「暂不」（纯关闭）。
     */
    private void showBranchListDialog(String current, List<String> remoteBranches, Runnable onPicked) {
        List<String> candidates = new ArrayList<>(remoteBranches);
        if (!candidates.contains(current)) {
            candidates.add(0, current);       // 当前分支（可能已删除/未出现在接口里）放最前
        }
        if (!candidates.contains(UpdateChecker.DEFAULT_BRANCH)) {
            candidates.add(UpdateChecker.DEFAULT_BRANCH);
        }

        final String[] items = new String[candidates.size() + 1];
        for (int i = 0; i < candidates.size(); i++) {
            String name = candidates.get(i);
            items[i] = name.equals(current) ? "✓ " + name : name;   // 当前分支打勾
        }
        items[items.length - 1] = getString(R.string.update_branch_custom);

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(onPicked != null ? R.string.init_branch_title : R.string.update_branch_title)
                .setItems(items, (dialog, which) -> {
                    if (which == items.length - 1) {
                        showCustomBranchDialog(current, onPicked);
                    } else {
                        applyUpdateBranch(candidates.get(which), onPicked);
                    }
                });
        if (onPicked != null) {
            // 初始化场景：给一个「不改分支，直接开始」的出口
            builder.setNegativeButton(R.string.init_branch_keep, (dialog, which) -> onPicked.run());
        } else {
            builder.setNegativeButton(R.string.update_button_later, null);
        }
        builder.show();
    }

    /** 手动输入分支名（列表取不到或想用列表外的分支时） */
    private void showCustomBranchDialog(String current, Runnable onPicked) {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        // 分支名是英文：用 URI 变体键盘（偏英文、无联想），避免中文输入法直接输入出非法名字
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint(R.string.update_branch_custom_hint);
        input.setText(current);
        input.setSelection(input.getText().length());   // 光标置尾，方便直接续写或删除

        // Builder 只有 setView(View) 这一个重载（没有带 padding 的版本）：
        // 外面套一层容器做左右留白，避免输入框贴到弹窗边缘
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        FrameLayout container = new FrameLayout(this);
        container.setPadding(pad, pad / 2, pad, 0);
        container.addView(input);

        new AlertDialog.Builder(this)
                .setTitle(R.string.update_branch_custom_title)
                .setView(container)
                .setPositiveButton(R.string.common_confirm, (dialog, which) -> {
                    String branch = input.getText().toString().trim();
                    if (!UpdateChecker.isValidBranchName(branch)) {
                        Toast.makeText(this, R.string.update_branch_invalid, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    applyUpdateBranch(branch, onPicked);
                })
                .setNegativeButton(R.string.update_button_later, null)
                .show();
    }

    /**
     * 保存分支选择 → 同步容器记录文件（~/repo_branch.txt）→ 按场景分发。
     *
     * 三种去向：
     *   1. 初始化场景（onPicked 非空）：立刻开跑初始化，容器内 git clone 按新分支拉代码；
     *   2. 更新场景且项目已装：直接 performUpdate()（git fetch + checkout -f -B <分支>）
     *      —— 即使该分支一个提交都没更新，也会真的切过去并 uv sync，
     *      「切换分支即下载对应分支代码」靠的就是这条；
     *   3. 更新场景但项目未装：没有 /root/app 可切，仅按新分支重新检测。
     */
    private void applyUpdateBranch(String branch, Runnable onPicked) {
        setUpdateBranch(branch);
        String saved = getUpdateBranch();

        if (onPicked != null) {
            Toast.makeText(this, getString(R.string.update_branch_saved, saved), Toast.LENGTH_SHORT).show();
            onPicked.run();
            return;
        }
        if (isProjectInitialized()) {
            Toast.makeText(this, getString(R.string.update_branch_switching, saved), Toast.LENGTH_SHORT).show();
            performUpdate();
        } else {
            Toast.makeText(this, getString(R.string.update_branch_saved, saved), Toast.LENGTH_SHORT).show();
            runCheckUpdate();
        }
    }

    /**
     * 打开系统浏览器下载/查看新 APK。
     * 有直接下载地址走下载；否则兜底跳到 Release 页（用户自行选择 asset）。
     */
    private void openApkDownload(String url) {
        String target = (url == null || url.isEmpty())
                ? "https://github.com/Duckyal/KaguraX/releases/latest"
                : url;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(target)));
        } catch (Exception e) {
            Toast.makeText(this, R.string.update_network_error, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 执行更新：新开会话（用户可见进度）在容器内执行：
     *   cd /root/app && export PATH="$HOME/.local/bin:$PATH" &&
     *   git reset --hard HEAD && git fetch --depth 1 origin <分支> &&
     *   git checkout -f -B <分支> FETCH_HEAD &&
     *   (which adb >/dev/null 2>&1 || (apt update && apt install -y adb)) && uv sync
     * 全部成功后把 commit SHA 写回 ~/repo_version.txt、分支名写回 ~/repo_branch.txt
     * （两个文件都在 Termux home，App 用 Java File API 直接读）。
     *
     * 分支：默认 main，可在「切换分支」里改为 dev 等分支，便于在手机上测试分支代码；
     * 分支名经 UpdateChecker.isValidBranchName 校验后才拼进命令（防命令注入）。
     *
     * 注意：
     *   - 命令整体通过 proot-distro login debian 在容器内执行，
     *     所以里面是容器语法（/root/app、apt）。
     *   - 两个记录文件用 Termux 绝对路径写入，
     *     proot 容器可访问 /data/data/ 下的文件，App 才能用 Java API 读到。
     *   - Java 字符串里嵌 shell 单引号无需转义，双引号需转义为 \"。
     *   - 只安装 adb，不再装 scrcpy：Debian trixie 主源没有 scrcpy 包，
     *     且本项目用自带的 scrcpy-server.jar + app_process 投屏，不依赖系统 scrcpy。
     */
    private void performUpdate() {
        // 分支名走 sanitizeBranch 兜底，确保拼进 shell 的一定是合法值
        final String branch = UpdateChecker.sanitizeBranch(getUpdateBranch());

        // 更新前先 source proot_env.sh 自愈 proot 运行环境（libtalloc/PD_PROOT_BIN）
        String command = ". ~/proot_env.sh 2>/dev/null || true; proot-distro login debian -- bash -c '"
                + "cd /root/app && export PATH=\"$HOME/.local/bin:$PATH\" && "
                // git reset --hard：丢弃容器内本地未提交改动（如 uv sync 时产生的 uv.lock 变更），
                // 否则切换分支/更新会因本地改动报 "would be overwritten by merge" 中止。/root/app 是纯代码副本，
                // 无本地私有代码，未跟踪文件（运行配置等）不受影响。
                + "git reset --hard HEAD && "
                // 显式拉取所选分支并强制切过去：浅克隆下 FETCH_HEAD 即该分支最新提交，
                // checkout -B 让本地分支一并指向它，等效「切分支 + 更新到最新」一步完成；
                // 分支名写错时 fetch 直接失败，后续被 && 短路（终端能看到报错）。
                + "git fetch --depth 1 origin " + branch + " && "
                + "git checkout -f -B " + branch + " FETCH_HEAD && "
                + "(which adb >/dev/null 2>&1 || (apt update && apt install -y adb)) && "
                + "uv sync && "
                + "git rev-parse HEAD > /data/data/duckyal.KaguraX/files/home/repo_version.txt && "
                + "git rev-parse --abbrev-ref HEAD > /data/data/duckyal.KaguraX/files/home/repo_branch.txt"
                + "'";
        // 会话名固定为 "update"，与 debian / project / init 会话分工明确
        TermuxRunner.run(this, command, true, "update", null);
        Toast.makeText(this, getString(R.string.update_started, branch), Toast.LENGTH_SHORT).show();
    }

}
