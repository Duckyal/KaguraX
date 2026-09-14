package com.termux.app;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.termux.R;
import com.termux.shared.notification.NotificationUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * 悬浮球服务（本项目新增）。
 *
 * 在任意界面（游戏、桌面、其他 App）之上显示一个只能贴边停靠的悬浮球：
 *   - 单击悬浮球：展开/收起遥控页（页面为 /float，无任何窗口装饰，直接铺满小窗）
 *   - 拖动悬浮球：只能吸附在屏幕左/右边缘（完整可见贴边），纵向可停在任意位置
 *   - 展开的小窗无壳：没有标题栏/边框/圆角/阴影，就是遥控页本身
 *       · 尺寸固定且不可拖动：竖屏约 300×520；横屏自动压缩高度不溢出屏
 *       · 展开位置贴悬浮球所在的一侧屏幕边缘
 *       · 展开期间悬浮球自动隐藏（不另外占用屏幕边缘），
 *       · 收起通道：
 *           1) 遥控页左上角 KaguraX 徽标经 KaguraXBridge.collapse() JS 桥收起；
 *           2) 系统返回键 / 全面屏返回手势 —— 在窗口内容层最顶层（根视图 dispatchKeyEvent）
 *              拦截：网页可后退时先后退，无历史则收起小窗
 *       （“点击/双击窗口外收起”不可行：窗外触摸不派发给悬浮窗，ACTION_OUTSIDE 在
 *        游戏/多数 ROM 上也收不到，实测无效）
 *   - 长按悬浮球：直接停止服务（仅球态响应，线态太细容易误触）
 *
 * 悬浮球有两种形态（本版新增，解决「悬浮窗挡住找图找字」）：
 *   - 球态：40dp 圆形图标 + 状态光环，可点/可拖
 *   - 线态：贴屏幕边缘的一条细线（默认白色；任务运行中变为色相循环的彩色线），
 *           点一下即可唤回球态
 *   球态展示后自动收起成线：空闲 5s，任务运行中 1s（用户要求「任务状态 1s 隐藏」）。
 *   摇一摇手机也可直接唤回球态（游戏时不用瞄准那条细线）。
 *
 * 悬浮窗不能污染找图输入，这里用的是「按需挂载」而不是 {@code FLAG_SECURE}：
 *   实测 Android 14（SDK 34）：FLAG_SECURE 并不会让该层从截图里消失 —— SurfaceFlinger
 *   捕获时把它渲染成一块不透明黑矩形，且窗口内容 GONE（遥控小窗已收起）时黑块照样在，
 *   比原本的彩色面板更干扰模板匹配。所以：
 *     - 悬浮球（用户要一直看到）：不带 flag，正常进截图；任务运行时 1s 自动收成线，
 *       占屏仅 33×154 px 且贴边，对找图影响可忽略；
 *     - 遥控小窗 / 日志气泡：显示时 addView、隐藏时 removeView，收起后彻底不留 layer，
 *       截图里既不遮挡也不留黑块。
 *   注意：不能用系统 Toast 做日志气泡 —— Toast 由系统进程构造，应用无法卸载它，
 *   必然进截图，所以气泡是自绘的 overlay 窗口。
 *
 * 实现要点：
 *   - 前台服务 + 常驻通知，保证在游戏内不被回收；
 *   - SYSTEM_ALERT_WINDOW 悬浮窗权限（Manifest 已声明，启动前仍需检查 Settings.canDrawOverlays）；
 *   - 拖动时 updateViewLayout 必须传 addView 时添加的顶层 View，
 *     对子 View 调用会抛 IllegalArgumentException；
 *   - 悬浮球的位置记录到 SharedPreferences，下次启动恢复。
 */
public class FloatingWindowService extends Service {

    private static final String LOG_TAG = "FloatingWindowService";

    private static final String NOTIFICATION_CHANNEL_ID = "floating_window";
    private static final int NOTIFICATION_ID = 26410;

    /** 悬浮遥控页地址（/float，小窗默认加载；网页里可链到完整控制台 /home） */
    private static final String SERVICE_URL = "http://127.0.0.1:8000/float";

    private static final String PREFS_NAME = "floating_window_prefs";
    private static final String PREF_BALL_LEFT = "ball_left";
    private static final String PREF_BALL_Y = "ball_y";

    /** 悬浮球直径（dp） */
    private static final int BALL_SIZE_DP = 40;
    /** 贴边时与屏幕边缘的间隙（dp，仅球态；线态是贴在边缘上的） */
    private static final int EDGE_MARGIN_DP = 6;

    /** 线态：可见线宽（dp）、线长（dp）、窗口宽度（dp） */
    private static final int LINE_WIDTH_DP = 4;
    private static final int LINE_HEIGHT_DP = 56;
    /** 线态窗口比线本身宽一点：留出绘制与点击余量（线居中画在窗口里） */
    private static final int LINE_WINDOW_W_DP = 12;

    /** 悬浮小窗竖屏目标尺寸(dp) */
    private static final int WINDOW_W_DP = 300;
    private static final int WINDOW_H_DP = 520;

    /** 任务运行状态查询地址与超时（后端 /api/task_status，空 device=任一设备） */
    private static final String TASK_STATUS_URL = "http://127.0.0.1:8000/api/task_status";
    private static final int STATUS_TIMEOUT_MS = 1200;

    /** 状态轮询周期（毫秒）：要在任务开始/结束后 1s 内收起球，轮询不能慢于 1s */
    private static final long MONITOR_INTERVAL_MS = 1000;

    /** 球态展示后自动收起成线的时间：空闲 5s / 任务运行中 1s */
    private static final long AUTO_HIDE_IDLE_MS = 5000;
    private static final long AUTO_HIDE_RUNNING_MS = 1000;

    /** 摇一摇：加速度偏离重力阈值（m/s²）与防抖间隔（毫秒） */
    private static final float SHAKE_THRESHOLD = 13.0f;
    private static final long SHAKE_DEBOUNCE_MS = 900;

