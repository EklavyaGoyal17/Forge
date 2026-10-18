package dev.forge.server.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Serves the built UI from {@code forge.ui.dir} (set in the container image) so that one process
 * delivers both the API and the single-page app. Client-side routes such as
 * {@code /experiments/<id>} are forwarded to {@code index.html}. In development the Vite dev server
 * serves the UI instead and this configuration is inactive.
 */
@Configuration
@ConditionalOnProperty("forge.ui.dir")
public class UiConfig implements WebMvcConfigurer {

    private final Path uiDir;

    public UiConfig(org.springframework.core.env.Environment env) {
        this.uiDir = Path.of(env.getRequiredProperty("forge.ui.dir")).toAbsolutePath().normalize();
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // A handler resolves only the part matched by the pattern's wildcard against its location,
        // so /assets/** must point at the assets directory itself.
        registry.addResourceHandler("/assets/**").addResourceLocations(location(uiDir.resolve("assets")));
        registry.addResourceHandler("/*.svg", "/*.ico", "/*.png", "/index.html").addResourceLocations(location(uiDir));
    }

    private static String location(Path dir) {
        String uri = dir.toUri().toString();
        return uri.endsWith("/") ? uri : uri + "/";
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 10)
    OncePerRequestFilter spaFallbackFilter() {
        boolean hasIndex = Files.isRegularFile(uiDir.resolve("index.html"));
        return new OncePerRequestFilter() {
            @Override
            protected boolean shouldNotFilter(HttpServletRequest request) {
                String path = request.getRequestURI();
                return !hasIndex
                        || !"GET".equals(request.getMethod())
                        || path.startsWith("/api/")
                        || path.startsWith("/actuator")
                        || path.startsWith("/assets/")
                        || path.substring(path.lastIndexOf('/') + 1).contains(".");
            }

            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                request.getRequestDispatcher("/index.html").forward(request, response);
            }
        };
    }
}
