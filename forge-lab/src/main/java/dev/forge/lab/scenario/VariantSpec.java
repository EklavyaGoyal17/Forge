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
 */
public record VariantSpec(String id, String label, String description, Boolean expectedCorrect, String sourceRef,
                          List<String> modes) {

    public VariantSpec {
        modes = modes == null ? List.of() : List.copyOf(modes);
    }

    public VariantSpec(String id, String label, String description, Boolean expectedCorrect, String sourceRef) {
        this(id, label, description, expectedCorrect, sourceRef, List.of());
    }

    public boolean supportsMode(Object mode) {
        return modes.isEmpty() || mode == null || modes.contains(String.valueOf(mode));
    }

    public VariantSpec onlyFor(String... supportedModes) {
        return new VariantSpec(id, label, description, expectedCorrect, sourceRef, List.of(supportedModes));
    }
}
