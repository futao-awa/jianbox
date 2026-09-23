package com.jianbox.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;
import org.json.JSONArray;

import java.util.Locale;
import java.util.regex.Pattern;

/** Email/password login and email-code registration backed by Supabase Auth. */
public class AccountActivity extends Activity {
    private static final long CODE_VALID_MS = 10 * 60 * 1000L;
    private static final Pattern EMAIL = Pattern.compile("^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}$", Pattern.CASE_INSENSITIVE);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private JianData data;
    private LinearLayout formCard;
    private TextView status, modeHint;
    private EditText email, password, confirm, code;
    private Button loginMode, registerMode, primary, verify;
    private boolean registering;
    private int cooldown, failures;
    private long sentAt;
    private String pendingEmail = "";
    private final Runnable cooldownTick = new Runnable() { public void run() {
        if (cooldown <= 0) { if (primary != null && registering) { primary.setEnabled(true); primary.setText("重新发送验证码"); } return; }
        if (primary != null && registering) { primary.setEnabled(false); primary.setText(String.format(Locale.CHINA, "%d 秒后可重发", cooldown)); }
        cooldown--;
        handler.postDelayed(this, 1000);
    }};

    @Override public void onCreate(Bundle state) { super.onCreate(state); data = new JianData(this); Ui.applyTheme(data); if(UpdateGuard.enforce(this))return; build(); }
    @Override protected void onResume(){super.onResume();UpdateGuard.resume(this);}

