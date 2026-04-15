package com.paicli.policy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 路径围栏：所有文件类工具调用必须先经过它。
 *
 * 定位：HITL 之前的 LLM 输入合法性检查，不是沙箱（不提供进程隔离）。
 *
 * 解决三类越界场景：
 * 1. 绝对路径直接逃出项目根（LLM 给出 /etc/passwd）
 * 2. 相对路径用 .. 穿越（../../etc/passwd）
 * 3. 符号链接逃逸（项目内的软链指向外部目录）
 *
 * 设计要点：
 * - 拒绝时抛 {@link PolicyException}，由调用方决定怎么呈现给用户和 LLM
 * - 不存在的路径也能校验（write_file 创建新文件场景）：向上找最近的存在祖先解析 realPath，再把剩余段接回
 * - 校验通过后返回的 Path 是已规范化的绝对路径，调用方直接拿来读写
 */
public class PathGuard {

    private final Path rootPath;

    public PathGuard(String root) {
        if (root == null || root.isBlank()) {
            throw new IllegalArgumentException("项目根路径不能为空");
        }
        Path candidate = Paths.get(root).toAbsolutePath().normalize();
        // macOS 上 /var/folders → /private/var/folders 这类符号链接，必须先把根本身展开成真实路径，
        // 否则后面 resolveRealPath 会把目标展开成 /private/... 但根仍是 /var/...，startsWith 永远 false。
        Path real = candidate;
        try {
            if (Files.exists(candidate)) {
                real = candidate.toRealPath();
            }
        } catch (IOException ignored) {
        }
        this.rootPath = real;
    }

    public Path getRootPath() {
        return rootPath;
    }

    /**
     * 校验路径是否在项目根之内，返回安全的绝对路径。
     */
    public Path resolveSafe(String input) {
        if (input == null || input.isBlank()) {
            throw new PolicyException("路径不能为空");
        }

        Path raw = Paths.get(input);
        Path resolved = raw.isAbsolute()
                ? raw.normalize()
                : rootPath.resolve(raw).normalize();

        Path realResolved = resolveRealPath(resolved);

        if (!realResolved.startsWith(rootPath)) {
            throw new PolicyException(
                    "路径越界: " + input + " 不在项目根 " + rootPath + " 之内");
        }
        return realResolved;
    }

    /**
     * 向上找到最近的存在祖先，调用 toRealPath 解析其中的符号链接，再把剩余段接回。
     *
     * 这样做的目的：write_file 给一个尚不存在的目标路径时，仍能识别出"路径中段是个软链且指向外部"的越界情况。
     */
    private Path resolveRealPath(Path target) {
        Path existing = target;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            return target.toAbsolutePath().normalize();
        }
        try {
            Path realExisting = existing.toRealPath();
            Path remainder = existing.relativize(target);
            return realExisting.resolve(remainder).normalize();
        } catch (IOException e) {
            return target.toAbsolutePath().normalize();
        }
    }
}
