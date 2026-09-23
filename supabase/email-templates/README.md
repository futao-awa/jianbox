# 简盒认证邮件配置

在 Supabase Dashboard 的 `Authentication > Email Templates > Confirm signup` 中配置：

- Subject：`【简盒】注册验证码`
- Body：使用 `confirmation.html`

注册和恢复密码模板都必须包含 `{{ .Token }}`，不能继续使用 `{{ .ConfirmationURL }}`。验证码长度由 Supabase Auth 配置决定，客户端兼容 6–10 位数字，当前项目邮件为 8 位。

在 `Authentication > Email Templates > Reset Password` 中配置：

- Subject：`【简盒】密码恢复验证码`
- Body：使用 `recovery.html`

建议在 `Authentication > Providers > Email` 中将 OTP 有效期设置为 600 秒，并保持服务端验证码与邮件发送限流开启。

2026 年 6 月起，新建的 Supabase Free 项目使用默认 SMTP 时不能自定义认证邮件模板。此类项目需要先在 `Authentication > SMTP Settings` 配置自有 SMTP，才能让主题和正文变成中文。
