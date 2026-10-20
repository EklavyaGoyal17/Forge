package dev.forge.server.challenge;

import dev.forge.lab.scenario.ScenarioCatalog;
import dev.forge.server.analysis.ComparisonService;
import dev.forge.server.analysis.RegressionService;
import dev.forge.server.config.Json;
import dev.forge.server.experiment.Experiment;
import dev.forge.server.experiment.ExperimentRepository;
import dev.forge.server.run.RunRecord;
import dev.forge.server.run.RunRepository;
import dev.forge.server.run.RunStatus;
import dev.forge.server.web.ApiException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Challenge attempts: starting one creates an experiment; its criteria are evaluated against the
 * experiment's successful runs whenever the attempt is read. Hints are revealed one at a time and
 * counted. Review answers are stored, never graded.
 */
@Service
public class ChallengeService {

    static final int MAX_ANSWER = 20_000;

    private final JdbcClient jdbc;
    private final Json json;
    private final ExperimentRepository experiments;
    private final RunRepository runs;
    private final ComparisonService comparisons;
    private final RegressionService regressions;
    private final RowMapper<Attempt> mapper;

    public ChallengeService(JdbcClient jdbc, Json json, ExperimentRepository experiments, RunRepository runs,
                            ComparisonService comparisons, RegressionService regressions) {
        this.jdbc = jdbc;
        this.json = json;
        this.experiments = experiments;
        this.runs = runs;
        this.comparisons = comparisons;
        this.regressions = regressions;
        this.mapper = (rs, i) -> new Attempt(rs.getObject("id", UUID.class), rs.getString("challenge_id"),
                rs.getObject("experiment_id", UUID.class), rs.getInt("hints_revealed"), json.read(rs.getString("answers")),
                rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant());
    }

    public record Attempt(UUID id, String challengeId, UUID experimentId, int hintsRevealed, JsonNode answers,
                          Instant startedAt, Instant completedAt) {
    }

    public record CriterionView(String id, String description, Criterion.Status status, String evidence, List<Integer> runs) {
    }

    /** A challenge as shown before and during an attempt. Hint texts are only included once revealed. */
    public record ChallengeView(String id, int number, String title, String scenarioId, String lab, String problem, String goal,
                                String question, Challenges.Start start, int hintCount, List<String> reviewQuestions,
                                List<String> criteria) {
    }

    public record AttemptView(ChallengeView challenge, Attempt attempt, String experimentTitle, int successfulRuns,
                              List<CriterionView> criteria, int passed, boolean complete, List<String> hints) {
    }

    public record Summary(ChallengeView challenge, AttemptView latest, int attempts, boolean everCompleted) {
    }

    static ChallengeView view(Challenges.Definition d) {
        String lab = ScenarioCatalog.find(d.scenarioId()).map(s -> s.descriptor().lab().name()).orElse(null);
        return new ChallengeView(d.id(), d.number(), d.title(), d.scenarioId(), lab, d.problem(), d.goal(), d.question(), d.start(),
                d.hints().size(), d.reviewQuestions(), d.criteria().stream().map(Criterion::description).toList());
    }

    public List<Summary> list() {
        List<Summary> out = new ArrayList<>();
        for (Challenges.Definition d : Challenges.all()) {
            List<Attempt> attempts = attempts(d.id());
            AttemptView latest = attempts.isEmpty() ? null : evaluate(attempts.getFirst());
            boolean ever = attempts.stream().anyMatch(a -> a.completedAt() != null) || latest != null && latest.complete();
            out.add(new Summary(view(d), latest, attempts.size(), ever));
        }
        return out;
    }

