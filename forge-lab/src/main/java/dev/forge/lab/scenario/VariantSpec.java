package dev.forge.lab.scenario;

import java.util.List;

/**
 * One implementation choice within a scenario.
 *
 * @param expectedCorrect {@code TRUE} if this variant is intended to satisfy the invariant,
 *                        {@code FALSE} if it is a deliberately broken reference implementation,
 *                        {@code null} if the scenario has no correctness invariant.
 * @param sourceRef       {@code "<class name>#<snippet id>"} of the code shown in the UI;
 *                        resolved by {@link SourceSnippets} from the packaged source file.
 * @param modes           values of the scenario's {@code mode} parameter this variant supports;
 *                        empty = all modes. (Explorer mode needs instrumented code, so variants
 *                        built on uninstrumented library internals support stress mode only.)
 * @param jvmFlags        extra flags the runner JVM is started with for this variant (e.g. a JIT
 *                        mode). Declared here in code, never taken from user input; the launcher
 *                        additionally accepts only flags on its own allow-list.
 */
public record VariantSpec(String id, String label, String description, Boolean expectedCorrect, String sourceRef,
                          List<String> modes, List<String> jvmFlags) {

    public VariantSpec {
        modes = modes == null ? List.of() : List.copyOf(modes);
        jvmFlags = jvmFlags == null ? List.of() : List.copyOf(jvmFlags);
    }

    public VariantSpec(String id, String label, String description, Boolean expectedCorrect, String sourceRef,
                       List<String> modes) {
        this(id, label, description, expectedCorrect, sourceRef, modes, List.of());
    }

    public VariantSpec(String id, String label, String description, Boolean expectedCorrect, String sourceRef) {
        this(id, label, description, expectedCorrect, sourceRef, List.of(), List.of());
    }

    public boolean supportsMode(Object mode) {
        return modes.isEmpty() || mode == null || modes.contains(String.valueOf(mode));
    }

    public VariantSpec onlyFor(String... supportedModes) {
        return new VariantSpec(id, label, description, expectedCorrect, sourceRef, List.of(supportedModes), jvmFlags);
    }

    public VariantSpec withJvmFlags(String... flags) {
        return new VariantSpec(id, label, description, expectedCorrect, sourceRef, modes, List.of(flags));
    }
}
