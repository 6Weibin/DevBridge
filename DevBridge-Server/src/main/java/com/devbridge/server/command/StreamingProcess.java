package com.devbridge.server.command;

/**
 * 长进程句柄，用于实时日志等需要显式停止的命令。
 *
 * <p>by AI.Coding</p>
 */
public interface StreamingProcess {

    /**
     * 获取进程会话 ID。
     *
     * @return 会话 ID
     */
    String id();

    /**
     * 判断进程是否仍在运行。
     *
     * @return 运行中返回 true
     */
    boolean isAlive();

    /**
     * 停止进程并释放读取线程。
     */
    void stop();
}
