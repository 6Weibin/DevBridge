package com.devbridge.server.service;

import com.devbridge.server.command.CommandResult;
import com.devbridge.server.command.CommandRunner;
import com.devbridge.server.model.ToolStatus;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 工具状态服务，负责探测 adb、hdc、libimobiledevice 相关工具。
 *
 * <p>by AI.Coding</p>
 */
@Service
public class ToolStatusService {

    private final ExecutableLocator executableLocator;
    private final CommandRunner commandRunner;

    /**
     * 注入路径定位器和命令执行器。
     *
     * @param executableLocator 工具路径定位器
     * @param commandRunner 命令执行器
     */
    public ToolStatusService(ExecutableLocator executableLocator, CommandRunner commandRunner) {
        this.executableLocator = executableLocator;
        this.commandRunner = commandRunner;
    }

    /**
     * 查询全部工具状态。
     *
     * @return 工具状态列表
     */
    public List<ToolStatus> listToolStatus() {
        return ToolCatalog.all().stream().map(this::probe).toList();
    }

    /**
     * 查询指定工具是否可用。
     *
     * @param definition 工具定义
     * @return 工具状态
     */
    public ToolStatus probe(ToolDefinition definition) {
        String executable = executableLocator.locate(definition);
        if (executable.isBlank()) {
            return new ToolStatus(definition.name(), definition.commands().get(0), false, "", "", "tool not found");
        }
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.addAll(definition.versionArgs());
        CommandResult result = commandRunner.run(command);
        return new ToolStatus(
                definition.name(),
                definition.commands().get(0),
                !result.timedOut() && result.exitCode() != 127,
                executable,
                result.firstOutputLine(),
                result.timedOut() ? "version command timed out" : "");
    }
}
