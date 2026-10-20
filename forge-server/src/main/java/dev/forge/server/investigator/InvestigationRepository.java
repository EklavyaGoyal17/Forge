package dev.forge.server.investigator;

import dev.forge.server.config.Json;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class InvestigationRepository {

    public record Investigation(UUID id, UUID experimentId, String question, String provider, String model, String answer,
                                JsonNode citations, JsonNode check, JsonNode evidenceIds, Instant createdAt) {
    }

    private final JdbcClient jdbc;
    private final Json json;
    private final RowMapper<Investigation> mapper;

    public InvestigationRepository(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
        this.mapper = (rs, i) -> new Investigation(
                rs.getObject("id", UUID.class),
                rs.getObject("experiment_id", UUID.class),
                rs.getString("question"),
                rs.getString("provider"),
                rs.getString("model"),
                rs.getString("answer"),
                json.read(rs.getString("citations")),
                json.read(rs.getString("check_result")),
                json.read(rs.getString("evidence_ids")),
                rs.getTimestamp("created_at").toInstant());
    }

    public Investigation insert(UUID experimentId, String question, String provider, String model, String answer,
                                Object citations, Object check, Object evidenceIds) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into investigation (id, experiment_id, question, provider, model, answer, citations, check_result, evidence_ids)
                values (:id, :exp, :q, :provider, :model, :answer, cast(:citations as jsonb), cast(:check as jsonb), cast(:evidence as jsonb))
                """)
                .param("id", id)
                .param("exp", experimentId)
                .param("q", question)
                .param("provider", provider)
                .param("model", model)
                .param("answer", answer)
                .param("citations", json.write(citations))
                .param("check", json.write(check))
                .param("evidence", json.write(evidenceIds))
                .update();
        return jdbc.sql("select * from investigation where id = :id").param("id", id).query(mapper).single();
    }

    public List<Investigation> forExperiment(UUID experimentId) {
        return jdbc.sql("select * from investigation where experiment_id = :exp order by created_at desc limit 50")
                .param("exp", experimentId).query(mapper).list();
    }
}
