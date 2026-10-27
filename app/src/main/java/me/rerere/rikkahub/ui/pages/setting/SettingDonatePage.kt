package me.rerere.rikkahub.ui.pages.setting

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.openUrl

@Composable
fun SettingDonatePage() {
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scope = rememberCoroutineScope()
    val saveImage = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri != null) scope.launch {
            val message = try {
                withContext(Dispatchers.IO) {
                    context.resources.openRawResource(R.drawable.wechat_donation).use { input ->
                        checkNotNull(context.contentResolver.openOutputStream(uri, "wt")) { "Cannot open destination" }.use { output ->
                            input.copyTo(output)
                        }
                    }
                }
                R.string.fork_donation_saved
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { R.string.fork_donation_save_failed }
            Toast.makeText(context, context.getString(message), Toast.LENGTH_LONG).show()
        }
    }
    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.donate_page_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.fork_donation_note))
            Image(
                painter = painterResource(R.drawable.wechat_donation),
                contentDescription = "WeChat donation · RikkaHub SSH",
                modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
            )
            TextButton(onClick = { saveImage.launch("rikkahub-ssh-wechat-donation.png") }) {
                Text(stringResource(R.string.fork_donation_save))
            }
            TextButton(onClick = {
                context.openUrl("https://xiangshuhub.github.io/rikkahub-ssh/wechat-donation.png")
            }) { Text(stringResource(R.string.fork_donation_open)) }
        }
    }
}
