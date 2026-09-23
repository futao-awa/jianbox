# 第三方项目与服务说明

简盒使用 Android 平台 API、Android WebView、Gradle/Android Gradle Plugin，并接入 Supabase Auth、Postgres、Edge Functions 和相关客户端协议。以上项目、平台与服务的商标、版权及许可证归各自权利人。

本仓库中的功能设计可能参考主流浏览器、下载器、广告过滤、沉浸式翻译、开发者工具和密码管理器的公开交互思路，但除非源码目录或文件头明确标注，不表示直接捆绑对应项目的代码，也不表示获得其商标或品牌授权。

发布者在加入任何第三方源代码、规则列表、图标、字体、网页解析器、媒体提取器或二进制依赖前，应：

1. 核对对应版本的许可证与再分发条件。
2. 保留版权声明、许可证文本和修改记录。
3. 不绕过 DRM、付费墙、访问控制或站点授权。
4. 对广告过滤、恶意网址检测和翻译规则提供来源、更新时间与误报反馈渠道。
5. 在正式发布前生成准确的依赖清单与 Software Bill of Materials。

Supabase publishable key 可供公开客户端使用，但必须配合 RLS 与服务器端鉴权；Supabase service-role/secret key 绝不能进入 Android APK、静态网页或公开仓库。
