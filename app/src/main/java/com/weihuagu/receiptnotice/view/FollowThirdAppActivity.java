package com.weihuagu.receiptnotice.view;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.weihuagu.receiptnotice.R;
import com.weihuagu.receiptnotice.util.PreferenceUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class FollowThirdAppActivity extends AppCompatActivity {
    private LinearLayout ruleListContainer;
    private TextView tvSelectedApp;
    private EditText etKeyword;
    private EditText etType;
    private Button btnPickApp;
    private Button btnAdd;

    private String selectedPkg = "";
    private String selectedAppName = "";
    private PreferenceUtil preference;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_followthirdapp);
        preference = new PreferenceUtil(this);
        initView();
        loadRules();
    }

    private void initView() {
        ruleListContainer = (LinearLayout) findViewById(R.id.rule_list_container);
        tvSelectedApp = (TextView) findViewById(R.id.tv_selected_app);
        etKeyword = (EditText) findViewById(R.id.keyword);
        etType = (EditText) findViewById(R.id.type);
        btnPickApp = (Button) findViewById(R.id.btn_pick_app);
        btnAdd = (Button) findViewById(R.id.btnsetthirdapp);

        btnPickApp.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAppPicker();
            }
        });

        btnAdd.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addRule();
            }
        });
    }

    private void showAppPicker() {
        final PackageManager pm = getPackageManager();
        List<ApplicationInfo> apps = pm.getInstalledApplications(0);
        final List<ApplicationInfo> userApps = new ArrayList<ApplicationInfo>();

        for (ApplicationInfo app : apps) {
            if ((app.flags & ApplicationInfo.FLAG_SYSTEM) == 0 ||
                    (app.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) {
                userApps.add(app);
            }
        }

        Collections.sort(userApps, new java.util.Comparator<ApplicationInfo>() {
            @Override
            public int compare(ApplicationInfo a, ApplicationInfo b) {
                return pm.getApplicationLabel(a).toString()
                        .compareToIgnoreCase(pm.getApplicationLabel(b).toString());
            }
        });

        String[] names = new String[userApps.size()];
        for (int i = 0; i < userApps.size(); i++) {
            ApplicationInfo info = userApps.get(i);
            names[i] = pm.getApplicationLabel(info) + "\n" + info.packageName;
        }

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.custom_app_picker_title))
                .setItems(names, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        ApplicationInfo selected = userApps.get(which);
                        selectedPkg = selected.packageName;
                        selectedAppName = pm.getApplicationLabel(selected).toString();
                        tvSelectedApp.setText(selectedAppName + " (" + selectedPkg + ")");
                        tvSelectedApp.setTextColor(0xFF333333);
                        if (etType.getText().toString().trim().isEmpty()) {
                            etType.setText(selectedAppName);
                        }
                    }
                })
                .show();
    }

    private void addRule() {
        if (selectedPkg.isEmpty()) {
            Toast.makeText(this, getString(R.string.custom_app_toast_select_first), Toast.LENGTH_SHORT).show();
            return;
        }
        String keyword = etKeyword.getText().toString().trim();
        String type = etType.getText().toString().trim();
        if (type.isEmpty()) {
            type = selectedAppName;
        }

        try {
            String json = preference.getCustomApps();
            JSONArray arr = (json == null || json.isEmpty()) ? new JSONArray() : new JSONArray(json);

            for (int i = 0; i < arr.length(); i++) {
                JSONObject existing = arr.getJSONObject(i);
                if (selectedPkg.equals(existing.optString("pkg", ""))) {
                    Toast.makeText(this, getString(R.string.custom_app_toast_already_exists), Toast.LENGTH_SHORT).show();
                    return;
                }
            }

            JSONObject rule = new JSONObject();
            rule.put("pkg", selectedPkg);
            rule.put("appName", selectedAppName);
            rule.put("keyword", keyword);
            rule.put("type", type);
            arr.put(rule);

            preference.setCustomApps(arr.toString());
            Toast.makeText(this, getString(R.string.custom_app_toast_added), Toast.LENGTH_SHORT).show();

            selectedPkg = "";
            selectedAppName = "";
            tvSelectedApp.setText(getString(R.string.custom_app_not_selected));
            tvSelectedApp.setTextColor(0xFF888888);
            etKeyword.setText("");
            etType.setText("");

            loadRules();
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.custom_app_toast_save_failed), Toast.LENGTH_SHORT).show();
        }
    }

    private void loadRules() {
        ruleListContainer.removeAllViews();
        try {
            String json = preference.getCustomApps();
            if (json == null || json.isEmpty()) {
                addEmptyHint();
                return;
            }

            JSONArray arr = new JSONArray(json);
            if (arr.length() == 0) {
                addEmptyHint();
                return;
            }

            for (int i = 0; i < arr.length(); i++) {
                JSONObject rule = arr.getJSONObject(i);
                addRuleView(i, rule);
            }
        } catch (Exception e) {
            addEmptyHint();
        }
    }

    private void addEmptyHint() {
        TextView empty = new TextView(this);
        empty.setText(getString(R.string.custom_app_empty));
        empty.setPadding(0, 24, 0, 24);
        empty.setTextColor(0xFF999999);
        empty.setGravity(Gravity.CENTER);
        ruleListContainer.addView(empty);
    }

    private void addRuleView(final int index, JSONObject rule) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(0xFFF5F5F5);
        card.setPadding(24, 16, 24, 16);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(0, 0, 0, 12);
        card.setLayoutParams(cardParams);

        String appName = rule.optString("appName", rule.optString("pkg", ""));
        String pkg = rule.optString("pkg", "");
        String keyword = rule.optString("keyword", "");
        String type = rule.optString("type", "");

        TextView tvName = new TextView(this);
        tvName.setText(appName);
        tvName.setTextSize(15);
        tvName.setTypeface(null, Typeface.BOLD);
        tvName.setTextColor(0xFF333333);
        card.addView(tvName);

        TextView tvPkg = new TextView(this);
        tvPkg.setText(pkg);
        tvPkg.setTextSize(12);
        tvPkg.setTextColor(0xFF888888);
        card.addView(tvPkg);

        TextView tvInfo = new TextView(this);
        String keywordDisplay = keyword.isEmpty()
                ? getString(R.string.custom_app_rule_all)
                : getString(R.string.custom_app_rule_keyword, keyword);
        tvInfo.setText(keywordDisplay + "  |  " + getString(R.string.custom_app_rule_type, type));
        tvInfo.setTextSize(13);
        tvInfo.setTextColor(0xFF666666);
        tvInfo.setPadding(0, 8, 0, 8);
        card.addView(tvInfo);

        Button btnDelete = new Button(this);
        btnDelete.setText(getString(R.string.custom_app_delete));
        btnDelete.setTextColor(Color.RED);
        btnDelete.setBackgroundColor(Color.TRANSPARENT);
        btnDelete.setPadding(0, 0, 0, 0);
        btnDelete.setGravity(Gravity.START);
        btnDelete.setTextSize(13);
        btnDelete.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(FollowThirdAppActivity.this)
                        .setTitle(getString(R.string.custom_app_delete_confirm_title))
                        .setMessage(getString(R.string.custom_app_delete_confirm_msg))
                        .setPositiveButton(getString(R.string.custom_app_delete_yes),
                                new android.content.DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(android.content.DialogInterface dialog, int which) {
                                        deleteRule(index);
                                    }
                                })
                        .setNegativeButton(getString(R.string.custom_app_delete_no), null)
                        .show();
            }
        });
        card.addView(btnDelete);

        ruleListContainer.addView(card);
    }

    private void deleteRule(int index) {
        try {
            String json = preference.getCustomApps();
            JSONArray arr = new JSONArray(json);
            JSONArray newArr = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                if (i != index) newArr.put(arr.get(i));
            }
            preference.setCustomApps(newArr.toString());
            loadRules();
            Toast.makeText(this, getString(R.string.custom_app_toast_deleted), Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.custom_app_toast_save_failed), Toast.LENGTH_SHORT).show();
        }
    }
}
