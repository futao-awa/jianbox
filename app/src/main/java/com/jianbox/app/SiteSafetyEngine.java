package com.jianbox.app;

import android.net.Uri;

import java.net.IDN;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class SiteSafetyEngine {
    enum Level { OFFICIAL, SAFE, WARNING, DANGER }
    static final class Result {
        final Level level; final String label; final String detail; final String host;
        Result(Level level,String label,String detail,String host){this.level=level;this.label=label;this.detail=detail;this.host=host;}
    }

    private static final Map<String,String> OFFICIAL = new LinkedHashMap<>();
    private static final Set<String> SHORTENERS = new java.util.HashSet<>(java.util.Arrays.asList("bit.ly","t.co","tinyurl.com","dwz.cn","suo.im","url.cn","reurl.cc","cutt.ly"));
    static {
        OFFICIAL.put("microsoft.com","Microsoft"); OFFICIAL.put("bing.com","Microsoft Bing"); OFFICIAL.put("github.com","GitHub");
        OFFICIAL.put("google.com","Google"); OFFICIAL.put("openai.com","OpenAI"); OFFICIAL.put("baidu.com","百度");
        OFFICIAL.put("qq.com","腾讯"); OFFICIAL.put("weixin.qq.com","微信"); OFFICIAL.put("bilibili.com","哔哩哔哩");
        OFFICIAL.put("doubao.com","豆包"); OFFICIAL.put("deepseek.com","DeepSeek"); OFFICIAL.put("steamcommunity.com","Steam");
        OFFICIAL.put("steampowered.com","Steam"); OFFICIAL.put("taobao.com","淘宝"); OFFICIAL.put("tmall.com","天猫");
        OFFICIAL.put("jd.com","京东"); OFFICIAL.put("zhihu.com","知乎"); OFFICIAL.put("douyin.com","抖音");
    }

    static Result analyze(String raw) {
        try {
            Uri uri = Uri.parse(raw); String ascii = uri.getHost(); if (ascii == null) return new Result(Level.WARNING,"未知协议","无法识别此链接的主机名","");
            ascii = ascii.toLowerCase(Locale.ROOT); String unicode = IDN.toUnicode(ascii);
            for (Map.Entry<String,String> item : OFFICIAL.entrySet()) if (matches(ascii,item.getKey()) && "https".equalsIgnoreCase(uri.getScheme()))
                return new Result(Level.OFFICIAL,"已知官网",item.getValue()+" 官方域名 · "+unicode,ascii);
            int score=0; StringBuilder reasons=new StringBuilder();
            if (!"https".equalsIgnoreCase(uri.getScheme())) { score+=20; add(reasons,"未使用 HTTPS 加密"); }
            if (ascii.contains("xn--")) { score+=35; add(reasons,"域名包含国际化编码，请核对字符"); }
            if (ascii.matches("^(\\d{1,3}\\.){3}\\d{1,3}$") || ascii.contains(":")) { score+=45; add(reasons,"直接使用 IP 地址"); }
            if (raw.contains("@")) { score+=45; add(reasons,"链接包含可能隐藏真实主机的 @ 符号"); }
            if (raw.length()>160) { score+=12; add(reasons,"链接异常冗长"); }
            if (ascii.split("\\.").length>5) { score+=15; add(reasons,"子域层级过多"); }
            if (SHORTENERS.contains(ascii)) { score+=25; add(reasons,"短链接隐藏了最终地址"); }
            if (ascii.matches(".*(login|verify|secure|account|wallet|pay|bank).*")) { score+=12; add(reasons,"域名包含登录或支付诱导词"); }
            if (ascii.matches(".*\\.(zip|mov|click|work|gq|tk)$")) { score+=18; add(reasons,"使用高风险滥用率域名后缀"); }
            String plain = ascii.replaceAll("[^a-z0-9]","");
            for (String official : OFFICIAL.keySet()) {
                String brand=official.substring(0,official.indexOf('.')).replaceAll("[^a-z0-9]","");
                if (brand.length() >= 4 && plain.contains(brand) && !matches(ascii,official)) { score+=32; add(reasons,"域名看起来在模仿 "+OFFICIAL.get(official)); break; }
            }
            Level level=score>=50?Level.DANGER:score>=20?Level.WARNING:Level.SAFE;
            String label=level==Level.DANGER?"高风险":level==Level.WARNING?"请核对":"连接安全";
            String detail=reasons.length()==0?"已使用 HTTPS，未发现明显的本地风险特征。此结果不代表网站内容获得担保。":reasons.toString();
            return new Result(level,label,detail+"\n真实主机："+unicode,ascii);
        } catch(Exception e){return new Result(Level.WARNING,"无法验证","链接格式异常，请谨慎访问","");}
    }

    static String cleanTracking(String raw) {
        return BrowserUrlRules.cleanTracking(raw);
    }

    static boolean isHighRiskDownload(String url,String mime){
        String lower=(url==null?"":url).toLowerCase(Locale.ROOT);String type=(mime==null?"":mime).toLowerCase(Locale.ROOT);
        return lower.matches(".*\\.(exe|msi|scr|bat|cmd|com|ps1|vbs|js|jse|lnk|iso)(\\?.*)?$")||type.contains("x-msdownload")||type.contains("x-msdos-program")||type.contains("x-executable");
    }

    private static boolean matches(String host,String root){return host.equals(root)||host.endsWith("."+root);}
    private static void add(StringBuilder b,String value){if(b.length()>0)b.append("；");b.append(value);}
    private SiteSafetyEngine(){}
}
