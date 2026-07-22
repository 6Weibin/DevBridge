package com.devbridge.server.command;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 基于 Java ProcessHandle 的跨平台进程树终止器。
 *
 * <p>by AI.Coding</p>
 */
public final class ProcessTreeTerminator {

    private static final Duration DEFAULT_GRACE_PERIOD = Duration.ofMillis(250);

    private final Duration gracePeriod;

    /**
     * 使用默认宽限期创建终止器。
     */
    public ProcessTreeTerminator() {
        this(DEFAULT_GRACE_PERIOD);
    }

    /**
     * 使用指定宽限期创建终止器。
     *
     * @param gracePeriod 普通终止后的等待时间
     */
    public ProcessTreeTerminator(Duration gracePeriod) {
        if (gracePeriod == null || gracePeriod.isNegative()) {
            throw new IllegalArgumentException("gracePeriod must not be null or negative");
        }
        this.gracePeriod = gracePeriod;
    }

    /**
     * 终止进程及全部后代。
     *
     * @param process 根进程
     * @return 终止结果
     */
    public ProcessTerminationResult terminate(Process process) {
        if (process == null) {
            return new ProcessTerminationResult(-1, 0, 0, true);
        }
        return terminate(process.toHandle(), process);
    }

    /**
     * 终止进程句柄及全部后代，先普通终止，宽限期后再强制清理。
     *
     * @param root 根进程句柄
     * @return 终止结果
     */
    public ProcessTerminationResult terminate(ProcessHandle root) {
        if (root == null) {
            return new ProcessTerminationResult(-1, 0, 0, true);
        }
        return terminate(root, null);
    }

    /**
     * 执行进程树终止；根进程对象存在时优先使用其直接销毁能力，兼容受限运行环境。
     *
     * @param root 根进程句柄
     * @param rootProcess 可选根进程对象
     * @return 终止结果
     */
    private ProcessTerminationResult terminate(ProcessHandle root, Process rootProcess) {
        List<ProcessHandle> descendants = descendants(root);
        requestTermination(descendants, false);
        terminateRoot(root, rootProcess, false);
        awaitTermination(withRoot(descendants, root));

        List<ProcessHandle> remaining = remainingHandles(root, descendants);
        int forced = requestTermination(
                remaining.stream().filter(handle -> handle.pid() != root.pid()).toList(),
                true);
        forced += terminateRoot(root, rootProcess, true);
        awaitTermination(withRoot(remaining, root));
        boolean terminated = withRoot(remaining, root).stream().noneMatch(ProcessHandle::isAlive);
        return new ProcessTerminationResult(root.pid(), descendants.size(), forced, terminated);
    }

    /**
     * 普通或强制终止根进程，优先使用调用方持有的 Process 对象。
     *
     * @param root 根进程句柄
     * @param rootProcess 可选根进程对象
     * @param forcibly 是否强制终止
     * @return 发出强制终止请求时返回 1，否则返回 0
     */
    private int terminateRoot(ProcessHandle root, Process rootProcess, boolean forcibly) {
        if (!root.isAlive()) {
            return 0;
        }
        try {
            if (rootProcess != null) {
                if (forcibly) {
                    rootProcess.destroyForcibly();
                } else {
                    rootProcess.destroy();
                }
                return forcibly ? 1 : 0;
            }
            return requestTermination(List.of(root), forcibly);
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    /**
     * 获取后代进程并按层级从深到浅排序，避免先杀父进程导致子进程脱离树关系。
     *
     * @param root 根进程
     * @return 排序后的后代进程
     */
    private List<ProcessHandle> descendants(ProcessHandle root) {
        try {
            return root.descendants()
                    .sorted(Comparator.comparingInt(this::depth).reversed())
                    .toList();
        } catch (RuntimeException ex) {
            // macOS 沙箱可能禁止 ProcessHandle 内部 sysctl，回退到固定系统进程快照命令。
            return platformDescendants(root.pid());
        }
    }

    /**
     * 使用固定平台命令读取 PID/PPID，并从快照中计算根进程的全部后代。
     *
     * @param rootPid 根进程 PID
     * @return 从深到浅排序的后代句柄
     */
    private List<ProcessHandle> platformDescendants(long rootPid) {
        Map<Long, List<Long>> children = processRelationships();
        ArrayDeque<Long> pending = new ArrayDeque<>(children.getOrDefault(rootPid, List.of()));
        Set<Long> descendantPids = new LinkedHashSet<>();
        while (!pending.isEmpty()) {
            long pid = pending.removeFirst();
            if (descendantPids.add(pid)) {
                pending.addAll(children.getOrDefault(pid, List.of()));
            }
        }
        List<Long> orderedPids = new ArrayList<>(descendantPids);
        Collections.reverse(orderedPids);
        return orderedPids.stream()
                .map(ProcessHandle::of)
                .flatMap(java.util.Optional::stream)
                .toList();
    }

    /**
     * 执行不包含用户输入的系统进程快照命令并解析父子关系。
     *
     * @return 父 PID 到子 PID 列表
     */
    private Map<Long, List<Long>> processRelationships() {
        Map<Long, List<Long>> children = new HashMap<>();
        try {
            Process process = new ProcessBuilder(snapshotCommand()).redirectErrorStream(true).start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    addRelationship(children, line);
                }
            }
            process.waitFor();
        } catch (IOException ex) {
            return Map.of();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return Map.of();
        }
        return children;
    }