    /** 日志气泡：停留时长、两次弹出最小间隔（限流）、文本最长字符数 */
    private static final long LOG_BUBBLE_DURATION_MS = 2200;
    private static final long LOG_BUBBLE_MIN_INTERVAL_MS = 1200;
    private static final int LOG_BUBBLE_MAX_CHARS = 56;

    /** 状态光环：球体外缘留白(dp)与光环线宽(dp) */
    private static final int RING_GAP_DP = 5;
    private static final int RING_STROKE_DP = 3;

    /** 状态光环颜色：任务执行中=旋转绿弧；空闲=静止灰弧 */
    private static final int COLOR_RUNNING = 0xFF00E676;
    private static final int COLOR_RUNNING_DIM = 0x5900E676;
    private static final int COLOR_IDLE = 0xFF9E9E9E;
    private static final int COLOR_IDLE_DIM = 0x339E9E9E;

    /** 线态颜色：空闲=白线；运行中=按色相循环取色（见 LineView） */
    private static final int COLOR_LINE_IDLE = 0xFFFFFFFF;

    /** 服务是否运行中（同进程静态标记，应用退后台自动开启时据此避免重复启动） */
    private static boolean sRunning = false;

    public static boolean isRunning() {
        return sRunning;
    }

    /** 统一启动入口（适配 Android 8+ 前台服务）。
     *  调用方：MainActivity(启动项目端口就绪后主动开启) /
     *  TermuxApplication(应用退后台且项目仍在运行兜底开启)。 */
    public static void start(Context context) {
        Intent intent = new Intent(context, FloatingWindowService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    private WindowManager mWindowManager;

    /** 悬浮球窗口：顶层容器内含「球态内容」与「线态内容」两套子视图，按状态切可见性 */
    private WindowManager.LayoutParams mBallParams;
    private View mBallView;
    private View mBallCircleView;
    private LineView mBallLineView;
    private RingView mRingView;

    private WindowManager.LayoutParams mWindowParams;
    private View mWindowView;
    private WebView mWebView;
    private boolean mWindowVisible = false;
    private boolean mWebViewPaused = false;

    /** 日志气泡窗口（自绘 overlay；系统 Toast 加不了 FLAG_SECURE，不能用） */
    private WindowManager.LayoutParams mBubbleParams;
    private TextView mBubbleView;
    private boolean mBubbleVisible = false;

    /** 悬浮球是否已「隐藏成线」（true=线态，false=球态） */
    private boolean mBallCollapsed = false;

    /** 展开的小窗当前贴靠屏幕哪一侧边缘（true=右边缘，false=左边缘） */
    private boolean mWindowDockedRight = false;

    /** 拖动状态 */
    private float mTouchDownRawX, mTouchDownRawY;
    private int mTouchStartX, mTouchStartY;
    private boolean mDragging;
    /** 本次按下时悬浮球是否处于线态（线态只响应单击，不跟手拖动） */
    private boolean mCollapsedAtDown;
    private int mTouchSlop;

    private ObjectAnimator mRingAnimator;
    private ValueAnimator mLineColorAnimator;

    /** 是否正在执行任务（轮询线程查后端后回主线程刷新，驱动光环与线色） */
    private volatile boolean mTaskRunning = false;
    /** 轮询线程开关（onDestroy 置 false 退出） */
    private volatile boolean mMonitorEnabled = false;
    private Thread mMonitorThread;
    /** 已消费的日志序号：随轮询带上，后端据此增量返回新日志（去重，不重复弹气泡） */
    private volatile long mLastLogSeq = 0;

    /** 摇一摇唤出 */
    private SensorManager mSensorManager;
    private SensorEventListener mShakeListener;
    private long mLastShakeAt = 0;

    /** UI 线程 Handler：自动收起计时、气泡计时都挂在这上面 */
    private final Handler mUiHandler = new Handler(Looper.getMainLooper());
    /** 球态展示超时 → 收起成线 */
    private final Runnable mAutoHideRunnable = () -> collapseBall();
    /** 气泡到时 → 淡出 */
    private final Runnable mBubbleHideRunnable = () -> fadeOutLogBubble();

    /** 气泡限流与同文本合并 */
    private long mBubbleShownAt = 0;
    private String mBubbleLastRaw = "";
    private int mBubbleRepeat = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        // 悬浮窗权限被关闭时直接退出（例如用户从系统设置里撤销权限）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Log.w(LOG_TAG, "No overlay permission, stopping");
            stopSelf();
            return;
        }

        mWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        mTouchSlop = ViewConfiguration.get(this).getScaledTouchSlop();

        int screenW = mWindowManager.getDefaultDisplay().getWidth();
        int screenH = mWindowManager.getDefaultDisplay().getHeight();

        setupNotification();
        createBall(screenW, screenH);
        createWindow(screenW, screenH);
        createLogBubble();
        startShakeDetect();
        startMonitoring();   // 轮询任务状态 + 顺带增量取日志
        sRunning = true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY; // 被杀后系统会尝试重建，重建即恢复悬浮球
    }

