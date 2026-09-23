# 简盒 JianBox

简盒是一款以 Android WebView 内置浏览器为核心的悬浮工具箱，集成多标签浏览、悬浮球与侧边栏、网页收藏、下载管理、备忘录、剪贴板、提醒、性能悬浮窗、账号同步、签到和 Supabase 管理后台。

当前交付版本：`3.9.6`（versionCode 20）。

## 项目结构

- `app/src/main`：Android 客户端源码。
- `supabase/migrations`：数据库结构、RLS、签到、硬币、用户和匿名安装统计迁移。
- `supabase/functions`：管理后台 Edge Functions。
- `supabase/admin-console`：白绿玻璃风格的静态管理页面。
- `supabase/email-templates`：中文注册/找回密码邮件模板。
- `server`：早期 Node.js 独立后端示例；当前正式方案以 Supabase 为准。
- `docs`：开源与部署说明。

## 本地构建

环境要求：JDK 17、Android SDK 36、Android Studio 或 Gradle Wrapper。

Android 客户端只需要 Supabase 项目 URL 和 publishable key。不要把 service-role 或 secret key 放入 Android、网页或 Git 仓库。可在本地 `gradle.properties` 中填写：

```properties
SUPABASE_URL=https://your-project-ref.supabase.co
SUPABASE_ANON_KEY=sb_publishable_replace_me
```

然后构建：

```powershell
./gradlew.bat :app:lintDebug :app:assembleDebug
```

也可以在命令行通过 `-PSUPABASE_URL` 和 `-PSUPABASE_ANON_KEY` 传入。未配置时本地浏览器等离线功能仍可编译，云端账号、资料和签到功能会提示尚未配置。

## Supabase 部署

1. 按编号执行 `supabase/migrations/001_jianbox.sql` 到 `008_admin_audit_actor_index.sql`。
2. 部署 `admin-console` Edge Function。
3. 将自己的 `profiles.is_admin` 设置为 `true`。
4. 复制 `supabase/admin-console/config.example.js` 为 `config.js`，只填写项目 URL 和 publishable key。
5. 把管理页面部署到任意 HTTPS 静态站点。

详细流程见交付包中的《简盒 v3.9.6 管理后台详细部署文档》。

## 开源与安全

- 开源包不包含真实 Supabase 项目标识、publishable key、service-role/secret key、本地 SDK 路径、APK、构建缓存或签名文件。
- 数据权限必须由 RLS 与 Edge Function 服务器端鉴权保护，不能依赖前端隐藏按钮。
- 正式发布应使用自行创建并妥善备份的 release keystore；debug 签名只用于测试。


## 许可证

自有代码采用 MIT License。第三方组件与参考项目的权利及许可证归各自权利人，详见 `THIRD_PARTY_NOTICES.md`。应用名称、开发者名称及品牌素材不因代码开源自动授予商标使用权。
