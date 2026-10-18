package dev.forge.lab.runner;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Records the runtime facts that a measurement depends on, from inside the measuring JVM. */
public final class EnvironmentCapture {

    private EnvironmentCapture() {
    }

    public static Map<String, Object> capture() {
        RuntimeMXBean rt = ManagementFactory.getRuntimeMXBean();
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("javaVersion", System.getProperty("java.version"));
        env.put("javaVendor", System.getProperty("java.vendor"));
        env.put("vmName", System.getProperty("java.vm.name"));
        env.put("vmVersion", System.getProperty("java.vm.version"));
        env.put("osName", System.getProperty("os.name"));
        env.put("osArch", System.getProperty("os.arch"));
        env.put("osVersion", System.getProperty("os.version"));
        env.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        env.put("maxHeapBytes", Runtime.getRuntime().maxMemory());
        List<String> gcs = ManagementFactory.getGarbageCollectorMXBeans().stream()
                .map(GarbageCollectorMXBean::getName).toList();
        env.put("garbageCollectors", gcs);
        var jit = ManagementFactory.getCompilationMXBean();
        env.put("jitCompiler", jit == null ? null : jit.getName());
        env.put("jvmArguments", rt.getInputArguments());
        env.put("pid", rt.getPid());
        env.put("jvmUptimeAtCaptureMs", rt.getUptime());
        return env;
    }
}
