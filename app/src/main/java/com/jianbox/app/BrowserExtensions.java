package com.jianbox.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.security.SecureRandom;

/** WebView-native extension layer shared by the full and floating browsers. */
final class BrowserExtensions {
    private static final Object MEDIA_LOCK = new Object();
    private static final Map<WebView,LinkedHashSet<String>> MEDIA_REQUESTS = new WeakHashMap<>();
    private static String mediaScanScript;

    static boolean shouldBlock(JianData data, String url) {
        return data.blockAds() && data.pluginAdBlock() && BrowserUrlRules.isAdHost(url);
    }

    static void resetMediaRequests(WebView web, String pageUrl) {
        if (web == null) return;
        synchronized (MEDIA_LOCK) { MEDIA_REQUESTS.put(web,new LinkedHashSet<>(MediaUrlExtractor.extract(pageUrl,null))); }
    }

    static void recordMediaRequest(WebView web, WebResourceRequest request) {
        if (web == null || request == null || request.getUrl() == null) return;
        java.util.Set<String> candidates=MediaUrlExtractor.extract(request.getUrl().toString(),request.getRequestHeaders());
        if (candidates.isEmpty()) return;
        synchronized (MEDIA_LOCK) {
            LinkedHashSet<String> urls=MEDIA_REQUESTS.computeIfAbsent(web,key->new LinkedHashSet<>());
            for (String url : candidates) { if (urls.size() >= 160) break; urls.add(url); }
        }
    }

    private static String mediaScanScript(Context context) throws java.io.IOException {
        if (mediaScanScript == null) {
            try (java.io.InputStream input=context.getAssets().open("media-scan.js");
                 java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream()) {
                byte[] buffer=new byte[4096]; int count;
                while ((count=input.read(buffer)) != -1) output.write(buffer,0,count);
                mediaScanScript=output.toString("UTF-8");
            }
        }
        return mediaScanScript;
    }

    private static List<String> capturedMedia(WebView web) {
        synchronized (MEDIA_LOCK) {
            LinkedHashSet<String> urls=MEDIA_REQUESTS.get(web);
            return urls==null?new ArrayList<>():new ArrayList<>(urls);
        }
    }

    static void cleanPage(WebView web, String url, JianData data) {
        BrowserCleaner.clean(web, url, data.blockAds() && data.pluginAdBlock());
    }

    static void installClipboardGuard(Context context, JianData data, WebView web, FrameLayout host) {
        if (web == null || host == null || !data.pluginClipboardGuard()) return;
        web.addJavascriptInterface(new ClipboardBridge(context, web, host), "JianClipboardGuard");
        String js="(function(){if(window.__jianClipboardInstalled)return;window.__jianClipboardInstalled=true;"+
                "window.__jianClipboardPending={};window.__jianClipboardResolve=function(id,ok){let p=window.__jianClipboardPending[id];if(!p)return;delete window.__jianClipboardPending[id];ok?p[0]():p[1](new DOMException('User denied clipboard write','NotAllowedError'));};"+
                "function ask(text){return new Promise((resolve,reject)=>{let id=Date.now().toString(36)+Math.random().toString(36).slice(2);window.__jianClipboardPending[id]=[resolve,reject];JianClipboardGuard.request(id,String(text==null?'':text).slice(0,20000));});}"+
                "try{let clip=navigator.clipboard||{};Object.defineProperty(navigator,'clipboard',{configurable:true,value:{read:clip.read?clip.read.bind(clip):undefined,readText:clip.readText?clip.readText.bind(clip):undefined,writeText:ask,write:function(){return ask('[网页请求写入富文本或文件内容]')}}});}catch(e){}"+
                "let old=document.execCommand?document.execCommand.bind(document):null;document.execCommand=function(cmd){if(String(cmd).toLowerCase()==='copy'){ask(String(getSelection?getSelection():'')).catch(()=>{});return false;}return old?old.apply(document,arguments):false;};})();";
        web.evaluateJavascript(js, ignored -> {});
    }

    private static final class ClipboardBridge {
        private final Context context; private final WebView web; private final FrameLayout host;
        ClipboardBridge(Context context,WebView web,FrameLayout host){this.context=context;this.web=web;this.host=host;}
        @JavascriptInterface public void request(String id,String value){new Handler(Looper.getMainLooper()).post(()->showClipboardPrompt(context,web,host,id,value));}
    }

