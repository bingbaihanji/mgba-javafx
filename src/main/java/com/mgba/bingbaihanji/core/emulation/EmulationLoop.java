package com.mgba.bingbaihanji.core.emulation;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 帧循环：按核心报告的 FPS × 倍速节奏调用 core.runOnce()。
 * 绝对时间调度防睡眠漂移。运行在独立线程 mgba-frame-loop。
 */
public final class EmulationLoop {

    private final EmulatorCore core;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile double speedMultiplier = 1.0;
    private volatile double targetFps = 60.0;
    private Thread thread;

    /** 每帧完成后回调（用于通知渲染层）。 */
    private volatile Runnable onFrameProduced = () -> {};

    public EmulationLoop(EmulatorCore core) {
        this.core = core;
    }

    public void setSpeedMultiplier(double speed) {
        this.speedMultiplier = speed;
    }

    public double getSpeedMultiplier() {
        return speedMultiplier;
    }

    public void setTargetFps(double fps) {
        this.targetFps = fps;
    }

    public void setOnFrameProduced(Runnable callback) {
        this.onFrameProduced = callback;
    }

    public boolean isRunning() {
        return running.get();
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            thread = new Thread(this::loopBody, "mgba-frame-loop");
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY + 2);
            thread.start();
        }
    }

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

    private void loopBody() {
        long nextFrame = System.nanoTime();
        while (running.get()) {
            long t0 = System.nanoTime();
            int result = core.runOnce();
            if (result != 0) {
                // runOnce 失败：短暂等待后继续（核心可能恢复）
                try { Thread.sleep(10); } catch (InterruptedException e) { return; }
                nextFrame = System.nanoTime();
                continue;
            }

            onFrameProduced.run();

            double fps = targetFps * speedMultiplier;
            long frameNanos = Math.max(1_000_000L, (long) (1_000_000_000.0 / fps));
            nextFrame += frameNanos;

            long now = System.nanoTime();
            long sleep = nextFrame - now;

            if (sleep < -frameNanos * 2) {
                // 落后太多，重新同步
                nextFrame = now;
            } else if (sleep > 1_000_000L) {
                try {
                    Thread.sleep((sleep - 1_000_000L) / 1_000_000L);
                } catch (InterruptedException e) {
                    return;
                }
                // 最后 1ms 自旋
                while (System.nanoTime() < nextFrame && running.get()) {
                    // spin
                }
            } else if (sleep > 0) {
                while (System.nanoTime() < nextFrame && running.get()) {
                    // spin
                }
            }
        }
    }
}
