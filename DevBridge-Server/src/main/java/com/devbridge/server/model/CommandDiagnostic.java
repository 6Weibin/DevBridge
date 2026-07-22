package com.devbridge.server.model;

import java.util.List;

/**
 * 本机命令诊断结果，用于排查工具可用但业务接口无数据的问题。
 *
 * <p>by AI.Coding</p>
 *
 * @param command 实际执行的命令参数
 * @param exitCode 退出码
 * @param stdout 标准输出
 * @param stderr 错误输出
 * @param timedOut 是否超时
 */
public record CommandDiagnostic(
        List<String> command,
        int exitCode,
        List<String> stdout,
        List<String> stderr,
        boolean timedOut) {
}
