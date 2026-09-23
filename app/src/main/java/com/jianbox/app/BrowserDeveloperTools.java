package com.jianbox.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.IDN;
import java.net.InetAddress;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Lightweight, WebView-focused developer panels. Network lookups only run after the user opens the panel. */
final class BrowserDeveloperTools {
    private static final String PANEL_TAG = "jian_extension_panel";
    private static final int MAX_RESPONSE = 1024 * 1024;
    private static final ExecutorService IO = Executors.newFixedThreadPool(4);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static void showInspector(Context context, WebView web, FrameLayout host) {
        if (web == null || host == null) return;
        String js = "(function(){let r=performance.getEntriesByType('resource')||[],n=performance.getEntriesByType('navigation')[0]||{};" +
                "return JSON.stringify({url:location.href,title:document.title,host:location.hostname,protocol:location.protocol," +
                "viewport:innerWidth+'x'+innerHeight,dpr:devicePixelRatio||1,dom:document.querySelectorAll('*').length," +
                "resources:r.length,transfer:Math.round(r.reduce((s,x)=>s+(x.transferSize||0),0)/1024),images:document.images.length," +
                "media:document.querySelectorAll('video,audio').length,links:document.querySelectorAll('a[href]').length," +
                "forms:document.forms.length,inputs:document.querySelectorAll('input,textarea,select').length," +
                "styles:document.styleSheets.length,scripts:document.scripts.length,meta:document.querySelectorAll('meta').length," +
                "cookies:navigator.cookieEnabled,text:(document.body&&document.body.innerText||'').length," +
                "domReady:Math.round(n.domContentLoadedEventEnd||0),load:Math.round(n.loadEventEnd||0)," +
                "response:Math.round(n.responseEnd||0),redirects:n.redirectCount||0})})()";
        web.evaluateJavascript(js, raw -> {
            try {
                JSONObject summary = new JSONObject(decode(raw));
                showInspectorPanel(context, host, summary);
            } catch (Exception e) {
                Toast.makeText(context, "无法读取当前页面的调试信息", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private static void showInspectorPanel(Context context, FrameLayout host, JSONObject summary) {
        removeOld(host);
        FrameLayout veil = veil(context);
        LinearLayout card = panelCard(context);
        card.addView(header(context, host, veil, "开发者工具", summary.optString("host", "当前页面")));

        LinearLayout tabs = Ui.horizontal(context);
        FrameLayout body = new FrameLayout(context);
        String[] labels = {"概览", "DNS", "域名", "运营商"};
        TextView[] buttons = new TextView[labels.length];
        Runnable[] renders = new Runnable[labels.length];
        NetworkReport report = new NetworkReport(summary.optString("host"));
        renders[0] = () -> renderOverview(context, body, summary);
        renders[1] = () -> renderNetworkTab(context, body, report, NetworkSection.DNS);
        renders[2] = () -> renderNetworkTab(context, body, report, NetworkSection.DOMAIN);
        renders[3] = () -> renderNetworkTab(context, body, report, NetworkSection.CARRIER);
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            buttons[i] = tab(context, labels[i], i == 0);
            buttons[i].setOnClickListener(v -> {
                for (int j = 0; j < buttons.length; j++) styleTab(context, buttons[j], j == index);
                renders[index].run();
            });
            tabs.addView(buttons[i], new LinearLayout.LayoutParams(0, Ui.dp(context, 34), 1));
            if (i < labels.length - 1) Ui.margin(buttons[i], 0, 0, 5, 0, context);
        }
        card.addView(tabs, new LinearLayout.LayoutParams(-1, Ui.dp(context, 38)));
        card.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        renderOverview(context, body, summary);
        attachPanel(context, host, veil, card);
    }

    private static void renderOverview(Context context, FrameLayout body, JSONObject o) {
        body.removeAllViews();
        ScrollView scroll = new ScrollView(context);
        LinearLayout content = Ui.vertical(context);
        content.setPadding(0, Ui.dp(context, 8), 0, Ui.dp(context, 10));
        String[][] sections = {
                {"页面", "地址", o.optString("url"), "协议", o.optString("protocol"), "视口 / DPR", o.optString("viewport") + " / " + o.optString("dpr")},
                {"结构", "DOM 节点", String.valueOf(o.optInt("dom")), "脚本 / 样式", o.optInt("scripts") + " / " + o.optInt("styles"), "图片 / 媒体", o.optInt("images") + " / " + o.optInt("media")},
                {"网络与性能", "资源请求", String.valueOf(o.optInt("resources")), "传输量", o.optInt("transfer") + " KB", "响应 / DOM / 完成", o.optInt("response") + " / " + o.optInt("domReady") + " / " + o.optInt("load") + " ms"},
                {"交互", "链接", String.valueOf(o.optInt("links")), "表单 / 输入", o.optInt("forms") + " / " + o.optInt("inputs"), "Cookie", o.optBoolean("cookies") ? "浏览器已启用" : "浏览器已停用"}
        };
        for (String[] section : sections) {
            content.addView(sectionTitle(context, section[0]));
            for (int i = 1; i < section.length; i += 2) content.addView(detailRow(context, section[i], section[i + 1]));
        }
        TextView note = Ui.text(context, "DNS、域名和运营商页会在您主动点开时，将当前域名或解析后的 IP 发送给对应的公开查询服务。", 9, Ui.MUTED, false);
        note.setPadding(Ui.dp(context, 4), Ui.dp(context, 10), Ui.dp(context, 4), Ui.dp(context, 4));
        content.addView(note);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        body.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
    }

    private static void renderNetworkTab(Context context, FrameLayout body, NetworkReport report, NetworkSection section) {
        body.removeAllViews();
        report.activeSection = section;
        if (!isPublicHost(report.host)) {
            body.addView(message(context, "当前页面没有可查询的公网域名。", false), new FrameLayout.LayoutParams(-1, -1));
            return;
        }
        if (report.loaded) {
            renderNetworkResult(context, body, report, section);
            return;
        }
        if (!report.loading) {
            report.loading = true;
            IO.execute(() -> {
                loadNetworkReport(report);
                report.loading = false;
                report.loaded = true;
                MAIN.post(() -> renderNetworkResult(context, body, report, report.activeSection));
            });
        }
        LinearLayout loading = Ui.vertical(context);
        loading.setGravity(Gravity.CENTER);
        ProgressBar bar = new ProgressBar(context);
        loading.addView(bar, new LinearLayout.LayoutParams(Ui.dp(context, 38), Ui.dp(context, 38)));
        TextView label = Ui.text(context, "正在查询 " + report.host + "\n首次查询可能需要几秒", 11, Ui.MUTED, false);
        label.setGravity(Gravity.CENTER);
        label.setPadding(0, Ui.dp(context, 10), 0, 0);
        loading.addView(label);
        body.addView(loading, new FrameLayout.LayoutParams(-1, -1));
    }

    private static void renderNetworkResult(Context context, FrameLayout body, NetworkReport report, NetworkSection section) {
        if (body == null || body.getParent() == null) return;
        body.removeAllViews();
        ScrollView scroll = new ScrollView(context);
        LinearLayout content = Ui.vertical(context);
        content.setPadding(0, Ui.dp(context, 8), 0, Ui.dp(context, 10));
        if (section == NetworkSection.DNS) renderDns(context, content, report);
        else if (section == NetworkSection.DOMAIN) renderDomain(context, content, report);
        else renderCarrier(context, content, report);
        TextView retry = smallAction(context, "重新查询");
        retry.setOnClickListener(v -> { report.reset(); renderNetworkTab(context, body, report, section); });
        content.addView(retry, new LinearLayout.LayoutParams(-1, Ui.dp(context, 36)));
        TextView stamp = Ui.text(context, "查询时间：" + report.time + "  ·  点击任意信息行可复制", 9, Ui.MUTED, false);
        stamp.setPadding(Ui.dp(context, 4), Ui.dp(context, 10), 0, 0);
        content.addView(stamp);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        body.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
    }

    private static void renderDns(Context context, LinearLayout content, NetworkReport report) {
        content.addView(sectionTitle(context, "系统 DNS + 阿里公共 DNS · 自动备用"));
        if (report.dns.isEmpty()) content.addView(message(context, valueOr(report.dnsError, "未返回可显示的 DNS 记录"), true));
        else for (DnsRecord record : report.dns) {
            String value = record.value + (record.ttl > 0 ? "\nTTL " + record.ttl + " 秒" : "");
            content.addView(detailRow(context, record.type, value));
        }
    }

    private static void renderDomain(Context context, LinearLayout content, NetworkReport report) {
        content.addView(sectionTitle(context, "RDAP 域名注册数据"));
        if (report.rdap == null) {
            content.addView(message(context, valueOr(report.rdapError, "没有查询到公开的域名注册信息"), true));
            return;
        }
        JSONObject r = report.rdap;
        addIfPresent(context, content, "查询域名", report.registeredDomain);
        addIfPresent(context, content, "标准域名", r.optString("ldhName"));
        addIfPresent(context, content, "Unicode 域名", r.optString("unicodeName"));
        addIfPresent(context, content, "注册标识", r.optString("handle"));
        addIfPresent(context, content, "状态", join(r.optJSONArray("status")));
        addIfPresent(context, content, "注册商", registrar(r.optJSONArray("entities")));
        addIfPresent(context, content, "注册时间", eventDate(r, "registration"));
        addIfPresent(context, content, "更新时间", eventDate(r, "last changed"));
        addIfPresent(context, content, "到期时间", eventDate(r, "expiration"));
        addIfPresent(context, content, "名称服务器", nameservers(r.optJSONArray("nameservers")));
        JSONObject secure = r.optJSONObject("secureDNS");
        if (secure != null) addIfPresent(context, content, "DNSSEC", secure.optBoolean("delegationSigned") ? "已签名" : "未签名 / 未公开");
        addIfPresent(context, content, "WHOIS 服务", r.optString("port43"));
        TextView source = Ui.text(context, "RDAP 是注册局公开数据。隐私代理、注册局更新延迟会影响结果；此处不把推测信息当作备案结论。", 9, Ui.MUTED, false);
        source.setPadding(Ui.dp(context, 4), Ui.dp(context, 8), Ui.dp(context, 4), 0);
        content.addView(source);
    }

    private static void renderCarrier(Context context, LinearLayout content, NetworkReport report) {
        content.addView(sectionTitle(context, "ipwho.is · IP / ASN / 网络提供商"));
        if (report.ipInfo == null) {
            content.addView(message(context, valueOr(report.ipError, "没有查询到公网 IP 信息"), true));
            return;
        }
        JSONObject ip = report.ipInfo;
        JSONObject connection = ip.optJSONObject("connection");
        JSONObject timezone = ip.optJSONObject("timezone");
        addIfPresent(context, content, "解析 IP", ip.optString("ip", report.primaryIp));
        if (connection != null) {
            addIfPresent(context, content, "ASN", connection.optString("asn"));
            addIfPresent(context, content, "运营商 / ISP", connection.optString("isp"));
            addIfPresent(context, content, "网络组织", connection.optString("org"));
            addIfPresent(context, content, "网络域名", connection.optString("domain"));
        }
        addIfPresent(context, content, "IP 类型", ip.optString("type"));
        addIfPresent(context, content, "国家 / 地区", joinParts(ip.optString("country"), ip.optString("region"), ip.optString("city")));
        addIfPresent(context, content, "经纬度", coordinate(ip));
        if (timezone != null) addIfPresent(context, content, "时区", joinParts(timezone.optString("id"), timezone.optString("utc")));
        TextView source = Ui.text(context, "IP 地理位置和运营商归属来自公开数据库，通常只能定位到网络出口或机房，不能代表用户的精确位置。", 9, Ui.MUTED, false);
        source.setPadding(Ui.dp(context, 4), Ui.dp(context, 8), Ui.dp(context, 4), 0);
        content.addView(source);
    }

    static void showSources(Context context, WebView web, FrameLayout host) {
        if (web == null || host == null) return;
        String js = "(function(){try{let f=[],seen=new Set(),add=(type,name,url,content,meta)=>{let k=type+'|'+url+'|'+name;if(seen.has(k)||f.length>=80)return;seen.add(k);f.push({type:type,name:name,url:url||'',content:String(content||'').slice(0,40000),meta:meta||''})};" +
                "let root=document.documentElement.cloneNode(true);[...root.querySelectorAll('*')].slice(0,6000).forEach(n=>{[...n.attributes].forEach(a=>{let v=a.value||'';if(v.length>512||/^(data|blob):/i.test(v))n.setAttribute(a.name,'[大型内嵌资源已省略]')});if(n.tagName==='SCRIPT'&&(n.textContent||'').length>40000)n.textContent='/* 大型内联脚本已省略 */'});" +
                "add('HTML',(document.title||'document')+'.html',location.href,root.outerHTML.slice(0,160000),'安全 DOM 快照');" +
                "[...document.querySelectorAll('link[rel=stylesheet]')].forEach((x,i)=>add('CSS',(x.href.split('/').pop()||('style-'+(i+1)+'.css')).split('?')[0],x.href,'','stylesheet'));" +
                "[...document.querySelectorAll('style')].forEach((x,i)=>add('CSS','inline-style-'+(i+1)+'.css',location.href+'#style-'+(i+1),x.textContent,'inline'));" +
                "[...document.scripts].forEach((x,i)=>add('JS',x.src?(x.src.split('/').pop()||('script-'+(i+1)+'.js')).split('?')[0]:'inline-script-'+(i+1)+'.js',x.src||location.href+'#script-'+(i+1),x.src?'':x.textContent,x.src?'script':'inline'));" +
                "let manifest=document.querySelector('link[rel=manifest]');if(manifest)add('Manifest',(manifest.href.split('/').pop()||'manifest.json').split('?')[0],manifest.href,'','manifest');" +
                "(performance.getEntriesByType('resource')||[]).forEach((x,i)=>{let t=(x.initiatorType||'resource').toLowerCase(),u=x.name||'',type=/css/i.test(t)||/\\.css(?:[?#]|$)/i.test(u)?'CSS':/script/i.test(t)||/\\.m?js(?:[?#]|$)/i.test(u)?'JS':/img|image/i.test(t)||/\\.(png|jpe?g|webp|gif|svg|ico)(?:[?#]|$)/i.test(u)?'图片':/font/i.test(t)||/\\.(woff2?|ttf|otf)(?:[?#]|$)/i.test(u)?'字体':/media|video|audio/i.test(t)?'媒体':'其他资源';let n=(u.split('/').pop()||type+'-'+(i+1)).split('?')[0];add(type,n,u,'',t+' · '+Math.round(x.duration||0)+' ms')});" +
                "return JSON.stringify({url:location.href,files:f})}catch(e){return JSON.stringify({error:String(e),files:[]})}})()";
        web.evaluateJavascript(js, raw -> {
            try {
                JSONObject result = new JSONObject(decode(raw));
                if (!result.optString("error").isEmpty()) throw new IllegalStateException(result.optString("error"));
                JSONArray values = result.optJSONArray("files");
                List<SourceFile> files = new ArrayList<>();
                if (values != null) for (int i = 0; i < values.length(); i++) {
                    JSONObject o = values.optJSONObject(i);
                    if (o != null) files.add(new SourceFile(o.optString("type", "其他资源"), o.optString("name", "未命名"), o.optString("url"), o.optString("content"), o.optString("meta")));
                }
                showSourcePanel(context, web, host, files);
            } catch (Exception e) {
                Toast.makeText(context, "当前页面没有返回可读取的源码结构", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private static void showSourcePanel(Context context, WebView web, FrameLayout host, List<SourceFile> files) {
        removeOld(host);
        Collections.sort(files, Comparator.comparingInt(f -> typeOrder(f.type)));
        FrameLayout veil = veil(context);
        LinearLayout card = panelCard(context);
        card.addView(header(context, host, veil, "Sources", files.size() + " 个文件与资源"));

        LinearLayout workspace = Ui.horizontal(context);
        workspace.setGravity(Gravity.TOP);
        ScrollView treeScroll = new ScrollView(context);
        LinearLayout tree = Ui.vertical(context);
        tree.setPadding(0, Ui.dp(context, 5), Ui.dp(context, 7), Ui.dp(context, 8));
        treeScroll.addView(tree, new ScrollView.LayoutParams(-1, -2));

        LinearLayout editor = Ui.vertical(context);
        editor.setPadding(Ui.dp(context, 8), Ui.dp(context, 5), 0, 0);
        LinearLayout editorHead = Ui.horizontal(context);
        LinearLayout fileTitle = Ui.vertical(context);
        TextView name = Ui.text(context, "选择文件", 11, Ui.TEXT, true);
        TextView path = Ui.text(context, "从左侧文件树选择 HTML、CSS 或 JavaScript", 8, Ui.MUTED, false);
        path.setSingleLine(true);
        fileTitle.addView(name);
        fileTitle.addView(path);
        editorHead.addView(fileTitle, new LinearLayout.LayoutParams(0, Ui.dp(context, 38), 1));
        TextView copy = smallAction(context, "复制");
        copy.setEnabled(false);
        editorHead.addView(copy, new LinearLayout.LayoutParams(Ui.dp(context, 54), Ui.dp(context, 34)));
        editor.addView(editorHead);

        TextView code = Ui.text(context, "// 选择左侧文件以查看内容\n// 外链 CSS、JavaScript 和清单文件会在点选时按需请求", 10, 0xFFE5E7EB, false);
        code.setTypeface(Typeface.MONOSPACE);
        code.setTextIsSelectable(true);
        code.setGravity(Gravity.TOP | Gravity.LEFT);
        code.setPadding(Ui.dp(context, 10), Ui.dp(context, 9), Ui.dp(context, 10), Ui.dp(context, 12));
        code.setBackground(Ui.bg(0xFF151922, 8, context));
        HorizontalScrollView horizontal = new HorizontalScrollView(context);
        horizontal.setFillViewport(true);
        horizontal.addView(code, new HorizontalScrollView.LayoutParams(-2, -2));
        ScrollView vertical = new ScrollView(context);
        vertical.addView(horizontal, new ScrollView.LayoutParams(-1, -2));
        editor.addView(vertical, new LinearLayout.LayoutParams(-1, 0, 1));

        int treeWidth = Ui.dp(context, context.getResources().getConfiguration().smallestScreenWidthDp >= 600 ? 180 : 118);
        workspace.addView(treeScroll, new LinearLayout.LayoutParams(treeWidth, -1));
        workspace.addView(editor, new LinearLayout.LayoutParams(0, -1, 1));
        card.addView(workspace, new LinearLayout.LayoutParams(-1, 0, 1));

        String[] currentText = {""};
        copy.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("页面源码", currentText[0]));
            Toast.makeText(context, "已复制当前文件内容", Toast.LENGTH_SHORT).show();
        });
        String previousType = null;
        for (SourceFile file : files) {
            if (!file.type.equals(previousType)) {
                tree.addView(treeGroup(context, file.type));
                previousType = file.type;
            }
            TextView row = treeFile(context, file);
            tree.addView(row, new LinearLayout.LayoutParams(-1, Ui.dp(context, 38)));
            row.setOnClickListener(v -> selectSource(context, web, file, name, path, code, copy, currentText));
        }
        if (files.isEmpty()) tree.addView(message(context, "没有发现可显示的页面文件", false));
        attachPanel(context, host, veil, card);
        if (!files.isEmpty()) selectSource(context, web, files.get(0), name, path, code, copy, currentText);
    }

    private static void selectSource(Context context, WebView web, SourceFile file, TextView name, TextView path, TextView code, TextView copy, String[] currentText) {
        name.setText(file.type + "  ·  " + file.name);
        path.setText(valueOr(file.url, file.meta));
        if (!file.content.isEmpty()) {
            setCode(code, copy, currentText, formatSource(file));
            return;
        }
        if (!isTextResource(file)) {
            setCode(code, copy, currentText, "资源类型：" + file.type + "\n名称：" + file.name + "\n地址：" + file.url + "\n信息：" + file.meta + "\n\n二进制资源不在源码面板内下载或解码。 ");
            return;
        }
        code.setText("// 正在按需加载\n" + file.url);
        copy.setEnabled(false);
        IO.execute(() -> {
            String result;
            try {
                result = fetchText(file.url, CookieManager.getInstance().getCookie(file.url), web.getSettings().getUserAgentString());
                file.content = result;
                result = formatSource(file);
            } catch (Exception e) {
                result = "// 无法加载此外部文件\n// " + readableError(e) + "\n\n" + file.url;
            }
            String finalResult = result;
            MAIN.post(() -> setCode(code, copy, currentText, finalResult));
        });
    }

    private static void setCode(TextView code, TextView copy, String[] currentText, String value) {
        currentText[0] = value;
        code.setText(value);
        copy.setEnabled(true);
    }

    private static String formatSource(SourceFile file) {
        String text = file.content;
        if ("HTML".equals(file.type)) return formatHtml(text);
        if ("Manifest".equals(file.type) || file.name.endsWith(".json")) {
            try { return new JSONObject(text).toString(2); } catch (Exception ignored) {}
            try { return new JSONArray(text).toString(2); } catch (Exception ignored) {}
        }
        return text;
    }

    private static void loadNetworkReport(NetworkReport report) {
        report.time = timestamp();
        try {
            for (InetAddress address : InetAddress.getAllByName(report.host)) {
                String value = address.getHostAddress();
                String type = value.contains(":") ? "AAAA" : "A";
                report.dns.add(new DnsRecord(type + "（系统）", value, 0));
                if (report.primaryIp.isEmpty() && "A".equals(type)) report.primaryIp = value;
            }
        } catch (Exception e) { report.dnsError = "系统 DNS 查询失败：" + readableError(e); }
        String[] types = {"A", "AAAA", "CNAME", "MX", "NS", "TXT", "CAA"};
        for (String type : types) {
            try {
                JSONObject dns;
                try { dns = getJson("https://dns.alidns.com/resolve?name=" + enc(report.host) + "&type=" + type); }
                catch (Exception domestic) { dns = getJson("https://dns.google/resolve?name=" + enc(report.host) + "&type=" + type); }
                JSONArray answers = dns.optJSONArray("Answer");
                if (answers != null) for (int i = 0; i < answers.length(); i++) {
                    JSONObject a = answers.optJSONObject(i);
                    if (a == null) continue;
                    String value = a.optString("data");
                    report.dns.add(new DnsRecord(type, value, a.optInt("TTL")));
                    if (report.primaryIp.isEmpty() && "A".equals(type)) report.primaryIp = value;
                }
            } catch (Exception e) {
                if (report.dnsError.isEmpty()) report.dnsError = "DNS 查询失败：" + readableError(e);
            }
        }
        report.registeredDomain = registeredDomain(report.host);
        try {
            report.rdap = getJson("https://rdap.org/domain/" + enc(report.registeredDomain));
        } catch (Exception e) {
            report.rdapError = "RDAP 查询失败：" + readableError(e);
        }
        if (!report.primaryIp.isEmpty()) {
            try {
                JSONObject ip = getJson("https://ipwho.is/" + enc(report.primaryIp));
                if (!ip.optBoolean("success", true)) throw new IllegalStateException(ip.optString("message", "服务未返回结果"));
                report.ipInfo = ip;
            } catch (Exception e) {
                report.ipError = "IP / 运营商查询失败：" + readableError(e);
            }
        } else report.ipError = "DNS 未返回 IPv4 地址，无法继续查询运营商";
    }

    private static JSONObject getJson(String address) throws Exception {
        return new JSONObject(fetchText(address, null, "JianBox/3.7 Android developer-tools"));
    }

    private static String fetchText(String address, String cookie, String userAgent) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(10000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "application/json,text/plain,text/css,application/javascript,text/html,*/*;q=0.2");
        connection.setRequestProperty("User-Agent", valueOr(userAgent, "JianBox/3.7 Android"));
        if (cookie != null && !cookie.isEmpty()) connection.setRequestProperty("Cookie", cookie);
        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) throw new IllegalStateException("HTTP " + status);
        try (BufferedInputStream input = new BufferedInputStream(connection.getInputStream()); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (output.size() + read > MAX_RESPONSE) {
                    output.write(buffer, 0, MAX_RESPONSE - output.size());
                    break;
                }
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }

    private static LinearLayout header(Context context, FrameLayout host, FrameLayout veil, String title, String subtitle) {
        LinearLayout row = Ui.horizontal(context);
        LinearLayout labels = Ui.vertical(context);
        labels.addView(Ui.text(context, title, 14, Ui.TEXT, true));
        TextView sub = Ui.text(context, subtitle, 9, Ui.MUTED, false);
        sub.setSingleLine(true);
        labels.addView(sub);
        row.addView(labels, new LinearLayout.LayoutParams(0, Ui.dp(context, 42), 1));
        TextView close = smallAction(context, "关闭");
        close.setOnClickListener(v -> host.removeView(veil));
        row.addView(close, new LinearLayout.LayoutParams(Ui.dp(context, 54), Ui.dp(context, 34)));
        return row;
    }

    private static TextView sectionTitle(Context context, String value) {
        TextView title = Ui.text(context, value, 10, Ui.GREEN, true);
        title.setPadding(Ui.dp(context, 4), Ui.dp(context, 9), Ui.dp(context, 4), Ui.dp(context, 5));
        return title;
    }

    private static TextView detailRow(Context context, String key, String value) {
        TextView row = Ui.text(context, key + "\n" + valueOr(value, "未公开"), 10, Ui.TEXT, false);
        row.setPadding(Ui.dp(context, 10), Ui.dp(context, 8), Ui.dp(context, 10), Ui.dp(context, 8));
        row.setBackground(Ui.bordered(Ui.SOFT_SURFACE, Ui.BORDER, 8, context));
        row.setTextIsSelectable(true);
        row.setOnClickListener(v -> {
            ClipboardManager c = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            c.setPrimaryClip(ClipData.newPlainText(key, value));
            Toast.makeText(context, "已复制 " + key, Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = Ui.dp(context, 5);
        row.setLayoutParams(lp);
        return row;
    }

    private static TextView message(Context context, String value, boolean error) {
        TextView text = Ui.text(context, value, 11, error ? 0xFFB42318 : Ui.MUTED, false);
        text.setGravity(Gravity.CENTER);
        text.setPadding(Ui.dp(context, 14), Ui.dp(context, 28), Ui.dp(context, 14), Ui.dp(context, 28));
        return text;
    }

    private static TextView tab(Context context, String value, boolean selected) {
        TextView tab = Ui.text(context, value, 10, Ui.TEXT, true);
        tab.setGravity(Gravity.CENTER);
        styleTab(context, tab, selected);
        return tab;
    }

    private static void styleTab(Context context, TextView tab, boolean selected) {
        tab.setTextColor(selected ? Color.WHITE : Ui.TEXT);
        tab.setBackground(selected ? Ui.bg(Ui.GREEN, 8, context) : Ui.bordered(Ui.SURFACE, Ui.BORDER, 8, context));
    }

    private static TextView smallAction(Context context, String value) {
        TextView button = Ui.text(context, value, 10, Ui.TEXT, true);
        button.setGravity(Gravity.CENTER);
        button.setBackground(Ui.bordered(Ui.SURFACE, Ui.BORDER, 8, context));
        return button;
    }

    private static TextView treeGroup(Context context, String value) {
        TextView group = Ui.text(context, "▾ " + value, 9, Ui.MUTED, true);
        group.setPadding(Ui.dp(context, 5), Ui.dp(context, 7), Ui.dp(context, 3), Ui.dp(context, 3));
        return group;
    }

    private static TextView treeFile(Context context, SourceFile file) {
        String glyph = "HTML".equals(file.type) ? "<>" : "CSS".equals(file.type) ? "#" : "JS".equals(file.type) ? "JS" : "·";
        TextView row = Ui.text(context, glyph + "  " + file.name, 9, Ui.TEXT, false);
        row.setSingleLine(true);
        row.setPadding(Ui.dp(context, 7), 0, Ui.dp(context, 5), 0);
        row.setBackground(Ui.bg(Ui.SOFT_SURFACE, 6, context));
        return row;
    }

    private static FrameLayout veil(Context context) {
        FrameLayout veil = new FrameLayout(context);
        veil.setTag(PANEL_TAG);
        veil.setBackgroundColor(0xAA000000);
        return veil;
    }

    private static LinearLayout panelCard(Context context) {
        LinearLayout card = Ui.vertical(context);
        card.setPadding(Ui.dp(context, 11), Ui.dp(context, 9), Ui.dp(context, 11), Ui.dp(context, 10));
        card.setBackground(Ui.bg(Ui.SURFACE, 12, context));
        card.setElevation(Ui.dp(context, 18));
        return card;
    }

    private static void attachPanel(Context context, FrameLayout host, FrameLayout veil, LinearLayout card) {
        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(-1, -1);
        cardParams.setMargins(Ui.dp(context, 6), Ui.dp(context, 6), Ui.dp(context, 6), Ui.dp(context, 6));
        veil.addView(card, cardParams);
        host.addView(veil, new FrameLayout.LayoutParams(-1, -1));
    }

    private static void removeOld(FrameLayout host) {
        View old = host.findViewWithTag(PANEL_TAG);
        if (old != null) host.removeView(old);
    }

    private static void addIfPresent(Context context, LinearLayout content, String key, String value) {
        if (value != null && !value.trim().isEmpty()) content.addView(detailRow(context, key, value));
    }

    private static String registrar(JSONArray entities) {
        if (entities == null) return "";
        for (int i = 0; i < entities.length(); i++) {
            JSONObject entity = entities.optJSONObject(i);
            if (entity == null || !contains(entity.optJSONArray("roles"), "registrar")) continue;
            JSONArray vcard = entity.optJSONArray("vcardArray");
            if (vcard != null && vcard.length() > 1) {
                JSONArray values = vcard.optJSONArray(1);
                if (values != null) for (int j = 0; j < values.length(); j++) {
                    JSONArray field = values.optJSONArray(j);
                    if (field != null && "fn".equals(field.optString(0))) return field.optString(3);
                }
            }
            return entity.optString("handle");
        }
        return "";
    }

    private static String eventDate(JSONObject rdap, String action) {
        JSONArray events = rdap.optJSONArray("events");
        if (events == null) return "";
        for (int i = 0; i < events.length(); i++) {
            JSONObject event = events.optJSONObject(i);
            if (event != null && action.equalsIgnoreCase(event.optString("eventAction"))) return event.optString("eventDate");
        }
        return "";
    }

    private static String nameservers(JSONArray array) {
        if (array == null) return "";
        List<String> result = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item != null) result.add(item.optString("ldhName"));
        }
        return android.text.TextUtils.join("\n", result);
    }

    private static String join(JSONArray array) {
        if (array == null) return "";
        List<String> result = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) result.add(array.optString(i));
        return android.text.TextUtils.join(" · ", result);
    }

    private static boolean contains(JSONArray array, String value) {
        if (array == null) return false;
        for (int i = 0; i < array.length(); i++) if (value.equalsIgnoreCase(array.optString(i))) return true;
        return false;
    }

    private static boolean isPublicHost(String host) {
        if (host == null || host.isEmpty() || "localhost".equalsIgnoreCase(host)) return false;
        return host.contains(".") && !host.endsWith(".local") && !host.startsWith("192.168.") && !host.startsWith("10.");
    }

    private static String registeredDomain(String host) {
        String ascii;
        try { ascii = IDN.toASCII(host).toLowerCase(Locale.ROOT); } catch (Exception e) { ascii = host.toLowerCase(Locale.ROOT); }
        String[] labels = ascii.split("\\.");
        if (labels.length <= 2) return ascii;
        String suffix2 = labels[labels.length - 2] + "." + labels[labels.length - 1];
        Set<String> multipart = new HashSet<>();
        Collections.addAll(multipart, "com.cn", "net.cn", "org.cn", "gov.cn", "edu.cn", "co.uk", "org.uk", "com.au", "co.jp", "co.kr", "com.hk", "com.tw");
        int take = multipart.contains(suffix2) && labels.length >= 3 ? 3 : 2;
        StringBuilder result = new StringBuilder();
        for (int i = labels.length - take; i < labels.length; i++) {
            if (result.length() > 0) result.append('.');
            result.append(labels[i]);
        }
        return result.toString();
    }

    private static String formatHtml(String input) {
        if (input == null) return "";
        return input.replace("><", ">\n<").replaceAll("(?i)(</?(?:html|head|body|div|section|article|header|footer|main|nav|script|style|link|meta|form|ul|ol|li|table|tr|p|h[1-6])[^>]*>)", "\n$1\n").replaceAll("\n{3,}", "\n\n").trim();
    }

    private static boolean isTextResource(SourceFile file) {
        return "HTML".equals(file.type) || "CSS".equals(file.type) || "JS".equals(file.type) || "Manifest".equals(file.type) || file.name.endsWith(".json") || file.name.endsWith(".xml") || file.name.endsWith(".txt");
    }

    private static int typeOrder(String type) {
        if ("HTML".equals(type)) return 0;
        if ("CSS".equals(type)) return 1;
        if ("JS".equals(type)) return 2;
        if ("Manifest".equals(type)) return 3;
        if ("字体".equals(type)) return 4;
        if ("图片".equals(type)) return 5;
        if ("媒体".equals(type)) return 6;
        return 7;
    }

    private static String decode(String raw) throws Exception { return new JSONArray("[" + raw + "]").getString(0); }
    private static String enc(String value) { try { return URLEncoder.encode(value, "UTF-8"); } catch (Exception e) { return value; } }
    private static String valueOr(String value, String fallback) { return value == null || value.trim().isEmpty() ? fallback : value; }
    private static String joinParts(String... values) { List<String> result = new ArrayList<>(); for (String value : values) if (value != null && !value.isEmpty()) result.add(value); return android.text.TextUtils.join(" · ", result); }
    private static String coordinate(JSONObject o) { return o.has("latitude") && o.has("longitude") ? o.optString("latitude") + ", " + o.optString("longitude") : ""; }
    private static String readableError(Exception e) { String value = e.getMessage(); return value == null || value.isEmpty() ? e.getClass().getSimpleName() : value; }
    private static String timestamp() { SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA); format.setTimeZone(TimeZone.getDefault()); return format.format(new Date()); }

    private enum NetworkSection { DNS, DOMAIN, CARRIER }
    private static final class DnsRecord { final String type, value; final int ttl; DnsRecord(String type, String value, int ttl) { this.type = type; this.value = value; this.ttl = ttl; } }
    private static final class NetworkReport {
        final String host; final List<DnsRecord> dns = new ArrayList<>();
        boolean loading, loaded; String time = "", primaryIp = "", registeredDomain = "", dnsError = "", rdapError = "", ipError = "";
        JSONObject rdap, ipInfo; NetworkSection activeSection = NetworkSection.DNS;
        NetworkReport(String host) { this.host = host == null ? "" : host; }
        void reset() { loading=false; loaded=false; time=""; primaryIp=""; registeredDomain=""; dnsError=""; rdapError=""; ipError=""; rdap=null; ipInfo=null; dns.clear(); }
    }
    private static final class SourceFile {
        final String type, name, url, meta; String content;
        SourceFile(String type, String name, String url, String content, String meta) { this.type = type; this.name = name; this.url = url; this.content = content; this.meta = meta; }
    }

    private BrowserDeveloperTools() {}
}
