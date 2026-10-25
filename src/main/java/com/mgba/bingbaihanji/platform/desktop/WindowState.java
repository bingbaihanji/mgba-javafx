package com.mgba.bingbaihanji.platform.desktop;

/**
 * 主窗口状态（持久化 DTO）。
 *
 * <p>字段与 Jackson 直接按名字映射。位置可能尚未记录（首次启动），
 * 因此 {@code x}/{@code y} 用可空的 {@link Double}，而不是哨兵值。
 */
public final class WindowState {

    /** 默认窗口宽度。 */
    public static final double DEFAULT_WIDTH = 960.0;

    /** 默认窗口高度。 */
    public static final double DEFAULT_HEIGHT = 640.0;

    /** 窗口宽度（未最大化时的尺寸）。 */
    public double width = DEFAULT_WIDTH;

    /** 窗口高度（未最大化时的尺寸）。 */
    public double height = DEFAULT_HEIGHT;

    /** 窗口左上角 X；null 表示交给 JavaFX 决定初始位置。 */
    public Double x;

    /** 窗口左上角 Y；null 表示交给 JavaFX 决定初始位置。 */
    public Double y;

    /** 关闭时是否处于最大化状态。 */
    public boolean maximized;
}
