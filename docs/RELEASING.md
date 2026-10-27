# 正式版发布

首个正式版：`v3.1.2`，`versionCode=195`，应用 ID `me.rerere.rikkahub.ssh`，最低 Android 8.0 / API 26。

## 签名

官方发布包的签名证书 SHA-256 指纹：

```text
43e4142be99dcaa84fcce0f54f6d3bc40db9c836e3805439e7eed77bf56f1531
```

这是公钥证书指纹，不是私钥或密码。以后同包名升级必须沿用此签名密钥。自行编译并使用其他密钥签名的包不能直接覆盖官方包。

私钥和签名配置须保存在仓库外，并做安全的异地备份。配置格式：

```properties
storeFile=/absolute/private/path/release.p12
storePassword=YOUR_PRIVATE_PASSWORD
keyAlias=rikkahub-ssh
keyPassword=YOUR_PRIVATE_PASSWORD
```

不要提交真实配置，不要把密钥、密码或整个配置文件放到 GitHub Release 附件。

## 构建

先按 README 构建前端并准备 Android SDK / JDK 21；然后：

```sh
export RIKKAHUB_SIGNING_PROPERTIES=/absolute/private/path/signing.properties
bash ./gradlew :app:assembleGithubRelease :app:testGithubDebugUnitTest :workspace:testDebugUnitTest \
  -x :web:buildWebUi
```

Firebase SDK 已移除，无需 Google Services 配置，也不要再排除已不存在的 Google Services 任务。

输出：`app/build/outputs/apk/github/release/` 下的 arm64-v8a、x86_64、universal 三个 `*-release.apk`。使用 Android SDK 的 `apksigner verify --verbose --print-certs` 校验每个文件。检查应用 ID、版本号及证书指纹，然后在设备上验证安装/启动。混淆映射 `app/build/outputs/mapping/githubRelease/mapping.txt` 应随构建在维护者私有存储中留档，用于诊断问题。

## 发布检查

1. 更新 `versionCode`（严格递增）、`versionName` 与文档，运行测试。
2. 构建签名 release，验证签名、包名、版本、APK 内没有 debuggable 标志或 Firebase 初始化组件。
3. 保持代码提交与产物一致；为该提交创建 `vX.Y.Z` tag。
4. 创建 GitHub Release 草稿，上传三个 APK 和 `SHA256SUMS`，核对附件 SHA-256，再公开并标记 Latest。
5. Release 说明须提醒：与原版/调试版数据独立，普通工具全局自动批准默认开启，建议低权限 SSH 账户。
6. 检查 GitHub `/releases/latest` 元数据指向正确版本。设备网络不可达 GitHub 时，应用内更新可能失败；这不是绕过签名校验的理由。

更新器只接受本仓库的稳定 `vX.Y.Z` Release 与 `-release.apk` 附件；不会给调试包提示安装不同包名的正式包。不要发布不同签名、不同应用 ID 的 APK 到同一个正式更新通道。
