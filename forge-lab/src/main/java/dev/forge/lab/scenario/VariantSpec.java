package dev.forge.lab.scenario;

/**
 * One implementation choice within a scenario.
 *
 * @param expectedCorrect {@code TRUE} if this variant is intended to satisfy the invariant,
 *                        {@code FALSE} if it is a deliberately broken reference implementation,
 *                        {@code null} if the scenario has no correctness invariant.
 * @param sourceRef       {@code "<class simple name>#<snippet id>"} of the code shown in the UI;
 *                        resolved by {@link SourceSnippets} from the packaged source file.
 */
public record VariantSpec(String id, String label, String description, Boolean expectedCorrect, String sourceRef) {
}
