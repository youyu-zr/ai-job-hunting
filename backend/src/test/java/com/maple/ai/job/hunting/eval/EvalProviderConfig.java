package com.maple.ai.job.hunting.eval;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.maple.ai.job.hunting.common.ai.ModelEndpointPolicy;
import org.springframework.util.PropertyPlaceholderHelper;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** No Spring context, ambient environment, default config locations, or secret-bearing toString. */
final class EvalProviderConfig {
    static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final PropertyPlaceholderHelper PLACEHOLDERS =
            new PropertyPlaceholderHelper("${", "}", ":", false);
    private final String name;
    private final String model;
    private final String baseUrl;
    private final String apiKey;
    private final String completionPath;
    private final String host;
    private final JsonNode extraBody;
    private final String askCompatibility;

    private EvalProviderConfig(String name, String model, String baseUrl, String apiKey,
                               String completionPath, JsonNode extraBody, String askCompatibility) {
        this.name = name;
        this.model = model;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.completionPath = completionPath;
        this.host = ModelEndpointPolicy.validateBaseUrl(baseUrl, false, false).getHost();
        ModelEndpointPolicy.validateCompletionPath(completionPath);
        this.extraBody = extraBody;
        this.askCompatibility = askCompatibility;
        require(identifier(name).equals(name) && identifier(model).equals(model));
        require(!host.contains(apiKey) && !name.contains(apiKey) && !model.contains(apiKey));
    }

