package dev.forge.server.investigator;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

import static dev.forge.server.investigator.EvidenceBuilder.n;

/**
 * A short, readable digest of a run's lab-specific details (explorer counts, liveness verdicts,
 * HTTP latency aggregates, failure timelines, JFR highlights, query plans). Raw series and full
 * plans stay out: the digest must be small enough to cite and to send to a model.
 */
final class DetailDigest {

    private DetailDigest() {
    }

    static String of(JsonNode details) {
        if (details == null || !details.isObject()) {
            return "";
        }
        List<String> out = new ArrayList<>();
        JsonNode x;
        if ((x = details.path("explorer")).isObject()) {
            out.add("Interleaving explorer: " + x.path("schedulesExplored").asString() + " schedules"
                    + (x.path("exhaustive").asBoolean() ? " (exhaustive)" : " (not exhaustive)")
                    + ", " + x.path("violations").asString() + " violating, " + x.path("deadlocks").asString() + " deadlocks, "
                    + x.path("stepLimitHits").asString() + " hit the step bound"
                    + (x.path("counterexample").isObject() ? "; first counterexample outcome: " + x.path("counterexample").path("outcome").asString() : ""));
        }
        if ((x = details.path("liveness")).isObject()) {
            List<String> starved = new ArrayList<>();
            x.path("starvation").propertyNames().forEach(starved::add);
            out.add("Liveness explorer: " + x.path("states").asString() + " states, " + x.path("edges").asString() + " transitions, graph "
                    + (x.path("complete").asBoolean() ? "complete" : "incomplete") + "; safety violations " + x.path("safetyViolations").asString()
                    + ", deadlocks " + x.path("deadlocks").asString() + ", livelock " + (x.path("livelock").isObject() ? "found" : "none")
                    + ", starvation possible for " + (starved.isEmpty() ? "nobody" : String.join(", ", starved)));
        }
        if ((x = details.path("httpLoad")).isObject()) {
            JsonNode agg = x.path("aggregate");
            out.add("HTTP load (" + x.path("design").asString() + ", " + x.path("workers").asString() + " workers, "
                    + x.path("config").path("rate").asString() + " req/s offered): client " + lat(agg.path("client"))
                    + "; from actual send " + lat(agg.path("uncorrected")) + "; server service " + lat(agg.path("service"))
                    + "; queue wait " + lat(agg.path("queueWait")));
        }
        if ((x = details.path("failure")).isObject()) {
            JsonNode c = x.path("config");
            StringBuilder sb = new StringBuilder("Failure lab: fault " + c.path("fault").asString() + " from " + n(c.path("faultStartMs").asDouble())
                    + " to " + n(c.path("faultEndMs").asDouble()) + " ms; " + n(c.path("rate").asDouble()) + " req/s offered; dependency capacity "
                    + (c.path("healthyCapacityRps").isNumber() ? n(c.path("healthyCapacityRps").asDouble()) + " req/s" : "unbounded")
                    + "; policy " + x.path("policy"));
            for (JsonNode t : x.path("trials")) {
                sb.append("\n  trial ").append(t.path("trial").asInt() + 1).append(": recovery ")
                        .append(t.path("recoveryMs").isNumber() ? n(t.path("recoveryMs").asDouble()) + " ms" : "none")
                        .append(", ").append(t.path("ok").asString()).append(" of ").append(t.path("offered").asString()).append(" ok, ")
                        .append(t.path("attempts").asString()).append(" attempts, ").append(t.path("retries").asString()).append(" retries, ")
                        .append(t.path("shortCircuited").asString()).append(" short-circuited, dependency received ")
                        .append(t.path("downstream").path("received").asString()).append(" (").append(t.path("downstream").path("wasted").asString())
                        .append(" wasted), breaker transitions ").append(t.path("breakerTransitions").size());
            }
            out.add(sb.toString());
        }
        if ((x = details.path("jvm")).isObject()) {
            JsonNode jfr = x.path("jfr");
            StringBuilder sb = new StringBuilder("JVM lab (" + x.path("kind").asString() + "): JFR " + jfr.path("cpu").path("samples").asString()
                    + " CPU samples, " + jfr.path("gc").path("collections").asString() + " GCs totalling " + n(jfr.path("gc").path("totalPauseMs").asDouble())
                    + " ms (max " + n(jfr.path("gc").path("maxPauseMs").asDouble()) + " ms), monitor waits " + jfr.path("locks").path("monitorEnter").path("events").asString()
                    + " (" + n(jfr.path("locks").path("monitorEnter").path("totalMs").asDouble()) + " ms), parks " + jfr.path("locks").path("threadPark").path("events").asString()
                    + ", compilations " + jfr.path("compilations").size());
            int k = 0;
            for (JsonNode f : jfr.path("cpu").path("topSelf")) {
                if (k++ == 3) {
                    break;
                }
                sb.append("\n  hot (self): ").append(f.path("frame").asString()).append(" ").append(n(100 * f.path("share").asDouble())).append("% of samples");
            }
            k = 0;
            for (JsonNode a : jfr.path("allocation").path("byClass")) {
                if (k++ == 3) {
                    break;
                }
                sb.append("\n  allocation: ").append(a.path("objectClass").asString()).append(" ").append(n(100 * a.path("share").asDouble())).append("% of sampled bytes");
            }
            if (x.path("allocation").isObject()) {
                sb.append("\n  exact allocated bytes per operation: ").append(x.path("allocation").path("exactAllocatedBytes").asString());
            }
            out.add(sb.toString());
        }
        if ((x = details.path("database")).isObject()) {
            StringBuilder sb = new StringBuilder("Database: ");
            JsonNode ds = x.path("dataset");
            if (ds.isObject()) {
                sb.append(ds.path("txnRows").asString()).append(" transaction rows, ").append(ds.path("accounts").asString()).append(" accounts; ");
            }
            if (x.path("index").isObject()) {
                sb.append("index ").append(x.path("index").path("name").asString()).append("; ");
            }
            JsonNode plans = x.path("plans");
            if (plans.isArray() && !plans.isEmpty()) {
                JsonNode p = plans.get(0);
                sb.append("plan '").append(p.path("label").asString()).append("': nodes ").append(p.path("nodes"))
                        .append(", execution ").append(n(p.path("executionMs").asDouble())).append(" ms, scanned rows ")
                        .append(p.path("scannedRows").asString()).append(" for ").append(p.path("rootRows").asString()).append(" returned");
            }
            out.add(sb.toString());
        }
        if ((x = details.path("jmh")).isObject()) {
            out.add("JMH: " + x.path("mode").asString() + " mode, " + x.path("forks").asString() + " forks");
        }
        return String.join("\n", out);
    }

    private static String lat(JsonNode s) {
        if (!s.isObject() || s.path("count").asLong() == 0) {
            return "n/a";
        }
        return "p50 " + n(s.path("p50Ms").asDouble()) + " ms, p99 " + n(s.path("p99Ms").asDouble()) + " ms, max " + n(s.path("maxMs").asDouble())
                + " ms over " + s.path("count").asString() + " requests";
    }
}