    @Override
    public void onDestroy() {
        sRunning = false;
        stopRingAnimation();
        stopLineColorAnimation();
        mUiHandler.removeCallbacks(mAutoHideRunnable);
        mUiHandler.removeCallbacks(mBubbleHideRunnable);
        stopShakeDetect();
        mMonitorEnabled = false;
        if (mMonitorThread != null) {
            mMonitorThread.interrupt();   // 打断轮询休眠，线程随即退出
            mMonitorThread = null;
        }
        removeView(mBallView);
        removeView(mWindowView);
        removeView(mBubbleView);
        if (mWebView != null) {
            mWebView.removeAllViews();
            mWebView.destroy();
            mWebView = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ==================== 前台服务通知 ====================

    /** 常驻通知：点击回到项目管理器主界面 */
    private void setupNotification() {
        NotificationUtils.setupNotificationChannel(this, NOTIFICATION_CHANNEL_ID,
                getString(R.string.floating_notification_channel_name),
                android.app.NotificationManager.IMPORTANCE_LOW);

        Intent contentIntent = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 0, contentIntent, flags);

        Notification notification = NotificationUtils.geNotificationBuilder(this,
                NOTIFICATION_CHANNEL_ID,
                Notification.PRIORITY_LOW,
                getString(R.string.floating_notification_title),
                getString(R.string.floating_notification_text),
                getString(R.string.floating_notification_text),
                pi, null,
                NotificationUtils.NOTIFICATION_MODE_SILENT).build();
        startForeground(NOTIFICATION_ID, notification);
    }

    // ==================== 悬浮球（球态 / 线态） ====================

    /**
     * 创建悬浮球窗口：顶层容器 = 球态内容（光环 + 图标） + 线态内容（细线），
     * 两者按 {@link #mBallCollapsed} 切换可见性，窗口尺寸随之变化。
     *
     * 悬浮球常驻可见（用户要一直看到它），因此不能加 FLAG_SECURE —— 加了会在截图里
     * 变成一块黑矩形（Android 14 实测），反而污染找图。它靠「任务运行时 1s 自动收成
     * 贴边细线」把占屏面积压到最小（33×154 px）来降低对匹配的影响。
     */
    private void createBall(int screenW, int screenH) {
        int ballSize = dp(BALL_SIZE_DP);
        int ringGap = dp(RING_GAP_DP);
        int box = ballSize + ringGap * 2;

        FrameLayout root = new FrameLayout(this);

        // ---- 球态内容：状态光环 + 图标 ----
        FrameLayout circle = new FrameLayout(this);
        RingView ring = new RingView(this, dp(RING_STROKE_DP));
        ring.setClickable(false);
        ring.setFocusable(false);
        ring.configure(COLOR_IDLE, COLOR_IDLE_DIM);   // 初始未知状态：静止灰弧
        circle.addView(ring, new FrameLayout.LayoutParams(box, box));
        mRingView = ring;
        mRingAnimator = ObjectAnimator.ofFloat(ring, View.ROTATION, 0f, 360f);
        mRingAnimator.setDuration(1800);
        mRingAnimator.setRepeatCount(ObjectAnimator.INFINITE);
        mRingAnimator.setInterpolator(new LinearInterpolator());

        ImageView ball = new ImageView(this);
        ball.setImageResource(R.drawable.ic_float_ball);
        ball.setContentDescription(getString(R.string.action_floating_ball));
        ball.setClickable(false);
        ball.setFocusable(false);
        circle.addView(ball, new FrameLayout.LayoutParams(ballSize, ballSize, Gravity.CENTER));

        root.addView(circle, new FrameLayout.LayoutParams(box, box));
        mBallCircleView = circle;

        // ---- 线态内容：贴屏幕边缘的细线 ----
        LineView line = new LineView(this, dp(LINE_WIDTH_DP));
        line.setClickable(false);
        line.setFocusable(false);
        line.setVisibility(View.GONE);
        root.addView(line, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        mBallLineView = line;

        // 彩色流动：色相 0~360 循环，所有颜色都走一遍 → 视觉上就是「彩色变换线」
        mLineColorAnimator = ValueAnimator.ofFloat(0f, 360f);
        mLineColorAnimator.setDuration(2400);
        mLineColorAnimator.setRepeatCount(ValueAnimator.INFINITE);
        mLineColorAnimator.setInterpolator(new LinearInterpolator());
        mLineColorAnimator.addUpdateListener(a -> line.setHue((float) a.getAnimatedValue()));

        mBallParams = new WindowManager.LayoutParams(
                box, box,
                overlayType(),
                // 不能加 FLAG_SECURE：Android 14 上它会被截图渲染成黑块（见类注释实测说明），
                // 反而污染找图。球/线本就贴边且任务运行时自动收成线，直接让它进截图更安全。
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        mBallParams.gravity = Gravity.TOP | Gravity.START;

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        mBallParams.y = prefs.getInt(PREF_BALL_Y, screenH / 3);

        // 单击：球态=展开/收起小窗；线态=唤回球态。拖动换边（仅球态），长按停止服务（仅球态）
        root.setOnTouchListener(makeDragListener(root, mBallParams, v -> onBallClick()));
        root.setOnLongClickListener(v -> {
            if (mBallCollapsed) return false;   // 线态太细，不响应长按，避免误触停服
            Toast.makeText(this, R.string.floating_toast_stopped, Toast.LENGTH_SHORT).show();
            stopSelf();
            return true;
        });

        mWindowManager.addView(root, mBallParams);
        mBallView = root;

        // 启动即球态（贴边完整显示），随后按 5s/1s 自动收成线
        applyBallVisualState(prefs.getBoolean(PREF_BALL_LEFT, false));
        scheduleAutoHide();
    }

    /** 单击悬浮球：线态→唤回球态；球态→展开/收起遥控小窗 */
    private void onBallClick() {
        if (mBallCollapsed) {
            expandBall();
        } else {
            toggleWindow();
        }
    }

    /** 切入线态（球态展示超时后调用） */
    private void collapseBall() {
        if (mWindowVisible || mBallCollapsed) return;   // 面板展开时不动球
        mBallCollapsed = true;
        applyBallVisualState(isBallOnLeft());
        saveBallPosition();
    }

    /** 唤回球态并重置自动收起计时（摇一摇、点击线条、收起小窗都走这里） */
    private void expandBall() {
        if (mWindowVisible) return;   // 小窗展开时球本就隐藏
        mBallCollapsed = false;
        applyBallVisualState(isBallOnLeft());
        scheduleAutoHide();
    }

    /**
     * 按当前形态摆放悬浮球：改窗口尺寸、切子视图可见性、贴到指定边缘。
     * 球态与线态都以「球心纵向位置」为锚点，切换形态时视觉位置不跳。
     *
     * @param onLeft 是否贴屏幕左边缘
     */
    private void applyBallVisualState(boolean onLeft) {
        if (mBallView == null || mBallParams == null) return;
        int screenW = mWindowManager.getDefaultDisplay().getWidth();
        int screenH = mWindowManager.getDefaultDisplay().getHeight();

        // 换尺寸前先算出当前中心的纵向位置，换完再按新高度摆回去
        int centerY = mBallParams.y + mBallParams.height / 2;

        if (mBallCollapsed) {
            mBallParams.width = dp(LINE_WINDOW_W_DP);
            mBallParams.height = dp(LINE_HEIGHT_DP);
            mBallCircleView.setVisibility(View.GONE);
            mBallLineView.setVisibility(View.VISIBLE);
        } else {
            int box = dp(BALL_SIZE_DP) + dp(RING_GAP_DP) * 2;
            mBallParams.width = box;
            mBallParams.height = box;
            mBallCircleView.setVisibility(View.VISIBLE);
            mBallLineView.setVisibility(View.GONE);
        }
        updateLineAppearance();

        int margin = mBallCollapsed ? 0 : dp(EDGE_MARGIN_DP);
        mBallParams.x = onLeft ? margin : screenW - mBallParams.width - margin;
        mBallParams.y = clamp(centerY - mBallParams.height / 2, 0, screenH - mBallParams.height);

        try {
            mWindowManager.updateViewLayout(mBallView, mBallParams);
        } catch (Exception e) {
            Log.w(LOG_TAG, "updateViewLayout failed: " + e.getMessage());
        }

        // 气泡只在「球隐藏成线」时出现
        if (!mBallCollapsed) hideLogBubbleNow();
        else if (mBubbleVisible) positionLogBubble();
    }

    /** 悬浮球是否贴在屏幕左半边（拖动换边后据此决定贴哪侧） */
    private boolean isBallOnLeft() {
        if (mBallParams == null) return false;
        int screenW = mWindowManager.getDefaultDisplay().getWidth();
        return (mBallParams.x + mBallParams.width / 2) < screenW / 2;
    }

    /** 拖动松手：无条件吸附到最近的左/右边缘（球态与线态都贴边） */
    private void snapBallToEdge() {
        if (mBallView == null || mBallParams == null) return;
        applyBallVisualState(isBallOnLeft());
    }

    /** 把视图位置限制在屏幕内（拖动悬浮球时防止拖出屏幕找不回来） */
    private void clampPosition(WindowManager.LayoutParams params, View view) {
        int screenW = mWindowManager.getDefaultDisplay().getWidth();
        int screenH = mWindowManager.getDefaultDisplay().getHeight();
        int viewW = view.getWidth();
        int viewH = view.getHeight();
        params.x = Math.max(0, Math.min(params.x, Math.max(0, screenW - viewW)));
        params.y = Math.max(0, Math.min(params.y, Math.max(0, screenH - viewH)));
    }

    private void saveBallPosition() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_BALL_LEFT, isBallOnLeft())
                .putInt(PREF_BALL_Y, mBallParams.y)
                .apply();
    }

    // ==================== 自动收起计时 ====================

    /** 重置自动收起计时：空闲 5s、任务运行中 1s 后把球收成线；小窗展开时不计时 */
    private void scheduleAutoHide() {
        mUiHandler.removeCallbacks(mAutoHideRunnable);
        if (mWindowVisible) return;
        mUiHandler.postDelayed(mAutoHideRunnable,
                mTaskRunning ? AUTO_HIDE_RUNNING_MS : AUTO_HIDE_IDLE_MS);
    }

    private void cancelAutoHide() {
        mUiHandler.removeCallbacks(mAutoHideRunnable);
    }

    // ==================== 摇一摇唤出 ====================

    /** 注册加速度传感器：摇动手机唤回球态（游戏里不必瞄准那条细线去点） */
    private void startShakeDetect() {
        mSensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (mSensorManager == null) return;
        Sensor accel = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (accel == null) {
            Log.w(LOG_TAG, "No accelerometer, shake-to-show unavailable");
            return;
        }
        mShakeListener = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent event) {
                float x = event.values[0], y = event.values[1], z = event.values[2];
                double g = Math.sqrt(x * x + y * y + z * z);
                if (Math.abs(g - SensorManager.GRAVITY_EARTH) < SHAKE_THRESHOLD) return;
                long now = System.currentTimeMillis();
                if (now - mLastShakeAt < SHAKE_DEBOUNCE_MS) return;   // 防抖：一次摇动只算一次
                mLastShakeAt = now;
                mUiHandler.post(FloatingWindowService.this::onShake);
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) { }
        };
        // SENSOR_DELAY_UI（约 15Hz）足够识别摇动，比 GAME 省电
        mSensorManager.registerListener(mShakeListener, accel, SensorManager.SENSOR_DELAY_UI);
    }

    private void stopShakeDetect() {
        if (mSensorManager != null && mShakeListener != null) {
            try {
                mSensorManager.unregisterListener(mShakeListener);
            } catch (Exception ignored) { }
        }
        mShakeListener = null;
    }

    /** 摇一摇：只负责「唤出」，不负责收起——否则玩游戏晃手机会把球晃没 */
    private void onShake() {
        if (mWindowVisible) return;        // 小窗展开时不响应
        if (!mBallCollapsed) {             // 已是球态：只续期，不打断
            scheduleAutoHide();
            return;
        }
        expandBall();
    }

    // ==================== 线态外观 ====================

    /** 按任务状态切换线条配色：空闲=白线静止；运行中=色相循环的彩色线 */
    private void updateLineAppearance() {
        if (mBallLineView == null) return;
        mBallLineView.setColorful(mTaskRunning);
        if (mTaskRunning) {
            if (mLineColorAnimator != null && !mLineColorAnimator.isStarted()) {
                mLineColorAnimator.start();
            }
        } else {
            stopLineColorAnimation();
        }
    }

    private void stopLineColorAnimation() {
        if (mLineColorAnimator != null && mLineColorAnimator.isStarted()) {
            mLineColorAnimator.cancel();
        }
        if (mBallLineView != null) {
            mBallLineView.setHue(0f);
            mBallLineView.invalidate();
        }
    }

    private void startRingAnimation() {
        if (mRingView == null || mRingAnimator == null) return;
        if (!mRingAnimator.isStarted()) {
            mRingView.setRotation(0f);
            mRingAnimator.start();
        }
    }

    private void stopRingAnimation() {
        if (mRingAnimator != null && mRingAnimator.isStarted()) {
            mRingAnimator.cancel();
        }
        if (mRingView != null) {
            mRingView.setRotation(0f);
        }
    }

    /**
     * 状态光环自定义 View：画一整圈半透明圆环 + 两段高亮弧。
     * 高亮弧在 onDraw 里固定角度，视觉旋转由外部对 View 做 rotation 动画实现。
     */
    private static final class RingView extends View {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float mStrokeWidth;
        private int mBrightColor = COLOR_IDLE;
        private int mDimColor = COLOR_IDLE_DIM;

        RingView(Context context, float strokeWidth) {
            super(context);
            mStrokeWidth = strokeWidth;
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeCap(Paint.Cap.ROUND);
        }

        /** 切换状态配色：任务执行中=绿，空闲=灰 */
        void configure(int bright, int dim) {
            if (mBrightColor == bright && mDimColor == dim) return;
            mBrightColor = bright;
            mDimColor = dim;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float radius = Math.min(cx, cy) - mStrokeWidth / 2f - 1f;
            if (radius <= 0) return;
            RectF bounds = new RectF(cx - radius, cy - radius, cx + radius, cy + radius);
            mPaint.setStrokeWidth(mStrokeWidth);
            // 整圈底色环（很淡）
            mPaint.setColor(mDimColor);
            canvas.drawCircle(cx, cy, radius, mPaint);
            // 主高亮弧 + 小尾弧（-90° 起画，视觉上从顶部开始）
            mPaint.setColor(mBrightColor);
            canvas.drawArc(bounds, -90f, 90f, false, mPaint);
            canvas.drawArc(bounds, 135f, 26f, false, mPaint);
        }
    }

    /**
     * 线态自定义 View：贴屏幕边缘的一条细线。
     * 空闲=白线；任务运行中=色相循环的彩色线（hue 由 ValueAnimator 驱动）。
     */
    private static final class LineView extends View {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float mLineWidth;
        private final float[] mHsv = new float[]{0f, 1f, 1f};
        private float mHue = 0f;
        private boolean mColorful = false;

        LineView(Context context, float lineWidth) {
            super(context);
            mLineWidth = lineWidth;
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeCap(Paint.Cap.ROUND);
            mPaint.setStrokeWidth(lineWidth);
            mPaint.setColor(COLOR_LINE_IDLE);
        }

        void setColorful(boolean colorful) {
            if (mColorful == colorful) return;
            mColorful = colorful;
            invalidate();
        }

        void setHue(float hue) {
            mHue = hue;
            if (mColorful) invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth() / 2f;
            float half = mLineWidth / 2f;
            float top = half + 1f;
            float bottom = getHeight() - half - 1f;
            if (bottom <= top) return;
            if (mColorful) {
                mHsv[0] = mHue % 360f;
                mPaint.setColor(Color.HSVToColor(mHsv));
            } else {
                mPaint.setColor(COLOR_LINE_IDLE);
            }
            canvas.drawLine(cx, top, cx, bottom, mPaint);
        }
    }

    // ==================== 任务状态轮询 + 日志增量 ====================

    /** /api/task_status 的解析结果 */
    private static final class Status {
        boolean running;
        String taskName = "";
        long logSeq = 0;
        final List<LogItem> logs = new ArrayList<>();
    }

    /** 一条日志（气泡一次只显示最后一条） */
    private static final class LogItem {
        String message = "";
        String level = "info";
    }

    /** 请求后端：任务运行状态 + since_seq 之后的新日志（顺带，不额外建通道）。
     *  悬浮球只在项目运行时才存在，这里只关心「有没有任务在跑」来点亮光环/线色。 */
    private Status fetchStatus() {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(TASK_STATUS_URL + "?since_seq=" + mLastLogSeq);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(STATUS_TIMEOUT_MS);
            conn.setReadTimeout(STATUS_TIMEOUT_MS);
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
            InputStream in = conn.getInputStream();
            StringBuilder sb = new StringBuilder();
            byte[] buf = new byte[1024];
            int n;
            while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n, "UTF-8"));

            JSONObject obj = new JSONObject(sb.toString());
            Status st = new Status();
            st.running = obj.optBoolean("running", false);
            st.taskName = obj.optString("task_name", "");
            st.logSeq = obj.optLong("log_seq", mLastLogSeq);
            JSONArray arr = obj.optJSONArray("logs");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    LogItem item = new LogItem();
                    item.message = o.optString("message", "");
                    item.level = o.optString("level", "info");
                    if (!item.message.isEmpty()) st.logs.add(item);
                }
            }
            return st;
        } catch (Exception e) {
            return null;   // 后端未响应 → 一律按空闲处理，不影响悬浮球存在
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 后台轮询线程：周期查询任务状态与新增日志；有变化时回主线程刷新 UI */
    private void startMonitoring() {
        mMonitorEnabled = true;
        mMonitorThread = new Thread(() -> {
            Boolean lastRunning = null;
            while (mMonitorEnabled) {
                Status st = fetchStatus();
                if (st != null) {
                    final boolean running = st.running;
                    final List<LogItem> logs = st.logs;
                    // 消费掉已取回的日志序号：无论本轮是否真的弹了气泡，都不再重复取
                    mLastLogSeq = st.logSeq;
                    if (lastRunning == null || running != lastRunning) {
                        new Handler(Looper.getMainLooper()).post(() -> refreshBallUI(running));
                    }
                    lastRunning = running;
                    if (!logs.isEmpty()) {
                        final LogItem last = logs.get(logs.size() - 1);
                        new Handler(Looper.getMainLooper()).post(() -> showLogBubble(last));
                    }
                }
                try {
                    Thread.sleep(MONITOR_INTERVAL_MS);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "float-task-monitor");
        mMonitorThread.setDaemon(true);
        mMonitorThread.start();
    }

    /** 主线程：按任务状态刷新光环与线色；任务刚开始时收起小窗、回球态（1s 后自动成线） */
    private void refreshBallUI(boolean running) {
        if (mBallView == null) return;
        boolean changed = running != mTaskRunning;
        mTaskRunning = running;

        if (running) {
            mRingView.configure(COLOR_RUNNING, COLOR_RUNNING_DIM);
            startRingAnimation();
        } else {
            mRingView.configure(COLOR_IDLE, COLOR_IDLE_DIM);
            stopRingAnimation();
        }
        updateLineAppearance();

        if (changed) {
            if (running && mWindowVisible) {
                // 任务启动：收起遥控小窗（回到球态），随后 1s 自动收成线
                collapseWindow();
            } else {
                scheduleAutoHide();
            }
        }
    }

    // ==================== 日志气泡 ====================

    /** 创建日志气泡：自绘的胶囊文本窗，不可触摸（绝不拦游戏点击）；按需挂载，不常驻 */
    private void createLogBubble() {
        TextView tv = new TextView(this);
        tv.setTextSize(12f);
        tv.setTextColor(0xFFFFFFFF);
        tv.setMaxLines(1);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setPadding(dp(10), dp(6), dp(10), dp(6));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xCC000000);        // 半透明黑底
        bg.setCornerRadius(dp(14));     // 胶囊
        tv.setBackground(bg);
        tv.setVisibility(View.GONE);
        tv.setClickable(false);
        tv.setFocusable(false);

        mBubbleParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        mBubbleParams.gravity = Gravity.TOP | Gravity.START;
        // 这里不 addView：气泡只在真正要显示时才挂载，消失即卸载，避免残留 layer 进截图
        mBubbleView = tv;
    }

    /**
     * 弹一条日志气泡。触发条件与节流：
     *   - 只在「球已隐藏成线」且小窗未展开时出现（用户要求气泡仅在球隐藏时显示）；
     *   - 两次弹出间隔不小于 LOG_BUBBLE_MIN_INTERVAL_MS（日志很密时天然丢中间态，
     *     永远只展示最新状态，避免屏幕边缘一直闪）；
     *   - 同一条文本连续出现时合并成「×N」。
     */
    private void showLogBubble(LogItem item) {
        if (mBubbleView == null || item == null) return;
        if (!mBallCollapsed || mWindowVisible) return;

        long now = System.currentTimeMillis();
        if (now - mBubbleShownAt < LOG_BUBBLE_MIN_INTERVAL_MS) return;

        String raw = item.message;
        if (raw.equals(mBubbleLastRaw)) {
            mBubbleRepeat++;
        } else {
            mBubbleRepeat = 1;
            mBubbleLastRaw = raw;
        }
        String text = raw.length() > LOG_BUBBLE_MAX_CHARS
                ? raw.substring(0, LOG_BUBBLE_MAX_CHARS) + "…" : raw;
        if (mBubbleRepeat > 1) text = text + "  ×" + mBubbleRepeat;

        mBubbleView.setText(text);
        mBubbleView.setTextColor(colorForLevel(item.level));
        mBubbleShownAt = now;

        positionLogBubble();
        if (!mBubbleVisible) {
            mBubbleView.setVisibility(View.VISIBLE);
            mBubbleView.setAlpha(0f);
            // 首次弹（或上次已卸载）才挂载窗口；用完即卸，截图里不留 layer
            if (mBubbleView.getParent() == null) {
                addView(mBubbleView, mBubbleParams);
            }
            mBubbleView.animate().alpha(1f).setDuration(160).start();
            mBubbleVisible = true;
        }
        mUiHandler.removeCallbacks(mBubbleHideRunnable);
        mUiHandler.postDelayed(mBubbleHideRunnable, LOG_BUBBLE_DURATION_MS);
    }

    /** 气泡出现在线条内侧：线在左边缘→气泡在线右侧；线在右边缘→气泡在线左侧 */
    private void positionLogBubble() {
        if (mBubbleView == null || mBallParams == null) return;
        int screenW = mWindowManager.getDefaultDisplay().getWidth();
        int screenH = mWindowManager.getDefaultDisplay().getHeight();

        mBubbleView.measure(
                View.MeasureSpec.makeMeasureSpec(screenW, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(screenH, View.MeasureSpec.AT_MOST));
        int w = mBubbleView.getMeasuredWidth();
        int h = mBubbleView.getMeasuredHeight();

        int gap = dp(6);
        boolean onLeft = isBallOnLeft();
        int x = onLeft
                ? mBallParams.width + gap                    // 线在左 → 气泡在线的右侧
                : screenW - mBallParams.width - gap - w;     // 线在右 → 气泡在线的左侧
        int centerY = mBallParams.y + mBallParams.height / 2;

        mBubbleParams.x = clamp(x, 0, Math.max(0, screenW - w));
        mBubbleParams.y = clamp(centerY - h / 2, 0, Math.max(0, screenH - h));
        // 未挂载时只更新参数（下次 addView 生效），已挂载才真正重排
        if (mBubbleView.getParent() != null) {
            try {
                mWindowManager.updateViewLayout(mBubbleView, mBubbleParams);
            } catch (Exception e) {
                Log.w(LOG_TAG, "bubble layout failed: " + e.getMessage());
            }
        }
    }

    /** 立即隐藏气泡（切回球态/展开小窗/销毁时调用） */
    private void hideLogBubbleNow() {
        mUiHandler.removeCallbacks(mBubbleHideRunnable);
        if (mBubbleView == null || !mBubbleVisible) return;
        mBubbleVisible = false;
        mBubbleView.animate().cancel();
        mBubbleView.setVisibility(View.GONE);
        removeView(mBubbleView);   // 卸载 layer，避免"看不见但截图里有黑块"
    }

    /** 气泡到时淡出 */
    private void fadeOutLogBubble() {
        if (mBubbleView == null || !mBubbleVisible) return;
        mBubbleVisible = false;
        mBubbleView.animate().alpha(0f).setDuration(200)
                .withEndAction(() -> {
                    if (mBubbleView != null) {
                        mBubbleView.setVisibility(View.GONE);
                        removeView(mBubbleView);
                    }
                })
                .start();
    }

    /** 日志级别配色 */
    private int colorForLevel(String level) {
        if (level == null) return 0xFFEDEDED;
        switch (level) {
            case "error":   return 0xFFFF6B6B;
            case "warning": return 0xFFFFCE54;
            case "success": return 0xFF6BE58C;
            default:        return 0xFFEDEDED;
        }
    }

    // ==================== 悬浮小窗 ====================

    @SuppressLint("SetJavaScriptEnabled")
    private void createWindow(int screenW, int screenH) {
        mWindowView = LayoutInflater.from(this).inflate(R.layout.floating_window, null);
        mWindowView.setVisibility(View.GONE); // 初始只显示悬浮球

        int[] winSize = computeWindowSize();
        mWindowParams = new WindowManager.LayoutParams(
                winSize[0], winSize[1],
                overlayType(),
                // 不加 NOT_FOCUSABLE：页面输入框需要能获得焦点弹出键盘。
                // 注意：不要依赖 FLAG_WATCH_OUTSIDE_TOUCH 做“点击窗口外收起”——该事件
                // 在可聚焦 overlay + 游戏/多数 ROM 场景收不到，收起已交给悬浮球完成
                // 不加 FLAG_SECURE：它会让整块小窗在截图里变成黑矩形（见类注释实测说明）
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        mWindowParams.gravity = Gravity.TOP | Gravity.START;

        mWebView = mWindowView.findViewById(R.id.floating_webview);
        WebSettings settings = mWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        // 用 UA 后缀自我标识悬浮窗小窗环境：遥控页据此区分 env-float(小窗) / env-full(桌面)
        settings.setUserAgentString(settings.getUserAgentString() + " KaguraXFloat/1.0");
        mWebView.setWebViewClient(new WebViewClient());

        // 网页内「收起」按钮经此桥收起小窗；JS 回调在 WebView 线程，需切主线程操作窗口
        Handler main = new Handler(Looper.getMainLooper());
        mWebView.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void collapse() {
                main.post(FloatingWindowService.this::collapseWindow);
            }
        }, "KaguraXBridge");

        // 系统返回键 / 全面屏返回手势兜底：在窗口内容层最顶层（根视图 dispatchKeyEvent）
        // 拦截。返回键无论焦点落在窗口内哪个子 View（含 WebView 网页内部）都会先经过根
        // 视图的分发，这里一定能收到：网页可后退时先后退，无历史则收起小窗。
        ((FloatPanelRoot) mWindowView).setBackRunnable(() -> {
            if (mWebView != null && mWebView.canGoBack()) {
                mWebView.goBack();
            } else {
                collapseWindow();
            }
        });

        // 这里不 addView：展开时才挂载，收起即卸载，避免"收起了但截图里仍有大黑块"
    }

    /** 展开/收起小窗 */
    private void toggleWindow() {
        if (mWindowVisible) {
            collapseWindow();
        } else {
            showWindow();
        }
    }

    private void showWindow() {
        if (mWindowView == null) return;
        cancelAutoHide();
        hideLogBubbleNow();   // 展开小窗时不再弹气泡（面板里本来就有日志）
        // 首次展开才加载页面；此后收起只隐藏，浏览状态保留
        if (mWebView.getUrl() == null) {
            mWebView.loadUrl(SERVICE_URL);
        }
        if (mWebViewPaused) {
            mWebView.onResume();
            mWebViewPaused = false;
        }

        // 每次展开都按当前屏幕适配尺寸（服务可能竖屏启动后进横屏游戏，不能沿用旧方向的尺寸）
        adaptWindowToScreen();

        int screenW = mWindowManager.getDefaultDisplay().getWidth();
        int screenH = mWindowManager.getDefaultDisplay().getHeight();
        int w = mWindowParams.width;
        int h = mWindowParams.height;
        // 悬浮球在哪一侧，小窗就贴该侧屏幕边缘展开
        boolean ballLeft = isBallOnLeft();
        mWindowDockedRight = !ballLeft;
        mWindowParams.x = ballLeft ? 0 : screenW - w;
        mWindowParams.y = clamp(mBallParams.y, 0, Math.max(0, screenH - h));
        // 展开期间隐藏悬浮球（不再占屏幕另一侧）；收起入口在遥控页左上角徽标 + 系统返回键
        mBallView.setVisibility(View.GONE);

        mWindowView.setVisibility(View.VISIBLE);
        if (mWindowView.getParent() == null) {
            mWindowManager.addView(mWindowView, mWindowParams);
        } else {
            mWindowManager.updateViewLayout(mWindowView, mWindowParams);
        }
        mWindowVisible = true;
    }

    private void collapseWindow() {
        if (mWindowView == null) return;
        mWindowView.setVisibility(View.GONE);
        removeView(mWindowView);   // 卸载 layer：只 GONE 的话截图里仍是整块黑
        if (mWebView != null) {
            mWebView.onPause();
            mWebViewPaused = true;
        }
        mWindowVisible = false;
        // 收起：悬浮球回到「球态」并贴在小窗所在的那一侧，随后按 5s/1s 自动收成线
        mBallCollapsed = false;
        applyBallVisualState(mWindowDockedRight ? false : true);
        mBallView.setVisibility(View.VISIBLE);
        scheduleAutoHide();
        saveBallPosition();
    }

    // ==================== 屏幕适配 ====================

    /** 按当前屏幕方向/尺寸计算小窗宽高：竖屏默认 300×520，横屏压缩到可用高度内（不低于 240dp） */
    private int[] computeWindowSize() {
        int screenW = mWindowManager.getDefaultDisplay().getWidth();
        int screenH = mWindowManager.getDefaultDisplay().getHeight();
        int w = Math.min(dp(WINDOW_W_DP), screenW - dp(40));
        int h;
        if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
            // 横屏物理高度小：窗口接近满高、上下留边距，操作区不至于太局促
            h = Math.max(dp(240), screenH - dp(48));
        } else {
            // 竖屏：用目标小尺寸，并给小屏留出裕量（顶部避让、底部留白）
            h = Math.min(dp(WINDOW_H_DP), screenH - dp(96));
        }
        return new int[]{w, h};
    }

    /**
     * 屏幕方向/尺寸变化后，把小窗与悬浮球调整到新屏幕内：
     * 小窗按新方向重新计算宽高并保持贴靠原边缘；悬浮球保证不落到屏幕外。
     */
    private void adaptWindowToScreen() {
        if (mWindowView == null || mWindowParams == null) return;
        int screenW = mWindowManager.getDefaultDisplay().getWidth();
        int screenH = mWindowManager.getDefaultDisplay().getHeight();

        int[] size = computeWindowSize();
        if (mWindowParams.width != size[0] || mWindowParams.height != size[1]) {
            mWindowParams.width = size[0];
            mWindowParams.height = size[1];
            if (mWindowVisible) {
                // 展开中：旋转后仍贴靠原来的边缘（按新屏幕重新对齐）
                mWindowParams.x = mWindowDockedRight ? screenW - size[0] : 0;
            } else {
                mWindowParams.x = Math.max(0, Math.min(mWindowParams.x, screenW - size[0]));
            }
            mWindowParams.y = Math.max(0, Math.min(mWindowParams.y, screenH - size[1]));
            // 未挂载（已收起）时只更新参数，等下次展开时用新尺寸挂载
            if (mWindowVisible && mWindowView.getParent() != null) {
                mWindowManager.updateViewLayout(mWindowView, mWindowParams);
            }
        }
        if (mBallParams == null) return;
        applyBallVisualState(isBallOnLeft());
        if (mBubbleVisible) positionLogBubble();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 服务常驻后台，屏幕方向切换（例如进入横屏游戏）后窗口尺寸需跟随适配
        adaptWindowToScreen();
    }

    // ==================== 拖动与点击 ====================

    /**
     * 通用拖动监听：按住移动改变窗口位置，松手且未发生位移（<touchSlop）时视为点击。
     * 球态拖动中自由跟手（方便换边），松手时无条件吸附到最近的屏幕边缘。
     * 线态不跟手拖动（窗口很窄，容易误拖），但超过 touchSlop 的滑动不会再被当成点击，
     * 避免手指划过屏幕边缘时误唤出球。
     *
     * @param window  窗口的顶层 View（即 addView 时添加的那个 View，updateViewLayout 用它）
     * @param params  目标窗口的 LayoutParams（x/y 会被更新）
     * @param onClick 点击回调（可为 null）
     */
    private View.OnTouchListener makeDragListener(final View window,
                                                  final WindowManager.LayoutParams params,
                                                  final View.OnClickListener onClick) {
        return (v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mTouchDownRawX = event.getRawX();
                    mTouchDownRawY = event.getRawY();
                    mTouchStartX = params.x;
                    mTouchStartY = params.y;
                    mDragging = false;
                    mCollapsedAtDown = (window == mBallView && mBallCollapsed);
                    cancelAutoHide();   // 交互期间不自动收起
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = event.getRawX() - mTouchDownRawX;
                    float dy = event.getRawY() - mTouchDownRawY;
                    if (!mDragging && (Math.abs(dx) > mTouchSlop || Math.abs(dy) > mTouchSlop)) {
                        mDragging = true;
                    }
                    if (mDragging && !mCollapsedAtDown) {
                        params.x = mTouchStartX + (int) dx;
                        params.y = mTouchStartY + (int) dy;
                        clampPosition(params, window);
                        // 拖动中自由跟手（悬浮球可被拖到屏幕中间以便换边），吸附在松手时处理
                        mWindowManager.updateViewLayout(window, params);
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    if (mDragging) {
                        mDragging = false;
                        if (window == mBallView) {
                            // 松手：无条件吸附到最近的左/右边缘
                            snapBallToEdge();
                            saveBallPosition();
                        }
                    } else if (onClick != null) {
                        onClick.onClick(v);
                    }
                    if (window == mBallView) scheduleAutoHide();   // 交互结束重新计时
                    return true;
                }
            }
            return false;
        };
    }

    private void removeView(View view) {
        if (view != null && view.getParent() != null) {
            try {
                mWindowManager.removeView(view);
            } catch (Exception e) {
                Log.w(LOG_TAG, "removeView failed: " + e.getMessage());
            }
        }
    }

    /** 挂载窗口（幂等：已挂载则跳过），配合 removeView 实现「按需挂载」 */
    private void addView(View view, WindowManager.LayoutParams params) {
        if (view == null || view.getParent() != null) return;
        try {
            mWindowManager.addView(view, params);
        } catch (Exception e) {
            Log.w(LOG_TAG, "addView failed: " + e.getMessage());
        }
    }

    /** Android 8.0+ 用 TYPE_APPLICATION_OVERLAY，旧版本用 TYPE_PHONE */
    private int overlayType() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        }
        return WindowManager.LayoutParams.TYPE_PHONE;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private int clamp(int value, int min, int max) {
        if (max < min) return min;
        return Math.max(min, Math.min(value, max));
    }
}
