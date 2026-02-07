package com.qiyi.agent;

import com.qiyi.util.AppLog;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Agent 基类，统一封装生命周期与并发控制：
 * <ul>
 *     <li>进程内：同一个 Agent 实例禁止重复 start()</li>
 *     <li>跨进程：基于文件锁保证同类 Agent 仅允许单实例运行</li>
 * </ul>
 */
public abstract class AbstractAgent implements IAgent {
    private final AtomicBoolean started = new AtomicBoolean(false);

    private java.io.RandomAccessFile singleInstanceRaf;
    private java.nio.channels.FileChannel singleInstanceChannel;
    private java.nio.channels.FileLock singleInstanceLock;
    private java.io.File singleInstanceLockFile;

    protected String lockName() {
        return this.getClass().getSimpleName();
    }

    @Override
    public final void start() {
        if (!started.compareAndSet(false, true)) {
            AppLog.warn(lockName() + " 已在当前进程中启动，忽略重复 start()");
            return;
        }
        if (!acquireSingleInstanceLock()) {
            AppLog.warn(lockName() + " 已在运行（跨进程单实例锁未获取），忽略 start()");
            started.set(false);
            return;
        }
        try {
            AppLog.info(lockName() + " start() begin");
            doStart();
            AppLog.info(lockName() + " start() done");
        } catch (Exception e) {
            AppLog.error(lockName() + " start() failed", e);
            try {
                stop();
            } catch (Exception ignored) {
            }
            throw e;
        }
    }

    @Override
    public final void stop() {
        if (!started.compareAndSet(true, false)) {
            return;
        }
        try {
            AppLog.info(lockName() + " stop() begin");
            doStop();
        } catch (Exception e) {
            AppLog.error(lockName() + " stop() failed", e);
        } finally {
            releaseSingleInstanceLock();
            AppLog.info(lockName() + " stop() done");
        }
    }

    protected abstract void doStart();

    protected void doStop() {
    }

    protected boolean isRunning() {
        return started.get();
    }

    private boolean acquireSingleInstanceLock() {
        String lockFileName = lockName() + ".lock";
        java.io.File lockFile = new java.io.File(System.getProperty("java.io.tmpdir"), lockFileName);
        java.io.RandomAccessFile raf;
        java.nio.channels.FileChannel channel;
        java.nio.channels.FileLock acquiredLock;
        try {
            raf = new java.io.RandomAccessFile(lockFile, "rw");
            channel = raf.getChannel();
            try {
                acquiredLock = channel.tryLock();
            } catch (java.nio.channels.OverlappingFileLockException e) {
                acquiredLock = null;
            }
            if (acquiredLock == null) {
                try {
                    channel.close();
                } catch (Exception ignored) {
                }
                try {
                    raf.close();
                } catch (Exception ignored) {
                }
                return false;
            }
        } catch (Exception e) {
            AppLog.error(lockName() + " acquire single instance lock failed", e);
            return false;
        }

        this.singleInstanceLockFile = lockFile;
        this.singleInstanceRaf = raf;
        this.singleInstanceChannel = channel;
        this.singleInstanceLock = acquiredLock;

        Runtime.getRuntime().addShutdownHook(new Thread(this::releaseSingleInstanceLock));
        AppLog.info(lockName() + " acquired single instance lock: " + lockFile.getAbsolutePath());
        return true;
    }

    private void releaseSingleInstanceLock() {
        java.nio.channels.FileLock lock = this.singleInstanceLock;
        java.nio.channels.FileChannel channel = this.singleInstanceChannel;
        java.io.RandomAccessFile raf = this.singleInstanceRaf;
        java.io.File file = this.singleInstanceLockFile;

        this.singleInstanceLock = null;
        this.singleInstanceChannel = null;
        this.singleInstanceRaf = null;
        this.singleInstanceLockFile = null;

        try {
            if (lock != null) lock.release();
        } catch (Exception ignored) {
        }
        try {
            if (channel != null) channel.close();
        } catch (Exception ignored) {
        }
        try {
            if (raf != null) raf.close();
        } catch (Exception ignored) {
        }
        try {
            if (file != null) file.delete();
        } catch (Exception ignored) {
        }
    }
}
