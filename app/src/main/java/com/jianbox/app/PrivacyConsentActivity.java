package com.jianbox.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** First-run privacy gate. Features remain unavailable until the user has read and accepted it. */
public class PrivacyConsentActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Button agree;
    private int seconds = 3;
    private final Runnable countdown = new Runnable() { @Override public void run() {
        if (seconds <= 0) { agree.setEnabled(true); agree.setText("我已阅读并同意"); return; }
        agree.setText("请阅读后继续（" + seconds + "s）");
        seconds--; handler.postDelayed(this, 1000);
    }};

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        JianData data = new JianData(this);
        Ui.applyTheme(data);
        if (data.privacyAccepted()) {
            if (UpdateGuard.enforce(this)) return;
            openApp(false);
            return;
        }
        LinearLayout shell = Ui.vertical(this); shell.setGravity(Gravity.CENTER);
        shell.setPadding(Ui.dp(this,22),Ui.dp(this,18),Ui.dp(this,22),Ui.dp(this,18));
        shell.setBackground(Ui.bg(Ui.GREEN_PALE,0,this));
        LinearLayout card = Ui.card(this); card.setPadding(Ui.dp(this,20),Ui.dp(this,20),Ui.dp(this,20),Ui.dp(this,18));
        TextView logo = Ui.icon(this,"简",Color.WHITE); logo.setGravity(Gravity.CENTER); card.addView(logo,Ui.lp(Ui.dp(this,52),Ui.dp(this,52)));
        TextView title = Ui.text(this,"隐私协议与服务说明",20,Ui.TEXT,true); title.setPadding(0,Ui.dp(this,14),0,Ui.dp(this,7)); card.addView(title);
        TextView intro = Ui.text(this,"请完整阅读。为保护你的选择，同意按钮将在 3 秒后启用。",11,Ui.MUTED,false); card.addView(intro);
        ScrollView scroll = new ScrollView(this);
        TextView body = Ui.text(this,"一、信息存储\n简盒默认仅在本机保存浏览历史、收藏、剪贴板备忘、下载记录、提醒和个性化配置。清除应用数据或在对应页面删除后，本机数据会被移除。\n\n二、权限用途\n悬浮球需要“显示在其他应用上层”权限；剪贴板记录仅在系统允许时读取；相机/手电筒只在你主动使用相关快捷功能时调用；通知权限仅用于下载和提醒提示。\n\n三、浏览与扩展\n内置浏览器通过 Android WebView 访问网页。广告过滤、翻译、复制保护和页面分析仅处理当前打开页面的公开内容；安全提示是辅助判断，不构成绝对安全保证。\n\n四、账号与后端\n账号为可选项。使用邮箱验证码认证时，邮箱地址、验证码请求和登录状态由 Supabase 处理；简盒不会读取你的邮件内容，也不会在客户端保存服务端管理密钥。签到奖励、硬币余额和账号活跃时间会保存在账号服务中。\n\n五、第三方查询\n只有在你主动打开开发者工具的 DNS、域名或运营商页面时，当前域名或解析后的公网 IP 才会发送给对应查询服务。\n\n六、下载与第三方内容\n下载文件来自你选择的网站。简盒会显示进度和保存路径，但不替代杀毒或版权审查；安装 APK 或打开未知文件前请自行确认来源。\n\n七、你的控制权\n你可在“设置/个性化/我的”中关闭历史记录、扩展、悬浮球，修改资料，或退出账号。继续使用即表示同意以上内容。",11,Ui.TEXT,false);
        body.setLineSpacing(Ui.dp(this,4),1f); body.setPadding(0,Ui.dp(this,10),0,Ui.dp(this,8)); scroll.addView(body); card.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        agree=Ui.button(this,"请阅读后继续（3s）",true); agree.setEnabled(false); agree.setOnClickListener(v->{data.privacyAccepted(true);showAccountChoice();}); card.addView(agree,new LinearLayout.LayoutParams(-1,Ui.dp(this,46)));
        Button exit=Ui.button(this,"暂不使用",false); exit.setOnClickListener(v->finishAffinity()); card.addView(exit,new LinearLayout.LayoutParams(-1,Ui.dp(this,40)));
        shell.addView(card,new LinearLayout.LayoutParams(-1,Ui.dp(this,570))); setContentView(shell); handler.post(countdown);
    }

    private void showAccountChoice(){
        new AlertDialog.Builder(this).setTitle("欢迎使用简盒").setMessage("账号可用于后续同步资料和接收服务通知。现在登录或注册？")
                .setPositiveButton("登录或注册",(d,w)->openApp(true)).setNegativeButton("暂时跳过",(d,w)->openApp(false)).setCancelable(false).show();
    }
    private void openApp(boolean account){
        startActivity(new Intent(this,MainActivity.class));
        if(account) startActivity(new Intent(this,AccountActivity.class));
        finish();
    }
    @Override protected void onDestroy(){handler.removeCallbacks(countdown);super.onDestroy();}
}
