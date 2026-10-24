package com.mgba.bingbaihanji.core.emulation;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 帧循环：按「目标 FPS × 倍速」的节奏调用 {@link EmulatorCore#runOnce()}。
 *
 * <p>绝对时间调度（{@code nextFrame += frameNanos}）防止睡眠漂移。
 * 落后超过两帧时自动重新同步。
 *
 * <p>线程模型：内部启动 daemon 线程 {@code mgba-frame-loop}，
 * {@link #stop()} 会阻塞等待线程退出（最多 1 秒）。
 */
public final class EmulationLoop {

    private final EmulatorCore core;

    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile double speedMultiplier = 1.0;

    private volatile double targetFps = 60.0;

    private volatile Runnable onFrameProduced = () -> {};

    private Thread thread;

    public EmulationLoop(EmulatorCore core) {
        this.core = core;
    }

    public double getSpeedMultiplier() {
        return speedMultiplier;
    }

    /** 设置倍速（0.2 ~ 16.0），1.0 = 原速。 */
    public void setSpeedMultiplier(double speed) {
        this.speedMultiplier = speed;
    }

    /** 设置目标 FPS（通常从 AvInfo 获取）。 */
    public void setTargetFps(double fps) {
        this.targetFps = fps;
    }

    /** 注册每帧完成后的回调（用于通知渲染层重绘）。 */
    public void setOnFrameProduced(Runnable callback) {
        this.onFrameProduced = callback;
    }

    public boolean isRunning() {
        return running.get();
    }

    /** 启动帧循环。已在运行时为空操作。 */
    public void start() {
        if (running.compareAndSet(false, true)) {
            thread = new Thread(this::loopBody, "mgba-frame-loop");
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY + 2);
            thread.start();
        }
    }

    /** 停止帧循环并等待线程退出（最多 1 秒）。 */
    public void stop() {
        running.set(false);
        if (thread != null) {
            try {
                thread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
    }

    // ── 内部 ──────────────────────────────────────────────────

    private void loopBody() {
        long nextFrame = System.nanoTime();

        while (running.get()) {
            if (core.runOnce() != 0) {
                // 核心调用失败：短暂等待后重新同步时间轴
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    return;
                }
                nextFrame = System.nanoTime();
                continue;
            }

            onFrameProduced.run();
            nextFrame += frameNanos();
            long sleep = nextFrame - System.nanoTime();

            if (sleep < -frameNanos() * 2) {
                // 落后太多：重新同步
                nextFrame = System.nanoTime();
            } else {
                sleepUntil(nextFrame);
            }
        }
    }

    private long frameNanos() {
        double fps = targetFps * speedMultiplier;
        return Math.max(1_000_000L, (long) (1_000_000_000.0 / fps));
    }

    /** 睡眠到目标时间点，最后 1ms 自旋以提高精度。 */
    private void sleepUntil(long deadline) {
        long sleep = deadline - System.nanoTime();
        if (sleep > 1_000_000L) {
            try {
                Thread.sleep((sleep - 1_000_000L) / 1_000_000L);
            } catch (InterruptedException e) {
                return;
            }
        }
        while (System.nanoTime() < deadline && running.get()) {
            // 最后 1ms 自旋
        }
    }
}
