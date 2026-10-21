package dev.forge.server.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Refuses HTTP requests that come from the runner container. The server never needs to receive
 * anything from it — the server opens every connection to the runner agent itself — while code
 * running in a run could otherwise call the (unauthenticated) API, for example to queue more runs.
 *
 * <p>The runner's addresses are resolved from its host name ({@code forge.security.deny-requests-from},
 * {@code runner} in the compose deployment) and refreshed periodically, since a restarted container
 * may get a new address.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnExpression("'${forge.security.deny-requests-from:}' != ''")
public class RunnerNetworkGuard extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RunnerNetworkGuard.class);
    private static final long REFRESH_NANOS = 15_000_000_000L;

    private final String host;
    private volatile Set<String> denied = Set.of();
    private volatile long resolvedAt;

    public RunnerNetworkGuard(@Value("${forge.security.deny-requests-from}") String host) {
        this.host = host;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (denied().contains(request.getRemoteAddr())) {
            log.warn("refused {} {} from the runner network ({})", request.getMethod(), request.getRequestURI(), request.getRemoteAddr());
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "requests from the runner container are not accepted");
            return;
        }
        chain.doFilter(request, response);
    }

    Set<String> denied() {
        long now = System.nanoTime();
        if (now - resolvedAt > REFRESH_NANOS) {
            try {
                denied = Arrays.stream(InetAddress.getAllByName(host)).map(InetAddress::getHostAddress).collect(Collectors.toUnmodifiableSet());
            } catch (UnknownHostException e) {
                // the runner is not up (yet); keep the last known addresses
            }
            resolvedAt = now;
        }
        return denied;
    }
}
