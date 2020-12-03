package com.spoon.backgroundfileupload;

import android.os.Process;

import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class ExecutorSupplier {

    public static final int NUMBER_OF_CORES = Runtime.getRuntime().availableProcessors();
    private final ThreadPoolExecutor mBackgroundTasks;
    private Executor mMainThreadExecutor;
    private static ExecutorSupplier sInstance;

    public static ExecutorSupplier getInstance() {
        if (sInstance == null) {
            synchronized (ExecutorSupplier.class) {
                sInstance = new ExecutorSupplier();
            }
        }
        return sInstance;
    }

    private ExecutorSupplier() {
        ThreadFactory backgroundPriorityThreadFactory = new PriorityThreadFactory(Process.THREAD_PRIORITY_BACKGROUND);
        mBackgroundTasks = new ThreadPoolExecutor(
                2,
                2,
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingDeque<Runnable>(),
                backgroundPriorityThreadFactory
        );

        mMainThreadExecutor = new MainThreadExecutor();
    }

    public ThreadPoolExecutor backgroundTasks() {
        return mBackgroundTasks;
    }
}
