package com.kingzcheung.xime.model

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.StatFs
import com.kingzcheung.xime.runtime.adaptation.DeviceCapabilityProbe
import com.kingzcheung.xime.settings.DoublePinyinScheme
import com.kingzcheung.xime.settings.DoublePinyinSchemes
import com.kingzcheung.xime.settings.InputLanguage
import com.kingzcheung.xime.settings.InputLayout
import com.kingzcheung.xime.settings.InputModes
import com.kingzcheung.xime.settings.InputScheme
import com.kingzcheung.xime.settings.InputProfiles
import com.kingzcheung.xime.settings.LanguagePreferences
import com.kingzcheung.xime.settings.LayoutKind
import com.kingzcheung.xime.settings.SchemaManager
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.settings.SpellingPatches
import com.kingzcheung.xime.speech.AsrModelManager
import com.kingzcheung.xime.speech.SpeechModelSelection
import com.kingzcheung.xime.util.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** 「按设备推荐配置」：读设备情况、照方案改设置、删掉用不到的模型。只在用户点了“按推荐设置”后改东西。 */
object SetupGuide {
    private const val KEY_SHOWN = "setup_guide_shown"

    /** 首次设置向导之后是否已经出现过（看过、设置过或点了“以后再说”）。 */
    fun isShown(context: Context): Boolean = SettingsPreferences.getPrefsPublic(context).getBoolean(KEY_SHOWN, false)
    fun markShown(context: Context) { SettingsPreferences.getPrefsPublic(context).edit().putBoolean(KEY_SHOWN, true).apply() }

