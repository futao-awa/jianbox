package com.jianbox.app;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class AppPickerActivity extends Activity {
    private final List<ResolveInfo> apps = new ArrayList<>();
    private GridLayout grid;
    private JianData data;
    private TextView count;
    private int gridColumns=3;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state); data = new JianData(this);Ui.applyTheme(data);
        if(UpdateGuard.enforce(this))return;
        getWindow().setStatusBarColor(Ui.GREEN_PALE); getWindow().setNavigationBarColor(Ui.GREEN_PALE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        loadApps(); buildUi(); render("");
    }

    @Override protected void onResume(){super.onResume();UpdateGuard.resume(this);}

    private void loadApps() {
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        apps.addAll(getPackageManager().queryIntentActivities(query, 0));
        Collator collator = Collator.getInstance(Locale.CHINA);
        apps.sort(Comparator.comparing(a -> a.loadLabel(getPackageManager()).toString(), collator));
    }

    private void buildUi() {
        LinearLayout root = Ui.vertical(this); root.setPadding(Ui.dp(this, 13), Ui.dp(this, 10), Ui.dp(this, 13), Ui.dp(this, 14)); if(Ui.GLASS)root.setBackground(ImageStore.background(this,data.appBackgroundPath(),0x44000000,0,0x33FFFFFF,100,0,data.appBackgroundPosition()));else root.setBackgroundColor(Ui.GREEN_PALE);
        LinearLayout head = Ui.horizontal(this);
        TextView back = Ui.text(this, "‹", 26, Ui.TEXT, false); back.setGravity(Gravity.CENTER); back.setOnClickListener(v -> finish()); head.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 38), Ui.dp(this, 42)));
        LinearLayout title = Ui.vertical(this); title.addView(Ui.label(this, "APP LIBRARY · QUICK LAUNCH")); title.addView(Ui.text(this, "选择应用", 19, Ui.TEXT, true)); head.addView(title, Ui.weight(1));
        count = Ui.text(this, apps.size() + " 个", 11, Ui.GREEN, true); head.addView(count);
        root.addView(head, new LinearLayout.LayoutParams(-1, Ui.dp(this, 48)));
        TextView sub = Ui.text(this, "轻点应用即可添加到悬浮侧栏，支持名称与包名搜索。", 10, Ui.MUTED, false); root.addView(sub, new LinearLayout.LayoutParams(-1, Ui.dp(this, 28)));
        EditText search = new EditText(this); search.setSingleLine(true); search.setHint("搜索已安装 App…"); search.setTextSize(13); search.setTextColor(Ui.TEXT); search.setHintTextColor(0x7799AA99);
        search.setCompoundDrawablesWithIntrinsicBounds(android.R.drawable.ic_menu_search, 0, 0, 0); search.setCompoundDrawablePadding(Ui.dp(this, 8));
        search.setBackground(Ui.bordered(Color.WHITE, Ui.BORDER, 14, this)); search.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0);
        root.addView(search, new LinearLayout.LayoutParams(-1, Ui.dp(this, 42)));
        float screenDp=getResources().getDisplayMetrics().widthPixels/getResources().getDisplayMetrics().density;gridColumns=screenDp>=1000?7:screenDp>=700?5:3;ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); grid = new GridLayout(this); grid.setColumnCount(gridColumns); grid.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 14));
        scroll.addView(grid, new ScrollView.LayoutParams(-1, -2)); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        search.addTextChangedListener(new TextWatcher() { public void beforeTextChanged(CharSequence s,int a,int b,int c){} public void onTextChanged(CharSequence s,int a,int b,int c){render(s.toString());} public void afterTextChanged(Editable e){} });
        setContentView(root);
    }

    private void render(String query) {
        grid.removeAllViews(); String needle = query.trim().toLowerCase(Locale.CHINA); int shown = 0;
        Set<String> existing = new HashSet<>(); for (JianData.QuickItem q : data.quickItems()) if (JianData.TYPE_APP.equals(q.type)) existing.add(q.value);
        int width = (getResources().getDisplayMetrics().widthPixels - Ui.dp(this, 26+gridColumns*6)) / gridColumns;
        for (ResolveInfo app : apps) {
            String label = app.loadLabel(getPackageManager()).toString(); String pkg = app.activityInfo.packageName;
            if (!needle.isEmpty() && !(label + pkg).toLowerCase(Locale.CHINA).contains(needle)) continue;
            shown++;
            LinearLayout card = Ui.vertical(this); card.setGravity(Gravity.CENTER); card.setPadding(Ui.dp(this, 5), Ui.dp(this, 8), Ui.dp(this, 5), Ui.dp(this, 6));
            card.setBackground(Ui.bordered(Color.WHITE, existing.contains(pkg) ? Ui.GREEN : 0x55C8E6C9, 15, this)); card.setElevation(Ui.dp(this, 1));
            ImageView icon = new ImageView(this); try { icon.setImageDrawable(app.loadIcon(getPackageManager())); } catch (Exception ignored) {}
            card.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));
            TextView name = Ui.text(this, label, 10, Ui.TEXT, true); name.setGravity(Gravity.CENTER); name.setMaxLines(2); card.addView(name, new LinearLayout.LayoutParams(-1, Ui.dp(this, 32)));
            TextView state = Ui.text(this, existing.contains(pkg) ? "已添加" : "+ 添加", 9, existing.contains(pkg) ? Ui.GREEN : Ui.MUTED, true); state.setGravity(Gravity.CENTER); card.addView(state, new LinearLayout.LayoutParams(-1, Ui.dp(this, 20)));
            GridLayout.LayoutParams p = new GridLayout.LayoutParams(); p.width = width; p.height = Ui.dp(this, 112); p.setMargins(Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3)); grid.addView(card, p);
            card.setOnClickListener(v -> {
                if (existing.contains(pkg)) { Toast.makeText(this, "该应用已在侧栏中", Toast.LENGTH_SHORT).show(); return; }
                JianData.QuickItem item = new JianData.QuickItem(); item.type = JianData.TYPE_APP; item.title = label; item.value = pkg; item.group = "应用"; item.glyph = label.substring(0, 1);
                List<JianData.QuickItem> all = data.quickItems(); all.add(item); data.saveQuick(all); existing.add(pkg); Toast.makeText(this, "已添加 " + label, Toast.LENGTH_SHORT).show(); render(query);
                if (data.serviceEnabled() && Settings.canDrawOverlays(this)) {
                    try { startForegroundService(new Intent(this, OverlayService.class).setAction(OverlayService.ACTION_REFRESH)); } catch (Exception ignored) {}
                }
            });
        }
        count.setText(String.format(Locale.CHINA, "%d 个", shown));
        if (shown == 0) { TextView empty = Ui.text(this, "没有找到匹配的应用", 13, Ui.MUTED, false); empty.setGravity(Gravity.CENTER); GridLayout.LayoutParams p = new GridLayout.LayoutParams(); p.width = getResources().getDisplayMetrics().widthPixels - Ui.dp(this, 36); p.height = Ui.dp(this, 130); grid.addView(empty, p); }
    }
}
