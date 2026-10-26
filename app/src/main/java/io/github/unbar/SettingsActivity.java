package io.github.unbar;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckedTextView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class SettingsActivity extends Activity {
    private static final String PREFS = "config";
    private static final String KEY_INITIALIZED = "initialized";
    private static final String KEY_PACKAGES = "packages";
    private static final String KEY_LANDSCAPE_ONLY = "landscape_only";
    private static final String DEFAULT_PACKAGE = "com.tencent.tmgp.sgame";

    private static final String ACTION_SET_CONFIG = "io.github.unbar.ACTION_SET_CONFIG";
    private static final String EXTRA_PACKAGES = "packages";
    private static final String EXTRA_LANDSCAPE_ONLY = "landscape_only";

    private final Set<String> selectedPackages = new HashSet<>();
    private AppAdapter adapter;
    private Switch landscapeOnlySwitch;
    private ProgressBar progressBar;
    private ListView listView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SharedPreferences preferences = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!preferences.getBoolean(KEY_INITIALIZED, false)) {
            selectedPackages.add(DEFAULT_PACKAGE);
            preferences.edit()
                    .putBoolean(KEY_INITIALIZED, true)
                    .putStringSet(KEY_PACKAGES, new HashSet<>(selectedPackages))
                    .putBoolean(KEY_LANDSCAPE_ONLY, true)
                    .apply();
        } else {
            selectedPackages.addAll(preferences.getStringSet(
                    KEY_PACKAGES, Collections.<String>emptySet()));
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(16);
        root.setPadding(padding, padding, padding, padding);

        TextView title = new TextView(this);
        title.setText("Unbar 应用选择");
        title.setTextSize(22f);
        title.setTextColor(Color.BLACK);
        title.setPadding(0, 0, 0, dp(8));
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView description = new TextView(this);
        description.setText("选中的应用位于前台时，禁止顶部边缘滑动唤出临时状态栏。");
        description.setTextSize(14f);
        description.setTextColor(Color.DKGRAY);
        description.setPadding(0, 0, 0, dp(8));
        root.addView(description);

        landscapeOnlySwitch = new Switch(this);
        landscapeOnlySwitch.setText("仅横屏时启用");
        landscapeOnlySwitch.setChecked(preferences.getBoolean(KEY_LANDSCAPE_ONLY, true));
        root.addView(landscapeOnlySwitch);

        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("搜索应用名称或包名");
        root.addView(search, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        progressBar = new ProgressBar(this);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        progressParams.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(progressBar, progressParams);

        listView = new ListView(this);
        root.addView(listView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        Button save = new Button(this);
        save.setText("保存并立即应用");
        save.setOnClickListener(view -> saveAndBroadcast());
        root.addView(save, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);

        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
                if (adapter != null) {
                    adapter.filter(text == null ? "" : text.toString());
                }
            }

            @Override
            public void afterTextChanged(Editable editable) {
            }
        });

        loadApps();
    }

    private void loadApps() {
        new Thread(() -> {
            PackageManager packageManager = getPackageManager();
            Intent launcherIntent = new Intent(Intent.ACTION_MAIN);
            launcherIntent.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> resolved = packageManager.queryIntentActivities(launcherIntent, 0);
            Map<String, AppEntry> unique = new HashMap<>();
            for (ResolveInfo info : resolved) {
                if (info.activityInfo == null) {
                    continue;
                }
                String packageName = info.activityInfo.packageName;
                if (getPackageName().equals(packageName) || unique.containsKey(packageName)) {
                    continue;
                }
                CharSequence loadedLabel = info.loadLabel(packageManager);
                String label = loadedLabel == null ? packageName : loadedLabel.toString();
                unique.put(packageName, new AppEntry(label, packageName));
            }

            List<AppEntry> apps = new ArrayList<>(unique.values());
            Collator collator = Collator.getInstance(Locale.getDefault());
            apps.sort((left, right) -> collator.compare(left.label, right.label));

            runOnUiThread(() -> {
                progressBar.setVisibility(View.GONE);
                adapter = new AppAdapter(apps);
                listView.setAdapter(adapter);
                listView.setOnItemClickListener((parent, view, position, id) -> {
                    AppEntry item = adapter.getItem(position);
                    if (!selectedPackages.add(item.packageName)) {
                        selectedPackages.remove(item.packageName);
                    }
                    adapter.notifyDataSetChanged();
                });
            });
        }, "Unbar-AppLoader").start();
    }

    private void saveAndBroadcast() {
        boolean landscapeOnly = landscapeOnlySwitch.isChecked();
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_INITIALIZED, true)
                .putStringSet(KEY_PACKAGES, new HashSet<>(selectedPackages))
                .putBoolean(KEY_LANDSCAPE_ONLY, landscapeOnly)
                .apply();

        ArrayList<String> sorted = new ArrayList<>(selectedPackages);
        Collections.sort(sorted);
        Intent intent = new Intent(ACTION_SET_CONFIG);
        intent.putExtra(EXTRA_PACKAGES, sorted.toArray(new String[0]));
        intent.putExtra(EXTRA_LANDSCAPE_ONLY, landscapeOnly);
        sendBroadcast(intent);
        Toast.makeText(this, "已保存，共选择 " + sorted.size() + " 个应用",
                Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final class AppAdapter extends BaseAdapter {
        private final List<AppEntry> all;
        private final List<AppEntry> visible = new ArrayList<>();

        AppAdapter(List<AppEntry> apps) {
            all = apps;
            visible.addAll(apps);
        }

        void filter(String query) {
            String normalized = query.trim().toLowerCase(Locale.ROOT);
            visible.clear();
            if (normalized.isEmpty()) {
                visible.addAll(all);
            } else {
                for (AppEntry entry : all) {
                    if (entry.label.toLowerCase(Locale.ROOT).contains(normalized)
                            || entry.packageName.toLowerCase(Locale.ROOT).contains(normalized)) {
                        visible.add(entry);
                    }
                }
            }
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return visible.size();
        }

        @Override
        public AppEntry getItem(int position) {
            return visible.get(position);
        }

        @Override
        public long getItemId(int position) {
            return getItem(position).packageName.hashCode();
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            CheckedTextView view;
            if (convertView instanceof CheckedTextView) {
                view = (CheckedTextView) convertView;
            } else {
                view = (CheckedTextView) getLayoutInflater().inflate(
                        android.R.layout.simple_list_item_multiple_choice, parent, false);
                view.setPadding(dp(12), dp(8), dp(12), dp(8));
            }
            AppEntry entry = getItem(position);
            view.setText(entry.label + "\n" + entry.packageName);
            view.setChecked(selectedPackages.contains(entry.packageName));
            return view;
        }
    }

    private static final class AppEntry {
        final String label;
        final String packageName;

        AppEntry(String label, String packageName) {
            this.label = label;
            this.packageName = packageName;
        }
    }
}
