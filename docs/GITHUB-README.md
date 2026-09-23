# 简盒 Jianbox

简盒是一款以 Android WebView 内置浏览器为核心的悬浮工具箱，包含多标签浏览、悬浮球、快捷工具、剪贴板备忘、提醒、下载管理、插件开关、账号同步和每日签到。

## 开源包安全边界

GitHub 源码包不包含真实 Supabase URL、publishable key、service role key、本机 SDK 路径、APK、Gradle 构建缓存或签名文件。公开的 `sb_publishable`/anon key 仍应通过本地配置提供，绝不能把 `service_role` 或其他服务器密钥放入 Android 项目。

## 环境要求

- Android Studio 或 JDK 17
- Android SDK 36（可按项目需要降低 compileSdk）
- Gradle Wrapper
- 一个已启用 Email Auth 的 Supabase 项目

## 配置与构建

不要把真实配置提交到 Git。可以在本机 `gradle.properties` 中填写，或直接通过命令行传入：

```powershell
./gradlew.bat :app:assembleDebug `
  -PSUPABASE_URL="https://your-project-ref.supabase.co" `
  -PSUPABASE_ANON_KEY="sb_publishable_xxx"
```

未配置时仍可编译和浏览本地功能，账号、云端资料和签到会显示“账号服务未配置”。

## Supabase 初始化

在 SQL Editor 或 Supabase MCP 中按顺序执行：

1. `supabase/migrations/001_jianbox.sql`
2. `supabase/migrations/002_profile_coins.sql`
3. `supabase/migrations/003_backfill_existing_profiles.sql`
4. `supabase/migrations/004_fix_daily_checkin_return_aliases.sql`
5. `supabase/migrations/005_fix_daily_checkin_conflict_target.sql`

最后一份迁移使用主键约束作为 `ON CONFLICT` 目标，避免 PL/pgSQL 输出字段 `checkin_date` 与表字段发生歧义。客户端签到只允许 `authenticated` 角色执行，函数内部仍会校验 `auth.uid()`。

Authentication 中启用 Email、关闭 Phone；如果开启邮箱确认，请配置 `jianbox://auth/recover` 重定向地址和中文邮件模板。找回密码流程遵循 Supabase 的反账号枚举策略，不通过公开接口泄露邮箱是否存在。

## 目录说明

- `app/src/main`：Android 客户端
- `supabase/migrations`：数据库结构、RLS 和签到函数
- `supabase/functions`：可选的管理统计 Edge Function 示例
- `docs`：开源发布、部署与安全说明

## 发布前检查

- 确认 Git 历史中没有 `gradle.properties`、`.env`、service role key、签名文件和 `app/build`。
- 使用 RLS 和 `authenticated` 角色保护用户数据，禁止把 service role key 放入 APK。
- 为自己的项目补充许可证、隐私政策、第三方许可证和联系方式。
- 生产包使用自己的签名密钥，不要复用示例 debug keystore。

## 联系与定制

开发者：芙桃。欢迎交流 Android 软件、浏览器、网页、后台服务、界面美化和功能定制需求。

- QQ 邮箱：tyy_5201314@foxmail.com
- QQ：3776821683

## 许可证

简盒自有开源代码采用 MIT License。第三方项目仅作设计和技术思路参考，其许可证与权利归各自权利人，详见 `THIRD_PARTY_NOTICES.md`。简盒名称、开发者名称和相关品牌标识不因代码开源而自动授予商标使用权。

开源参考项目及其许可证请见 App 内“关于”页面、`supabase/README.md` 和 `THIRD_PARTY_NOTICES.md`。
