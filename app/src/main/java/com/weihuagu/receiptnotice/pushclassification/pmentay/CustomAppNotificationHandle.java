package com.weihuagu.receiptnotice.pushclassification.pmentay;

import android.app.Notification;

import com.weihuagu.receiptnotice.PmentayNotificationHandle;
import com.weihuagu.receiptnotice.action.IDoPost;

import java.util.HashMap;
import java.util.Map;

public class CustomAppNotificationHandle extends PmentayNotificationHandle {
    private String customType;
    private String keyword;

    public CustomAppNotificationHandle(String pkgtype, Notification notification,
                                       IDoPost postpush, String customType, String keyword) {
        super(pkgtype, notification, postpush);
        this.customType = customType;
        this.keyword = keyword;
    }

    public void handleNotification() {
        if (keyword != null && !keyword.isEmpty()) {
            if (!title.contains(keyword) && !content.contains(keyword)) {
                return;
            }
        }

        Map<String, String> postmap = new HashMap<String, String>();
        postmap.put("type", customType);
        postmap.put("time", notitime);
        postmap.put("title", title);
        postmap.put("content", content);

        String money = extractMoney(content);
        if (money == null) {
            money = extractMoney(title);
        }
        postmap.put("money", money != null ? money : "0");

        postpush.doPost(postmap);
    }
}
