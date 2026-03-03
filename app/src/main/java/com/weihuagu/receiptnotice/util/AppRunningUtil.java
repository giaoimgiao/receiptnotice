package com.weihuagu.receiptnotice.util;

import android.app.ActivityManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;

import java.util.List;

public class AppRunningUtil {

    public static final String PKG_ALIPAY = "com.eg.android.AlipayGphone";
    public static final String PKG_WECHAT = "com.tencent.mm";
    public static final String PKG_UNIONPAY = "com.unionpay";

    public static final int STATUS_NOT_INSTALLED = 0;
    public static final int STATUS_INSTALLED_INACTIVE = 1;
    public static final int STATUS_RECENTLY_ACTIVE = 2;
    public static final int STATUS_UNKNOWN = -1;

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
     * API 21+ 使用 UsageStatsManager 检查近 N 分钟内是否活跃；
     * API < 21 回退到 ActivityManager.getRunningAppProcesses()。
     *
     * @param minutes 判定"近期活跃"的时间窗口（分钟）
     * @return STATUS_NOT_INSTALLED / STATUS_INSTALLED_INACTIVE / STATUS_RECENTLY_ACTIVE / STATUS_UNKNOWN
     */
    public static int getAppStatus(Context context, String packageName, int minutes) {
        if (!isAppInstalled(context, packageName)) {
            return STATUS_NOT_INSTALLED;
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
