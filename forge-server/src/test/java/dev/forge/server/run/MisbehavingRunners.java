package dev.forge.server.run;

import java.io.PrintStream;

/** Runner substitutes that break the rules on purpose, to prove the launcher contains them. */
public final class MisbehavingRunners {

    private MisbehavingRunners() {
    }

    /** Ignores every deadline and spawns a child process that also never exits. */
    public static final class HangForever {
        public static void main(String[] args) throws Exception {
            String java = ProcessHandle.current().info().command().orElse("java");
            Process child = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), Sleeper.class.getName())
                    .inheritIO().start();
            System.out.println("{\"type\":\"event\",\"ts\":0,\"level\":\"INFO\",\"message\":\"hanging " + child.pid() + "\"}");
            System.out.flush();
            Thread.sleep(Long.MAX_VALUE);
        }
    }

    public static final class Sleeper {
        public static void main(String[] args) throws Exception {
            Thread.sleep(Long.MAX_VALUE);
        }
    }

    /** Floods stdout to trip the output cap. */
    public static final class Flood {
        public static void main(String[] args) {
            PrintStream out = System.out;
            String line = "{\"type\":\"event\",\"ts\":0,\"level\":\"INFO\",\"message\":\"" + "x".repeat(1000) + "\"}";
            while (true) {
                out.println(line);
            }
        }
    }

    /** Reports what it can see of the environment, to verify secrets do not leak into runners. */
    public static final class EnvProbe {
        public static void main(String[] args) {
            String keys = String.join(",", System.getenv().keySet());
            System.out.println("{\"type\":\"event\",\"ts\":0,\"level\":\"INFO\",\"message\":\"ENV " + keys + "\"}");
            System.out.println("{\"type\":\"event\",\"ts\":0,\"level\":\"INFO\",\"message\":\"TMP "
                    + System.getProperty("java.io.tmpdir").replace("\\", "/") + "\"}");
        }
    }
}
