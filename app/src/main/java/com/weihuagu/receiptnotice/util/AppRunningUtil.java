package com.weihuagu.receiptnotice.util;

import android.app.ActivityManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.util.List;

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

        if (hasRootAccess()) {
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
    // Root 相关
    // -------------------------------------------------------------------------

    public static boolean hasRootAccess() {
        if (sHasRoot != null) return sHasRoot;
        try {
            Process p = Runtime.getRuntime().exec("su -c id");
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = reader.readLine();
            p.waitFor();
            reader.close();
            sHasRoot = (line != null && line.contains("uid=0"));
        } catch (Exception e) {
            sHasRoot = false;
        }
        Log.d(TAG, "hasRootAccess: " + sHasRoot);
        return sHasRoot;
    }

    /** 通过 root 执行 pidof 检测进程是否存活 */
    private static boolean isProcessAliveRoot(String packageName) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "pidof " + packageName});
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = reader.readLine();
            p.waitFor();
            reader.close();
            return line != null && !line.trim().isEmpty();
        } catch (Exception e) {
            Log.w(TAG, "pidof failed", e);
            return false;
        }
    }

    /** 通过 root 启动指定 App 的 Launch Activity */
    public static boolean launchAppRoot(Context context, String packageName) {
        try {
            PackageManager pm = context.getPackageManager();
            Intent launchIntent = pm.getLaunchIntentForPackage(packageName);
            if (launchIntent == null) return false;
            String component = launchIntent.getComponent().flattenToShortString();
            Process p = Runtime.getRuntime().exec(new String[]{
                    "su", "-c", "am start -n " + component});
            p.waitFor();
            return p.exitValue() == 0;
        } catch (Exception e) {
            Log.w(TAG, "launchAppRoot failed", e);
            return false;
        }
    }

    /** 重置 root 检测缓存（换设备/刷机后可调用） */
    public static void resetRootCache() {
        sHasRoot = null;
    }

    // -------------------------------------------------------------------------
    // 非 root 回退方案
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
