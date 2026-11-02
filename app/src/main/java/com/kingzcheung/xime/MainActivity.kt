package com.kingzcheung.xime

import android.content.Intent
import android.Manifest
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsControllerCompat
import com.kingzcheung.xime.rime.RimeEngine
import com.kingzcheung.xime.settings.ImportManager
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.settings.SchemaManager
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.settings.SettingsScreen
import com.kingzcheung.xime.ui.settings.SettingsNavigationRequest
import com.kingzcheung.xime.ui.settings.SetupWizardScreen
import com.kingzcheung.xime.ui.theme.XimeTheme
import com.kingzcheung.xime.util.PermissionHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    private var settingsNavigationRequest by mutableStateOf<SettingsNavigationRequest?>(null)
    private var pendingShare by mutableStateOf<PendingShare?>(null)

    private data class PendingShare(val uri: android.net.Uri, val title: String, val body: String)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSharedIntent(intent)
        // A plain Settings tap only brings this instance forward. Do not reset its
        // navigation, focused test field or text. Explicit shortcuts still navigate.
        // 插件授权页：冷启动走 initialPluginId，已打开时直接进该插件的详情页。
        (intent.getStringExtra("open_plugin_id")?.let { "plugin_detail/$it" }
            ?: intent.getStringExtra("open_fragment"))?.let { route ->
            settingsNavigationRequest = SettingsNavigationRequest(
                route, (settingsNavigationRequest?.id ?: 0) + 1,
            )
        }
    }
    
    companion object {
        private const val TAG = "MainActivity"
        /** A real permission dialog takes the user longer than this to answer. */
        private const val NO_DIALOG_MS = 500L

        /** 构造跳转到指定插件网络授权页的 Intent（IME 等后台场景调用）。 */
        fun buildPluginAuthIntent(context: android.content.Context, pluginId: String): Intent {
            return Intent(context, MainActivity::class.java).apply {
                putExtra("open_fragment", "plugins")
                putExtra("open_plugin_id", pluginId)
                // 设置已打开时只带 NEW_TASK 不会调 onNewIntent，停在旧页面；和 settingsActivityIntent 一样复用。
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        }
    }

    override fun onDestroy() {
        prewarmScope.cancel()
        super.onDestroy()
    }
    
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        // Denied at once with no dialog: refused before with "don't ask again" (or twice on Android 11+). The
        // system will never ask again, so the only way left is the app's settings page; the user is sent there.
        val noDialog = !isGranted && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) &&
            android.os.SystemClock.uptimeMillis() - permissionAskedAt < NO_DIALOG_MS
        if (noDialog) {
            PermissionHelper.onSentToAppSettings()
            Toast.makeText(this, "麦克风权限之前被拒绝过，请在设置的「权限」里允许麦克风", Toast.LENGTH_LONG).show()
            try {
                // Its own task: finishAndRemoveTask below must not take the settings page with it.
                startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.fromParts("package", packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) { Log.w(TAG, "App settings page unavailable", e) }
        } else {
            PermissionHelper.onRecordAudioResult(isGranted)
            if (isGranted) {
                Toast.makeText(this, "麦克风权限已授权", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "麦克风权限被拒绝，无法使用语音输入", Toast.LENGTH_SHORT).show()
            }
        }
        finishAndRemoveTask()
    }
    /** When the permission request started; a denial faster than this came without a dialog. */
    private var permissionAskedAt = 0L
    
    private val prewarmScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private fun prewarmKeyboardConfig() {
        if (RimeEngine.isInitialized()) return
        prewarmScope.launch {
            try {
                KeysConfigHelper.loadConfig(this@MainActivity)
            } catch (e: Exception) {
                Log.w(TAG, "Keyboard config pre-warm failed, will load on demand", e)
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // The Application owns engine preparation, including installation and deployment.
        prewarmKeyboardConfig()

        handleSharedIntent(intent)
        
        val requestPermission = intent?.getStringExtra("request_permission")
        if (requestPermission == PermissionHelper.PERMISSION_RECORD_AUDIO) {
            if (!PermissionHelper.hasRecordAudioPermission(this)) {
                permissionAskedAt = android.os.SystemClock.uptimeMillis()
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                PermissionHelper.onRecordAudioResult(true)
                Toast.makeText(this, "麦克风权限已授权", Toast.LENGTH_SHORT).show()
                finishAndRemoveTask()
            }
            return
        }
        
        val openFragment = intent?.getStringExtra("open_fragment")
        val openPluginId = intent?.getStringExtra("open_plugin_id")

        com.kingzcheung.xime.model.DefaultModelInstaller.start(applicationContext)

        setContent {
            val context = this
            val setupCompleted = SettingsPreferences.isSetupCompleted(context)
            var showWizard by remember { mutableStateOf(!setupCompleted) }
            var showGuide by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
            var wizardToSettings by remember { mutableStateOf(false) }
            var darkMode by remember { mutableIntStateOf(SettingsPreferences.getEffectiveDarkMode(context)) }
            var keyboardTheme by remember { mutableStateOf(SettingsPreferences.getKeyboardTheme(context)) }

            // Theme changes can also come from the IME while this activity stays
            // open. Its existing composition must follow the same saved choice.
            DisposableEffect(context) {
                val preferences = SettingsPreferences.getPrefsPublic(context)
                val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                    if (key == null || key == "keyboard_theme" || key == "dark_mode") {
                        darkMode = SettingsPreferences.getEffectiveDarkMode(context)
                        keyboardTheme = SettingsPreferences.getKeyboardTheme(context)
                    }
                }
                preferences.registerOnSharedPreferenceChangeListener(listener)
                onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
            }
            
            val isDarkTheme = when (darkMode) {
                2 -> isSystemInDarkTheme()
                1 -> true
                else -> false
            }

            XimeTheme(darkTheme = isDarkTheme, themeId = keyboardTheme) {
                val view = LocalView.current
                if (!view.isInEditMode) {
                    DisposableEffect(isDarkTheme) {
                        val window = (view.context as? ComponentActivity)?.window
                        if (window != null) {
                            val controller = WindowInsetsControllerCompat(window, view)
                            controller.isAppearanceLightStatusBars = !isDarkTheme
                            controller.isAppearanceLightNavigationBars = !isDarkTheme
                        }
                        onDispose { }
                    }
                }

                val density = LocalDensity.current
                val navBarHeight = with(density) {
                    WindowInsets.navigationBars.getBottom(density).toDp()
                }
                val scrimHeight = if (navBarHeight > 0.dp) navBarHeight else 32.dp

                pendingShare?.let { pending ->
                    com.kingzcheung.xime.ui.settings.ImportApprovalDialog(
                        title = pending.title,
                        body = pending.body,
                        confirmLabel = "导入",
                        onConfirm = {
                            val uri = pending.uri
                            pendingShare = null
                            importShared(uri)
                        },
                        onDismiss = {
                            pendingShare = null
                            advanceShareQueue()
                        },
                    )
                }
                Box(modifier = Modifier.fillMaxSize()) {
                    com.kingzcheung.xime.ui.settings.SettingsWidthLimit {
                    if (showWizard) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            SetupWizardScreen(
                                visible = !wizardToSettings,
                                onNavigateToSchemaSettings = { wizardToSettings = true },
                                onCompleted = {
                                    showWizard = false
                                    // 老人包按规格固定配置，不在首次设置后弹出推荐配置。
                                    showGuide = !BuildConfig.ELDER && !com.kingzcheung.xime.model.SetupGuide.isShown(context)
                                }
                            )
                            if (wizardToSettings) {
                                SettingsScreen(
                                    initialRoute = "schema",
                                    onThemeChanged = {
                                        darkMode = SettingsPreferences.getEffectiveDarkMode(context)
                                        keyboardTheme = SettingsPreferences.getKeyboardTheme(context)
                                    },
                                    onWizardBack = { wizardToSettings = false }
                                )
                            }
                        }
                    } else if (showGuide) {
                        com.kingzcheung.xime.ui.settings.SetupGuideScreen(firstRun = true, onDone = { showGuide = false })
                    } else {
                        SettingsScreen(
                            initialRoute = openFragment,
                            initialPluginId = openPluginId,
                            navigationRequest = settingsNavigationRequest,
                            onThemeChanged = {
                                darkMode = SettingsPreferences.getEffectiveDarkMode(context)
                                keyboardTheme = SettingsPreferences.getKeyboardTheme(context)
                            }
                        )
                    }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(scrimHeight)
                            .align(Alignment.BottomCenter)
                            .background(
                                Brush.verticalGradient(
                                    colors = if (isDarkTheme) {
                                        listOf(Color.Transparent, Color.White.copy(alpha = 0.10f))
                                    } else {
                                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.10f))
                                    }
                                )
                            )
                    )
                }
            }
        }
    }
    
    private fun handleSharedIntent(intent: Intent?) {
        if (intent == null) return
        val uris = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<android.net.Uri>(Intent.EXTRA_STREAM).orEmpty()
            else -> return
        }
        // 别的应用塞进来的文件先问过再写。一次只确认队列里的第一个，确认或取消后再看下一个。
        shareQueue = uris
        if (pendingShare == null) advanceShareQueue()
    }

    private var shareQueue: List<android.net.Uri> = emptyList()

    private fun advanceShareQueue() {
        val uri = shareQueue.firstOrNull() ?: return
        shareQueue = shareQueue.drop(1)
        val mimeImage = intent?.type?.startsWith("image/") == true && shareQueue.isEmpty() && pendingShare == null
        prewarmScope.launch {
            val peeked = ImportManager.preview(this@MainActivity, uri)
            val preview = (peeked as? ImportManager.ImportResult.NeedsScriptConfirmation)?.let(ImportManager::previewOf)
            val text = preview?.confirmationText(fromShare = true)
            withContext(Dispatchers.Main) {
                if (preview == null || text == null) {
                    val reason = (peeked as? ImportManager.ImportResult.Failed)?.reason ?: "无法读取分享的文件"
                    Toast.makeText(this@MainActivity, reason, Toast.LENGTH_LONG).show()
                    advanceShareQueue()
                } else if (mimeImage && preview.image) {
                    pendingShare = PendingShare(uri, "导入背景图", text)
                } else {
                    pendingShare = PendingShare(
                        uri,
                        if (preview.plugin) "安装插件" else if (preview.scripts.isNotEmpty()) "安装方案脚本" else "导入文件",
                        text,
                    )
                }
            }
        }
    }

    private fun importShared(uri: android.net.Uri) {
        if (intent?.type?.startsWith("image/") == true && shareQueue.isEmpty()) {
            // 单张图片仍走原来的背景图入口；多文件里的图片按方案导入规则处理。
        }
        prewarmScope.launch {
            val mime = contentResolver.getType(uri)
            if (mime?.startsWith("image/") == true) {
                withContext(Dispatchers.Main) { importThemeImage(uri, mime); advanceShareQueue() }
                return@launch
            }
            when (val result = ImportManager.import(this@MainActivity, uri, allowScripts = true, installPlugin = true)) {
                is ImportManager.ImportResult.Content -> {
                    launch(Dispatchers.Main) {
                        val msg = if (result.pendingPackageId != null) "${result.message}：到「已安装的方案」页确认"
                            else if (result.installedDirect) result.message.replace("点「应用方案更改」", "到「已安装的方案」点「应用方案更改」")
                            else result.message
                        Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                        advanceShareQueue()
                    }
                }
                is ImportManager.ImportResult.Plugin -> {
                    launch(Dispatchers.Main) {
                        Toast.makeText(
                            this@MainActivity,
                            "插件「${result.pluginInfo?.name}」已安装，默认关闭。在插件页打开后才会运行",
                            Toast.LENGTH_LONG
                        ).show()
                        advanceShareQueue()
                    }
                }
                is ImportManager.ImportResult.Failed -> {
                    launch(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "导入失败：${result.reason}", Toast.LENGTH_LONG).show()
                        advanceShareQueue()
                    }
                }
                else -> launch(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "导入失败", Toast.LENGTH_LONG).show()
                    advanceShareQueue()
                }
            }
        }
    }

    /** 分享图片到 Xime：保存到 rime/themes/ 并提示用户如何引用。 */
    private fun importThemeImage(uri: android.net.Uri, mimeType: String) {
        prewarmScope.launch {
            val result = withContext(Dispatchers.IO) {
                val themesDir = File(SchemaManager.getRimeDir(this@MainActivity), "themes")
                themesDir.mkdirs()
                val ext = when (mimeType) {
                    "image/png" -> "png"
                    "image/webp" -> "webp"
                    else -> "jpg"
                }
                val name = "custom_${System.currentTimeMillis()}.$ext"
                val target = File(themesDir, name)
                try {
                    contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                    name
                } catch (e: Exception) {
                    Log.w(TAG, "importThemeImage failed", e)
                    null
                }
            }
            launch(Dispatchers.Main) {
                if (result == null) {
                    Toast.makeText(this@MainActivity, "背景图导入失败", Toast.LENGTH_LONG).show()
                } else {
                    copyThemeConfigTemplate(result)
                    Toast.makeText(
                        this@MainActivity,
                        "背景图已导入 rime/themes/$result，配置模板已复制到剪贴板",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    /** 生成可在 xime.custom.yaml 中使用的主题配置模板并复制到剪贴板。 */
    private fun copyThemeConfigTemplate(fileName: String) {
        val schemeId = fileName.substringBeforeLast('.')
        val template = """
            color_schemes:
              $schemeId:
                name: "自定义背景 ${schemeId.removePrefix("custom_")}"
                keyboard_background:
                  type: image
                  src: "themes/$fileName"
                  fit: cover
                  overlay_alpha: 0.15
                  overlay_alpha_dark: 0.30
                key_bg_color: 0x8cffffff
                key_bg_color_dark: 0x66000000
                key_text_color: 0x232323
                key_text_color_dark: 0xf2f2f2
                candidate_text_color: 0x232323
                candidate_text_color_dark: 0xf2f2f2
                candidate_selected_text_color: 0xffffff
                candidate_selected_text_color_dark: 0xffffff
        """.trimIndent()
        val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("xime theme", template))
    }
}
