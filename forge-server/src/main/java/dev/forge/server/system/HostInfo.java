package dev.forge.server.system;

import dev.forge.lab.runner.HostFacts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Facts about the server's host, captured once at startup. Runs launched in this process record
 * these; runs executed by the runner agent record the agent's host instead.
 */
@Component
public class HostInfo {

    private final Map<String, Object> facts;

    public HostInfo(@Value("${forge.version:dev}") String forgeVersion) {
        this.facts = HostFacts.collect(forgeVersion);
    }

    public Map<String, Object> facts() {
        return facts;
    }

    public String cpuModelOrUnknown() {
        Object v = facts.get("cpuModel");
        return v == null ? "unknown" : v.toString();
    }
}