    static EvalProviderConfig read(Path propertiesPath, List<Path> envPaths) {
        try {
            Properties properties = new Properties();
            require(Files.size(propertiesPath) <= 2_097_152);
            try (Reader reader = Files.newBufferedReader(propertiesPath, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
            Map<String, String> environment = new HashMap<>();
            for (Path path : envPaths) {
                require(Files.size(path) <= 2_097_152);
                for (String raw : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                    String line = raw.strip();
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    if (line.startsWith("export ")) {
                        line = line.substring(7).stripLeading();
                    }
                    int equals = line.indexOf('=');
                    require(equals > 0);
                    String key = line.substring(0, equals).strip();
                    require(key.matches("[A-Za-z_][A-Za-z0-9_]*"));
                    String value = line.substring(equals + 1).strip();
                    if (value.startsWith("\"") || value.startsWith("'")) {
                        require(value.length() >= 2 && value.charAt(value.length() - 1) == value.charAt(0));
                        value = value.substring(1, value.length() - 1);
                    }
                    require(!value.contains("\r") && !value.contains("\n") && !value.contains("`")
                            && !value.contains("$("));
                    String previous = environment.putIfAbsent(key, value);
                    require(previous == null || previous.equals(value));
                }
            }
            return resolve(properties, environment);
        } catch (Exception ignored) {
            throw rejected();
        }
    }

    static EvalProviderConfig resolve(Properties properties, Map<String, String> environment) {
        try {
            List<ObjectNode> pools = new ArrayList<>();
            String list = expand(properties.getProperty("openai.pool.config.list", "[]"),
                    properties, environment);
            JsonNode explicit = JSON.readTree(list);
            require(explicit != null && explicit.isArray() && explicit.size() <= 100);
            for (JsonNode node : explicit) {
                require(node.isObject());
                pools.add((ObjectNode) node);
            }
            if (pools.isEmpty()) {
                require(bool(expand(properties.getProperty("openai.pool.config.auto", "true"),
                        properties, environment)));
                Map<String, ObjectNode> automatic = new LinkedHashMap<>();
                for (String key : properties.stringPropertyNames()) {
                    if (!key.startsWith("spring.ai.")) {
                        continue;
                    }
                    String suffix = key.substring("spring.ai.".length());
                    int dot = suffix.indexOf('.');
                    if (dot <= 0) {
                        continue;
                    }
                    String provider = suffix.substring(0, dot);
                    ObjectNode node = automatic.computeIfAbsent(provider,
                            unused -> JSON.createObjectNode().put("name", provider));
                    node.put(suffix.substring(dot + 1), properties.getProperty(key));
                }
                pools.addAll(automatic.values());
            }
            List<ObjectNode> primary = new ArrayList<>();
            for (ObjectNode pool : pools) {
                String first = value(pool, "chat.options.first", properties, environment, "false");
                if (bool(first)) {
                    primary.add(pool);
                }
            }
            require(primary.size() == 1);
            ObjectNode selected = primary.get(0);
            require(!bool(value(selected, "chat.options.proxy", properties, environment, "false")));
            String name = value(selected, "name", properties, environment, "");
            String model = value(selected, "chat.options.model", properties, environment, "");
            String baseUrl = value(selected, "base-url", properties, environment, "");
            String apiKey = value(selected, "api-key", properties, environment, "");
            require(!apiKey.isBlank() && apiKey.length() <= 4096 && apiKey.chars().noneMatch(Character::isWhitespace));
            String path = value(selected, "completions-path", properties, environment, "/v1/chat/completions");
            JsonNode extra = selected.get("extraBody");
            if (extra != null) {
                if (extra.isTextual()) {
                    extra = JSON.readTree(expand(extra.textValue(), properties, environment));
                }
                require(extra.isObject());
                extra = expandTree(extra, properties, environment);
                // The harness has no tools or function callbacks, even if a pool requests them.
                for (String forbidden : List.of("tools", "functions", "tool_choice", "function_call")) {
                    require(!extra.has(forbidden));
                }
            }
            String askCompatibility = "DEFAULT_POOL_FILE_MAPPING_ABSENT";
            if (properties.containsKey("use-ai-map")) {
                JsonNode routes = JSON.readTree(expand(properties.getProperty("use-ai-map"), properties, environment));
                require(routes != null && routes.isObject());
                JsonNode ask = routes.get("ask");
                askCompatibility = ask != null && ask.isTextual()
                        && "openai-pool".equals(ask.textValue())
                        ? "FILE_ASK_OPENAI_POOL_COMPATIBLE" : "FILE_ASK_ROUTE_INCOMPATIBLE";
            }
            return new EvalProviderConfig(name, model, baseUrl, apiKey, path, extra, askCompatibility);
        } catch (Exception ignored) {
            throw rejected();
        }
    }

    private static JsonNode expandTree(JsonNode node, Properties p, Map<String, String> env) {
        if (node.isTextual()) {
            return JSON.getNodeFactory().textNode(expand(node.textValue(), p, env));
        }
        if (node.isObject()) {
            ObjectNode result = JSON.createObjectNode();
            node.fields().forEachRemaining(field -> result.set(field.getKey(), expandTree(field.getValue(), p, env)));
            return result;
        }
        if (node.isArray()) {
            var result = JSON.createArrayNode();
            node.forEach(item -> result.add(expandTree(item, p, env)));
            return result;
        }
        return node;
    }

    private static String value(ObjectNode pool, String key, Properties p, Map<String, String> env, String fallback) {
        String alias = key.contains(".") ? key.replace('.', '-') : key.replace('-', '.');
        JsonNode first = pool.get(key);
        JsonNode second = pool.get(alias);
        require(first == null || first.isValueNode());
        require(second == null || second.isValueNode());
        String a = first == null ? null : expand(first.asText(), p, env);
        String b = second == null ? null : expand(second.asText(), p, env);
        require(a == null || b == null || a.equals(b));
        return a == null ? (b == null ? fallback : b) : a;
    }

    private static String expand(String value, Properties p, Map<String, String> env) {
        require(value != null && value.length() <= 2_097_152);
        String result = PLACEHOLDERS.replacePlaceholders(value,
                key -> env.containsKey(key) ? env.get(key) : p.getProperty(key));
        require(!result.contains("${") && result.length() <= 2_097_152);
        return result;
    }

    private static boolean bool(String value) {
        require("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value));
        return Boolean.parseBoolean(value);
    }

    static String identifier(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}")
                && !value.toLowerCase(java.util.Locale.ROOT).contains("sk-")
                ? value : "UNREPORTED";
    }

    String safeResponseIdentifier(String value) {
        return value == null || value.contains(apiKey) ? "UNREPORTED" : identifier(value);
    }

    static IllegalArgumentException rejected() {
        return new IllegalArgumentException("EVAL_CONFIGURATION_REJECTED");
    }

    static void require(boolean condition) {
        if (!condition) {
            throw rejected();
        }
    }

    String name() { return name; }
    String model() { return model; }
    String host() { return host; }
    String baseUrl() { return baseUrl; }
    String apiKey() { return apiKey; }
    String completionPath() { return completionPath; }
    JsonNode extraBody() { return extraBody; }
    String askCompatibility() { return askCompatibility; }
    boolean askCompatible() { return !askCompatibility.equals("FILE_ASK_ROUTE_INCOMPATIBLE"); }

    @Override
    public String toString() {
        return "EvalProviderConfig[REDACTED]";
    }
}
