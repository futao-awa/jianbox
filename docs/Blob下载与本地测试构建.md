# Blob 下载与本地测试构建

版本：3.9.9，versionCode 23。

## 网页临时文件下载

`blob:` 是由网页渲染器持有的对象地址，不能交给 `HttpURLConnection` 下载。全屏和悬浮浏览器现在将其交给原网页读取，再以 48 KiB 分块写入应用下载目录。

- 使用 AndroidX WebKit 在文档开始时安装脚本，以捕获网页立即撤销的临时链接及未挂入 DOM 的下载按钮文件名；旧 WebView 降级为页面加载后的安装。
- 临时 Blob 引用保留两分钟，最多 8 个、合计 256 MiB。过期或无法读取时显示重新下载提示。
- 下载开始前生成单次随机令牌；网页不能自行创建原生下载任务、指定文件路径或调用通用文件 API。跨域框架消息仅发送到 Blob 所属源。
- 单文件上限 512 MiB，检查分块顺序、Base64 长度及总字节数。先写临时文件，完整接收后才保存为最终文件；同名文件自动编号。
- 读取超时、关闭或切换页面时终止传输并清理本次临时文件。下载期间应保留原网页。
- 失败任务的“返回网页重试”打开原页面，不会把过期 Blob 链接再次发给 HTTP 下载器。普通 HTTP/HTTPS 下载仍使用原下载服务。

## 配置隔离

真实云端配置统一存放于 Git 仓库外的 `test-build.properties`，以 `-PtestBuildConfigFile=绝对路径` 显式指定。不要把原版源码中的管理后台配置文件复制进仓库。

必需字段：`SUPABASE_URL`、`SUPABASE_ANON_KEY`。可选测试签名字段：`SIGNING_STORE_FILE`、`SIGNING_STORE_PASSWORD`、`SIGNING_KEY_ALIAS`、`SIGNING_KEY_PASSWORD`。Windows 路径建议使用 `/`，避免 properties 的反斜杠转义。

这些值只写入 Debug 的生成文件和 APK。Release 的 `BuildConfig` 字段保持为空，源码不读取默认本地配置或环境变量。构建会拒绝仓库内的配置文件以及服务端密钥。

签名私钥无法从 APK 中恢复；如没有旧签名私钥，新签名 APK 不能直接覆盖旧安装。不要为了安装测试包直接删除仍需保留的应用数据。

## 验证

```powershell
./tests/run-browser-tests.ps1 -JavaHome '你的 JDK 目录'
./gradlew.bat -PtestBuildConfigFile=C:/local/jianbox/test-build.properties :app:lintDebug :app:assembleDebug :app:generateReleaseBuildConfig
```

自动测试包含 56 项 Java 检查与 14 个脚本用例，覆盖完整二进制传输、立即撤销 URL、下载文件名、分块顺序及大小限制、重复文件名、取消、空文件、跨域消息目标和配置外的原浏览器回归。

设备验收：分别在全屏、悬浮浏览器访问 `https://haowallpaper.com/homeViewLook/15063087959870784` 并使用网页下载按钮，检查文件内容及名称；再测试立即撤销 Blob URL、同名文件、多分块文件、关闭页面和返回网页重试。站点需要登录或下载权限时，沿用网页本身的授权流程。
