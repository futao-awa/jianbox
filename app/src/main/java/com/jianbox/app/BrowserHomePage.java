package com.jianbox.app;

import android.content.Context;
import android.util.Base64;

import java.io.File;
import java.io.FileInputStream;

final class BrowserHomePage {
    private static String cacheKey="";
    private static String cacheHtml="";
    static synchronized String html(Context context, JianData data) {
        String background = "linear-gradient(135deg,#b9d9c1 0%,#f6d5c5 48%,#9fc6b4 100%)";
        String path = data.browserBackgroundPath();
        File sourceFile=path==null||path.isEmpty()?null:new File(path);
        String key=(path==null?"":path)+"|"+(sourceFile==null?0:sourceFile.lastModified())+"|"+data.searchEngine()+"|"+data.browserBackgroundBrightness()+"|"+data.browserBackgroundShade()+"|"+data.browserBackgroundBlur()+"|"+data.browserBackgroundPosition();
        if(key.equals(cacheKey)&&!cacheHtml.isEmpty())return cacheHtml;
        if (path != null && !path.isEmpty()) {
            try {
                File file = new File(path); byte[] bytes = new byte[(int)file.length()];
                try (FileInputStream in = new FileInputStream(file)) { int read=0,n; while(read<bytes.length && (n=in.read(bytes,read,bytes.length-read))>0)read+=n; }
                String mime = path.endsWith(".png") ? "image/png" : "image/jpeg";
                background = "url(data:"+mime+";base64,"+Base64.encodeToString(bytes,Base64.NO_WRAP)+")";
            } catch (Exception ignored) {}
        }
        String engine=SearchEngine.safeId(data.searchEngine());
        String position="center "+data.browserBackgroundPosition()+"%";
        float shade=Math.max(0,Math.min(70,data.browserBackgroundShade()))/100f;
        String html="<!doctype html><html lang='zh-CN'><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1,maximum-scale=1'>"+
                "<style>*{box-sizing:border-box}html,body{margin:0;min-height:100%;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI','Microsoft YaHei',sans-serif;color:#fff}body{background:#1c2c21}.shade{min-height:100vh;padding:4vh clamp(18px,6vw,64px) 3vh;display:flex;flex-direction:column;position:relative;isolation:isolate;overflow:auto}.shade:before{content:'';position:fixed;inset:-24px;background:"+background+";background-position:"+position+";background-size:cover;background-repeat:no-repeat;filter:blur("+data.browserBackgroundBlur()+"px) brightness("+data.browserBackgroundBrightness()+"%);z-index:-2}.shade:after{content:'';position:fixed;inset:0;background:rgba(8,18,11,"+shade+");z-index:-1}.shell{width:100%;max-width:860px;margin:0 auto}.brand{font-size:10px;font-weight:700;letter-spacing:1.5px;opacity:.86}.clock{margin-top:2.2vh;text-shadow:0 3px 18px rgba(0,0,0,.25)}#time{font-size:38px;font-weight:300}.date{font-size:11px;margin-top:2px;opacity:.88}.center{margin-top:3.5vh}h1{font-size:20px;margin:0 0 4px;text-shadow:0 2px 16px rgba(0,0,0,.25)}.hello{font-size:10px;opacity:.88;margin-bottom:11px}.search{height:48px;padding:0 5px 0 7px;background:rgba(255,255,255,.94);border:1px solid rgba(255,255,255,.72);border-radius:14px;display:flex;align-items:center;box-shadow:0 10px 26px rgba(24,45,30,.16);backdrop-filter:blur(16px)}.engine{width:31px;height:31px;border-radius:9px;color:#fff;display:flex;align-items:center;justify-content:center;font-size:12px;font-weight:800}.search input{min-width:0;flex:1;border:0;outline:0;background:transparent;padding:0 9px;color:#213a27;font-size:12px}.search button{width:48px;height:36px;border:0;border-radius:10px;background:#303a33;color:white;font-size:11px;font-weight:700}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(72px,1fr));gap:7px;margin-top:12px}.card{height:58px;border-radius:11px;background:rgba(255,255,255,.23);border:1px solid rgba(255,255,255,.40);backdrop-filter:blur(12px);display:flex;flex-direction:column;align-items:center;justify-content:center;color:#fff;text-decoration:none;text-shadow:0 1px 5px rgba(0,0,0,.3);font-size:9px;min-width:0}.card b{font-size:16px;margin-bottom:3px}.card span{max-width:94%;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.card small{display:none;font-size:7px;opacity:.74;margin-top:2px}.card.user small{display:block}.foot{max-width:860px;width:100%;margin:auto auto 0;padding-top:18px;display:flex;justify-content:space-between;font-size:9px;opacity:.8}@media(min-width:700px){.shade{padding-top:5vh}.shell{max-width:900px}.grid{grid-template-columns:repeat(6,1fr)}.card{height:64px}.search{max-width:760px}#time{font-size:42px}}@media(max-height:620px){.shade{padding-top:18px}.clock{margin-top:8px}#time{font-size:30px}.center{margin-top:14px}.grid{margin-top:8px}.card{height:50px}.foot{padding-top:9px}}</style></head>"+
                "<body><main class='shade'><div class='shell'><div class='brand'>JIAN EDGE · NEW TAB</div><div class='clock'><div id='time'>--:--</div><div class='date' id='date'>今天</div></div>"+
                "<section class='center'><h1>想去哪里？</h1><div class='hello'>"+SearchEngine.label(engine)+" · 链接先在本机检查</div><form id='searchForm' class='search' action='"+SearchEngine.action(engine)+"' method='get'><span class='engine' style='background:"+String.format("#%06X",SearchEngine.color(engine)&0xFFFFFF)+"'>"+SearchEngine.glyph(engine)+"</span><input id='searchInput' name='"+SearchEngine.queryKey(engine)+"' autofocus autocomplete='off' placeholder='搜索或输入网址'><button>搜索</button></form>"+
                "<div class='grid'><a class='card' href='jian://navigation'><b>⌂</b><span>导航收藏</span></a><a class='card' href='jian://bookmarks'><b>★</b><span>网页收藏</span></a><a class='card' href='jian://tools'><b>◇</b><span>便捷工具</span></a><a class='card' href='jian://history'><b>◷</b><span>浏览历史</span></a><a class='card' href='jian://background'><b>▧</b><span>更换背景</span></a><a class='card' href='jian://shortcuts'><b>＋</b><span>便捷入口</span></a><a class='card' href='jian://plugins'><b>插</b><span>扩展插件</span></a></div></section></div>"+
                "<div class='foot'><span>本地安全检查</span><span>网站入口来自快捷分组</span></div></main><script>document.getElementById('searchForm').addEventListener('submit',e=>{let v=document.getElementById('searchInput').value.trim();if(v.includes('.')&&!v.includes(' ')){e.preventDefault();location.href=/^[a-z]+:\\/\\//i.test(v)?v:'https://'+v}});function tick(){let d=new Date();document.getElementById('time').textContent=d.toLocaleTimeString('zh-CN',{hour:'2-digit',minute:'2-digit',hour12:false});document.getElementById('date').textContent=d.toLocaleDateString('zh-CN',{month:'long',day:'numeric',weekday:'long'})}tick();setInterval(tick,1000)</script></body></html>";
        cacheKey=key;cacheHtml=html;return html;
    }
    private static String escape(String value){return value==null?"":value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");}
    private static String attr(String value){return escape(value).replace("'","&#39;");}
    private BrowserHomePage() {}
}
