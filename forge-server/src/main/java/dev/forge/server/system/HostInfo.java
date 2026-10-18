package dev.forge.server.system;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Facts about the host that the runner JVM cannot see (its environment is cleared) but that a
 * measurement depends on. Captured once at startup.
 */
@Component
public class HostInfo {

    private final Map<String, Object> facts;

    public HostInfo(@Value("${forge.version:dev}") String forgeVersion) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cpuModel", cpuModel());
        m.put("logicalProcessors", Runtime.getRuntime().availableProcessors());
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            m.put("totalMemoryBytes", os.getTotalMemorySize());
        }
        m.put("osName", System.getProperty("os.name"));
        m.put("osVersion", System.getProperty("os.version"));
        m.put("inContainer", Files.exists(Path.of("/.dockerenv")));
        m.put("forgeVersion", forgeVersion);
        this.facts = java.util.Collections.unmodifiableMap(m); // keeps insertion order for display
    }

    public Map<String, Object> facts() {
        return facts;
    }

    public String cpuModelOrUnknown() {
        Object v = facts.get("cpuModel");
        return v == null ? "unknown" : v.toString();
    }

    private static String cpuModel() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (os.contains("linux")) {
                for (String line : Files.readAllLines(Path.of("/proc/cpuinfo"))) {
                    if (line.startsWith("model name")) {
                        return line.substring(line.indexOf(':') + 1).trim();
                    }
                }
            } else if (os.contains("windows")) {
                String out = exec(List.of("reg", "query", "HKLM\\HARDWARE\\DESCRIPTION\\System\\CentralProcessor\\0",
                        "/v", "ProcessorNameString"));
                for (String line : out.split("\\R")) {
                    if (line.contains("ProcessorNameString")) {
                        return line.substring(line.indexOf("REG_SZ") + 6).trim();
                    }
                }
                return System.getenv("PROCESSOR_IDENTIFIER");
            } else if (os.contains("mac")) {
                return exec(List.of("sysctl", "-n", "machdep.cpu.brand_string")).trim();
            }
        } catch (Exception ignored) {
            // best effort only
        }
        return null;
    }

    private static String exec(List<String> command) throws Exception {
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        byte[] out = p.getInputStream().readNBytes(16_384);
        if (!p.waitFor(3, TimeUnit.SECONDS)) {
            p.destroyForcibly();
        }
        return new String(out, StandardCharsets.UTF_8);
    }
}
