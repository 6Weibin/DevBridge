package com.devbridge.server.service;

import com.devbridge.server.model.RemoteFileNode;
import com.devbridge.server.model.RemoteFileType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Android 远端目录输出解析器，隔离 adb ls 文本格式。
 *
 * <p>by AI.Coding</p>
 */
@Component
public class RemoteFileParser {

    private static final Pattern LS_LINE = Pattern.compile(
            "^([bcdlps-][rwxStTs-]{9})\\s+\\S+\\s+(\\S+)\\s+(\\S+)\\s+(\\d+)\\s+"
                    + "(\\d{4}-\\d{2}-\\d{2})\\s+(\\d{2}:\\d{2})\\s+(.+)$");

    /**
     * 解析 Android `ls -la` 输出。
     *
     * @param lines 命令输出行
     * @param parentPath 父目录路径
     * @return 文件节点列表
     */
    public List<RemoteFileNode> parseAndroidLs(List<String> lines, String parentPath) {
        List<RemoteFileNode> nodes = new ArrayList<>();
        for (String line : lines) {
            parseLine(line, parentPath, nodes);
        }
        nodes.sort(fileNodeComparator());
        return nodes;
    }

    /**
     * 构造文件树排序规则：目录优先，同类型按名称升序；大小写差异只作为最后兜底。
     *
     * @return 文件节点排序器
     */
    private Comparator<RemoteFileNode> fileNodeComparator() {
        return Comparator
                .comparingInt((RemoteFileNode node) -> node.type() == RemoteFileType.DIRECTORY ? 0 : 1)
                .thenComparing(node -> node.name().toLowerCase(Locale.ROOT))
                .thenComparing(RemoteFileNode::name);
    }

    /**
     * 解析单行输出；不匹配的摘要行会被忽略，避免 `total` 行污染文件树。
     *
     * @param line 输出行
     * @param parentPath 父目录路径
     * @param nodes 解析结果
     */
    private void parseLine(String line, String parentPath, List<RemoteFileNode> nodes) {
        Matcher matcher = LS_LINE.matcher(line.trim());
        if (!matcher.matches()) {
            return;
        }
        String name = cleanName(matcher.group(7));
        if (name.equals(".") || name.equals("..")) {
            return;
        }
        String permissions = matcher.group(1);
        // Android 根目录下常见 `sdcard -> /storage/self/primary` 符号链接，按目录展示才能继续进入。
        RemoteFileType type = permissions.charAt(0) == 'd' || permissions.charAt(0) == 'l'
                ? RemoteFileType.DIRECTORY
                : RemoteFileType.FILE;
        nodes.add(new RemoteFileNode(
                name,
                join(parentPath, name),
                type,
                Long.parseLong(matcher.group(4)),
                matcher.group(5) + " " + matcher.group(6),
                permissions,
                matcher.group(2),
                matcher.group(3)));
    }

    /**
     * 清理符号链接箭头后的名称，仅保留前端可点击的路径名。
     *
     * @param rawName 原始名称
     * @return 清理后的名称
     */
    private String cleanName(String rawName) {
        int linkIndex = rawName.indexOf(" -> ");
        String name = linkIndex >= 0 ? rawName.substring(0, linkIndex) : rawName;
        // 部分 Android `ls /sdcard` 会输出绝对路径形式的符号链接名，转成节点名避免拼出 `//sdcard`。
        int pathIndex = name.lastIndexOf('/');
        return pathIndex >= 0 ? name.substring(pathIndex + 1) : name;
    }

    /**
     * 拼接父目录和文件名。
     *
     * @param parentPath 父目录
     * @param name 文件名
     * @return 完整路径
     */
    private String join(String parentPath, String name) {
        return parentPath.endsWith("/") ? parentPath + name : parentPath + "/" + name;
    }
}
