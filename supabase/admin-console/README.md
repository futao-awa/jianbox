# 简盒运营控制台

管理页采用紧凑的白绿运营后台布局，桌面端使用左侧导航，窄屏使用底部导航。后端由 Supabase Auth、Postgres、Storage 和 `admin-console` Edge Function 提供。

## 功能

- 概览：注册用户、24 小时/30 天活跃、匿名安装、版本与公告统计。
- 用户：按邮箱、昵称或 UUID 搜索，查看硬币、签到、活跃时间，执行封禁、解封和删除。
- 版本：上传 APK、计算 SHA-256、保存草稿、发布、撤回、强制更新和最低版本配置。
- 公告：设置级别、生效时间、结束时间和目标版本范围。
- 审计：记录用户治理、版本和公告的高权限操作。
- 状态：检查 Edge Function、APK Storage 和管理员 RLS。

## 架构限制

Supabase Edge Functions 是 API 运行环境。官方会把 `GET` 返回的 `text/html` 改写为 `text/plain`，因此不能用函数 URL 托管本页面。

- 页面：将本目录的 `index.html`、`style.css`、`app.js`、`config.js` 部署到 Cloudflare Pages、Netlify、Vercel、GitHub Pages 或自有 HTTPS 服务器。
- API：`https://你的项目.supabase.co/functions/v1/admin-console`
- APK：保存在公开下载、管理员可写的 `app-releases` Storage bucket。

## 部署

1. 按顺序应用 `supabase/migrations/001` 至 `010`。
2. 将管理员对应的 `public.profiles.is_admin` 设置为 `true`。
3. 部署 Edge Function：

   ```bash
   supabase functions deploy admin-console --no-verify-jwt
   ```

   `--no-verify-jwt` 只关闭网关的旧式 JWT 预检。函数内部仍使用 `auth.getUser()` 验证当前 JWT，并再次检查 `profiles.is_admin`。

4. 复制 `config.example.js` 为 `config.js`，只填写项目 URL 和 publishable key。
5. 将四个静态文件上传到 HTTPS 静态站点，确保 `index.html` 位于站点根目录。

## 安全

- 浏览器端只能包含 publishable key，禁止放入 service-role 或 secret key。
- service-role 仅由 Supabase Edge Function 环境提供。
- 数据表启用 RLS；Storage 上传、覆盖和删除仅允许管理员。
- 管理员不能在控制台中封禁或删除自己和其他管理员。
- 删除用户或已托管 APK 不可恢复，页面会二次确认并记录审计日志。

`rollout_percent` 已作为后续灰度发布字段保留，当前 Android 客户端仍按完整发布处理。Android 3.9.7（versionCode 21）起会读取已发布的稳定版、测试版和 `app_announcements` 公告；公告在每台设备上按 ID 显示一次。
