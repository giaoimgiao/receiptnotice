package com.weihuagu.receiptnotice;
import android.app.Notification;
import android.provider.Telephony.Sms;

import com.weihuagu.receiptnotice.action.IDoPost;
import com.weihuagu.receiptnotice.pushclassification.pmentay.AlipayPmentayNotificationHandle;
import com.weihuagu.receiptnotice.pushclassification.pmentay.BanksProxy;
import com.weihuagu.receiptnotice.pushclassification.pmentay.CashbarPmentayNotificationHandle;
import com.weihuagu.receiptnotice.pushclassification.pmentay.CustomAppNotificationHandle;
import com.weihuagu.receiptnotice.pushclassification.pmentay.IcbcelifePmentayNotificationHandle;
import com.weihuagu.receiptnotice.pushclassification.pmentay.MipushPmentayNotificationHandle;
import com.weihuagu.receiptnotice.pushclassification.pmentay.UnionpayPmentayNotificationHandle;
import com.weihuagu.receiptnotice.pushclassification.pmentay.WechatPmentayNotificationHandle;
import com.weihuagu.receiptnotice.pushclassification.pmentay.XposedmodulePmentayNotificationHandle;
import com.weihuagu.receiptnotice.util.PreferenceUtil;

import org.json.JSONArray;
import org.json.JSONObject;

public  class NotificationHandleFactory{
    public PmentayNotificationHandle getNotificationHandle(String pkg, Notification notification, IDoPost postpush){
                if("com.xiaomi.xmsf".equals(pkg)){
                        return  new MipushPmentayNotificationHandle("com.xiaomi.xmsf",notification,postpush);
                }
                if("com.eg.android.AlipayGphone".equals(pkg)){
                        return new AlipayPmentayNotificationHandle("com.eg.android.AlipayGphone",notification,postpush);
                }
                if("android".equals(pkg)){
                        return new XposedmodulePmentayNotificationHandle("github.tornaco.xposedmoduletest",notification,postpush);
                }
                if("com.tencent.mm".equals(pkg)){
                        return new WechatPmentayNotificationHandle("com.tencent.mm",notification,postpush);
                }
                if("com.wosai.cashbar".equals(pkg)){
                        return new CashbarPmentayNotificationHandle("com.wosai.cashbar",notification,postpush);
                }
                if("com.unionpay".equals(pkg)){
                        return new UnionpayPmentayNotificationHandle("com.unionpay",notification,postpush);
                }
                if("com.icbc.biz.elife".equals(pkg)){
                        return new IcbcelifePmentayNotificationHandle("com.icbc.biz.elife",notification,postpush);
                }
                if(getMessageAppPkg().equals(pkg)){
                        return new BanksProxy(getMessageAppPkg(),notification,postpush);
                }

                PmentayNotificationHandle customHandle = matchCustomApp(pkg, notification, postpush);
                if(customHandle != null){
                        return customHandle;
                }

                return null;

        }

        private PmentayNotificationHandle matchCustomApp(String pkg, Notification notification, IDoPost postpush){
                try{
                        PreferenceUtil preference = new PreferenceUtil(MainApplication.getAppContext());
                        String json = preference.getCustomApps();
                        if(json == null || json.isEmpty()) return null;

                        JSONArray arr = new JSONArray(json);
                        for(int i = 0; i < arr.length(); i++){
                                JSONObject rule = arr.getJSONObject(i);
                                if(pkg.equals(rule.optString("pkg",""))){
                                        return new CustomAppNotificationHandle(
                                                pkg, notification, postpush,
                                                rule.optString("type", pkg),
                                                rule.optString("keyword", ""));
                                }
                        }
                }catch(Exception e){
                        // ignore parse errors
                }
                return null;
        }

        private String getMessageAppPkg(){
                return Sms.getDefaultSmsPackage(MainApplication.getAppContext());

        }
}
