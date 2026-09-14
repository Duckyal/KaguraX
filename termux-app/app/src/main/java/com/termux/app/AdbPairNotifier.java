package com.termux.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.RemoteInput;

import com.termux.R;

/**
 * AdbPairNotifier —— 自动配对/连接流程的进度与结果通知。
 *
 * 为什么用通知而不是悬浮窗：Android 12 起系统「设置」持有 HIDE_OVERLAY_WINDOWS 权限，
 * 会主动隐藏第三方悬浮窗（TYPE_APPLICATION_OVERLAY，防点击劫持）—— 而配对端口与配对码
 * 只在系统「无线调试」页上显示，浮窗在那一页完全不可见。
 *
 * 但光靠通知也不够：它要用户下拉状态栏才看得见，而人在系统页干等时不会去下拉。
 * 所以分工是 ——「过程」主要靠 Toast 即时反馈（见 AdbPairAccessibilityService.report），
 * 通知负责两件事：
 *   · 进度留痕：静默、不可划掉，用户切走后再回来还能回看走到哪一步了；
 *   · 结果提醒：重要性 HIGH，成功/失败以横幅弹出 + 声音，确保不会错过。
 *
 * 实现取舍：
 *   · 进度固定一个通知 id 原地刷新，不刷屏；
 *   · 结果用独立 id + 独立高优先级渠道：渠道重要性一旦创建就不能由 App 修改（只能引导用户
 *     去系统设置改），所以「静默进度」和「会响的结果」必须分属两个渠道 id，不能共用再改属性；
 *   · 所有方法都吞异常：通知只是「汇报」，任何失败都不该影响配对流程本身。
 */
final class AdbPairNotifier {

    /** 进度渠道：静默（过程反馈主要走 Toast，这里只做留痕） */
    private static final String CHANNEL_PROGRESS = "adb_pair_progress";

    /** 结果渠道：重要性 HIGH，成功/失败会以横幅弹出 */
    private static final String CHANNEL_RESULT = "adb_pair_result";

    /** 手动填写渠道：可填写通知（用户照系统页自己填端口/配对码），重要性 HIGH 才好被注意到 */
    private static final String CHANNEL_MANUAL = "adb_pair_manual";

    /** 进度通知 id：全程复用同一条，状态原地更新 */
    private static final int PROGRESS_ID = 20031;

    /** 结果通知 id：独立一条，发布前把进度那条收掉 */
    private static final int RESULT_ID = 20032;

    /** 手动填写的通知 id：配对与连接共用同一条原地切换，不在通知栏里堆两条 */
    private static final int MANUAL_ID = 20033;

    /** 「回复」PendingIntent 的 requestCode：配对与连接必须区分，否则后建的会覆盖前一个 */
    private static final int REQUEST_PAIR_INPUT = 1;
    private static final int REQUEST_CONNECT_INPUT = 2;

    private AdbPairNotifier() {
        // 纯工具类，禁止实例化
    }

    /** 进度（静默更新；不可划掉，避免用户误划后看不到后续结果） */
    static void progress(Context context, String text) {
        clearResult(context);   // 新一轮开始：先把上一轮的结论收掉（详见 clearResult）
        post(context, CHANNEL_PROGRESS, PROGRESS_ID, context.getString(R.string.adb_notif_title),
                text, true, true);
    }

    /**
     * 收掉上一条「结果」通知（成功 / 失败）。
     *
     * 为什么需要它：结果那条是独立 id + 可划掉的横幅，不会因为新一轮开始就自己消失 ——
     * 上一轮配对失败的通知会一直挂在通知栏里，用户下一轮都跑完了还看得见，分不清哪个才是
     * 这次的结论。所以每次发新进度或新输入框之前先把旧结论清掉：通知栏里永远只留
     * 「当前这一轮」的东西。
     */
    static void clearResult(Context context) {
        cancel(context, RESULT_ID);
    }

    /** 成功（高优先级横幅提醒 + 可划掉；手动填写那条也一并收掉） */
    static void success(Context context, String address) {
        dismissProgress(context);
        cancel(context, MANUAL_ID);
        post(context, CHANNEL_RESULT, RESULT_ID, context.getString(R.string.adb_notif_title),
                context.getString(R.string.adb_notif_success, address), false, false);
    }

    /** 失败（高优先级横幅提醒；正文说明原因与下一步怎么办） */
    static void fail(Context context, String reason) {
        dismissProgress(context);
        post(context, CHANNEL_RESULT, RESULT_ID, context.getString(R.string.adb_notif_fail_title),
                reason, false, false);
    }

    // ==================== 手动填写（可填写通知） ====================

