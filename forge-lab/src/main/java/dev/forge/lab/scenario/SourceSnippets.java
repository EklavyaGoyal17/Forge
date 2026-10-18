package dev.forge.lab.scenario;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Extracts the code shown in the UI directly from the packaged scenario sources, so that what a
 * user reads is byte-for-byte the code that executed.
 *
 * <p>A snippet is delimited in a scenario source file by
 * {@code // forge:snippet <id>} and {@code // forge:end}. Source files are packaged under
 * {@code forge-src/} by the build (see forge-lab/pom.xml).
 */
public final class SourceSnippets {

    private SourceSnippets() {
    }

    /** @param ref {@code "<fully qualified class name>#<snippet id>"} */
    public static Optional<String> resolve(String ref) {
        if (ref == null || !ref.contains("#")) {
            return Optional.empty();
        }
        String className = ref.substring(0, ref.indexOf('#'));
        String snippetId = ref.substring(ref.indexOf('#') + 1);
        String resource = "forge-src/" + className.replace('.', '/') + ".java";
        try (InputStream in = SourceSnippets.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                return Optional.empty();
            }
            return extract(new String(in.readAllBytes(), StandardCharsets.UTF_8), snippetId);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    static Optional<String> extract(String source, String snippetId) {
        List<String> lines = source.lines().toList();
        List<String> out = new ArrayList<>();
        boolean inside = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if (!inside && trimmed.equals("// forge:snippet " + snippetId)) {
                inside = true;
                continue;
            }
            if (inside && trimmed.equals("// forge:end")) {
                return Optional.of(dedent(out));
            }
            if (inside) {
                out.add(line);
            }
        }
        return Optional.empty();
    }

    private static String dedent(List<String> lines) {
        int indent = Integer.MAX_VALUE;
        for (String l : lines) {
            if (!l.isBlank()) {
                int i = 0;
                while (i < l.length() && l.charAt(i) == ' ') {
                    i++;
                }
                indent = Math.min(indent, i);
            }
        }
        int cut = indent == Integer.MAX_VALUE ? 0 : indent;
        StringBuilder sb = new StringBuilder();
        for (String l : lines) {
            sb.append(l.isBlank() ? "" : l.substring(cut)).append('\n');
        }
        return sb.toString().stripTrailing() + "\n";
    }
}
