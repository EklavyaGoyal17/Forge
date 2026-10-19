package dev.forge.lab.jvm;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * A point on the JFR timeline at which the runner's {@link System#nanoTime()} is known.
 *
 * <p>JFR timestamps and {@code System.nanoTime()} come from different clocks. Two markers bracket a
 * measured region, so the summariser can place JFR events (a finished compilation, a GC pause)
 * relative to measurements taken with {@code nanoTime} — e.g. "C2 code was installed before
 * invocation 612" — without assuming the two clocks share an origin.
 */
@Name(JfrMarker.NAME)
@Label("FORGE marker")
@Category("FORGE")
@Description("Correlates the JFR timeline with System.nanoTime in the runner")
@StackTrace(false)
public final class JfrMarker extends jdk.jfr.Event {

    public static final String NAME = "forge.Marker";

    @Label("Label")
    String label;

    @Label("System.nanoTime")
    long nanoTime;

    /** Commits a marker now (no-op when no recording is running). */
    public static void mark(String label) {
        JfrMarker m = new JfrMarker();
        m.label = label;
        m.nanoTime = System.nanoTime();
        m.commit();
    }
}
