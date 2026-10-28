# RikkaHub SSH

基于 [RikkaHub](https://github.com/rikkahub/rikkahub) **3.1.1** 的 Android AI 客户端修改版，侧重 SSH 工作空间、远程文件操作和受控的 AI 应用管理。

**这是独立维护的非官方分支，与上游项目无隶属关系。请把本分支问题提交到本仓库，不要给上游作者增加支持负担。** 原项目与贡献者的版权、AGPL-3.0 许可证保持不变。

- 官网：[RikkaHub SSH](https://xiangshuhub.github.io/rikkahub-ssh/)
- 国内镜像与下载：[Gitee](https://gitee.com/chunjie008/rikkahub-ssh)
- 主仓库：[xiangshuhub/rikkahub-ssh](https://github.com/xiangshuhub/rikkahub-ssh)
- 反馈：[Issues](https://github.com/xiangshuhub/rikkahub-ssh/issues)
- QQ 群：**手游安全 · 1037044062** — [点击加入](https://qm.qq.com/q/qoWQXcdGf0)
- 自愿支持：[微信捐助说明](DONATE.md)

## 发布状态

当前正式版 **v3.1.5**：[Gitee 国内下载](https://gitee.com/chunjie008/rikkahub-ssh/releases/tag/v3.1.5) · [GitHub 备用下载](https://github.com/xiangshuhub/rikkahub-ssh/releases/tag/v3.1.5)。普通 Android 手机优先选择 `arm64-v8a`，模拟器可选择 `x86_64`，不确定时选择 `universal`。两边发布同一批 APK，签名与 SHA-256 一致，不使用上游下载通道。

v3.1.5 更新：供应商独立 SOCKS5 代理、SSH 同名专属助手、目录历史与书签、独立应用管理助手、工作空间入口优化、余额查询修正及工具授权简化。详见 [代理](docs/provider-proxy.md)、[工作空间助手](docs/workspace-assistant.md) 和 [文件浏览器](docs/workspace-browser.md) 文档。版本号跳过 v3.1.4；旧版可覆盖升级，升级前请备份。

| 构建 | 应用 ID |
| --- | --- |
| 正式版 | `me.rerere.rikkahub.ssh` |
| 调试版 | `me.rerere.rikkahub.ssh.debug` |

它们与原版 RikkaHub 可共存。调试版和正式版是两个应用，不可直接覆盖继承数据；迁移前请自行导出并妥善备份。正式版使用固定的独立签名密钥；仓库不包含任何签名私钥。

## 本分支修改

基线：上游标签 `3.1.1`，提交 `ad2c4430d50ba52392b2941367f98ef59614cb73`。沿用原有 Kotlin / Compose / Workspace 架构，而非整体替换成另一套 Agent。

### SSH 与 tmux

- 聊天内终端和文件快捷入口，使用助手绑定的工作空间及会话目录。
- tmux 会话/窗口列举、选择、显式创建与连接、确认后终止；允许明确选择普通 PTY。
- 关闭本地终端标签不会主动销毁远端 tmux；不自动安装 tmux、不回放命令。
- 文件区支持新建文件/目录；编辑支持版本冲突检查、UTF-8/UTF-16 BOM、CRLF、临时文件原子替换及草稿恢复。

详见 [SSH/tmux 迁移说明](docs/ssh-tmux-migration.md)。

### 工作空间四工具

SSH 与本地工作空间均提供：`workspace_read_file`、`workspace_write_file`、`workspace_edit_file`、`workspace_shell`。

- read 分页并返回整文件版本号。
- write 新建不覆盖；替换已有文件必须提交读取时的版本号。
- edit 支持基于原始快照的多个精确替换，拒绝不唯一、重叠或陈旧修改，展示 diff。
- SSH 直接写入限制在工作空间文件根目录，拒绝符号链接；缺少安全写入所需扩展时失败，不降级为截断覆盖。

详见 [四工具说明及限制](docs/workspace-four-tools.md)。

### AI 应用管理

使用内置“应用管理助手”，在其详情页可启用/停用管理能力（默认启用）。普通助手不携带这些工具；旧的全局管理授权已停用：

- 助手查询、新建、修改提示词/模型、绑定 Skill/MCP。
- Skill 查询、新建、编辑、删除；内置 Skill 只读，编辑保留附件，修改前备份。
- MCP 列表、新建、删除；新建默认禁用，凭据、登录与启用由用户在设置中处理。
- 助手工作空间绑定查询、绑定、切换、解绑；不创建/删除服务器或操作远端文件。
- 管理助手仅拥有管理工具，不提供搜索、SSH/shell、Skill 执行、记忆或外部 MCP 工具。改名不影响身份，复制不继承管理权限。
- 所有管理修改必须逐次确认，**全局自动批准不能绕过**；版本或影响范围变化后必须重新确认。
- 助手配置修改仅影响全局配置和新对话，不自动改写当前聊天快照或切换正在执行的任务。

详见 [应用管理工具说明](docs/app-management-tools.md)。

### 模型预设与界面

- 新增默认关闭的 Codex、Grok 入口，复用已有登录实现。
- OpenCode Zen 预设：Claude Haiku 5.5 使用 Anthropic Messages；DeepSeek V4.1 Flash、GLM 5.3 Flash 使用 OpenAI Responses。API Key 由用户自行填写。
- 工具授权设置页；应用标识、反馈、QQ群、捐助和更新通道改为本分支。
- 保留上游搜索、记忆、MCP、Skill、多模型聊天等基础能力。模型目录及服务端协议可能变化，预设不代表长期可用性承诺。

## 安全与隐私：请先阅读

> **普通工具的全局自动批准默认开启。** 不适合无监督操作重要服务器；请按需在设置中关闭。应用管理修改、手动 tmux 删除等确认不受此开关影响。

- SSH shell 权限等同于远端登录账户；文件工具的目录限制**不是 shell 沙箱**。建议使用专用低权限账户并备份重要文件。
- 历史聊天、剪贴板、日历等权限请按需启用。发送给云模型或联网工具的内容会到对应服务商，不要把私钥和令牌写进提示词。
- 文件版本检查是乐观并发保护，不是远端跨客户端的原子比较交换。特殊权限位、ACL、所有权等元数据不保证完整保留。
- 草稿恢复不是持续自动保存；Skill 整目录备份保留在应用私有目录，尚无完整一键恢复界面。
- 从 v3.1.2 起，移除 Firebase Analytics/Crashlytics SDK、插件及聊天事件上报，所有构建均不需要 `google-services.json`。其他联网功能仍按各自设置工作；这不代表已完成对全部上游联网行为的安全审计。

## 从源码构建

需要 JDK 21、Android SDK（由 Gradle 文件指定版本）、Node.js 22+、pnpm 10。保留子模块：

```sh
git clone --recurse-submodules https://github.com/xiangshuhub/rikkahub-ssh.git
cd rikkahub-ssh
# 设置 ANDROID_HOME，或通过 local.properties 指定 sdk.dir
cd web-ui
npx --yes pnpm@10 install --frozen-lockfile
npx --yes pnpm@10 run build
cd ..
bash ./gradlew :app:assembleGithubDebug -x :web:buildWebUi
```

arm64 调试 APK：`app/build/outputs/apk/github/debug/app-github-arm64-v8a-debug.apk`。

测试：

```sh
bash ./gradlew :app:testGithubDebugUnitTest :workspace:testDebugUnitTest -x :web:buildWebUi
```

工作空间测试包含嵌入式 SSH/SFTP，部分 tmux 测试依赖本机可用的 tmux。GitHub Actions 目前仅提供手动源码测试，不自动发布 APK，也不需要发布签名密钥。

### 正式发布约定

构建与签名流程、公钥指纹见 [发布说明](docs/RELEASING.md)。

- 使用并长期备份同一 release 签名密钥；通过 `RIKKAHUB_SIGNING_PROPERTIES` 指向仓库外的签名配置。不要提交私钥、密码或用户 API Key。
- 正式版本使用递增的 `versionCode` 与 `versionName`，GitHub tag 采用 `vX.Y.Z`。
- 更新检查优先请求 Gitee，连接/读取/解析失败、404 或缺少有效 APK 时再尝试 GitHub；单源请求最长 12 秒。有效的当前/旧版本不会再请求备用源。
- 下载链接跟随成功的更新源；下载任务启动后的网络失败不会自动切源，可从官网或另一平台手动下载。
- 两边稳定 Release 使用相同 tag 和 APK；附件名须以 `-release.apk` 结尾。镜像目前由维护者单向同步，并非自动双向镜像。
- 无 Release / 无合格 APK 时不提示更新。调试版不接收正式版覆盖更新。

## 致谢与许可证

感谢 [RikkaHub 原项目及所有贡献者](https://github.com/rikkahub/rikkahub)。本分支保留上游历史和版权声明，相关 SSH/tmux 行为在现有工作空间架构中适配实现；工具设计借鉴了 Pi 的 read/write/edit/shell 分工，并未嵌入 Pi 运行时。

许可证：[GNU Affero General Public License v3.0](LICENSE)。分发修改后的应用时，请履行许可证要求并提供对应源码。捐助仅支持本分支维护，不代表上游认可，也不解锁任何付费功能。
