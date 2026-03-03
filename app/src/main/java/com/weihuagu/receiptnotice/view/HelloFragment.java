package com.weihuagu.receiptnotice.view;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Observer;

import com.jeremyliao.liveeventbus.LiveEventBus;
import com.weihuagu.receiptnotice.MainApplication;
import com.weihuagu.receiptnotice.R;
import com.weihuagu.receiptnotice.util.AppRunningUtil;
import com.weihuagu.receiptnotice.util.LogUtil;
import com.weihuagu.receiptnotice.util.PreferenceUtil;

public class HelloFragment extends Fragment {
    private TextView numofpush;
    private TextView posturl;
    private TextView statusAlipay;
    private TextView statusWechat;
    private TextView statusRoot;
    private Button btnCheckStatus;
    private Button btnRequestRoot;

    private CheckBox cbSelf;
    private CheckBox cbAlipay;
    private CheckBox cbWechat;
    private TextView pidSelf;
    private TextView pidAlipay;
    private TextView pidWechat;
    private Spinner spinnerInterval;
    private TextView nextRefreshText;
    private LinearLayout keepaliveSection;
    private View dividerKeepalive;

    private View rootview;
    private PreferenceUtil preference;

    private static final int ACTIVE_WINDOW_MINUTES = 10;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        rootview = inflater.inflate(R.layout.fragment_hello, container, false);
        return rootview;
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        initView();
        subMessage();
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshAppStatus();
        if (AppRunningUtil.isRootGranted()) {
            doKeepAlive();
        }
    }

    private void initView() {
        preference = new PreferenceUtil(getContext());
        numofpush = (TextView) rootview.findViewById(R.id.numofpush);
        setTextWithNumofpush();
        posturl = (TextView) rootview.findViewById(R.id.posturl);
        setTextWithPosturl();

        statusAlipay = (TextView) rootview.findViewById(R.id.status_alipay);
        statusWechat = (TextView) rootview.findViewById(R.id.status_wechat);
        statusRoot = (TextView) rootview.findViewById(R.id.status_root);

        keepaliveSection = (LinearLayout) rootview.findViewById(R.id.keepalive_section);
        dividerKeepalive = rootview.findViewById(R.id.divider_keepalive);

        cbSelf = (CheckBox) rootview.findViewById(R.id.cb_keepalive_self);
        cbAlipay = (CheckBox) rootview.findViewById(R.id.cb_keepalive_alipay);
        cbWechat = (CheckBox) rootview.findViewById(R.id.cb_keepalive_wechat);
        pidSelf = (TextView) rootview.findViewById(R.id.pid_self);
        pidAlipay = (TextView) rootview.findViewById(R.id.pid_alipay);
        pidWechat = (TextView) rootview.findViewById(R.id.pid_wechat);
        spinnerInterval = (Spinner) rootview.findViewById(R.id.spinner_interval);
        nextRefreshText = (TextView) rootview.findViewById(R.id.keepalive_next_refresh);

        btnCheckStatus = (Button) rootview.findViewById(R.id.btn_check_status);
        btnRequestRoot = (Button) rootview.findViewById(R.id.btn_request_root);

        SharedPreferences sp = getPrefs();
        cbSelf.setChecked(sp.getBoolean(AppRunningUtil.PREF_KA_SELF, false));
        cbAlipay.setChecked(sp.getBoolean(AppRunningUtil.PREF_KA_ALIPAY, false));
        cbWechat.setChecked(sp.getBoolean(AppRunningUtil.PREF_KA_WECHAT, false));

        int savedInterval = sp.getInt(AppRunningUtil.PREF_KA_INTERVAL, AppRunningUtil.DEFAULT_INTERVAL);
        spinnerInterval.setSelection(AppRunningUtil.getIndexByInterval(savedInterval));
        spinnerInterval.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                int interval = AppRunningUtil.getIntervalByIndex(position);
                getPrefs().edit()
                        .putInt(AppRunningUtil.PREF_KA_INTERVAL, interval)
                        .apply();
                updateNextRefreshText(interval);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        CompoundButton.OnCheckedChangeListener kaListener = new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                SharedPreferences.Editor editor = getPrefs().edit();
                editor.putBoolean(AppRunningUtil.PREF_KA_SELF, cbSelf.isChecked());
                editor.putBoolean(AppRunningUtil.PREF_KA_ALIPAY, cbAlipay.isChecked());
                editor.putBoolean(AppRunningUtil.PREF_KA_WECHAT, cbWechat.isChecked());
                editor.apply();

                if (AppRunningUtil.isRootGranted()) {
                    doKeepAlive();
                }
            }
        };
        cbSelf.setOnCheckedChangeListener(kaListener);
        cbAlipay.setOnCheckedChangeListener(kaListener);
        cbWechat.setOnCheckedChangeListener(kaListener);

        btnCheckStatus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (AppRunningUtil.isRootGranted()) {
                    refreshAppStatus();
                    launchInactiveApps();
                    doKeepAlive();
                } else if (!AppRunningUtil.hasUsageStatsPermission(getContext())) {
                    AppRunningUtil.requestUsageStatsPermission(getContext());
                    Toast.makeText(getContext(),
                            getString(R.string.toast_grant_usage_permission),
                            Toast.LENGTH_LONG).show();
                } else {
                    refreshAppStatus();
                    Toast.makeText(getContext(),
                            getString(R.string.toast_status_refreshed),
                            Toast.LENGTH_SHORT).show();
                }
            }
        });

        btnRequestRoot.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (AppRunningUtil.isRootGranted()) {
                    Toast.makeText(getContext(),
                            getString(R.string.toast_root_already),
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                btnRequestRoot.setEnabled(false);
                btnRequestRoot.setText(getString(R.string.btn_requesting_root));
                Toast.makeText(getContext(),
                        getString(R.string.toast_root_requesting),
                        Toast.LENGTH_LONG).show();

                AppRunningUtil.requestRootAsync(new AppRunningUtil.RootCallback() {
                    @Override
                    public void onResult(boolean hasRoot) {
                        if (getContext() == null) return;
                        btnRequestRoot.setEnabled(true);
                        if (hasRoot) {
                            onRootGranted();
                        } else {
                            btnRequestRoot.setText(getString(R.string.btn_request_root));
                            statusRoot.setText(getString(R.string.status_root_denied));
                            statusRoot.setTextColor(0xFFF44336);
                            Toast.makeText(getContext(),
                                    getString(R.string.toast_root_failed),
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        });

        updateRootUI();
        refreshAppStatus();
    }

    private SharedPreferences getPrefs() {
        return PreferenceManager.getDefaultSharedPreferences(getContext());
    }

    private void updateNextRefreshText(int intervalMinutes) {
        boolean anyChecked = cbSelf.isChecked() || cbAlipay.isChecked() || cbWechat.isChecked();
        if (anyChecked) {
            nextRefreshText.setText(getString(R.string.keepalive_next_refresh, intervalMinutes));
        } else {
            nextRefreshText.setText(getString(R.string.keepalive_off));
        }
    }

    private void onRootGranted() {
        btnRequestRoot.setText(getString(R.string.btn_root_granted));
        btnRequestRoot.setEnabled(false);
        btnCheckStatus.setText(getString(R.string.btn_check_and_launch));
        statusRoot.setText(getString(R.string.status_root_granted));
        statusRoot.setTextColor(0xFF4CAF50);

        keepaliveSection.setVisibility(View.VISIBLE);
        dividerKeepalive.setVisibility(View.VISIBLE);

        Toast.makeText(getContext(),
                getString(R.string.toast_root_success),
                Toast.LENGTH_SHORT).show();

        refreshAppStatus();
        doKeepAlive();
    }

    private void updateRootUI() {
        if (AppRunningUtil.isRootGranted()) {
            btnRequestRoot.setText(getString(R.string.btn_root_granted));
            btnRequestRoot.setEnabled(false);
            btnCheckStatus.setText(getString(R.string.btn_check_and_launch));
            statusRoot.setText(getString(R.string.status_root_granted));
            statusRoot.setTextColor(0xFF4CAF50);
            keepaliveSection.setVisibility(View.VISIBLE);
            dividerKeepalive.setVisibility(View.VISIBLE);
        } else {
            btnRequestRoot.setText(getString(R.string.btn_request_root));
            btnRequestRoot.setEnabled(true);
            btnCheckStatus.setText(getString(R.string.btn_check_app_status));
            statusRoot.setText(getString(R.string.status_root_not_granted));
            statusRoot.setTextColor(0xFF9E9E9E);
            keepaliveSection.setVisibility(View.GONE);
            dividerKeepalive.setVisibility(View.GONE);
        }
    }

    private void doKeepAlive() {
        if (!AppRunningUtil.isRootGranted()) return;

        boolean kaSelf = cbSelf.isChecked();
        boolean kaAlipay = cbAlipay.isChecked();
        boolean kaWechat = cbWechat.isChecked();

        String[] pids = AppRunningUtil.keepSelectedAlive(kaSelf, kaAlipay, kaWechat);

        updatePidDisplay(pidSelf, kaSelf, pids[0]);
        updatePidDisplay(pidAlipay, kaAlipay, pids[1]);
        updatePidDisplay(pidWechat, kaWechat, pids[2]);

        int interval = getPrefs().getInt(AppRunningUtil.PREF_KA_INTERVAL, AppRunningUtil.DEFAULT_INTERVAL);
        updateNextRefreshText(interval);
    }

    private void updatePidDisplay(TextView pidView, boolean enabled, String pid) {
        if (!enabled) {
            pidView.setText("");
            return;
        }
        if (pid != null) {
            pidView.setText("PID " + pid + "  " + getString(R.string.keepalive_protected));
            pidView.setTextColor(0xFF4CAF50);
        } else {
            pidView.setText(getString(R.string.keepalive_not_running));
            pidView.setTextColor(0xFFFF9800);
        }
    }

    private void launchInactiveApps() {
        if (getContext() == null) return;
        int launched = 0;

        String[][] apps = {
                {AppRunningUtil.PKG_ALIPAY, "Alipay"},
                {AppRunningUtil.PKG_WECHAT, "WeChat"}
        };

        for (String[] app : apps) {
            int status = AppRunningUtil.getAppStatus(getContext(), app[0], ACTIVE_WINDOW_MINUTES);
            if (status == AppRunningUtil.STATUS_INSTALLED_INACTIVE) {
                if (AppRunningUtil.launchAppRoot(getContext(), app[0])) {
                    launched++;
                    LogUtil.debugLog("Root launch: " + app[1]);
                }
            }
        }

        if (launched > 0) {
            Toast.makeText(getContext(),
                    getString(R.string.toast_launched_apps, launched),
                    Toast.LENGTH_SHORT).show();
            rootview.postDelayed(new Runnable() {
                @Override
                public void run() {
                    refreshAppStatus();
                    doKeepAlive();
                }
            }, 3000);
        } else {
            Toast.makeText(getContext(),
                    getString(R.string.toast_all_running),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void refreshAppStatus() {
        if (getContext() == null) return;

        int alipayStatus = AppRunningUtil.getAppStatus(
                getContext(), AppRunningUtil.PKG_ALIPAY, ACTIVE_WINDOW_MINUTES);
        int wechatStatus = AppRunningUtil.getAppStatus(
                getContext(), AppRunningUtil.PKG_WECHAT, ACTIVE_WINDOW_MINUTES);

        boolean rooted = AppRunningUtil.isRootGranted();

        if (rooted) {
            String alipayPid = AppRunningUtil.getProcessPid(AppRunningUtil.PKG_ALIPAY);
            String wechatPid = AppRunningUtil.getProcessPid(AppRunningUtil.PKG_WECHAT);

            statusAlipay.setText(getString(R.string.status_alipay_label) + getStatusText(alipayStatus)
                    + (alipayPid != null ? "  PID:" + alipayPid : ""));
            statusWechat.setText(getString(R.string.status_wechat_label) + getStatusText(wechatStatus)
                    + (wechatPid != null ? "  PID:" + wechatPid : ""));
        } else {
            statusAlipay.setText(getString(R.string.status_alipay_label) + getStatusText(alipayStatus));
            statusWechat.setText(getString(R.string.status_wechat_label) + getStatusText(wechatStatus));
        }

        setStatusColor(statusAlipay, alipayStatus);
        setStatusColor(statusWechat, wechatStatus);
    }

    private String getStatusText(int status) {
        switch (status) {
            case AppRunningUtil.STATUS_NOT_INSTALLED:
                return getString(R.string.status_not_installed);
            case AppRunningUtil.STATUS_INSTALLED_INACTIVE:
                return getString(R.string.status_inactive);
            case AppRunningUtil.STATUS_RECENTLY_ACTIVE:
                return getString(R.string.status_active);
            case AppRunningUtil.STATUS_UNKNOWN:
            default:
                return getString(R.string.status_unknown);
        }
    }

    private void setStatusColor(TextView tv, int status) {
        switch (status) {
            case AppRunningUtil.STATUS_RECENTLY_ACTIVE:
                tv.setTextColor(0xFF4CAF50);
                break;
            case AppRunningUtil.STATUS_INSTALLED_INACTIVE:
                tv.setTextColor(0xFFFF9800);
                break;
            case AppRunningUtil.STATUS_NOT_INSTALLED:
                tv.setTextColor(0xFFF44336);
                break;
            default:
                tv.setTextColor(0xFF9E9E9E);
                break;
        }
    }

    private void setTextWithNumofpush() {
        numofpush.setText("推送次数" + preference.getNumOfPush());
    }

    private void setTextWithPosturl() {
        if (preference.getPostUrl() != null)
            posturl.setText("目前的推送地址：" + preference.getPostUrl());
    }

    private void resetText() {
        setTextWithPosturl();
        setTextWithNumofpush();
    }

    private void subMessage() {
        LiveEventBus
                .get("message_finished_one_post", String[].class)
                .observeForever(new Observer<String[]>() {
                    @Override
                    public void onChanged(@Nullable String[] testpostbean) {
                        resetText();
                    }
                });
        LiveEventBus
                .get("user_set_posturl", String.class)
                .observeForever(new Observer<String>() {
                    @Override
                    public void onChanged(@Nullable String url) {
                        resetText();
                    }
                });
        LiveEventBus
                .get("time_interval", String.class)
                .observeForever(new Observer<String>() {
                    @Override
                    public void onChanged(@Nullable String baseinterval) {
                        Toast.makeText(MainApplication.getAppContext(), "接受到一分钟间隔事件，更新推送次数",
                                Toast.LENGTH_SHORT).show();
                        LogUtil.debugLog("接受到一分钟一次的时间间隔事件");
                        resetText();
                    }
                });
    }
}
