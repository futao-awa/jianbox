# 简盒 JianBox

简盒是一款以 Android WebView 内置浏览器为核心的悬浮工具箱，集成多标签浏览、悬浮球与侧边栏、网页收藏、下载管理、备忘录、剪贴板、提醒、性能悬浮窗、账号同步、签到和 Supabase 管理后台。

当前版本：`3.9.9`（versionCode 23）。修复浏览器跳转、媒体嗅探及网页 Blob 下载，详见 [浏览器回归](docs/浏览器跳转与媒体嗅探回归.md) 和 [Blob 下载与测试构建](docs/Blob下载与本地测试构建.md)。

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

开源源码不含真实云端配置或签名文件。默认构建可使用本地浏览器功能：

```powershell
./gradlew.bat :app:lintDebug :app:assembleDebug
```

需要云端功能的测试包，通过 `-PtestBuildConfigFile` 显式指定 **Git 仓库之外** 的本机 properties 文件：

```powershell
./gradlew.bat -PtestBuildConfigFile=C:/local/jianbox/test-build.properties :app:lintDebug :app:assembleDebug
```

该文件包含 `SUPABASE_URL` 和 `SUPABASE_ANON_KEY`，只能使用 publishable key 或旧版 anon key；禁止 service-role/secret key。可选的 `SIGNING_STORE_FILE`、`SIGNING_STORE_PASSWORD`、`SIGNING_KEY_ALIAS`、`SIGNING_KEY_PASSWORD` 用于指定测试签名。

配置仅注入 Debug 包。Release 的云端字段保持为空；不再从源码中的 `gradle.properties` 或环境变量自动导入配置。配置文件、签名私钥及 APK 均不得提交到 Git。详见 [测试构建说明](docs/Blob下载与本地测试构建.md)。

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
