package com.jianbox.app;

import android.annotation.SuppressLint;
import android.animation.ValueAnimator;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.AlertDialog;
import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.net.Uri;
import android.net.TrafficStats;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Debug;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class OverlayService extends Service {
    static final String ACTION_START = "com.jianbox.START";
    static final String ACTION_APPEARANCE = "com.jianbox.APPEARANCE";
    static final String ACTION_REFRESH = "com.jianbox.REFRESH";
    static final String ACTION_ALERT = "com.jianbox.ALERT";
    private static final int SERVICE_NOTIFICATION = 810;
    private static final SimpleDateFormat DATE = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);

    private WindowManager wm;
    private WindowManager.LayoutParams bubbleParams;
    private WindowManager.LayoutParams panelParams;
    private BubbleView bubble;
    private LinearLayout panel;
    private LinearLayout panelBody;
    private LinearLayout monitorView;
    private TextView monitorText;
    private WindowManager.LayoutParams monitorParams;
    private final Handler monitorHandler = new Handler(Looper.getMainLooper());
    private long lastCpuTime;
    private long lastSampleTime;
    private long lastRx;
    private long lastTx;
    private JianData data;
    private ClipboardManager clipboard;
    private ClipboardManager.OnPrimaryClipChangedListener clipListener;
    private int activeTab;
    private boolean torchOn;
    private FloatingBrowserWindow floatingBrowser;
    private boolean serviceDestroying;
    private boolean panelOnRight;
    private boolean panelExpanded;
    private int panelAnimationToken;
    private ValueAnimator bubbleTransitionAnimator;

    @Override public void onCreate() {
        super.onCreate();
        data = new JianData(this);
        if(!data.privacyAccepted()){stopSelf();return;}
        if(UpdateGuard.hasForceGate(this)){stopSelf();return;}
        Ui.applyTheme(data);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        createChannels();
        startForeground(SERVICE_NOTIFICATION, serviceNotification());
        installClipboardListener();
        floatingBrowser = new FloatingBrowserWindow(this, wm, data);
        if (Settings.canDrawOverlays(this)) createBubble();
        syncMonitor();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if(UpdateGuard.hasForceGate(this)){stopSelf();return START_NOT_STICKY;}
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_APPEARANCE.equals(action)) { applyAppearance(); syncMonitor(); }
        else if (ACTION_REFRESH.equals(action)) { refreshPanel(); syncMonitor(); }
        else if (ACTION_ALERT.equals(action)) {
            data.pendingReminder(true);
            if (bubble != null) bubble.setAlert(true);
            if (panel != null) { activeTab = 2; renderPanelBody(); }
        } else if (bubble == null && Settings.canDrawOverlays(this)) createBubble();
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        serviceDestroying = true;
        super.onDestroy();
        if (clipListener != null) clipboard.removePrimaryClipChangedListener(clipListener);
        if(bubbleTransitionAnimator!=null)bubbleTransitionAnimator.cancel();
        removePanel();
        removeMonitor();
        if (floatingBrowser != null) { floatingBrowser.destroy(); floatingBrowser = null; }
        if (bubble != null) { try { wm.removeView(bubble); } catch (Exception ignored) {} bubble = null; }
    }

    private void createBubble() {
        int size = Ui.dp(this, data.bubbleSize());
        bubble = new BubbleView(this);
        bubble.setIcon(ImageStore.load(data.bubbleIconPath()));
        bubble.setAlert(data.pendingReminder());
        bubbleParams = overlayParams(size, size, false);
        bubbleParams.gravity = Gravity.TOP | Gravity.START;
        bubbleParams.x = -size / 2;
        bubbleParams.y = getResources().getDisplayMetrics().heightPixels / 3;
        bubble.setOnTouchListener(new BubbleTouch());
        try { wm.addView(bubble, bubbleParams); } catch (Exception e) { stopSelf(); }
    }

    private WindowManager.LayoutParams overlayParams(int width, int height, boolean focusable) {
        int type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        int flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        if (!focusable) flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(width, height, type, flags, android.graphics.PixelFormat.TRANSLUCENT);
        p.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        return p;
    }

    private final class BubbleTouch implements View.OnTouchListener {
        float downRawX, downRawY;
        int downX, downY;
        boolean moved;
        @Override public boolean onTouch(View view, MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                downRawX = event.getRawX(); downRawY = event.getRawY();
                downX = bubbleParams.x; downY = bubbleParams.y; moved = false; return true;
            }
            if (event.getAction() == MotionEvent.ACTION_MOVE) {
                float dx = event.getRawX() - downRawX, dy = event.getRawY() - downRawY;
                if (Math.abs(dx) + Math.abs(dy) > Ui.dp(OverlayService.this, 7)) moved = true;
                bubbleParams.x = downX + Math.round(dx);
                bubbleParams.y = Math.max(0, Math.min(getResources().getDisplayMetrics().heightPixels - bubbleParams.height, downY + Math.round(dy)));
                try { wm.updateViewLayout(bubble, bubbleParams); } catch (Exception ignored) {}
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_UP) {
                if (!moved) view.performClick(); else snapBubble();
                return true;
            }
            return false;
        }
    }

    private void snapBubble() {
        int width = getResources().getDisplayMetrics().widthPixels;
        int target = bubbleParams.x + bubbleParams.width / 2 < width / 2 ? -bubbleParams.width / 2 : width - bubbleParams.width / 2;
        ValueAnimator a = ValueAnimator.ofInt(bubbleParams.x, target);
        a.setDuration(180);
        a.addUpdateListener(v -> { bubbleParams.x = (Integer) v.getAnimatedValue(); try { wm.updateViewLayout(bubble, bubbleParams); } catch (Exception ignored) {} });
        a.start();
    }

    private void applyAppearance() {
        Ui.applyTheme(data);
        if(floatingBrowser!=null)floatingBrowser.refreshExtensions();
        if (bubble == null || bubbleParams == null) return;
        int size = Ui.dp(this, data.bubbleSize());
        boolean right = bubbleParams.x > getResources().getDisplayMetrics().widthPixels / 2;
        bubbleParams.width = size; bubbleParams.height = size;
        bubbleParams.x = right ? getResources().getDisplayMetrics().widthPixels - size / 2 : -size / 2;
        bubble.invalidate();
        bubble.setIcon(ImageStore.load(data.bubbleIconPath()));
        try { wm.updateViewLayout(bubble, bubbleParams); } catch (Exception ignored) {}
        if(panel!=null&&panelParams!=null){
            int panelColor=(Math.round(255*data.panelAlpha()/100f)<<24)|0x00FFFFFF;
            panel.setBackground(ImageStore.background(this,data.panelBackgroundPath(),panelColor,data.panelRadius(),Ui.BORDER,data.panelAlpha(),data.panelImageBlur(),50));
            panelParams.width=Math.min(Ui.dp(this,420),Math.round(getResources().getDisplayMetrics().widthPixels*data.panelWidth()/100f));
            if(Build.VERSION.SDK_INT>=31){if(data.panelBlur()){panelParams.flags|=WindowManager.LayoutParams.FLAG_BLUR_BEHIND;panelParams.setBlurBehindRadius(Ui.dp(this,data.panelBlurRadius()));}else panelParams.flags&=~WindowManager.LayoutParams.FLAG_BLUR_BEHIND;}
            try{wm.updateViewLayout(panel,panelParams);}catch(Exception ignored){}
            rebuildPanel();
        }
    }

    private void togglePanel() {
        if (panel != null) { removePanel(); return; }
        UpdateGuard.guardOverlay(this, this::showPanel);
    }

    private void showPanel() {
        if(UpdateGuard.hasForceGate(this)){UpdateGuard.openGate(this);return;}
        if(bubble==null||bubbleParams==null)return;
        final int animationToken=++panelAnimationToken;
        if(bubbleTransitionAnimator!=null)bubbleTransitionAnimator.cancel();
        bubble.setVisibility(View.VISIBLE);bubble.setTransitionProgress(1f);bubble.setEnabled(false);
        captureClipboard();
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        int width = data.panelCompact() ? Ui.dp(this, 68) : Math.min(Ui.dp(this, 340), Math.round(screenWidth * Math.min(80,data.panelWidth()) / 100f));
        int height = Math.min(Ui.dp(this, 720), Math.round(screenHeight * .88f));
        boolean right = bubbleParams.x > screenWidth / 2;
        panelOnRight = right;
        panel = Ui.vertical(this);
        panel.setPadding(Ui.dp(this, data.panelCompact()?7:12), Ui.dp(this, data.panelCompact()?8:12), Ui.dp(this, data.panelCompact()?7:12), Ui.dp(this, 10));
        int panelColor = (Math.round(255 * data.panelAlpha() / 100f) << 24) | 0x00FFFFFF;
        panel.setBackground(ImageStore.background(this,data.panelBackgroundPath(),panelColor,data.panelRadius(),Ui.BORDER,data.panelAlpha(),data.panelImageBlur(),50));
        panel.setElevation(Ui.dp(this, 12));
        panel.setOnTouchListener((v,e)->{if(e.getAction()==MotionEvent.ACTION_OUTSIDE){removePanel();return true;}return false;});
        panelExpanded = !data.panelCompact();
        if(panelExpanded) buildPanelHeader();
        panelBody = Ui.vertical(this);
        panel.addView(panelBody, panelExpanded ? new LinearLayout.LayoutParams(-1, 0, 1) : new LinearLayout.LayoutParams(-1, -2));
        if (panelExpanded) renderPanelBody(); else renderSidebarMenu();
        panelParams = overlayParams(width, panelExpanded ? height : WindowManager.LayoutParams.WRAP_CONTENT, true);
        panelParams.flags |= WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
        if (Build.VERSION.SDK_INT >= 31 && data.panelBlur()) {
            panelParams.flags |= WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
            panelParams.setBlurBehindRadius(Ui.dp(this, data.panelBlurRadius()));
        }
        panelParams.gravity = Gravity.TOP | Gravity.START;
        panelParams.x = right ? screenWidth - width - Ui.dp(this, 8) : Ui.dp(this, 8);
        panelParams.y = Math.max(Ui.dp(this, 22), Math.min(bubbleParams.y - Ui.dp(this, 50), screenHeight - height - Ui.dp(this, 22)));
        try {
            panel.setAlpha(0f); panel.setTranslationX(right ? Ui.dp(this,28) : -Ui.dp(this,28)); wm.addView(panel, panelParams);
            panel.animate().cancel();panel.animate().alpha(1f).translationX(0f).setDuration(200).start();
            animateBubbleDrawing(1f,0f,135,0,animationToken,false);
        } catch (Exception e) { panel = null;if(bubbleTransitionAnimator!=null)bubbleTransitionAnimator.cancel();bubble.setTransitionProgress(1f);bubble.setEnabled(true); }
    }

    private void buildExpandedPanel() {
        panel.removeAllViews();
        buildPanelHeader();
        panelBody = Ui.vertical(this);
        panel.addView(panelBody, new LinearLayout.LayoutParams(-1, 0, 1));
        renderPanelBody();
    }

    private void renderSidebarMenu() {
        if (panelBody == null) return;
        panelBody.removeAllViews();
        String[][] items={{"⌘","快捷"},{"✎","备忘"},{"◷","提醒"},{"◈","渲染"},{"▣","浏览"},{"⚙","设置"}};
        for (int i=0;i<items.length;i++) {
            final int index=i;LinearLayout item=Ui.vertical(this);item.setGravity(Gravity.CENTER);item.setPadding(0,Ui.dp(this,5),0,Ui.dp(this,4));
            TextView icon=Ui.text(this,items[i][0],19,data.panelTextColor(),true);icon.setGravity(Gravity.CENTER);icon.setBackground(panelBlockBackground(15));item.addView(icon,new LinearLayout.LayoutParams(Ui.dp(this,50),Ui.dp(this,44)));
            TextView label=Ui.text(this,items[i][1],10,data.panelTextColor(),true);label.setGravity(Gravity.CENTER);item.addView(label,new LinearLayout.LayoutParams(-1,Ui.dp(this,24)));
            item.setOnClickListener(v->{if(index==4){if(floatingBrowser==null)floatingBrowser=new FloatingBrowserWindow(OverlayService.this,wm,data);floatingBrowser.show(data.browserHome());removePanel();}else if(index==5){expandPanel(4);}else expandPanel(index);});
            panelBody.addView(item,new LinearLayout.LayoutParams(-1,Ui.dp(this,70)));Ui.margin(item,0,0,0,3,this);
        }
        TextView plus=Ui.text(this,"+",24,data.panelTextColor(),false);plus.setGravity(Gravity.CENTER);plus.setBackground(panelBlockBackground(15));plus.setOnClickListener(v->expandPanel(5));panelBody.addView(plus,new LinearLayout.LayoutParams(-1,Ui.dp(this,48)));Ui.margin(plus,0,5,0,0,this);
    }

    private void expandPanel(int section) {
        if(panel==null||panelParams==null)return;activeTab=section;panelExpanded=true;
        int screenWidth=getResources().getDisplayMetrics().widthPixels;int screenHeight=getResources().getDisplayMetrics().heightPixels;int width=Math.min(Ui.dp(this,340),Math.round(screenWidth*Math.min(80,data.panelWidth())/100f));panelParams.width=width;panelParams.height=Math.min(Ui.dp(this,700),Math.round(screenHeight*.82f));panelParams.x=panelOnRight?screenWidth-width-Ui.dp(this,8):Ui.dp(this,8);
        try{wm.updateViewLayout(panel,panelParams);}catch(Exception ignored){}
        buildExpandedPanel();panel.setAlpha(.82f);panel.setTranslationX(panelOnRight?Ui.dp(this,18):-Ui.dp(this,18));panel.animate().alpha(1f).translationX(0f).setDuration(180).start();
    }

    private void buildPanelHeader() {
        LinearLayout row = Ui.horizontal(this);
        if(panelExpanded){TextView back=Ui.text(this,"‹",24,Ui.MUTED,false);back.setGravity(Gravity.CENTER);back.setBackground(Ui.bordered(Ui.SURFACE,Ui.BORDER,10,this));back.setOnClickListener(v->{panelExpanded=false;int width=Ui.dp(this,68);panelParams.width=width;panelParams.height=WindowManager.LayoutParams.WRAP_CONTENT;panelParams.x=panelOnRight?getResources().getDisplayMetrics().widthPixels-width-Ui.dp(this,8):Ui.dp(this,8);try{wm.updateViewLayout(panel,panelParams);}catch(Exception ignored){}panel.removeAllViews();panelBody=Ui.vertical(this);panel.addView(panelBody,new LinearLayout.LayoutParams(-1,-2));renderSidebarMenu();});row.addView(back,Ui.lp(Ui.dp(this,34),Ui.dp(this,34)));}
        TextView title=Ui.text(this,new String[]{"快捷功能","备忘录","定时提醒","性能渲染","悬浮设置","添加快捷方式"}[Math.max(0,Math.min(5,activeTab))],15,data.panelTextColor(),true);title.setGravity(Gravity.CENTER_VERTICAL);title.setPadding(Ui.dp(this,8),0,0,0);row.addView(title,Ui.weight(1));
        TextView close = Ui.text(this, "×", 23, Ui.MUTED, false);
        close.setGravity(Gravity.CENTER); close.setBackground(Ui.bordered(Color.WHITE, Ui.BORDER, 10, this));
        close.setOnClickListener(v -> removePanel());
        row.addView(close, Ui.lp(Ui.dp(this, 34), Ui.dp(this, 34)));
        panel.addView(row, new LinearLayout.LayoutParams(-1, Ui.dp(this, 46)));
    }

    private void buildTabs() {
        LinearLayout tabs = Ui.horizontal(this);
        String[] names = {"快捷", "备忘", "提醒", "渲染"};
        for (int i = 0; i < names.length; i++) {
            final int index = i;
            TextView tab = Ui.text(this, names[i], 11, activeTab == i ? Color.WHITE : Ui.MUTED, true);
            tab.setGravity(Gravity.CENTER);
            tab.setBackground(activeTab == i ? Ui.bg(Ui.GREEN, 10, this) : Ui.bg(Ui.GREEN_SOFT, 10, this));
            tab.setOnClickListener(v -> { activeTab = index; rebuildPanel(); });
            tabs.addView(tab, Ui.weight(1)); Ui.margin(tab, i == 0 ? 0 : 3, 0, i == names.length - 1 ? 0 : 3, 0, this);
        }
        panel.addView(tabs, new LinearLayout.LayoutParams(-1, Ui.dp(this, 38)));
        Ui.margin(tabs, 0, 4, 0, 8, this);
    }

    private void rebuildPanel() {
        if (panel == null) return;
        if(!panelExpanded){renderSidebarMenu();return;}
        panel.removeViews(1, panel.getChildCount() - 1);
        panelBody = Ui.vertical(this);
        panel.addView(panelBody, new LinearLayout.LayoutParams(-1, 0, 1));
        renderPanelBody();
    }

    private void renderPanelBody() {
        if (panelBody == null) return;
        panelBody.removeAllViews();
        if (activeTab == 0) renderQuick();
        else if (activeTab == 1) renderNotes("");
        else if (activeTab == 2) renderReminders();
        else if (activeTab == 3) renderMonitorPanel();
        else if (activeTab == 4) renderOverlaySettings();
        else renderAddShortcut();
    }

    private void renderOverlaySettings(){TextView info=Ui.text(this,"这些设置立即作用于当前悬浮侧栏。",10,Ui.MUTED,false);info.setPadding(0,0,0,Ui.dp(this,10));panelBody.addView(info);CheckBox compact=new CheckBox(this);compact.setText("使用窄侧栏入口");compact.setTextColor(data.panelTextColor());compact.setChecked(data.panelCompact());compact.setOnCheckedChangeListener((v,c)->data.panelCompact(c));panelBody.addView(compact,new LinearLayout.LayoutParams(-1,Ui.dp(this,44)));CheckBox blur=new CheckBox(this);blur.setText("背景模糊");blur.setTextColor(data.panelTextColor());blur.setChecked(data.panelBlur());blur.setOnCheckedChangeListener((v,c)->data.panelBlur(c));panelBody.addView(blur,new LinearLayout.LayoutParams(-1,Ui.dp(this,44)));TextView alpha=Ui.text(this,"背景透明度："+data.panelAlpha()+"%",11,data.panelTextColor(),true);panelBody.addView(alpha);android.widget.SeekBar bar=new android.widget.SeekBar(this);bar.setMax(100);bar.setProgress(data.panelAlpha());bar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(android.widget.SeekBar b,int p,boolean u){data.panelAlpha(p);alpha.setText("背景透明度："+p+"%");}public void onStartTrackingTouch(android.widget.SeekBar b){}public void onStopTrackingTouch(android.widget.SeekBar b){Toast.makeText(OverlayService.this,"下次展开时应用新背景",Toast.LENGTH_SHORT).show();}});panelBody.addView(bar,new LinearLayout.LayoutParams(-1,Ui.dp(this,44)));Button advanced=Ui.button(this,"打开完整个性化设置",false);advanced.setOnClickListener(v->{if(UpdateGuard.hasForceGate(this)){removePanel();UpdateGuard.openGate(this);return;}startActivity(new Intent(this,MainActivity.class).putExtra(MainActivity.EXTRA_PAGE,6).putExtra("my_subpage","appearance").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));removePanel();});panelBody.addView(advanced,new LinearLayout.LayoutParams(-1,Ui.dp(this,44)));}

    private void renderAddShortcut(){EditText title=new EditText(this);title.setHint("名称，例如：工作台");title.setSingleLine(true);title.setTextColor(data.panelTextColor());title.setHintTextColor(Ui.MUTED);title.setBackground(panelBlockBackground(11));title.setPadding(Ui.dp(this,12),0,Ui.dp(this,12),0);panelBody.addView(title,new LinearLayout.LayoutParams(-1,Ui.dp(this,46)));EditText url=new EditText(this);url.setHint("网址，例如：https://example.com");url.setSingleLine(true);url.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);url.setTextColor(data.panelTextColor());url.setHintTextColor(Ui.MUTED);url.setBackground(panelBlockBackground(11));url.setPadding(Ui.dp(this,12),0,Ui.dp(this,12),0);panelBody.addView(url,new LinearLayout.LayoutParams(-1,Ui.dp(this,46)));Ui.margin(url,0,8,0,0,this);Button save=Ui.button(this,"保存到快捷功能",true);save.setOnClickListener(v->{String n=title.getText().toString().trim(),u=url.getText().toString().trim();if(n.isEmpty()||u.isEmpty()){Toast.makeText(this,"请填写名称和网址",Toast.LENGTH_SHORT).show();return;}if(!u.startsWith("http://")&&!u.startsWith("https://"))u="https://"+u;List<JianData.QuickItem> list=data.quickItems();JianData.QuickItem q=new JianData.QuickItem();q.title=n;q.value=u;q.type=JianData.TYPE_WEB;q.group="悬浮添加";q.glyph="链";list.add(0,q);data.saveQuick(list);activeTab=0;renderPanelBody();Toast.makeText(this,"已添加快捷方式",Toast.LENGTH_SHORT).show();});panelBody.addView(save,new LinearLayout.LayoutParams(-1,Ui.dp(this,44)));Ui.margin(save,0,8,0,0,this);TextView help=Ui.text(this,"App 和系统开关可在简盒主页面的快捷功能管理中添加。",10,Ui.MUTED,false);panelBody.addView(help);}

    private void renderQuick() {
        List<JianData.QuickItem> list = data.quickItems();
        ScrollView scroll = new ScrollView(this);
        LinearLayout wrap = Ui.vertical(this);
        scroll.addView(wrap, new ScrollView.LayoutParams(-1, -2));
        Map<String, List<JianData.QuickItem>> groups = new LinkedHashMap<>();
        for (JianData.QuickItem q : list) groups.computeIfAbsent(q.group, k -> new ArrayList<>()).add(q);
        for (Map.Entry<String, List<JianData.QuickItem>> group : groups.entrySet()) {
            TextView label = Ui.text(this, group.getKey(), 11, Ui.MUTED, true);
            label.setPadding(Ui.dp(this, 3), Ui.dp(this, 12), 0, Ui.dp(this, 5)); wrap.addView(label);
            for (JianData.QuickItem q : group.getValue()) wrap.addView(quickRow(q, list), new LinearLayout.LayoutParams(-1, Ui.dp(this, 60)));
        }
        TextView hint = Ui.text(this, "长按拖动排序 · 左滑后确认删除", 10, 0x7799AA99, false);
        hint.setGravity(Gravity.CENTER); hint.setPadding(0, Ui.dp(this, 11), 0, Ui.dp(this, 12)); wrap.addView(hint);
        panelBody.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    @SuppressLint("ClickableViewAccessibility") // The row retains a click listener; touch only adds the left-swipe gesture.
    private View quickRow(JianData.QuickItem item, List<JianData.QuickItem> items) {
        LinearLayout row = Ui.horizontal(this);
        row.setPadding(Ui.dp(this, 10), Ui.dp(this, 7), Ui.dp(this, 10), Ui.dp(this, 7));
        row.setBackground(panelBlockBackground(13));
        TextView icon = Ui.icon(this, item.glyph, Ui.GREEN);
        row.addView(icon, Ui.lp(Ui.dp(this, 40), Ui.dp(this, 40)));
        LinearLayout copy = Ui.vertical(this); copy.setPadding(Ui.dp(this, 11), 0, 0, 0);
        copy.addView(Ui.text(this, item.title, 13, data.panelTextColor(), true));
        String sub = JianData.TYPE_APP.equals(item.type) ? "应用" : JianData.TYPE_WEB.equals(item.type) ? item.value.replaceFirst("https?://", "") : "系统开关";
        TextView subView = Ui.text(this, sub, 10, Ui.MUTED, false); subView.setSingleLine(true); copy.addView(subView);
        row.addView(copy, Ui.weight(1));
        TextView arrow = Ui.text(this, "›", 22, Ui.MUTED, false); row.addView(arrow, Ui.lp(Ui.dp(this, 24), -1));
        final float[] downX = new float[1];
        row.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) downX[0] = e.getX();
            if (e.getAction() == MotionEvent.ACTION_UP && downX[0] - e.getX() > Ui.dp(this, 75)) {
                showQuickDeleteConfirm(row, item, items); return true;
            }
            return false;
        });
        row.setOnClickListener(v -> launch(item));
        row.setOnLongClickListener(v -> {
            ClipData drag = ClipData.newPlainText("jianbox-item", item.id);
            v.startDragAndDrop(drag, new View.DragShadowBuilder(v), item.id, 0);
            v.setAlpha(.35f); return true;
        });
        row.setOnDragListener((v, event) -> {
            if (event.getAction() == DragEvent.ACTION_DRAG_ENTERED) {
                String draggedId = String.valueOf(event.getLocalState());
                int from = indexOf(items, draggedId), to = indexOf(items, item.id);
                if (from >= 0 && to >= 0 && from != to) { JianData.QuickItem moved = items.remove(from); items.add(to, moved); data.saveQuick(items); }
            }
            if (event.getAction() == DragEvent.ACTION_DRAG_ENDED) { v.setAlpha(1f); renderPanelBody(); }
            return true;
        });
        return row;
    }

    private void showQuickDeleteConfirm(LinearLayout row, JianData.QuickItem item, List<JianData.QuickItem> items) {
        row.removeAllViews(); row.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
        row.setBackground(Ui.bordered(0xFFFFF3F2, 0x55EF5350, 13, this));
        row.addView(Ui.text(this, "确认删除“" + item.title + "”？", 12, Ui.RED, true), Ui.weight(1));
        Button cancel = Ui.button(this, "取消", false); Button confirm = Ui.button(this, "删除", false); confirm.setTextColor(Ui.RED);
        row.addView(cancel, Ui.lp(Ui.dp(this, 58), Ui.dp(this, 40))); row.addView(confirm, Ui.lp(Ui.dp(this, 58), Ui.dp(this, 40)));
        cancel.setOnClickListener(v -> renderPanelBody());
        confirm.setOnClickListener(v -> { items.removeIf(q -> q.id.equals(item.id)); data.saveQuick(items); renderPanelBody(); });
    }

    private int indexOf(List<JianData.QuickItem> list, String id) {
        for (int i = 0; i < list.size(); i++) if (list.get(i).id.equals(id)) return i; return -1;
    }

    private void launch(JianData.QuickItem q) {
        if(UpdateGuard.hasForceGate(this)){removePanel();UpdateGuard.openGate(this);return;}
        try {
            Intent i;
            if (JianData.TYPE_WEB.equals(q.type)) {
                removePanel();
                if (floatingBrowser == null) floatingBrowser = new FloatingBrowserWindow(this, wm, data);
                floatingBrowser.show(q.value);
                return;
            }
            else if (JianData.TYPE_APP.equals(q.type)) {
                i = getPackageManager().getLaunchIntentForPackage(q.value);
                if (i == null) throw new IllegalStateException("App unavailable");
            } else { systemAction(q.value); return; }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i); removePanel();
        } catch (Exception e) { Toast.makeText(this, "暂时无法打开 " + q.title, Toast.LENGTH_SHORT).show(); }
    }

    private void systemAction(String action) {
        if(UpdateGuard.hasForceGate(this)){removePanel();UpdateGuard.openGate(this);return;}
        try {
            Intent intent;
            if ("wifi".equals(action)) intent = Build.VERSION.SDK_INT >= 29 ? new Intent(Settings.Panel.ACTION_WIFI) : new Intent(Settings.ACTION_WIFI_SETTINGS);
            else if ("bluetooth".equals(action)) intent = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
            else if ("brightness".equals(action)) intent = new Intent(Settings.ACTION_DISPLAY_SETTINGS);
            else if ("torch".equals(action)) { toggleTorch(); return; }
            else intent = new Intent(Settings.ACTION_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(intent); removePanel();
        } catch (Exception e) { Toast.makeText(this, "系统面板不可用", Toast.LENGTH_SHORT).show(); }
    }

    private void toggleTorch() {
        CameraManager cm = (CameraManager) getSystemService(CAMERA_SERVICE);
        try {
            for (String id : cm.getCameraIdList()) {
                Boolean flash = cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                Integer facing = cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
                if (Boolean.TRUE.equals(flash) && facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    torchOn = !torchOn; cm.setTorchMode(id, torchOn); Toast.makeText(this, torchOn ? "手电筒已打开" : "手电筒已关闭", Toast.LENGTH_SHORT).show(); return;
                }
            }
        } catch (Exception e) { Toast.makeText(this, "请允许相机权限后使用手电筒", Toast.LENGTH_SHORT).show(); }
    }

    private void renderNotes(String query) {
        LinearLayout searchRow = Ui.horizontal(this);
        EditText input = new EditText(this); input.setHint("写下备忘，支持 #标签"); input.setTextSize(12); input.setSingleLine(true);
        input.setTextColor(data.panelTextColor()); input.setHintTextColor(0x7799AA99); input.setBackground(panelBlockBackground(11));
        input.setPadding(Ui.dp(this, 11), 0, Ui.dp(this, 9), 0); searchRow.addView(input, Ui.weight(1));
        Button save = Ui.button(this, "＋", true); save.setTextSize(19); searchRow.addView(save, Ui.lp(Ui.dp(this, 52), Ui.dp(this, 42))); Ui.margin(save, 7, 0, 0, 0, this);
        save.setOnClickListener(v -> { String text = input.getText().toString().trim(); data.addClip(text, JianData.extractTags(text), "备忘"); input.setText(""); renderNotesList("", listHolder()); });
        panelBody.addView(searchRow, new LinearLayout.LayoutParams(-1, Ui.dp(this, 44)));

        EditText search = new EditText(this); search.setHint("搜索历史、链接或标签"); search.setTextSize(11); search.setSingleLine(true);
        search.setTextColor(data.panelTextColor()); search.setHintTextColor(0x7799AA99); search.setBackground(panelBlockBackground(12));
        search.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0); panelBody.addView(search, new LinearLayout.LayoutParams(-1, Ui.dp(this, 48))); Ui.margin(search, 0, 9, 0, 8, this);
        ScrollView scroll = new ScrollView(this); LinearLayout list = listHolder(); scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        panelBody.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        renderNotesList(query, list);
        search.addTextChangedListener(new SimpleWatcher(s -> renderNotesList(s, list)));
    }

    private LinearLayout listHolder() { return Ui.vertical(this); }

    private void renderNotesList(String query, LinearLayout holder) {
        if (holder.getParent() == null && panelBody != null) { renderPanelBody(); return; }
        holder.removeAllViews();
        String needle = query == null ? "" : query.toLowerCase(Locale.CHINA);
        for (JianData.ClipItem clip : data.clips()) {
            if (!needle.isEmpty() && !(clip.text + " " + clip.tags).toLowerCase(Locale.CHINA).contains(needle)) continue;
            holder.addView(noteRow(clip), new LinearLayout.LayoutParams(-1, -2));
        }
        if (holder.getChildCount() == 0) {
            TextView empty = Ui.text(this, "复制内容或在上方写一条备忘吧", 12, Ui.MUTED, false); empty.setGravity(Gravity.CENTER);
            holder.addView(empty, new LinearLayout.LayoutParams(-1, Ui.dp(this, 90)));
        }
    }

    private View noteRow(JianData.ClipItem clip) {
        LinearLayout row = Ui.vertical(this); row.setPadding(Ui.dp(this, 11), Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 9));
        row.setBackground(panelBlockBackground(13));
        LinearLayout top = Ui.horizontal(this);
        TextView kind = Ui.text(this, clip.kind + (clip.tags.isEmpty() ? "" : "  " + clip.tags), 10, Ui.GREEN, true); top.addView(kind, Ui.weight(1));
        TextView star = Ui.text(this, clip.favorite ? "★" : "☆", 18, clip.favorite ? 0xFFFFB300 : Ui.MUTED, false);
        star.setGravity(Gravity.CENTER); top.addView(star, Ui.lp(Ui.dp(this, 34), Ui.dp(this, 28)));
        star.setOnClickListener(v -> { List<JianData.ClipItem> clips = data.clips(); for (JianData.ClipItem c : clips) if (c.id.equals(clip.id)) c.favorite = !c.favorite; data.saveClips(clips); renderPanelBody(); });
        TextView delete = Ui.text(this, "删", 11, Ui.RED, true); delete.setGravity(Gravity.CENTER); top.addView(delete, Ui.lp(Ui.dp(this, 36), Ui.dp(this, 28)));
        delete.setOnClickListener(v -> showNoteDeleteConfirm(row, clip));
        row.addView(top);
        if ("图片".equals(clip.kind)) {
            ImageView image = new ImageView(this); image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            try { image.setImageURI(Uri.parse(clip.text)); } catch (Exception ignored) {}
            row.addView(image, new LinearLayout.LayoutParams(-1, Ui.dp(this, 90)));
        } else {
            TextView text = Ui.text(this, clip.text, 12, data.panelTextColor(), false); text.setMaxLines(3); text.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 3)); row.addView(text);
        }
        TextView time = Ui.text(this, DATE.format(clip.time) + "  ·  轻点复制", 9, 0x7799AA99, false); row.addView(time);
        row.setOnClickListener(v -> {
            clipboard.setPrimaryClip(ClipData.newPlainText("简盒", clip.text)); Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.setMargins(0, 0, 0, Ui.dp(this, 7)); row.setLayoutParams(p);
        return row;
    }

    private void showNoteDeleteConfirm(LinearLayout row, JianData.ClipItem clip) {
        row.removeAllViews(); row.setGravity(Gravity.CENTER_VERTICAL); row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackground(Ui.bordered(0xFFFFF3F2, 0x55EF5350, 13, this));
        row.addView(Ui.text(this, "删除这条备忘？", 12, Ui.RED, true), Ui.weight(1));
        Button cancel = Ui.button(this, "取消", false); Button confirm = Ui.button(this, "删除", false); confirm.setTextColor(Ui.RED);
        row.addView(cancel, Ui.lp(Ui.dp(this, 58), Ui.dp(this, 40))); row.addView(confirm, Ui.lp(Ui.dp(this, 58), Ui.dp(this, 40)));
        cancel.setOnClickListener(v -> renderPanelBody());
        confirm.setOnClickListener(v -> { List<JianData.ClipItem> clips = data.clips(); clips.removeIf(c -> c.id.equals(clip.id)); data.saveClips(clips); renderPanelBody(); });
    }

    private void renderReminders() {
        data.pendingReminder(false); if (bubble != null) bubble.setAlert(false);
        Button add = Ui.button(this, "+  新建提醒", true); add.setOnClickListener(v -> inlineReminderEditor());
        panelBody.addView(add, new LinearLayout.LayoutParams(-1, Ui.dp(this, 44)));
        ScrollView scroll = new ScrollView(this); LinearLayout list = Ui.vertical(this); scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        panelBody.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1)); Ui.margin(scroll, 0, 8, 0, 0, this);
        int count = 0;
        for (JianData.Reminder r : data.reminders()) if (!r.done) { list.addView(reminderRow(r), new LinearLayout.LayoutParams(-1, -2)); count++; }
        if (count == 0) { TextView empty = Ui.text(this, "没有待处理提醒，一身轻。", 12, Ui.MUTED, false); empty.setGravity(Gravity.CENTER); list.addView(empty, new LinearLayout.LayoutParams(-1, Ui.dp(this, 110))); }
    }

    private void inlineReminderEditor() {
        panelBody.removeAllViews();
        TextView title = Ui.text(this, "新建提醒", 16, data.panelTextColor(), true); panelBody.addView(title, new LinearLayout.LayoutParams(-1, Ui.dp(this, 42)));
        EditText input = new EditText(this); input.setHint("提醒我……"); input.setTextSize(14); input.setTextColor(data.panelTextColor()); input.setSingleLine(true); input.setBackground(panelBlockBackground(11)); input.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        panelBody.addView(input, new LinearLayout.LayoutParams(-1, Ui.dp(this, 48)));
        TextView help = Ui.text(this, "选择触发时间", 11, Ui.MUTED, false); help.setPadding(0, Ui.dp(this, 13), 0, Ui.dp(this, 7)); panelBody.addView(help);
        for (Object[] choice : new Object[][]{{"10 分钟后", 10 * 60_000L, "once"}, {"1 小时后", 60 * 60_000L, "once"}, {"每天此刻", 24 * 60 * 60_000L, "daily"}}) {
            Button b = Ui.button(this, String.valueOf(choice[0]), false); panelBody.addView(b, new LinearLayout.LayoutParams(-1, Ui.dp(this, 44))); Ui.margin(b, 0, 0, 0, 7, this);
            b.setOnClickListener(v -> {
                if (input.getText().toString().trim().isEmpty()) { Toast.makeText(this, "先写下提醒内容", Toast.LENGTH_SHORT).show(); return; }
                JianData.Reminder r = new JianData.Reminder(); r.title = input.getText().toString().trim(); r.time = System.currentTimeMillis() + (Long) choice[1]; r.repeat = String.valueOf(choice[2]); if (!"once".equals(r.repeat)) r.repeatCount = 0;
                data.updateReminder(r); ReminderReceiver.schedule(this, r); renderPanelBody();
            });
        }
        Button advanced = Ui.button(this, "自定义时间、次数和提醒方式", true);
        advanced.setOnClickListener(v -> inlineAdvancedReminder(input.getText().toString().trim()));
        panelBody.addView(advanced, new LinearLayout.LayoutParams(-1, Ui.dp(this, 46))); Ui.margin(advanced, 0, 4, 0, 8, this);
        Button back = Ui.button(this, "返回", false); back.setOnClickListener(v -> renderPanelBody()); panelBody.addView(back, new LinearLayout.LayoutParams(-1, Ui.dp(this, 44)));
    }

    private void inlineAdvancedReminder(String initial){panelBody.removeAllViews();EditText input=new EditText(this);input.setHint("提醒内容");input.setText(initial);input.setSingleLine(true);input.setTextColor(data.panelTextColor());input.setHintTextColor(Ui.MUTED);input.setBackground(panelBlockBackground(11));input.setPadding(Ui.dp(this,12),0,Ui.dp(this,12),0);panelBody.addView(input,new LinearLayout.LayoutParams(-1,Ui.dp(this,46)));java.util.Calendar now=java.util.Calendar.getInstance();android.widget.DatePicker date=new android.widget.DatePicker(this);date.setMinDate(System.currentTimeMillis()-1000);panelBody.addView(date,new LinearLayout.LayoutParams(-1,Ui.dp(this,155)));android.widget.TimePicker time=new android.widget.TimePicker(this);time.setIs24HourView(true);time.setHour(now.get(java.util.Calendar.HOUR_OF_DAY));time.setMinute(now.get(java.util.Calendar.MINUTE));panelBody.addView(time,new LinearLayout.LayoutParams(-1,Ui.dp(this,120)));LinearLayout checks=Ui.horizontal(this);CheckBox vibrate=new CheckBox(this);vibrate.setText("震动");vibrate.setTextColor(data.panelTextColor());vibrate.setChecked(true);CheckBox sound=new CheckBox(this);sound.setText("声音");sound.setTextColor(data.panelTextColor());sound.setChecked(true);checks.addView(vibrate,Ui.weight(1));checks.addView(sound,Ui.weight(1));panelBody.addView(checks,new LinearLayout.LayoutParams(-1,Ui.dp(this,42)));Button save=Ui.button(this,"保存自定义提醒",true);save.setOnClickListener(v->{String text=input.getText().toString().trim();if(text.isEmpty()){Toast.makeText(this,"请填写提醒内容",Toast.LENGTH_SHORT).show();return;}java.util.Calendar when=java.util.Calendar.getInstance();when.set(date.getYear(),date.getMonth(),date.getDayOfMonth(),time.getHour(),time.getMinute(),0);if(when.getTimeInMillis()<=System.currentTimeMillis()){Toast.makeText(this,"请选择未来时间",Toast.LENGTH_SHORT).show();return;}JianData.Reminder r=new JianData.Reminder();r.title=text;r.time=when.getTimeInMillis();r.vibrate=vibrate.isChecked();r.sound=sound.isChecked();r.repeat="once";r.repeatCount=1;data.updateReminder(r);ReminderReceiver.schedule(this,r);renderPanelBody();});panelBody.addView(save,new LinearLayout.LayoutParams(-1,Ui.dp(this,44)));}

    private View reminderRow(JianData.Reminder reminder) {
        LinearLayout card = Ui.vertical(this); card.setPadding(Ui.dp(this, 12), Ui.dp(this, 11), Ui.dp(this, 10), Ui.dp(this, 10)); card.setBackground(panelBlockBackground(13));
        card.addView(Ui.text(this, reminder.title, 13, data.panelTextColor(), true));
        String repeat = "hourly".equals(reminder.repeat) ? "每小时" : "daily".equals(reminder.repeat) ? "每天" : "仅一次";
        String modes = (reminder.vibrate ? "震动" : "") + (reminder.sound ? (reminder.vibrate ? "+声音" : "声音") : "");
        String times = reminder.repeatCount == 0 ? "不限次数" : reminder.repeatCount + "次";
        TextView sub = Ui.text(this, DATE.format(reminder.time) + "  ·  " + repeat + "  ·  " + times + (modes.isEmpty() ? "" : "  ·  " + modes), 10, Ui.MUTED, false); sub.setPadding(0, Ui.dp(this, 3), 0, Ui.dp(this, 7)); card.addView(sub);
        LinearLayout actions = Ui.horizontal(this);
        Button snooze = Ui.button(this, "稍后10分钟", false); Button done = Ui.button(this, "标记完成", true);
        actions.addView(snooze, Ui.weight(1)); Ui.margin(snooze, 0, 0, 6, 0, this); actions.addView(done, Ui.weight(1)); card.addView(actions);
        snooze.setOnClickListener(v -> { reminder.time = System.currentTimeMillis() + 10 * 60_000; data.updateReminder(reminder); ReminderReceiver.schedule(this, reminder); renderPanelBody(); });
        done.setOnClickListener(v -> { reminder.done = true; data.updateReminder(reminder); ReminderReceiver.cancel(this, reminder); renderPanelBody(); });
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.setMargins(0, 0, 0, Ui.dp(this, 8)); card.setLayoutParams(p); return card;
    }

    private void renderMonitorPanel() {
        LinearLayout hero = Ui.card(this); hero.setBackground(Ui.bordered(Ui.GREEN_SOFT, Ui.BORDER, 15, this));
        LinearLayout line = Ui.horizontal(this);
        LinearLayout copy = Ui.vertical(this); copy.addView(Ui.text(this, "性能监控悬浮窗", 14, Ui.TEXT, true)); copy.addView(Ui.text(this, "每秒刷新 · 可独立拖动", 10, Ui.MUTED, false)); line.addView(copy, Ui.weight(1));
        Button toggle = Ui.button(this, data.monitorEnabled() ? "关闭" : "开启", !data.monitorEnabled());
        line.addView(toggle, Ui.lp(Ui.dp(this, 66), Ui.dp(this, 40))); hero.addView(line); panelBody.addView(hero, new LinearLayout.LayoutParams(-1, -2));
        toggle.setOnClickListener(v -> { data.monitorEnabled(!data.monitorEnabled()); syncMonitor(); renderPanelBody(); });
        TextView title = Ui.text(this, "自定义显示内容", 11, Ui.MUTED, true); title.setPadding(Ui.dp(this, 3), Ui.dp(this, 14), 0, Ui.dp(this, 5)); panelBody.addView(title);
        String fields = data.monitorFields();
        String[][] options = {{"cpu","App CPU"},{"ram","设备内存"},{"app","App 内存"},{"battery","电量"},{"network","网络速度"},{"fps","屏幕刷新率"}};
        for (String[] option : options) {
            CheckBox check = new CheckBox(this); check.setText(option[1]); check.setTextSize(12); check.setTextColor(Ui.TEXT); check.setChecked(csvContains(fields, option[0]));
            check.setPadding(Ui.dp(this, 6), 0, 0, 0); panelBody.addView(check, new LinearLayout.LayoutParams(-1, Ui.dp(this, 40)));
            check.setOnCheckedChangeListener((v, checked) -> { updateMonitorField(option[0], checked); syncMonitor(); });
        }
        TextView hint = Ui.text(this, "监控只读取 Android 提供的本机统计，不上传数据。非激活页面会暂停渲染以降低额外消耗。", 10, Ui.MUTED, false);
        hint.setPadding(Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4), 0); panelBody.addView(hint);
    }

    private boolean csvContains(String csv, String key) {
        for (String value : csv.split(",")) if (value.trim().equals(key)) return true; return false;
    }

    private void updateMonitorField(String key, boolean enabled) {
        List<String> values = new ArrayList<>(); for (String value : data.monitorFields().split(",")) if (!value.trim().isEmpty() && !value.equals(key)) values.add(value);
        if (enabled) values.add(key); data.monitorFields(android.text.TextUtils.join(",", values));
    }

    private void syncMonitor() {
        if (!Settings.canDrawOverlays(this) || !data.monitorEnabled()) { removeMonitor(); return; }
        if (monitorView == null) showMonitor(); else { monitorView.setAlpha(data.monitorAlpha() / 100f);monitorView.setBackground(monitorBackground());if(monitorParams!=null&&monitorParams.width!=Ui.dp(this,data.monitorWidth())){monitorParams.width=Ui.dp(this,data.monitorWidth());try{wm.updateViewLayout(monitorView,monitorParams);}catch(Exception ignored){}} if (monitorText != null) monitorText.setTextSize(data.monitorTextSize()); updateMonitorNow(); }
    }

    private Drawable monitorBackground() { String path=data.monitorBackgroundPath(); int shade=path.isEmpty()?0xE6222925:(Math.round(255*data.monitorBackgroundShade()/100f)<<24); return ImageStore.background(this,path,shade,12,data.monitorBorderColor(),data.monitorBackgroundAlpha(),data.monitorBackgroundBlur(),50); }

    @SuppressLint("ClickableViewAccessibility")
    private void showMonitor() {
        monitorView = Ui.vertical(this); monitorView.setPadding(Ui.dp(this, 11), Ui.dp(this, 8), Ui.dp(this, 11), Ui.dp(this, 8));
        monitorView.setBackground(monitorBackground()); monitorView.setElevation(Ui.dp(this, 8)); monitorView.setAlpha(data.monitorAlpha() / 100f);
        LinearLayout head = Ui.horizontal(this); TextView brand = Ui.text(this, "JIAN · RENDER", 9, 0xFFE7EEE9, true); head.addView(brand, Ui.weight(1));
        TextView close = Ui.text(this, "×", 15, Color.WHITE, false); close.setGravity(Gravity.CENTER); head.addView(close, Ui.lp(Ui.dp(this, 24), Ui.dp(this, 22))); monitorView.addView(head);
        monitorText = Ui.text(this, "采样中…", data.monitorTextSize(), Color.WHITE, false); monitorText.setTypeface(Typeface.MONOSPACE); monitorText.setLineSpacing(Ui.dp(this, 2), 1f); monitorView.addView(monitorText);
        monitorParams = overlayParams(Ui.dp(this, data.monitorWidth()), WindowManager.LayoutParams.WRAP_CONTENT, false); monitorParams.gravity = Gravity.TOP | Gravity.START; monitorParams.x = Ui.dp(this, 14); monitorParams.y = Ui.dp(this, 110);
        final float[] down = new float[4]; final boolean[] monitorMoved = new boolean[1];
        monitorView.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) { down[0]=e.getRawX(); down[1]=e.getRawY(); down[2]=monitorParams.x; down[3]=monitorParams.y; monitorMoved[0]=false; return true; }
            if (e.getAction() == MotionEvent.ACTION_MOVE) { if(Math.abs(e.getRawX()-down[0])+Math.abs(e.getRawY()-down[1])>Ui.dp(this,6))monitorMoved[0]=true; monitorParams.x=(int)(down[2]+e.getRawX()-down[0]); monitorParams.y=Math.max(0,(int)(down[3]+e.getRawY()-down[1])); try{wm.updateViewLayout(monitorView,monitorParams);}catch(Exception ignored){} return true; }
            if (e.getAction() == MotionEvent.ACTION_UP) { if(!monitorMoved[0])v.performClick(); return true; } return false;
        });
        monitorView.setOnClickListener(v -> { activeTab = 3; if (panel == null) showPanel(); else rebuildPanel(); });
        close.setOnClickListener(v -> { data.monitorEnabled(false); removeMonitor(); if (panel != null && activeTab == 3) renderPanelBody(); });
        try { wm.addView(monitorView, monitorParams); } catch (Exception e) { monitorView = null; return; }
        lastCpuTime = Process.getElapsedCpuTime(); lastSampleTime = SystemClock.elapsedRealtime(); lastRx = TrafficStats.getUidRxBytes(Process.myUid()); lastTx = TrafficStats.getUidTxBytes(Process.myUid());
        monitorHandler.post(monitorTick);
    }

    private final Runnable monitorTick = new Runnable() {
        @Override public void run() { if (monitorView == null) return; updateMonitorNow(); monitorHandler.postDelayed(this, 1000); }
    };

    private void updateMonitorNow() {
        if (monitorText == null) return;
        long now = SystemClock.elapsedRealtime(), cpuNow = Process.getElapsedCpuTime();
        float cpu = lastSampleTime == 0 ? 0 : Math.max(0, Math.min(999, (cpuNow - lastCpuTime) * 100f / Math.max(1, now - lastSampleTime)));
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE); ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo(); am.getMemoryInfo(memory);
        float usedRam = memory.totalMem == 0 ? 0 : (memory.totalMem - memory.availMem) * 100f / memory.totalMem;
        float appMb = Debug.getPss() / 1024f;
        BatteryManager battery = (BatteryManager) getSystemService(BATTERY_SERVICE); int batteryLevel = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        long rx = TrafficStats.getUidRxBytes(Process.myUid()), tx = TrafficStats.getUidTxBytes(Process.myUid()); float seconds = Math.max(.2f, (now - lastSampleTime) / 1000f);
        float downKb = Math.max(0, rx - lastRx) / 1024f / seconds, upKb = Math.max(0, tx - lastTx) / 1024f / seconds;
        String fields = data.monitorFields(); StringBuilder text = new StringBuilder();
        if (csvContains(fields,"cpu")) text.append(String.format(Locale.CHINA,"CPU   %5.1f%%\n",cpu));
        if (csvContains(fields,"ram")) text.append(String.format(Locale.CHINA,"RAM   %5.1f%%\n",usedRam));
        if (csvContains(fields,"app")) text.append(String.format(Locale.CHINA,"APP   %5.1f MB\n",appMb));
        if (csvContains(fields,"battery")) text.append(String.format(Locale.CHINA,"BAT   %5d%%\n",batteryLevel));
        if (csvContains(fields,"network")) text.append(String.format(Locale.CHINA,"NET ↓ %.1f ↑ %.1f KB/s\n",downKb,upKb));
        if (csvContains(fields,"fps")) text.append(String.format(Locale.CHINA,"HZ    %5.0f",wm.getDefaultDisplay().getRefreshRate()));
        monitorText.setText(text.length() == 0 ? "未选择监控项" : text.toString().trim());
        lastCpuTime=cpuNow; lastSampleTime=now; lastRx=rx; lastTx=tx;
    }

    private void removeMonitor() {
        monitorHandler.removeCallbacks(monitorTick);
        if (monitorView != null) { try { wm.removeView(monitorView); } catch (Exception ignored) {} monitorView=null; monitorText=null; }
    }

    private void installClipboardListener() {
        clipListener = this::captureClipboard;
        clipboard.addPrimaryClipChangedListener(clipListener);
    }

    private void captureClipboard() {
        try {
            ClipData clip = clipboard.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return;
            ClipData.Item item = clip.getItemAt(0);
            if (item.getUri() != null) data.addClip(item.getUri().toString(), "", "图片");
            else {
                CharSequence value = item.coerceToText(this);
                if (value != null) data.addClip(value.toString(), null, null);
            }
            if (panel != null && activeTab == 1) renderPanelBody();
        } catch (SecurityException ignored) { /* Android 10+ only allows reads while this overlay is focused. */ }
    }

    private void refreshPanel() { if (panel != null) renderPanelBody(); }

    private void removePanel() {
        LinearLayout closing=panel;if(closing==null)return;final int animationToken=++panelAnimationToken;panel=null;panelBody=null;
        closing.animate().cancel();closing.clearAnimation();closing.setAlpha(1f);closing.setTranslationX(0f);
        if(bubbleTransitionAnimator!=null){bubbleTransitionAnimator.cancel();bubbleTransitionAnimator=null;}
        if(serviceDestroying){try{wm.removeView(closing);}catch(Exception ignored){}return;}
        if(bubble==null){try{wm.removeView(closing);}catch(Exception ignored){}return;}
        // WindowManager overlays are composited independently. Draw the stable bubble first,
        // then remove the panel on the next frame; cross-window alpha animations caused flashes.
        bubble.setVisibility(View.VISIBLE);bubble.setEnabled(false);bubble.setTransitionProgress(1f);bubble.invalidate();
        bubble.postOnAnimation(()->{try{wm.removeView(closing);}catch(Exception ignored){}if(bubble!=null&&panelAnimationToken==animationToken)bubble.setEnabled(true);});
    }

    private void animateBubbleDrawing(float from,float to,long duration,long delay,int token,boolean enableAtEnd){
        if(bubble==null)return;if(bubbleTransitionAnimator!=null)bubbleTransitionAnimator.cancel();
        bubble.setTransitionProgress(from);ValueAnimator animation=ValueAnimator.ofFloat(from,to);bubbleTransitionAnimator=animation;animation.setDuration(duration);animation.setStartDelay(delay);
        animation.addUpdateListener(value->{if(bubble!=null&&panelAnimationToken==token)bubble.setTransitionProgress((Float)value.getAnimatedValue());});
        animation.addListener(new AnimatorListenerAdapter(){@Override public void onAnimationEnd(Animator value){if(bubbleTransitionAnimator==animation)bubbleTransitionAnimator=null;if(bubble!=null&&panelAnimationToken==token){bubble.setTransitionProgress(to);if(enableAtEnd)bubble.setEnabled(true);}}});animation.start();
    }

    private Drawable panelBlockBackground(float radius){int alpha=Math.round(255*data.panelBlockAlpha()/100f);return Ui.bordered((alpha<<24)|0x00FFFFFF,Ui.BORDER,radius,this);}

    private void createChannels() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel service = new NotificationChannel("jianbox_service", "简盒悬浮球", NotificationManager.IMPORTANCE_LOW);
        service.setDescription("保持屏幕边缘工具可用"); nm.createNotificationChannel(service);
        NotificationChannel reminders = new NotificationChannel("jianbox_reminder", "简盒提醒", NotificationManager.IMPORTANCE_HIGH);
        reminders.setDescription("定时备忘与提醒"); reminders.enableVibration(true); nm.createNotificationChannel(reminders);
    }

    private Notification serviceNotification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(this, "jianbox_service");
        return b.setSmallIcon(com.jianbox.app.R.drawable.ic_launcher).setContentTitle("简盒正在运行")
                .setContentText("悬浮球已停靠在屏幕边缘").setContentIntent(content).setOngoing(true).setCategory(Notification.CATEGORY_SERVICE).build();
    }

    private static final class SimpleWatcher implements TextWatcher {
        interface Changed { void value(String value); }
        private final Changed changed;
        SimpleWatcher(Changed c) { changed = c; }
        public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
        public void onTextChanged(CharSequence s, int st, int b, int c) { changed.value(s.toString()); }
        public void afterTextChanged(Editable e) {}
    }

    private final class BubbleView extends View {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        boolean alert;
        float pulse;
        ValueAnimator animator;
        Bitmap iconBitmap;
        float transitionProgress=1f;
        BubbleView(Context c) { super(c); setLayerType(View.LAYER_TYPE_SOFTWARE, null); text.setTextAlign(Paint.Align.CENTER); text.setTypeface(Typeface.DEFAULT_BOLD); }
        void setIcon(Bitmap bitmap){if(iconBitmap!=null&&iconBitmap!=bitmap&&!iconBitmap.isRecycled())iconBitmap.recycle();iconBitmap=bitmap;invalidate();}
        void setTransitionProgress(float value){transitionProgress=Math.max(0f,Math.min(1f,value));invalidate();}
        @Override public boolean performClick() { super.performClick(); togglePanel(); return true; }
        void setAlert(boolean value) {
            alert = value;
            if (animator != null) animator.cancel();
            if (alert) {
                animator = ValueAnimator.ofFloat(0f, 1f, 0f); animator.setDuration(1200); animator.setRepeatCount(ValueAnimator.INFINITE);
                animator.addUpdateListener(a -> { pulse = (Float) a.getAnimatedValue(); invalidate(); }); animator.start();
            } else { pulse = 0; invalidate(); }
        }
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);if(transitionProgress<=.001f)return;float cx = getWidth()/2f, cy = getHeight()/2f, r = Math.min(cx, cy) - Ui.dp(OverlayService.this, 4);int transitionAlpha=Math.round(255*transitionProgress);float scale=.86f+.14f*transitionProgress;c.save();c.scale(scale,scale,cx,cy);
            if (alert) { paint.setColor(0x44EF5350);paint.setAlpha(Math.round(68*transitionProgress)); c.drawCircle(cx, cy, r + Ui.dp(OverlayService.this, 4) * pulse, paint); }
            paint.setColor(data.bubbleColor()); paint.setAlpha(Math.round(255 * data.bubbleAlpha()/100f*transitionProgress)); paint.setShadowLayer(Ui.dp(OverlayService.this, 5), 0, Ui.dp(OverlayService.this, 2), 0x55000000); c.drawCircle(cx, cy, r, paint);
            paint.clearShadowLayer();
            if(iconBitmap!=null&&!iconBitmap.isRecycled()){c.save();c.clipPath(new android.graphics.Path(){{addCircle(cx,cy,r,Direction.CW);}});paint.setAlpha(Math.round(255*data.bubbleAlpha()/100f*transitionProgress));ImageStore.drawCenterCrop(c,iconBitmap,new RectF(cx-r,cy-r,cx+r,cy+r),paint);c.restore();}
            else {text.setColor(Color.WHITE); text.setAlpha(transitionAlpha); text.setTextSize(r * .72f); Paint.FontMetrics f = text.getFontMetrics();c.drawText("简", cx, cy - (f.ascent + f.descent)/2, text);}
            if (alert) { paint.setColor(Ui.RED); paint.setAlpha(transitionAlpha); c.drawCircle(getWidth()*.76f, getHeight()*.23f, Ui.dp(OverlayService.this, 5), paint); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(Ui.dp(OverlayService.this, 2)); paint.setColor(Color.WHITE);paint.setAlpha(transitionAlpha); c.drawCircle(getWidth()*.76f, getHeight()*.23f, Ui.dp(OverlayService.this, 5), paint); paint.setStyle(Paint.Style.FILL); }
            c.restore();
        }
    }
}
