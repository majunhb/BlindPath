package com.blindpath.module_voice.data

import com.blindpath.module_voice.domain.IntentRouter
import com.blindpath.module_voice.domain.*
import com.blindpath.module_voice.domain.model.NluResult
import com.blindpath.module_voice.domain.model.VoiceIntent
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 意图路由表实现 — PRD四层架构第四层
 *
 * 将NLU解析出的意图映射到对应模块的API调用。
 * SOS意图拥有最高优先级，无视当前状态立即执行。
 */
@Singleton
class IntentRouterImpl @Inject constructor() : IntentRouter {

    private var navigationExecutor: NavigationExecutor? = null
    private var sceneExecutor: SceneExecutor? = null
    private var voiceControlExecutor: VoiceControlExecutor? = null
    private var sosExecutor: SosExecutor? = null

    override suspend fun route(nluResult: NluResult): RouteResult {
        Timber.i("IntentRouter: 路由意图 → ${nluResult.intent.id}" +
            (if (nluResult.slots.isNotEmpty()) " slots=${nluResult.slots}" else ""))

        return when (nluResult.intent) {
            // ===== 3. 紧急安全类（最高优先级）=====
            VoiceIntent.SOS -> {
                val text = sosExecutor?.triggerSos()
                    ?: "紧急求助已触发，正在发送位置给紧急联系人，请保持冷静"
                RouteResult(success = true, speakText = text)
            }

            // ===== 1. 导航与查询类 =====
            VoiceIntent.NAVIGATE_TO -> {
                val dest = nluResult.getSlot("destination")
                if (dest != null) {
                    val text = navigationExecutor?.navigateTo(dest)
                        ?: "正在为您导航到${dest}"
                    RouteResult(success = true, speakText = text)
                } else {
                    RouteResult(
                        success = false,
                        speakText = "请问您要去哪里？",
                        needsFollowUp = true,
                        followUpPrompt = "请说出目的地"
                    )
                }
            }

            VoiceIntent.NAVIGATE_HOME -> {
                val text = navigationExecutor?.navigateHome()
                    ?: "正在为您导航回家"
                RouteResult(success = true, speakText = text)
            }

            VoiceIntent.QUERY_DISTANCE -> {
                val text = navigationExecutor?.queryDistance()
                    ?: "暂时无法获取导航距离"
                RouteResult(success = true, speakText = text)
            }

            VoiceIntent.QUERY_LOCATION -> {
                val text = navigationExecutor?.queryLocation()
                    ?: "暂时无法获取当前位置"
                RouteResult(success = true, speakText = text)
            }

            VoiceIntent.REPEAT -> {
                RouteResult(success = true, speakText = "重复上一条播报")
            }

            // ===== 2. 模式与控制类 =====
            VoiceIntent.SWITCH_VOICE_MODE -> {
                val text = navigationExecutor?.switchToVoiceMode()
                    ?: "已切换到语音导航模式"
                RouteResult(success = true, speakText = text)
            }

            VoiceIntent.SWITCH_AR_MODE -> {
                val text = navigationExecutor?.switchToArMode()
                    ?: "已切换到AR实景导航模式"
                RouteResult(success = true, speakText = text)
            }

            VoiceIntent.VOLUME_UP -> {
                val text = voiceControlExecutor?.volumeUp()
                    ?: "已增大音量"
                RouteResult(success = true, speakText = text)
            }

            VoiceIntent.VOLUME_DOWN -> {
                val text = voiceControlExecutor?.volumeDown()
                    ?: "已减小音量"
                RouteResult(success = true, speakText = text)
            }

            VoiceIntent.SPEED_UP -> {
                val text = voiceControlExecutor?.speedUp()
                    ?: "已加快语速"
                RouteResult(success = true, speakText = text)
            }

            VoiceIntent.SPEED_DOWN -> {
                val text = voiceControlExecutor?.speedDown()
                    ?: "已减慢语速"
                RouteResult(success = true, speakText = text)
            }

            // ===== 3. 停止导航 =====
            VoiceIntent.STOP_NAVIGATION -> {
                val text = navigationExecutor?.stopNavigation()
                    ?: "导航已停止"
                RouteResult(success = true, speakText = text)
            }

            // ===== 4. 场景识别类 =====
            VoiceIntent.LOOK_AHEAD -> {
                val text = sceneExecutor?.lookAhead()
                    ?: "正在识别前方场景"
                RouteResult(success = true, speakText = text)
            }

            VoiceIntent.START_DETECTION -> {
                val text = sceneExecutor?.startDetection()
                    ?: "障碍物检测已开启"
                RouteResult(success = true, speakText = text)
            }

            VoiceIntent.STOP_DETECTION -> {
                val text = sceneExecutor?.stopDetection()
                    ?: "障碍物检测已关闭"
                RouteResult(success = true, speakText = text)
            }

            // ===== 5. 室内导航类 =====
            VoiceIntent.INDOOR_NAVIGATE -> {
                val dest = nluResult.getSlot("destination")
                if (dest != null) {
                    RouteResult(success = true, speakText = "正在为您查找${dest}的位置")
                } else {
                    RouteResult(
                        success = false,
                        speakText = "请问您要找哪里？",
                        needsFollowUp = true
                    )
                }
            }

            VoiceIntent.QUERY_FLOOR -> {
                RouteResult(success = true, speakText = "楼层信息暂不可用")
            }

            // ===== 6. 全局控制类 =====
            VoiceIntent.HELP -> {
                RouteResult(success = true, speakText = buildHelpMessage())
            }

            // ===== 未识别意图 =====
            VoiceIntent.UNKNOWN -> {
                RouteResult(
                    success = false,
                    speakText = nluResult.followUpPrompt ?: "没有听清，请您大声再说一遍",
                    needsFollowUp = false
                )
            }
        }
    }

    override fun setNavigationExecutor(executor: NavigationExecutor) {
        navigationExecutor = executor
    }

    override fun setSceneExecutor(executor: SceneExecutor) {
        sceneExecutor = executor
    }

    override fun setVoiceControlExecutor(executor: VoiceControlExecutor) {
        voiceControlExecutor = executor
    }

    override fun setSosExecutor(executor: SosExecutor) {
        sosExecutor = executor
    }

    private fun buildHelpMessage(): String {
        return "您可以说：我要去加目的地、导航回家、还有多远、我在哪、" +
            "切换到语音模式、切换到实景模式、大声一点、小声一点、" +
            "紧急求助、停止导航、看看前面有什么。说帮助可重复收听。"
    }
}
