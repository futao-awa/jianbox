package com.jianbox.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Message;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.webkit.CookieManager;
import android.webkit.SafeBrowsingResponse;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** A browser owned by the overlay service: movable, resizable, dockable and lazily tabbed. */
final class FloatingBrowserWindow {
    private static final String EDGE_UA = "Mozilla/5.0 (Linux; Android 16; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Mobile Safari/537.36 EdgA/138.0.0.0";
    private static final String[] AD_HOST_PARTS = {"doubleclick.net","googlesyndication.com","googleadservices.com","adservice.","adsystem.com","umeng.com","cnzz.com","tanx.com","adnxs.com"};

    private final Context context;
    private final WindowManager wm;
    private final JianData data;
    private final List<Tab> tabs = new ArrayList<>();
    private final Set<String> trustedHosts = new HashSet<>();
    private FrameLayout window;
    private WindowManager.LayoutParams params;
    private FrameLayout webContainer;
    private TextView tabCount;
    private EditText address;
    private ProgressBar progress;
    private TextView safety;
    private TextView bookmark;
    private FrameLayout extensionBarHost;
    private BrowserExtensions.PageFeatures pageFeatures=new BrowserExtensions.PageFeatures();
    private View riskVeil;
    private LinearLayout dock;
    private WindowManager.LayoutParams dockParams;
    private int current = -1;
    private boolean fullSize;
    private boolean windowUpdateScheduled;
    private int restoreX, restoreY, restoreWidth, restoreHeight;

    private static final class Tab {
        String id = UUID.randomUUID().toString();
        String url = "jian://home";
        String title = "新标签页";
        long lastUsed;
        WebView web;
    }

    @SuppressLint("WebViewApiAvailability")
    FloatingBrowserWindow(Context context, WindowManager wm, JianData data) {
        this.context = context;
        this.wm = wm;
        this.data = data;
        Ui.applyTheme(data);
        if (Build.VERSION.SDK_INT >= 27) WebView.startSafeBrowsing(context, ok -> {});
    }

    void show(String rawUrl) {
        if(UpdateGuard.hasForceGate(context)){UpdateGuard.openGate(context);return;}
        ensureWindow();
        if (window == null) return;
        if (dock != null) removeDock();
        window.animate().cancel();window.setAlpha(1f);window.setScaleX(1f);window.setScaleY(1f);
        window.setVisibility(View.VISIBLE);
        if (rawUrl == null || rawUrl.trim().isEmpty()) rawUrl = data.browserHome();
        addTab(rawUrl);
    }

    void destroy() {
        removeDock();
        if (window != null) {
            try { wm.removeView(window); } catch (Exception ignored) {}
            window = null;
        }
        for (Tab tab : tabs) destroyWeb(tab);
        tabs.clear(); current = -1;
    }

