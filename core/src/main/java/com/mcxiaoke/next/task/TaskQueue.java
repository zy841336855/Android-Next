package com.mcxiaoke.next.task;

import android.os.Handler;
import android.os.Handler.Callback;
import android.os.Looper;
import android.os.Message;
import com.mcxiaoke.next.utils.LogUtils;
import com.mcxiaoke.next.utils.StringUtils;
import com.mcxiaoke.next.utils.ThreadUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 一个用于执行异步任务的类，单例，支持检查Caller，支持按照Caller和Tag取消对应的任务
 * User: mcxiaoke
 * Date: 2013-7-1 2013-7-25 2014-03-04 2014-03-25 2014-05-14 2014-05-29
 */
public final class TaskQueue implements Callback {
    public static final String TAG = TaskQueue.class.getSimpleName();
    // 某一个线程运行结束时需要从TaskMap里移除
    public static final int MSG_TASK_DONE = 4001;
    private final Object mLock = new Object();
    private ThreadPoolExecutor mExecutor;
    private ThreadPoolExecutor mSerialExecutor;
    private Handler mUiHandler;
    private Map<Integer, List<String>> mCallerMap;
    private Map<String, TaskRunnable> mTaskMap;
    private boolean mEnableCallerAliveCheck;
    private boolean mDebug;

    public TaskQueue() {
        if (mDebug) {
            LogUtils.v(TAG, "NextExecutor()");
        }
        ensureThread();
        ensureData();
        ensureHandler();
        ensureExecutor();
    }

    public static TaskQueue getDefault() {
        return SingletonHolder.DEFAULT;
    }

    /**
     * 检查参数非空
     *
     * @param args 参数列表
     */
    private static void checkArguments(final Object... args) {
        for (Object o : args) {
            if (o == null) {
                throw new NullPointerException("argument can not be null.");
            }
        }
    }

