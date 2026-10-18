package dev.forge.server;

import dev.forge.server.config.UiConfig;
import dev.forge.server.scenario.ScenarioController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The packaged UI: hashed assets are served, client-side routes fall back to index.html, the API is untouched. */
@WebMvcTest(controllers = ScenarioController.class)
@Import(UiConfig.class)
class UiConfigTest {

    static final Path UI;

    static {
        try {
            UI = Files.createTempDirectory("forge-ui-test");
            Files.createDirectories(UI.resolve("assets"));
            Files.writeString(UI.resolve("index.html"), "<!doctype html><title>FORGE</title>");
            Files.writeString(UI.resolve("assets/index-abc123.js"), "console.log('forge')");
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void uiDir(DynamicPropertyRegistry registry) {
        registry.add("forge.ui.dir", UI::toString);
    }

    @Autowired
    MockMvc mvc;

    @Test
    void servesHashedAssetsFromTheAssetsDirectory() throws Exception {
        mvc.perform(get("/assets/index-abc123.js")).andExpect(status().isOk())
                .andExpect(content().string("console.log('forge')"));
    }

    @Test
    void clientSideRoutesForwardToIndex() throws Exception {
        mvc.perform(get("/experiments/some-id")).andExpect(forwardedUrl("/index.html"));
        mvc.perform(get("/")).andExpect(forwardedUrl("/index.html"));
    }

    @Test
    void apiRoutesAreNotRewritten() throws Exception {
        mvc.perform(get("/api/scenarios")).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"));
        mvc.perform(get("/assets/missing.js")).andExpect(status().isNotFound());
    }
}
