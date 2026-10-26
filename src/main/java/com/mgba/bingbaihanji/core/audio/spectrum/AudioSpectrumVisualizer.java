package com.mgba.bingbaihanji.core.audio.spectrum;

/**
 * 音频频谱可视化器接口
 * 所有频谱显示组件均应实现此接口，以便与音频系统统一交互
 */
public interface AudioSpectrumVisualizer {

    /**
     * 更新频谱数据（由音频系统周期性调用）
     *
     * @param magnitudes 频谱幅度数组，通常长度为128，值范围[-60, 0] dB
     */
    void updateSpectrum(float[] magnitudes);

    /**
     * 清空频谱显示
     */
    void clear();

    /**
     * 启动内部动画循环（如果之前停止过）
     */
    void start();

    /**
     * 停止内部动画循环
     */
    void stop();

    /**
     * 检查当前动画是否正在运行
     *
     * @return true 表示动画正在运行
     */
    boolean isRunning();

    /**
     * 释放资源，停止动画并移除所有内部监听器
     * 当组件不再使用时必须调用此方法
     */
    void dispose();
}