    private static void showClipboardPrompt(Context context,WebView web,FrameLayout host,String id,String value){
        View old=host.findViewWithTag("jian_clipboard_prompt");if(old!=null)host.removeView(old);
        FrameLayout veil=new FrameLayout(context);veil.setTag("jian_clipboard_prompt");veil.setBackgroundColor(0x99000000);
        LinearLayout card=Ui.vertical(context);card.setPadding(Ui.dp(context,16),Ui.dp(context,14),Ui.dp(context,16),Ui.dp(context,14));card.setBackground(Ui.bg(Ui.SURFACE,16,context));card.setElevation(Ui.dp(context,14));
        LinearLayout head=Ui.horizontal(context);TextView icon=Ui.text(context,"剪",13,Color.WHITE,true);icon.setGravity(Gravity.CENTER);icon.setBackground(Ui.bg(0xFF2563EB,11,context));head.addView(icon,new LinearLayout.LayoutParams(Ui.dp(context,38),Ui.dp(context,38)));LinearLayout copy=Ui.vertical(context);copy.setPadding(Ui.dp(context,10),0,0,0);copy.addView(Ui.text(context,"网页请求写入剪贴板",14,Ui.TEXT,true));String site="当前网页";try{String h=Uri.parse(web.getUrl()).getHost();if(h!=null)site=h;}catch(Exception ignored){}copy.addView(Ui.text(context,site,10,Ui.MUTED,false));head.addView(copy,new LinearLayout.LayoutParams(0,-2,1));card.addView(head);
        String preview=value==null?"":value;if(preview.length()>240)preview=preview.substring(0,240)+"…";TextView body=Ui.text(context,preview.isEmpty()?"网页未提供可预览的文本":preview,11,Ui.TEXT,false);body.setPadding(Ui.dp(context,10),Ui.dp(context,9),Ui.dp(context,10),Ui.dp(context,9));body.setBackground(Ui.bordered(Ui.SOFT_SURFACE,Ui.BORDER,10,context));card.addView(body,new LinearLayout.LayoutParams(-1,-2));Ui.margin(body,0,10,0,10,context);
        LinearLayout actions=Ui.horizontal(context);TextView deny=panelButton(context,"拦截",false),allow=panelButton(context,"允许一次",true);actions.addView(deny,new LinearLayout.LayoutParams(0,Ui.dp(context,42),1));Ui.margin(deny,0,0,7,0,context);actions.addView(allow,new LinearLayout.LayoutParams(0,Ui.dp(context,42),1));card.addView(actions);
        FrameLayout.LayoutParams cp=new FrameLayout.LayoutParams(Math.min(Ui.dp(context,360),Math.max(Ui.dp(context,270),host.getWidth()-Ui.dp(context,28))),-2,Gravity.CENTER);veil.addView(card,cp);host.addView(veil,new FrameLayout.LayoutParams(-1,-1));
        Runnable reject=()->{resolveClipboard(web,id,false);host.removeView(veil);};deny.setOnClickListener(v->reject.run());veil.setOnClickListener(v->{if(v==veil)reject.run();});allow.setOnClickListener(v->{ClipboardManager clipboard=(ClipboardManager)context.getSystemService(Context.CLIPBOARD_SERVICE);clipboard.setPrimaryClip(ClipData.newPlainText("网页内容",value));resolveClipboard(web,id,true);host.removeView(veil);Toast.makeText(context,"已允许本次写入",Toast.LENGTH_SHORT).show();});
    }

    private static void resolveClipboard(WebView web,String id,boolean allowed){String safe=JSONObject.quote(id==null?"":id);web.evaluateJavascript("window.__jianClipboardResolve&&window.__jianClipboardResolve("+safe+","+allowed+")",ignored->{});}

    static final class PageFeatures { boolean video; boolean captions; boolean downloads; boolean github; }

