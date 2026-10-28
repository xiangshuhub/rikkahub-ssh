# AI 应用管理工具

## 开启方式

使用默认内置的“应用管理助手”。其详情页有“启用应用管理能力”开关，默认启用；停用后不再提供管理工具，已经生成的待执行调用也会在执行时被拒绝。

旧版“设置 → 工具授权”里的四个管理开关已移除，旧授权数据不再参与权限判断。普通助手不注册管理工具，也不注入管理工具说明。升级不切换当前助手，不改写对话历史或配置快照。

管理身份由保留的内置助手 UUID 标记，而非名称或提示词；改名不丢失身份，复制助手会生成新 UUID，因此不继承管理权限。内置助手不可删除，可停用。模型不能设置管理身份、指定新助手 ID 或改变启停权限。

管理助手仅拥有三个管理工具（工作空间查询/绑定包含在助手管理工具中）。不提供 shell、SSH 文件操作、搜索、记忆、历史检索、Skill 执行或外部 MCP 调用；也剥离模型原生工具。配置归一化与请求处理同时限制这些能力，旧的会话覆盖配置不能重新打开它们。

## 能力

| 工具 | 支持动作 |
| --- | --- |
| `app_management_assistants` | `list`、`read`、`create`、`update`、`read_backup`、`list_workspaces`、`preview_workspace_binding` |
| `app_management_skills` | `list`、`read`、`save`、`delete`、`read_backup` |
| `app_management_mcp` | `list`、`create`、`delete` |

助手支持名称、系统提示词、模型 UUID（或 `default`）、Skill 集合和 MCP 集合。绑定字段是替换集合，不是追加；模型应先读取再修改。专用管理助手具有这些管理能力，但不能为自己绑定工作空间、Skill 或 MCP 工具。

Skill `save` 创建或修改完整 `SKILL.md`，需要 YAML `name` 和 `description`；工具创建的名称限小写英文字母、数字和连字符。编辑只替换 `SKILL.md`，保留附属脚本、参考文件和资源，不执行脚本。内置 Skill 只读。附属文件单独编辑、助手删除、MCP 启用/登录/自动测试连接不在本批范围。

MCP 支持 SSE 和 Streamable HTTP，新增配置始终 `enable=false`。名称使用 1–64 个 ASCII 字母/数字，以满足现有 MCP 工具命名规则。URL 必须是 HTTP(S)，不能包含用户信息、query 或 fragment，避免把密钥带进聊天。授权、请求头、启用和连接请到 MCP 设置操作。已有配置的列表不会返回 URL、请求头、OAuth 令牌或客户端密钥。

## 确认与作用域

- 查询不请求额外确认；所有修改和删除都需要用户逐次确认。
- `app_management_` 工具不会被全局自动批准或永久授权绕过。
- 对现有对象操作需要最新 `if_revision`；Skill 创建使用 `if_revision=new`。
- 删除 Skill 前先 `read`；删除 MCP 前先 `list`。把返回的 `delete_confirmation` 原文放入删除调用，使确认卡片展示目标、影响范围及绑定助手。
- `delete_confirmation` 是影响说明，不是用户批准凭证。模型填入它后仍须等待 UI 审批。
- 删除会清理全局助手引用；MCP 删除还会断开连接、取消重连/授权状态，并从持久配置移除请求头和 OAuth 凭据。不会在本地管理备份中另存 MCP 凭据，也不会向远端授权服务器请求撤销令牌。
- 助手创建和更新必须指定 `scope=new_conversations`。已有对话配置快照不被改写，也不会自动切换当前聊天的助手。
- 版本检查和跨会话共享的管理工具锁可防止陈旧写入和管理工具的并发修改，但不构成跨 UI、数据库、外部文件操作的全局事务。

## 助手工作空间绑定

复用 `app_management_assistants`，一个助手最多绑定一个工作空间。

1. `list_workspaces` 列出 ID、名称、类型、配置状态和修改时间；不返回 SSH 地址、用户名或凭据，不主动连接服务器。
2. `read` / `list` 的助手摘要包含 `workspace_id`；`null` 表示未绑定。
3. `preview_workspace_binding` 传入现有助手 `id`（新建助手则传 `name`）、目标 `workspace_id` 和 `scope=new_conversations`。目标填 `none` 表示解绑。
4. 把返回的 `workspace_confirmation` 和现有助手的 `if_revision` 放入 `update`；新建助手使用 `create`。同时传入同一目标 `workspace_id` 和 scope，等待用户确认。

确认说明包含助手身份、旧/新工作空间名称、类型、ID、配置修改版本和作用域。保存前复核目标存在性及确认内容；权限撤销、助手版本过期、目标被删除/重命名/更新时会拒绝旧请求。省略 `workspace_id` 保留原绑定；显式 JSON null 无效，解绑须使用字符串 `none`。

解绑不会删除工作空间或其文件。仅保存全局助手配置，不改写已有聊天快照、不切换当前任务，也不保证目标连接可用。工作空间数据库与助手设置之间不是跨存储事务，外部并发修改仍存在最终复核之后的短暂竞态窗口。

## 备份与恢复

修改助手提示词/配置前备份原助手；修改或删除 Skill 前备份整个 Skill 目录。备份位于应用私有的 `noBackupFilesDir/app-management-backups`，不自动发送模型，也不进入 Android 自动备份。

成功写入/删除返回 `backup_id`。对应类别的 `read_backup` 可读取原助手的安全字段，或原 `SKILL.md` 内容。恢复需再执行 `update`/`save` 并重新确认。Skill 附属文件的完整备份仍留在私有目录，本批未提供一键整目录恢复界面。

## 示例

- “新建一个服务器运维助手，提示词要求先只读排查，再提出修改计划。只对新对话生效。”
- “新建 server-log-review Skill，并绑定到运维助手。”
- “新增名为 MyServer 的 Streamable HTTP MCP，地址为 https://example.com/mcp，保持禁用。”
- “查看 test-skill 的内容和使用范围，确认后删除它。”
- “列出 MCP，删除 MyServer 并移除助手绑定，先让我确认。”

## 验证

```sh
ANDROID_HOME=/home/gui/Android/Sdk bash ./gradlew :app:testGithubDebugUnitTest \
  --tests me.rerere.rikkahub.data.ai.tools.AppManagementToolsTest \
  --tests me.rerere.rikkahub.data.ai.tools.AppManagementPolicyTest \
  --tests me.rerere.rikkahub.data.preferences.ToolApprovalPolicyTest \
  --tests me.rerere.rikkahub.data.datastore.DefaultProvidersTest \
  -x :web:buildWebUi
```

覆盖普通助手无管理权限、管理助手启停/旧调用撤销、禁止身份提升及自身工具绑定、默认助手迁移、会话覆盖约束、已删除助手不继承当前管理身份、跨类别绑定、版本冲突、创建重名、备份读取、Skill 编辑保留附件、内置 Skill 保护、删除绑定清理、MCP 默认禁用及凭据脱敏。工具执行测试使用真实 SettingsStore 和测试 Skill 文件适配器；未在真实模型或 MCP 服务器上做端到端验证。