    /** 在 IO 线程调用：会查模型文件。 */
    fun readFacts(context: Context): SetupFacts {
        val hardware = DeviceCapabilityProbe.read(context)
        ModelManager.initialize()
        val models = SetupGuidePlanner.managedIds.mapNotNull { ModelManager.getModel(it) }
        val installed = models.mapNotNull { model ->
            if (!ModelManager.isModelDownloaded(context, model)) null
            else model.id to ModelManager.getModelSizeOnDisk(context, model.id).takeIf { it > 0 }.let { it ?: catalogBytes(model) }
        }.toMap()
        val inPackage = models.filter { model ->
            model.resolvedVersion()?.let { BundledModelInstaller.packagedSource(context, model, it) } != null
        }.map { it.id }.toSet()
        val connectivity = runCatching { context.getSystemService(ConnectivityManager::class.java) }.getOrNull()
        val network = runCatching { connectivity?.activeNetwork?.let { connectivity.getNetworkCapabilities(it) } }.getOrNull()
        val manager = AsrModelManager(context)
        val chinese = runCatching { manager.getSelectedModelId(InputLanguage.CHINESE) }.getOrNull()
        val currentSchema = SettingsPreferences.getCurrentSchema(context)
        val profile = runCatching { InputProfiles.current(currentSchema) }.getOrNull()
        val t9Layout = profile?.layout?.kind == LayoutKind.T9
        val enabledSchemas = runCatching { SchemaManager.getEnabledSchemas(context) }.getOrDefault(emptyList())
        val typing = when (profile?.scheme) {
            InputScheme.PINYIN -> TypingWay.FULL_PINYIN
            InputScheme.DOUBLE_PINYIN -> TypingWay.DOUBLE_PINYIN
            InputScheme.WUBI, InputScheme.WUBI_PINYIN -> TypingWay.WUBI
            // 仓颉、注音、笔画这些另有打法；手写、英文等不是打中文的键盘，按全拼问。
            InputScheme.CANGJIE, InputScheme.ZHUYIN, InputScheme.STROKE -> TypingWay.KEEP
            else -> TypingWay.FULL_PINYIN
        }
        // 现在打中文的键盘；不是中文、或仓颉注音这类另有打法的，不问键盘也不换。
        val chineseLayout = profile?.takeIf { it.language == InputLanguage.CHINESE && typing != TypingWay.KEEP }?.layout
        val keyboard = when {
            chineseLayout == null -> KeyboardWay.KEEP
            chineseLayout.kind == LayoutKind.T9 -> KeyboardWay.T9
            chineseLayout.id == InputLayout.MERGED14.id -> KeyboardWay.K14
            chineseLayout.kind == LayoutKind.ALPHABETIC -> KeyboardWay.QWERTY
            else -> KeyboardWay.KEEP
        }
        val doublePinyins = DoublePinyinScheme.BUILTINS + runCatching { DoublePinyinSchemes.load(context) }.getOrDefault(emptyList())
        // 自己下载过九键词组模型（不是安装包带的那份）也算在用九键。
        val t9Download = listOf(CandidateModelCatalog.NGRAM, CandidateModelCatalog.NGRAM_SMALL).any { id ->
            ModelManager.getModel(id)?.versions?.any { hasDownloadedModelFiles(ModelStorage.getModelDir(context, id), it) } == true
        }
        return SetupFacts(
            tier = DeviceModelProfiles.choose(hardware).tier,
            performanceTier = com.kingzcheung.xime.runtime.adaptation.PerformanceTiers.classify(hardware),
            // 和键盘一样按最短边 600dp 分手机、平板。
            device = if (context.resources.configuration.smallestScreenWidthDp >= 600) "平板" else "手机",
            memoryBytes = hardware.totalMemoryBytes,
            cores = hardware.processors,
            maxCpuKHz = hardware.maxCpuKHz,
            soc = socName(),
            language = Locale.getDefault().let { it.getDisplayLanguage(it) }.takeIf { it.isNotBlank() },
            freeBytes = runCatching { StatFs(context.filesDir.path).availableBytes }.getOrNull(),
            online = network != null,
            metered = network != null && !network.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            japanese = InputLanguage.JAPANESE in LanguagePreferences.enabled(context) || Locale.getDefault().language == "ja",
            sttEnabled = SettingsPreferences.isSttEnabled(context),
            streamingVoice = chinese != null && SpeechModelSelection.hasCorrection(chinese),
            usesT9 = t9Layout || t9Download,
            usesCaption = com.kingzcheung.xime.BuildConfig.ELDER ||
                runCatching { com.kingzcheung.xime.ui.caption.CaptionLauncherIcon.isEnabled(context) }.getOrDefault(false),
            predictionEnabled = SettingsPreferences.isSmartPredictionEnabled(context),
            installed = installed,
            inPackage = inPackage,
            sizes = models.associate { it.id to catalogBytes(it) },
            typing = typing,
            typingName = profile?.scheme?.displayName.orEmpty(),
            // 九键、14 键、自建合并键布局上的双拼算同一种双拼（26 键那个方案）。
            doublePinyin = if (typing != TypingWay.DOUBLE_PINYIN) null else DoublePinyinSchemes.baseId(currentSchema),
            keyboard = keyboard,
            keyboardName = chineseLayout?.displayName.orEmpty(),
            shownDoublePinyin = DoublePinyinScheme.BUILTINS.map { it.id }.filterTo(linkedSetOf()) { it in enabledSchemas },
            doublePinyinChoices = doublePinyins.map { DoublePinyinChoice(it.id, it.name, it.preview(), !DoublePinyinSchemes.isCustom(it.id),
                lettersOnly = it.usesSemicolon) },
            wubiInstalled = File(SchemaManager.getRimeDir(context), "${SetupGuidePlanner.WUBI}.schema.yaml").isFile,
            doublePinyinKeyHints = SettingsPreferences.getPrefsPublic(context)
                .getBoolean(com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences.DOUBLE_PINYIN_KEY_HINTS_KEY, false),
            fuzzy = SpellingPatches.fuzzy(context).mapTo(linkedSetOf()) { it.id },
            captionIconChangeable = !com.kingzcheung.xime.BuildConfig.ELDER,
        )
    }

