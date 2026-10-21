package dev.forge.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.regex.Pattern;

/**
 * The disposable lab database used by Database Studio scenarios: a different database with its own
 * restricted role. By default it lives on the platform's PostgreSQL server; with {@code adminUrl} set
 * it lives on a separate server (the compose deployment's {@code lab-postgres}, the only database the
 * runner container can reach), administered with {@code adminUser}/{@code adminPassword}.
 */
@ConfigurationProperties("forge.lab-database")
public record LabDatabaseProperties(
        boolean enabled,
        String database,
        String role,
        int connectionLimit,
        int statementTimeoutSeconds,
        int lockTimeoutSeconds,
        String adminUrl,
        String adminUser,
        String adminPassword) {

    private static final Pattern IDENTIFIER = Pattern.compile("[a-z_][a-z0-9_]{0,62}");

    public LabDatabaseProperties {
        if (database == null || !IDENTIFIER.matcher(database).matches()) {
            throw new IllegalArgumentException("forge.lab-database.database must be a lower-case SQL identifier");
        }
        if (role == null || !IDENTIFIER.matcher(role).matches()) {
            throw new IllegalArgumentException("forge.lab-database.role must be a lower-case SQL identifier");
        }
        if (connectionLimit < 1 || connectionLimit > 64) {
            throw new IllegalArgumentException("forge.lab-database.connection-limit must be in [1, 64]");
        }
        if (statementTimeoutSeconds < 1 || lockTimeoutSeconds < 1) {
            throw new IllegalArgumentException("forge.lab-database timeouts must be positive");
        }
    }

    /** True when the lab database is on its own PostgreSQL server rather than the platform's. */
    public boolean separateServer() {
        return adminUrl != null && !adminUrl.isBlank();
    }
}
