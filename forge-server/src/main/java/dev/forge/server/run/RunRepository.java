package dev.forge.server.run;

import dev.forge.server.config.Json;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class RunRepository {

    private final JdbcClient jdbc;
    private final Json json;
    private final RowMapper<RunRecord> mapper;

    public RunRepository(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
        this.mapper = (rs, i) -> new RunRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("experiment_id", UUID.class),
                rs.getInt("seq"),
                rs.getString("label"),
                rs.getString("role"),
                rs.getString("scenario_id"),
                rs.getInt("scenario_version"),
                rs.getString("variant"),
                json.read(rs.getString("params")),
                rs.getLong("seed"),
                rs.getInt("trials"),
                rs.getInt("warmup_trials"),
                rs.getInt("heap_mb"),
                rs.getInt("timeout_seconds"),
                RunStatus.valueOf(rs.getString("status")),
                rs.getString("status_reason"),
                (Integer) rs.getObject("exit_code"),
                (Long) rs.getObject("progress_done"),
                (Long) rs.getObject("progress_total"),
                rs.getString("progress_phase"),
                json.read(rs.getString("environment")),
                json.read(rs.getString("result")),
                rs.getString("forge_version"),
                rs.getBoolean("cancel_requested"),
                instant(rs.getTimestamp("queued_at")),
                instant(rs.getTimestamp("started_at")),
                instant(rs.getTimestamp("finished_at")));
    }

    public record NewRun(UUID id, UUID experimentId, String label, String role, String scenarioId, int scenarioVersion,
                         String variant, Object params, long seed, int trials, int warmupTrials, int heapMb,
                         int timeoutSeconds, String forgeVersion) {
    }

    public record Event(long id, Instant ts, String level, String message) {
    }

    public record ArtifactMeta(String name, String contentType, int sizeBytes, Instant createdAt) {
    }

    public record Artifact(String name, String contentType, String content) {
    }

    /** Inserts a QUEUED run with the next per-experiment sequence number. */
    @Transactional
    public RunRecord insert(NewRun r) {
        // serialise sequence allocation per experiment
        jdbc.sql("select id from experiment where id = :id for update").param("id", r.experimentId()).query(UUID.class).single();
        jdbc.sql("""
                insert into run (id, experiment_id, seq, label, role, scenario_id, scenario_version, variant, params,
                                 seed, trials, warmup_trials, heap_mb, timeout_seconds, status, forge_version)
                values (:id, :exp, (select coalesce(max(seq), 0) + 1 from run where experiment_id = :exp), :label, :role,
                        :scenario, :version, :variant, cast(:params as jsonb), :seed, :trials, :warmup, :heap, :timeout,
                        'QUEUED', :forgeVersion)
                """)
                .param("id", r.id())
                .param("exp", r.experimentId())
                .param("label", r.label())
                .param("role", r.role())
                .param("scenario", r.scenarioId())
                .param("version", r.scenarioVersion())
                .param("variant", r.variant())
                .param("params", json.write(r.params()))
                .param("seed", r.seed())
                .param("trials", r.trials())
                .param("warmup", r.warmupTrials())
                .param("heap", r.heapMb())
                .param("timeout", r.timeoutSeconds())
                .param("forgeVersion", r.forgeVersion())
                .update();
        jdbc.sql("update experiment set updated_at = now() where id = :id").param("id", r.experimentId()).update();
        return find(r.id()).orElseThrow();
    }

    public Optional<RunRecord> find(UUID id) {
        return jdbc.sql("select * from run where id = :id").param("id", id).query(mapper).optional();
    }

    public List<RunRecord> forExperiment(UUID experimentId) {
        return jdbc.sql("select * from run where experiment_id = :id order by seq").param("id", experimentId).query(mapper).list();
    }

    public List<RunRecord> recent(int limit) {
        return jdbc.sql("select * from run order by queued_at desc limit :limit").param("limit", limit).query(mapper).list();
    }

    public int countPending() {
        return jdbc.sql("select count(*) from run where status in ('QUEUED', 'RUNNING')").query(Integer.class).single();
    }

    public int countQueued() {
        return jdbc.sql("select count(*) from run where status = 'QUEUED'").query(Integer.class).single();
    }

    /**
     * Atomically claims the oldest queued run. {@code FOR UPDATE SKIP LOCKED} makes this safe with
     * several workers (or several server instances) without double execution.
     */
    public Optional<RunRecord> claimNext() {
        return jdbc.sql("""
                update run set status = 'RUNNING', started_at = now()
                where id = (select id from run where status = 'QUEUED' and not cancel_requested
                            order by queued_at, seq limit 1 for update skip locked)
                returning *
                """).query(mapper).optional();
    }

    /** Cancels a queued run directly; returns false if it was not queued. */
    public boolean cancelIfQueued(UUID id, String reason) {
        return jdbc.sql("""
                update run set status = 'CANCELLED', status_reason = :reason, cancel_requested = true, finished_at = now()
                where id = :id and status = 'QUEUED'
                """).param("id", id).param("reason", reason).update() > 0;
    }

    public void requestCancel(UUID id) {
        jdbc.sql("update run set cancel_requested = true where id = :id").param("id", id).update();
    }

    public void progress(UUID id, long done, long total, String phase) {
        jdbc.sql("update run set progress_done = :done, progress_total = :total, progress_phase = :phase where id = :id")
                .param("id", id).param("done", done).param("total", total).param("phase", phase).update();
    }

    public void finish(UUID id, RunStatus status, String reason, Integer exitCode, JsonNode environment, JsonNode result) {
        jdbc.sql("""
                update run set status = :status, status_reason = :reason, exit_code = :exit,
                               environment = cast(:env as jsonb), result = cast(:result as jsonb), finished_at = now()
                where id = :id
                """)
                .param("id", id)
                .param("status", status.name())
                .param("reason", reason)
                .param("exit", exitCode)
                .param("env", environment == null ? null : json.write(environment))
                .param("result", result == null ? null : json.write(result))
                .update();
    }

    /** Runs left RUNNING by a previous server process cannot be resumed: their runner is gone. */
    public int failOrphanedRunning(String reason) {
        return jdbc.sql("""
                update run set status = 'FAILED', status_reason = :reason, finished_at = now()
                where status = 'RUNNING'
                """).param("reason", reason).update();
    }

    public void addEvent(UUID runId, Instant ts, String level, String message) {
        jdbc.sql("insert into run_event (run_id, ts, level, message) values (:run, :ts, :level, :message)")
                .param("run", runId)
                .param("ts", Timestamp.from(ts))
                .param("level", level)
                .param("message", message.length() > 4_000 ? message.substring(0, 4_000) + "…" : message)
                .update();
    }

    public List<Event> events(UUID runId, long afterId) {
        return jdbc.sql("select id, ts, level, message from run_event where run_id = :run and id > :after order by id limit 1000")
                .param("run", runId).param("after", afterId)
                .query((rs, i) -> new Event(rs.getLong("id"), instant(rs.getTimestamp("ts")), rs.getString("level"),
                        rs.getString("message")))
                .list();
    }

    public int countArtifacts(UUID runId) {
        return jdbc.sql("select count(*) from run_artifact where run_id = :run").param("run", runId).query(Integer.class).single();
    }

    public void putArtifact(UUID runId, String name, String contentType, String content) {
        jdbc.sql("""
                insert into run_artifact (run_id, name, content_type, content, size_bytes)
                values (:run, :name, :type, :content, :size)
                on conflict (run_id, name) do update set content = excluded.content, size_bytes = excluded.size_bytes
                """)
                .param("run", runId)
                .param("name", name)
                .param("type", contentType)
                .param("content", content)
                .param("size", content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .update();
    }

    public List<ArtifactMeta> artifacts(UUID runId) {
        return jdbc.sql("select name, content_type, size_bytes, created_at from run_artifact where run_id = :run order by id")
                .param("run", runId)
                .query((rs, i) -> new ArtifactMeta(rs.getString("name"), rs.getString("content_type"),
                        rs.getInt("size_bytes"), instant(rs.getTimestamp("created_at"))))
                .list();
    }

    public Optional<Artifact> artifact(UUID runId, String name) {
        return jdbc.sql("select name, content_type, content from run_artifact where run_id = :run and name = :name")
                .param("run", runId).param("name", name)
                .query((rs, i) -> new Artifact(rs.getString("name"), rs.getString("content_type"), rs.getString("content")))
                .optional();
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