    public Map<String, Object> get(String challengeId) {
        Challenges.Definition d = definition(challengeId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("challenge", view(d));
        m.put("attempts", attempts(d.id()).stream().map(this::evaluate).toList());
        return m;
    }

    public AttemptView start(String challengeId, String hypothesis) {
        Challenges.Definition d = definition(challengeId);
        if (hypothesis == null || hypothesis.isBlank()) {
            throw ApiException.badRequest("write your hypothesis before starting: what do you think is wrong, and what will fix it?");
        }
        if (hypothesis.length() > MAX_ANSWER) {
            throw ApiException.badRequest("hypothesis must be at most " + MAX_ANSWER + " characters");
        }
        Experiment e = experiments.insert(UUID.randomUUID(), "Challenge " + d.number() + ": " + d.title(), d.question(), hypothesis.strip(),
                d.scenarioId(), List.of("challenge", d.id()));
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into challenge_attempt (id, challenge_id, experiment_id) values (:id, :c, :e)")
                .param("id", id).param("c", d.id()).param("e", e.id()).update();
        return evaluate(find(id));
    }

    public AttemptView attempt(UUID attemptId) {
        return evaluate(find(attemptId));
    }

    public AttemptView revealHint(UUID attemptId) {
        Attempt a = find(attemptId);
        Challenges.Definition d = definition(a.challengeId());
        if (a.hintsRevealed() < d.hints().size()) {
            jdbc.sql("update challenge_attempt set hints_revealed = hints_revealed + 1 where id = :id").param("id", attemptId).update();
        }
        return evaluate(find(attemptId));
    }

    public AttemptView saveAnswers(UUID attemptId, Map<String, Object> answers) {
        Attempt a = find(attemptId);
        Challenges.Definition d = definition(a.challengeId());
        Map<String, String> clean = new LinkedHashMap<>();
        for (var e : (answers == null ? Map.<String, Object>of() : answers).entrySet()) {
            int index;
            try {
                index = Integer.parseInt(e.getKey());
            } catch (NumberFormatException ex) {
                throw ApiException.badRequest("answers are keyed by question number, got '" + e.getKey() + "'");
            }
            if (index < 0 || index >= d.reviewQuestions().size()) {
                throw ApiException.badRequest("no review question " + index);
            }
            if (!(e.getValue() instanceof String s) || s.length() > MAX_ANSWER) {
                throw ApiException.badRequest("each answer must be a string of at most " + MAX_ANSWER + " characters");
            }
            clean.put(Integer.toString(index), s);
        }
        jdbc.sql("update challenge_attempt set answers = cast(:a as jsonb) where id = :id").param("a", json.write(clean)).param("id", attemptId).update();
        return evaluate(find(attemptId));
    }

    AttemptView evaluate(Attempt a) {
        Challenges.Definition d = definition(a.challengeId());
        Experiment e = experiments.find(a.experimentId()).orElseThrow(() -> ApiException.notFound("experiment"));
        List<RunRecord> ok = runs.forExperiment(e.id()).stream()
                .filter(r -> r.status() == RunStatus.SUCCEEDED && r.result() != null)
                .sorted(java.util.Comparator.comparingInt(RunRecord::seq)).toList();
        Criterion.Context ctx = new Criterion.Context(ok, comparisons, regressions.check(e));
        List<CriterionView> results = new ArrayList<>();
        for (Criterion c : d.criteria()) {
            Criterion.Result r;
            try {
                r = c.evaluate(ctx);
            } catch (RuntimeException ex) {
                r = Criterion.Result.notYet("Could not evaluate: " + ex.getMessage());
            }
            results.add(new CriterionView(c.id(), c.description(), r.status(), r.evidence(), r.runs()));
        }
        int passed = (int) results.stream().filter(r -> r.status() == Criterion.Status.PASS).count();
        boolean complete = passed == results.size();
        Attempt current = a;
        if (complete && a.completedAt() == null) {
            jdbc.sql("update challenge_attempt set completed_at = now() where id = :id and completed_at is null").param("id", a.id()).update();
            current = find(a.id());
        }
        return new AttemptView(view(d), current, e.title(), ok.size(), results, passed, complete,
                d.hints().subList(0, Math.min(current.hintsRevealed(), d.hints().size())));
    }

    private List<Attempt> attempts(String challengeId) {
        return jdbc.sql("select * from challenge_attempt where challenge_id = :c order by started_at desc").param("c", challengeId).query(mapper).list();
    }

    private Attempt find(UUID id) {
        return jdbc.sql("select * from challenge_attempt where id = :id").param("id", id).query(mapper).optional()
                .orElseThrow(() -> ApiException.notFound("challenge attempt"));
    }

    private static Challenges.Definition definition(String id) {
        return Challenges.find(id).orElseThrow(() -> ApiException.notFound("challenge"));
    }
}
