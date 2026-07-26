package com.paicli.github;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitHubDiffLineMapTest {

    @Test
    void mapsNewOldAndContextLinesFromPatch() {
        GitHubDiffLineMap lineMap = GitHubDiffLineMap.fromChangedFiles(List.of(file(
                "src/App.java",
                null,
                """
                        @@ -10,4 +10,5 @@
                         context
                        -old line
                        +new line
                         another context
                        +added line
                        """)));

        GitHubDiffPosition added = lineMap.resolve("src/App.java", 13, "RIGHT").orElseThrow();
        GitHubDiffPosition deleted = lineMap.resolve("src/App.java", 11, "LEFT").orElseThrow();
        GitHubDiffPosition context = lineMap.resolve("src/App.java", 12, "RIGHT").orElseThrow();

        assertEquals("added", added.type());
        assertEquals("deleted", deleted.type());
        assertEquals("context", context.type());
        assertFalse(lineMap.resolve("src/App.java", 13, "LEFT").isPresent());
    }

    @Test
    void resolvesRenamedFileAliasToCurrentPath() {
        GitHubDiffLineMap lineMap = GitHubDiffLineMap.fromChangedFiles(List.of(file(
                "src/NewName.java",
                "src/OldName.java",
                """
                        @@ -1,1 +1,1 @@
                        -old
                        +new
                        """)));

        GitHubDiffPosition position = lineMap.resolve("src/OldName.java", 1, "RIGHT").orElseThrow();

        assertEquals("src/NewName.java", position.path());
        assertEquals(1, position.line());
        assertTrue(lineMap.hasFile("src/OldName.java"));
    }

    private static GitHubChangedFile file(String path, String previousPath, String patch) {
        return new GitHubChangedFile(path, "modified", 1, 1, 2, patch, previousPath, "");
    }
}