    private void build() {
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        if (Ui.GLASS) scroll.setBackground(ImageStore.background(this, data.appBackgroundPath(), 0x26000000, 0, 0x22FFFFFF, 100, 0, data.appBackgroundPosition()));
        else scroll.setBackground(Ui.bg(Ui.GREEN_PALE, 0, this));
        LinearLayout root = Ui.vertical(this); root.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 28));
        int width = Math.min(getResources().getDisplayMetrics().widthPixels, Ui.dp(this, 560));
        scroll.addView(root, new ScrollView.LayoutParams(width, -2, Gravity.CENTER_HORIZONTAL));

        LinearLayout header = Ui.horizontal(this);
        TextView back = Ui.text(this, "‹", 27, Ui.TEXT, false); back.setGravity(Gravity.CENTER); back.setBackground(Ui.bordered(Ui.SURFACE, Ui.BORDER, 11, this)); back.setOnClickListener(v -> finish());
        header.addView(back, Ui.lp(Ui.dp(this, 40), Ui.dp(this, 40)));
        LinearLayout heading = Ui.vertical(this); heading.setPadding(Ui.dp(this, 11), 0, 0, 0); heading.addView(Ui.label(this, "JIAN BOX · ACCOUNT")); heading.addView(Ui.text(this, "账号中心", 20, Ui.TEXT, true)); header.addView(heading, new LinearLayout.LayoutParams(0, Ui.dp(this, 46), 1));
        TextView badge = Ui.text(this, "安全", 9, Ui.GREEN, true); badge.setGravity(Gravity.CENTER); badge.setBackground(Ui.bg(Ui.GREEN_SOFT, 10, this)); header.addView(badge, Ui.lp(Ui.dp(this, 48), Ui.dp(this, 32)));
        root.addView(header, new LinearLayout.LayoutParams(-1, Ui.dp(this, 52)));
        TextView subtitle = Ui.text(this, "登录后同步个人资料、签到与后续云端服务。", 11, Ui.MUTED, false); subtitle.setPadding(Ui.dp(this, 2), 0, 0, Ui.dp(this, 12)); root.addView(subtitle);

        LinearLayout identity = Ui.card(this); LinearLayout identityRow = Ui.horizontal(this);
        Bitmap avatar = ImageStore.load(data.profileAvatarPath());
        if (avatar != null) { ImageView image = new ImageView(this); image.setImageBitmap(avatar); image.setScaleType(ImageView.ScaleType.CENTER_CROP); image.setClipToOutline(true); image.setBackground(Ui.bg(Ui.GREEN_SOFT, 18, this)); identityRow.addView(image, Ui.lp(Ui.dp(this, 58), Ui.dp(this, 58))); }
        else identityRow.addView(Ui.icon(this, "我", Color.WHITE), Ui.lp(Ui.dp(this, 58), Ui.dp(this, 58)));
        LinearLayout identityCopy = Ui.vertical(this); identityCopy.setPadding(Ui.dp(this, 12), 0, 0, 0); identityCopy.addView(Ui.text(this, data.accountToken().isEmpty() ? "欢迎使用简盒账号" : data.accountEmail(), 16, Ui.TEXT, true)); identityCopy.addView(Ui.text(this, data.accountToken().isEmpty() ? "邮箱验证 · 密码保护 · 可选登录" : "账号已通过 Supabase 安全验证", 10, Ui.MUTED, false)); identityRow.addView(identityCopy, new LinearLayout.LayoutParams(0, Ui.dp(this, 58), 1)); identity.addView(identityRow); root.addView(identity, new LinearLayout.LayoutParams(-1, -2)); Ui.margin(identity, 0, 0, 0, 10, this);

        if (!data.accountToken().isEmpty()) {
            LinearLayout session = Ui.card(this);
            session.addView(Ui.label(this, "CURRENT SESSION"));
            TextView emailText = Ui.text(this, data.accountEmail(), 16, Ui.TEXT, true);
            emailText.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 3));
            session.addView(emailText);
            session.addView(Ui.text(this, "此设备已完成邮箱验证，登录和注册入口已隐藏。", 10, Ui.MUTED, false));
            Button logout = Ui.button(this, "退出当前账号", false);
            logout.setTextColor(Ui.RED);
            logout.setOnClickListener(v -> { data.clearAccount(); setResult(RESULT_OK); finish(); });
            session.addView(logout, new LinearLayout.LayoutParams(-1, Ui.dp(this, 42)));
            Ui.margin(logout, 0, 12, 0, 0, this);
            root.addView(session, new LinearLayout.LayoutParams(-1, -2));
            Ui.margin(session, 0, 0, 0, 10, this);
            TextView tip = Ui.text(this, "退出后可以重新使用邮箱登录或注册。", 10, Ui.MUTED, false);
            tip.setPadding(Ui.dp(this, 4), Ui.dp(this, 2), Ui.dp(this, 4), 0);
            root.addView(tip, new LinearLayout.LayoutParams(-1, Ui.dp(this, 30)));
            setContentView(scroll);
            return;
        }

        formCard = Ui.card(this);
        LinearLayout titleRow = Ui.horizontal(this); TextView glyph = Ui.text(this, "账", 11, Color.WHITE, true); glyph.setGravity(Gravity.CENTER); glyph.setBackground(Ui.bg(Ui.GREEN, 8, this)); titleRow.addView(glyph, Ui.lp(Ui.dp(this, 26), Ui.dp(this, 26))); TextView formTitle = Ui.text(this, "登录或注册", 15, Ui.TEXT, true); formTitle.setPadding(Ui.dp(this, 9), 0, 0, 0); titleRow.addView(formTitle, new LinearLayout.LayoutParams(0, Ui.dp(this, 30), 1)); formCard.addView(titleRow);
        LinearLayout modes = Ui.horizontal(this); modes.setPadding(0, Ui.dp(this, 10), 0, 0); loginMode = modeButton("登录", true); registerMode = modeButton("注册", false); modes.addView(loginMode, new LinearLayout.LayoutParams(0, Ui.dp(this, 38), 1)); Ui.margin(loginMode, 0, 0, 6, 0, this); modes.addView(registerMode, new LinearLayout.LayoutParams(0, Ui.dp(this, 38), 1)); formCard.addView(modes);
        modeHint = Ui.text(this, "使用已注册的邮箱和密码登录", 10, Ui.MUTED, false); modeHint.setPadding(Ui.dp(this, 2), Ui.dp(this, 10), 0, Ui.dp(this, 8)); formCard.addView(modeHint);
        formCard.addView(fieldLabel("邮箱")); email = field("name@example.com", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS); formCard.addView(email);
        formCard.addView(fieldLabel("密码")); password = field("至少 8 位字符", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); formCard.addView(password);
        TextView confirmLabel = fieldLabel("确认密码"); confirmLabel.setTag("register_only"); confirmLabel.setVisibility(View.GONE); formCard.addView(confirmLabel); confirm = field("再次输入密码", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); confirm.setTag("register_only"); confirm.setVisibility(View.GONE); formCard.addView(confirm);
        TextView codeLabel = fieldLabel("邮箱验证码"); codeLabel.setTag("register_only"); codeLabel.setVisibility(View.GONE); formCard.addView(codeLabel); code = field("输入邮件中的数字验证码", InputType.TYPE_CLASS_NUMBER); code.setTag("register_only"); code.setFilters(new InputFilter[]{new InputFilter.LengthFilter(10)}); code.setVisibility(View.GONE); formCard.addView(code);
        CheckBox reveal = new CheckBox(this); reveal.setText("显示密码"); reveal.setTextSize(10); reveal.setTextColor(Ui.MUTED); reveal.setPadding(0, 0, 0, 0); reveal.setOnCheckedChangeListener((v, checked) -> { int type = InputType.TYPE_CLASS_TEXT | (checked ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD : InputType.TYPE_TEXT_VARIATION_PASSWORD); password.setInputType(type); confirm.setInputType(type); password.setSelection(password.length()); confirm.setSelection(confirm.length()); }); formCard.addView(reveal, new LinearLayout.LayoutParams(-1, Ui.dp(this, 34)));
        primary = Ui.button(this, "登录", true); formCard.addView(primary, new LinearLayout.LayoutParams(-1, Ui.dp(this, 44)));
        verify = Ui.button(this, "验证并完成注册", true); verify.setVisibility(View.GONE); formCard.addView(verify, new LinearLayout.LayoutParams(-1, Ui.dp(this, 44))); Ui.margin(verify, 0, 7, 0, 0, this);
        status = Ui.text(this, "账号服务已连接", 10, BackendClient.configured() ? Ui.GREEN : Ui.RED, true); status.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8)); status.setBackground(Ui.bordered(Ui.SOFT_SURFACE, Ui.BORDER, 9, this)); formCard.addView(status); Ui.margin(status, 0, 9, 0, 0, this);
        TextView forgot = Ui.text(this, "忘记密码", 10, Ui.GREEN, true); forgot.setGravity(Gravity.END | Gravity.CENTER_VERTICAL); forgot.setPadding(0, Ui.dp(this, 7), Ui.dp(this, 2), 0); forgot.setOnClickListener(v -> startActivity(new Intent(this, ResetPasswordActivity.class))); formCard.addView(forgot, new LinearLayout.LayoutParams(-1, Ui.dp(this, 30)));
        root.addView(formCard, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout security = Ui.card(this); LinearLayout securityTitle = Ui.horizontal(this); securityTitle.addView(Ui.icon(this, "盾", Ui.GREEN), Ui.lp(Ui.dp(this, 38), Ui.dp(this, 38))); LinearLayout securityCopy = Ui.vertical(this); securityCopy.setPadding(Ui.dp(this, 10), 0, 0, 0); securityCopy.addView(Ui.text(this, "验证与安全限制", 12, Ui.TEXT, true)); securityCopy.addView(Ui.text(this, "验证码 10 分钟有效 · 60 秒重发冷却 · 连续错误 5 次后重置", 9, Ui.MUTED, false)); securityTitle.addView(securityCopy, new LinearLayout.LayoutParams(0, Ui.dp(this, 40), 1)); security.addView(securityTitle); root.addView(security, new LinearLayout.LayoutParams(-1, -2)); Ui.margin(security, 0, 10, 0, 0, this);

        if (!data.accountToken().isEmpty()) { LinearLayout session = Ui.card(this); session.addView(Ui.text(this, "当前会话", 13, Ui.TEXT, true)); TextView account = Ui.text(this, data.accountEmail(), 10, Ui.MUTED, false); account.setPadding(0, Ui.dp(this, 5), 0, Ui.dp(this, 8)); session.addView(account); Button logout = Ui.button(this, "退出当前账号", false); logout.setTextColor(Ui.RED); logout.setOnClickListener(v -> { data.clearAccount(); setResult(RESULT_OK); finish(); }); session.addView(logout, new LinearLayout.LayoutParams(-1, Ui.dp(this, 40))); root.addView(session, new LinearLayout.LayoutParams(-1, -2)); Ui.margin(session, 0, 10, 0, 0, this); }

        setContentView(scroll); loginMode.setOnClickListener(v -> setMode(false)); registerMode.setOnClickListener(v -> setMode(true)); primary.setOnClickListener(v -> { if (registering) requestCode(); else login(); }); verify.setOnClickListener(v -> verifyRegistration());
    }

    private Button modeButton(String label, boolean selected) { Button b = Ui.button(this, label, selected); b.setTextSize(11); return b; }
    private TextView fieldLabel(String text) { TextView v = Ui.text(this, text, 10, Ui.MUTED, true); v.setPadding(Ui.dp(this, 2), Ui.dp(this, 2), 0, Ui.dp(this, 5)); return v; }
    private void setMode(boolean value) { if (registering == value) return; registering = value; formCard.animate().cancel(); formCard.setAlpha(.55f); formCard.setTranslationY(Ui.dp(this, 6)); loginMode.setBackground(registering ? Ui.bordered(Ui.SURFACE, Ui.BORDER, 9, this) : Ui.bg(Ui.GREEN, 9, this)); loginMode.setTextColor(registering ? Ui.TEXT : Color.WHITE); registerMode.setBackground(registering ? Ui.bg(Ui.GREEN, 9, this) : Ui.bordered(Ui.SURFACE, Ui.BORDER, 9, this)); registerMode.setTextColor(registering ? Color.WHITE : Ui.TEXT); for (int i=0;i<formCard.getChildCount();i++){View child=formCard.getChildAt(i);if("register_only".equals(child.getTag()))child.setVisibility(registering?View.VISIBLE:View.GONE);} verify.setVisibility(View.GONE); primary.setText(registering ? "发送注册验证码" : "登录"); primary.setEnabled(!registering || cooldown <= 0); if (registering && cooldown > 0) primary.setText(String.format(Locale.CHINA, "%d 秒后可重发", cooldown)); modeHint.setText(registering ? "设置密码并验证邮箱，验证码将发送到收件箱" : "使用已注册的邮箱和密码登录"); resetPending(registering ? "填写信息后发送验证码" : "账号服务已连接"); formCard.animate().alpha(1f).translationY(0f).setDuration(160).start(); }
    private EditText field(String hint, int type) { EditText v = new EditText(this); v.setHint(hint); v.setSingleLine(true); v.setInputType(type); v.setTextSize(12); v.setTextColor(Ui.TEXT); v.setHintTextColor(Ui.MUTED); v.setBackground(Ui.bordered(Ui.SOFT_SURFACE, Ui.BORDER, 10, this)); v.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 45)); lp.setMargins(0, 0, 0, Ui.dp(this, 7)); v.setLayoutParams(lp); return v; }
    private String emailValue() { String v = email.getText().toString().trim().toLowerCase(Locale.ROOT); if (!EMAIL.matcher(v).matches()) { setStatus("请输入有效的邮箱地址", true); return null; } if (!BackendClient.configured()) { setStatus("账号服务配置缺失，请安装完整构建版本", true); return null; } return v; }
    private boolean passwordValid() { String p = password.getText().toString(); if (p.length() < 8) { setStatus("密码至少需要 8 位", true); return false; } if (registering && !p.equals(confirm.getText().toString())) { setStatus("两次输入的密码不一致", true); return false; } return true; }
    private void setStatus(String message, boolean error) { status.setText(message); status.setTextColor(error ? Ui.RED : Ui.GREEN); }

    private void login() { String e = emailValue(); if (e == null || !passwordValid()) return; setBusy(true, "正在安全登录…"); try { BackendClient.request("/auth/v1/token?grant_type=password", "POST", new JSONObject().put("email", e).put("password", password.getText().toString()), (ok, body, error) -> { if (!ok) { setBusy(false, error); return; } pendingEmail = e; saveSession(body, false); }); } catch (Exception ex) { setBusy(false, "登录请求失败"); } }
    private void requestCode() { String e = emailValue(); if (e == null || !passwordValid()) return; if (cooldown > 0) { setStatus("请稍候再重发验证码", true); return; } setBusy(true, "正在发送注册验证码…"); try { JSONObject payload = new JSONObject().put("email", e).put("password", password.getText().toString()).put("data", new JSONObject().put("display_name", e.split("@")[0])); BackendClient.request("/auth/v1/signup", "POST", payload, (ok, body, error) -> { if (!ok) { setBusy(false, error); return; }
            JSONObject candidate = body == null ? null : body.optJSONObject("user");
            if (candidate == null) candidate = body;
            JSONArray identities = candidate == null ? null : candidate.optJSONArray("identities");
            boolean existing = candidate != null && !candidate.optString("id", "").isEmpty() && identities != null && identities.length() == 0;
            if (existing) { pendingEmail = ""; sentAt = 0; cooldown = 0; handler.removeCallbacks(cooldownTick); verify.setVisibility(View.GONE); setBusy(false, "该邮箱已经注册，请切换到登录模式"); return; }
            pendingEmail = e; sentAt = System.currentTimeMillis(); failures = 0; cooldown = 60; setBusy(false, "验证码已发送，请输入邮件中的完整数字验证码"); verify.setVisibility(View.VISIBLE); verify.setEnabled(true); primary.setText("重新发送验证码"); primary.setEnabled(false); code.requestFocus(); handler.removeCallbacks(cooldownTick); handler.post(cooldownTick); }); } catch (Exception ex) { setBusy(false, "验证码请求失败"); } }
    private void verifyRegistration() { String e = emailValue(); if (e == null || !passwordValid()) return; if (!e.equals(pendingEmail)) { resetPending("邮箱已改变，请重新获取验证码"); return; } if (System.currentTimeMillis() - sentAt > CODE_VALID_MS) { resetPending("验证码已过期，请重新获取"); return; } if (failures >= 5) { resetPending("尝试次数过多，请重新获取验证码"); return; } String token = code.getText().toString().trim(); if (!token.matches("\\d{6,10}")) { setStatus("请输入邮件中的完整数字验证码（当前邮件为 8 位）", true); return; } verify.setEnabled(false); setStatus("正在校验邮箱…", false); try { BackendClient.request("/auth/v1/verify", "POST", new JSONObject().put("email", e).put("token", token).put("type", "signup"), (ok, body, error) -> { if (!ok) { failures++; verify.setEnabled(failures < 5); setStatus(failures >= 5 ? "验证码连续错误 5 次，请重新获取" : error + "（还可尝试 " + (5 - failures) + " 次）", true); return; } pendingEmail = e; String tokenValue = body == null ? "" : body.optString("access_token", ""); if (!tokenValue.isEmpty()) saveSession(body, true); else loginAfterVerify(); }); } catch (Exception ex) { verify.setEnabled(true); setStatus("验证码校验请求失败，可重新点击验证", true); } }
    private void loginAfterVerify() { try { BackendClient.request("/auth/v1/token?grant_type=password", "POST", new JSONObject().put("email", pendingEmail).put("password", password.getText().toString()), (ok, body, error) -> { if (!ok) { verify.setEnabled(true); setStatus("邮箱已验证，但登录失败，请重试", true); return; } saveSession(body, true); }); } catch (Exception ex) { verify.setEnabled(true); setStatus("登录请求失败", true); } }
    private void saveSession(JSONObject body, boolean isRegister) { String token = body == null ? "" : body.optString("access_token", ""); JSONObject user = body == null ? null : body.optJSONObject("user"); if (token.isEmpty() || user == null || user.optString("id").isEmpty()) { setBusy(false, "服务端未返回有效会话，操作未完成"); return; } data.accountToken(token); data.accountRefreshToken(body.optString("refresh_token", "")); data.accountUserId(user.optString("id")); data.accountEmail(user.optString("email", pendingEmail)); BackendClient.heartbeat(data); BackendClient.syncProfileName(data, data.profileName(), (ok, error) -> { }); setStatus(isRegister ? "注册成功" : "登录成功", false); showSessionComplete(isRegister); }
    private void showSessionComplete(boolean isRegister) {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout card = Ui.vertical(this);
        card.setPadding(Ui.dp(this, 20), Ui.dp(this, 18), Ui.dp(this, 20), Ui.dp(this, 16));
        card.setBackground(Ui.bordered(Ui.SURFACE, Ui.BORDER, 18, this));
        LinearLayout title = Ui.horizontal(this);
        title.addView(Ui.icon(this, isRegister ? "✓" : "我", Color.WHITE), Ui.lp(Ui.dp(this, 42), Ui.dp(this, 42)));
        LinearLayout copy = Ui.vertical(this);
        copy.setPadding(Ui.dp(this, 11), 0, 0, 0);
        copy.addView(Ui.text(this, isRegister ? "注册成功" : "登录成功", 17, Ui.TEXT, true));
        copy.addView(Ui.text(this, "账号已安全保存到本机", 10, Ui.MUTED, false));
        title.addView(copy, new LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1));
        card.addView(title);
        TextView email = Ui.text(this, data.accountEmail(), 12, Ui.GREEN_DARK, true);
        email.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12));
        email.setBackground(Ui.bg(Ui.GREEN_SOFT, 11, this));
        card.addView(email, new LinearLayout.LayoutParams(-1, Ui.dp(this, 44)));
        Ui.margin(email, 0, 14, 0, 0, this);
        TextView detail = Ui.text(this, isRegister ? "邮箱验证完成，现在可以使用签到、资料同步等账号功能。" : "欢迎回来，已恢复你的简盒账号状态。", 10, Ui.MUTED, false);
        detail.setPadding(Ui.dp(this, 2), 0, Ui.dp(this, 2), Ui.dp(this, 14));
        card.addView(detail);
        Button enter = Ui.button(this, "进入我的页面", true);
        enter.setOnClickListener(v -> { dialog.dismiss(); complete(); });
        card.addView(enter, new LinearLayout.LayoutParams(-1, Ui.dp(this, 42)));
        dialog.setContentView(card);
        dialog.setCancelable(false);
        Window window = dialog.getWindow();
        if (window != null) { window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT)); window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); WindowManager.LayoutParams lp = window.getAttributes(); lp.dimAmount = .55f; window.setAttributes(lp); }
        dialog.show();
        if (dialog.getWindow() != null) dialog.getWindow().setLayout(Math.min(Ui.dp(this, 380), getResources().getDisplayMetrics().widthPixels - Ui.dp(this, 32)), WindowManager.LayoutParams.WRAP_CONTENT);
    }
    private void complete() { setResult(RESULT_OK, new Intent().putExtra(MainActivity.EXTRA_PAGE, 6)); finish(); }
    private void setBusy(boolean busy, String message) { loginMode.setEnabled(!busy); registerMode.setEnabled(!busy); primary.setEnabled(!busy && (!registering || cooldown <= 0)); verify.setEnabled(!busy && !pendingEmail.isEmpty() && failures < 5); boolean error=!busy&&(message.contains("失败")||message.contains("不正确")||message.contains("缺失")||message.contains("频繁")||message.contains("尚未")||message.contains("过期")||message.contains("错误")||message.contains("已经注册")||message.contains("暂未开放"));setStatus(message,error); }
    private void resetPending(String message) { pendingEmail = ""; sentAt = 0; failures = 0; code.setText(""); verify.setVisibility(View.GONE); verify.setEnabled(false); setStatus(message, message.contains("过期") || message.contains("改变") || message.contains("过多") || message.contains("已经注册") || message.contains("失败")); }
    @Override protected void onDestroy() { handler.removeCallbacks(cooldownTick); super.onDestroy(); }
}
