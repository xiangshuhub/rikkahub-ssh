package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.Socks5ProxyException
import me.rerere.ai.provider.Socks5Stage
import me.rerere.ai.util.HttpException
import me.rerere.rikkahub.R
import org.koin.compose.koinInject

@Composable
fun ProviderProxyOption(provider: ProviderSetting, onEdit: (ProviderSetting) -> Unit) {
    var draft by remember(provider.id, provider.proxy) { mutableStateOf(provider.proxy) }
    var port by remember(provider.id, provider.proxy) { mutableStateOf(provider.proxy.port.toString()) }
    var testing by remember { mutableStateOf(false) }
    var result by remember(provider.id, draft, port) { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    val manager = koinInject<ProviderManager>()
    val candidate = draft.copy(port = port.toIntOrNull() ?: 0)
    val valid = runCatching { candidate.validate() }.isSuccess

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.provider_proxy_description), style = MaterialTheme.typography.bodySmall)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(if (draft.enabled) R.string.provider_proxy_socks5 else R.string.provider_proxy_default))
            Switch(checked = draft.enabled, enabled = !testing, onCheckedChange = { draft = draft.copy(enabled = it) })
        }
        OutlinedTextField(
            value = draft.host, onValueChange = { draft = draft.copy(host = it.trim()) },
            label = { Text(stringResource(R.string.provider_proxy_host)) }, singleLine = true,
            enabled = !testing, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = port, onValueChange = { port = it.filter(Char::isDigit).take(5) },
            label = { Text(stringResource(R.string.provider_proxy_port)) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            enabled = !testing, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = draft.username, onValueChange = { draft = draft.copy(username = it) },
            label = { Text(stringResource(R.string.provider_proxy_username)) }, singleLine = true,
            enabled = !testing, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = draft.password, onValueChange = { draft = draft.copy(password = it) },
            label = { Text(stringResource(R.string.provider_proxy_password)) }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            enabled = !testing, modifier = Modifier.fillMaxWidth(),
        )
        if (!valid) Text(stringResource(R.string.provider_proxy_invalid), color = MaterialTheme.colorScheme.error)
        Text(stringResource(R.string.provider_proxy_notice), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(enabled = !testing && (!draft.enabled || valid), onClick = {
                onEdit(provider.withProxy(if (valid) candidate else provider.proxy.copy(enabled = false)))
                result = R.string.setting_provider_page_save_success
            }) { Text(stringResource(R.string.assistant_page_save)) }
            OutlinedButton(enabled = !testing && (!draft.enabled || valid), onClick = {
                testing = true
                result = null
                val snapshot = provider.withProxy(candidate)
                scope.launch {
                    try {
                        withTimeout(30_000) { manager.testConnection(snapshot) }
                        result = R.string.provider_proxy_test_success
                    } catch (_: TimeoutCancellationException) {
                        result = R.string.provider_proxy_test_timeout
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val causes = generateSequence<Throwable>(e) { it.cause }.take(10).toList()
                        val socks = causes.filterIsInstance<Socks5ProxyException>().firstOrNull()
                        result = when {
                            socks?.stage == Socks5Stage.AUTH -> R.string.provider_proxy_test_auth
                            socks?.stage == Socks5Stage.TARGET -> R.string.provider_proxy_test_target
                            socks != null -> R.string.provider_proxy_test_connect
                            causes.any { it is HttpException } -> R.string.provider_proxy_test_api
                            else -> R.string.provider_proxy_test_failed
                        }
                    } finally { testing = false }
                }
            }) { Text(stringResource(R.string.provider_proxy_test)) }
        }
        if (testing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        result?.let { Text(stringResource(it)) }
    }
}