    private void ensureWindow() {
        if (window != null) return;
        int sw = context.getResources().getDisplayMetrics().widthPixels;
        int sh = context.getResources().getDisplayMetrics().heightPixels;
        params = overlayParams(Math.min(Ui.dp(context, 390), sw - Ui.dp(context, 24)), Math.min(Ui.dp(context, 590), sh - Ui.dp(context, 90)), true);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = Math.max(Ui.dp(context, 8), sw - params.width - Ui.dp(context, 12));
        params.y = Ui.dp(context, 55);

        window = new FrameLayout(context);
        window.setBackground(Ui.bordered(Ui.GLASS?0xE8FFFFFF:0xFFF6FAF7, Ui.GLASS?0x66FFFFFF:0x8868A96C, 19, context));
        window.setClipToOutline(true);
        window.setElevation(Ui.dp(context, 18));
        LinearLayout column = Ui.vertical(context);
        window.addView(column, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout title = Ui.horizontal(context);
        title.setPadding(Ui.dp(context, 7), Ui.dp(context, 3), Ui.dp(context, 5), Ui.dp(context, 3));
        TextView grip = Ui.text(context, "⠿  悬浮浏览器", 10, Ui.TEXT, true);
        title.addView(grip, new LinearLayout.LayoutParams(0, Ui.dp(context, 31), 1));
        tabCount=control("1");tabCount.setTextSize(9);tabCount.setBackground(Ui.bordered(Color.WHITE,Ui.BORDER,8,context));tabCount.setOnClickListener(v->showTabSwitcher());title.addView(tabCount,box(30,28));
        TextView shrink = control("—"), maximize = control("□"), close = control("×");
        title.addView(shrink, box(31,31)); title.addView(maximize, box(31,31)); title.addView(close, box(31,31));
        column.addView(title, new LinearLayout.LayoutParams(-1, Ui.dp(context, 38)));
        installMoveGesture(grip);
        shrink.setOnClickListener(v -> minimize());
        maximize.setOnClickListener(v -> toggleMaximize());
        close.setOnClickListener(v -> destroy());

        LinearLayout nav = Ui.horizontal(context); nav.setPadding(Ui.dp(context, 5), 0, Ui.dp(context, 5), Ui.dp(context, 4));
        TextView back = control("‹"), forward=control("›"), refresh=control("↻"); nav.addView(back, box(27,35));nav.addView(forward,box(27,35));nav.addView(refresh,box(27,35));
        address = new EditText(context); address.setSingleLine(true); address.setTextSize(11); address.setTextColor(Ui.TEXT); address.setHint("搜索或输入网址");
        address.setHintTextColor(0x7799AA99); address.setBackground(Ui.bordered(Color.WHITE, Ui.BORDER, 9, context)); address.setPadding(Ui.dp(context, 9),0,Ui.dp(context,9),0);
        address.setImeOptions(EditorInfo.IME_ACTION_GO);
        nav.addView(address, new LinearLayout.LayoutParams(0, Ui.dp(context, 35), 1));
        safety = Ui.text(context,"安全",8,Ui.GREEN,true); safety.setGravity(Gravity.CENTER); safety.setBackground(Ui.bg(Ui.GREEN_SOFT,8,context));
        nav.addView(safety, box(43,31));
        bookmark=control("☆");bookmark.setTextSize(14);nav.addView(bookmark,box(29,35));
        TextView openFull = control("↗"); nav.addView(openFull, box(31,35));
        column.addView(nav, new LinearLayout.LayoutParams(-1, Ui.dp(context, 40)));
        back.setOnClickListener(v -> { WebView w=activeWeb(); if(w!=null&&w.canGoBack())w.goBack(); });
        forward.setOnClickListener(v -> { WebView w=activeWeb(); if(w!=null&&w.canGoForward())w.goForward(); });
        refresh.setOnClickListener(v->{WebView w=activeWeb();if(w!=null)w.reload();});
        address.setOnEditorActionListener((v, action, event) -> { if(action==EditorInfo.IME_ACTION_GO||(event!=null&&event.getKeyCode()==KeyEvent.KEYCODE_ENTER)){loadActive(address.getText().toString());return true;}return false; });
        safety.setOnClickListener(v -> Toast.makeText(context, "绿色仅表示未发现明显风险；官网仅对白名单域名标注", Toast.LENGTH_LONG).show());
        bookmark.setOnClickListener(v->toggleBookmark());
        openFull.setOnClickListener(v -> { Tab t=activeTab(); BrowserActivity.open(context,t==null?data.browserHome():t.url); minimize(); });

        progress = new ProgressBar(context,null,android.R.attr.progressBarStyleHorizontal); progress.setMax(100); progress.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.GREEN));
        column.addView(progress,new LinearLayout.LayoutParams(-1,Ui.dp(context,2)));
        extensionBarHost=new FrameLayout(context);column.addView(extensionBarHost,new LinearLayout.LayoutParams(-1,Ui.dp(context,32)));refreshExtensions();
        webContainer = new FrameLayout(context); webContainer.setBackgroundColor(Color.WHITE);
        column.addView(webContainer,new LinearLayout.LayoutParams(-1,0,1));