    private fun socName(): String? {
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            listOf(Build.SOC_MANUFACTURER, Build.SOC_MODEL).filter { it.isNotBlank() && it != Build.UNKNOWN }.joinToString(" ")
        else ""
        return soc.ifBlank { Build.HARDWARE.takeIf { it.isNotBlank() && it != Build.UNKNOWN } }
    }

    /** 目录里的大小；归档下载的模型文件清单没有字节数，用版本上写的大小（如“136.4MB”）。 */
    internal fun catalogBytes(model: ModelInfo): Long {
        val files = model.files.sumOf { it.sizeBytes }
        if (files > 0) return files
        val size = model.resolvedVersion()?.size.orEmpty()
        val number = Regex("([0-9.]+)\\s*([KMG]B)", RegexOption.IGNORE_CASE).find(size) ?: return 0
        val unit = when (number.groupValues[2].uppercase()) { "KB" -> 1024L; "MB" -> 1024L * 1024; else -> 1024L * 1024 * 1024 }
        return (number.groupValues[1].toDouble() * unit).toLong()
    }

    data class Result(val removed: Int, val freedBytes: Long, val downloading: Long, val waitingForWifi: Boolean, val failed: List<String>,
        /** 打字方式这一块改好的、没改成的，白话一句一行。 */
        val inputDone: List<String> = emptyList(), val inputFailed: List<String> = emptyList(),
        /** 在下载的、从安装包取出的模型，完成页写「语音、联想模型正在下载」。 */
        val downloadingIds: List<String> = emptyList(), val unpackingIds: List<String> = emptyList())

    /**
     * 照 [plan] 设置：改语音、联想选项，删掉 [removeIds]（安装包里的不再解压），
     * 安装包里有的直接取回，其余排队下载（移动数据下除非 [allowMetered] 否则等 Wi-Fi）。
     */
    suspend fun apply(context: Context, plan: SetupPlan, removeIds: Set<String>, allowMetered: Boolean,
        hideDoublePinyin: Boolean = true): Result {
        val app = context.applicationContext
        val facts = readFacts(app)
        val manager = AsrModelManager(app)
        SettingsPreferences.setSttEnabled(app, plan.sttEnabled)
        plan.voiceMode?.let { mode ->
            listOf(InputLanguage.CHINESE, InputLanguage.ENGLISH).forEach { runCatching { manager.setModel(mode, it) } }
        }
        plan.predictionModel?.let { model ->
            if (SettingsPreferences.getPredictionSelectedModel(app) != model) {
                SettingsPreferences.setPredictionSelectedModel(app, model)
                com.kingzcheung.xime.association.AssociationManager.release()
            }
        }
        val keepIds = plan.keep.map { it.id }.toSet()
        // 不在方案里的首次默认模型不再自动下载。
        SetupGuidePlanner.managedIds.filter { it !in keepIds }.forEach { DefaultModelInstaller.markHandled(app, it) }

        val removing = plan.remove.filter { it.id in removeIds }
        BundledModelInstaller.skip(app, removing.map { it.id }.filter { it in BundledModelInstaller.modelIds }.toSet())
        val failed = mutableListOf<String>()
        var freed = 0L
        for (item in removing) {
            // 安装包带的九键词组模型还没放进词库目录：关掉它，启动时就不会再放进去。
            if (item.source != SetupSource.ON_PHONE) {
                if (ModelManager.getModel(item.id)?.category == ModelCategory.CANDIDATE) CandidateModelAssets.setDisabled(app, item.id, true)
                continue
            }
            if (ModelManager.deleteModel(app, item.id)) freed += item.bytes
            else failed += item.title
        }

        val missing = plan.keep.filter { it.id !in facts.installed }
        val fromPackage = missing.filter { it.id in facts.inPackage }
        val fromNetwork = missing.filterNot { it.id in facts.inPackage }
        for (item in fromPackage) ModelManager.getModel(item.id)?.let { ModelManager.downloadModelInBackground(app, it) }
        var waiting = false
        if (fromNetwork.isNotEmpty()) {
            DefaultModelInstaller.requestGuided(app, fromNetwork.map { it.id }.toSet(), allowMetered)
            waiting = facts.metered && !allowMetered
            // 已在跑的首次下载可能还没看到新加的这几项：能下就直接开始，同一模型的下载会合并。
            if (facts.online && !waiting) fromNetwork.forEach { item ->
                ModelManager.getModel(item.id)?.let { ModelManager.downloadModelInBackground(app, it, onlyIfDefaultPending = true) }
            }
        }
        val inputFailed = applyInput(app, plan.input, hideDoublePinyin, facts.online)
        val inputDone = (plan.input.lines + listOfNotNull(plan.input.hideLine?.takeIf { hideDoublePinyin }))
            .filterNot { it.startsWith("打字方式还是") }
            .filterNot { line -> "五笔" in line && inputFailed.any { "五笔" in it } }
        markShown(app)
        FileLogger.i("SetupGuide", "按推荐设置：语音=${plan.voiceMode} 联想=${plan.predictionModel} 九键=${plan.t9Model} " +
            "删除=${removing.map { it.id }} 取回=${fromPackage.map { it.id }} 下载=${fromNetwork.map { it.id }} 打字=${plan.input}")
        return Result(removing.count { it.source == SetupSource.ON_PHONE } - failed.size, freed, fromNetwork.sumOf { it.bytes }, waiting, failed,
            inputDone, inputFailed, fromNetwork.map { it.id }, fromPackage.map { it.id })
    }

