package io.github.acedroidx.frp

import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import io.github.acedroidx.frp.ui.theme.FrpTheme
import io.github.acedroidx.frp.ui.theme.ThemeModeKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.runtime.collectAsState
import androidx.core.content.edit

class ConfigActivity : ComponentActivity() {
    private val configEditText = MutableStateFlow("")
    private val isAutoStart = MutableStateFlow(false)
    private val frpVersion = MutableStateFlow("")
    private val themeMode = MutableStateFlow("")
    private lateinit var configFile: File
    private lateinit var autoStartPreferencesKey: String
    private lateinit var preferences: SharedPreferences

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val frpConfig = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.extras?.getParcelable(IntentExtraKey.FrpConfig, FrpConfig::class.java)
        } else {
            @Suppress("DEPRECATION") intent?.extras?.getParcelable(IntentExtraKey.FrpConfig)
        }
        if (frpConfig == null) {
            Log.e("adx", "frp config is null")
            Toast.makeText(this, getString(R.string.toast_frp_config_null), Toast.LENGTH_SHORT)
                .show()
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        configFile = frpConfig.getFile(this)
        autoStartPreferencesKey = frpConfig.type.getAutoStartPreferencesKey()
        preferences = getSharedPreferences("data", MODE_PRIVATE)
        val loadingText = getString(R.string.loading)
        frpVersion.value =
            preferences.getString(PreferencesKey.FRP_VERSION, loadingText) ?: loadingText
        val rawTheme = preferences.getString(PreferencesKey.THEME_MODE, ThemeModeKeys.FOLLOW_SYSTEM)
        themeMode.value = ThemeModeKeys.normalize(rawTheme)
        readConfig()
        readIsAutoStart()

        enableEdgeToEdge()
        setContent {
            val currentTheme by themeMode.collectAsStateWithLifecycle(themeMode.collectAsState().value.ifEmpty { ThemeModeKeys.FOLLOW_SYSTEM })
            FrpTheme(themeMode = currentTheme) {
                val frpVersion by frpVersion.collectAsStateWithLifecycle(frpVersion.collectAsState().value.ifEmpty { loadingText })
                Scaffold(topBar = {
                    TopAppBar(title = {
                        Text(
                            stringResource(
                                R.string.frp_for_android_version,
                                BuildConfig.VERSION_NAME,
                                frpVersion
                            )
                        )
                    }, navigationIcon = {
                        IconButton(onClick = { closeActivity() }) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_arrow_back_24dp),
                                contentDescription = stringResource(R.string.content_desc_back)
                            )
                        }
                    })
                }) { contentPadding ->
                    // Screen content
                    MainContent(contentPadding)
                }
            }
        }
    }

    @Preview(showBackground = true)
    @Composable
    fun MainContent(contentPadding: PaddingValues = PaddingValues(0.dp)) {
        val focusRequester = remember { FocusRequester() }

        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .imePadding()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
            ) {
                Button(onClick = { fetchConfigAndUpdate() }) {
                    Text(stringResource(R.string.fetch_config_button))
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
            ) {
                Text(stringResource(R.string.auto_start_switch))
                Switch(
                    checked = isAutoStart.collectAsStateWithLifecycle(false).value,
                    onCheckedChange = { setAutoStart(it) })
            }
            TextField(
                value = configEditText.collectAsStateWithLifecycle("").value,
                onValueChange = { },
                textStyle = MaterialTheme.typography.bodyMedium.merge(fontFamily = FontFamily.Monospace),
                readOnly = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 12.dp)
                    .focusRequester(focusRequester)
            )
        }
    }

    fun readConfig() {
        if (configFile.exists()) {
            val mReader = configFile.bufferedReader()
            val mRespBuff = StringBuffer()
            val buff = CharArray(1024)
            var ch = 0
            while (mReader.read(buff).also { ch = it } != -1) {
                mRespBuff.append(buff, 0, ch)
            }
            mReader.close()
            configEditText.value = mRespBuff.toString()
        } else {
            Log.e("adx", "config file is not exist")
            Toast.makeText(this, getString(R.string.toast_config_not_exist), Toast.LENGTH_SHORT)
                .show()
        }
    }

    fun fetchConfigAndUpdate() {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                RemoteConfigFetcher.fetchConfig(this@ConfigActivity)
            }
            result.onSuccess { content ->
                try {
                    withContext(Dispatchers.IO) {
                        val parent = configFile.parentFile
                        if (parent != null && !parent.exists()) {
                            parent.mkdirs()
                        }
                        configFile.writeText(content)
                    }
                    configEditText.value = content
                    Toast.makeText(
                        this@ConfigActivity,
                        getString(R.string.toast_fetch_config_success),
                        Toast.LENGTH_SHORT
                    ).show()
                } catch (e: Exception) {
                    Log.e("adx", "write fetched config failed: ${e.message}")
                    Toast.makeText(
                        this@ConfigActivity,
                        getString(R.string.toast_fetch_config_failed, e.message ?: ""),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }.onFailure { e ->
                Log.e("adx", "fetch config failed: ${e.message}")
                Toast.makeText(
                    this@ConfigActivity,
                    getString(R.string.toast_fetch_config_failed, e.message ?: ""),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    fun readIsAutoStart() {
        isAutoStart.value =
            preferences.getStringSet(autoStartPreferencesKey, emptySet())?.contains(configFile.name)
                ?: false
    }

    fun setAutoStart(value: Boolean) {
        preferences.edit {
            val set = preferences.getStringSet(autoStartPreferencesKey, emptySet())?.toMutableSet()
            if (value) {
                set?.add(configFile.name)
            } else {
                set?.remove(configFile.name)
            }
            putStringSet(autoStartPreferencesKey, set)
        }
        isAutoStart.value = value
    }

    fun closeActivity() {
        setResult(RESULT_OK)
        finish()
    }
}