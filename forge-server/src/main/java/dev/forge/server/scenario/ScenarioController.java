package dev.forge.server.scenario;

import dev.forge.lab.scenario.ScenarioCatalog;
import dev.forge.lab.scenario.ScenarioDescriptor;
import dev.forge.lab.scenario.SourceSnippets;
import dev.forge.lab.scenario.VariantSpec;
import dev.forge.server.web.ApiException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/scenarios")
public class ScenarioController {

    /** Descriptor plus the exact source of every variant, for the configuration and code views. */
    public record ScenarioView(ScenarioDescriptor descriptor, Map<String, String> sources) {
    }

    @GetMapping
    public List<ScenarioDescriptor> list() {
        return ScenarioCatalog.descriptors();
    }

    @GetMapping("/{id}")
    public ScenarioView get(@PathVariable String id) {
        ScenarioDescriptor d = ScenarioCatalog.find(id).orElseThrow(() -> ApiException.notFound("scenario")).descriptor();
        Map<String, String> sources = new LinkedHashMap<>();
        for (VariantSpec v : d.variants()) {
            SourceSnippets.resolve(v.sourceRef()).ifPresent(src -> sources.put(v.id(), src));
        }
        return new ScenarioView(d, sources);
    }
}
