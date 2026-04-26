package com.paicli.mcp.mention;

import com.paicli.mcp.resources.McpResourceDescriptor;
import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;

import java.util.List;
import java.util.function.Supplier;

public class AtMentionCompleter implements Completer {
    private final Supplier<List<McpResourceDescriptor>> resourceSupplier;

    public AtMentionCompleter(Supplier<List<McpResourceDescriptor>> resourceSupplier) {
        this.resourceSupplier = resourceSupplier;
    }

    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        if (line == null || candidates == null) {
            return;
        }
        String word = line.word() == null ? "" : line.word();
        if (!word.startsWith("@")) {
            return;
        }
        String prefix = word.substring(1);
        for (McpResourceDescriptor resource : resourceSupplier.get()) {
            String value = "@" + resource.serverName() + ":" + resource.uri();
            if (!prefix.isBlank() && !value.substring(1).startsWith(prefix)) {
                continue;
            }
            String description = resource.description() == null || resource.description().isBlank()
                    ? resource.mimeType()
                    : resource.description();
            candidates.add(new Candidate(
                    value,
                    value,
                    resource.displayName(),
                    description,
                    null,
                    null,
                    true
            ));
        }
    }
}