    /**
     * 让用户照系统页填「配对端口 + 6 位配对码」。
     *
     * 这是手动兜底的入口：配对端口与配对码只在系统「无线调试」页上显示，而悬浮窗在那一页会被
     * 系统的 HIDE_OVERLAY_WINDOWS 隐藏（见 {@link AdbPairInputReceiver} 的类注释），
     * 通知栏是唯一「在那一页上一定看得见、还能直接输入」的载体。
     */
    static void manualAskPair(Context context) {
        postManual(context, context.getString(R.string.adb_manual_pair_title),
                context.getString(R.string.adb_manual_pair_text),
                context.getString(R.string.adb_manual_pair_reply_label),
                AdbPairInputReceiver.ACTION_PAIR, REQUEST_PAIR_INPUT);
    }

    /** 配对失效后重新取码：同样开配对输入，但前面加一句说明为什么又回来了 */
    static void manualAskPairAgain(Context context) {
        postManual(context, context.getString(R.string.adb_manual_pair_title),
                context.getString(R.string.adb_hint_re_authenticate) + "\n\n"
                        + context.getString(R.string.adb_manual_pair_text),
                context.getString(R.string.adb_manual_pair_reply_label),
                AdbPairInputReceiver.ACTION_PAIR, REQUEST_PAIR_INPUT);
    }

    /**
     * 同一条通知换成「填连接端口」。
     *
     * @param afterPair 刚配对完（true），还是已配对过、直接重连（false）。只差正文开头那半句：
     *                  重连的人并没有刚做过配对，「配对成功。」会让他以为自己漏掉了什么步骤。
     */
    static void manualAskConnectPort(Context context, boolean afterPair) {
        postManual(context, context.getString(R.string.adb_manual_connect_title),
                context.getString(afterPair ? R.string.adb_manual_connect_text
                        : R.string.adb_manual_connect_text_reconnect),
                context.getString(R.string.adb_manual_connect_reply_label),
                AdbPairInputReceiver.ACTION_CONNECT, REQUEST_CONNECT_INPUT);
    }

    /**
     * 配对/连接进行中：原地刷新文案并撤掉输入框，避免结果没回来又被提交一次。
     *
     * @param detail 显示在文案里的东西：配对阶段是配对端口，连接阶段是完整地址（用户可能是长按
     *               复制系统页那行粘过来的，把 host 一并显示出来，填错地址一眼就能看出来）。
     */
    static void manualRunning(Context context, boolean pairStep, String detail) {
        postManual(context,
                context.getString(pairStep ? R.string.adb_manual_pair_title
                        : R.string.adb_manual_connect_title),
                context.getString(pairStep ? R.string.adb_manual_pair_running
                        : R.string.adb_manual_connect_running, detail),
                null, null, 0);
    }

    /**
     * 这一步没成（输入没认出来 / 配对失败 / 连接失败）：**保留输入框**，只是把原因顶到正文里。
     *
     * 手动流程失败后用户多半还想就着新数字再试一次，所以不能像自动流程那样直接换成结果横幅 ——
     * 那样输入入口就没了，用户得回 App 重新点一遍。
     */
    static void manualRetry(Context context, boolean pairStep, String reason) {
        postManual(context,
                context.getString(pairStep ? R.string.adb_manual_pair_title
                        : R.string.adb_manual_connect_title),
                reason,
                context.getString(pairStep ? R.string.adb_manual_pair_reply_label
                        : R.string.adb_manual_connect_reply_label),
                pairStep ? AdbPairInputReceiver.ACTION_PAIR : AdbPairInputReceiver.ACTION_CONNECT,
                pairStep ? REQUEST_PAIR_INPUT : REQUEST_CONNECT_INPUT);
    }

    /**
     * 发（或更新）手动填写的通知。
     *
     * replyLabel 为 null 表示这一步只要用户看着，不需要输入（进行中）：此时不带「回复」按钮。
     * 常驻且不可划掉 —— 用户可能分几次看系统页再填，通知不能在中间被清掉。
     */
    private static void postManual(Context context, String title, String text, String replyLabel,
                                   String action, int requestCode) {
        try {
            clearResult(context);   // 手动流程也是新一轮：上一轮的结论先收掉，别混在当前输入旁边
            NotificationManager manager = (NotificationManager)
                    context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null) return;
            ensureChannel(context, manager, CHANNEL_MANUAL);

            NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_MANUAL)
                    .setSmallIcon(R.drawable.ic_terminal)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setOnlyAlertOnce(true)      // 反复更新不再响，只在第一次提醒用户来看
                    .setOngoing(true)            // 不可划掉：用户得能随时回来接着填
                    .setAutoCancel(false);
            // 故意不设 setContentIntent（保持 null）：这条通知的用途是让用户对着系统「无线调试」
            // 页抄数字，而点通知主体若跳回 App，会立刻把那一页盖住、数字当场看不见 —— 更别说
            // 用户在通知上滑动或点空白处极易误触。去掉后点主体没有任何动作（多数系统会就地展开），
            // 输入入口只剩下面那个「回复」按钮，不会再把人从系统页上弹走。

            if (replyLabel != null) {
                RemoteInput remoteInput = new RemoteInput.Builder(AdbPairInputReceiver.KEY_INPUT)
                        .setLabel(replyLabel)
                        .build();
                builder.addAction(new NotificationCompat.Action.Builder(
                        R.drawable.ic_terminal, replyLabel,
                        replyIntent(context, action, requestCode))
                        .addRemoteInput(remoteInput)
                        .build());
            }

