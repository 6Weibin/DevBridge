package com.devbridge.server.ai.tool.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 工具 JSON Schema 轻量校验器，覆盖当前工具契约使用的对象、数组、标量、必填和附加字段规则。
 *
 * <p>by AI.Coding</p>
 */
@Component
public class ToolSchemaValidator {

    /**
     * 校验工具参数，不接受未知字段或类型不匹配值。
     *
     * @param schema JSON Schema
     * @param value 参数值
     */
    public void validate(JsonNode schema, JsonNode value) {
        if (schema == null || schema.isNull()) {
            throw new IllegalArgumentException("工具输入 Schema 不能为空");
        }
        validateNode(schema, value, "$", true);
    }

    /**
     * 递归校验单个节点。
     *
     * @param schema 当前 Schema
     * @param value 当前值
     * @param path 参数路径
     * @param root 是否根节点
     */
    private void validateNode(JsonNode schema, JsonNode value, String path, boolean root) {
        String type = schema.path("type").asText(root ? "object" : "");
        if (!matchesType(type, value)) {
            throw new IllegalArgumentException(path + " 类型必须为 " + type);
        }
        validateEnum(schema, value, path);
        if ("object".equals(type)) {
            validateObject(schema, value, path);
        } else if ("array".equals(type)) {
            validateArray(schema, value, path);
        } else if ("string".equals(type)) {
            validateString(schema, value, path);
        } else if ("integer".equals(type) || "number".equals(type)) {
            validateNumber(schema, value, path);
        }
    }

    /**
     * 校验对象必填字段、未知字段和子属性。
     *
     * @param schema 对象 Schema
     * @param value 对象值
     * @param path 参数路径
     */
    private void validateObject(JsonNode schema, JsonNode value, String path) {
        Set<String> required = new HashSet<>();
        schema.path("required").forEach(node -> required.add(node.asText()));
        for (String field : required) {
            if (!value.has(field) || value.get(field).isNull()) {
                throw new IllegalArgumentException(path + "." + field + " 不能为空");
            }
        }
        JsonNode properties = schema.path("properties");
        boolean rejectUnknown = schema.has("additionalProperties") && !schema.path("additionalProperties").asBoolean(true);
        Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode fieldSchema = properties.get(field.getKey());
            if (fieldSchema == null && rejectUnknown) {
                throw new IllegalArgumentException(path + "." + field.getKey() + " 是未知字段");
            }
            if (fieldSchema != null) {
                validateNode(fieldSchema, field.getValue(), path + "." + field.getKey(), false);
            }
        }
    }

    /**
     * 校验数组元素。
     *
     * @param schema 数组 Schema
     * @param value 数组值
     * @param path 参数路径
     */
    private void validateArray(JsonNode schema, JsonNode value, String path) {
        int minItems = schema.path("minItems").asInt(0);
        int maxItems = schema.path("maxItems").asInt(Integer.MAX_VALUE);
        if (value.size() < minItems || value.size() > maxItems) {
            throw new IllegalArgumentException(path + " 元素数量必须在 " + minItems + " 到 " + maxItems + " 之间");
        }
        JsonNode itemSchema = schema.path("items");
        for (int index = 0; index < value.size(); index++) {
            validateNode(itemSchema, value.get(index), path + "[" + index + "]", false);
        }
    }

    /**
     * 校验字符串长度。
     *
     * @param schema 字符串 Schema
     * @param value 字符串值
     * @param path 参数路径
     */
    private void validateString(JsonNode schema, JsonNode value, String path) {
        int minLength = schema.path("minLength").asInt(0);
        int maxLength = schema.path("maxLength").asInt(Integer.MAX_VALUE);
        if (value.asText().length() < minLength || value.asText().length() > maxLength) {
            throw new IllegalArgumentException(path + " 长度必须在 " + minLength + " 到 " + maxLength + " 之间");
        }
    }

    /**
     * 校验数字最小值和最大值。
     *
     * @param schema 数字 Schema
     * @param value 数字值
     * @param path 参数路径
     */
    private void validateNumber(JsonNode schema, JsonNode value, String path) {
        double number = value.asDouble();
        if (schema.has("minimum") && number < schema.path("minimum").asDouble()) {
            throw new IllegalArgumentException(path + " 不能小于 " + schema.path("minimum").asText());
        }
        if (schema.has("maximum") && number > schema.path("maximum").asDouble()) {
            throw new IllegalArgumentException(path + " 不能大于 " + schema.path("maximum").asText());
        }
    }

    /**
     * 校验枚举值。
     *
     * @param schema Schema
     * @param value 参数值
     * @param path 参数路径
     */
    private void validateEnum(JsonNode schema, JsonNode value, String path) {
        JsonNode values = schema.path("enum");
        if (values.isArray() && values.size() > 0) {
            boolean matched = false;
            for (JsonNode allowed : values) {
                matched |= allowed.equals(value);
            }
            if (!matched) {
                throw new IllegalArgumentException(path + " 不在允许值范围内");
            }
        }
    }

    /**
     * 判断 JSON 值是否符合声明类型。
     *
     * @param type Schema 类型
     * @param value JSON 值
     * @return 类型匹配返回 true
     */
    private boolean matchesType(String type, JsonNode value) {
        if (value == null || value.isNull()) {
            return false;
        }
        return switch (type) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isTextual();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "" -> true;
            default -> throw new IllegalArgumentException("不支持的工具 Schema 类型: " + type);
        };
    }
}
