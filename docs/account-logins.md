# 官方账号登录

在「设置 → 提供商 → 添加」中选择账号类型，然后在详情页登录。
API Key 提供商仍然保留；账号类型不允许自定义 API 地址，避免将订阅凭据发往第三方网关。

| 类型 | 登录方式 |
| --- | --- |
| ChatGPT | 保留已有浏览器授权 |
| Grok / X | 保留已有设备码授权 |
| Claude Pro / Max | 默认浏览器回调；另有复制授权码入口；两种方式都支持手动粘贴授权结果 |
| GitHub Copilot | 设备码授权（github.com 账号） |
| Kimi Code | 设备码授权 |
| Meta Muse | 设备码授权 |
| OpenRouter OAuth | 浏览器回调或手动粘贴回调网址 / 授权码 |

设备码流程仍需在浏览器中确认，但不要求浏览器回调 App。授权期间使用临时前台服务保活；
取消或完成后释放。进程被系统强制结束时，未完成的授权需要重试。

## 协议与凭据

- 参考本地 `pi/packages/ai/src/auth/oauth/` 的对应实现移植，并非使用 Pi 服务转发。
- Claude 使用 PKCE、state 校验、Bearer 鉴权、OAuth beta 标识以及协议要求的 Claude Code system 身份块。
  原有 system 内容、工具及消息保留，由现有 Claude 编解码器处理。
- Copilot 的 GitHub 令牌只用于换取短期推理令牌；推理域名来自令牌，限制在官方 GitHub Copilot 域名下。
  Claude 模型走 Messages，GPT/Grok/OSWE/MAI 模型走 Responses，其余模型走 Chat Completions。
  模型列表过滤禁用策略；不会自动替用户启用模型策略。不包含 GitHub Enterprise 自定义域名。
- Kimi 的访问令牌用于 Coding API，刷新令牌用于续期。
- Meta 的身份令牌用于签发 Model API key，而不是直接用于推理；身份过期后须重新登录。
- OpenRouter 授权签发用户控制的 API Key，不是消费级包月订阅；按账号余额及权限计费。
  模型列表使用 `/models/user`；Key 被撤销后需重新登录。
- 新账号使用既有账号凭据存储、串行刷新和 401 单次重试机制。退出登录仅移除本地凭据，不撤销服务端授权。
  备份和导出的设置可能含账号凭据，应按密钥文件保管；本次没有改变全应用的存储/备份加密机制。
- 账号详情页展示额度或预算面板；原有 ChatGPT/Grok 额度功能不变。

## 额度与预算

| 账号 | 数据来源 | 展示 |
| --- | --- | --- |
| Claude | `GET https://api.anthropic.com/api/oauth/usage` | 5 小时、每周、Opus/Sonnet 周用量（存在时） |
| Copilot | `GET https://api.github.com/copilot_internal/user` | Premium requests、Chat、Completions 配额与重置日期；使用 GitHub 身份令牌而非推理令牌 |
| Kimi | `GET https://api.kimi.com/coding/v1/usages` | 总体及滚动窗口，已用、剩余、重置时间 |
| OpenRouter | `GET https://openrouter.ai/api/v1/key` | Key 剩余预算、上限、累计/日/周/月用量及返回的组织预算；单位 USD，不代表账号余额 |
| Meta | 签发 Key 响应的 `subs_usage` | 本地保存的窗口/周用量快照；显示采样时间 |

新查询成功缓存 60 秒、失败缓存 30 秒，合并并发查询；手动刷新在缓存有效期内复用结果。
缓存按账号凭据区分，不携带原始响应错误内容。401 最多刷新一次；403/429 显示权限不足/限流，不当作零额度。
未报告的字段保持未知，仅在服务明确返回 unlimited 或 OpenRouter 的 limit=null 时展示无上限。
OpenRouter 累计 usage 不当作当前预算窗口消费；百分比不按大小猜测 0–1/0–100 的单位。

Meta 刷新按钮只重新读取快照，绝不为了查额度调用 Key 签发接口。旧登录没有快照时显示暂不可查询；
后续正常登录/凭据刷新若返回 `subs_usage`，会自动更新。快照可能陈旧，不是实时余额。

除 OpenRouter 外这些额度接口主要来自非公开协议，可能变化，未进行真实账号验证。
协议参考：[Claude](https://github.com/FullFran/claudeops-tui/blob/main/docs/oauth-usage-endpoint.md)、
[Copilot/Kimi](https://github.com/usageowl/usageowl/blob/main/docs/PROVIDERS.md)、
[Meta](https://github.com/coreyhaines31/fuelgauge/issues/18)、
[OpenRouter](https://openrouter.ai/docs/api/api-reference/api-keys/get-current-api-key)。

## 验证

不使用真实账号、密钥或付费请求的测试覆盖：设备码 HTTP 200 等待/拒绝、授权码输入与 state 校验、
Meta/Copilot 两阶段凭据、刷新、OpenRouter PKCE 换 Key、设置序列化、固定官方路由与受保护鉴权头、
Claude system 身份块、Copilot 模型策略过滤；额度字段解析、未知/零/无限值区分、缓存失效与限流缓存、
取消传播、401 单次重试、Meta 快照持久化与查询零网络请求。

```sh
ANDROID_HOME=/home/gui/Android/Sdk ./gradlew \
  :ai:testDebugUnitTest :oauth:testDebugUnitTest \
  :app:testGithubDebugUnitTest --tests '*SubscriptionAuthTest' --tests '*ProviderAccountManagerTest' --tests '*QuotaMetricSummaryTest' \
  :app:compileGithubDebugKotlin -x :web:buildWebUi --offline
```

`-x :web:buildWebUi` 仅用于本地缺少 pnpm 时验证 Kotlin 和单元测试，不能替代完整发布构建。
上线前必须在 Android 设备使用有权限的真实账号逐项检查登录、刷新、模型列表、流式聊天与工具调用；
这些离线测试不保证服务端当前接受第三方客户端或所有订阅套餐。使用前请确认对应服务的条款及账号政策。