    static HorizontalScrollView toolbar(Context context, JianData data, Action action) { return toolbar(context,data,new PageFeatures(),action); }
    static HorizontalScrollView toolbar(Context context, JianData data, PageFeatures features, Action action) {
        HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setBackgroundColor(0xFFF7FAF8);
        LinearLayout rail = Ui.horizontal(context);
        rail.setPadding(Ui.dp(context, 6), Ui.dp(context, 2), Ui.dp(context, 6), Ui.dp(context, 2));
        if (data.pluginTranslate()) addTool(rail, context, "译", "翻译", action::translate);
        if (data.pluginFind()) addTool(rail, context, "查", "页内查找", action::find);
        if (data.pluginMediaExtractor()) addTool(rail, context, "媒", "媒体", action::media);
        if (data.pluginSourceViewer()) addTool(rail, context, "</>", "源码", action::source);
        if (features.video && data.pluginVideoTools()) { addTool(rail, context, "▶", "倍速", action::speed); if(features.captions)addTool(rail,context,"字","字幕",action::captions); }
        if (data.pluginSuperCopy()) addTool(rail, context, "复", "自由复制", action::superCopy);
        if (data.pluginVisualAdjust()) addTool(rail, context, "调", "视觉", action::visual);
        if (data.pluginPasswordHelper()) addTool(rail, context, "密", "密码", action::password);
        if (features.downloads && data.pluginDownloadLinks()) addTool(rail, context, "↓", "直链", action::downloads);
        if (data.pluginDeveloper()) addTool(rail, context, "{}", "调试", action::developer);
        addTool(rail, context, "＋", "插件", action::manage);
        scroll.addView(rail, new HorizontalScrollView.LayoutParams(-2, -1));
        return scroll;
    }

    static void detect(WebView web, java.util.function.Consumer<PageFeatures> callback){
        if(web==null||callback==null)return;
        String js="(function(){let video=false,captions=false,downloads=false;function scan(w){let d;try{d=w.document}catch(e){return}let vs=Array.from(d.querySelectorAll('video,audio'));video=video||vs.length>0;captions=captions||vs.some(v=>v.textTracks&&v.textTracks.length>0);downloads=downloads||Array.from(d.querySelectorAll('a[href]')).some(a=>/\\.(zip|7z|rar|apk|pdf|epub|mp3|mp4|m3u8|mpd)(\\?|#|$)/i.test(a.href));for(let i=0;i<w.frames.length;i++)try{scan(w.frames[i])}catch(e){}}scan(window);return JSON.stringify({video:video,captions:captions,downloads:downloads,github:/github\\.com$/i.test(location.hostname)||/github\\.com\\//i.test(location.hostname)})})()";
        web.evaluateJavascript(js,raw->{PageFeatures f=new PageFeatures();try{JSONObject o=new JSONObject(decode(raw));f.video=o.optBoolean("video");f.captions=o.optBoolean("captions");f.downloads=o.optBoolean("downloads");f.github=o.optBoolean("github");}catch(Exception ignored){}if(!capturedMedia(web).isEmpty())f.video=true;callback.accept(f);});
    }

    private static void addTool(LinearLayout rail, Context context, String icon, String label, Runnable action) {
        LinearLayout item = Ui.horizontal(context);
        item.setPadding(Ui.dp(context, 6), 0, Ui.dp(context, 8), 0);
        item.setBackground(Ui.bordered(Color.WHITE, 0x335F7964, 10, context));
        TextView glyph = Ui.text(context, icon, 10, Ui.GREEN, true);
        glyph.setGravity(Gravity.CENTER);
        item.addView(glyph, new LinearLayout.LayoutParams(Ui.dp(context, 24), Ui.dp(context, 28)));
        item.addView(Ui.text(context, label, 9, Ui.TEXT, true), new LinearLayout.LayoutParams(-2, Ui.dp(context, 28)));
        item.setOnClickListener(v -> action.run());
        rail.addView(item, new LinearLayout.LayoutParams(-2, Ui.dp(context, 30)));
        Ui.margin(item, 0, 0, 5, 0, context);
    }

