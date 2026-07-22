package com.devbridge.server.ai.storage;

import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * AI 本地文件维护读写锁，阻止备份恢复与 Task、Conversation 文件写入并发交叉。
 *
 * <p>by AI.Coding</p>
 */
@Component
public class AiDataMaintenanceLock {

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);

    /** 在普通业务读锁内执行文件操作。 */
    public <T> T read(Supplier<T> action) {
        lock.readLock().lock();
        try {
            return action.get();
        } finally {
            lock.readLock().unlock();
        }
    }

    /** 在普通业务读锁内执行无返回值文件操作。 */
    public void read(Runnable action) {
        read(() -> {
            action.run();
            return null;
        });
    }

    /** 在维护写锁内执行备份、恢复和索引重建。 */
    public <T> T write(Supplier<T> action) {
        lock.writeLock().lock();
        try {
            return action.get();
        } finally {
            lock.writeLock().unlock();
        }
    }
}
