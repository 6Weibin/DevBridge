package com.devbridge.server.command;

/**
 * 单个进程输出流的读取上限，在读取阶段同时约束行数和字节数。
 *
 * <p>by AI.Coding</p>
 *
 * @param maxLines 最大保留行数
 * @param maxBytes 最大保留字节数
 */
public record ProcessOutputLimit(int maxLines, int maxBytes) {

    /**
     * 校验输出上限，避免无效配置退化为无界读取。
     */
    public ProcessOutputLimit {
        if (maxLines <= 0) {
            throw new IllegalArgumentException("maxLines must be greater than zero");
        }
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be greater than zero");
        }
    }
}
