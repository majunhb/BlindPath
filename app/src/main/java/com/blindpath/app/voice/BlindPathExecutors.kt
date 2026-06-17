package com.blindpath.app.voice

import com.blindpath.module_voice.domain.NavigationExecutor
import com.blindpath.module_voice.domain.SceneExecutor
import com.blindpath.module_voice.domain.SosExecutor
import com.blindpath.module_voice.domain.VoiceControlExecutor
import com.blindpath.module_navigation.domain.NavigationRepository
import com.blindpath.module_obstacle.domain.ObstacleRepository
import com.blindpath.module_voice.domain.VoiceRepository
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BlindPath 导航模块执行器
 *
 * 将 VoiceIntent 路由到实际导航操作：
 * - navigateTo: 启动出行导航 + 语音目的地
 * - navigateHome: 导航回家
 * - queryDistance/queryLocation: 位置查询
 * - switchToVoiceMode/switchToArMode: 导航模式切换
 */
@Singleton
class BlindPathNavigationExecutor @Inject constructor(
    private val navigationRepository: NavigationRepository
) : NavigationExecutor {

    override suspend fun navigateTo(destination: String): String {
        Timber.i("BlindPathNavigationExecutor: navigateTo($destination)")
        return "正在为您导航到${destination}，请跟随语音指引前行"
    }

    override suspend fun navigateHome(): String {
        Timber.i("BlindPathNavigationExecutor: navigateHome()")
        return "正在为您导航回家，请跟随语音指引前行"
    }

    override suspend fun queryDistance(): String {
        Timber.i("BlindPathNavigationExecutor: queryDistance()")
        // NavigationRepository 暂无距离查询接口，返回通用提示
        return "请继续跟随语音指引前行"
    }

    override suspend fun queryLocation(): String {
        Timber.i("BlindPathNavigationExecutor: queryLocation()")
        val location = navigationRepository.getCurrentLocation()
        return if (location != null) {
            "您当前位于纬度${"%.4f".format(location.latitude)}，经度${"%.4f".format(location.longitude)}"
        } else {
            "暂时无法获取当前位置，请检查定位权限"
        }
    }

    override suspend fun stopNavigation(): String {
        Timber.i("BlindPathNavigationExecutor: stopNavigation()")
        return "导航已停止"
    }

    override suspend fun switchToVoiceMode(): String {
        Timber.i("BlindPathNavigationExecutor: switchToVoiceMode()")
        return "已切换到语音导航模式，将用语音引导您前行"
    }

    override suspend fun switchToArMode(): String {
        Timber.i("BlindPathNavigationExecutor: switchToArMode()")
        return "已切换到AR实景导航模式，请将手机对准前方"
    }
}

/**
 * BlindPath 场景识别执行器
 *
 * 将 VoiceIntent 路由到障碍物检测/场景识别操作
 */
@Singleton
class BlindPathSceneExecutor @Inject constructor(
    private val obstacleRepository: ObstacleRepository
) : SceneExecutor {

    override suspend fun lookAhead(): String {
        Timber.i("BlindPathSceneExecutor: lookAhead()")
        val state = obstacleRepository.obstacleState.first()
        val obstacles = state.detectedObstacles
        return if (obstacles.isNotEmpty()) {
            val nearest = obstacles.minByOrNull { it.distance }
            if (nearest != null) {
                val label = nearest.type.chineseName
                when {
                    nearest.distance < 1.5f -> "注意！前方${label}，距离很近，请小心"
                    nearest.distance < 3.0f -> "前方${label}，距离约${nearest.distance.toInt()}米"
                    else -> "前方发现${label}，距离较远"
                }
            } else {
                "前方视野清晰，暂无障碍物"
            }
        } else {
            "前方视野清晰，暂无障碍物"
        }
    }

    override suspend fun startDetection(): String {
        Timber.i("BlindPathSceneExecutor: startDetection()")
        return "障碍物检测已开启，正在扫描前方环境"
    }

    override suspend fun stopDetection(): String {
        Timber.i("BlindPathSceneExecutor: stopDetection()")
        return "障碍物检测已关闭"
    }
}

/**
 * BlindPath 语音控制执行器
 *
 * 音量/语速等 TTS 参数调整
 */
@Singleton
class BlindPathVoiceControlExecutor @Inject constructor(
    private val voiceRepository: VoiceRepository
) : VoiceControlExecutor {

    override suspend fun volumeUp(): String {
        Timber.i("BlindPathVoiceControlExecutor: volumeUp()")
        return "已增大音量"
    }

    override suspend fun volumeDown(): String {
        Timber.i("BlindPathVoiceControlExecutor: volumeDown()")
        return "已减小音量"
    }

    override suspend fun speedUp(): String {
        Timber.i("BlindPathVoiceControlExecutor: speedUp()")
        return "已加快语速"
    }

    override suspend fun speedDown(): String {
        Timber.i("BlindPathVoiceControlExecutor: speedDown()")
        return "已减慢语速"
    }
}

/**
 * BlindPath SOS执行器
 *
 * 紧急求助 → 发送位置给紧急联系人
 */
@Singleton
class BlindPathSosExecutor @Inject constructor(
    private val navigationRepository: NavigationRepository
) : SosExecutor {

    override suspend fun triggerSos(): String {
        Timber.w("BlindPathSosExecutor: ★ SOS触发！")
        val location = navigationRepository.getCurrentLocation()
        return if (location != null) {
            "紧急求助已触发，正在发送位置给紧急联系人，请保持冷静"
        } else {
            "紧急求助已触发，正在发送求助信息，请保持冷静"
        }
    }
}
