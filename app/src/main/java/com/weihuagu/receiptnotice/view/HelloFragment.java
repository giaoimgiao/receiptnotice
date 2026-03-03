package com.weihuagu.receiptnotice.view;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
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
    private Button btnCheckStatus;
    private View rootview;
    private PreferenceUtil preference;

    private static final int ACTIVE_WINDOW_MINUTES = 10;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        rootview=inflater.inflate(R.layout.fragment_hello,container, false);
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
    }

    private void initView(){
        preference=new PreferenceUtil(getContext());
        numofpush=(TextView)rootview.findViewById(R.id.numofpush);
        setTextWithNumofpush();
        posturl=(TextView)rootview.findViewById(R.id.posturl);
        setTextWithPosturl();

        statusAlipay = (TextView) rootview.findViewById(R.id.status_alipay);
        statusWechat = (TextView) rootview.findViewById(R.id.status_wechat);
        btnCheckStatus = (Button) rootview.findViewById(R.id.btn_check_status);
        btnCheckStatus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!AppRunningUtil.hasUsageStatsPermission(getContext())) {
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
        refreshAppStatus();
    }

    private void refreshAppStatus() {
        if (getContext() == null) return;

        int alipayStatus = AppRunningUtil.getAppStatus(
                getContext(), AppRunningUtil.PKG_ALIPAY, ACTIVE_WINDOW_MINUTES);
        int wechatStatus = AppRunningUtil.getAppStatus(
                getContext(), AppRunningUtil.PKG_WECHAT, ACTIVE_WINDOW_MINUTES);

        statusAlipay.setText(getString(R.string.status_alipay_label) + getStatusText(alipayStatus));
        statusWechat.setText(getString(R.string.status_wechat_label) + getStatusText(wechatStatus));

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

    private void setTextWithNumofpush(){
        numofpush.setText("推送次数"+preference.getNumOfPush());
    }
    private void setTextWithPosturl(){
        if(preference.getPostUrl()!=null)
            posturl.setText("目前的推送地址："+preference.getPostUrl());

    }
    private void resetText(){
        setTextWithPosturl();
        setTextWithNumofpush();
    }
    private void subMessage(){
        LiveEventBus
                .get("message_finished_one_post",String[].class)
                .observeForever(new Observer<String[]>() {
                    @Override
                    public void onChanged(@Nullable String[] testpostbean) {
                        resetText();
                    }
                });
        LiveEventBus
                .get("user_set_posturl",String.class)
                .observeForever(new Observer<String>() {
                    @Override
                    public void onChanged(@Nullable String url) {
                        resetText();
                    }
                });
        LiveEventBus
                .get("time_interval",String.class)
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
