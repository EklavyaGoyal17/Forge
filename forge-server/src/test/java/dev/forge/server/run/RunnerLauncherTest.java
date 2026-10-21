package dev.forge.server.run;

import dev.forge.lab.scenario.RunSpec;
import dev.forge.server.config.Json;
import dev.forge.server.config.RunnerProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exercises the process-isolation paths of the launcher directly, without Spring or a database. */
class RunnerLauncherTest {

    @TempDir
    Path root;

    private RunnerLauncher launcher(long stdoutCap) throws Exception {
        RunnerProperties props = new RunnerProperties("", "", root.toString(), 1, 10, 128, 512, 60, 0,
                stdoutCap, 65_536, 1_048_576, 20, false, null, null);
        return new RunnerLauncher(props, new Json(JsonMapper.builder().build()));
    }

    private static RunSpec spec(String id) {
        return new RunSpec(id, "concurrency.lost-update", 1, "atomic-cas",
                Map.of("mode", "stress", "threads", 2L, "depositsPerThread", 100L), 1, 2, 0);
    }

    @Test
    void jitModeVariantsAddTheirDeclaredFlagAndOtherVariantsAddNone() throws Exception {
        RunnerLauncher l = launcher(1 << 20);
        RunSpec interpreter = new RunSpec("x", "jvm.warmup", 1, "interpreter", Map.of(), 1, 1, 0);
        RunSpec tiered = new RunSpec("y", "jvm.warmup", 1, "tiered", Map.of(), 1, 1, 0);
        assertThat(RunnerLauncher.variantFlags(interpreter)).containsExactly("-Xint");
        assertThat(RunnerLauncher.variantFlags(tiered)).isEmpty();
        assertThat(RunnerLauncher.variantFlags(spec("plain"))).isEmpty();
        List<String> args = l.jvmArguments(128, 30, root, RunnerLauncher.variantFlags(interpreter));
        // before the main class, so the JVM (not the runner) receives it
        assertThat(args.indexOf("-Xint")).isBetween(0, args.indexOf("-cp"));
    }

    @Test
    void flagsOutsideTheAllowListAreRefused() {
        assertThatThrownBy(() -> RunnerLauncher.vetVariantFlags(List.of("-XX:+UnlockDiagnosticVMOptions")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("allow-list");
        assertThatThrownBy(() -> RunnerLauncher.vetVariantFlags(List.of("-javaagent:/tmp/x.jar")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(RunnerLauncher.vetVariantFlags(List.of("-XX:-TieredCompilation"))).containsExactly("-XX:-TieredCompilation");
    }

    @Test
    void everyVariantFlagDeclaredInTheCatalogIsOnTheAllowList() {
        dev.forge.lab.scenario.ScenarioCatalog.descriptors().forEach(d -> d.variants().forEach(v ->
                assertThat(RunnerLauncher.ALLOWED_VARIANT_FLAGS).as("%s/%s", d.id(), v.id()).containsAll(v.jvmFlags())));
    }

    @Test
    void timeoutDestroysTheWholeProcessTree() throws Exception {
        RunnerLauncher l = launcher(1 << 20);
        l.useMainClassForTesting(MisbehavingRunners.HangForever.class.getName());
        List<JsonNode> messages = new CopyOnWriteArrayList<>();
        long start = System.nanoTime();

        RunnerLauncher.Outcome o = l.launch(spec("hang"), 128, 5, messages::add, () -> false);

        double seconds = (System.nanoTime() - start) / 1e9;
        assertThat(o.termination()).isEqualTo(RunnerLauncher.Termination.TIMED_OUT);
        assertThat(seconds).isBetween(4.5, 15.0);
        String hanging = messages.stream().map(m -> m.path("message").asString())
                .filter(s -> s.startsWith("hanging ")).findFirst().orElseThrow();
        long grandchild = Long.parseLong(hanging.substring("hanging ".length()));
        // the grandchild the runner spawned must have been destroyed with the tree
        Thread.sleep(500);
        assertThat(ProcessHandle.of(grandchild).map(ProcessHandle::isAlive).orElse(false)).isFalse();
    }

    @Test
    void cancellationKillsPromptly() throws Exception {
        RunnerLauncher l = launcher(1 << 20);
        l.useMainClassForTesting(MisbehavingRunners.HangForever.class.getName());
        long start = System.nanoTime();
        RunnerLauncher.Outcome o = l.launch(spec("cancel"), 128, 60, m -> { },
                () -> System.nanoTime() - start > 1_500_000_000L);
        assertThat(o.termination()).isEqualTo(RunnerLauncher.Termination.CANCELLED);
        assertThat((System.nanoTime() - start) / 1e9).isLessThan(15.0);
    }

    @Test
    void outputFloodTripsTheCap() throws Exception {
        RunnerLauncher l = launcher(200_000);
        l.useMainClassForTesting(MisbehavingRunners.Flood.class.getName());
        RunnerLauncher.Outcome o = l.launch(spec("flood"), 128, 30, m -> { }, () -> false);
        assertThat(o.termination()).isEqualTo(RunnerLauncher.Termination.OUTPUT_LIMIT);
    }

    @Test
    void runnerSeesOnlyAMinimalEnvironmentAndAPrivateTempDir() throws Exception {
        RunnerLauncher l = launcher(1 << 20);
        l.useMainClassForTesting(MisbehavingRunners.EnvProbe.class.getName());
        List<JsonNode> messages = new CopyOnWriteArrayList<>();
        RunnerLauncher.Outcome o = l.launch(spec("env"), 128, 30, messages::add, () -> false);
        assertThat(o.termination()).isEqualTo(RunnerLauncher.Termination.EXITED);
        String env = messages.stream().map(m -> m.path("message").asString()).filter(s -> s.startsWith("ENV ")).findFirst().orElseThrow();
        List<String> keys = List.of(env.substring(4).split(","));
        assertThat(keys).allMatch(k -> List.of("path", "systemroot", "temp", "tmp", "tmpdir", "lang")
                .contains(k.toLowerCase()), "only minimal variables: " + keys);
        String tmp = messages.stream().map(m -> m.path("message").asString()).filter(s -> s.startsWith("TMP ")).findFirst().orElseThrow();
        assertThat(tmp).contains("run-env");
        // the private work directory is removed after the run
        assertThat(Files.exists(root.resolve("run-env"))).isFalse();
    }

    @Test
    void argfileQuotingEscapesBackslashesAndQuotes() {
        assertThat(RunnerLauncher.quoteArg("C:\\Program Files\\x")).isEqualTo("\"C:\\\\Program Files\\\\x\"");
        assertThat(RunnerLauncher.quoteArg("a\"b")).isEqualTo("\"a\\\"b\"");
    }
}
