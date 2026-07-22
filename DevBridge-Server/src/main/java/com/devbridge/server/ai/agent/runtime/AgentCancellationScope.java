package com.devbridge.server.ai.agent.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 单个 Agent Task 的取消作用域，串行化注册和取消竞态。
 *
 * <p>by AI.Coding</p>
 */
public final class AgentCancellationScope {

    private static final int MAX_HANDLE_ID_LENGTH = 200;
    private static final int MAX_FAILURES = 20;
    private static final int MAX_FAILURE_LENGTH = 300;

    private final String taskId;
    private final ExecutorService cancellationExecutor;
    private final Duration cleanupTimeout;
    private final Object lock = new Object();
    private final Map<String, RegisteredHandle> handles = new LinkedHashMap<>();
    private boolean canceled;
    private boolean closed;
    private String reason = "";

    /**
     * 创建任务取消作用域。
     *
     * @param taskId 任务标识
     */
    AgentCancellationScope(
            String taskId,
            ExecutorService cancellationExecutor,
            Duration cleanupTimeout) {
        this.taskId = taskId;
        this.cancellationExecutor = cancellationExecutor;
        this.cleanupTimeout = cleanupTimeout;
    }

    /**
     * 注册可取消资源；取消已经发生时立即执行回调，防止竞态逃逸。
     *
     * @param type 句柄类型
     * @param handleId 句柄标识
     * @param handle 取消回调
     * @return 可关闭注册
     */
    public AgentCancellationRegistration register(
            AgentCancellationHandleType type,
            String handleId,
            AgentCancellationHandle handle) {
        String key = key(type, handleId, handle);
        boolean cancelImmediately;
        synchronized (lock) {
            cancelImmediately = canceled || closed;
            if (!cancelImmediately
                    && handles.putIfAbsent(key, new RegisteredHandle(type, handleId, handle)) != null) {
                throw new IllegalStateException("取消句柄已注册: " + key);
            }
        }
        if (cancelImmediately) {
            cancelImmediately(type, handleId, handle);
            return new AgentCancellationRegistration(false, null);
        }
        return new AgentCancellationRegistration(true, () -> unregister(key));
    }

    /**
     * 幂等取消作用域内全部资源，并隔离单个回调异常。
     *
     * @param cancelReason 取消原因
     * @return 取消传播结果
     */
    public AgentCancellationResult cancel(String cancelReason) {
        List<RegisteredHandle> snapshot;
        synchronized (lock) {
            if (canceled) {
                return AgentCancellationResult.notActive();
            }
            canceled = true;
            reason = normalizeReason(cancelReason);
            snapshot = new ArrayList<>(handles.values());
            handles.clear();
        }
        return cancelHandles(snapshot);
    }

    /**
     * 判断任务是否已经请求取消。
     *
     * @return 已取消返回 true
     */
    public boolean isCancellationRequested() {
        synchronized (lock) {
            return canceled;
        }
    }

    /**
     * 获取取消原因。
     *
     * @return 取消原因
     */
    public String reason() {
        synchronized (lock) {
            return reason;
        }
    }

    /**
     * 获取任务标识。
     *
     * @return 任务标识
     */
    public String taskId() {
        return taskId;
    }

    /**
     * 关闭作用域并清理已完成资源注册，不触发取消。
     */
    void close() {
        synchronized (lock) {
            closed = true;
            handles.clear();
        }
    }