    /**
     * 返回当前平台的固定 PID/PPID 快照命令。
     *
     * @return 命令参数数组
     */
    private List<String> snapshotCommand() {
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            return List.of(
                    "powershell.exe",
                    "-NoProfile",
                    "-NonInteractive",
                    "-Command",
                    "Get-CimInstance Win32_Process | ForEach-Object { \"$($_.ProcessId) $($_.ParentProcessId)\" }");
        }
        return List.of("ps", "-axo", "pid=,ppid=");
    }

    /**
     * 解析单行 PID/PPID 输出并加入关系表。
     *
     * @param children 父子关系表
     * @param line 命令输出行
     */
    private void addRelationship(Map<Long, List<Long>> children, String line) {
        String[] values = line == null ? new String[0] : line.trim().split("\\s+");
        if (values.length != 2) {
            return;
        }
        try {
            long pid = Long.parseLong(values[0]);
            long parentPid = Long.parseLong(values[1]);
            children.computeIfAbsent(parentPid, ignored -> new ArrayList<>()).add(pid);
        } catch (NumberFormatException ignored) {
            // PowerShell 或 ps 的非数据行不影响其他进程关系。
        }
    }

    /**
     * 请求普通或强制终止一组进程。
     *
     * @param handles 进程句柄
     * @param forcibly 是否强制终止
     * @return 发出强制终止请求的进程数
     */
    private int requestTermination(List<ProcessHandle> handles, boolean forcibly) {
        int requested = 0;
        for (ProcessHandle handle : handles) {
            if (!handle.isAlive()) {
                continue;
            }
            try {
                boolean accepted = forcibly ? handle.destroyForcibly() : handle.destroy();
                if (forcibly && accepted) {
                    requested++;
                }
            } catch (RuntimeException ignored) {
                // 安全策略拒绝时继续处理其他后代，最终结果会明确标记未完全终止。
            }
        }
        return requested;
    }

    /**
     * 在宽限期内轮询进程状态，避免为每个后代创建额外等待线程。
     *
     * @param handles 待观察进程
     */
    private void awaitTermination(List<ProcessHandle> handles) {
        long deadline = System.nanoTime() + gracePeriod.toNanos();
        while (handles.stream().anyMatch(ProcessHandle::isAlive) && System.nanoTime() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * 合并初始后代和宽限期内新发现的后代，按 PID 去重。
     *
     * @param root 根进程
     * @param initial 初始后代
     * @return 仍需强制处理的进程
     */
    private List<ProcessHandle> remainingHandles(ProcessHandle root, List<ProcessHandle> initial) {
        Map<Long, ProcessHandle> handles = new LinkedHashMap<>();
        initial.stream().filter(ProcessHandle::isAlive).forEach(handle -> handles.put(handle.pid(), handle));
        descendants(root).stream().filter(ProcessHandle::isAlive).forEach(handle -> handles.put(handle.pid(), handle));
        if (root.isAlive()) {
            handles.put(root.pid(), root);
        }
        return new ArrayList<>(handles.values());
    }

    /**
     * 将根进程加入观察集合并按 PID 去重。
     *
     * @param descendants 后代进程
     * @param root 根进程
     * @return 完整观察集合
     */
    private List<ProcessHandle> withRoot(List<ProcessHandle> descendants, ProcessHandle root) {
        Map<Long, ProcessHandle> handles = new LinkedHashMap<>();
        descendants.forEach(handle -> handles.put(handle.pid(), handle));
        handles.put(root.pid(), root);
        return new ArrayList<>(handles.values());
    }

    /**
     * 计算进程在系统进程树中的深度。
     *
     * @param handle 进程句柄
     * @return 进程深度
     */
    private int depth(ProcessHandle handle) {
        int depth = 0;
        ProcessHandle current = handle;
        try {
            while (current.parent().isPresent() && depth < 1_024) {
                current = current.parent().orElseThrow();
                depth++;
            }
        } catch (RuntimeException ignored) {
            return depth;
        }
        return depth;
    }
}
