package dev.forge.server.config;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Small facade over the application's Jackson mapper for jsonb columns. */
@Component
public class Json {

    private final JsonMapper mapper;

    public Json(JsonMapper mapper) {
        this.mapper = mapper;
    }

    public String write(Object value) {
        return mapper.writeValueAsString(value);
    }

    public JsonNode read(String json) {
        return json == null ? null : mapper.readTree(json);
    }

    public JsonNode tree(Object value) {
        return mapper.valueToTree(value);
    }

    public <T> T convert(Object value, Class<T> type) {
        return mapper.convertValue(value, type);
    }

    public JsonMapper mapper() {
        return mapper;
    }
}
