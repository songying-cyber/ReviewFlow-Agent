package com.paicli.cli;

import com.paicli.mcp.mention.AtMentionCompleter;
import com.paicli.mcp.resources.McpResourceDescriptor;
import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;

import java.util.List;
import java.util.function.Supplier;

final class PaiCliCompleter implements Completer {
    private final Supplier<List<McpResourceDescriptor>> resourceSupplier;

    PaiCliCompleter(Supplier<List<McpResourceDescriptor>> resourceSupplier) {
        this.resourceSupplier = resourceSupplier;
    }

    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        if (line == null || candidates == null) {
            return;
        }
        String input = line.line() == null ? "" : line.line();
        if (input.startsWith("/")) {
            completeSlashCommand(line, candidates);
            return;
        }
        new AtMentionCompleter(resourceSupplier).complete(reader, line, candidates);
    }

    private void completeSlashCommand(ParsedLine line, List<Candidate> candidates) {
        String input = line.line() == null ? "" : line.line();
        int cursor = Math.max(0, Math.min(line.cursor(), input.length()));
        String prefix = input.substring(0, cursor);
        String word = line.word() == null ? "" : line.word();
        int replacementStart = Math.max(0, prefix.length() - word.length());

        for (Main.SlashCommandHint hint : Main.slashCommandHints()) {
            String command = hint.insertText();
            if (!command.startsWith(prefix)) {
                continue;
            }
            String value = command.substring(Math.min(replacementStart, command.length()));
            candidates.add(new Candidate(
                    value,
                    hint.display(),
                    "PaiCLI 命令",
                    hint.description(),
                    null,
                    null,
                    true
            ));
        }
    }
}