    static void translate(Context context, WebView web) {
        if (web == null) return;
        String url = web.getUrl();
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://")) || url.startsWith("https://jian.home")) {
            Toast.makeText(context, "本地页面无需翻译", Toast.LENGTH_SHORT).show(); return;
        }
        String target = "https://translate.google.com/translate?sl=auto&tl=zh-CN&u=" + Uri.encode(url);
        Toast.makeText(context, "页面地址将发送给 Google 翻译服务", Toast.LENGTH_LONG).show();
        web.loadUrl(target);
    }

    static void showFind(Context context, WebView web, FrameLayout host) {
        if (web == null || host == null) return;
        removePanel(host);
        LinearLayout bar = Ui.horizontal(context);
        bar.setTag("jian_extension_panel");
        bar.setPadding(Ui.dp(context, 8), Ui.dp(context, 6), Ui.dp(context, 6), Ui.dp(context, 6));
        bar.setBackground(Ui.bordered(0xFFF9FCFA, Ui.BORDER, 12, context));
        bar.setElevation(Ui.dp(context, 10));
        EditText input = new EditText(context); input.setSingleLine(true); input.setHint("在页面中查找"); input.setTextSize(12);
        input.setTextColor(Ui.TEXT); input.setBackground(Ui.bordered(Color.WHITE, Ui.BORDER, 9, context)); input.setPadding(Ui.dp(context,10),0,Ui.dp(context,10),0);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH); bar.addView(input,new LinearLayout.LayoutParams(0,Ui.dp(context,40),1));
        TextView prev=smallButton(context,"↑"),next=smallButton(context,"↓"),close=smallButton(context,"×");
        bar.addView(prev,new LinearLayout.LayoutParams(Ui.dp(context,38),Ui.dp(context,40)));
        bar.addView(next,new LinearLayout.LayoutParams(Ui.dp(context,38),Ui.dp(context,40)));
        bar.addView(close,new LinearLayout.LayoutParams(Ui.dp(context,38),Ui.dp(context,40)));
        Runnable find=()->web.findAllAsync(input.getText().toString());
        input.setOnEditorActionListener((v,a,e)->{find.run();return true;}); prev.setOnClickListener(v->web.findNext(false)); next.setOnClickListener(v->{if(input.getText().length()>0){find.run();web.findNext(true);}});
        close.setOnClickListener(v->{web.clearMatches();host.removeView(bar);});
        FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(-1,Ui.dp(context,54),Gravity.TOP);lp.setMargins(Ui.dp(context,7),Ui.dp(context,7),Ui.dp(context,7),0);host.addView(bar,lp);input.requestFocus();
    }

    static void extractMedia(Context context, WebView web, FrameLayout host) {
        if (web == null || host == null) return;
        String pageUrl=web.getUrl();
        String script;
        try { script=mediaScanScript(context); }
        catch (java.io.IOException error) { Toast.makeText(context,"媒体扫描器加载失败，请重试",Toast.LENGTH_SHORT).show(); return; }
        web.evaluateJavascript(script, raw -> {
            if (!java.util.Objects.equals(pageUrl,web.getUrl())) return;
            List<String> items=capturedMedia(web);
            try { JSONArray a=new JSONArray(decode(raw)); for(int i=0;i<a.length();i++)items.add(a.getString(i)); } catch(Exception ignored) {}
            showList(context,host,"公开媒体地址",items,"已合并播放器配置、页面元素和实际媒体请求。未找到时请先播放视频，再点刷新。",false,
                    ()->extractMedia(context,web,host));
        });
    }

    static void showVideoSpeed(Context context, WebView web, FrameLayout host){
        if(web==null||host==null)return;removePanel(host);LinearLayout bar=choiceBar(context,host,"视频播放",new String[]{"0.5×","1×","1.25×","1.5×","2×","3×"},value->{String speed=value.substring(0,value.length()-1);String js="(function(){let n=0;function scan(w){let d;try{d=w.document}catch(e){return}d.querySelectorAll('video,audio').forEach(v=>{v.playbackRate="+speed+";n++});for(let i=0;i<w.frames.length;i++)try{scan(w.frames[i])}catch(e){}}scan(window);return n})()";web.evaluateJavascript(js,raw->{boolean changed=raw!=null&&raw.matches("[1-9][0-9]*");Toast.makeText(context,changed?"已设置播放速度 "+value:"未找到可控制的播放器，请尝试网页自带倍速",Toast.LENGTH_SHORT).show();});});TextView captions=smallButton(context,"字幕");captions.setTextSize(11);captions.setOnClickListener(v->enableCaptions(context,web));bar.addView(captions,new LinearLayout.LayoutParams(Ui.dp(context,48),Ui.dp(context,32)));addPanel(context,host,bar);
    }

    static void enableCaptions(Context context, WebView web){if(web==null)return;String js="(function(){let n=0;function scan(w){let d;try{d=w.document}catch(e){return}d.querySelectorAll('video').forEach(v=>Array.from(v.textTracks||[]).forEach(t=>{t.mode='showing';n++}));for(let i=0;i<w.frames.length;i++)try{scan(w.frames[i])}catch(e){}}scan(window);return n})()";web.evaluateJavascript(js,raw->{try{int n=Integer.parseInt(raw);Toast.makeText(context,n>0?"已启用网页字幕轨":"此页面没有可用字幕轨",Toast.LENGTH_SHORT).show();}catch(Exception e){Toast.makeText(context,"未发现可用字幕轨",Toast.LENGTH_SHORT).show();}});}

    static void enableSuperCopy(Context context, WebView web){if(web==null)return;web.evaluateJavascript("(function(){document.documentElement.style.userSelect='text';document.body.style.userSelect='text';document.oncopy=null;document.onselectstart=null;document.querySelectorAll('*').forEach(e=>{e.style.userSelect='text';e.oncopy=null;e.onselectstart=null});return true})()",ignored->Toast.makeText(context,"已允许选择和复制页面文字",Toast.LENGTH_SHORT).show());}

    static void showVisual(Context context,JianData data,WebView web,FrameLayout host){
        if(web==null||host==null)return;removePanel(host);
        FrameLayout veil=new FrameLayout(context);veil.setTag("jian_extension_panel");veil.setBackgroundColor(0x88000000);
        LinearLayout card=Ui.vertical(context);card.setPadding(Ui.dp(context,14),Ui.dp(context,12),Ui.dp(context,14),Ui.dp(context,12));card.setBackground(Ui.bg(Ui.SURFACE,16,context));card.setElevation(Ui.dp(context,14));
        LinearLayout head=Ui.horizontal(context);LinearLayout title=Ui.vertical(context);title.addView(Ui.text(context,"页面视觉调节",15,Ui.TEXT,true));title.addView(Ui.text(context,"快速预设保留在首层，详细参数按需展开",9,Ui.MUTED,false));head.addView(title,new LinearLayout.LayoutParams(0,-2,1));TextView fold=smallButton(context,"详细"),close=smallButton(context,"×");fold.setTextSize(10);head.addView(fold,new LinearLayout.LayoutParams(Ui.dp(context,48),Ui.dp(context,36)));head.addView(close,new LinearLayout.LayoutParams(Ui.dp(context,36),Ui.dp(context,36)));card.addView(head);
        LinearLayout presets=Ui.horizontal(context);String[][] preset={{"标准","standard"},{"黑色","dark"},{"白色","light"},{"护眼","eye"}};for(String[] p:preset){TextView b=panelButton(context,p[0],p[1].equals(data.visualTheme()));b.setTextSize(10);presets.addView(b,new LinearLayout.LayoutParams(0,Ui.dp(context,36),1));Ui.margin(b,0,0,5,0,context);b.setOnClickListener(v->{data.visualTheme(p[1]);if("standard".equals(p[1])){data.visualBrightness(100);data.visualContrast(100);data.visualSaturation(100);data.visualGray(0);}else if("dark".equals(p[1])){data.visualBrightness(82);data.visualContrast(112);data.visualSaturation(92);data.visualGray(0);}else if("light".equals(p[1])){data.visualBrightness(112);data.visualContrast(96);data.visualSaturation(90);data.visualGray(0);}else{data.visualBrightness(92);data.visualContrast(100);data.visualSaturation(88);data.visualGray(0);}data.addVisualHistory(p[0]+"主题");applyVisual(web,data);showVisual(context,data,web,host);});}card.addView(presets);
        LinearLayout details=Ui.vertical(context);details.setVisibility(View.GONE);
        details.addView(visualSlider(context,"亮度",60,140,data.visualBrightness(),v->{data.visualBrightness(v);applyVisual(web,data);}));details.addView(visualSlider(context,"对比度",60,160,data.visualContrast(),v->{data.visualContrast(v);applyVisual(web,data);}));details.addView(visualSlider(context,"饱和度",0,180,data.visualSaturation(),v->{data.visualSaturation(v);applyVisual(web,data);}));details.addView(visualSlider(context,"灰度",0,100,data.visualGray(),v->{data.visualGray(v);applyVisual(web,data);}));TextView history=Ui.text(context,"最近："+visualHistory(data),9,Ui.MUTED,false);history.setPadding(0,Ui.dp(context,5),0,0);details.addView(history);card.addView(details);
        fold.setOnClickListener(v->{boolean hide=details.getVisibility()==View.VISIBLE;details.setVisibility(hide?View.GONE:View.VISIBLE);fold.setText(hide?"详细":"收起");});close.setOnClickListener(v->host.removeView(veil));FrameLayout.LayoutParams cp=new FrameLayout.LayoutParams(Math.min(Ui.dp(context,400),Math.max(Ui.dp(context,290),host.getWidth()-Ui.dp(context,22))),-2,Gravity.TOP|Gravity.CENTER_HORIZONTAL);cp.topMargin=Ui.dp(context,8);veil.addView(card,cp);host.addView(veil,new FrameLayout.LayoutParams(-1,-1));applyVisual(web,data);
    }

    private static View visualSlider(Context context,String name,int min,int max,int value,java.util.function.IntConsumer action){LinearLayout row=Ui.vertical(context);LinearLayout label=Ui.horizontal(context);TextView title=Ui.text(context,name,11,Ui.TEXT,true),number=Ui.text(context,String.format(Locale.ROOT,"%d%%",value),10,Ui.MUTED,false);label.addView(title,new LinearLayout.LayoutParams(0,Ui.dp(context,24),1));label.addView(number);row.addView(label);SeekBar bar=new SeekBar(context);bar.setMax(max-min);bar.setProgress(Math.max(0,Math.min(max-min,value-min)));bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar s,int p,boolean user){int v=min+p;number.setText(String.format(Locale.ROOT,"%d%%",v));if(user)action.accept(v);}public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){}});row.addView(bar,new LinearLayout.LayoutParams(-1,Ui.dp(context,30)));return row;}
    private static void applyVisual(WebView web,JianData data){String theme=data.visualTheme();String colors;if("dark".equals(theme))colors="document.documentElement.style.background='#111';document.body.style.backgroundColor='#111';document.body.style.color='#eee';";else if("light".equals(theme))colors="document.documentElement.style.background='#fff';document.body.style.backgroundColor='#fff';document.body.style.color='#171717';";else if("eye".equals(theme))colors="document.documentElement.style.background='#eef3e2';document.body.style.backgroundColor='#eef3e2';";else colors="document.documentElement.style.background='';document.body.style.backgroundColor='';document.body.style.color='';";String filter=String.format(Locale.ROOT,"brightness(%.2f) contrast(%.2f) saturate(%.2f) grayscale(%.2f)",data.visualBrightness()/100f,data.visualContrast()/100f,data.visualSaturation()/100f,data.visualGray()/100f);web.evaluateJavascript("(function(){document.documentElement.style.filter='"+filter+"';"+colors+"return true})()",ignored->{});}
    private static String visualHistory(JianData data){try{JSONArray a=new JSONArray(data.visualHistory());List<String> values=new ArrayList<>();for(int i=0;i<a.length();i++)values.add(a.optString(i));return values.isEmpty()?"暂无记录":android.text.TextUtils.join(" · ",values);}catch(Exception e){return "暂无记录";}}

    static void showPassword(Context context, FrameLayout host){if(host==null)return;String chars="ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%*_-";SecureRandom random=new SecureRandom();StringBuilder value=new StringBuilder();for(int i=0;i<20;i++)value.append(chars.charAt(random.nextInt(chars.length())));showList(context,host,"本机密码生成器",java.util.Collections.singletonList(value.toString()),"仅在本机随机生成；简盒不会读取、保存或自动粘贴账号密码。点击内容即可复制。",true);}

    static void extractDownloads(Context context, WebView web, FrameLayout host){if(web==null)return;String js="(function(){const s=new Set();document.querySelectorAll('a[href]').forEach(a=>{if(/\\.(zip|7z|rar|apk|pdf|epub|mp3|mp4|m3u8)(\\?|#|$)/i.test(a.href))try{s.add(new URL(a.href,location.href).href)}catch(e){}});return JSON.stringify(Array.from(s).slice(0,80))})()";web.evaluateJavascript(js,raw->{List<String> items=new ArrayList<>();try{JSONArray a=new JSONArray(decode(raw));for(int i=0;i<a.length();i++)items.add(a.getString(i));}catch(Exception ignored){}showList(context,host,"公开下载直链",items,"只列出当前页面公开的文件链接；不会绕过登录、网盘权限或 DRM。",false);});}

    static void showDeveloper(Context context,WebView web,FrameLayout host){
        BrowserDeveloperTools.showInspector(context,web,host);
    }
    private static View developerStat(Context context,String label,String value){LinearLayout tile=Ui.vertical(context);tile.setPadding(Ui.dp(context,10),Ui.dp(context,7),Ui.dp(context,10),Ui.dp(context,6));tile.setBackground(Ui.bordered(Ui.SOFT_SURFACE,Ui.BORDER,10,context));tile.addView(Ui.text(context,value,13,Ui.TEXT,true));tile.addView(Ui.text(context,label,9,Ui.MUTED,false));return tile;}

    private static LinearLayout choiceBar(Context context,FrameLayout host,String title,String[] labels,java.util.function.Consumer<String> action){LinearLayout bar=Ui.horizontal(context);bar.setTag("jian_extension_panel");bar.setPadding(Ui.dp(context,7),Ui.dp(context,4),Ui.dp(context,5),Ui.dp(context,4));bar.setBackground(Ui.bordered(0xFFF9FCFA,Ui.BORDER,11,context));TextView heading=Ui.text(context,title,10,Ui.MUTED,true);heading.setGravity(Gravity.CENTER);bar.addView(heading,new LinearLayout.LayoutParams(Ui.dp(context,48),Ui.dp(context,30)));for(String label:labels){TextView b=smallButton(context,label);b.setTextSize(10);b.setBackground(Ui.bordered(Color.WHITE,Ui.BORDER,8,context));b.setOnClickListener(v->action.accept(label));bar.addView(b,new LinearLayout.LayoutParams(Ui.dp(context,48),Ui.dp(context,30)));Ui.margin(b,0,0,4,0,context);}TextView close=smallButton(context,"×");close.setOnClickListener(v->removePanel(host));bar.addView(close,new LinearLayout.LayoutParams(Ui.dp(context,30),Ui.dp(context,30)));return bar;}
    private static void addPanel(Context context,FrameLayout host,View panel){panel.setTag(null);HorizontalScrollView wrapper=new HorizontalScrollView(context);wrapper.setTag("jian_extension_panel");wrapper.setHorizontalScrollBarEnabled(false);wrapper.addView(panel,new HorizontalScrollView.LayoutParams(-2,-1));FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(-1,Ui.dp(context,40),Gravity.TOP);lp.setMargins(Ui.dp(context,5),Ui.dp(context,5),Ui.dp(context,5),0);host.addView(wrapper,lp);}

    static void viewSource(Context context, WebView web, FrameLayout host) {
        BrowserDeveloperTools.showSources(context,web,host);
    }

    private static List<String> jsonStrings(JSONArray array){List<String> out=new ArrayList<>();if(array!=null)for(int i=0;i<array.length();i++)out.add(array.optString(i));return out;}
    private static String formatHtml(String value){if(value==null)return"";return value.replace("><","&gt;\n&lt;").replace("<","\n<").replace(">",">\n").replace("\n\n","\n");}

    private static void showList(Context context, FrameLayout host, String title, List<String> rawItems, String hint, boolean sourceMode) {
        showList(context,host,title,rawItems,hint,sourceMode,null);
    }

    private static void showList(Context context, FrameLayout host, String title, List<String> rawItems, String hint, boolean sourceMode, Runnable refresh) {
        if(host==null)return;removePanel(host);
        LinkedHashSet<String> unique=new LinkedHashSet<>(rawItems);List<String> items=new ArrayList<>(unique);
        FrameLayout veil=new FrameLayout(context);veil.setTag("jian_extension_panel");veil.setBackgroundColor(0x99000000);
        LinearLayout card=Ui.vertical(context);card.setPadding(Ui.dp(context,14),Ui.dp(context,12),Ui.dp(context,14),Ui.dp(context,12));card.setBackground(Ui.bg(Ui.SURFACE,14,context));
        LinearLayout head=Ui.horizontal(context);head.addView(Ui.text(context,title,15,Ui.TEXT,true),new LinearLayout.LayoutParams(0,Ui.dp(context,38),1));TextView close=smallButton(context,"×");head.addView(close,new LinearLayout.LayoutParams(Ui.dp(context,40),Ui.dp(context,38)));card.addView(head);
        if(refresh!=null){TextView reload=panelButton(context,"刷新媒体地址",false);reload.setOnClickListener(v->refresh.run());card.addView(reload,new LinearLayout.LayoutParams(-1,Ui.dp(context,36)));}
        TextView note=Ui.text(context,hint,10,Ui.MUTED,false);note.setPadding(0,0,0,Ui.dp(context,7));card.addView(note);
        ScrollView scroll=new ScrollView(context);LinearLayout list=Ui.vertical(context);scroll.addView(list,new ScrollView.LayoutParams(-1,-2));
        if(items.isEmpty()){TextView empty=Ui.text(context,"当前页面未发现可提取内容",12,Ui.MUTED,false);empty.setGravity(Gravity.CENTER);list.addView(empty,new LinearLayout.LayoutParams(-1,Ui.dp(context,100)));}
        else for(String item:items){TextView row=Ui.text(context,item,sourceMode?9:10,Ui.TEXT,false);row.setTextIsSelectable(sourceMode);row.setPadding(Ui.dp(context,9),Ui.dp(context,8),Ui.dp(context,9),Ui.dp(context,8));row.setBackground(Ui.bordered(0xFFF7FAF8,0x335F7964,9,context));row.setOnClickListener(v->{copy(context,item);Toast.makeText(context,"已复制",Toast.LENGTH_SHORT).show();});list.addView(row,new LinearLayout.LayoutParams(-1,-2));Ui.margin(row,0,0,0,6,context);}
        card.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        if(!items.isEmpty()){TextView copy=Ui.text(context,sourceMode?"复制全部源码":"复制全部地址",12,Color.WHITE,true);copy.setGravity(Gravity.CENTER);copy.setBackground(Ui.bg(Ui.GREEN,10,context));copy.setOnClickListener(v->{copy(context,android.text.TextUtils.join("\n",items));Toast.makeText(context,"已复制",Toast.LENGTH_SHORT).show();});card.addView(copy,new LinearLayout.LayoutParams(-1,Ui.dp(context,42)));Ui.margin(copy,0,8,0,0,context);}
        FrameLayout.LayoutParams cp=new FrameLayout.LayoutParams(-1,-1);cp.setMargins(Ui.dp(context,10),Ui.dp(context,10),Ui.dp(context,10),Ui.dp(context,10));veil.addView(card,cp);close.setOnClickListener(v->host.removeView(veil));host.addView(veil,new FrameLayout.LayoutParams(-1,-1));
    }

    private static TextView smallButton(Context context,String value){TextView v=Ui.text(context,value,17,Ui.TEXT,true);v.setGravity(Gravity.CENTER);v.setBackground(Ui.bg(Color.TRANSPARENT,9,context));return v;}
    private static TextView panelButton(Context context,String value,boolean primary){TextView v=Ui.text(context,value,11,primary?Color.WHITE:Ui.TEXT,true);v.setGravity(Gravity.CENTER);v.setBackground(primary?Ui.bg(Ui.GREEN,9,context):Ui.bordered(Ui.SURFACE,Ui.BORDER,9,context));return v;}
    private static String decode(String raw) throws Exception { return new JSONArray("["+raw+"]").getString(0); }
    private static void copy(Context context,String value){ClipboardManager c=(ClipboardManager)context.getSystemService(Context.CLIPBOARD_SERVICE);c.setPrimaryClip(ClipData.newPlainText("简盒",value));}
    private static void removePanel(FrameLayout host){View old=host.findViewWithTag("jian_extension_panel");if(old!=null)host.removeView(old);}

    interface Action { void translate(); void find(); void media(); void source(); void speed(); void captions(); void superCopy(); void visual(); void password(); void downloads(); void developer(); void manage(); }
    private BrowserExtensions() {}
}