    private void ensureThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("TaskQueue instance must be created on main thread");
        }
    }

    private void ensureData() {
//        if (mDebug) {
//            LogUtils.v(TAG, "ensureData()");
//        }
        if (mTaskMap == null) {
            mTaskMap = new ConcurrentHashMap<String, TaskRunnable>();
        }
        if (mCallerMap == null) {
            mCallerMap = new ConcurrentHashMap<Integer, List<String>>();
        }
    }

    /**
     * 是否检查Android组件生命周期
     * 对于Activity，检查Activity.isFinishing()
     * 对于Fragment，检查Fragment.isAdded()
     * 如果不满足条件，取消回调
     *
     * @param enable enable
     */
    public void setEnableCallerAliveCheck(boolean enable) {
        mEnableCallerAliveCheck = enable;
    }

    /**
     * debug开关
     *
     * @param debug 是否开启DEBUG模式
     */
    public void setDebug(boolean debug) {
        mDebug = debug;
    }

    private void logExecutor(final String name, final ThreadPoolExecutor executor) {
        final int corePoolSize = executor.getCorePoolSize();
        final int poolSize = executor.getPoolSize();
        final int activeCount = executor.getActiveCount();
        final long taskCount = executor.getTaskCount();
        final long completedCount = executor.getCompletedTaskCount();
        final boolean isShutdown = executor.isShutdown();
        final boolean isTerminated = executor.isTerminated();
        LogUtils.v(TAG, name + " CorePoolSize:" + corePoolSize + " PoolSize:" + poolSize);
        LogUtils.v(TAG, name + " isShutdown:" + isShutdown + " isTerminated:" + isTerminated);
        LogUtils.v(TAG, name + " activeCount:" + activeCount + " taskCount:" + taskCount
                + " completedCount:" + completedCount);
    }

    /**
     * 执行异步任务，回调时会检查Caller是否存在，如果不存在就不执行回调函数
     *
     * @param callable Callable对象，任务的实际操作
     * @param callback 回调接口
     * @param caller   调用方，一般为Fragment或Activity
     * @param serial   是否按顺序执行任务
     * @param <Result> 类型参数，异步任务执行结果
     * @return 返回内部生成的此次任务的TAG
     */
    private <Result> String enqueue(final Callable<Result> callable, final TaskCallback<Result> callback,
                                    final Object caller, final boolean serial) {

        checkArguments(callable, caller);
        ensureData();
        ensureHandler();
        ensureExecutor();

        if (mDebug) {
            LogUtils.v(TAG, "enqueue() serial=" + serial);
        }

        final Handler handler = mUiHandler;
        final boolean enable = mEnableCallerAliveCheck;

        final TaskCallable<Result> nextCallable;
        if (callable instanceof TaskCallable) {
            nextCallable = (TaskCallable<Result>) callable;
        } else {
            nextCallable = new TaskCallableWrapper<Result>(callable);
        }

        final TaskRunnable<Result> runnable = new TaskRunnable<Result>
                (handler, enable, serial, nextCallable, callback, caller);
        runnable.setDebug(mDebug);

        addToTaskMap(runnable);
        addToCallerMap(runnable);

        return runnable.getTag();
    }

    public <Result> String execute(final Callable<Result> callable, final TaskCallback<Result> callback,
                                   final Object caller, final boolean serially) {
        if (mDebug) {
            LogUtils.v(TAG, "execute()");
        }
        return enqueue(callable, callback, caller, serially);
    }

    public <Result> String add(final Callable<Result> callable,
                               final TaskCallback<Result> callback,
                               final Object caller) {
        if (mDebug) {
            LogUtils.v(TAG, "execute()");
        }
        return enqueue(callable, callback, caller, false);
    }

    /**
     * 没有回调
     *
     * @param callable Callable
     * @param caller   Caller
     * @param <Result> Result
     * @param caller   Caller
     * @return Tag
     */
    public <Result> String add(final Callable<Result> callable, final Object caller) {
        return add(callable, null, caller);
    }

    public <Result> String addSerially(final Callable<Result> callable,
                                       final TaskCallback<Result> callback, final Object caller) {
        if (mDebug) {
            LogUtils.v(TAG, "addSerially()");
        }
        return enqueue(callable, callback, caller, true);
    }

    /**
     * 没有回调
     *
     * @param callable Callable
     * @param caller   Caller
     * @param <Result> Result
     * @param caller   Caller
     * @return Tag
     */
    public <Result> String addSerially(final Callable<Result> callable, final Object caller) {
        return addSerially(callable, null, caller);
    }

    /**
     * 检查某个任务是否正在运行
     *
     * @param tag 任务的TAG
     * @return 是否正在运行
     */
    public boolean isActive(String tag) {
        TaskRunnable nr = mTaskMap.get(tag);
        return nr != null && nr.isRunning();
    }

    private <Result> void addToTaskMap(final TaskRunnable<Result> runnable) {
        final String tag = runnable.getTag();
        if (mDebug) {
            LogUtils.v(TAG, "addToTaskMap() tag=" + tag);
        }
        Future<?> future = smartSubmit(runnable);
        runnable.setFuture(future);
        addTagToTaskMap(tag, runnable);
    }

    private <Result> void addTagToTaskMap(final String tag, final TaskRunnable<Result> runnable) {
        synchronized (mLock) {
            mTaskMap.put(tag, runnable);
        }
    }

    private void removeTagFromTaskMap(final String tag) {
//        if (mDebug) {
//            LogUtils.v(TAG, "removeTagFromTaskMap() tag=" + tag);
//        }
        synchronized (mLock) {
            mTaskMap.remove(tag);
        }
    }

    private <Result> void addToCallerMap(final TaskRunnable<Result> runnable) {
        // caller的key是hashcode
        // tag的组成:className+hashcode+timestamp+sequenceNumber
        final int hashCode = runnable.getHashCode();
        final String tag = runnable.getTag();
        if (mDebug) {
            LogUtils.v(TAG, "addToCallerMap() tag=" + tag);
        }
        addTagToCallerMap(hashCode, tag);

    }

    private void addTagToCallerMap(final int hashCode, final String tag) {
        List<String> tags = mCallerMap.get(hashCode);
        if (tags == null) {
            tags = new ArrayList<String>();
            synchronized (mLock) {
                mCallerMap.put(hashCode, tags);
            }
        }
        synchronized (mLock) {
            tags.add(tag);
        }
    }

    private void removeTagFromCallerMap(final int hashCode, final String tag) {
//        if (mDebug) {
//            LogUtils.v(TAG, "removeTagFromCallerMap() hashCode=" + hashCode + " tag=" + tag);
//        }
        List<String> tags = mCallerMap.get(hashCode);
        if (tags != null) {
            synchronized (mLock) {
                tags.remove(tag);
            }
        }
    }

    /**
     * 便利任务列表，取消所有任务
     */
    public void cancelAll() {
        if (mDebug) {
            LogUtils.v(TAG, "cancelAll()");
        }
        cancelAllInternal();
    }

    /**
     * 取消所有的Runnable对应的任务
     */
    private void cancelAllInternal() {
        Collection<TaskRunnable> runnables = mTaskMap.values();
        for (TaskRunnable runnable : runnables) {
            if (runnable != null) {
                runnable.cancel();
            }
        }
        synchronized (mLock) {
            mTaskMap.clear();
        }
    }

    /**
     * 取消TAG对应的任务
     *
     * @param tag 任务TAG
     * @return 任务是否存在
     */
    public boolean cancel(String tag) {
        if (mDebug) {
            LogUtils.v(TAG, "cancel() tag=" + tag);
        }
        boolean result = false;
        final TaskRunnable runnable;
        synchronized (mLock) {
            runnable = mTaskMap.remove(tag);
        }
        if (runnable != null) {
            result = runnable.cancel();
        }
        return result;
    }

    /**
     * 取消由该调用方发起的所有任务
     * 建议在Fragment或Activity的onDestroy中调用
     *
     * @param caller 任务调用方
     * @return 返回取消的数目
     */
    public <Caller> int cancelAll(Caller caller) {
        if (mDebug) {
            LogUtils.v(TAG, "cancelAll() caller=" + caller.getClass().getSimpleName());
        }
        int cancelledCount = 0;
        final int hashCode = System.identityHashCode(caller);
        final List<String> tags;
        synchronized (mLock) {
            tags = mCallerMap.remove(hashCode);
        }
        if (tags == null || tags.isEmpty()) {
            return cancelledCount;
        }

        for (String tag : tags) {
            cancel(tag);
            ++cancelledCount;
        }

        if (mDebug) {
            LogUtils.v(TAG, "cancelAll() cancelledCount=" + cancelledCount);
        }

        return cancelledCount;
    }

    /**
     * 设置自定义的ExecutorService
     *
     * @param executor ExecutorService
     */
    public void setExecutor(final ThreadPoolExecutor executor) {
        mExecutor = executor;
    }

    /**
     * 取消所有任务，关闭TaskExecutor
     */
    public void destroy() {
        if (mDebug) {
            LogUtils.v(TAG, "destroy()");
        }
        cancelAll();
        destroyHandler();
        destroyExecutor();
    }

    /**
     * 从队列移除某个任务
     *
     * @param tag 任务TAG
     */
    private void remove(String tag) {
        if (mDebug) {
            LogUtils.v(TAG, "remove() tag=" + tag);
        }
        final String hashCodeStr = tag.split(TaskRunnable.SEPARATOR)[1];
        final int hashCode = Integer.valueOf(hashCodeStr);
        removeTagFromTaskMap(tag);
        removeTagFromCallerMap(hashCode, tag);
    }

    /**
     * 将任务添加到线程池执行
     *
     * @param runnable 任务Runnable
     * @return 返回任务对应的Future对象
     */
    private Future<?> smartSubmit(final TaskRunnable runnable) {
        if (runnable.isSerial()) {
            return submitSerial(runnable);
        } else {
            return submit(runnable);
        }
    }

    private Future<?> submit(final Runnable runnable) {
        ensureHandler();
        ensureExecutor();
        return mExecutor.submit(runnable);
    }

    private <T> Future<T> submit(final Callable<T> callable) {
        ensureHandler();
        ensureExecutor();
        return mExecutor.submit(callable);
    }

    private Future<?> submitSerial(final Runnable runnable) {
        ensureHandler();
        ensureExecutor();
        return mSerialExecutor.submit(runnable);
    }

    private <T> Future<T> submitSerial(final Callable<T> callable) {
        ensureHandler();
        ensureExecutor();
        return mSerialExecutor.submit(callable);
    }

    /**
     * 检查并初始化ExecutorService
     */
    private void ensureExecutor() {
        if (mExecutor == null || mExecutor.isShutdown()) {
            mExecutor = ThreadUtils.newCachedThreadPool("next");
        }
        if (mSerialExecutor == null || mSerialExecutor.isShutdown()) {
            mSerialExecutor = ThreadUtils.newSingleThreadExecutor("next-serial");
        }
    }

    /**
     * 检查并初始化Handler，主线程处理消息
     * TODO 考虑把所有的操作都放到一个单独的线程，避免cancelAll等操作堵塞住线程
     */
    private void ensureHandler() {
        if (mUiHandler == null) {
            mUiHandler = new Handler(this);
        }
    }

    @Override
    public boolean handleMessage(final Message msg) {
        if (mDebug) {
            LogUtils.v(TAG, "handleMessage() what=" + msg.what);
        }
        switch (msg.what) {
            case MSG_TASK_DONE: {
                final String tag = (String) msg.obj;
                if (mDebug) {
                    LogUtils.v(TAG, "handleMessage() tag:" + tag);
                    logExecutor("Executor", mExecutor);
                    logExecutor("SerialExecutor", mSerialExecutor);
                }
                remove(tag);
            }
            break;
            default:
                break;
        }
        return true;
    }

    /**
     * 关闭Executor
     */
    private void destroyExecutor() {
        if (mExecutor != null) {
            mExecutor.shutdownNow();
            mExecutor = null;
        }
        if (mSerialExecutor != null) {
            mSerialExecutor.shutdownNow();
            mSerialExecutor = null;
        }
    }

    /**
     * 关闭Handler
     */
    private void destroyHandler() {
        synchronized (mLock) {
            if (mUiHandler != null) {
                mUiHandler.removeCallbacksAndMessages(null);
                mUiHandler = null;
            }
        }
    }

    /**
     * 获取当前实例的详细信息
     *
     * @param logcat 是否输出到logcat
     * @return
     */
    public String dump(final boolean logcat) {
        final StringBuilder builder = new StringBuilder();

        final ThreadPoolExecutor executor = mExecutor;

        // thread pool info
        final int corePoolSize = executor.getCorePoolSize();
        final int poolSize = executor.getPoolSize();
        final int activeCount = executor.getActiveCount();
        final long taskCount = executor.getTaskCount();
        final long completedCount = executor.getCompletedTaskCount();
        final boolean isShutdown = executor.isShutdown();
        final boolean isTerminated = executor.isTerminated();
        builder.append(TAG).append("[ ");
        builder.append("ThreadPool:{")
                .append(" CorePoolSize:").append(corePoolSize).append(";")
                .append(" PoolSize:").append(poolSize).append(";")
                .append(" isShutdown:").append(isShutdown).append(";")
                .append(" isTerminated:").append(isTerminated).append(";")
                .append(" activeCount:").append(activeCount).append(";")
                .append(" taskCount:").append(taskCount).append(";")
                .append(" completedCount:").append(completedCount).append(";")
                .append("}\n");

//        private Map<Integer, List<String>> mCallerMap;
//        private Map<String, TaskRunnable> mTaskMap;
        // caller map
        final Map<Integer, List<String>> callerMap = mCallerMap;
        builder.append("CallerMap:{");
        for (Map.Entry<Integer, List<String>> entry : callerMap.entrySet()) {
            builder.append(" caller:").append(entry.getKey())
                    .append(", tags:").append(StringUtils.toString(entry.getValue())).append(";");
        }
        builder.append("}\n");
        builder.append("]");

        // task map
        Map<String, TaskRunnable> taskMap = mTaskMap;
        builder.append("TaskMap:{");
        for (Map.Entry<String, TaskRunnable> entry : taskMap.entrySet()) {
            builder.append(" tag:").append(entry.getKey())
                    .append(", runnable:").append(entry.getValue()).append(";");
        }
        builder.append("}\n");

        final String info = builder.toString();

        if (logcat) {
            LogUtils.d(TAG, info);
        }
        return info;
    }

    // 延迟加载
    private static final class SingletonHolder {
        static final TaskQueue DEFAULT = new TaskQueue();
    }

}