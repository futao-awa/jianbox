# 简盒 Supabase 后端

项目 ref 请替换为你自己的 Supabase 项目。Android 客户端仅使用 Supabase publishable / anon key 与标准 Auth、PostgREST 接口；绝不能向 App、Git 仓库或聊天中提供 service_role 密钥。

## 一次性项目配置

1. 在 Supabase Dashboard 的 SQL Editor 按顺序执行 migrations/001_jianbox.sql、migrations/002_profile_coins.sql 和 migrations/003_backfill_existing_profiles.sql。三份脚本分别创建用户档案、版本策略、使用事件、RLS、管理员统计函数、签到硬币函数，并为迁移前已注册的账号补建资料行。若使用 Supabase MCP 或 CLI，请将三份文件作为按顺序执行的迁移应用。
2. Authentication → Providers → Email：启用 Email，关闭 Phone；建议开启 Confirm email。在 Authentication → URL Configuration 的 Additional Redirect URLs 添加 `jianbox://auth/recover`，用于密码重置后回到 App。
3. Authentication → Email Templates：分别将 email-templates/confirmation.html 粘贴到 Confirm signup，将 recovery.html 粘贴到 Reset password。
4. Project Settings → API：只复制 publishable key（或 legacy anon key）。构建时传入，不写入源码：

       .\gradlew.bat :app:assembleDebug -PSUPABASE_URL='https://你的项目.supabase.co' -PSUPABASE_ANON_KEY='你的 publishable key'

5. 注册第一个管理账号后，在 SQL Editor 以该账号的 auth.users.id 执行：

       update public.profiles set is_admin = true where id = '你的用户 UUID';

## 版本发布与强制更新

app_updates 只允许客户端读取 is_published=true 的记录。发布新版本示例：

    insert into public.app_updates
      (version_code, version_name, min_version_code, apk_url, notes, is_published, force_update)
    values
      (13, '3.7.1', 12, 'https://example.com/jianbox-v3.7.1.apk', '修复与优化', true, false);

若旧版必须退出，将 force_update=true 且把 min_version_code 调整为最低允许版本号。必须同时填写可访问的 apk_url，否则客户端只能提示用户联系管理员。

## 使用统计与管理员服务

客户端登录后会更新自己的 profiles.last_seen_at 并写入 app_open 事件。jianbox_admin_metrics() 返回注册用户数、24 小时活跃与 30 天活跃，只有 profiles.is_admin=true 的登录用户可以执行。

functions/admin-stats/index.ts 是可选的 Edge Function 示例，适合管理面板调用。部署时将 SUPABASE_SERVICE_ROLE_KEY 只保存在 Edge Function Secrets 中，并启用 JWT 验证；它不会被 Android APK 使用。

## 管理控制台与匿名安装统计

`migrations/006_admin_console_and_install_metrics.sql` 新增匿名安装统计、管理员审计日志和扩展后的管理员指标。`migrations/007_allow_trusted_admin_bootstrap.sql` 使首个管理员可以在 SQL Editor 中安全地完成初始化，但登录客户端仍无法把自己提升为管理员。执行迁移后，客户端会写入随机安装 UUID 与最近活跃时间；该 UUID 不包含匿名用户的邮箱、昵称或硬件标识。未注册人数应理解为“尚未关联登录账号的匿名安装设备数”，仅从支持该版本的客户端开始累积，不能追溯旧版历史。

静态管理台位于 `admin-console/`，Edge Function 位于 `functions/admin-console/`。完整部署步骤见 `admin-console/README.md`。管理台只能使用 publishable key，用户邮箱、封禁和删除操作只由已验证 `is_admin=true` 的 Edge Function 在服务端执行。

## 当前客户端行为

- 仅提供邮箱 + 密码注册、登录与找回密码入口；不支持手机号注册。
- 启用确认邮箱时，注册成功后客户端会引导用户先确认邮箱再登录。
- 账号为可选项；未登录也能使用所有本地工具功能。
- 客户端用公开 key 查询已发布版本策略，管理权限始终在 Supabase RLS / Edge Function 服务端校验。