    /**
     * 执行取消回调并生成有界失败摘要。
     *
     * @param snapshot 待取消句柄
     * @return 取消结果
     */
    private AgentCancellationResult cancelHandles(List<RegisteredHandle> snapshot) {
        int canceledCount = 0;
        List<String> failures = new ArrayList<>();
        List<PendingCancellation> pending = submitCancellations(snapshot, failures);
        long deadline = System.nanoTime() + cleanupTimeout.toNanos();
        for (PendingCancellation cancellation : pending) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0 || !awaitCancellation(cancellation, remaining, failures)) {
                cancellation.future().cancel(true);
                continue;
            }
            canceledCount++;
        }
        return new AgentCancellationResult(true, snapshot.size(), canceledCount, failures);
    }

    /** 将取消回调提交到独立有界执行器，队列饱和时记录失败而不阻塞任务终态。 */
    private List<PendingCancellation> submitCancellations(
            List<RegisteredHandle> snapshot,
            List<String> failures) {
        List<PendingCancellation> pending = new ArrayList<>();
        for (RegisteredHandle registered : snapshot) {
            try {
                pending.add(new PendingCancellation(
                        registered, cancellationExecutor.submit(registered.handle()::cancel)));
            } catch (RejectedExecutionException ex) {
                addFailure(failures, registered, ex);
            }
        }
        return pending;
    }

    /** 在全局清理期限内等待单个回调，超时或异常均转换为有界失败摘要。 */
    private boolean awaitCancellation(
            PendingCancellation cancellation,
            long remainingNanos,
            List<String> failures) {
        try {
            cancellation.future().get(remainingNanos, TimeUnit.NANOSECONDS);
            return true;
        } catch (TimeoutException ex) {
            addFailure(failures, cancellation.handle(), new RuntimeException("取消清理超时"));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            addFailure(failures, cancellation.handle(), new RuntimeException("取消清理被中断"));
        } catch (ExecutionException ex) {
            Throwable cause = rootCause(ex);
            RuntimeException error = cause instanceof RuntimeException runtime
                    ? runtime : new RuntimeException(cause == null ? ex.getMessage() : cause.getMessage());
            addFailure(failures, cancellation.handle(), error);
        }
        return false;
    }

    /** 解开异步执行器的包装异常，失败摘要只保留真实取消根因。 */
    private Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    /**
     * 取消已经晚于任务取消信号注册的资源。
     *
     * @param handle 取消句柄
     */
    private void cancelImmediately(
            AgentCancellationHandleType type,
            String handleId,
            AgentCancellationHandle handle) {
        // 晚注册资源沿用同一异步超时机制，避免注册线程被不可控清理回调永久阻塞。
        cancelHandles(List.of(new RegisteredHandle(type, handleId, handle)));
    }

    /**
     * 从作用域移除已正常结束的资源句柄。
     *
     * @param key 句柄键
     */
    private void unregister(String key) {
        synchronized (lock) {
            handles.remove(key);
        }
    }

    /**
     * 校验并生成句柄唯一键。
     *
     * @param type 句柄类型
     * @param handleId 句柄标识
     * @param handle 取消回调
     * @return 唯一键
     */
    private String key(
            AgentCancellationHandleType type,
            String handleId,
            AgentCancellationHandle handle) {
        if (type == null || handle == null || handleId == null || handleId.isBlank()) {
            throw new IllegalArgumentException("取消句柄类型、标识和回调不能为空");
        }
        if (handleId.length() > MAX_HANDLE_ID_LENGTH) {
            throw new IllegalArgumentException("取消句柄标识过长");
        }
        return type.name() + ":" + handleId;
    }

    /**
     * 追加有界失败摘要，避免异常堆栈进入任务事件。
     *
     * @param failures 失败列表
     * @param registered 注册句柄
     * @param error 回调异常
     */
    private void addFailure(
            List<String> failures,
            RegisteredHandle registered,
            RuntimeException error) {
        if (failures.size() >= MAX_FAILURES) {
            return;
        }
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        String summary = registered.type().name() + ":" + registered.handleId() + ":" + message;
        failures.add(summary.length() <= MAX_FAILURE_LENGTH
                ? summary
                : summary.substring(0, MAX_FAILURE_LENGTH));
    }

    /**
     * 规范化取消原因。
     *
     * @param cancelReason 原始原因
     * @return 非空原因
     */
    private String normalizeReason(String cancelReason) {
        return cancelReason == null || cancelReason.isBlank() ? "任务已取消" : cancelReason.trim();
    }

    /**
     * 作用域内部注册句柄。
     *
     * <p>by AI.Coding</p>
     *
     * @param type 句柄类型
     * @param handleId 句柄标识
     * @param handle 取消回调
     */
    private record RegisteredHandle(
            AgentCancellationHandleType type,
            String handleId,
            AgentCancellationHandle handle) {
    }

    /** 取消回调和异步结果的内部绑定。 */
    private record PendingCancellation(RegisteredHandle handle, Future<?> future) {
    }
}
