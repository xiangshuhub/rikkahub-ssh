package me.rerere.rikkahub.data.datastore

import me.rerere.rikkahub.data.ai.prompts.LEARNING_MODE_PROMPT
import me.rerere.rikkahub.data.model.APP_MANAGEMENT_ASSISTANT_ID
import me.rerere.rikkahub.data.model.managementOnly
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.tts.provider.TTSProviderSetting
import kotlin.uuid.Uuid

internal val DEFAULT_ASSISTANT_ID = Uuid.parse("0950e2dc-9bd5-4801-afa3-aa887aa36b4e")
internal val DEFAULT_ASSISTANTS = listOf(
    Assistant(
        id = DEFAULT_ASSISTANT_ID,
        name = "",
        systemPrompt = ""
    ),
    Assistant(
        id = Uuid.parse("3d47790c-c415-4b90-9388-751128adb0a0"),
        name = "",
        systemPrompt = """
            You are a helpful assistant, called {{char}}, based on model {{model_name}}.

            ## Info
            - Date: {{cur_date}}
            - Locale: {{locale}}
            - Timezone: {{timezone}}
            - Device Info: {{device_info}}
            - System Version: {{system_version}}
            - User Nickname: {{user}}

            ## Hint
            - If the user does not specify a language, reply in the user's primary language.
            - Remember to use Markdown syntax for formatting, and use latex for mathematical expressions.
        """.trimIndent()
    ),
    Assistant(
        id = APP_MANAGEMENT_ASSISTANT_ID,
        name = "应用管理助手",
        systemPrompt = """
            你是 RikkaHub SSH 的应用管理助手，仅负责助手配置、工作空间绑定、Skill 与 MCP 配置管理。
            只使用提供的应用管理工具，不执行 shell、文件操作、搜索或外部 MCP 工具。
            修改前先读取当前配置、说明影响，再发起需要用户确认的工具调用；不得绕过确认。
            工作空间功能仅查看及绑定/解绑，不创建、删除服务器或操作远端文件。
            助手修改仅影响全局配置与新对话，不改写已有对话快照。
            不请求或输出密钥；新建 MCP 默认禁用，认证由用户在设置中完成。
            不得修改管理身份或启停权限，也不得将普通助手提升为管理助手。
        """.trimIndent(),
    ).managementOnly(),
)

val DEFAULT_SYSTEM_TTS_ID = Uuid.parse("026a01a2-c3a0-4fd5-8075-80e03bdef200")
internal val DEFAULT_TTS_PROVIDERS = listOf(
    TTSProviderSetting.SystemTTS(
        id = DEFAULT_SYSTEM_TTS_ID,
        name = "",
    ),
    TTSProviderSetting.OpenAI(
        id = Uuid.parse("e36b22ef-ca82-40ab-9e70-60cad861911c"),
        name = "AiHubMix",
        baseUrl = "https://aihubmix.com/v1",
        model = "gpt-4o-mini-tts",
        voice = "alloy",
    )
)

internal val DEFAULT_ASSISTANTS_IDS = DEFAULT_ASSISTANTS.map { it.id }

val DEFAULT_MODE_INJECTIONS = listOf(
    PromptInjection.ModeInjection(
        id = Uuid.parse("b87eaf16-f5cd-4ac1-9e4f-b11ae3a61d74"),
        content = LEARNING_MODE_PROMPT,
        position = InjectionPosition.AFTER_SYSTEM_PROMPT,
        name = "Learning Mode"
    )
)
