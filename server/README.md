# 简盒后台服务

使用 Node.js 20+，不依赖第三方包。提供账号注册/登录、活跃统计、版本策略和玻璃风格管理页面。

```powershell
$env:JIANBOX_ADMIN_TOKEN='请替换为高强度随机令牌'
npm start
```

默认监听 `8787` 端口，管理页位于 `/admin`。正式部署时必须放在 HTTPS 反向代理后，并把 Android 工程 `BackendClient.BASE_URL` 改成实际域名。用户无需在 App 内配置地址。

当前数据保存在 `server/data` JSON 文件中，适合单机小规模部署。正式公开服务建议迁移到 PostgreSQL，并配置备份、请求限流、邮件验证和 FCM 推送凭据。
