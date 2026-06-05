package com.blindpath.module_voice.service

/**
 * 唤醒词检测器接口
 *
 * 统一百度、讯飞、能量检测三种唤醒引擎的接口
 * 所有唤醒引擎必须实现此接口
 */
interface WakeWordDetector {

    /**
     * 启动监听
     */
    fun startListening()

    /**
     * 处理音频数据
     * @param audioData 16-bit PCM 音频数据
     * @return 是否检测到唤醒词
     */
    fun process(audioData: ShortArray): Boolean

    /**
     * 获取音频帧长度
     */
    fun getFrameLength(): Int

    /**
     * 获取采样率
     */
    fun getSampleRate(): Int

    /**
     * 释放资源
     */
    fun release()
}
