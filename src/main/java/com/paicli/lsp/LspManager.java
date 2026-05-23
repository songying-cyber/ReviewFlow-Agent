package com.paicli.lsp;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.Problem;
import com.github.javaparser.Range;
import com.github.javaparser.ast.CompilationUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class LspManager {
    private static final Logger log = LoggerFactory.getLogger(LspManager.class);

    private final JavaParser javaParser = new JavaParser();
    private final Map<Path, List<LspDiagnostic>> pendingByFile = new LinkedHashMap<>();
    private Path projectRoot;

    public LspManager(String projectPath) {
        setProjectPath(projectPath);
    }

    public synchronized void setProjectPath(String projectPath) {
        String root = projectPath == null || projectPath.isBlank()
                ? System.getProperty("user.dir")
                : projectPath;
        this.projectRoot = Path.of(root).toAbsolutePath().normalize();
    }

    public void runPostEditLspHook(String displayPath, Path editedFile) {
        if (!enabled()) {
            return;
        }
        if (editedFile == null) {
            return;
        }
        Path file = editedFile.toAbsolutePath().normalize();
        if (!Files.isRegularFile(file)) {
            clearDiagnostics(file);
            return;
        }
        if (!isJavaFile(file)) {
            clearDiagnostics(file);
            return;
        }
        try {
            List<LspDiagnostic> diagnostics = diagnoseJava(displayPath, file);
            replaceDiagnostics(file, diagnostics);
            log.debug("LSP post-edit diagnostics for {}: {}", file, diagnostics.size());
        } catch (Exception e) {
            log.trace("LSP post-edit hook failed for {}", file, e);
        }
    }

    public synchronized LspDiagnosticReport flushPendingDiagnostics() {
        if (pendingByFile.isEmpty()) {
            return LspDiagnosticReport.EMPTY;
        }
        List<LspDiagnostic> diagnostics = pendingByFile.values().stream()
                .flatMap(List::stream)
                .sorted(Comparator
                        .comparing((LspDiagnostic d) -> severityRank(d.severity()))
                        .thenComparing(LspDiagnostic::filePath)
                        .thenComparingInt(LspDiagnostic::line)
                        .thenComparingInt(LspDiagnostic::column))
                .toList();
        pendingByFile.clear();
        return LspDiagnosticFormatter.format(diagnostics);
    }

    synchronized List<LspDiagnostic> pendingDiagnosticsSnapshot() {
        return pendingByFile.values().stream()
                .flatMap(List::stream)
                .toList();
    }

    private List<LspDiagnostic> diagnoseJava(String displayPath, Path file) throws IOException {
        ParseResult<CompilationUnit> result = javaParser.parse(file);
        List<LspDiagnostic> diagnostics = new ArrayList<>();
        for (Problem problem : result.getProblems()) {
            diagnostics.add(toDiagnostic(displayPath, problem));
        }
        return diagnostics;
    }

    private LspDiagnostic toDiagnostic(String displayPath, Problem problem) {
        Range range = problem.getLocation()
                .flatMap(tokenRange -> tokenRange.getBegin().getRange())
                .orElse(null);
        int line = range == null ? 1 : range.begin.line;
        int column = range == null ? 1 : range.begin.column;
        return new LspDiagnostic(
                LspSeverity.ERROR,
                normalizeDisplayPath(displayPath),
                line,
                column,
                cleanMessage(problem.getMessage()),
                "javaparser"
        );
    }

    private String cleanMessage(String message) {
        if (message == null || message.isBlank()) {
            return "Java parse error";
        }
        String normalized = message.replace("\r\n", "\n").replace('\r', '\n').trim();
        int newline = normalized.indexOf('\n');
        if (newline >= 0) {
            normalized = normalized.substring(0, newline).trim();
        }
        return normalized.isBlank() ? "Java parse error" : normalized;
    }

    private synchronized void replaceDiagnostics(Path file, List<LspDiagnostic> diagnostics) {
        if (diagnostics == null || diagnostics.isEmpty()) {
            pendingByFile.remove(file);
        } else {
            pendingByFile.put(file, List.copyOf(diagnostics));
        }
    }

    private synchronized void clearDiagnostics(Path file) {
        if (file != null) {
            pendingByFile.remove(file.toAbsolutePath().normalize());
        }
    }

    private boolean isJavaFile(Path file) {
        String name = file.getFileName() == null ? "" : file.getFileName().toString();
        return name.endsWith(".java");
    }

    private boolean enabled() {
        String raw = System.getProperty("paicli.lsp.enabled");
        if (raw == null || raw.isBlank()) {
            raw = System.getenv("PAICLI_LSP_ENABLED");
        }
        return raw == null || raw.isBlank() || Boolean.parseBoolean(raw.trim());
    }

    private String normalizeDisplayPath(String displayPath) {
        if (displayPath != null && !displayPath.isBlank()) {
            return displayPath.replace('\\', '/');
        }
        return projectRoot.toString().replace('\\', '/');
    }

    private static int severityRank(LspSeverity severity) {
        return switch (severity) {
            case ERROR -> 0;
            case WARNING -> 1;
            case INFO -> 2;
        };
    }
}
