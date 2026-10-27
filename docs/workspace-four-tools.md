# 工作空间四工具

本地和 SSH 工作空间均提供 `workspace_read_file`、`workspace_write_file`、`workspace_edit_file`、`workspace_shell`。复用现有连接池、工作空间绑定和审批设置，不创建另一套 SSH 或 Pi 运行时。

## 调用方式

读取：

```json
{"path":"/srv/project/main.kt","offset":1,"limit":200}
```

返回 `text`、整个文件的 `revision`、`total_lines`，未读完时返回 `next_offset`。行号从 1 开始，每次最多 2000 行和约 48 KiB 文本。过长单行会要求使用 shell 检查子串，而不是悄悄丢失行内内容。分页并不等于流式读取：SSH 文本文件仍有 2 MiB 总大小限制；本地保持 8 MiB 上限。图片沿用原读取方式，无文本版本号。

新建（父目录须存在）：

```json
{"path":"/srv/project/new.txt","text":"hello\n","expected_revision":"new"}
```

覆盖写入时，`expected_revision` 必须是此前 read 返回的版本号。应优先用 edit 做局部修改，而不是重新生成整个文件。

批量精确编辑：

```json
{
  "path":"/srv/project/main.kt",
  "expected_revision":"<read 返回的 revision>",
  "edits":[
    {"old_text":"val first = 1","new_text":"val first = 2"},
    {"old_text":"val second = 3","new_text":"val second = 4"}
  ]
}
```

每段匹配都针对同一份原始文件，必须唯一且不重叠；全部校验后一次保存。拒绝无匹配、多匹配、重叠、空旧文本和 `replace_all=true`。不再自动进行宽松匹配。旧的顶层 `old_text/new_text` 仍可用于单次替换，但也要求版本号。执行前可以预览各段 diff，执行后显示实际文件 diff。

## SSH 安全边界

- AI 直接写入仅限配置的工作空间文件根目录。shell 仍具有远端登录账户的权限；直接写入限制不是 shell 沙箱。
- 拒绝目标及父路径的符号链接；不能只依赖 REALPATH，因为部分服务器返回的是词法路径。
- 覆盖时沿用安全编辑保存逻辑：版本复核、同目录临时文件、POSIX rename。保留 UTF-8/UTF-16 BOM、CRLF 和普通权限位。
- 新建先上传临时文件，再通过 `hardlink@openssh.com` 无覆盖发布最终文件名；同名文件存在即失败。
- 覆盖需要 `posix-rename@openssh.com`，新建需要 `hardlink@openssh.com`。扩展不支持时拒绝操作，不降级为直接截断写入。可改用手动文件管理功能。
- 保留现有审批规则，包括用户选择的全局自动批准设置；文件冲突、路径和编码检查不会被自动批准跳过。
- 版本检查是乐观并发保护，不是跨远端客户端的原子比较交换。无法彻底排除恶意远端进程在检查与保存之间替换路径的竞态。
- 不保证保留 ACL、扩展属性、所有权和特殊模式位；有这类需求请使用专门的远端管理流程。

## 验证

运行 `:app:testGithubDebugUnitTest :workspace:testDebugUnitTest`（前端已构建时可排除 `:web:buildWebUi`；不再需要 Firebase 配置）。

覆盖批量编辑的原始快照语义、唯一性/重叠/缺失匹配、分页和输出上限；嵌入式 SSH/SFTP 集成测试覆盖新建不覆盖、特殊文件名、编码/CRLF、旧版本拒绝、根目录越界及符号链接拒绝。没有对真实模型和用户远端服务器进行端到端修改测试。
