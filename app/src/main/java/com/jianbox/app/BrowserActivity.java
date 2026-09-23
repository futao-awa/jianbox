package com.jianbox.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Message;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.SafeBrowsingResponse;
import android.webkit.SslErrorHandler;
import android.net.http.SslError;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class BrowserActivity extends Activity {
    public static final String EXTRA_URL = "url";
    private static final String EDGE_UA = "Mozilla/5.0 (Linux; Android 16; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Mobile Safari/537.36 EdgA/138.0.0.0";
    private static final String[] AD_HOST_PARTS = {"doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.", "adsystem.com", "umeng.com", "cnzz.com", "tanx.com", "baidustatic.com/cpro", "adnxs.com"};
    private static final SimpleDateFormat DATE = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);
    private static final int REQUEST_BACKGROUND = 401;

    private final List<TabState> tabs = new ArrayList<>();
    private JianData data;
    private FrameLayout webContainer;
    private FrameLayout browserShell;
    private EditText address;
    private ProgressBar progress;
    private TextView tabCount;
    private TextView safetyChip;
    private TextView bookmarkButton;
    private FrameLayout extensionBarHost;
    private FrameLayout downloadOverlayHost;
    private BrowserExtensions.PageFeatures pageFeatures=new BrowserExtensions.PageFeatures();
    private SiteSafetyEngine.Result lastSafety;
    private LinearLayout safetyActions;
    private final Set<String> trustedHosts = new HashSet<>();
    private int current = -1;

    static void open(Context context, String url) {
        if (UpdateGuard.hasForceGate(context)) { UpdateGuard.openGate(context); return; }
        Intent i = new Intent(context, BrowserActivity.class).putExtra(EXTRA_URL, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(i);
    }

    private static final class TabState {
        String id = UUID.randomUUID().toString();
        String url = "jian://home";
        String title = "新标签页";
        long lastUsed;
        WebView view;
        Bitmap preview;
    }

    @SuppressLint("WebViewApiAvailability")
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        data = new JianData(this);
        if(!data.privacyAccepted()){startActivity(new Intent(this,PrivacyConsentActivity.class));finish();return;}
        Ui.applyTheme(data);
        if (UpdateGuard.enforce(this)) return;
        getWindow().setStatusBarColor(0xFFF5FAF6);
        getWindow().setNavigationBarColor(0xFFF5FAF6);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        buildUi();
        if (Build.VERSION.SDK_INT >= 27) WebView.startSafeBrowsing(this, ok -> {});
        restoreSession();
        String requested = requestedUrl(getIntent());
        if (requested != null && requested.startsWith("jian://") && !"jian://home".equals(requested)) {
            addTab(data.browserHome(), true);
            if (requested.startsWith("jian://history")) webContainer.post(this::showHistory);
            else if (requested.startsWith("jian://bookmarks")) webContainer.post(this::showBookmarks);
            else if (requested.startsWith("jian://background")) webContainer.post(this::chooseBackground);
            else if (requested.startsWith("jian://tools")) startActivity(new Intent(this, ToolboxActivity.class));
            else if (requested.startsWith("jian://plugins")) startActivity(new Intent(this,MainActivity.class).putExtra(MainActivity.EXTRA_PAGE,3));
            else if (requested.startsWith("jian://shortcuts")) startActivity(new Intent(this,MainActivity.class).putExtra(MainActivity.EXTRA_PAGE,1));
            else if (requested.startsWith("jian://navigation")) load("https://go.taolove.top/");
        }
        else if (requested != null && !requested.isEmpty()) addTab(requested, true);
        else if (tabs.isEmpty()) addTab(data.browserHome(), true);
        else selectTab(Math.min(current < 0 ? 0 : current, tabs.size() - 1));
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent);
        if (UpdateGuard.enforce(this)) return;
        String url = requestedUrl(intent);
        if (url != null && !url.isEmpty()&&!openInternal(url)) addTab(url, true);
    }

    private boolean openInternal(String url){
        if(url==null||!url.startsWith("jian://")||"jian://home".equals(url))return false;if(activeTab()==null)addTab(data.browserHome(),true);
        if(url.startsWith("jian://history"))webContainer.post(this::showHistory);else if(url.startsWith("jian://bookmarks"))webContainer.post(this::showBookmarks);else if(url.startsWith("jian://background"))webContainer.post(this::chooseBackground);else if(url.startsWith("jian://tools"))startActivity(new Intent(this,ToolboxActivity.class));else if(url.startsWith("jian://plugins"))startActivity(new Intent(this,MainActivity.class).putExtra(MainActivity.EXTRA_PAGE,3));else if(url.startsWith("jian://shortcuts"))startActivity(new Intent(this,MainActivity.class).putExtra(MainActivity.EXTRA_PAGE,1));else if(url.startsWith("jian://navigation"))load("https://go.taolove.top/");return true;
    }

    private String requestedUrl(Intent intent){
        if(intent==null)return null;
        String extra=intent.getStringExtra(EXTRA_URL);if(extra!=null&&!extra.trim().isEmpty())return extra;
        if(Intent.ACTION_VIEW.equals(intent.getAction())&&intent.getData()!=null)return intent.getDataString();
        if(Intent.ACTION_WEB_SEARCH.equals(intent.getAction()))return intent.getStringExtra(SearchManager.QUERY);
        return null;
    }

    private void buildUi() {
        browserShell=new FrameLayout(this);LinearLayout root = Ui.vertical(this);root.setBackgroundColor(0xFFF5FAF6);browserShell.addView(root,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout toolbar=Ui.horizontal(this);toolbar.setPadding(Ui.dp(this,5),Ui.dp(this,4),Ui.dp(this,5),Ui.dp(this,4));toolbar.setBackgroundColor(Ui.GLASS?0xDFFFFFFF:Color.WHITE);
        TextView back=toolbarButton("‹");back.setOnClickListener(v->{WebView w=activeWeb();if(w!=null&&w.canGoBack())w.goBack();else finish();});toolbar.addView(back,new LinearLayout.LayoutParams(Ui.dp(this,30),Ui.dp(this,36)));
        TextView forward=toolbarButton("›");forward.setOnClickListener(v->{WebView w=activeWeb();if(w!=null&&w.canGoForward())w.goForward();});toolbar.addView(forward,new LinearLayout.LayoutParams(Ui.dp(this,28),Ui.dp(this,36)));
        address = new EditText(this); address.setSingleLine(true); address.setTextSize(11); address.setTextColor(Ui.TEXT); address.setHint(SearchEngine.label(data.searchEngine())+" 搜索或输入网址");
        address.setHintTextColor(0x7799AA99); address.setBackground(Ui.bordered(0xFFF3F8F4, Ui.BORDER, 10, this));
        address.setPadding(Ui.dp(this,10),0,Ui.dp(this,8),0); address.setImeOptions(EditorInfo.IME_ACTION_GO);
        address.setOnEditorActionListener((v, action, event) -> {if (action == EditorInfo.IME_ACTION_GO || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) { load(normalize(address.getText().toString())); return true; }return false;});
        toolbar.addView(address,new LinearLayout.LayoutParams(0,Ui.dp(this,36),1));
        safetyChip=toolbarButton("✓");safetyChip.setSingleLine(true);safetyChip.setTextSize(8);safetyChip.setTextColor(Ui.GREEN);safetyChip.setOnClickListener(v->showSafetyDetail());toolbar.addView(safetyChip,new LinearLayout.LayoutParams(Ui.dp(this,32),Ui.dp(this,34)));
        TextView refresh=toolbarButton("↻");refresh.setOnClickListener(v->{WebView w=activeWeb();if(w!=null)w.reload();});toolbar.addView(refresh,new LinearLayout.LayoutParams(Ui.dp(this,29),Ui.dp(this,36)));
        bookmarkButton=toolbarButton("☆");bookmarkButton.setTextSize(15);bookmarkButton.setOnClickListener(v->bookmarkCurrent());toolbar.addView(bookmarkButton,new LinearLayout.LayoutParams(Ui.dp(this,29),Ui.dp(this,36)));
        tabCount=toolbarButton("1");tabCount.setTextSize(9);tabCount.setBackground(Ui.bordered(0xFFF7FAF8,Ui.BORDER,8,this));tabCount.setOnClickListener(v->showTabSwitcher());toolbar.addView(tabCount,new LinearLayout.LayoutParams(Ui.dp(this,31),Ui.dp(this,31)));
        TextView menu=toolbarButton("⋮");menu.setOnClickListener(v->browserMenu());toolbar.addView(menu,new LinearLayout.LayoutParams(Ui.dp(this,28),Ui.dp(this,36)));
        root.addView(toolbar,new LinearLayout.LayoutParams(-1,Ui.dp(this,44)));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); progress.setMax(100); progress.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.GREEN));
        root.addView(progress, new LinearLayout.LayoutParams(-1, Ui.dp(this, 2)));
        extensionBarHost=new FrameLayout(this);root.addView(extensionBarHost,new LinearLayout.LayoutParams(-1,Ui.dp(this,32)));rebuildExtensionBar();
        webContainer = new FrameLayout(this); webContainer.setBackgroundColor(Color.WHITE);
        root.addView(webContainer, new LinearLayout.LayoutParams(-1, 0, 1));

        downloadOverlayHost = new FrameLayout(this);
        downloadOverlayHost.setClipChildren(false);
        browserShell.addView(downloadOverlayHost, new FrameLayout.LayoutParams(-1, -1));

        setContentView(browserShell);
    }

    private TextView toolbarButton(String value) {
        TextView b = Ui.text(this, value, 16, Ui.TEXT, true); b.setGravity(Gravity.CENTER); b.setBackground(Ui.bg(Color.TRANSPARENT, 8, this)); return b;
    }

    private void rebuildExtensionBar(){
        if(extensionBarHost==null)return;extensionBarHost.removeAllViews();
        extensionBarHost.addView(BrowserExtensions.toolbar(this,data,pageFeatures,new BrowserExtensions.Action(){
            public void translate(){BrowserExtensions.translate(BrowserActivity.this,activeWeb());}
            public void find(){BrowserExtensions.showFind(BrowserActivity.this,activeWeb(),webContainer);}
            public void media(){BrowserExtensions.extractMedia(BrowserActivity.this,activeWeb(),webContainer);}
            public void source(){BrowserExtensions.viewSource(BrowserActivity.this,activeWeb(),webContainer);}
            public void speed(){BrowserExtensions.showVideoSpeed(BrowserActivity.this,activeWeb(),webContainer);}
            public void captions(){BrowserExtensions.enableCaptions(BrowserActivity.this,activeWeb());}
            public void superCopy(){BrowserExtensions.enableSuperCopy(BrowserActivity.this,activeWeb());}
            public void visual(){BrowserExtensions.showVisual(BrowserActivity.this,data,activeWeb(),webContainer);}
            public void password(){BrowserExtensions.showPassword(BrowserActivity.this,webContainer);}
            public void downloads(){BrowserExtensions.extractDownloads(BrowserActivity.this,activeWeb(),webContainer);}
            public void developer(){BrowserExtensions.showDeveloper(BrowserActivity.this,activeWeb(),webContainer);}
            public void manage(){startActivity(new Intent(BrowserActivity.this,MainActivity.class).putExtra(MainActivity.EXTRA_PAGE,3));}
        }),new FrameLayout.LayoutParams(-1,-1));
    }

    private void addTab(String url, boolean select) {
        TabState t = new TabState(); t.url = normalize(url); tabs.add(t);
        if (select) selectTab(tabs.size() - 1); else rebuildTabs();
    }

    private void selectTab(int index) {
        if (index < 0 || index >= tabs.size()) return;
        if (current >= 0 && current < tabs.size() && tabs.get(current).view != null) {
            capturePreview(tabs.get(current));
            tabs.get(current).view.onPause(); tabs.get(current).view.setVisibility(View.GONE);
        }
        current = index; TabState tab = tabs.get(index); tab.lastUsed = System.currentTimeMillis();
        if (tab.view == null) {
            tab.view = createWebView(tab); webContainer.addView(tab.view, new FrameLayout.LayoutParams(-1, -1)); loadTab(tab, tab.url);
        }
        tab.view.setVisibility(View.VISIBLE); tab.view.onResume(); address.setText("jian://home".equals(tab.url) ? "新标签页" : tab.url); address.setSelection(address.length()); updateSafety(tab.url);updateBookmarkButton();
        rebuildTabs(); trimWebViews(); saveSession();
    }

    private void closeTab(int index) {
        if (index < 0 || index >= tabs.size()) return;
        TabState removed = tabs.remove(index);
        if (removed.view != null) { webContainer.removeView(removed.view); removed.view.destroy(); }if(removed.preview!=null&&!removed.preview.isRecycled())removed.preview.recycle();
        if (tabs.isEmpty()) { current = -1; addTab(data.browserHome(), true); return; }
        if (index < current) current--; else if (index == current) current = -1;
        selectTab(Math.min(index, tabs.size() - 1));
    }

    private void rebuildTabs() {
        if(tabCount!=null)tabCount.setText(String.valueOf(tabs.size()));updateBookmarkButton();
    }

    private void capturePreview(TabState tab){
        if(tab==null||tab.view==null||tab.view.getWidth()<=0||tab.view.getHeight()<=0)return;
        try{Bitmap bitmap=Bitmap.createBitmap(320,190,Bitmap.Config.RGB_565);Canvas canvas=new Canvas(bitmap);canvas.drawColor(Color.WHITE);canvas.scale(320f/tab.view.getWidth(),190f/tab.view.getHeight());tab.view.draw(canvas);if(tab.preview!=null&&!tab.preview.isRecycled())tab.preview.recycle();tab.preview=bitmap;}catch(Exception ignored){}
    }

    private void showTabSwitcher(){
        TabState active=activeTab();if(active!=null)capturePreview(active);View old=browserShell.findViewWithTag("tab_switcher");if(old!=null)browserShell.removeView(old);
        FrameLayout overlay=new FrameLayout(this);overlay.setTag("tab_switcher");overlay.setBackgroundColor(0xFFF2F6F3);LinearLayout column=Ui.vertical(this);column.setPadding(Ui.dp(this,12),Ui.dp(this,8),Ui.dp(this,12),Ui.dp(this,10));overlay.addView(column,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout head=Ui.horizontal(this);TextView close=toolbarButton("‹");close.setOnClickListener(v->browserShell.removeView(overlay));head.addView(close,new LinearLayout.LayoutParams(Ui.dp(this,38),Ui.dp(this,42)));LinearLayout title=Ui.vertical(this);title.addView(Ui.text(this,"窗口",19,Ui.TEXT,true));title.addView(Ui.text(this,tabs.size()+" 个页面 · 点击卡片切换",9,Ui.MUTED,false));head.addView(title,Ui.weight(1));TextView clear=Ui.text(this,"全部关闭",10,Ui.RED,true);clear.setGravity(Gravity.CENTER);clear.setOnClickListener(v->{for(TabState tab:new ArrayList<>(tabs)){if(tab.view!=null){webContainer.removeView(tab.view);tab.view.destroy();}if(tab.preview!=null&&!tab.preview.isRecycled())tab.preview.recycle();}tabs.clear();current=-1;browserShell.removeView(overlay);addTab(data.browserHome(),true);});head.addView(clear,new LinearLayout.LayoutParams(Ui.dp(this,70),Ui.dp(this,40)));column.addView(head,new LinearLayout.LayoutParams(-1,Ui.dp(this,48)));
        ScrollView scroll=new ScrollView(this);GridLayout grid=new GridLayout(this);float density=getResources().getDisplayMetrics().density;int screenWidth=getResources().getDisplayMetrics().widthPixels;float screenDp=screenWidth/density;int columns=screenDp>=1000?4:screenDp>=700?3:2;grid.setColumnCount(columns);grid.setPadding(0,Ui.dp(this,5),0,Ui.dp(this,8));scroll.addView(grid,new ScrollView.LayoutParams(-1,-2));column.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));int cardWidth=(screenWidth-Ui.dp(this,24+columns*6))/columns;
        for(int i=0;i<tabs.size();i++){final int index=i;TabState tab=tabs.get(i);LinearLayout card=Ui.vertical(this);card.setPadding(Ui.dp(this,7),Ui.dp(this,7),Ui.dp(this,7),Ui.dp(this,6));card.setBackground(i==current?Ui.bordered(Color.WHITE,Ui.GREEN,13,this):Ui.bordered(Color.WHITE,0x335F7964,13,this));
            FrameLayout previewBox=new FrameLayout(this);previewBox.setBackground(Ui.bg(0xFFE8EFEA,8,this));if(tab.preview!=null&&!tab.preview.isRecycled()){ImageView image=new ImageView(this);image.setScaleType(ImageView.ScaleType.CENTER_CROP);image.setImageBitmap(tab.preview);previewBox.addView(image,new FrameLayout.LayoutParams(-1,-1));}else{TextView placeholder=Ui.text(this,shortTitle(tab.title),12,Ui.MUTED,true);placeholder.setGravity(Gravity.CENTER);previewBox.addView(placeholder,new FrameLayout.LayoutParams(-1,-1));}TextView x=Ui.text(this,"×",15,Color.WHITE,true);x.setGravity(Gravity.CENTER);x.setBackground(Ui.bg(0x99000000,10,this));FrameLayout.LayoutParams xp=new FrameLayout.LayoutParams(Ui.dp(this,25),Ui.dp(this,25),Gravity.TOP|Gravity.END);xp.setMargins(0,Ui.dp(this,3),Ui.dp(this,3),0);previewBox.addView(x,xp);card.addView(previewBox,new LinearLayout.LayoutParams(-1,Ui.dp(this,112)));
             TextView name=Ui.text(this,tab.title,10,Ui.TEXT,true);name.setSingleLine(true);name.setPadding(Ui.dp(this,2),Ui.dp(this,3),0,0);card.addView(name,new LinearLayout.LayoutParams(-1,Ui.dp(this,21)));TextView site=Ui.text(this,"jian://home".equals(tab.url)?"新标签页":host(tab.url),8,Ui.MUTED,false);site.setSingleLine(true);card.addView(site,new LinearLayout.LayoutParams(-1,Ui.dp(this,16)));card.setOnClickListener(v->{browserShell.removeView(overlay);selectTab(index);});x.setOnClickListener(v->{card.animate().alpha(0f).scaleX(.88f).scaleY(.88f).setDuration(150).withEndAction(()->{browserShell.removeView(overlay);closeTab(index);showTabSwitcher();}).start();});GridLayout.LayoutParams p=new GridLayout.LayoutParams();p.width=cardWidth;p.height=Ui.dp(this,162);p.setMargins(Ui.dp(this,3),Ui.dp(this,4),Ui.dp(this,3),Ui.dp(this,4));grid.addView(card,p);
        }
        TextView add=Ui.text(this,"＋  新建窗口",12,Color.WHITE,true);add.setGravity(Gravity.CENTER);add.setBackground(Ui.bg(Ui.GREEN,11,this));add.setOnClickListener(v->{browserShell.removeView(overlay);addTab(data.browserHome(),true);});column.addView(add,new LinearLayout.LayoutParams(-1,Ui.dp(this,42)));browserShell.addView(overlay,new FrameLayout.LayoutParams(-1,-1));
    }

    private void updateBookmarkButton(){if(bookmarkButton==null)return;TabState tab=activeTab();boolean saved=tab!=null&&data.bookmarkFor(tab.url)!=null;bookmarkButton.setText(saved?"★":"☆");bookmarkButton.setTextColor(saved?0xFFFFB300:Ui.TEXT);}

    private void bookmarkCurrent(){TabState tab=activeTab();if(tab==null||tab.url==null||!tab.url.startsWith("http")){Toast.makeText(this,"当前页面不能收藏",Toast.LENGTH_SHORT).show();return;}editBookmark(data.bookmarkFor(tab.url),tab.title,tab.url);}

    private void editBookmark(JianData.Bookmark existing,String defaultTitle,String url){
        LinearLayout form=Ui.vertical(this);form.setPadding(Ui.dp(this,16),0,Ui.dp(this,16),Ui.dp(this,4));EditText title=new EditText(this);title.setSingleLine(true);title.setText(existing==null?defaultTitle:existing.title);title.setHint("收藏名称");title.setTextSize(12);title.setBackground(Ui.bordered(0xFFF7FAF8,Ui.BORDER,10,this));title.setPadding(Ui.dp(this,10),0,Ui.dp(this,10),0);form.addView(title,new LinearLayout.LayoutParams(-1,Ui.dp(this,44)));EditText group=new EditText(this);group.setSingleLine(true);group.setText(existing==null?"常用":existing.group);group.setHint("分组，例如：工作、学习");group.setTextSize(12);group.setBackground(Ui.bordered(0xFFF7FAF8,Ui.BORDER,10,this));group.setPadding(Ui.dp(this,10),0,Ui.dp(this,10),0);form.addView(group,new LinearLayout.LayoutParams(-1,Ui.dp(this,44)));Ui.margin(group,0,7,0,0,this);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(existing==null?"收藏当前网页":"编辑收藏").setView(form).setNegativeButton("取消",null).setNeutralButton(existing==null?"查看收藏":"删除",null).setPositiveButton("保存",null).create();dialog.setOnShowListener(v->{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(x->{String name=title.getText().toString().trim(),category=group.getText().toString().trim();if(name.isEmpty()){title.setError("请输入名称");return;}JianData.Bookmark item=existing==null?new JianData.Bookmark():existing;item.title=name;item.url=url;item.group=category.isEmpty()?"常用":category;item.time=System.currentTimeMillis();data.saveBookmark(item);updateBookmarkButton();dialog.dismiss();Toast.makeText(this,"已保存到「"+item.group+"」",Toast.LENGTH_SHORT).show();});dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(x->{dialog.dismiss();if(existing==null)showBookmarks();else{data.removeBookmark(existing.id);updateBookmarkButton();Toast.makeText(this,"已取消收藏",Toast.LENGTH_SHORT).show();}});});dialog.show();
    }

    private void showBookmarks(){
        LinearLayout root=Ui.vertical(this);root.setPadding(Ui.dp(this,12),0,Ui.dp(this,12),Ui.dp(this,8));EditText search=new EditText(this);search.setSingleLine(true);search.setHint("搜索收藏或分组");search.setTextSize(12);search.setBackground(Ui.bordered(0xFFF3F8F4,Ui.BORDER,10,this));search.setPadding(Ui.dp(this,10),0,Ui.dp(this,10),0);root.addView(search,new LinearLayout.LayoutParams(-1,Ui.dp(this,42)));ScrollView scroll=new ScrollView(this);LinearLayout rows=Ui.vertical(this);scroll.addView(rows,new ScrollView.LayoutParams(-1,-2));root.addView(scroll,new LinearLayout.LayoutParams(-1,Ui.dp(this,440)));final AlertDialog[] holder=new AlertDialog[1];Runnable render=new Runnable(){public void run(){rows.removeAllViews();String query=search.getText().toString().trim().toLowerCase(Locale.CHINA),lastGroup="";for(JianData.Bookmark item:data.bookmarks()){if(!query.isEmpty()&&!(item.title+item.url+item.group).toLowerCase(Locale.CHINA).contains(query))continue;if(!item.group.equals(lastGroup)){TextView group=Ui.text(BrowserActivity.this,item.group,10,Ui.GREEN,true);group.setPadding(Ui.dp(BrowserActivity.this,3),Ui.dp(BrowserActivity.this,9),0,Ui.dp(BrowserActivity.this,4));rows.addView(group);lastGroup=item.group;}LinearLayout row=Ui.horizontal(BrowserActivity.this);row.setPadding(Ui.dp(BrowserActivity.this,8),Ui.dp(BrowserActivity.this,4),0,Ui.dp(BrowserActivity.this,4));LinearLayout copy=Ui.vertical(BrowserActivity.this);TextView name=Ui.text(BrowserActivity.this,item.title,11,Ui.TEXT,true);name.setSingleLine(true);copy.addView(name);TextView site=Ui.text(BrowserActivity.this,host(item.url),9,Ui.MUTED,false);copy.addView(site);row.addView(copy,Ui.weight(1));TextView edit=Ui.text(BrowserActivity.this,"编",10,Ui.GREEN,true);edit.setGravity(Gravity.CENTER);row.addView(edit,new LinearLayout.LayoutParams(Ui.dp(BrowserActivity.this,34),Ui.dp(BrowserActivity.this,36)));TextView del=Ui.text(BrowserActivity.this,"×",17,Ui.RED,true);del.setGravity(Gravity.CENTER);row.addView(del,new LinearLayout.LayoutParams(Ui.dp(BrowserActivity.this,34),Ui.dp(BrowserActivity.this,36)));row.setOnClickListener(v->{addTab(item.url,true);if(holder[0]!=null)holder[0].dismiss();});edit.setOnClickListener(v->{if(holder[0]!=null)holder[0].dismiss();editBookmark(item,item.title,item.url);});del.setOnClickListener(v->{data.removeBookmark(item.id);updateBookmarkButton();run();});rows.addView(row,new LinearLayout.LayoutParams(-1,Ui.dp(BrowserActivity.this,50)));}if(rows.getChildCount()==0){TextView empty=Ui.text(BrowserActivity.this,"还没有匹配的收藏",11,Ui.MUTED,false);empty.setGravity(Gravity.CENTER);rows.addView(empty,new LinearLayout.LayoutParams(-1,Ui.dp(BrowserActivity.this,100)));}}};search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int b,int c){}public void onTextChanged(CharSequence s,int a,int b,int c){render.run();}public void afterTextChanged(Editable e){}});render.run();holder[0]=new AlertDialog.Builder(this).setTitle("收藏夹").setView(root).setNegativeButton("关闭",null).setPositiveButton("收藏当前页",null).create();holder[0].setOnShowListener(v->holder[0].getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(x->{holder[0].dismiss();bookmarkCurrent();}));holder[0].show();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private WebView createWebView(TabState tab) {
        WebView web = new WebView(this); web.setBackgroundColor(Color.WHITE); web.setLayerType(View.LAYER_TYPE_HARDWARE, null); web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        WebSettings s = web.getSettings(); s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true); s.setDatabaseEnabled(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT); s.setUseWideViewPort(true); s.setLoadWithOverviewMode(true); s.setMediaPlaybackRequiresUserGesture(true); s.setSupportZoom(true); s.setBuiltInZoomControls(true); s.setDisplayZoomControls(false);
        s.setSupportMultipleWindows(true); s.setJavaScriptCanOpenWindowsAutomatically(false); s.setAllowFileAccess(false); s.setAllowContentAccess(true);
        s.setUserAgentString(EDGE_UA); s.setDefaultTextEncodingName("UTF-8");
        if (android.os.Build.VERSION.SDK_INT >= 29) s.setForceDark(WebSettings.FORCE_DARK_AUTO);
        CookieManager.getInstance().setAcceptCookie(true); CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                // Subframe navigation belongs to its own frame, never to the tab.
                if (!request.isForMainFrame()) return !BrowserUrlRules.isWebViewUrl(url);
                if (BrowserUrlRules.isWebViewUrl(url) && !BrowserUrlRules.isWebUrl(url)) return false;
                if (handleSpecial(url, tab)) return true;
                if (BrowserUrlRules.isWebUrl(url)) {
                    String cleaned = data.cleanTrackers() ? SiteSafetyEngine.cleanTracking(url) : url;
                    if (data.safetyEnabled()) {
                        SiteSafetyEngine.Result result = SiteSafetyEngine.analyze(cleaned);
                        if (tab == activeTab()) updateSafetyResult(result);
                        if ((result.level == SiteSafetyEngine.Level.DANGER || result.level == SiteSafetyEngine.Level.WARNING) && !trustedHosts.contains(result.host)) {
                            showRiskDialog(view,cleaned,result); return true;
                        }
                    }
                    if (!cleaned.equals(url)) { view.loadUrl(cleaned); Toast.makeText(BrowserActivity.this,"已移除链接跟踪参数",Toast.LENGTH_SHORT).show(); return true; }
                    return false;
                }
                if (!request.hasGesture()) { Toast.makeText(BrowserActivity.this,"已阻止网页自动打开外部应用",Toast.LENGTH_SHORT).show(); return true; }
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception ignored) { Toast.makeText(BrowserActivity.this, "无法打开此链接", Toast.LENGTH_SHORT).show(); }
                return true;
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                // A deliberate top-level visit must not be silently replaced with an empty response.
                if (!request.isForMainFrame() && BrowserExtensions.shouldBlock(data,request.getUrl().toString())) return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
                BrowserExtensions.recordMediaRequest(view,request);
                return super.shouldInterceptRequest(view, request);
            }
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                BrowserExtensions.resetMediaRequests(view,url);
                if (!BrowserUrlRules.isHome(url)) tab.url = url;
                if (tab == activeTab()) { address.setText(BrowserUrlRules.isHome(url) ? "新标签页" : url); progress.setVisibility(View.VISIBLE); updateSafety(tab.url); }
            }
            @Override public void onPageFinished(WebView view, String url) {
                boolean home = BrowserUrlRules.isHome(url); tab.url = home ? "jian://home" : url; tab.title = home ? "新标签页" : (view.getTitle() == null || view.getTitle().isEmpty() ? host(url) : view.getTitle());
                if (!home && BrowserUrlRules.isWebUrl(url) && data.saveBrowserHistory()) data.addHistory(tab.title, url);
                BrowserExtensions.cleanPage(view,url,data);
                BrowserExtensions.installClipboardGuard(BrowserActivity.this,data,view,webContainer);
                if (tab == activeTab()) { address.setText(home ? "新标签页" : url); progress.setVisibility(View.GONE); rebuildTabs(); updateSafety(tab.url); BrowserExtensions.detect(view,features->{if(tab==activeTab()){pageFeatures=features;rebuildExtensionBar();}}); }
                saveSession();
            }
            @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel(); Toast.makeText(BrowserActivity.this,"已阻止证书异常的连接："+host(error.getUrl()),Toast.LENGTH_LONG).show();
            }
            @android.annotation.TargetApi(27)
            @Override public void onSafeBrowsingHit(WebView view, WebResourceRequest request, int threatType, SafeBrowsingResponse response) {
                new AlertDialog.Builder(BrowserActivity.this).setTitle("检测到危险网页").setMessage("Android Safe Browsing 将此页面标记为可能包含恶意软件、钓鱼或有害内容。简盒不会提供绕过系统拦截的入口。\n\n"+request.getUrl()).setPositiveButton("返回安全页",(d,w)->response.backToSafety(true)).setOnCancelListener(d->response.backToSafety(true)).show();
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int value) { if (tab == activeTab()) { progress.setProgress(value); progress.setVisibility(value >= 100 ? View.GONE : View.VISIBLE); } }
            @Override public void onReceivedTitle(WebView view, String title) { tab.title = title == null ? tab.title : title; if (tab == activeTab()) rebuildTabs(); }
            @Override public boolean onCreateWindow(WebView view, boolean dialog, boolean userGesture, Message resultMsg) {
                if (!userGesture) { Toast.makeText(BrowserActivity.this,"已阻止网页自动弹出新窗口",Toast.LENGTH_SHORT).show(); return false; }
                // Chromium supplies the real destination after accepting the transport.
                // HitTestResult can contain an image URL or be empty for scripted links.
                TabState child = new TabState(); child.url = "about:blank"; child.title = "新窗口";
                tabs.add(child); child.view = createWebView(child);
                webContainer.addView(child.view, new FrameLayout.LayoutParams(-1, -1));
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(child.view); resultMsg.sendToTarget(); selectTab(tabs.size() - 1); return true;
            }
            @Override public void onCloseWindow(WebView window) {
                for (int i=0;i<tabs.size();i++) if (tabs.get(i).view==window) { closeTab(i); break; }
            }
        });
        web.setDownloadListener((url, ua, disposition, type, length) -> {
            if(SiteSafetyEngine.isHighRiskDownload(url,type))new AlertDialog.Builder(this).setTitle("高风险文件类型").setMessage("该链接指向可执行文件或脚本。此类文件可能被用于钓鱼、远控或银狐类攻击。简盒只能识别风险类型，不能确认文件是否无毒。\n\n"+url).setNegativeButton("取消",null).setPositiveButton("仍然下载",(d,w)->BrowserDownloads.confirm(this,url,ua,disposition,type,downloadOverlayHost)).show();
            else BrowserDownloads.confirm(this,url,ua,disposition,type,downloadOverlayHost);
        });
        return web;
    }

    private void trimWebViews() {
        List<TabState> loaded = new ArrayList<>(); for (TabState t : tabs) if (t.view != null && t != activeTab()) loaded.add(t);
        loaded.sort(Comparator.comparingLong(t -> t.lastUsed));
        while (loaded.size() > 1) {
            TabState old = loaded.remove(0); old.url = old.view.getUrl() == null ? old.url : old.view.getUrl();
            capturePreview(old);
            webContainer.removeView(old.view); old.view.stopLoading(); old.view.destroy(); old.view = null;
        }
    }

    private void browserMenu() {
        String ad = data.blockAds() ? "关闭轻量广告过滤" : "开启轻量广告过滤";
        String history = data.saveBrowserHistory() ? "暂停记录历史" : "恢复记录历史";
        String safety = data.safetyEnabled() ? "关闭站点安全提示" : "开启站点安全提示";
        String tracker = data.cleanTrackers() ? "关闭跟踪参数清理" : "开启跟踪参数清理";
        String[] items = {"收藏夹", "窗口管理", "新建窗口", "前进", "回到搜索主页", "更换主页背景", ad, history, safety, tracker, "下载管理", "最小化", "清理浏览器缓存"};
        String[] icons={"★","窗","＋","›","⌂","图","净","记","盾","链","↓","—","扫"};
        LinearLayout root=Ui.vertical(this);root.setPadding(Ui.dp(this,12),Ui.dp(this,8),Ui.dp(this,12),Ui.dp(this,10));TextView sub=Ui.text(this,"页面与隐私控制",11,Ui.MUTED,false);sub.setPadding(Ui.dp(this,3),0,0,Ui.dp(this,8));root.addView(sub);final AlertDialog[] holder=new AlertDialog[1];
        for(int i=0;i<items.length;i+=2){LinearLayout row=Ui.horizontal(this);for(int j=i;j<Math.min(i+2,items.length);j++){final int action=j;LinearLayout tile=Ui.horizontal(this);tile.setPadding(Ui.dp(this,9),0,Ui.dp(this,8),0);tile.setBackground(Ui.bordered(0xFFF7FAF8,0x446B8B72,11,this));TextView icon=Ui.text(this,icons[j],11,Ui.GREEN,true);icon.setGravity(Gravity.CENTER);icon.setBackground(Ui.bg(Ui.GREEN_SOFT,9,this));tile.addView(icon,new LinearLayout.LayoutParams(Ui.dp(this,30),Ui.dp(this,30)));TextView name=Ui.text(this,items[j],10,Ui.TEXT,true);name.setPadding(Ui.dp(this,7),0,0,0);name.setMaxLines(2);tile.addView(name,new LinearLayout.LayoutParams(0,-1,1));tile.setOnClickListener(v->{if(holder[0]!=null)holder[0].dismiss();runBrowserMenuAction(action);});row.addView(tile,new LinearLayout.LayoutParams(0,Ui.dp(this,50),1));if(j==i)Ui.margin(tile,0,0,7,0,this);}root.addView(row,new LinearLayout.LayoutParams(-1,Ui.dp(this,57)));}
        holder[0]=new AlertDialog.Builder(this).setTitle("Jian Edge").setView(root).setNegativeButton("关闭",null).create();holder[0].show();
    }

    private void runBrowserMenuAction(int which){
        if(which==0)showBookmarks();
        else if(which==1)showTabSwitcher();
        else if(which==2)addTab(data.browserHome(),true);
        else if(which==3){WebView web=activeWeb();if(web!=null&&web.canGoForward())web.goForward();else Toast.makeText(this,"没有可前进的页面",Toast.LENGTH_SHORT).show();}
        else if(which==4)load(data.browserHome());
        else if(which==5)chooseBackground();
        else if(which==6){data.blockAds(!data.blockAds());Toast.makeText(this,data.blockAds()?"已开启广告过滤":"已关闭广告过滤",Toast.LENGTH_SHORT).show();}
        else if(which==7)data.saveBrowserHistory(!data.saveBrowserHistory());
        else if(which==8)data.safetyEnabled(!data.safetyEnabled());
        else if(which==9)data.cleanTrackers(!data.cleanTrackers());
        else if(which==10)BrowserDownloads.openManager(this);
        else if(which==11)finish();
        else if(which==12){WebView temp=activeWeb();if(temp!=null)temp.clearCache(true);Toast.makeText(this,"缓存已清理",Toast.LENGTH_SHORT).show();}
    }

    private boolean handleSpecial(String url, TabState tab) {
        if (!url.startsWith("jian://")) return false;
        if (url.startsWith("jian://home")) loadTab(tab,"jian://home");
        else if (url.startsWith("jian://navigation")) loadTab(tab,"https://go.taolove.top/");
        else if (url.startsWith("jian://tools")) startActivity(new Intent(this, ToolboxActivity.class));
        else if (url.startsWith("jian://history")) showHistory();
        else if (url.startsWith("jian://bookmarks")) showBookmarks();
        else if (url.startsWith("jian://plugins")) startActivity(new Intent(this,MainActivity.class).putExtra(MainActivity.EXTRA_PAGE,3));
        else if (url.startsWith("jian://shortcuts")) startActivity(new Intent(this,MainActivity.class).putExtra(MainActivity.EXTRA_PAGE,1));
        else if (url.startsWith("jian://background")) chooseBackground();
        return true;
    }

    private void loadTab(TabState tab, String url) {
        if (tab == null || tab.view == null) return;
        String value = normalize(url);
        if ("jian://home".equals(value)) {
            tab.url=value; tab.title="新标签页"; tab.view.loadDataWithBaseURL("https://jian.home/",BrowserHomePage.html(this,data),"text/html","UTF-8",null);
            return;
        }
        if (handleSpecial(value,tab)) return;
        String cleaned=data.cleanTrackers()?SiteSafetyEngine.cleanTracking(value):value;
        if(!cleaned.equals(value))Toast.makeText(this,"已移除链接跟踪参数",Toast.LENGTH_SHORT).show();
        value=cleaned;
        if(data.safetyEnabled()&&(value.startsWith("http://")||value.startsWith("https://"))){
            SiteSafetyEngine.Result result=SiteSafetyEngine.analyze(value);updateSafetyResult(result);
            if((result.level==SiteSafetyEngine.Level.DANGER||result.level==SiteSafetyEngine.Level.WARNING)&&!trustedHosts.contains(result.host)){showRiskDialog(tab.view,value,result);return;}
        }
        tab.url=value; tab.view.loadUrl(value);
    }

    private void showRiskDialog(WebView view,String url,SiteSafetyEngine.Result result) {
        boolean danger=result.level==SiteSafetyEngine.Level.DANGER;Dialog dialog=safetyCard(danger?"高风险链接":"访问前请核对",result,result.host,url,true);
        TextView back=panelAction("取消访问",false),proceed=panelAction("继续访问",true);safetyActions.addView(back,new LinearLayout.LayoutParams(0,Ui.dp(this,44),1));Ui.margin(back,0,0,8,0,this);safetyActions.addView(proceed,new LinearLayout.LayoutParams(0,Ui.dp(this,44),1));back.setOnClickListener(v->dialog.dismiss());proceed.setOnClickListener(v->{dialog.dismiss();for(TabState tab:tabs)if(tab.view==view){trustedHosts.add(result.host);view.loadUrl(url);break;}});dialog.show();fitDialog(dialog);
    }

    private void updateSafety(String url) {
        if (url==null || "jian://home".equals(url) || BrowserUrlRules.isHome(url)) {
            updateSafetyResult(new SiteSafetyEngine.Result(SiteSafetyEngine.Level.SAFE,"本地主页","页面由简盒在本机生成，不会上传搜索前的输入内容。","jian.home")); return;
        }
        if (!data.safetyEnabled()) { lastSafety=null; safetyChip.setText("关闭"); safetyChip.setTextColor(Ui.MUTED); safetyChip.setBackground(Ui.bg(0xFFECEFED,10,this)); return; }
        updateSafetyResult(SiteSafetyEngine.analyze(url));
    }

    private void updateSafetyResult(SiteSafetyEngine.Result result) {
        lastSafety=result; int color=result.level==SiteSafetyEngine.Level.OFFICIAL?0xFF2E7D32:result.level==SiteSafetyEngine.Level.SAFE?Ui.GREEN:result.level==SiteSafetyEngine.Level.WARNING?0xFFF59E0B:Ui.RED;
        String shortLabel=result.level==SiteSafetyEngine.Level.OFFICIAL?"官网":result.level==SiteSafetyEngine.Level.SAFE?"安全":result.level==SiteSafetyEngine.Level.WARNING?"核对":"危险";safetyChip.setText(shortLabel); safetyChip.setTextColor(color); safetyChip.setBackground(Ui.bg((color&0x00FFFFFF)|0x1A000000,9,this));
    }

    private void showSafetyDetail() {
        if(lastSafety==null){Toast.makeText(this,"站点安全检查已关闭",Toast.LENGTH_SHORT).show();return;}
        Dialog dialog=safetyCard(lastSafety.label,lastSafety,lastSafety.host,activeWeb()==null?"":activeWeb().getUrl(),false);TextView close=panelAction("知道了",true);safetyActions.addView(close,new LinearLayout.LayoutParams(-1,Ui.dp(this,44)));close.setOnClickListener(v->dialog.dismiss());dialog.show();fitDialog(dialog);
    }

    private Dialog safetyCard(String title,SiteSafetyEngine.Result result,String host,String url,boolean decision){
        Dialog dialog=new Dialog(this);LinearLayout card=Ui.vertical(this);card.setPadding(Ui.dp(this,18),Ui.dp(this,16),Ui.dp(this,18),Ui.dp(this,16));card.setBackground(Ui.bg(Ui.SURFACE,18,this));
        int color=result.level==SiteSafetyEngine.Level.DANGER?Ui.RED:result.level==SiteSafetyEngine.Level.WARNING?0xFFF59E0B:Ui.GREEN;LinearLayout head=Ui.horizontal(this);TextView icon=Ui.text(this,result.level==SiteSafetyEngine.Level.DANGER?"!":"✓",16,Color.WHITE,true);icon.setGravity(Gravity.CENTER);icon.setBackground(Ui.bg(color,13,this));head.addView(icon,new LinearLayout.LayoutParams(Ui.dp(this,46),Ui.dp(this,46)));LinearLayout titles=Ui.vertical(this);titles.setPadding(Ui.dp(this,11),0,0,0);titles.addView(Ui.text(this,title,17,Ui.TEXT,true));titles.addView(Ui.text(this,host==null||host.isEmpty()?"当前页面":host,10,Ui.MUTED,false));head.addView(titles,new LinearLayout.LayoutParams(0,-2,1));TextView chip=Ui.text(this,result.label,9,color,true);chip.setGravity(Gravity.CENTER);chip.setBackground(Ui.bg((color&0x00FFFFFF)|0x18000000,9,this));head.addView(chip,new LinearLayout.LayoutParams(Ui.dp(this,54),Ui.dp(this,30)));card.addView(head);
        TextView detail=Ui.text(this,result.detail,11,Ui.TEXT,false);detail.setPadding(Ui.dp(this,11),Ui.dp(this,9),Ui.dp(this,11),Ui.dp(this,9));detail.setBackground(Ui.bordered(Ui.SOFT_SURFACE,Ui.BORDER,11,this));card.addView(detail);Ui.margin(detail,0,12,0,8,this);
        String[][] checks={{"连接",url!=null&&url.startsWith("https://")?"HTTPS 加密":"非 HTTPS"},{"域名",host==null||host.isEmpty()?"无法识别":host},{"本地规则",result.label}};for(String[] check:checks){LinearLayout row=Ui.horizontal(this);TextView key=Ui.text(this,check[0],10,Ui.MUTED,false),value=Ui.text(this,check[1],10,Ui.TEXT,true);row.addView(key,new LinearLayout.LayoutParams(0,Ui.dp(this,31),1));row.addView(value);card.addView(row);}
        TextView note=Ui.text(this,decision?"继续访问只会信任本次运行中的该域名。":"检测基于本地规则与 Android Safe Browsing，结果用于辅助判断。",9,Ui.MUTED,false);note.setPadding(0,Ui.dp(this,7),0,Ui.dp(this,10));card.addView(note);safetyActions=Ui.horizontal(this);card.addView(safetyActions);dialog.setContentView(card);dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));return dialog;
    }
    private TextView panelAction(String label,boolean primary){TextView v=Ui.text(this,label,12,primary?Color.WHITE:Ui.TEXT,true);v.setGravity(Gravity.CENTER);v.setBackground(primary?Ui.bg(Ui.GREEN,10,this):Ui.bordered(Ui.SURFACE,Ui.BORDER,10,this));return v;}
    private void fitDialog(Dialog dialog){if(dialog.getWindow()!=null){dialog.getWindow().setLayout(Math.min(Ui.dp(this,410),getResources().getDisplayMetrics().widthPixels-Ui.dp(this,28)),-2);dialog.getWindow().setDimAmount(.55f);dialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);}}

    private void chooseBackground() {
        Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE); startActivityForResult(pick,REQUEST_BACKGROUND);
    }

    private void showHistory() {
        LinearLayout root = Ui.vertical(this); root.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), Ui.dp(this, 8));
        EditText search = new EditText(this); search.setSingleLine(true); search.setHint("搜索标题或网址"); search.setBackground(Ui.bordered(0xFFF3F8F4, Ui.BORDER, 11, this)); search.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        root.addView(search, new LinearLayout.LayoutParams(-1, Ui.dp(this, 44)));
        ScrollView scroll = new ScrollView(this); LinearLayout rows = Ui.vertical(this); scroll.addView(rows, new ScrollView.LayoutParams(-1, -2)); root.addView(scroll, new LinearLayout.LayoutParams(-1, Ui.dp(this, 430)));
        final AlertDialog[] dialog = new AlertDialog[1];
        Runnable render = new Runnable() { public void run() {
            rows.removeAllViews(); String q = search.getText().toString().trim().toLowerCase(Locale.CHINA);
            for (JianData.BrowserHistory h : data.history()) {
                if (!q.isEmpty() && !(h.title + h.url).toLowerCase(Locale.CHINA).contains(q)) continue;
                LinearLayout row = Ui.horizontal(BrowserActivity.this); row.setPadding(Ui.dp(BrowserActivity.this, 7), Ui.dp(BrowserActivity.this, 5), 0, Ui.dp(BrowserActivity.this, 5));
                LinearLayout copy = Ui.vertical(BrowserActivity.this); copy.addView(Ui.text(BrowserActivity.this, h.title, 12, Ui.TEXT, true)); TextView url = Ui.text(BrowserActivity.this, h.url + " · " + DATE.format(h.time), 9, Ui.MUTED, false); url.setSingleLine(true); copy.addView(url); row.addView(copy, Ui.weight(1));
                TextView del = Ui.text(BrowserActivity.this, "×", 17, Ui.RED, true); del.setGravity(Gravity.CENTER); del.setBackground(Ui.bg(0xFFFFF1F1, 10, BrowserActivity.this)); row.addView(del, new LinearLayout.LayoutParams(Ui.dp(BrowserActivity.this, 36), Ui.dp(BrowserActivity.this, 36)));
                row.setOnClickListener(v -> { addTab(h.url, true); if (dialog[0] != null) dialog[0].dismiss(); });
                del.setOnClickListener(v -> new AlertDialog.Builder(BrowserActivity.this).setTitle("删除这条记录？").setMessage(h.title).setNegativeButton("取消",null).setPositiveButton("删除",(d,w)->{List<JianData.BrowserHistory> all=data.history();all.removeIf(x->x.id.equals(h.id));data.saveHistory(all);run();}).show());
                rows.addView(row, new LinearLayout.LayoutParams(-1, Ui.dp(BrowserActivity.this, 58)));
            }
            if (rows.getChildCount() == 0) { TextView empty = Ui.text(BrowserActivity.this, "没有匹配的浏览记录", 12, Ui.MUTED, false); empty.setGravity(Gravity.CENTER); rows.addView(empty, new LinearLayout.LayoutParams(-1, Ui.dp(BrowserActivity.this, 100))); }
        }};
        search.addTextChangedListener(new TextWatcher() { public void beforeTextChanged(CharSequence s,int a,int b,int c){} public void onTextChanged(CharSequence s,int a,int b,int c){render.run();} public void afterTextChanged(Editable e){} });
        render.run(); dialog[0] = new AlertDialog.Builder(this).setTitle("浏览历史").setView(root).setNegativeButton("关闭", null).setPositiveButton("清空全部", null).create();
        dialog[0].setOnShowListener(x -> dialog[0].getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("清空浏览历史？").setMessage("此操作无法撤销。").setNegativeButton("取消", null).setPositiveButton("清空", (a,b) -> { data.clearHistory(); render.run(); }).show()));
        dialog[0].show();
    }

    private void load(String url) { TabState tab=activeTab(); if (tab != null) { loadTab(tab,normalize(url)); address.clearFocus(); } }

    private String normalize(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty() || "新标签页".equals(value)) return data == null ? "jian://home" : data.browserHome();
        if ("about:blank".equals(value)) return value;
        if (value.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")) return value;
        if (value.contains(".") && !value.contains(" ")) return "https://" + value;
        return SearchEngine.url(data.searchEngine(),value);
    }

    private boolean isAdUrl(String url) {
        String lower = url.toLowerCase(Locale.ROOT); for (String part : AD_HOST_PARTS) if (lower.contains(part)) return true; return false;
    }

    private String host(String url) { try { String h = new URI(url).getHost(); return h == null ? "新标签页" : h; } catch (Exception e) { return "新标签页"; } }
    private String shortTitle(String title) { String value=title==null||title.trim().isEmpty()?"网页":title.trim();return value.length()>12?value.substring(0,12):value; }
    private TabState activeTab() { return current >= 0 && current < tabs.size() ? tabs.get(current) : null; }
    private WebView activeWeb() { TabState t = activeTab(); return t == null ? null : t.view; }

    private void saveSession() {
        try {
            JSONArray a = new JSONArray(); for (TabState t : tabs) a.put(new JSONObject().put("url", t.url).put("title", t.title));
            getSharedPreferences("jian_browser_session", MODE_PRIVATE).edit().putString("tabs", a.toString()).putInt("current", current).apply();
        } catch (Exception ignored) {}
    }

    private void restoreSession() {
        try {
            JSONArray a = new JSONArray(getSharedPreferences("jian_browser_session", MODE_PRIVATE).getString("tabs", "[]"));
            for (int i = 0; i < Math.min(a.length(), 12); i++) { JSONObject o = a.getJSONObject(i); TabState t = new TabState(); t.url = o.optString("url", data.browserHome()); t.title = o.optString("title", "标签页"); tabs.add(t); }
            current = getSharedPreferences("jian_browser_session", MODE_PRIVATE).getInt("current", 0);
        } catch (Exception ignored) { tabs.clear(); current = -1; }
    }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent intent) {
        super.onActivityResult(requestCode,resultCode,intent);
        if(requestCode==REQUEST_BACKGROUND&&resultCode==RESULT_OK&&intent!=null&&intent.getData()!=null){
            try { String path=ImageStore.save(this,intent.getData(),"browser_home",1600,false);data.browserBackgroundPath(path);Toast.makeText(this,"主页背景已更新",Toast.LENGTH_SHORT).show();if(activeTab()!=null&&"jian://home".equals(activeTab().url))loadTab(activeTab(),"jian://home"); }
            catch(Exception e){Toast.makeText(this,"无法使用这张图片",Toast.LENGTH_SHORT).show();}
        }
    }

    @Override public void onBackPressed() {View switcher=browserShell==null?null:browserShell.findViewWithTag("tab_switcher");if(switcher!=null){browserShell.removeView(switcher);return;}WebView web=activeWeb();if(web!=null&&web.canGoBack()){web.goBack();return;}saveSession();finish(); }
    @Override protected void onPause() { super.onPause(); WebView w = activeWeb(); if (w != null) w.onPause(); saveSession(); }
    @Override protected void onResume() { super.onResume();if(!UpdateGuard.resume(this))return;Ui.applyTheme(data); if(address!=null)address.setHint(SearchEngine.label(data.searchEngine())+" 搜索或输入网址");rebuildExtensionBar();WebView w = activeWeb(); if (w != null) w.onResume(); }
    @Override protected void onDestroy() { for (TabState t : tabs) {if (t.view != null) t.view.destroy();if(t.preview!=null&&!t.preview.isRecycled())t.preview.recycle();} super.onDestroy(); }
}
