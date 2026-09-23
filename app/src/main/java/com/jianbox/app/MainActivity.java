package com.jianbox.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {
    public static final String EXTRA_NEW_REMINDER = "new_reminder";
    public static final String EXTRA_PAGE = "page";
    private static final int REQUEST_BUBBLE_ICON=501, REQUEST_PANEL_BG=502, REQUEST_MONITOR_BG=503, REQUEST_BROWSER_BG=504, REQUEST_APP_ICON=505, REQUEST_APP_BACKGROUND=506, REQUEST_PROFILE_AVATAR=507;
    private JianData data;
    private LinearLayout content;
    private ScrollView mainScroll;
    private LinearLayout bottomNav;
    private int currentPage;
    private String mySubpage="";
    private Switch serviceSwitch;
    private TextView permissionHint;
    private boolean profileRefreshInFlight;
    private long lastProfileRefreshAt;
    private static final SimpleDateFormat DATE = new SimpleDateFormat("MM月dd日 HH:mm", Locale.CHINA);
    private boolean reminderIntentHandled;
    private final Handler appearanceHandler=new Handler(Looper.getMainLooper());
    private final Runnable appearanceUpdate=()->{if(!data.serviceEnabled()||!Settings.canDrawOverlays(this))return;Intent i=new Intent(this,OverlayService.class).setAction(OverlayService.ACTION_APPEARANCE);try{startService(i);}catch(Exception ignored){}};

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        data = new JianData(this);
        if(!data.privacyAccepted()){startActivity(new Intent(this,PrivacyConsentActivity.class));finish();return;}
        Ui.applyTheme(data);
        currentPage=Math.max(0,Math.min(7,getIntent().getIntExtra(EXTRA_PAGE,0)));
        mySubpage=getIntent().getStringExtra("my_subpage")==null?"":getIntent().getStringExtra("my_subpage");
        if (UpdateGuard.enforce(this)) return;
        buildScreen();
        askRuntimePermissions();
        BackendClient.heartbeat(data);
    }

    @Override protected void onResume() {
        super.onResume();
        if (!UpdateGuard.resume(this)) return;
        updatePermissionState();
        if (data.serviceEnabled() && Settings.canDrawOverlays(this)) startBubble();
        if (content != null) render();
        if (!reminderIntentHandled && getIntent().getBooleanExtra(EXTRA_NEW_REMINDER, false)) {
            reminderIntentHandled = true; content.post(() -> reminderDialog(null));
        }
    }

    private void buildScreen() {
        getWindow().setStatusBarColor(Ui.GREEN_PALE);
        getWindow().setNavigationBarColor(Ui.GREEN_PALE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);

        LinearLayout shell=Ui.vertical(this); android.graphics.drawable.Drawable shellBg;
        if (Ui.GLASS) shellBg=ImageStore.background(this,appBackgroundPath(),0x23000000,0,0x33FFFFFF,100,0,data.appBackgroundPosition());
        else shellBg=new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{Ui.GREEN_SOFT,Color.WHITE,Ui.GREEN_PALE});
        shell.setBackground(shellBg);
        mainScroll = new ScrollView(this);
        mainScroll.setFillViewport(true);
        mainScroll.setBackgroundColor(Color.TRANSPARENT);
        content = Ui.vertical(this);
        content.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 24));
        int contentWidth=Math.min(getResources().getDisplayMetrics().widthPixels,Ui.dp(this,820));ScrollView.LayoutParams contentParams=new ScrollView.LayoutParams(contentWidth,-2,Gravity.CENTER_HORIZONTAL);mainScroll.addView(content,contentParams);
        shell.addView(mainScroll,new LinearLayout.LayoutParams(-1,0,1));
        HorizontalScrollView navScroll=new HorizontalScrollView(this);navScroll.setHorizontalScrollBarEnabled(false);navScroll.setFillViewport(true);navScroll.setBackground(Ui.bordered(0xFAFFFFFF,Ui.BORDER,16,this));navScroll.setElevation(Ui.dp(this,12));
        bottomNav=Ui.horizontal(this);bottomNav.setGravity(Gravity.CENTER);bottomNav.setPadding(Ui.dp(this,5),Ui.dp(this,3),Ui.dp(this,5),Ui.dp(this,4));navScroll.addView(bottomNav,new HorizontalScrollView.LayoutParams(-1,-1));shell.addView(navScroll,new LinearLayout.LayoutParams(-1,Ui.dp(this,60)));setContentView(shell);
        render();
    }

    private void render() {
        if(content!=null){content.animate().cancel();content.setAlpha(.35f);content.setTranslationY(Ui.dp(this,10));}
        content.removeAllViews();
        if(currentPage==6&&!mySubpage.isEmpty())addMySubpageHeader();else addHeader();
        if(currentPage==0){addBrowserDashboard();addBrowserCard();addRecentHistoryCard();}
        else if(currentPage==1){addToolsDashboard();addShortcutsCard();addReminderCard();}
        else if(currentPage==2){addServiceCard();addMonitorCard();}
        else if(currentPage==3){addPluginsCard();}
        else if(currentPage==4){addAppearanceCard();addAdvancedAppearanceCard();}
        else if(currentPage==5){addBrowserSettingsCard();addAppLibraryCard();addPrivacyCard();}
        else if(currentPage==6){if("appearance".equals(mySubpage)){addAppearanceCard();addAdvancedAppearanceCard();}else if("browser_data".equals(mySubpage)){addMyBrowserDataPage();}else if("privacy".equals(mySubpage)){addPrivacyCard();}else addMyPage();}
        else {addAboutCard();removeLegacyAboutUpdateAction();}
        rebuildBottomNav();
        if(content!=null)content.animate().alpha(1f).translationY(0f).setDuration(180).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
    }

    private void addHeader() {
        LinearLayout row = Ui.horizontal(this);
        android.graphics.Bitmap customLogo=ImageStore.load(data.appIconPath().isEmpty()?data.profileAvatarPath():data.appIconPath());
        if(customLogo==null){TextView logo=Ui.icon(this,"简",Color.WHITE);row.addView(logo,Ui.lp(Ui.dp(this,40),Ui.dp(this,40)));}
        else {ImageView logo=new ImageView(this);logo.setScaleType(ImageView.ScaleType.CENTER_CROP);logo.setImageBitmap(customLogo);logo.setBackground(Ui.bg(Ui.GREEN_SOFT,10,this));logo.setClipToOutline(true);row.addView(logo,Ui.lp(Ui.dp(this,40),Ui.dp(this,40)));}
        LinearLayout titles = Ui.vertical(this);
        titles.setPadding(Ui.dp(this, 12), 0, 0, 0);
        String[] labels={"JIAN EDGE · BROWSER FIRST","JIAN BOX · QUICK TOOLS","JIAN FLOAT · OVERLAY","JIAN EDGE · EXTENSIONS","JIAN BOX · PERSONALIZE","JIAN BOX · SETTINGS","JIAN BOX · PROFILE","JIAN BOX · ABOUT"};
        String[] names={"浏览器","便捷工具","悬浮窗口","插件","个性化","设置","我的","关于"};
        titles.addView(Ui.label(this, labels[currentPage]));
        titles.addView(Ui.text(this, names[currentPage], 21, Ui.TEXT, true));
        row.addView(titles, Ui.weight(1));
        TextView edge = Ui.text(this, "EDGE", 9, Ui.GREEN, true); edge.setGravity(Gravity.CENTER); edge.setBackground(Ui.bg(Ui.GREEN_SOFT, 10, this)); row.addView(edge, Ui.lp(Ui.dp(this, 48), Ui.dp(this, 32)));
        content.addView(row, new LinearLayout.LayoutParams(-1, Ui.dp(this, 48)));
        String[] subs={"轻量搜索、多标签与悬浮浏览。","格式化、转换、快捷入口和提醒。","管理悬浮球、浏览器窗和性能监控。","按需启用 WebView 原生扩展能力。","软件主题、壁纸、图标和侧栏外观。","搜索、安全、历史与默认入口。","头像、昵称、简介、签到与账号。","软件说明、隐私边界和开源致谢。"};
        TextView sub = Ui.text(this, subs[currentPage], 12, Ui.MUTED, false);
        content.addView(sub, new LinearLayout.LayoutParams(-1, Ui.dp(this, 30)));
    }

    private void rebuildBottomNav(){
        if(bottomNav==null)return;bottomNav.removeAllViews();String[][] items={{"⌕","浏览"},{"◇","工具"},{"浮","悬浮"},{"插","插件"},{"⚙","设置"},{"我","我的"},{"i","关于"}};int[] pages={0,1,2,3,5,6,7};
        for(int i=0;i<items.length;i++){final int page=pages[i];boolean selected=page==currentPage;LinearLayout cell=Ui.vertical(this);cell.setGravity(Gravity.CENTER);cell.setBackground(selected?Ui.bg(Ui.GREEN_SOFT,11,this):Ui.bg(Color.TRANSPARENT,11,this));TextView icon=Ui.text(this,items[i][0],14,selected?Ui.GREEN:Ui.MUTED,true);icon.setGravity(Gravity.CENTER);TextView label=Ui.text(this,items[i][1],8,selected?Ui.GREEN:Ui.MUTED,selected);label.setGravity(Gravity.CENTER);cell.addView(icon,new LinearLayout.LayoutParams(-1,Ui.dp(this,25)));cell.addView(label,new LinearLayout.LayoutParams(-1,Ui.dp(this,19)));cell.setOnClickListener(v->{if(currentPage!=page||!mySubpage.isEmpty()){currentPage=page;mySubpage="";mainScroll.scrollTo(0,0);render();}});bottomNav.addView(cell,new LinearLayout.LayoutParams(Ui.dp(this,62),-1));if(i<items.length-1)Ui.margin(cell,0,0,2,0,this);}
    }

    private void addMySubpageHeader(){LinearLayout row=Ui.horizontal(this);TextView back=Ui.text(this,"‹",27,Ui.TEXT,false);back.setGravity(Gravity.CENTER);back.setBackground(Ui.bordered(Ui.SURFACE,Ui.BORDER,10,this));back.setOnClickListener(v->{mySubpage="";mainScroll.scrollTo(0,0);render();});row.addView(back,Ui.lp(Ui.dp(this,38),Ui.dp(this,38)));LinearLayout title=Ui.vertical(this);title.setPadding(Ui.dp(this,10),0,0,0);String name="appearance".equals(mySubpage)?"个性化":"browser_data".equals(mySubpage)?"收藏与历史":"隐私与安全";title.addView(Ui.text(this,name,19,Ui.TEXT,true));title.addView(Ui.text(this,"我的 · "+name,9,Ui.MUTED,false));row.addView(title,Ui.weight(1));content.addView(row,new LinearLayout.LayoutParams(-1,Ui.dp(this,50)));}

    private void addMyBrowserDataPage(){LinearLayout card=Ui.card(this);card.addView(sectionTitle("浏览数据","览"));TextView summary=Ui.text(this,"本机保存 "+data.bookmarks().size()+" 条收藏、"+data.history().size()+" 条历史记录。",11,Ui.MUTED,false);summary.setPadding(0,Ui.dp(this,10),0,Ui.dp(this,10));card.addView(summary);Button bookmarks=Ui.button(this,"打开收藏与分组",true);bookmarks.setOnClickListener(v->BrowserActivity.open(this,"jian://bookmarks"));card.addView(bookmarks,new LinearLayout.LayoutParams(-1,Ui.dp(this,42)));Button history=Ui.button(this,"查询和删除历史记录",false);history.setOnClickListener(v->showBrowserHistory());card.addView(history,new LinearLayout.LayoutParams(-1,Ui.dp(this,42)));Ui.margin(history,0,7,0,0,this);content.addView(card,new LinearLayout.LayoutParams(-1,-2));}

    private void addBrowserDashboard(){
        int shade=Math.round(255*data.browserBackgroundShade()/100f)<<24;
        LinearLayout hero=Ui.card(this);hero.setPadding(Ui.dp(this,14),Ui.dp(this,14),Ui.dp(this,14),Ui.dp(this,12));hero.setBackground(ImageStore.background(this,data.browserBackgroundPath(),shade,16,0x44FFFFFF,Math.min(100,data.browserBackgroundBrightness()),data.browserBackgroundBlur(),data.browserBackgroundPosition()));
        String engine=SearchEngine.safeId(data.searchEngine());hero.addView(Ui.text(this,"Jian Edge",11,0xEEFFFFFF,true));hero.addView(Ui.text(this,"搜索整个网络",21,Color.WHITE,true));TextView hint=Ui.text(this,"简洁主页 · 本地风险检查",10,0xDDFFFFFF,false);hint.setPadding(0,Ui.dp(this,3),0,Ui.dp(this,12));hero.addView(hint);
        LinearLayout searchRow=Ui.horizontal(this);EditText search=new EditText(this);search.setSingleLine(true);search.setHint(SearchEngine.label(engine)+" 搜索或输入网址");search.setTextSize(12);search.setTextColor(Ui.TEXT);search.setHintTextColor(0x7799AA99);search.setBackground(Ui.bg(0xF7FFFFFF,12,this));search.setPadding(Ui.dp(this,12),0,Ui.dp(this,10),0);TextView go=Ui.text(this,"⌕",18,Color.WHITE,true);go.setGravity(Gravity.CENTER);go.setBackground(Ui.bg(SearchEngine.color(engine),12,this));searchRow.addView(search,new LinearLayout.LayoutParams(0,Ui.dp(this,46),1));Ui.margin(search,0,0,7,0,this);searchRow.addView(go,Ui.lp(Ui.dp(this,46),Ui.dp(this,46)));hero.addView(searchRow,new LinearLayout.LayoutParams(-1,Ui.dp(this,46)));go.setOnClickListener(v->BrowserActivity.open(this,search.getText().toString()));search.setOnEditorActionListener((v,a,e)->{go.performClick();return true;});
        LinearLayout quick=Ui.horizontal(this);quick.setPadding(0,Ui.dp(this,7),0,0);String[][] links={{"新标签","jian://home"},{"导航收藏","https://go.taolove.top/"},{"安全说明","https://github.com/TrianguloY/URLCheck"}};for(int i=0;i<links.length;i++){String[] link=links[i];Button b=Ui.button(this,link[0],false);b.setTextSize(9);b.setOnClickListener(v->BrowserActivity.open(this,link[1]));quick.addView(b,new LinearLayout.LayoutParams(0,Ui.dp(this,38),1));if(i<links.length-1)Ui.margin(b,0,0,5,0,this);}hero.addView(quick);content.addView(hero,new LinearLayout.LayoutParams(-1,-2));Ui.margin(hero,0,4,0,9,this);
    }

    private void addRecentHistoryCard(){
        LinearLayout card=Ui.card(this);card.addView(sectionTitle("最近浏览","时"));int count=0;for(JianData.BrowserHistory h:data.history()){if(count++==4)break;LinearLayout row=Ui.horizontal(this);TextView title=Ui.text(this,h.title,12,Ui.TEXT,false);title.setSingleLine(true);row.addView(title,Ui.weight(1));TextView host=Ui.text(this,Uri.parse(h.url).getHost(),9,Ui.MUTED,false);row.addView(host);row.setOnClickListener(v->BrowserActivity.open(this,h.url));card.addView(row,new LinearLayout.LayoutParams(-1,Ui.dp(this,42)));}if(count==0){TextView empty=Ui.text(this,"浏览记录会显示在这里",11,Ui.MUTED,false);empty.setPadding(0,Ui.dp(this,12),0,Ui.dp(this,8));card.addView(empty);}Button all=Ui.button(this,"查询与管理历史",false);all.setOnClickListener(v->showBrowserHistory());card.addView(all,new LinearLayout.LayoutParams(-1,Ui.dp(this,42)));content.addView(card,new LinearLayout.LayoutParams(-1,-2));Ui.margin(card,0,0,0,12,this);
    }

    private void addToolsDashboard(){
        LinearLayout card=Ui.card(this);card.setBackground(Ui.bordered(0xFFF1F8F1,Ui.BORDER,16,this));card.addView(sectionTitle("本地与在线工具","工"));TextView intro=Ui.text(this,"JSON 美化、文本清理、URL 编解码，以及文档、音频、图片格式转换入口。",11,Ui.MUTED,false);intro.setPadding(0,Ui.dp(this,9),0,Ui.dp(this,10));card.addView(intro);Button open=Ui.button(this,"打开便捷工具箱",true);open.setOnClickListener(v->startActivity(new Intent(this,ToolboxActivity.class)));card.addView(open,new LinearLayout.LayoutParams(-1,Ui.dp(this,44)));content.addView(card,new LinearLayout.LayoutParams(-1,-2));Ui.margin(card,0,5,0,12,this);
    }

    private void addMonitorCard(){
        LinearLayout card=Ui.card(this);card.setBackground(monitorBackground(16));LinearLayout line=Ui.horizontal(this);LinearLayout copy=Ui.vertical(this);copy.addView(Ui.text(this,"性能监控窗",15,Color.WHITE,true));copy.addView(Ui.text(this,"CPU · 内存 · 网络 · 电量 · 刷新率",10,0xDDFFFFFF,false));line.addView(copy,Ui.weight(1));Switch toggle=new Switch(this);toggle.setChecked(data.monitorEnabled());toggle.setOnCheckedChangeListener((v,c)->{data.monitorEnabled(c);if(c){data.serviceEnabled(true);ensureOverlayAndStart();}notifyAppearance();});line.addView(toggle);card.addView(line);card.addView(sliderRow("监控窗宽度",130,300,data.monitorWidth(),v->{data.monitorWidth(v);notifyAppearance();}));LinearLayout bgActions=Ui.horizontal(this);bgActions.setPadding(0,Ui.dp(this,7),0,0);Button bg=Ui.button(this,"上传性能窗背景",false);Button clear=Ui.button(this,"清除背景",false);bg.setOnClickListener(v->chooseImage(REQUEST_MONITOR_BG));clear.setOnClickListener(v->{data.monitorBackgroundPath("");notifyAppearance();render();});bgActions.addView(bg,new LinearLayout.LayoutParams(0,Ui.dp(this,44),1));Ui.margin(bg,0,0,7,0,this);bgActions.addView(clear,new LinearLayout.LayoutParams(0,Ui.dp(this,44),1));card.addView(bgActions);content.addView(card,new LinearLayout.LayoutParams(-1,-2));Ui.margin(card,0,0,0,12,this);
    }

    private android.graphics.drawable.Drawable monitorBackground(float radius){String path=data.monitorBackgroundPath();int shade=path.isEmpty()?0xE6222925:(Math.round(255*data.monitorBackgroundShade()/100f)<<24);return ImageStore.background(this,path,shade,radius,data.monitorBorderColor(),data.monitorBackgroundAlpha(),data.monitorBackgroundBlur(),50);}

    private void addAppLibraryCard(){
        LinearLayout card=Ui.card(this);card.addView(sectionTitle("应用与权限","应"));TextView info=Ui.text(this,"从带搜索的应用库添加快捷启动项，也可以管理系统授予简盒的权限。",11,Ui.MUTED,false);info.setPadding(0,Ui.dp(this,9),0,Ui.dp(this,10));card.addView(info);LinearLayout actions=Ui.horizontal(this);Button apps=Ui.button(this,"App 选择页",true);Button permissions=Ui.button(this,"应用设置",false);apps.setOnClickListener(v->startActivity(new Intent(this,AppPickerActivity.class)));permissions.setOnClickListener(v->startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName()))));actions.addView(apps,Ui.weight(1));Ui.margin(apps,0,0,7,0,this);actions.addView(permissions,Ui.weight(1));card.addView(actions);content.addView(card,new LinearLayout.LayoutParams(-1,-2));Ui.margin(card,0,0,0,12,this);
    }

    private void addServiceCard() {
        LinearLayout card = Ui.card(this);
        LinearLayout top = Ui.horizontal(this);
        TextView icon = Ui.icon(this, "浮", Ui.GREEN);
        top.addView(icon, Ui.lp(Ui.dp(this, 42), Ui.dp(this, 42)));
        LinearLayout copy = Ui.vertical(this);
        copy.setPadding(Ui.dp(this, 12), 0, 0, 0);
        copy.addView(Ui.text(this, "边缘悬浮球", 16, Ui.TEXT, true));
        permissionHint = Ui.text(this, "检查权限中…", 11, Ui.MUTED, false);
        copy.addView(permissionHint);
        top.addView(copy, Ui.weight(1));
        serviceSwitch = new Switch(this);
        serviceSwitch.setChecked(data.serviceEnabled());
        serviceSwitch.setOnCheckedChangeListener((button, checked) -> {
            data.serviceEnabled(checked);
            if (checked) ensureOverlayAndStart(); else stopBubble();
        });
        top.addView(serviceSwitch, Ui.lp(-2, Ui.dp(this, 46)));
        card.addView(top, new LinearLayout.LayoutParams(-1, -2));

        TextView help = Ui.text(this, "拖动后自动吸附左右边缘并半隐藏；轻点展开侧边栏。", 12, Ui.MUTED, false);
        help.setPadding(0, Ui.dp(this, 12), 0, 0);
        card.addView(help);
        content.addView(card, new LinearLayout.LayoutParams(-1, -2));
        Ui.margin(card, 0, 8, 0, 12, this);
    }

    private void addAppearanceCard() {
        LinearLayout card = Ui.card(this);
        card.addView(sectionTitle("个性外观", "调"));
        TextView styleLabel=Ui.text(this,"软件整体样式",12,Ui.MUTED,false);styleLabel.setPadding(0,Ui.dp(this,12),0,Ui.dp(this,6));card.addView(styleLabel);
        LinearLayout styles=Ui.horizontal(this);Button mint=Ui.button(this,"薄荷白绿","mint".equals(data.appStyle())),glass=Ui.button(this,"壁纸玻璃",Ui.GLASS),night=Ui.button(this,"深夜蓝","midnight".equals(data.appStyle())),rose=Ui.button(this,"樱粉","rose".equals(data.appStyle()));
        mint.setOnClickListener(v->{data.appStyle("mint");Ui.applyTheme(data);buildScreen();});glass.setOnClickListener(v->{data.appStyle("glass");Ui.applyTheme(data);buildScreen();});night.setOnClickListener(v->{data.appStyle("midnight");Ui.applyTheme(data);buildScreen();});rose.setOnClickListener(v->{data.appStyle("rose");Ui.applyTheme(data);buildScreen();});
        styles.addView(mint,new LinearLayout.LayoutParams(0,Ui.dp(this,38),1));Ui.margin(mint,0,0,5,0,this);styles.addView(glass,new LinearLayout.LayoutParams(0,Ui.dp(this,38),1));Ui.margin(glass,0,0,5,0,this);styles.addView(night,new LinearLayout.LayoutParams(0,Ui.dp(this,38),1));Ui.margin(night,0,0,5,0,this);styles.addView(rose,new LinearLayout.LayoutParams(0,Ui.dp(this,38),1));card.addView(styles);
        TextView styleHint=Ui.text(this,Ui.GLASS?"整套界面使用软件级壁纸、半透明卡片和玻璃底栏；可在下方上传壁纸并调整裁切位置。":"主题会替换整个软件的底色、卡片、边框与文字对比度。",10,Ui.MUTED,false);styleHint.setPadding(0,Ui.dp(this,6),0,0);card.addView(styleHint);
        LinearLayout iconActions=Ui.horizontal(this);iconActions.setPadding(0,Ui.dp(this,10),0,0);Button customIcon=Ui.button(this,"上传悬浮球图标",false);Button resetIcon=Ui.button(this,"恢复默认",false);customIcon.setOnClickListener(v->chooseImage(REQUEST_BUBBLE_ICON));resetIcon.setOnClickListener(v->{data.bubbleIconPath("");notifyAppearance();});iconActions.addView(customIcon,new LinearLayout.LayoutParams(0,Ui.dp(this,44),1));Ui.margin(customIcon,0,0,7,0,this);iconActions.addView(resetIcon,new LinearLayout.LayoutParams(0,Ui.dp(this,44),1));card.addView(iconActions);
        card.addView(sliderRow("大小", 40, 72, data.bubbleSize(), value -> { data.bubbleSize(value); notifyAppearance(); }));
        card.addView(sliderRow("透明度", 35, 100, data.bubbleAlpha(), value -> { data.bubbleAlpha(value); notifyAppearance(); }));
        content.addView(card, new LinearLayout.LayoutParams(-1, -2));
        Ui.margin(card, 0, 0, 0, 12, this);
    }

    private void addAdvancedAppearanceCard() {
        LinearLayout card = Ui.card(this); card.addView(sectionTitle("侧栏与背景", "层"));
        LinearLayout compactRow=Ui.horizontal(this);LinearLayout compactCopy=Ui.vertical(this);compactCopy.addView(Ui.text(this,"窄侧栏入口",13,Ui.TEXT,true));compactCopy.addView(Ui.text(this,"展开悬浮球后先显示图标长条，点击分区再进入完整页面",10,Ui.MUTED,false));compactRow.addView(compactCopy,Ui.weight(1));Switch compactToggle=new Switch(this);compactToggle.setChecked(data.panelCompact());compactToggle.setOnCheckedChangeListener((v,checked)->{data.panelCompact(checked);notifyAppearance();});compactRow.addView(compactToggle);card.addView(compactRow);
        card.addView(sliderRow("侧栏宽度 %", 72, 96, data.panelWidth(), v -> { data.panelWidth(v); notifyAppearance(); }));
        card.addView(sliderRow("侧栏背景不透明度 %", 0, 100, data.panelAlpha(), v -> { data.panelAlpha(v); notifyAppearance(); }));
        card.addView(sliderRow("功能区块不透明度 %", 0, 100, data.panelBlockAlpha(), v -> { data.panelBlockAlpha(v); notifyAppearance(); }));
        card.addView(sliderRow("侧栏图片模糊", 0, 20, data.panelImageBlur(), v -> { data.panelImageBlur(v); notifyAppearance(); }));
        card.addView(sliderRow("圆角", 10, 32, data.panelRadius(), v -> { data.panelRadius(v); notifyAppearance(); }));
        LinearLayout blurRow = Ui.horizontal(this); LinearLayout copy = Ui.vertical(this); copy.addView(Ui.text(this, "背景虚化", 13, Ui.TEXT, true)); copy.addView(Ui.text(this, "Android 12+ 且设备支持时生效", 10, Ui.MUTED, false)); blurRow.addView(copy, Ui.weight(1));
        Switch blur = new Switch(this); blur.setChecked(data.panelBlur()); blur.setOnCheckedChangeListener((v, checked) -> { data.panelBlur(checked); notifyAppearance(); }); blurRow.addView(blur); card.addView(blurRow);
        card.addView(sliderRow("虚化强度", 0, 60, data.panelBlurRadius(), v -> { data.panelBlurRadius(v); notifyAppearance(); }));
        TextView textColorLabel=Ui.text(this,"侧栏主题文字颜色",12,Ui.MUTED,false);textColorLabel.setPadding(0,Ui.dp(this,8),0,Ui.dp(this,6));card.addView(textColorLabel);LinearLayout textColors=Ui.horizontal(this);int[] textPalette={0xFF1A3A1A,0xFF161B22,0xFFFFFFFF,0xFF17324D,0xFF5A2030};for(int color:textPalette){TextView chip=Ui.text(this,color==data.panelTextColor()?"✓":"",13,color==Color.WHITE?Ui.TEXT:Color.WHITE,true);chip.setGravity(Gravity.CENTER);chip.setBackground(Ui.bordered(color,color==Color.WHITE?Ui.BORDER:color,16,this));chip.setOnClickListener(v->{data.panelTextColor(color);notifyAppearance();render();});textColors.addView(chip,Ui.lp(Ui.dp(this,32),Ui.dp(this,32)));Ui.margin(chip,0,0,9,0,this);}card.addView(textColors);
        card.addView(sliderRow("性能窗透明度 %", 40, 100, data.monitorAlpha(), v -> { data.monitorAlpha(v); notifyAppearance(); }));
        card.addView(sliderRow("性能窗字号", 9, 16, data.monitorTextSize(), v -> { data.monitorTextSize(v); notifyAppearance(); }));
        card.addView(sliderRow("性能背景不透明度 %",20,100,data.monitorBackgroundAlpha(),v->{data.monitorBackgroundAlpha(v);notifyAppearance();}));
        card.addView(sliderRow("性能背景暗色遮罩 %",0,60,data.monitorBackgroundShade(),v->{data.monitorBackgroundShade(v);notifyAppearance();}));
        card.addView(sliderRow("性能背景模糊",0,16,data.monitorBackgroundBlur(),v->{data.monitorBackgroundBlur(v);notifyAppearance();}));
        TextView borderLabel=Ui.text(this,"性能窗边框",12,Ui.MUTED,false);borderLabel.setPadding(0,Ui.dp(this,8),0,Ui.dp(this,6));card.addView(borderLabel);LinearLayout borderColors=Ui.horizontal(this);int[] borderPalette={0x00FFFFFF,0x66FFFFFF,0x665AAE76,0x6664A5F5,0x66FFB74D,0x669E9E9E};for(int color:borderPalette){TextView chip=Ui.text(this,color==data.monitorBorderColor()?"✓":"",13,Color.WHITE,true);chip.setGravity(Gravity.CENTER);chip.setBackground(Ui.bordered(color==0?0xFF303733:color|0xFF000000,color==0?0x44FFFFFF:color,16,this));chip.setOnClickListener(v->{data.monitorBorderColor(color);notifyAppearance();render();});borderColors.addView(chip,Ui.lp(Ui.dp(this,32),Ui.dp(this,32)));Ui.margin(chip,0,0,9,0,this);}card.addView(borderColors);
        TextView browserLabel=Ui.text(this,"软件级背景与裁切",13,Ui.TEXT,true);browserLabel.setPadding(0,Ui.dp(this,14),0,0);card.addView(browserLabel);
        TextView appBackgroundHint=Ui.text(this,"壁纸玻璃主题会应用到主页、插件、设置、下载和底部栏；上下滑动“展示位置”即可选择竖图的取景区域。",10,Ui.MUTED,false);appBackgroundHint.setPadding(0,Ui.dp(this,4),0,0);card.addView(appBackgroundHint);
        card.addView(sliderRow("软件背景展示位置",0,100,data.appBackgroundPosition(),v->{data.appBackgroundPosition(v);if(Ui.GLASS)buildScreen();}));
        card.addView(sliderRow("背景亮度 %",50,140,data.browserBackgroundBrightness(),data::browserBackgroundBrightness));
        card.addView(sliderRow("暗色遮罩 %",0,60,data.browserBackgroundShade(),data::browserBackgroundShade));
        card.addView(sliderRow("背景模糊",0,20,data.browserBackgroundBlur(),data::browserBackgroundBlur));
        card.addView(sliderRow("显示区域（顶部到下部）",0,100,data.browserBackgroundPosition(),data::browserBackgroundPosition));
        LinearLayout backgrounds=Ui.horizontal(this);backgrounds.setPadding(0,Ui.dp(this,9),0,0);Button appBg=Ui.button(this,"上传软件背景",false);Button browserBg=Ui.button(this,"搜索页背景图",false);appBg.setOnClickListener(v->chooseImage(REQUEST_APP_BACKGROUND));browserBg.setOnClickListener(v->chooseImage(REQUEST_BROWSER_BG));backgrounds.addView(appBg,new LinearLayout.LayoutParams(0,Ui.dp(this,44),1));Ui.margin(appBg,0,0,7,0,this);backgrounds.addView(browserBg,new LinearLayout.LayoutParams(0,Ui.dp(this,44),1));card.addView(backgrounds);
        Button resetBackgrounds=Ui.button(this,"清除自定义软件背景",false);resetBackgrounds.setOnClickListener(v->{data.appBackgroundPath("");notifyAppearance();buildScreen();});card.addView(resetBackgrounds,new LinearLayout.LayoutParams(-1,Ui.dp(this,42)));Ui.margin(resetBackgrounds,0,8,0,0,this);
        TextView themeLabel=Ui.text(this,"软件整体配色",13,Ui.TEXT,true);themeLabel.setPadding(0,Ui.dp(this,14),0,Ui.dp(this,7));card.addView(themeLabel);LinearLayout themes=Ui.horizontal(this);int[] accents={0xFF4CAF50,0xFF00897B,0xFF1976D2,0xFF7B1FA2,0xFFE64A19,0xFF455A64};for(int color:accents){TextView chip=Ui.text(this,color==data.appAccentColor()?"✓":"",13,Color.WHITE,true);chip.setGravity(Gravity.CENTER);chip.setBackground(Ui.bg(color,17,this));chip.setOnClickListener(v->{data.appAccentColor(color);Ui.applyAccent(color);buildScreen();notifyAppearance();});themes.addView(chip,Ui.lp(Ui.dp(this,34),Ui.dp(this,34)));Ui.margin(chip,0,0,9,0,this);}card.addView(themes);
        LinearLayout iconActions=Ui.horizontal(this);iconActions.setPadding(0,Ui.dp(this,9),0,0);Button appIcon=Ui.button(this,"上传应用内图标",false);Button clearIcon=Ui.button(this,"恢复简字图标",false);appIcon.setOnClickListener(v->chooseImage(REQUEST_APP_ICON));clearIcon.setOnClickListener(v->{data.appIconPath("");render();});iconActions.addView(appIcon,new LinearLayout.LayoutParams(0,Ui.dp(this,44),1));Ui.margin(appIcon,0,0,7,0,this);iconActions.addView(clearIcon,new LinearLayout.LayoutParams(0,Ui.dp(this,44),1));card.addView(iconActions);
        content.addView(card, new LinearLayout.LayoutParams(-1, -2)); Ui.margin(card, 0, 0, 0, 12, this);
    }

    private void addBrowserCard() {
        LinearLayout card = Ui.card(this); card.setBackground(Ui.bordered(0xFFF1F8F1, Ui.BORDER, 16, this)); card.addView(sectionTitle("Jian Edge 内置浏览器", "网"));
        TextView text = Ui.text(this, "默认首页：简洁搜索工作台\n多标签懒加载、扩展工具栏与悬浮浏览", 11, Ui.MUTED, false); text.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10)); card.addView(text);
        LinearLayout actions = Ui.horizontal(this); Button open = Ui.button(this, "打开搜索主页", true); Button history = Ui.button(this, "浏览历史", false);
        open.setOnClickListener(v -> BrowserActivity.open(this, data.browserHome())); history.setOnClickListener(v -> showBrowserHistory());
        actions.addView(open, Ui.weight(1)); Ui.margin(open, 0, 0, 7, 0, this); actions.addView(history, Ui.weight(1)); card.addView(actions);
        content.addView(card, new LinearLayout.LayoutParams(-1, -2)); Ui.margin(card, 0, 0, 0, 12, this);
    }

    private void addPluginsCard() {
        LinearLayout card=Ui.card(this);card.addView(sectionTitle("浏览器扩展", "插"));
        TextView intro=Ui.text(this,"这些功能使用 Android WebView 原生接口实现，普通浏览器和悬浮浏览器共享同一套开关。",11,Ui.MUTED,false);intro.setPadding(0,Ui.dp(this,8),0,Ui.dp(this,8));card.addView(intro);
        addPluginRow(card,"广告拦截","阻止常见广告请求并清理页面广告容器",data.pluginAdBlock(),data::pluginAdBlock);
        addPluginRow(card,"网页翻译","将当前公开网页交给翻译服务生成中文页面",data.pluginTranslate(),data::pluginTranslate);
        addPluginRow(card,"页内查找","高亮关键词并在匹配结果间前后切换",data.pluginFind(),data::pluginFind);
        addPluginRow(card,"媒体提取","列出 DOM 中公开的音视频地址，不处理 DRM",data.pluginMediaExtractor(),data::pluginMediaExtractor);
        addPluginRow(card,"源码查看","读取当前渲染页面的 HTML，最多 500 KB",data.pluginSourceViewer(),data::pluginSourceViewer);
        addPluginRow(card,"视频工具","检测到视频后显示倍速与网页自带字幕",data.pluginVideoTools(),data::pluginVideoTools);
        addPluginRow(card,"自由复制","恢复网页文字选择与复制，不绕过付费内容",data.pluginSuperCopy(),data::pluginSuperCopy);
        addPluginRow(card,"视觉调节","调整网页亮度、对比度、饱和度与灰度",data.pluginVisualAdjust(),data::pluginVisualAdjust);
        addPluginRow(card,"密码助手","仅在本机生成强密码，不记录账号或剪贴板",data.pluginPasswordHelper(),data::pluginPasswordHelper);
        addPluginRow(card,"下载直链","检测到公开文件链接时显示提取入口",data.pluginDownloadLinks(),data::pluginDownloadLinks);
        addPluginRow(card,"开发者信息","显示 DOM、资源、视口和页面性能摘要",data.pluginDeveloper(),data::pluginDeveloper);
        addPluginRow(card,"剪贴板防护","网页写入剪贴板前显示来源、预览并请求一次性同意",data.pluginClipboardGuard(),data::pluginClipboardGuard);
        TextView privacy=Ui.text(this,"翻译会把当前页面地址发送给 Google 翻译服务。视频字幕只启用网页已经提供的字幕轨；下载与媒体工具不会绕过登录、网盘限制、付费墙、DRM 或版权保护。密码助手不会自动读取、保存或粘贴密码。",10,Ui.MUTED,false);privacy.setPadding(0,Ui.dp(this,10),0,0);card.addView(privacy);
        content.addView(card,new LinearLayout.LayoutParams(-1,-2));Ui.margin(card,0,5,0,12,this);
    }

    private interface BoolChange { void set(boolean value); }
    private void addPluginRow(LinearLayout card,String title,String detail,boolean enabled,BoolChange listener){LinearLayout row=Ui.horizontal(this);row.setPadding(0,Ui.dp(this,7),0,Ui.dp(this,7));LinearLayout copy=Ui.vertical(this);copy.addView(Ui.text(this,title,13,Ui.TEXT,true));copy.addView(Ui.text(this,detail,10,Ui.MUTED,false));row.addView(copy,Ui.weight(1));Switch toggle=new Switch(this);toggle.setChecked(enabled);toggle.setOnCheckedChangeListener((v,c)->{listener.set(c);notifyAppearance();});row.addView(toggle);card.addView(row,new LinearLayout.LayoutParams(-1,-2));}

    private void addBrowserSettingsCard(){
        LinearLayout card=Ui.card(this);card.addView(sectionTitle("浏览器设置", "设"));
        TextView help=Ui.text(this,"简盒内部的地址栏搜索使用下方引擎。Android 不允许应用直接修改其他浏览器的搜索引擎。",10,Ui.MUTED,false);help.setPadding(0,Ui.dp(this,8),0,Ui.dp(this,8));card.addView(help);
        addSearchEngineSelector(card);
        addSettingSwitch(card,"广告过滤总开关","与插件页的广告拦截模块同时开启时生效",data.blockAds(),data::blockAds);
        addSettingSwitch(card,"记录浏览历史","历史仅保存在本机，可随时查询或清空",data.saveBrowserHistory(),data::saveBrowserHistory);
        addSettingSwitch(card,"站点安全提示","结合本地规则、SSL 与 WebView Safe Browsing",data.safetyEnabled(),data::safetyEnabled);
        addSettingSwitch(card,"清理跟踪参数","打开链接前移除常见 utm、fbclid 等参数",data.cleanTrackers(),data::cleanTrackers);
        LinearLayout actions=Ui.horizontal(this);Button defaults=Ui.button(this,"系统默认应用",true);Button history=Ui.button(this,"管理历史",false);defaults.setOnClickListener(v->{try{startActivity(new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS));}catch(Exception e){startActivity(new Intent(Settings.ACTION_SETTINGS));}});history.setOnClickListener(v->showBrowserHistory());actions.addView(defaults,Ui.weight(1));Ui.margin(defaults,0,0,7,0,this);actions.addView(history,Ui.weight(1));card.addView(actions);
        TextView note=Ui.text(this,"在系统默认应用中把简盒选为浏览器后，网页链接和系统网页搜索可交给 Jian Edge；简盒无法静默替用户更改系统默认项。",10,Ui.MUTED,false);note.setPadding(0,Ui.dp(this,8),0,0);card.addView(note);
        content.addView(card,new LinearLayout.LayoutParams(-1,-2));Ui.margin(card,0,5,0,12,this);
    }

    private void addSettingSwitch(LinearLayout card,String title,String detail,boolean enabled,BoolChange listener){LinearLayout row=Ui.horizontal(this);row.setPadding(0,Ui.dp(this,7),0,Ui.dp(this,7));LinearLayout copy=Ui.vertical(this);copy.addView(Ui.text(this,title,13,Ui.TEXT,true));copy.addView(Ui.text(this,detail,10,Ui.MUTED,false));row.addView(copy,Ui.weight(1));Switch toggle=new Switch(this);toggle.setChecked(enabled);toggle.setOnCheckedChangeListener((v,c)->listener.set(c));row.addView(toggle);card.addView(row);}

    private void addAboutCard(){
        LinearLayout intro=Ui.card(this);intro.addView(sectionTitle("关于简盒", "简"));TextView copy=Ui.text(this,"简盒是一款以 Jian Edge 内置浏览器为核心的 Android 边缘工具箱，整合悬浮浏览、多标签、快捷入口、备忘、提醒和性能监控。\n\n浏览器基于系统 Android WebView 内核并使用 Edge 移动端兼容标识，不捆绑 Chromium 或 Microsoft Edge 安装包。",11,Ui.MUTED,false);copy.setPadding(0,Ui.dp(this,9),0,Ui.dp(this,9));intro.addView(copy);Button account=Ui.button(this,data.accountToken().isEmpty()?"可选账号：登录或注册":"账号已登录",false);account.setOnClickListener(v->startActivity(new Intent(this,AccountActivity.class)));intro.addView(account,new LinearLayout.LayoutParams(-1,Ui.dp(this,42)));content.addView(intro,new LinearLayout.LayoutParams(-1,-2));Ui.margin(intro,0,5,0,12,this);
        LinearLayout author=Ui.card(this);author.addView(sectionTitle("开发者与合作","✦"));TextView authorCopy=Ui.text(this,"开发者：芙桃\n\n承接 Android 软件、浏览器、网页、后台服务与功能定制开发，也可提供界面美化、性能优化和现有项目改造。欢迎描述你的需求，先沟通方案再评估周期。\n\nQQ邮箱：tyy_5201314@foxmail.com\nQQ：3776821683",11,Ui.TEXT,false);authorCopy.setPadding(0,Ui.dp(this,9),0,Ui.dp(this,10));author.addView(authorCopy);Button mail=Ui.button(this,"发送邮件咨询",true);mail.setOnClickListener(v->{try{startActivity(new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:tyy_5201314@foxmail.com")));}catch(Exception ignored){Toast.makeText(this,"设备未配置可用的邮件应用",Toast.LENGTH_SHORT).show();}});author.addView(mail,new LinearLayout.LayoutParams(-1,Ui.dp(this,40)));Button qq=Ui.button(this,"打开 QQ 咨询",false);qq.setOnClickListener(v->{try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("mqqwpa://im/chat?chat_type=wpa&uin=3776821683")));}catch(Exception ignored){Toast.makeText(this,"未找到可用的 QQ 应用，请搜索 QQ：3776821683",Toast.LENGTH_SHORT).show();}});author.addView(qq,new LinearLayout.LayoutParams(-1,Ui.dp(this,40)));Ui.margin(qq,Ui.dp(this,6),0,0,0,this);Button update=Ui.button(this,"检查更新",false);update.setOnClickListener(v->UpdateGuard.checkNow(this));author.addView(update,new LinearLayout.LayoutParams(-1,Ui.dp(this,40)));Ui.margin(update,Ui.dp(this,6),0,0,0,this);content.addView(author,new LinearLayout.LayoutParams(-1,-2));Ui.margin(author,0,0,0,12,this);
        LinearLayout projects=Ui.card(this);projects.addView(sectionTitle("开源参考与致谢", "源"));addProject(projects,"Cromite","隐私、拦截与内容优先设计参考 · GPL-3.0","https://github.com/uazo/cromite");addProject(projects,"Fulguris","WebView 窗口、书签与界面设计参考 · MPL-2.0","https://github.com/Slion/Fulguris");addProject(projects,"uBlock Origin","广告过滤设计参考 · GPL-3.0","https://github.com/gorhill/uBlock");addProject(projects,"Immersive Translate","翻译交互设计参考 · AGPL-3.0","https://github.com/immersive-translate/immersive-translate");addProject(projects,"URLCheck","访问前安全核对思路 · GPL-3.0","https://github.com/TrianguloY/URLCheck");addProject(projects,"SingleFile","页面内容提取思路 · AGPL-3.0","https://github.com/gildas-lormeau/SingleFile");TextView license=Ui.text(this,"简盒没有直接复制这些浏览器扩展的源代码。WebView 不支持 chrome.* / browser.* 扩展 API，因此相关能力采用独立的原生兼容实现；项目名称和链接用于致谢与许可证说明。\n\n© 2026 芙桃。简盒自有代码、品牌和界面内容保留相应权利。",10,Ui.MUTED,false);license.setPadding(0,Ui.dp(this,8),0,0);projects.addView(license);content.addView(projects,new LinearLayout.LayoutParams(-1,-2));
    }

    private String appBackgroundPath(){return data.appBackgroundPath();}

    private void addUpdateServiceCard(){
        LinearLayout update=Ui.card(this);
        update.addView(sectionTitle("版本服务","↻"));
        addProfileEntry(update,"↻","检查更新","连接简盒更新服务，查看可用的新版本",v->UpdateGuard.checkNow(this));
        content.addView(update,new LinearLayout.LayoutParams(-1,-2));
        Ui.margin(update,0,0,0,10,this);
    }

    private void removeLegacyAboutUpdateAction(){
        if(currentPage!=7||content==null||content.getChildCount()<2)return;
        View candidate=content.getChildAt(1);
        if(!(candidate instanceof ViewGroup))return;
        ViewGroup author=(ViewGroup)candidate;
        int last=author.getChildCount()-1;
        if(last>=0&&author.getChildAt(last) instanceof Button)author.removeViewAt(last);
    }
    private void addMyPage(){
        LinearLayout card=Ui.card(this);card.addView(sectionTitle("个人中心","我"));
        LinearLayout row=Ui.horizontal(this);android.graphics.Bitmap avatar=ImageStore.load(data.profileAvatarPath());
        if(avatar!=null){ImageView image=new ImageView(this);image.setImageBitmap(avatar);image.setScaleType(ImageView.ScaleType.CENTER_CROP);image.setClipToOutline(true);image.setBackground(Ui.bg(Ui.GREEN_SOFT,22,this));row.addView(image,Ui.lp(Ui.dp(this,64),Ui.dp(this,64)));}else row.addView(Ui.icon(this,"我",Color.WHITE),Ui.lp(Ui.dp(this,64),Ui.dp(this,64)));
        LinearLayout copy=Ui.vertical(this);copy.setPadding(Ui.dp(this,12),0,0,0);String name=data.profileName().isEmpty()?(data.accountEmail().isEmpty()?"简盒用户":data.accountEmail().split("@")[0]):data.profileName();copy.addView(Ui.text(this,name,17,Ui.TEXT,true));copy.addView(Ui.text(this,data.profileBio().isEmpty()?"写一句介绍，让简盒更像你。":data.profileBio(),10,Ui.MUTED,false));TextView accountState=Ui.text(this,data.accountToken().isEmpty()?"未登录 · 本机模式":"已验证 · "+data.accountEmail(),9,data.accountToken().isEmpty()?Ui.MUTED:Ui.GREEN,true);copy.addView(accountState);row.addView(copy,Ui.weight(1));card.addView(row);
        LinearLayout stats=Ui.horizontal(this);stats.setPadding(0,Ui.dp(this,12),0,Ui.dp(this,8));stats.addView(profileStat("◉",String.valueOf(data.coinBalance()),"简盒硬币"),new LinearLayout.LayoutParams(0,Ui.dp(this,68),1));stats.addView(profileStat("✓",String.valueOf(data.checkinCount()),"累计签到"),new LinearLayout.LayoutParams(0,Ui.dp(this,68),1));stats.addView(profileStat("☁",data.accountToken().isEmpty()?"本机":"在线","账号状态"),new LinearLayout.LayoutParams(0,Ui.dp(this,68),1));card.addView(stats);
        TextView coinHelp=Ui.text(this,"每日签到由服务器随机发放 1–10 枚简盒硬币，每个账号每天仅可领取一次。硬币用途将在后续版本开放，当前仅记录余额。",9,Ui.MUTED,false);coinHelp.setPadding(Ui.dp(this,8),Ui.dp(this,8),Ui.dp(this,8),Ui.dp(this,8));coinHelp.setBackground(Ui.bordered(Ui.SOFT_SURFACE,Ui.BORDER,9,this));card.addView(coinHelp);
        String today=new java.text.SimpleDateFormat("yyyy-MM-dd",Locale.ROOT).format(new java.util.Date());boolean done=today.equals(data.checkinDate());Button check=Ui.button(this,data.accountToken().isEmpty()?"登录后签到领取硬币":done?"今日已签到 · 明天再来":"立即签到 · 随机领取 1–10 枚硬币",!done&&!data.accountToken().isEmpty());check.setOnClickListener(v->performDailyCheckin(check));card.addView(check,new LinearLayout.LayoutParams(-1,Ui.dp(this,42)));Ui.margin(check,0,10,0,12,this);
        LinearLayout profileActions=Ui.horizontal(this);Button edit=Ui.button(this,"编辑资料",false),account=Ui.button(this,data.accountToken().isEmpty()?"登录 / 注册":"账号安全",false);edit.setOnClickListener(v->editProfile());account.setOnClickListener(v->startActivity(new Intent(this,AccountActivity.class)));profileActions.addView(edit,new LinearLayout.LayoutParams(0,Ui.dp(this,40),1));Ui.margin(edit,0,0,7,0,this);profileActions.addView(account,new LinearLayout.LayoutParams(0,Ui.dp(this,40),1));card.addView(profileActions);content.addView(card,new LinearLayout.LayoutParams(-1,-2));

        LinearLayout services=Ui.card(this);services.addView(sectionTitle("常用服务","服"));addProfileEntry(services,"↓","下载管理","应用内下载、断点续传和文件预览",v->startActivity(new Intent(this,DownloadActivity.class)));addProfileEntry(services,"☆","浏览收藏与历史","管理收藏分组与本机浏览记录",v->openMySubpage("browser_data"));addProfileEntry(services,"◈","个性化","主题、软件壁纸、头像与悬浮侧栏",v->openMySubpage("appearance"));addProfileEntry(services,"盾","隐私与安全","历史策略、站点安全和权限说明",v->openMySubpage("privacy"));content.addView(services,new LinearLayout.LayoutParams(-1,-2));Ui.margin(services,0,10,0,0,this);
        addUpdateServiceCard();
        if(!data.accountToken().isEmpty())refreshRemoteProfile();
    }
    private View profileStat(String icon,String value,String label){LinearLayout box=Ui.vertical(this);box.setGravity(Gravity.CENTER);box.setBackground(Ui.bg(Ui.SOFT_SURFACE,10,this));box.addView(Ui.text(this,icon+"  "+value,14,Ui.TEXT,true),new LinearLayout.LayoutParams(-2,Ui.dp(this,30)));box.addView(Ui.text(this,label,9,Ui.MUTED,false),new LinearLayout.LayoutParams(-2,Ui.dp(this,20)));Ui.margin(box,3,0,3,0,this);return box;}
    private void openMySubpage(String page){mySubpage=page;mainScroll.scrollTo(0,0);render();}
    private void addProfileEntry(LinearLayout card,String icon,String title,String detail,View.OnClickListener action){LinearLayout row=Ui.horizontal(this);row.setPadding(Ui.dp(this,5),Ui.dp(this,7),Ui.dp(this,3),Ui.dp(this,7));TextView glyph=Ui.icon(this,icon,Ui.GREEN);row.addView(glyph,Ui.lp(Ui.dp(this,38),Ui.dp(this,38)));LinearLayout text=Ui.vertical(this);text.setPadding(Ui.dp(this,10),0,0,0);text.addView(Ui.text(this,title,12,Ui.TEXT,true));text.addView(Ui.text(this,detail,9,Ui.MUTED,false));row.addView(text,Ui.weight(1));row.addView(Ui.text(this,"›",20,Ui.MUTED,false),Ui.lp(Ui.dp(this,24),Ui.dp(this,38)));row.setOnClickListener(action);card.addView(row,new LinearLayout.LayoutParams(-1,Ui.dp(this,54)));}
    private void refreshRemoteProfile(){long now=System.currentTimeMillis();if(profileRefreshInFlight||now-lastProfileRefreshAt<30000)return;profileRefreshInFlight=true;String path="/rest/v1/profiles?id=eq."+data.accountUserId()+"&select=display_name,coins,checkin_count,last_checkin_date";BackendClient.requestWithSession(path,"GET",null,data,(ok,body,error)->{profileRefreshInFlight=false;lastProfileRefreshAt=System.currentTimeMillis();if(!ok)return;org.json.JSONObject row=BackendClient.firstRow(body);if(row==null)return;data.coinBalance(row.optInt("coins",data.coinBalance()));data.checkinCount(row.optInt("checkin_count",data.checkinCount()));data.checkinDate(row.optString("last_checkin_date",data.checkinDate()));String remote=row.optString("display_name","");if(!remote.isEmpty()&&data.profileName().isEmpty())data.profileName(remote);if(currentPage==6&&mySubpage.isEmpty())render();});}
    private void performDailyCheckin(Button button){if(data.accountToken().isEmpty()){startActivity(new Intent(this,AccountActivity.class));return;}button.setEnabled(false);button.setText("正在安全签到…");BackendClient.requestWithSession("/rest/v1/rpc/jianbox_daily_checkin","POST",new org.json.JSONObject(),data,(ok,body,error)->{if(!ok){button.setEnabled(true);button.setText("签到失败，点击重试");Toast.makeText(this,error,Toast.LENGTH_LONG).show();return;}org.json.JSONObject row=BackendClient.firstRow(body);if(row==null){button.setEnabled(true);button.setText("签到失败，点击重试");Toast.makeText(this,"签到服务未返回结果，请稍后重试",Toast.LENGTH_LONG).show();return;}int reward=row.optInt("reward",0);data.coinBalance(row.optInt("balance",data.coinBalance()));data.checkinCount(row.optInt("total_checkins",data.checkinCount()));data.checkinDate(row.optString("checkin_date",data.checkinDate()));boolean claimed=row.optBoolean("claimed",false);new AlertDialog.Builder(this).setTitle(claimed?"签到成功":"今天已经签到").setMessage(claimed?"本次随机获得 "+reward+" 枚简盒硬币。\n当前余额："+data.coinBalance()+" 枚":"今日奖励已领取。\n当前余额："+data.coinBalance()+" 枚").setPositiveButton("知道了",(d,w)->render()).show();});}
    private void editProfile(){
        if(data.accountToken().isEmpty()){Toast.makeText(this,"请先登录账号后再修改头像、昵称和简介",Toast.LENGTH_LONG).show();startActivity(new Intent(this,AccountActivity.class));return;}
        android.app.Dialog dialog=new android.app.Dialog(this);dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout card=Ui.vertical(this);card.setPadding(Ui.dp(this,18),Ui.dp(this,16),Ui.dp(this,18),Ui.dp(this,16));card.setBackground(Ui.bordered(Ui.SURFACE,Ui.BORDER,18,this));
        LinearLayout head=Ui.horizontal(this);android.graphics.Bitmap avatar=ImageStore.load(data.profileAvatarPath());
        if(avatar!=null){ImageView image=new ImageView(this);image.setImageBitmap(avatar);image.setScaleType(ImageView.ScaleType.CENTER_CROP);image.setClipToOutline(true);image.setBackground(Ui.bg(Ui.GREEN_SOFT,14,this));head.addView(image,Ui.lp(Ui.dp(this,48),Ui.dp(this,48)));}else head.addView(Ui.icon(this,"我",Color.WHITE),Ui.lp(Ui.dp(this,48),Ui.dp(this,48)));
        LinearLayout heading=Ui.vertical(this);heading.setPadding(Ui.dp(this,11),0,0,0);heading.addView(Ui.text(this,"编辑个人资料",17,Ui.TEXT,true));heading.addView(Ui.text(this,"头像、昵称与个人简介",10,Ui.MUTED,false));head.addView(heading,new LinearLayout.LayoutParams(0,Ui.dp(this,48),1));card.addView(head);
        TextView nameLabel=Ui.text(this,"昵称",10,Ui.MUTED,true);nameLabel.setPadding(Ui.dp(this,2),Ui.dp(this,14),0,Ui.dp(this,5));card.addView(nameLabel);
        EditText name=field("输入昵称");name.setText(data.profileName());name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(30)});card.addView(name,new LinearLayout.LayoutParams(-1,Ui.dp(this,44)));
        TextView bioLabel=Ui.text(this,"个人简介",10,Ui.MUTED,true);bioLabel.setPadding(Ui.dp(this,2),Ui.dp(this,11),0,Ui.dp(this,5));card.addView(bioLabel);
        EditText bio=field("写一句介绍，让简盒更像你");bio.setSingleLine(false);bio.setGravity(Gravity.TOP|Gravity.START);bio.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);bio.setPadding(Ui.dp(this,12),Ui.dp(this,10),Ui.dp(this,12),Ui.dp(this,8));bio.setFilters(new InputFilter[]{new InputFilter.LengthFilter(120)});bio.setText(data.profileBio());card.addView(bio,new LinearLayout.LayoutParams(-1,Ui.dp(this,76)));
        Button avatarButton=Ui.button(this,"更换头像",false);avatarButton.setOnClickListener(v->{dialog.dismiss();chooseImage(REQUEST_PROFILE_AVATAR);});card.addView(avatarButton,new LinearLayout.LayoutParams(-1,Ui.dp(this,40)));Ui.margin(avatarButton,0,12,0,0,this);
        LinearLayout actions=Ui.horizontal(this);Button cancel=Ui.button(this,"取消",false),save=Ui.button(this,"保存资料",true);cancel.setOnClickListener(v->dialog.dismiss());save.setOnClickListener(v->{String displayName=name.getText().toString().trim(),description=bio.getText().toString().trim();if(displayName.isEmpty()){name.setError("请输入昵称");return;}data.profileName(displayName);data.profileBio(description);if(!data.accountToken().isEmpty()){try{BackendClient.requestWithSession("/rest/v1/profiles?id=eq."+data.accountUserId(),"PATCH",new org.json.JSONObject().put("display_name",displayName),data,(ok,body,error)->{if(!ok)Toast.makeText(this,"资料已保存在本机，云端同步失败："+error,Toast.LENGTH_LONG).show();});}catch(Exception ignored){}}dialog.dismiss();render();Toast.makeText(this,"资料已保存",Toast.LENGTH_SHORT).show();});actions.addView(cancel,new LinearLayout.LayoutParams(0,Ui.dp(this,42),1));Ui.margin(cancel,0,0,7,0,this);actions.addView(save,new LinearLayout.LayoutParams(0,Ui.dp(this,42),1));card.addView(actions);Ui.margin(actions,0,8,0,0,this);
        dialog.setContentView(card);android.view.Window window=dialog.getWindow();if(window!=null){window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);android.view.WindowManager.LayoutParams lp=window.getAttributes();lp.dimAmount=.55f;window.setAttributes(lp);}dialog.show();if(dialog.getWindow()!=null)dialog.getWindow().setLayout(Math.min(Ui.dp(this,410),getResources().getDisplayMetrics().widthPixels-Ui.dp(this,28)),android.view.WindowManager.LayoutParams.WRAP_CONTENT);
    }

    private void addProject(LinearLayout card,String name,String detail,String url){LinearLayout row=Ui.horizontal(this);row.setPadding(0,Ui.dp(this,8),0,Ui.dp(this,8));LinearLayout copy=Ui.vertical(this);copy.addView(Ui.text(this,name,12,Ui.TEXT,true));copy.addView(Ui.text(this,detail,9,Ui.MUTED,false));row.addView(copy,Ui.weight(1));TextView open=Ui.text(this,"↗",18,Ui.GREEN,true);open.setGravity(Gravity.CENTER);row.addView(open,Ui.lp(Ui.dp(this,38),Ui.dp(this,38)));row.setOnClickListener(v->BrowserActivity.open(this,url));card.addView(row);}

    private void addSearchEngineSelector(LinearLayout card){TextView label=Ui.text(this,"默认搜索引擎",12,Ui.MUTED,true);label.setPadding(0,Ui.dp(this,4),0,Ui.dp(this,6));card.addView(label);HorizontalScrollView scroller=new HorizontalScrollView(this);scroller.setHorizontalScrollBarEnabled(false);LinearLayout rail=Ui.horizontal(this);String selected=SearchEngine.safeId(data.searchEngine());for(String id:SearchEngine.IDS){LinearLayout chip=Ui.horizontal(this);chip.setPadding(Ui.dp(this,8),0,Ui.dp(this,10),0);chip.setBackground(selected.equals(id)?Ui.bordered(0xFFF2F7F3,SearchEngine.color(id),12,this):Ui.bordered(Color.WHITE,0x335F6A63,12,this));TextView icon=Ui.text(this,SearchEngine.glyph(id),11,Color.WHITE,true);icon.setGravity(Gravity.CENTER);icon.setBackground(Ui.bg(SearchEngine.color(id),9,this));chip.addView(icon,Ui.lp(Ui.dp(this,28),Ui.dp(this,28)));TextView name=Ui.text(this,SearchEngine.label(id),10,Ui.TEXT,selected.equals(id));name.setPadding(Ui.dp(this,7),0,0,0);chip.addView(name);chip.setOnClickListener(v->{data.searchEngine(id);render();});rail.addView(chip,new LinearLayout.LayoutParams(-2,Ui.dp(this,42)));Ui.margin(chip,0,0,7,0,this);}scroller.addView(rail,new HorizontalScrollView.LayoutParams(-2,-1));card.addView(scroller,new LinearLayout.LayoutParams(-1,Ui.dp(this,48)));}

    @Override public void onBackPressed(){if(currentPage==6&&!mySubpage.isEmpty()){mySubpage="";mainScroll.scrollTo(0,0);render();}else finish();}

    private interface ValueChange { void onValue(int value); }

    private View sliderRow(String title, int min, int max, int value, ValueChange listener) {
        LinearLayout box = Ui.vertical(this);
        box.setPadding(0, Ui.dp(this, 12), 0, 0);
        LinearLayout line = Ui.horizontal(this);
        line.addView(Ui.text(this, title, 12, Ui.MUTED, false), Ui.weight(1));
        TextView number = Ui.text(this, String.valueOf(value), 12, Ui.GREEN, true);
        line.addView(number);
        box.addView(line);
        SeekBar bar = new SeekBar(this);
        bar.setMax(max - min);
        bar.setProgress(value - min);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar b, int progress, boolean user) {
                int v = min + progress; number.setText(String.valueOf(v)); listener.onValue(v);
            }
            public void onStartTrackingTouch(SeekBar b) {}
            public void onStopTrackingTouch(SeekBar b) {}
        });
        box.addView(bar, new LinearLayout.LayoutParams(-1, Ui.dp(this, 34)));
        return box;
    }

    private void addShortcutsCard() {
        LinearLayout card = Ui.card(this);
        card.addView(sectionTitle("快捷入口", "快"));
        List<JianData.QuickItem> items = data.quickItems();
        Set<String> groups = new HashSet<>();
        for (JianData.QuickItem q : items) groups.add(q.group);
        TextView stat = Ui.text(this, items.size() + " 个入口 · " + groups.size() + " 个分组", 12, Ui.MUTED, false);
        stat.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));
        card.addView(stat);
        LinearLayout actions = Ui.horizontal(this);
        Button web = Ui.button(this, "+ 网页", false);
        Button app = Ui.button(this, "选择 App", false);
        Button manage = Ui.button(this, "管理排序", true);
        web.setOnClickListener(v -> addWebDialog());
        app.setOnClickListener(v -> startActivity(new Intent(this, AppPickerActivity.class)));
        manage.setOnClickListener(v -> manageQuickDialog());
        actions.addView(web, Ui.weight(1)); Ui.margin(web, 0, 0, 7, 0, this);
        actions.addView(app, Ui.weight(1)); Ui.margin(app, 0, 0, 7, 0, this);
        actions.addView(manage, Ui.weight(1.2f));
        card.addView(actions);
        content.addView(card, new LinearLayout.LayoutParams(-1, -2));
        Ui.margin(card, 0, 0, 0, 12, this);
    }

    private void addReminderCard() {
        LinearLayout card = Ui.card(this);
        card.addView(sectionTitle("定时提醒", "铃"));
        List<JianData.Reminder> active = new ArrayList<>();
        for (JianData.Reminder r : data.reminders()) if (!r.done) active.add(r);
        TextView stat = Ui.text(this, active.isEmpty() ? "还没有待办提醒" : active.size() + " 个提醒待处理", 12, Ui.MUTED, false);
        stat.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));
        card.addView(stat);
        int count = 0;
        for (JianData.Reminder r : active) {
            if (count++ == 2) break;
            TextView item = Ui.text(this, "•  " + r.title + "   " + DATE.format(r.time), 12, Ui.TEXT, false);
            item.setPadding(0, 0, 0, Ui.dp(this, 7));
            card.addView(item);
        }
        Button add = Ui.button(this, "+ 新建提醒", true);
        add.setOnClickListener(v -> reminderDialog(null));
        card.addView(add, new LinearLayout.LayoutParams(-1, -2));
        content.addView(card, new LinearLayout.LayoutParams(-1, -2));
        Ui.margin(card, 0, 0, 0, 12, this);
    }

    private void addPrivacyCard() {
        LinearLayout card = Ui.card(this);
        card.setBackground(Ui.bordered(Ui.GREEN_SOFT, Ui.BORDER, 16, this));
        card.addView(Ui.text(this, "隐私说明", 13, Ui.TEXT, true));
        TextView text = Ui.text(this, "剪贴板、备忘和完整浏览历史仅保存在本机。站点检查参考 URLCheck 的访问前核对思路，采用本地启发式规则、WebView Safe Browsing 与 SSL 强制拦截，不会为任意站点武断标注官网，也不构成绝对安全保证。在线转换仅在用户主动选择服务和文件后上传。", 11, Ui.MUTED, false);
        text.setPadding(0, Ui.dp(this, 6), 0, 0);
        card.addView(text);
        content.addView(card, new LinearLayout.LayoutParams(-1, -2));
    }

    private void showBrowserHistory() {
        List<JianData.BrowserHistory> history = data.history(); LinearLayout rows = Ui.vertical(this); rows.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 10), Ui.dp(this, 8));
        ScrollView scroll = new ScrollView(this); scroll.addView(rows, new ScrollView.LayoutParams(-1,-2));
        final AlertDialog[] dialog = new AlertDialog[1];
        Runnable renderRows = new Runnable() { public void run() {
            rows.removeAllViews();
            for (JianData.BrowserHistory item : data.history()) {
                LinearLayout row = Ui.horizontal(MainActivity.this); LinearLayout copy = Ui.vertical(MainActivity.this); copy.addView(Ui.text(MainActivity.this,item.title,12,Ui.TEXT,true)); TextView url=Ui.text(MainActivity.this,item.url,9,Ui.MUTED,false); url.setSingleLine(true); copy.addView(url); row.addView(copy,Ui.weight(1));
                TextView del=Ui.text(MainActivity.this,"×",17,Ui.RED,true);del.setGravity(Gravity.CENTER);del.setBackground(Ui.bg(0xFFFFF1F1,10,MainActivity.this));row.addView(del,Ui.lp(Ui.dp(MainActivity.this,36),Ui.dp(MainActivity.this,36)));rows.addView(row,new LinearLayout.LayoutParams(-1,Ui.dp(MainActivity.this,58)));
                row.setOnClickListener(v->{BrowserActivity.open(MainActivity.this,item.url);if(dialog[0]!=null)dialog[0].dismiss();});
                del.setOnClickListener(v->new AlertDialog.Builder(MainActivity.this).setTitle("删除这条历史记录？").setMessage(item.title).setNegativeButton("取消",null).setPositiveButton("删除",(d,w)->{List<JianData.BrowserHistory> all=data.history();all.removeIf(h->h.id.equals(item.id));data.saveHistory(all);run();}).show());
            }
            if(rows.getChildCount()==0){TextView empty=Ui.text(MainActivity.this,"暂无浏览历史",12,Ui.MUTED,false);empty.setGravity(Gravity.CENTER);rows.addView(empty,new LinearLayout.LayoutParams(-1,Ui.dp(MainActivity.this,100)));}
        }};
        renderRows.run(); dialog[0]=new AlertDialog.Builder(this).setTitle("浏览历史").setView(scroll).setNegativeButton("关闭",null).setPositiveButton("清空全部",null).create();
        dialog[0].setOnShowListener(x->dialog[0].getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->new AlertDialog.Builder(this).setTitle("清空全部历史？").setMessage("此操作无法撤销。").setNegativeButton("取消",null).setPositiveButton("清空",(d,w)->{data.clearHistory();renderRows.run();}).show())); dialog[0].show();
    }

    private View sectionTitle(String title, String glyph) {
        LinearLayout row = Ui.horizontal(this);
        TextView dot = Ui.text(this, glyph, 11, Color.WHITE, true);
        dot.setGravity(Gravity.CENTER);
        dot.setBackground(Ui.bg(Ui.GREEN, 7, this));
        row.addView(dot, Ui.lp(Ui.dp(this, 24), Ui.dp(this, 24)));
        TextView label = Ui.text(this, title, 15, Ui.TEXT, true);
        label.setPadding(Ui.dp(this, 9), 0, 0, 0);
        row.addView(label, Ui.weight(1));
        return row;
    }

    private void addWebDialog() {
        LinearLayout form = dialogForm();
        EditText name = field("名称，例如：工作台");
        EditText url = field("https://example.com");
        EditText group = field("分组，例如：常用"); group.setText("常用");
        form.addView(name); form.addView(url); form.addView(group);
        new AlertDialog.Builder(this).setTitle("添加网页快捷方式").setView(form)
                .setNegativeButton("取消", null).setPositiveButton("保存", (d, w) -> {
                    String value = url.getText().toString().trim();
                    if (!value.startsWith("http://") && !value.startsWith("https://")) value = "https://" + value;
                    if (name.getText().toString().trim().isEmpty() || value.length() < 10) {
                        Toast.makeText(this, "请填写名称和有效网址", Toast.LENGTH_SHORT).show(); return;
                    }
                    JianData.QuickItem q = new JianData.QuickItem();
                    q.type = JianData.TYPE_WEB; q.title = name.getText().toString().trim(); q.value = value;
                    q.group = group.getText().toString().trim().isEmpty() ? "常用" : group.getText().toString().trim();
                    q.glyph = q.title.substring(0, 1);
                    List<JianData.QuickItem> list = data.quickItems(); list.add(q); data.saveQuick(list); render(); notifyData();
                }).show();
    }

    private void pickAppDialog() {
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = getPackageManager().queryIntentActivities(query, 0);
        apps.sort(Comparator.comparing(a -> a.loadLabel(getPackageManager()).toString(), java.text.Collator.getInstance(Locale.CHINA)));
        String[] labels = new String[apps.size()];
        for (int i = 0; i < apps.size(); i++) labels[i] = apps.get(i).loadLabel(getPackageManager()).toString();
        new AlertDialog.Builder(this).setTitle("选择 App").setItems(labels, (d, index) -> {
            ResolveInfo r = apps.get(index);
            JianData.QuickItem q = new JianData.QuickItem();
            q.type = JianData.TYPE_APP; q.title = labels[index]; q.value = r.activityInfo.packageName;
            q.group = "应用"; q.glyph = q.title.substring(0, 1);
            List<JianData.QuickItem> list = data.quickItems(); list.add(q); data.saveQuick(list); render(); notifyData();
        }).setNegativeButton("取消", null).show();
    }

    private void manageQuickDialog() {
        List<JianData.QuickItem> list = data.quickItems();
        LinearLayout rows = Ui.vertical(this);
        rows.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 10));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("快捷入口管理")
                .setMessage("用 ↑ ↓ 调整顺序；侧边栏中也可长按拖动、左滑删除。")
                .setView(rows).setNegativeButton("完成", null).create();
        Runnable redraw = new Runnable() { public void run() {
            rows.removeAllViews();
            for (int i = 0; i < list.size(); i++) {
                final int pos = i;
                LinearLayout row = Ui.horizontal(MainActivity.this);
                TextView title = Ui.text(MainActivity.this, list.get(i).glyph + "  " + list.get(i).title + "  · " + list.get(i).group, 13, Ui.TEXT, false);
                row.addView(title, Ui.weight(1));
                for (String action : new String[]{"↑", "↓", "删"}) {
                    Button b = Ui.button(MainActivity.this, action, false);
                    b.setTextColor(action.equals("删") ? Ui.RED : Ui.GREEN);
                    row.addView(b, Ui.lp(Ui.dp(MainActivity.this, 48), Ui.dp(MainActivity.this, 40)));
                    b.setOnClickListener(v -> {
                        if (action.equals("↑") && pos > 0) Collections.swap(list, pos, pos - 1);
                        else if (action.equals("↓") && pos < list.size() - 1) Collections.swap(list, pos, pos + 1);
                        else if (action.equals("删")) list.remove(pos);
                        data.saveQuick(list); notifyData(); run();
                    });
                }
                rows.addView(row, new LinearLayout.LayoutParams(-1, Ui.dp(MainActivity.this, 50)));
            }
        }};
        redraw.run();
        dialog.setOnDismissListener(d -> render());
        dialog.show();
    }

    private void reminderDialog(JianData.Reminder existing) {
        JianData.Reminder reminder = existing == null ? new JianData.Reminder() : existing;
        Calendar selected = Calendar.getInstance();
        selected.setTimeInMillis(Math.max(reminder.time, System.currentTimeMillis() + 60_000));
        LinearLayout form = dialogForm();
        EditText title = field("提醒内容"); if (existing != null) title.setText(reminder.title);
        Button date = Ui.button(this, DATE.format(selected.getTime()), false);
        Spinner repeat = new Spinner(this);
        String[] repeatLabels = {"仅一次", "每小时", "每天"};
        repeat.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, repeatLabels));
        if ("hourly".equals(reminder.repeat)) repeat.setSelection(1); else if ("daily".equals(reminder.repeat)) repeat.setSelection(2);
        Spinner count = new Spinner(this); String[] countLabels = {"提醒 1 次", "提醒 3 次", "提醒 5 次", "不限次数"};
        count.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, countLabels));
        if (reminder.repeatCount == 3) count.setSelection(1); else if (reminder.repeatCount == 5) count.setSelection(2); else if (reminder.repeatCount == 0) count.setSelection(3);
        Spinner mode = new Spinner(this); String[] modeLabels = {"悬浮球 + 通知栏", "仅通知栏", "仅悬浮球"};
        mode.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, modeLabels));
        if ("notification".equals(reminder.mode)) mode.setSelection(1); else if ("bubble".equals(reminder.mode)) mode.setSelection(2);
        CheckBox vibrate = new CheckBox(this); vibrate.setText("震动"); vibrate.setTextColor(Ui.TEXT); vibrate.setChecked(reminder.vibrate);
        CheckBox sound = new CheckBox(this); sound.setText("提示音"); sound.setTextColor(Ui.TEXT); sound.setChecked(reminder.sound);
        LinearLayout modes = Ui.horizontal(this); modes.addView(vibrate, Ui.weight(1)); modes.addView(sound, Ui.weight(1));
        date.setOnClickListener(v -> new DatePickerDialog(this, (dp, year, month, day) -> {
            selected.set(year, month, day);
            new TimePickerDialog(this, (tp, hour, minute) -> {
                selected.set(Calendar.HOUR_OF_DAY, hour); selected.set(Calendar.MINUTE, minute); selected.set(Calendar.SECOND, 0);
                date.setText(DATE.format(selected.getTime()));
            }, selected.get(Calendar.HOUR_OF_DAY), selected.get(Calendar.MINUTE), true).show();
        }, selected.get(Calendar.YEAR), selected.get(Calendar.MONTH), selected.get(Calendar.DAY_OF_MONTH)).show());
        form.addView(title); form.addView(Ui.text(this,"自定义触发日期与时间",10,Ui.MUTED,true)); form.addView(date);
        form.addView(Ui.text(this,"重复频率与次数",10,Ui.MUTED,true)); form.addView(repeat); form.addView(count);
        form.addView(Ui.text(this,"提醒方式",10,Ui.MUTED,true)); form.addView(mode); form.addView(modes);
        new AlertDialog.Builder(this).setTitle(existing == null ? "新建提醒" : "编辑提醒").setView(form)
                .setNegativeButton("取消", null).setPositiveButton("保存", (d, w) -> {
                    String value = title.getText().toString().trim();
                    if (value.isEmpty()) { Toast.makeText(this, "请输入提醒内容", Toast.LENGTH_SHORT).show(); return; }
                    reminder.title = value; reminder.time = selected.getTimeInMillis(); reminder.done = false;
                    reminder.repeat = repeat.getSelectedItemPosition() == 1 ? "hourly" : repeat.getSelectedItemPosition() == 2 ? "daily" : "once";
                    int[] counts = {1,3,5,0}; reminder.repeatCount = "once".equals(reminder.repeat) ? 1 : counts[count.getSelectedItemPosition()]; reminder.firedCount = 0;
                    reminder.mode = mode.getSelectedItemPosition() == 1 ? "notification" : mode.getSelectedItemPosition() == 2 ? "bubble" : "both";
                    reminder.vibrate = vibrate.isChecked(); reminder.sound = sound.isChecked();
                    data.updateReminder(reminder); ReminderReceiver.schedule(this, reminder); render(); notifyData();
                }).show();
    }

    private LinearLayout dialogForm() {
        LinearLayout form = Ui.vertical(this);
        form.setPadding(Ui.dp(this, 22), Ui.dp(this, 4), Ui.dp(this, 22), 0);
        return form;
    }

    private EditText field(String hint) {
        EditText e = new EditText(this); e.setHint(hint); e.setSingleLine(true);
        e.setTextColor(Ui.TEXT); e.setHintTextColor(0x77998899);
        e.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        e.setBackground(Ui.bordered(Color.WHITE, Ui.BORDER, 9, this));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, Ui.dp(this, 48));
        p.setMargins(0, 0, 0, Ui.dp(this, 9)); e.setLayoutParams(p); return e;
    }

    private void chooseImage(int requestCode){if(requestCode==REQUEST_PROFILE_AVATAR&&data.accountToken().isEmpty()){Toast.makeText(this,"请先登录账号后再修改头像",Toast.LENGTH_LONG).show();startActivity(new Intent(this,AccountActivity.class));return;}Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(pick,requestCode);}

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent intent){
        super.onActivityResult(requestCode,resultCode,intent);if(resultCode!=RESULT_OK||intent==null||intent.getData()==null)return;
        try{if(requestCode==REQUEST_BUBBLE_ICON)data.bubbleIconPath(ImageStore.save(this,intent.getData(),"bubble_icon",512,true));else if(requestCode==REQUEST_PANEL_BG)data.panelBackgroundPath(ImageStore.save(this,intent.getData(),"panel_background",1400,false));else if(requestCode==REQUEST_MONITOR_BG)data.monitorBackgroundPath(ImageStore.save(this,intent.getData(),"monitor_background",900,false));else if(requestCode==REQUEST_BROWSER_BG)data.browserBackgroundPath(ImageStore.save(this,intent.getData(),"browser_home",1600,false));else if(requestCode==REQUEST_APP_ICON)data.appIconPath(ImageStore.save(this,intent.getData(),"app_brand_icon",512,true));else if(requestCode==REQUEST_APP_BACKGROUND)data.appBackgroundPath(ImageStore.save(this,intent.getData(),"app_background",1800,false));else if(requestCode==REQUEST_PROFILE_AVATAR)data.profileAvatarPath(ImageStore.save(this,intent.getData(),"profile_avatar",512,true));else return;notifyAppearance();if(Ui.GLASS)buildScreen();else render();Toast.makeText(this,"自定义图片已应用",Toast.LENGTH_SHORT).show();}catch(Exception e){Toast.makeText(this,"无法读取这张图片",Toast.LENGTH_SHORT).show();}
    }

    private void askRuntimePermissions() {
        List<String> missing = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            missing.add(Manifest.permission.CAMERA);
        if (!missing.isEmpty()) requestPermissions(missing.toArray(new String[0]), 30);
    }

    private void ensureOverlayAndStart() {
        if (!Settings.canDrawOverlays(this)) {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
            startActivityForResult(i, 20);
        } else startBubble();
        updatePermissionState();
    }

    private void startBubble() {
        if (UpdateGuard.hasForceGate(this)) { UpdateGuard.openGate(this); return; }
        Intent i = new Intent(this, OverlayService.class).setAction(OverlayService.ACTION_START);
        startForegroundService(i);
    }

    private void stopBubble() { stopService(new Intent(this, OverlayService.class)); }

    private void notifyAppearance() {
        appearanceHandler.removeCallbacks(appearanceUpdate);appearanceHandler.postDelayed(appearanceUpdate,180);
    }

    private void notifyData() {
        if (!data.serviceEnabled() || !Settings.canDrawOverlays(this)) return;
        Intent i = new Intent(this, OverlayService.class).setAction(OverlayService.ACTION_REFRESH);
        try { startService(i); } catch (Exception ignored) {}
    }

    private void updatePermissionState() {
        if (permissionHint == null) return;
        boolean granted = Settings.canDrawOverlays(this);
        permissionHint.setText(granted ? "悬浮窗权限已开启" : "需要授予“显示在其他应用上层”权限");
        permissionHint.setTextColor(granted ? Ui.GREEN : Ui.RED);
        if (!granted && serviceSwitch != null && data.serviceEnabled()) serviceSwitch.setChecked(true);
    }
}
