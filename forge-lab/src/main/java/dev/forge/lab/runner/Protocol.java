package dev.forge.lab.runner;

import dev.forge.lab.scenario.ScenarioResult;
import tools.jackson.databind.json.JsonMapper;

import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Runner → server messages, one JSON object per line on the runner's real stdout.
 *
 * <pre>
 * {"type":"event","ts":…,"level":"INFO","message":"…"}
 * {"type":"progress","ts":…,"done":3,"total":20,"phase":"measured"}
 * {"type":"artifact","ts":…,"name":"…","contentType":"text/plain","content":"…"}
 * {"type":"result","ts":…,"environment":{…},"result":{…}}
 * {"type":"error","ts":…,"kind":"…","message":"…","stackTrace":"…"}
 * </pre>
 */
public final class Protocol {

    public static final JsonMapper MAPPER = JsonMapper.builder().build();

    public static final String EVENT = "event";
    public static final String PROGRESS = "progress";
    public static final String ARTIFACT = "artifact";
    public static final String RESULT = "result";
    public static final String ERROR = "error";

    private final PrintStream out;

    public Protocol(PrintStream out) {
        this.out = out;
    }

    public void event(String level, String message) {
        Map<String, Object> m = base(EVENT);
        m.put("level", level);
        m.put("message", message);
        write(m);
    }

    public void progress(long done, long total, String phase) {
        Map<String, Object> m = base(PROGRESS);
        m.put("done", done);
        m.put("total", total);
        m.put("phase", phase);
        write(m);
    }

    public void artifact(String name, String contentType, String content) {
        Map<String, Object> m = base(ARTIFACT);
        m.put("name", name);
        m.put("contentType", contentType);
        m.put("content", content);
        write(m);
    }

    public void result(Map<String, Object> environment, ScenarioResult result) {
        Map<String, Object> m = base(RESULT);
        m.put("environment", environment);
        m.put("result", result);
        write(m);
    }

    public void error(String kind, String message, String stackTrace) {
        Map<String, Object> m = base(ERROR);
        m.put("kind", kind);
        m.put("message", message);
        m.put("stackTrace", stackTrace);
        write(m);
    }

    private static Map<String, Object> base(String type) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("ts", System.currentTimeMillis());
        return m;
    }

    private synchronized void write(Map<String, Object> message) {
        out.println(MAPPER.writeValueAsString(message));
        out.flush();
    }
}