    /**
     * 打字方式：模糊音、双拼方案栏、五笔、当前方案、桌面「字幕」图标。都走设置页同一套函数（双拼页、模糊音、方案市场），
     * 输入法服务照常收到改动后重新部署、切回当前方案。返回没做成的，白话。
     */
    private suspend fun applyInput(app: Context, input: InputPlan, hideDoublePinyin: Boolean, online: Boolean): List<String> {
        val failed = mutableListOf<String>()
        if (input.fuzzyOn.isNotEmpty() || input.fuzzyOff.isNotEmpty()) {
            SpellingPatches.Fuzzy.entries.forEach { item ->
                if (item.id in input.fuzzyOn) SpellingPatches.setFuzzy(app, item, true)
                if (item.id in input.fuzzyOff) SpellingPatches.setFuzzy(app, item, false)
            }
            runCatching { SpellingPatches.apply(app) }.onFailure { failed += "模糊音没改成：${it.message}" }
        }

        var wubiReady = !input.useWubi
        var redeploy = false
        if (input.useWubi) {
            if (input.downloadWubi) {
                val scheme = com.kingzcheung.xime.settings.CuratedRimeSchemes.merge(emptyList()).first { it.id == SetupGuidePlanner.WUBI }
                val download = if (online) com.kingzcheung.xime.settings.XimeIndexSource.downloadScheme(app, scheme) else null
                val install = if (download?.success == true) com.kingzcheung.xime.settings.XimeIndexSource.installFromMarket(
                    app, scheme, allowScripts = true) else null
                if (install?.success == true) {
                    com.kingzcheung.xime.settings.MarketVersionStore.setSchemeVersion(app, scheme.id, scheme.currentVersion)
                    wubiReady = true
                    redeploy = true
                } else failed += when {
                    !online -> "五笔86方案没下载：现在没联网。可以连上网后再来这页，或在 扩展 → 方案 里获取。"
                    else -> "五笔86方案没装上（${install?.failureReason ?: download?.failureReason ?: "下载失败"}），可以在 扩展 → 方案 里再获取。"
                }
            } else wubiReady = true
            // 装过但从方案栏关掉了：打开它。
            val enabled = SchemaManager.getEnabledSchemas(app)
            if (wubiReady && SetupGuidePlanner.WUBI !in enabled) {
                SchemaManager.setEnabledSchemas(app, enabled + SetupGuidePlanner.WUBI)
                LanguagePreferences.enableForSchema(app, SetupGuidePlanner.WUBI)
                redeploy = true
            }
        }

        // 先藏、再切当前、最后打开选中的双拼：打开那一步通知输入法部署，并切到已经写好的当前方案。
        if (hideDoublePinyin) input.hideDoublePinyin.forEach { id ->
            runCatching { DoublePinyinSchemes.setEnabled(app, id, false) }
        }
        val switchTo = input.switchTo?.takeIf { it != SetupGuidePlanner.WUBI || wubiReady }
        switchTo?.let { id ->
            // 要用的布局不在方案栏里（如 14 键没开过、全拼被关掉过）：先打开，双拼由下面那一步打开。
            // 双拼的九键版本走双拼页同一个开关（排在九键后面）；双拼的 14 键版本随双拼和中文14键自动出现。
            val missing = input.enable.filterNot { it in SchemaManager.getEnabledSchemas(app) }
            missing.filter(DoublePinyinSchemes::isT9).forEach { t9 ->
                runCatching { DoublePinyinSchemes.setEnabled(app, t9, true) }.onFailure { failed += "九键双拼没打开：${it.message}" }
            }
            val others = missing.filterNot(DoublePinyinSchemes::isT9)
            if (others.isNotEmpty()) {
                SchemaManager.setEnabledSchemas(app, SchemaManager.getEnabledSchemas(app) + others)
                others.forEach { LanguagePreferences.enableForSchema(app, it) }
                redeploy = true
            }
            SettingsPreferences.setCurrentSchema(app, id)
            InputModes.selectProfile(app, id, InputLanguage.CHINESE)
        }
        input.showDoublePinyin?.let { id ->
            runCatching { DoublePinyinSchemes.setEnabled(app, id, true) }.onFailure { failed += "双拼没打开：${it.message}" }
        }
        // 和双拼方案页的开关是同一个设置，正在显示的键盘监听它，马上生效。
        // 键位提示和上下角标提示互锁（InputGestureLocks）：同一次写入一起关掉两个角标提示。
        if (input.keyHintsOn) SettingsPreferences.getPrefsPublic(app).edit()
            .putBoolean(com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences.DOUBLE_PINYIN_KEY_HINTS_KEY, true)
            .putBoolean(SettingsPreferences.KEY_SWIPE_UP_HINTS_ENABLED, false)
            .putBoolean(SettingsPreferences.KEY_SWIPE_DOWN_HINTS_ENABLED, false).apply()
        if (redeploy) {
            // 与「已安装的方案」里「应用方案更改」同一步。
            val deployed = runCatching {
                com.kingzcheung.xime.settings.KeysConfigHelper.loadConfig(app)
                com.kingzcheung.xime.ui.theme.KeyboardThemes.reload(app)
                com.kingzcheung.xime.rime.RimeConfigHelper.redeploy(app)
            }.getOrDefault(false)
            if (!deployed) failed += "方案没部署成功；到 设置 → 已安装的方案 点「应用方案更改」。"
        }
        // 方案已经部署好时直接切过去；还没部署的，输入法部署完按当前方案切。
        if (switchTo != null && com.kingzcheung.xime.rime.RimeEngine.isInitialized()) runCatching {
            val engine = com.kingzcheung.xime.rime.RimeEngine.getInstance()
            if (switchTo in engine.getAvailableSchemas()) engine.switchSchema(switchTo)
        }

        input.captionIcon?.let { on ->
            runCatching { withContext(Dispatchers.Main) { com.kingzcheung.xime.ui.caption.CaptionLauncherIcon.setEnabled(app, on) } }
                .onFailure { failed += "桌面「字幕」图标没改成，可以在 设置 → 语音转文本 → 字幕 里开关。" }
        }
        return failed
    }
}