            manager.notify(MANUAL_ID, builder.build());
        } catch (Exception ignored) {
            // 同 post()：未授予通知权限等情况静默忽略，不影响其他流程
        }
    }

    /**
     * 「回复」按钮的 PendingIntent —— 用户敲进去的内容由 SystemUI 回填到这个 Intent。
     *
     * 可变性必须显式处理：Android 12+ 要求二选一，而带 RemoteInput 的**只能**是 MUTABLE ——
     * 输入结果是以 fillInIntent 的形式回填进来的，Immutable 会把这次回填整个丢掉，
     * 表现为「填了等于没填」。Android 12 以下是默认可变语义，所以**不能**顺手补个
     * FLAG_IMMUTABLE（那会静默破坏输入）；只有 31+ 需要显式声明 MUTABLE。
     */
    private static PendingIntent replyIntent(Context context, String action, int requestCode) {
        Intent intent = new Intent(context, AdbPairInputReceiver.class).setAction(action);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_MUTABLE;
        }
        return PendingIntent.getBroadcast(context, requestCode, intent, flags);
    }

    /** 收掉指定通知（不存在时无副作用） */
    private static void cancel(Context context, int id) {
        try {
            NotificationManager manager = (NotificationManager)
                    context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) manager.cancel(id);
        } catch (Exception ignored) {
            // 同其他方法：通知只是汇报，失败不影响流程
        }
    }

    /** 发（或更新）通知 */
    private static void post(Context context, String channelId, int id, String title, String text,
                             boolean ongoing, boolean onlyAlertOnce) {
        try {
            NotificationManager manager = (NotificationManager)
                    context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null) return;
            ensureChannel(context, manager, channelId);

            Notification notification = new NotificationCompat.Builder(context, channelId)
                    .setSmallIcon(R.drawable.ic_terminal)
                    .setContentTitle(title)
                    .setContentText(text)
                    // 失败原因与操作指引较长，BigTextStyle 让用户下拉通知栏能看全
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                    .setPriority(CHANNEL_RESULT.equals(channelId)
                            ? NotificationCompat.PRIORITY_HIGH : NotificationCompat.PRIORITY_LOW)
                    .setOnlyAlertOnce(onlyAlertOnce)
                    .setOngoing(ongoing)
                    .setAutoCancel(!ongoing)
                    .setContentIntent(contentIntent(context))
                    .build();

            manager.notify(id, notification);
        } catch (Exception ignored) {
            // 未授予通知权限（Android 13+）等情况：静默忽略，配对流程照常继续
        }
    }

    /** 收掉进度通知（结果是主角，别让两条通知并排挤在一起） */
    private static void dismissProgress(Context context) {
        try {
            NotificationManager manager = (NotificationManager)
                    context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.cancel(PROGRESS_ID);
            }
        } catch (Exception ignored) {
            // 同上：通知只是汇报，失败不影响流程
        }
    }

    /**
     * 首次发通知前建渠道（Android 8+ 必需；重复调用无害）。
     *
     * 注意：渠道的重要性一旦创建就不能由 App 改（只能引导用户去系统设置改），
     * 所以「静默进度」和「会响的结果」必须是两个渠道 id，不能共用一个再改属性。
     */
    private static void ensureChannel(Context context, NotificationManager manager, String channelId) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        if (manager.getNotificationChannel(channelId) != null) return;

        int nameRes;
        int descRes;
        int importance;
        boolean silent = false;
        if (CHANNEL_PROGRESS.equals(channelId)) {
            nameRes = R.string.adb_notif_channel_name;
            descRes = R.string.adb_notif_channel_desc;
            importance = NotificationManager.IMPORTANCE_LOW;
            silent = true;      // 进度是静默的：不响不震（过程反馈已经有 Toast 了）
        } else if (CHANNEL_MANUAL.equals(channelId)) {
            nameRes = R.string.adb_manual_channel_name;
            descRes = R.string.adb_manual_channel_desc;
            importance = NotificationManager.IMPORTANCE_HIGH;   // 得让用户注意到去填
        } else {
            nameRes = R.string.adb_notif_result_channel_name;
            descRes = R.string.adb_notif_result_channel_desc;
            importance = NotificationManager.IMPORTANCE_HIGH;
        }

        NotificationChannel channel = new NotificationChannel(channelId,
                context.getString(nameRes), importance);
        channel.setDescription(context.getString(descRes));
        if (silent) {
            channel.enableVibration(false);
            channel.setSound(null, null);
        }
        manager.createNotificationChannel(channel);
    }

    /** 点通知回到 App（看状态条 / 走「手动填写」兜底） */
    private static PendingIntent contentIntent(Context context) {
        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;   // Android 12+ 要求显式声明可变性
        }
        return PendingIntent.getActivity(context, 0, intent, flags);
    }
}
