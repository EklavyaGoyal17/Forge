package dev.forge.server.experiment;

import dev.forge.server.config.Json;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ExperimentRepository {

    private final JdbcClient jdbc;
    private final Json json;
    private final RowMapper<Experiment> mapper;

    public ExperimentRepository(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
        this.mapper = (rs, i) -> new Experiment(
                rs.getObject("id", UUID.class),
                rs.getString("title"),
                rs.getString("question"),
                rs.getString("hypothesis"),
                instant(rs.getTimestamp("hypothesis_updated_at")),
                rs.getString("scenario_id"),
                rs.getString("status"),
                rs.getString("observations"),
                rs.getString("alternative_explanations"),
                rs.getString("decision"),
                rs.getString("follow_ups"),
                json.read(rs.getString("review_answers")),
                json.read(rs.getString("tags")),
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at")));
    }

    /** List row with run statistics, used by the notebook and dashboard. */
    public record Summary(Experiment experiment, int runCount, int succeededRuns, Instant lastRunAt) {
    }

    public Experiment insert(UUID id, String title, String question, String hypothesis, String scenarioId, List<String> tags) {
        jdbc.sql("""
                insert into experiment (id, title, question, hypothesis, scenario_id, tags)
                values (:id, :title, :question, :hypothesis, :scenario, cast(:tags as jsonb))
                """)
                .param("id", id)
                .param("title", title)
                .param("question", question)
                .param("hypothesis", hypothesis)
                .param("scenario", scenarioId)
                .param("tags", json.write(tags))
                .update();
        return find(id).orElseThrow();
    }

    public Optional<Experiment> find(UUID id) {
        return jdbc.sql("select * from experiment where id = :id").param("id", id).query(mapper).optional();
    }

    public List<Summary> search(String text, String lab, int limit) {
        String pattern = text == null || text.isBlank() ? null : "%" + escapeLike(text.trim()) + "%";
        return jdbc.sql("""
                select e.*,
                       (select count(*) from run r where r.experiment_id = e.id) as run_count,
                       (select count(*) from run r where r.experiment_id = e.id and r.status = 'SUCCEEDED') as ok_count,
                       (select max(r.queued_at) from run r where r.experiment_id = e.id) as last_run_at
                from experiment e
                where (cast(:pattern as text) is null
                       or e.title ilike :pattern escape '\\'
                       or e.question ilike :pattern escape '\\'
                       or e.hypothesis ilike :pattern escape '\\'
                       or e.scenario_id ilike :pattern escape '\\'
                       or e.tags::text ilike :pattern escape '\\')
                  and (cast(:lab as text) is null or e.scenario_id like :labPrefix)
                order by e.updated_at desc
                limit :limit
                """)
                .param("pattern", pattern)
                .param("lab", lab)
                .param("labPrefix", lab == null ? null : escapeLike(lab) + ".%")
                .param("limit", limit)
                .query((rs, i) -> new Summary(mapper.mapRow(rs, i), rs.getInt("run_count"), rs.getInt("ok_count"),
                        instant(rs.getTimestamp("last_run_at"))))
                .list();
    }

    /** Applies a partial update; only keys present in {@code fields} change. */
    public void update(UUID id, Map<String, Object> fields) {
        if (fields.isEmpty()) {
            return;
        }
        StringBuilder sql = new StringBuilder("update experiment set updated_at = now()");
        JdbcClient.StatementSpec[] spec = new JdbcClient.StatementSpec[1];
        for (String column : fields.keySet()) {
            if (!UPDATABLE.contains(column)) {
                throw new IllegalArgumentException("column not updatable: " + column);
            }
            boolean jsonb = column.equals("review_answers") || column.equals("tags");
            sql.append(", ").append(column).append(" = ")
                    .append(jsonb ? "cast(:" + column + " as jsonb)" : ":" + column);
            if (column.equals("hypothesis")) {
                sql.append(", hypothesis_updated_at = now()");
            }
        }
        sql.append(" where id = :id");
        spec[0] = jdbc.sql(sql.toString()).param("id", id);
        fields.forEach((k, v) -> spec[0] = spec[0].param(k, v instanceof String s ? s : json.write(v)));
        spec[0].update();
    }

    public boolean delete(UUID id) {
        return jdbc.sql("delete from experiment where id = :id").param("id", id).update() > 0;
    }

    private static final List<String> UPDATABLE = List.of("title", "question", "hypothesis", "status", "observations",
            "alternative_explanations", "decision", "follow_ups", "review_answers", "tags");

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
