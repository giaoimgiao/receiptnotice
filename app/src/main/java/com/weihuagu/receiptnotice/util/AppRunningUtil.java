package com.weihuagu.receiptnotice.util;

import android.app.ActivityManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class AppRunningUtil {

    private static final String TAG = "AppRunningUtil";

    public static final String PKG_ALIPAY = "com.eg.android.AlipayGphone";
    public static final String PKG_WECHAT = "com.tencent.mm";
    public static final String PKG_UNIONPAY = "com.unionpay";

    public static final int STATUS_NOT_INSTALLED = 0;
    public static final int STATUS_INSTALLED_INACTIVE = 1;
    public static final int STATUS_RECENTLY_ACTIVE = 2;
    public static final int STATUS_UNKNOWN = -1;

    private static Boolean sHasRoot = null;

    public interface RootCallback {
        void onResult(boolean hasRoot);
    }

    public static boolean isAppInstalled(Context context, String packageName) {
        try {
            context.getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * 检测目标 App 的运行状态。
     * 优先使用 root (pidof) 精确检测进程；
     * 无 root 时回退到 UsageStatsManager / ActivityManager。
     */
    public static int getAppStatus(Context context, String packageName, int minutes) {
        if (!isAppInstalled(context, packageName)) {
            return STATUS_NOT_INSTALLED;
        }

        if (isRootGranted()) {
            return isProcessAliveRoot(packageName)
                    ? STATUS_RECENTLY_ACTIVE
                    : STATUS_INSTALLED_INACTIVE;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            if (!hasUsageStatsPermission(context)) {
                return STATUS_UNKNOWN;
            }
            return isAppRecentlyActive(context, packageName, minutes)
                    ? STATUS_RECENTLY_ACTIVE
                    : STATUS_INSTALLED_INACTIVE;
        }

        return isAppInProcessList(context, packageName)
                ? STATUS_RECENTLY_ACTIVE
                : STATUS_INSTALLED_INACTIVE;
    }

    // -------------------------------------------------------------------------
    // Root
    // -------------------------------------------------------------------------

    /** 返回缓存的 root 状态（不触发 su 请求） */
    public static boolean isRootGranted() {
        return sHasRoot != null && sHasRoot;
    }

    /**
     * 异步请求 root 权限，给 Magisk 足够时间弹出授权对话框。
     * 超时 15 秒（用户需要在 Magisk 弹窗中点击允许）。
     */
    public static void requestRootAsync(final RootCallback callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean result = checkRootBlocking(15);
                sHasRoot = result;
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onResult(result);
                    }
                });
            }
        }).start();
    }

    /**
     * 阻塞式 root 检测，等待指定秒数让 Magisk 弹窗。
     */
    private static boolean checkRootBlocking(int timeoutSeconds) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
            final AtomicBoolean finished = new AtomicBoolean(false);
            final CountDownLatch latch = new CountDownLatch(1);
            final StringBuilder output = new StringBuilder();

            Thread reader = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        BufferedReader br = new BufferedReader(
                                new InputStreamReader(p.getInputStream()));
                        String line;
                        while ((line = br.readLine()) != null) {
                            output.append(line);
                        }
                        br.close();
                    } catch (Exception ignored) {
                    }
                    latch.countDown();
                }
            });
            reader.start();

            boolean completed = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!completed) {
                p.destroyForcibly();
                Log.w(TAG, "su request timed out after " + timeoutSeconds + "s");
                return false;
            }

            latch.await(2, TimeUnit.SECONDS);
            String result = output.toString();
            boolean hasRoot = result.contains("uid=0");
            Log.d(TAG, "root check result: " + result + " -> " + hasRoot);
            return hasRoot;
        } catch (Exception e) {
            Log.w(TAG, "root check failed", e);
            return false;
        }
    }

    /** 通过 root 执行 pidof 检测进程是否存活 */
    private static boolean isProcessAliveRoot(String packageName) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "pidof " + packageName});
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = reader.readLine();
            p.waitFor(3, TimeUnit.SECONDS);
            reader.close();
            return line != null && !line.trim().isEmpty();
        } catch (Exception e) {
            Log.w(TAG, "pidof failed", e);
            return false;
        }
    }

    /** 通过 root 启动指定 App */
    public static boolean launchAppRoot(Context context, String packageName) {
        try {
            PackageManager pm = context.getPackageManager();
            Intent launchIntent = pm.getLaunchIntentForPackage(packageName);
            if (launchIntent == null) return false;
            String component = launchIntent.getComponent().flattenToShortString();
            Process p = Runtime.getRuntime().exec(new String[]{
                    "su", "-c", "am start -n " + component});
            p.waitFor(5, TimeUnit.SECONDS);
            return p.exitValue() == 0;
        } catch (Exception e) {
            Log.w(TAG, "launchAppRoot failed", e);
            return false;
        }
    }

    /**
     * 通过 root 读取系统通知记录中的支付相关通知。
     * 使用 dumpsys notification --noredact 获取完整通知内容。
     */
    public static String readPaymentNotifications() {
        if (!isRootGranted()) return null;
        try {
            Process p = Runtime.getRuntime().exec(new String[]{
                    "su", "-c", "dumpsys notification --noredact"});
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            boolean inPaymentSection = false;
            while ((line = reader.readLine()) != null) {
                if (line.contains("com.eg.android.AlipayGphone")
                        || line.contains("com.tencent.mm")
                        || line.contains("com.unionpay")) {
                    inPaymentSection = true;
                }
                if (inPaymentSection) {
                    sb.append(line).append("\n");
                    if (line.trim().isEmpty() || line.contains("NotificationRecord")) {
                        inPaymentSection = false;
                    }
                }
            }
            p.waitFor(5, TimeUnit.SECONDS);
            reader.close();
            return sb.length() > 0 ? sb.toString() : null;
        } catch (Exception e) {
            Log.w(TAG, "readPaymentNotifications failed", e);
            return null;
        }
    }

    /** 获取进程 PID，未运行返回 null */
    public static String getProcessPid(String packageName) {
        if (!isRootGranted()) return null;
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "pidof " + packageName});
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = reader.readLine();
            p.waitFor(3, TimeUnit.SECONDS);
            reader.close();
            if (line != null && !line.trim().isEmpty()) {
                return line.trim().split("\\s+")[0];
            }
        } catch (Exception e) {
            Log.w(TAG, "getProcessPid failed", e);
        }
        return null;
    }

    /**
     * 通过 root 设置进程 oom_score_adj 为 -1000，防止系统回收。
     * @return 成功保活的 PID，失败返回 null
     */
    public static String keepAlive(String packageName) {
        if (!isRootGranted()) return null;
        try {
            String pid = getProcessPid(packageName);
            if (pid == null) return null;

            Process adjProc = Runtime.getRuntime().exec(new String[]{
                    "su", "-c", "echo -1000 > /proc/" + pid + "/oom_score_adj"});
            adjProc.waitFor(3, TimeUnit.SECONDS);

            if (adjProc.exitValue() == 0) {
                Log.d(TAG, "keepAlive: " + packageName + " pid=" + pid + " oom_score_adj=-1000");
                return pid;
            }
        } catch (Exception e) {
            Log.w(TAG, "keepAlive failed for " + packageName, e);
        }
        return null;
    }

    public static final String PKG_SELF = "com.weihuagu.receiptnotice";
    public static final String PREF_KA_SELF = "keepalive_self";
    public static final String PREF_KA_ALIPAY = "keepalive_alipay";
    public static final String PREF_KA_WECHAT = "keepalive_wechat";
    public static final String PREF_KA_INTERVAL = "keepalive_interval";
    public static final int DEFAULT_INTERVAL = 1;

    private static final int[] INTERVAL_VALUES = {1, 2, 3, 5, 10, 15, 30};

    public static int getIntervalByIndex(int index) {
        if (index >= 0 && index < INTERVAL_VALUES.length) return INTERVAL_VALUES[index];
        return DEFAULT_INTERVAL;
    }

    public static int getIndexByInterval(int interval) {
        for (int i = 0; i < INTERVAL_VALUES.length; i++) {
            if (INTERVAL_VALUES[i] == interval) return i;
        }
        return 0;
    }

    /**
     * 按需保活选中的应用。
     * @return 三个位置分别对应 自身/支付宝/微信 的 PID（未选中或未运行为 null）
     */
    public static String[] keepSelectedAlive(boolean self, boolean alipay, boolean wechat) {
        String[] pids = new String[3];
        if (self)   pids[0] = keepAlive(PKG_SELF);
        if (alipay) pids[1] = keepAlive(PKG_ALIPAY);
        if (wechat) pids[2] = keepAlive(PKG_WECHAT);
        return pids;
    }

    /** 重置 root 缓存 */
    public static void resetRootCache() {
        sHasRoot = null;
    }

    // -------------------------------------------------------------------------
    // 非 root 回退
    // -------------------------------------------------------------------------

    public static boolean hasUsageStatsPermission(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            UsageStatsManager usm = (UsageStatsManager)
                    context.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) return false;
            long now = System.currentTimeMillis();
            List<UsageStats> stats = usm.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY, now - 60_000L, now);
            return stats != null && !stats.isEmpty();
        }
        return true;
    }

    public static void requestUsageStatsPermission(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        }
    }

    private static boolean isAppRecentlyActive(Context context, String packageName, int minutes) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            UsageStatsManager usm = (UsageStatsManager)
                    context.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) return false;
            long now = System.currentTimeMillis();
            long windowMs = minutes * 60_000L;
            List<UsageStats> stats = usm.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY, now - windowMs, now);
            if (stats != null) {
                for (UsageStats s : stats) {
                    if (s.getPackageName().equals(packageName)) {
                        long lastUsed = s.getLastTimeUsed();
                        if (lastUsed > 0 && (now - lastUsed) < windowMs) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    @SuppressWarnings("deprecation")
    private static boolean isAppInProcessList(Context context, String packageName) {
        ActivityManager am = (ActivityManager)
                context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return false;
        List<ActivityManager.RunningAppProcessInfo> processes = am.getRunningAppProcesses();
        if (processes != null) {
            for (ActivityManager.RunningAppProcessInfo p : processes) {
                if (p.processName != null && p.processName.equals(packageName)) {
                    return true;
                }
            }
        }
        return false;
    }
}
