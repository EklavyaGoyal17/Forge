package dev.forge.lab.scenario;

/**
 * Connection details of the disposable lab database, handed to Database Studio scenarios only.
 *
 * <p>The role is restricted by the server (no access to the platform database, statement and lock
 * timeouts, connection limit); the password is generated per server start and travels only over
 * the runner's stdin, never on a command line or in the stored environment.
 */
public record LabDatabase(String jdbcUrl, String user, String password) {

    /** Prefix of every per-run schema; the server drops leftovers with this prefix. */
    public static final String SCHEMA_PREFIX = "run_";

    /** The schema a run owns in the lab database: {@code run_} + the run id's hex digits. */
    public static String schemaFor(String runId) {
        String hex = runId.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (hex.isEmpty() || hex.length() > 40) {
            throw new IllegalArgumentException("run id cannot be mapped to a schema name: " + runId);
        }
        return SCHEMA_PREFIX + hex;
    }

    @Override
    public String toString() {
        return "LabDatabase[jdbcUrl=" + jdbcUrl + ", user=" + user + ", password=***]";
    }
}
