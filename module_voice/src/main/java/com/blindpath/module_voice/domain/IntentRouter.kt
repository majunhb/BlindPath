package com.blindpath.module_voice.domain

import com.blindpath.module_voice.domain.model.NluResult
import com.blindpath.module_voice.domain.model.VoiceIntent

/**
 * 意图路由表 — PRD四层架构第四层（业务执行与反馈层）
 *
 * 将NLU解析出的意图精准映射到对应模块的API接口。
 * 执行结果通过"讯飞离线TTS语音播报 + 震动反馈"输出。
 *
 * 路由规则：
 * 1. SOS意图 → 最高优先级，无视当前状态立即执行
 * 2. 导航意图 → 调用NavigationViewModel
 * 3. 模式切换意图 → 调用NavigationViewModel切换模式
 * 4. 场景识别意图 → 调用ObstacleRepository
 * 5. 控制意图 → 调用VoiceRepository调整参数
 *
 * 播报优先级管理：
 * - SOS紧急播报 → P0 立即打断
 * - 障碍物预警 → P0 立即打断
 * - 导航关键指令 → P1 等待当前句子
 * - 普通播报 → P2 排队
 */
interface IntentRouter {

    /**
     * 路由NLU结果到对应模块执行
     *
     * @param nluResult NLU解析结果
     * @return 路由执行结果（包含播报文本和是否成功）
     */
    suspend fun route(nluResult: NluResult): RouteResult

    /**
     * 设置导航模块执行器
     */
    fun setNavigationExecutor(executor: NavigationExecutor)

    /**
     * 设置场景识别执行器
     */
    fun setSceneExecutor(executor: SceneExecutor)

    /**
     * 设置语音控制执行器（音量/语速等）
     */
    fun setVoiceControlExecutor(executor: VoiceControlExecutor)

    /**
     * 设置SOS执行器
     */
    fun setSosExecutor(executor: SosExecutor)
}

/**
 * 路由执行结果
 */
data class RouteResult(
    val success: Boolean,
    val speakText: String,        // 需要TTS播报的反馈文本
    val needsFollowUp: Boolean = false,  // 是否需要继续监听
    val followUpPrompt: String? = null   // 追问提示
)

/**
 * 导航模块执行器接口
 */
interface NavigationExecutor {
    /** 导航到目的地，返回播报文本 */
    suspend fun navigateTo(destination: String): String
    /** 导航回家 */
    suspend fun navigateHome(): String
    /** 查询剩余距离 */
    suspend fun queryDistance(): String
    /** 查询当前位置 */
    suspend fun queryLocation(): String
    /** 停止导航 */
    suspend fun stopNavigation(): String
    /** 切换到语音模式 */
    suspend fun switchToVoiceMode(): String
    /** 切换到AR模式 */
    suspend fun switchToArMode(): String
}

/**
 * 场景识别执行器接口
 */
interface SceneExecutor {
    /** 识别前方场景 */
    suspend fun lookAhead(): String
    /** 开启检测 */
    suspend fun startDetection(): String
    /** 关闭检测 */
    suspend fun stopDetection(): String
}

/**
 * 语音控制执行器接口
 */
interface VoiceControlExecutor {
    /** 增大音量 */
    suspend fun volumeUp(): String
    /** 减小音量 */
    suspend fun volumeDown(): String
    /** 加快语速 */
    suspend fun speedUp(): String
    /** 减慢语速 */
    suspend fun speedDown(): String
}

/**
 * SOS执行器接口
 */
interface SosExecutor {
    /** 触发SOS */
    suspend fun triggerSos(): String
}
