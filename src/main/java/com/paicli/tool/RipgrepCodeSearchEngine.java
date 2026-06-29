package com.paicli.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

final class RipgrepCodeSearchEngine implements CodeSearchEngine {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(8);

    private final Set<String> excludedDirs;

    RipgrepCodeSearchEngine(Set<String> excludedDirs) {
        this.excludedDirs = excludedDirs;
    }

    @Override
    public CodeSearchResult search(CodeSearchRequest request) {
        if (Boolean.getBoolean("paicli.search.disable.rg") || !isRipgrepAvailable()) {
            return fallback(request);
        }

        Process process = null;
        ExecutorService readerExecutor = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(command(request));
            pb.directory(request.projectRoot().toFile());
            pb.redirectErrorStream(true);
            process = pb.start();

            readerExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "paicli-rg-reader");
                thread.setDaemon(true);
                return thread;
            });
            Process runningProcess = process;
            Future<ParsedRipgrepOutput> outputFuture = readerExecutor.submit(
                    () -> parseOutput(runningProcess.getInputStream(), runningProcess, request));
            boolean finished = process.waitFor(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                outputFuture.cancel(true);
                return new CodeSearchResult("rg", List.of(), true, "rg 搜索超时 " + TIMEOUT.toSeconds() + " 秒");
            }
            ParsedRipgrepOutput parsed = outputFuture.get(1, TimeUnit.SECONDS);
            readerExecutor.shutdownNow();
            boolean partial = parsed.partial();
            String partialReason = parsed.partialReason();
            if (!partial && parsed.matches().size() >= request.maxResults()) {
                partial = true;
                partialReason = "已达到 max_results=" + request.maxResults();
            }
            return new CodeSearchResult("rg", parsed.matches(), partial, partialReason);
        } catch (Exception e) {
            if (process != null) {
                process.destroyForcibly();
            }
            return fallback(request);
        } finally {
            if (readerExecutor != null) {
                readerExecutor.shutdownNow();
            }
        }
    }

    private ParsedRipgrepOutput parseOutput(InputStream inputStream, Process process, CodeSearchRequest request) throws IOException {
        List<GrepMatch> matches = new ArrayList<>();
        List<ContextLine> pendingBefore = new ArrayList<>();
        MutableMatch current = null;
        Map<String, Integer> perFileMatches = new HashMap<>();
        boolean partial = false;
        String partialReason = "";

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                JsonNode event = MAPPER.readTree(line);
                String type = event.path("type").asText();
                JsonNode data = event.path("data");
                if ("context".equals(type)) {
                    ContextLine context = toContextLine(data);
                    String contextFile = pathText(data);
                    if (current != null && current.file().equals(contextFile)) {
                        current.context().add(context);
                    } else {
                        pendingBefore.add(context);
                    }
                } else if ("match".equals(type)) {
                    if (current != null) {
                        matches.add(current.toGrepMatch());
                        if (matches.size() >= request.maxResults()) {
                            partial = true;
                            partialReason = "已达到 max_results=" + request.maxResults();
                            process.destroyForcibly();
                            break;
                        }
                    }
                    String file = pathText(data);
                    int currentFileMatches = perFileMatches.getOrDefault(file, 0);
                    if (currentFileMatches >= request.headLimit()) {
                        partial = true;
                        partialReason = "部分文件已达到 head_limit=" + request.headLimit();
                        pendingBefore.clear();
                        current = null;
                        continue;
                    }
                    int lineNumber = data.path("line_number").asInt();
                    current = new MutableMatch(file, lineNumber, new ArrayList<>());
                    current.context().addAll(pendingBefore);
                    current.context().add(toContextLine(data));
                    pendingBefore.clear();
                    perFileMatches.put(file, currentFileMatches + 1);
                }
            }
        }
        if (current != null && matches.size() < request.maxResults()) {
            matches.add(current.toGrepMatch());
        }
        return new ParsedRipgrepOutput(matches, partial, partialReason);
    }

    private List<String> command(CodeSearchRequest request) {
        List<String> cmd = new ArrayList<>();
        cmd.add("rg");
        cmd.add("--json");
        cmd.add("--color=never");
        cmd.add("--line-number");
        cmd.add("--max-filesize");
        cmd.add("2M");
        if (!request.caseSensitive()) {
            cmd.add("-i");
        }
        if (!request.regex()) {
            cmd.add("--fixed-strings");
        }
        if (request.contextLines() > 0) {
            cmd.add("-C");
            cmd.add(String.valueOf(request.contextLines()));
        }
        for (String dir : excludedDirs) {
            cmd.add("--glob");
            cmd.add("!" + dir + "/**");
        }
        if (request.glob() != null && !request.glob().isBlank()) {
            cmd.add("--glob");
            cmd.add(request.glob());
        }
        cmd.add("--");
        cmd.add(request.query());
        cmd.add(relativeRoot(request.root(), request.projectRoot()));
        return cmd;
    }

    private String relativeRoot(Path root, Path projectRoot) {
        if (root.equals(projectRoot)) {
            return ".";
        }
        return projectRoot.relativize(root).toString();
    }

    private ContextLine toContextLine(JsonNode data) {
        return new ContextLine(data.path("line_number").asInt(), data.path("lines").path("text").asText().stripTrailing());
    }

    private String pathText(JsonNode data) {
        return data.path("path").path("text").asText();
    }

    private boolean isRipgrepAvailable() {
        try {
            Process process = new ProcessBuilder("rg", "--version").start();
            boolean finished = process.waitFor(2, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    private CodeSearchResult fallback(CodeSearchRequest request) {
        return new JavaCodeSearchEngine(excludedDirs).search(request);
    }

    private record MutableMatch(String file, int lineNumber, List<ContextLine> context) {
        GrepMatch toGrepMatch() {
            return new GrepMatch(file, lineNumber, context);
        }
    }

    private record ParsedRipgrepOutput(List<GrepMatch> matches, boolean partial, String partialReason) {}
}
