package dev.forge.server.investigator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks an answer against the evidence it was given: every cited id must exist, and every number
 * in the text should appear in the evidence. A number that does not is not necessarily wrong — it
 * may be derived (a ratio, a difference) — but the reader is told to verify it, because a model can
 * also invent one.
 */
final class AnswerChecker {

    private static final Pattern CITATION = Pattern.compile("\\[([A-Za-z][A-Za-z0-9_.\\-]*)\\]");
    private static final Pattern NUMBER = Pattern.compile("(?<![\\w.])-?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d+)?(?![\\w])");

    private AnswerChecker() {
    }

    /**
     * @param cited          ids cited in the answer, in order of first use
     * @param unknownCitations cited ids that are not in the evidence
     * @param unverifiedNumbers numbers in the answer that appear nowhere in the evidence (after rounding)
     * @param checkedNumbers how many numbers were checked
     */
    record Result(List<String> cited, List<String> unknownCitations, List<String> unverifiedNumbers, int checkedNumbers) {
    }

    static Result check(String answer, Evidence.Bundle bundle) {
        Set<String> ids = bundle.byId().keySet();
        Set<String> cited = new LinkedHashSet<>();
        Matcher c = CITATION.matcher(answer);
        while (c.find()) {
            cited.add(c.group(1));
        }
        List<String> unknown = cited.stream().filter(id -> !ids.contains(id)).toList();

        List<BigDecimal> evidence = numbers(bundle.render());
        evidence.addAll(numbers(String.join(" ", ids)));    // numbers that are part of ids, e.g. run #3
        String prose = CITATION.matcher(answer).replaceAll(" ");
        List<String> unverified = new ArrayList<>();
        int checked = 0;
        Matcher m = NUMBER.matcher(prose);
        Set<String> seen = new LinkedHashSet<>();
        while (m.find()) {
            String raw = m.group();
            if (!seen.add(raw)) {
                continue;
            }
            BigDecimal value = parse(raw);
            if (value == null || value.abs().compareTo(BigDecimal.TEN) <= 0 && value.scale() <= 0) {
                continue;   // small whole numbers ("3 trials", "2×") are too common to check meaningfully
            }
            checked++;
            if (!supported(value, evidence)) {
                unverified.add(raw);
            }
        }
        return new Result(List.copyOf(cited), unknown, List.copyOf(unverified), checked);
    }

    /** Equal to an evidence number, or to one rounded to the answer's precision, or within 0.5%. */
    static boolean supported(BigDecimal value, List<BigDecimal> evidence) {
        int scale = Math.max(0, value.scale());
        for (BigDecimal e : evidence) {
            if (e.compareTo(value) == 0 || e.setScale(scale, RoundingMode.HALF_UP).compareTo(value) == 0) {
                return true;
            }
            if (e.signum() != 0 && value.subtract(e).abs().divide(e.abs(), 6, RoundingMode.HALF_UP).doubleValue() <= 0.005) {
                return true;
            }
        }
        return false;
    }

    static List<BigDecimal> numbers(String text) {
        List<BigDecimal> out = new ArrayList<>();
        Matcher m = NUMBER.matcher(text);
        while (m.find()) {
            BigDecimal v = parse(m.group());
            if (v != null) {
                out.add(v);
            }
        }
        return out;
    }

    private static BigDecimal parse(String raw) {
        try {
            return new BigDecimal(raw.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