        TextView resize = Ui.text(context,"◢",18,Ui.GREEN,true); resize.setGravity(Gravity.BOTTOM|Gravity.END); resize.setPadding(0,0,Ui.dp(context,5),Ui.dp(context,4));
        FrameLayout.LayoutParams resizeLp = new FrameLayout.LayoutParams(Ui.dp(context,46),Ui.dp(context,46),Gravity.BOTTOM|Gravity.END); window.addView(resize,resizeLp);resize.setElevation(Ui.dp(context,24));resize.setClickable(true);resize.bringToFront(); installResizeGesture(resize);
        try { wm.addView(window,params); } catch(Exception e) { window=null; Toast.makeText(context,"无法创建悬浮浏览器，请检查悬浮窗权限",Toast.LENGTH_LONG).show(); }
    }

    void refreshExtensions(){
        Ui.applyTheme(data);if(extensionBarHost==null)return;extensionBarHost.removeAllViews();
        extensionBarHost.addView(BrowserExtensions.toolbar(context,data,pageFeatures,new BrowserExtensions.Action(){
            public void translate(){BrowserExtensions.translate(context,activeWeb());}
            public void find(){BrowserExtensions.showFind(context,activeWeb(),webContainer);}
            public void media(){BrowserExtensions.extractMedia(context,activeWeb(),webContainer);}
            public void source(){BrowserExtensions.viewSource(context,activeWeb(),webContainer);}
            public void speed(){BrowserExtensions.showVideoSpeed(context,activeWeb(),webContainer);}
            public void captions(){BrowserExtensions.enableCaptions(context,activeWeb());}
            public void superCopy(){BrowserExtensions.enableSuperCopy(context,activeWeb());}
            public void visual(){BrowserExtensions.showVisual(context,data,activeWeb(),webContainer);}
            public void password(){BrowserExtensions.showPassword(context,webContainer);}
            public void downloads(){BrowserExtensions.extractDownloads(context,activeWeb(),webContainer);}
            public void developer(){BrowserExtensions.showDeveloper(context,activeWeb(),webContainer);}
            public void manage(){context.startActivity(new Intent(context,MainActivity.class).putExtra(MainActivity.EXTRA_PAGE,3).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}
        }),new FrameLayout.LayoutParams(-1,-1));
    }

    private WindowManager.LayoutParams overlayParams(int width,int height,boolean focusable) {
        int flags=WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        if(!focusable)flags|=WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(width,height,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,flags,android.graphics.PixelFormat.TRANSLUCENT);
        p.softInputMode=WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE; return p;
    }

    private LinearLayout.LayoutParams box(int w,int h){return new LinearLayout.LayoutParams(Ui.dp(context,w),Ui.dp(context,h));}
    private TextView control(String value){TextView v=Ui.text(context,value,16,Ui.TEXT,true);v.setGravity(Gravity.CENTER);v.setBackground(Ui.bg(Color.TRANSPARENT,8,context));return v;}

    @SuppressLint("ClickableViewAccessibility")
    private void installMoveGesture(View grip) {
        final float[] down=new float[4];
        grip.setOnTouchListener((v,e)->{if(e.getAction()==MotionEvent.ACTION_DOWN){window.animate().cancel();down[0]=e.getRawX();down[1]=e.getRawY();down[2]=params.x;down[3]=params.y;return true;}if(e.getAction()==MotionEvent.ACTION_MOVE&&!fullSize){params.x=clamp((int)(down[2]+e.getRawX()-down[0]),0,screenWidth()-params.width);params.y=clamp((int)(down[3]+e.getRawY()-down[1]),0,screenHeight()-params.height);scheduleWindowUpdate();return true;}if(e.getAction()==MotionEvent.ACTION_UP||e.getAction()==MotionEvent.ACTION_CANCEL){updateWindow();return true;}return false;});
    }

    @SuppressLint("ClickableViewAccessibility")
    private void installResizeGesture(View resize) {
        final float[] down=new float[4];
        resize.setOnTouchListener((v,e)->{if(e.getAction()==MotionEvent.ACTION_DOWN){window.animate().cancel();v.getParent().requestDisallowInterceptTouchEvent(true);down[0]=e.getRawX();down[1]=e.getRawY();down[2]=params.width;down[3]=params.height;return true;}if(e.getAction()==MotionEvent.ACTION_MOVE&&!fullSize){params.width=clamp((int)(down[2]+e.getRawX()-down[0]),Ui.dp(context,280),screenWidth()-params.x);params.height=clamp((int)(down[3]+e.getRawY()-down[1]),Ui.dp(context,300),screenHeight()-params.y);scheduleWindowUpdate();return true;}if(e.getAction()==MotionEvent.ACTION_UP||e.getAction()==MotionEvent.ACTION_CANCEL){v.getParent().requestDisallowInterceptTouchEvent(false);updateWindow();return true;}return false;});
    }

    private void toggleMaximize(){if(window!=null)window.animate().cancel();if(!fullSize){restoreX=params.x;restoreY=params.y;restoreWidth=params.width;restoreHeight=params.height;params.x=0;params.y=0;params.width=screenWidth();params.height=screenHeight();fullSize=true;}else{params.width=clamp(restoreWidth,Ui.dp(context,280),screenWidth());params.height=clamp(restoreHeight,Ui.dp(context,300),screenHeight());params.x=clamp(restoreX,0,screenWidth()-params.width);params.y=clamp(restoreY,0,screenHeight()-params.height);fullSize=false;}updateWindow();}
    private void updateWindow(){if(window!=null)try{wm.updateViewLayout(window,params);}catch(Exception ignored){}}
    private void scheduleWindowUpdate(){if(window==null||windowUpdateScheduled)return;windowUpdateScheduled=true;window.postOnAnimation(()->{windowUpdateScheduled=false;updateWindow();});}
    private int screenWidth(){return context.getResources().getDisplayMetrics().widthPixels;}
    private int screenHeight(){return context.getResources().getDisplayMetrics().heightPixels;}
    private int clamp(int v,int min,int max){return Math.max(min,Math.min(v,Math.max(min,max)));}

    private void minimize(){if(window==null)return;window.animate().cancel();window.animate().alpha(0f).scaleX(.96f).scaleY(.96f).setDuration(150).withEndAction(()->{if(window!=null){window.setVisibility(View.GONE);window.setAlpha(1f);window.setScaleX(1f);window.setScaleY(1f);showDock();}}).start();}
    private void showDock(){
        if(dock!=null){rebuildDock();return;}
        dock=Ui.horizontal(context);dock.setPadding(Ui.dp(context,3),Ui.dp(context,2),Ui.dp(context,3),Ui.dp(context,2));dock.setBackground(Ui.bordered(0xF2222925,0x556F7D73,10,context));dock.setElevation(Ui.dp(context,10));
        dockParams=overlayParams(Math.min(Ui.dp(context,158),screenWidth()-Ui.dp(context,20)),Ui.dp(context,38),false);dockParams.gravity=Gravity.TOP|Gravity.START;dockParams.x=screenWidth()-dockParams.width-Ui.dp(context,5);dockParams.y=clamp(params.y,0,screenHeight()-dockParams.height);
        rebuildDock();try{dock.setAlpha(0f);dock.setScaleX(.92f);wm.addView(dock,dockParams);dock.animate().alpha(1f).scaleX(1f).setDuration(160).start();}catch(Exception e){dock=null;}
    }
    @SuppressLint("ClickableViewAccessibility") private void installDockMove(View handle){final float[] d=new float[4];handle.setOnTouchListener((v,e)->{if(e.getAction()==MotionEvent.ACTION_DOWN){d[0]=e.getRawX();d[1]=e.getRawY();d[2]=dockParams.x;d[3]=dockParams.y;return true;}if(e.getAction()==MotionEvent.ACTION_MOVE){dockParams.x=clamp((int)(d[2]+e.getRawX()-d[0]),0,screenWidth()-dockParams.width);dockParams.y=clamp((int)(d[3]+e.getRawY()-d[1]),0,screenHeight()-dockParams.height);try{wm.updateViewLayout(dock,dockParams);}catch(Exception ignored){}return true;}return e.getAction()==MotionEvent.ACTION_UP||e.getAction()==MotionEvent.ACTION_CANCEL;});}
    private void rebuildDock(){if(dock==null)return;dock.removeAllViews();TextView handle=Ui.text(context,"⋮⋮",10,0xFFCBD5CE,true);handle.setGravity(Gravity.CENTER);installDockMove(handle);dock.addView(handle,box(23,30));HorizontalScrollView scroller=new HorizontalScrollView(context);scroller.setHorizontalScrollBarEnabled(false);LinearLayout rail=Ui.horizontal(context);for(int i=0;i<tabs.size();i++){final int index=i;TextView chip=Ui.text(context,(i+1)+" "+shortTitle(tabs.get(i).title),8,Color.WHITE,i==current);chip.setGravity(Gravity.CENTER);chip.setSingleLine(true);chip.setBackground(Ui.bg(i==current?0xFF536D5C:0x443E5145,8,context));chip.setOnClickListener(v->{removeDock();window.animate().cancel();window.setAlpha(1f);window.setScaleX(1f);window.setScaleY(1f);window.setVisibility(View.VISIBLE);selectTab(index);});rail.addView(chip,new LinearLayout.LayoutParams(Ui.dp(context,60),Ui.dp(context,28)));Ui.margin(chip,0,0,3,0,context);}scroller.addView(rail,new HorizontalScrollView.LayoutParams(-2,-1));dock.addView(scroller,new LinearLayout.LayoutParams(0,Ui.dp(context,30),1));}
    private void removeDock(){if(dock!=null){try{wm.removeView(dock);}catch(Exception ignored){}dock=null;dockParams=null;}}

    private void addTab(String raw){Tab t=new Tab();t.url=normalize(raw);tabs.add(t);selectTab(tabs.size()-1);}
    private void selectTab(int index){if(index<0||index>=tabs.size())return;if(current>=0&&current<tabs.size()&&tabs.get(current).web!=null){tabs.get(current).web.onPause();tabs.get(current).web.setVisibility(View.GONE);}current=index;Tab t=tabs.get(index);t.lastUsed=System.currentTimeMillis();if(t.web==null){t.web=createWebView(t);webContainer.addView(t.web,new FrameLayout.LayoutParams(-1,-1));requestLoad(t,t.url);}t.web.setVisibility(View.VISIBLE);t.web.onResume();address.setText("jian://home".equals(t.url)?"新标签页":t.url);updateSafety(t.url);rebuildTabs();trimWebViews();rebuildDock();}
    private void closeTab(int index){if(index<0||index>=tabs.size())return;Tab old=tabs.remove(index);destroyWeb(old);if(tabs.isEmpty()){current=-1;addTab(data.browserHome());return;}if(index<current)current--;else if(index==current)current=-1;selectTab(Math.min(index,tabs.size()-1));}
    private void destroyWeb(Tab t){if(t.web!=null){if(webContainer!=null)webContainer.removeView(t.web);t.web.stopLoading();BlobDownloads.release(t.web);t.web.destroy();t.web=null;}}

    private void rebuildTabs(){if(tabCount!=null)tabCount.setText(String.valueOf(tabs.size()));updateBookmark();}

    private void showTabSwitcher(){
        if(window==null)return;View old=window.findViewWithTag("floating_tab_switcher");if(old!=null)window.removeView(old);
        FrameLayout veil=new FrameLayout(context);veil.setTag("floating_tab_switcher");veil.setBackgroundColor(0xF7F3F7F4);LinearLayout column=Ui.vertical(context);column.setPadding(Ui.dp(context,10),Ui.dp(context,8),Ui.dp(context,10),Ui.dp(context,8));veil.addView(column,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout head=Ui.horizontal(context);TextView close=control("‹");close.setOnClickListener(v->window.removeView(veil));head.addView(close,box(34,36));head.addView(Ui.text(context,"窗口  "+tabs.size(),15,Ui.TEXT,true),new LinearLayout.LayoutParams(0,Ui.dp(context,36),1));TextView add=Ui.text(context,"＋ 新建",10,Ui.GREEN,true);add.setGravity(Gravity.CENTER);add.setOnClickListener(v->{window.removeView(veil);addTab(data.browserHome());});head.addView(add,box(58,36));column.addView(head);
        android.widget.ScrollView scroll=new android.widget.ScrollView(context);LinearLayout rows=Ui.vertical(context);scroll.addView(rows,new android.widget.ScrollView.LayoutParams(-1,-2));column.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
         for(int i=0;i<tabs.size();i++){final int index=i;Tab tab=tabs.get(i);LinearLayout card=Ui.horizontal(context);card.setPadding(Ui.dp(context,9),Ui.dp(context,5),Ui.dp(context,5),Ui.dp(context,5));card.setBackground(i==current?Ui.bordered(Color.WHITE,Ui.GREEN,10,context):Ui.bordered(Color.WHITE,0x335F7964,10,context));TextView number=Ui.text(context,String.valueOf(i+1),10,Color.WHITE,true);number.setGravity(Gravity.CENTER);number.setBackground(Ui.bg(i==current?Ui.GREEN:Ui.MUTED,9,context));card.addView(number,box(28,28));LinearLayout copy=Ui.vertical(context);copy.setPadding(Ui.dp(context,8),0,0,0);TextView name=Ui.text(context,tab.title,10,Ui.TEXT,true);name.setSingleLine(true);copy.addView(name);TextView site=Ui.text(context,host(tab.url),8,Ui.MUTED,false);site.setSingleLine(true);copy.addView(site);card.addView(copy,new LinearLayout.LayoutParams(0,-2,1));TextView x=Ui.text(context,"×",16,Ui.RED,true);x.setGravity(Gravity.CENTER);card.addView(x,box(32,36));card.setOnClickListener(v->{window.removeView(veil);selectTab(index);});x.setOnClickListener(v->{card.animate().alpha(0f).translationX(Ui.dp(context,24)).setDuration(140).withEndAction(()->{window.removeView(veil);closeTab(index);showTabSwitcher();}).start();});rows.addView(card,new LinearLayout.LayoutParams(-1,Ui.dp(context,50)));Ui.margin(card,0,0,0,6,context);}
        window.addView(veil,new FrameLayout.LayoutParams(-1,-1));
    }

    private void toggleBookmark(){Tab tab=activeTab();if(tab==null||tab.url==null||!tab.url.startsWith("http")){Toast.makeText(context,"当前页面不能收藏",Toast.LENGTH_SHORT).show();return;}JianData.Bookmark saved=data.bookmarkFor(tab.url);if(saved!=null){data.removeBookmark(saved.id);Toast.makeText(context,"已取消收藏",Toast.LENGTH_SHORT).show();}else{JianData.Bookmark item=new JianData.Bookmark();item.title=tab.title;item.url=tab.url;item.group="常用";item.time=System.currentTimeMillis();data.saveBookmark(item);Toast.makeText(context,"已收藏到「常用」",Toast.LENGTH_SHORT).show();}updateBookmark();}
    private void updateBookmark(){if(bookmark==null)return;Tab tab=activeTab();boolean saved=tab!=null&&data.bookmarkFor(tab.url)!=null;bookmark.setText(saved?"★":"☆");bookmark.setTextColor(saved?0xFFFFB300:Ui.TEXT);}

    @SuppressLint("SetJavaScriptEnabled") private WebView createWebView(Tab tab){
        WebView web=new WebView(context);web.setBackgroundColor(Color.WHITE);web.setLayerType(View.LAYER_TYPE_HARDWARE,null);web.setOverScrollMode(View.OVER_SCROLL_NEVER);WebSettings s=web.getSettings();s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);s.setDatabaseEnabled(true);s.setCacheMode(WebSettings.LOAD_DEFAULT);s.setUseWideViewPort(true);s.setLoadWithOverviewMode(true);s.setMediaPlaybackRequiresUserGesture(true);s.setSupportZoom(true);s.setBuiltInZoomControls(true);s.setDisplayZoomControls(false);s.setSupportMultipleWindows(true);s.setJavaScriptCanOpenWindowsAutomatically(false);s.setAllowFileAccess(false);s.setAllowContentAccess(true);s.setUserAgentString(EDGE_UA);CookieManager.getInstance().setAcceptCookie(true);CookieManager.getInstance().setAcceptThirdPartyCookies(web,true);
        BlobDownloads.install(web);
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){
                String url=request.getUrl().toString();
                if(!request.isForMainFrame())return !BrowserUrlRules.isWebViewUrl(url);
                if(BrowserUrlRules.isWebViewUrl(url)&&!BrowserUrlRules.isWebUrl(url))return false;
                if(handleSpecial(tab,url))return true;
                if(BrowserUrlRules.isWebUrl(url)){
                    String cleaned=data.cleanTrackers()?SiteSafetyEngine.cleanTracking(url):url;
                    if(data.safetyEnabled()){
                        SiteSafetyEngine.Result result=SiteSafetyEngine.analyze(cleaned);
                        if((result.level==SiteSafetyEngine.Level.WARNING||result.level==SiteSafetyEngine.Level.DANGER)&&!trustedHosts.contains(result.host)){showRisk(tab,cleaned,result);return true;}
                    }
                    if(!cleaned.equals(url)){Toast.makeText(context,"已移除链接跟踪参数",Toast.LENGTH_SHORT).show();view.loadUrl(cleaned);return true;}
                    return false;
                }
                if(!request.hasGesture()){Toast.makeText(context,"已阻止网页自动打开外部应用",Toast.LENGTH_SHORT).show();return true;}
                try{context.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}catch(Exception e){Toast.makeText(context,"无法打开此链接",Toast.LENGTH_SHORT).show();}
                return true;
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){
                if(!request.isForMainFrame()&&BrowserExtensions.shouldBlock(data,request.getUrl().toString()))return new WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream(new byte[0]));
                BrowserExtensions.recordMediaRequest(view,request);return super.shouldInterceptRequest(view,request);
            }
            @Override public void onPageStarted(WebView view,String url,android.graphics.Bitmap icon){BlobDownloads.onPageStarted(view,url);BrowserExtensions.resetMediaRequests(view,url);if(!BrowserUrlRules.isHome(url))tab.url=url;if(tab==activeTab()){address.setText(BrowserUrlRules.isHome(url)?"新标签页":url);progress.setVisibility(View.VISIBLE);updateSafety(tab.url);}}
            @Override public void onPageFinished(WebView view,String url){boolean home=BrowserUrlRules.isHome(url);tab.url=home?"jian://home":url;tab.title=home?"新标签页":(view.getTitle()==null||view.getTitle().isEmpty()?host(url):view.getTitle());if(!home&&BrowserUrlRules.isWebUrl(url)&&data.saveBrowserHistory())data.addHistory(tab.title,url);BlobDownloads.onPageFinished(view);BrowserExtensions.cleanPage(view,url,data);BrowserExtensions.installClipboardGuard(context,data,view,webContainer);if(tab==activeTab()){address.setText(home?"新标签页":url);progress.setVisibility(View.GONE);updateSafety(tab.url);rebuildTabs();BrowserExtensions.detect(view,features->{if(tab==activeTab()){pageFeatures=features;refreshExtensions();}});}}
            @Override public void onReceivedSslError(WebView view,SslErrorHandler handler,SslError error){handler.cancel();Toast.makeText(context,"已阻止证书异常的连接："+host(error.getUrl()),Toast.LENGTH_LONG).show();}
            @android.annotation.TargetApi(27)
            @Override public void onSafeBrowsingHit(WebView view,WebResourceRequest request,int threatType,SafeBrowsingResponse response){response.backToSafety(true);Toast.makeText(context,"Android Safe Browsing 已拦截危险网页",Toast.LENGTH_LONG).show();}
        });
        web.setWebChromeClient(new WebChromeClient(){
            @Override public void onProgressChanged(WebView view,int value){if(tab==activeTab()){progress.setProgress(value);progress.setVisibility(value>=100?View.GONE:View.VISIBLE);}}
            @Override public void onReceivedTitle(WebView view,String title){if(title!=null&&!title.isEmpty())tab.title=title;if(tab==activeTab())rebuildTabs();}
            @Override public boolean onCreateWindow(WebView view,boolean dialog,boolean userGesture,Message resultMsg){
                if(!userGesture){Toast.makeText(context,"已阻止网页自动弹出新窗口",Toast.LENGTH_SHORT).show();return false;}
                Tab child=new Tab();child.url="about:blank";child.title="新窗口";tabs.add(child);
                child.web=createWebView(child);webContainer.addView(child.web,new FrameLayout.LayoutParams(-1,-1));
                WebView.WebViewTransport transport=(WebView.WebViewTransport)resultMsg.obj;
                transport.setWebView(child.web);resultMsg.sendToTarget();selectTab(tabs.size()-1);return true;
            }
            @Override public void onCloseWindow(WebView window){for(int i=0;i<tabs.size();i++)if(tabs.get(i).web==window){closeTab(i);break;}}
        });
        web.setDownloadListener((url,ua,disposition,type,length)->{if(BlobDownloads.isBlob(url)){BlobDownloads.request(context,web,url,ua,disposition,type,webContainer);return;}if(SiteSafetyEngine.isHighRiskDownload(url,type)){Toast.makeText(context,"检测到高风险文件类型，请在全屏浏览器确认",Toast.LENGTH_LONG).show();BrowserActivity.open(context,url);minimize();}else BrowserDownloads.enqueue(context,url,ua,disposition,type);});return web;
    }

    private void requestLoad(Tab tab,String raw){String url=normalize(raw);if("jian://home".equals(url)){tab.url=url;tab.title="新标签页";tab.web.loadDataWithBaseURL("https://jian.home/",BrowserHomePage.html(context,data),"text/html","UTF-8",null);return;}if(handleSpecial(tab,url))return;String cleaned=data.cleanTrackers()?SiteSafetyEngine.cleanTracking(url):url;if(!cleaned.equals(url))Toast.makeText(context,"已移除链接跟踪参数",Toast.LENGTH_SHORT).show();url=cleaned;if(data.safetyEnabled()&&(url.startsWith("http://")||url.startsWith("https://"))){SiteSafetyEngine.Result result=SiteSafetyEngine.analyze(url);if((result.level==SiteSafetyEngine.Level.WARNING||result.level==SiteSafetyEngine.Level.DANGER)&&!trustedHosts.contains(result.host)){showRisk(tab,url,result);return;}}tab.url=url;tab.web.loadUrl(url);}
    private void showRisk(Tab tab,String url,SiteSafetyEngine.Result result){if(riskVeil!=null&&riskVeil.getParent()==window)window.removeView(riskVeil);FrameLayout veil=new FrameLayout(context);riskVeil=veil;veil.setBackgroundColor(0xB8000000);LinearLayout card=Ui.vertical(context);card.setPadding(Ui.dp(context,18),Ui.dp(context,16),Ui.dp(context,18),Ui.dp(context,16));card.setBackground(Ui.bg(Color.WHITE,17,context));TextView heading=Ui.text(context,result.level==SiteSafetyEngine.Level.DANGER?"高风险链接":"访问前请核对",18,result.level==SiteSafetyEngine.Level.DANGER?Ui.RED:0xFFF59E0B,true);card.addView(heading);TextView detail=Ui.text(context,result.detail+"\n\n"+url,11,Ui.TEXT,false);detail.setPadding(0,Ui.dp(context,8),0,Ui.dp(context,12));card.addView(detail);LinearLayout actions=Ui.horizontal(context);TextView back=action("取消访问",false),proceed=action("确认访问",true);actions.addView(back,new LinearLayout.LayoutParams(0,Ui.dp(context,44),1));actions.addView(proceed,new LinearLayout.LayoutParams(0,Ui.dp(context,44),1));card.addView(actions);FrameLayout.LayoutParams cp=new FrameLayout.LayoutParams(Math.min(Ui.dp(context,330),params.width-Ui.dp(context,30)),-2,Gravity.CENTER);veil.addView(card,cp);window.addView(veil,new FrameLayout.LayoutParams(-1,-1));back.setOnClickListener(v->{window.removeView(veil);riskVeil=null;});String finalUrl=url;proceed.setOnClickListener(v->{if(!tabs.contains(tab)||tab.web==null)return;trustedHosts.add(result.host);window.removeView(veil);riskVeil=null;tab.url=finalUrl;tab.web.loadUrl(finalUrl);});}
    private TextView action(String label,boolean primary){TextView v=Ui.text(context,label,12,primary?Color.WHITE:Ui.TEXT,true);v.setGravity(Gravity.CENTER);v.setBackground(primary?Ui.bg(Ui.GREEN,10,context):Ui.bordered(Color.WHITE,Ui.BORDER,10,context));return v;}
    private boolean handleSpecial(Tab tab,String url){if(!url.startsWith("jian://"))return false;if(url.startsWith("jian://navigation"))requestLoad(tab,"https://go.taolove.top/");else if(url.startsWith("jian://tools")){context.startActivity(new Intent(context,ToolboxActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}else if(url.startsWith("jian://history")||url.startsWith("jian://background")||url.startsWith("jian://bookmarks")||url.startsWith("jian://plugins")||url.startsWith("jian://shortcuts")){BrowserActivity.open(context,url);minimize();}else requestLoad(tab,"jian://home");return true;}
    private void loadActive(String raw){Tab t=activeTab();if(t!=null)requestLoad(t,raw);}
    private void trimWebViews(){List<Tab> loaded=new ArrayList<>();for(Tab t:tabs)if(t.web!=null&&t!=activeTab())loaded.add(t);loaded.sort(Comparator.comparingLong(t->t.lastUsed));while(loaded.size()>1)destroyWeb(loaded.remove(0));}
    private void updateSafety(String url){if(safety==null)return;if(url==null||url.startsWith("jian://")||BrowserUrlRules.isHome(url)){safety.setText("本地");safety.setTextColor(Ui.GREEN);return;}if(!data.safetyEnabled()){safety.setText("未检查");safety.setTextColor(Ui.MUTED);return;}SiteSafetyEngine.Result r=SiteSafetyEngine.analyze(url);int c=r.level==SiteSafetyEngine.Level.OFFICIAL?0xFF2E7D32:r.level==SiteSafetyEngine.Level.SAFE?Ui.GREEN:r.level==SiteSafetyEngine.Level.WARNING?0xFFF59E0B:Ui.RED;safety.setText(r.label);safety.setTextColor(c);}
    private String normalize(String raw){String v=raw==null?"":raw.trim();if(v.isEmpty()||"新标签页".equals(v))return data.browserHome();if("about:blank".equals(v))return v;if(v.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*"))return v;if(v.contains(".")&&!v.contains(" "))return "https://"+v;return SearchEngine.url(data.searchEngine(),v);}
    private boolean isAdUrl(String url){String lower=url.toLowerCase(Locale.ROOT);for(String part:AD_HOST_PARTS)if(lower.contains(part))return true;return false;}
    private String host(String url){try{String h=new URI(url).getHost();return h==null?"网页":h;}catch(Exception e){return "网页";}}
    private String shortTitle(String title){String v=title==null||title.trim().isEmpty()?"网页":title.trim();return v.length()>10?v.substring(0,10):v;}
    private Tab activeTab(){return current>=0&&current<tabs.size()?tabs.get(current):null;}
    private WebView activeWeb(){Tab t=activeTab();return t==null?null:t.web;}
}
