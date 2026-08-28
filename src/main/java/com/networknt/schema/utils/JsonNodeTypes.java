package com.networknt.schema.utils;

import java.util.Iterator;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.ExecutionContext;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaContext;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;

public class JsonNodeTypes {
    private static final long V6_VALUE = SpecificationVersion.DRAFT_6.getOrder();

    private static final String TYPE = "type";
    private static final String ENUM = "enum";
    private static final String REF = "$ref";
    private static final String NULLABLE = "nullable";

    public static boolean isNodeNullable(JsonNode schema){
        JsonNode nullable = schema.get(NULLABLE);
        return nullable != null && nullable.asBoolean();
    }

    public static boolean equalsToSchemaType(JsonNode node, JsonType schemaType, Schema parentSchema, SchemaContext schemaContext, ExecutionContext executionContext) {
        SchemaRegistryConfig config = schemaContext.getSchemaRegistryConfig();
        JsonType nodeType = TypeFactory.getValueNodeType(node, config);
        // in the case that node type is not the same as schema type, try to convert node to the
        // same type of schema. In REST API, query parameters, path parameters and headers are all
        // string type and we must convert, otherwise, all schema validations will fail.
        if (nodeType != schemaType) {
            if (schemaType == JsonType.NUMBER && nodeType == JsonType.INTEGER) {
                return true;
            }
            if (schemaType == JsonType.INTEGER && nodeType == JsonType.NUMBER && node.canConvertToExactIntegral() && V6_VALUE <= detectVersion(schemaContext)) {
                return true;
            }

            if (nodeType == JsonType.NULL) {
                if (parentSchema != null && schemaContext.isNullableKeywordEnabled()
                        && isNullableAncestor(parentSchema, executionContext)) {
                    return true;
                }
            }

            // Skip the type validation when the schema is an enum object schema. Since the current type
            // of node itself can be used for type validation.
            if (!config.isStrict("type", Boolean.TRUE) && isEnumObjectSchema(parentSchema, executionContext)) {
                return true;
            }
            if (config.isTypeLoose()) {
                // if typeLoose is true, everything can be a size 1 array
                if (schemaType == JsonType.ARRAY) {
                    return true;
                }
                if (nodeType == JsonType.STRING) {
                    if (schemaType == JsonType.INTEGER) {
                        return Strings.isInteger(node.textValue());
                    } else if (schemaType == JsonType.BOOLEAN) {
                        return Strings.isBoolean(node.textValue());
                    } else if (schemaType == JsonType.NUMBER) {
                        return Strings.isNumeric(node.textValue());
                    }
                }
            }
            if (schemaType == JsonType.ANY) {
                return true;
            }
            return false;
        }
        return true;
    }

    /**
     * Determines if the schema owning the failing {@code type} keyword is
     * nullable, either directly, via its lexical parent, or via a schema that
     * referenced it through {@code $ref}.
     * <p>
     * A schema resolved through {@code $ref} is cached and shared across every
     * site that references it, so its lexical parent (obtained through
     * {@link Schema#getParentSchema()}) reflects where it is declared in the
     * document rather than where it was referenced from. To find a
     * {@code nullable: true} declared on the referencing schema (for example a
     * property composed using {@code allOf} containing only a {@code $ref}),
     * this walks up the dynamic evaluation stack instead, following through any
     * chain of {@code $ref} schemas.
     * <p>
     * A schema found this way is itself a Reference Object (its node contains
     * {@code $ref}), and per the OpenAPI 3.0 specification any sibling
     * properties on a Reference Object, including {@code nullable}, must be
     * ignored. So its own node is never consulted for {@code nullable} — only
     * its lexical parent, which is the schema actually composing it (for
     * example the {@code allOf}-owning schema).
     *
     * @param schema the schema owning the {@code type} keyword
     * @param executionContext the execution context
     * @return true if a nullable schema is found
     */
    private static boolean isNullableAncestor(Schema schema, ExecutionContext executionContext) {
        Schema current = schema;
        boolean isRefSchema = false;
        while (current != null) {
            if (!isRefSchema && isNodeNullable(current.getSchemaNode())) {
                return true;
            }
            Schema parentSchema = current.getParentSchema();
            if (parentSchema != null && isNodeNullable(parentSchema.getSchemaNode())) {
                return true;
            }
            current = findReferencingSchema(current, executionContext);
            isRefSchema = true;
        }
        return false;
    }

    /**
     * Finds the schema that referenced the given schema through {@code $ref}, if
     * any, by looking at the schema evaluated immediately before it on the
     * dynamic evaluation stack.
     *
     * @param schema the schema that may have been reached through {@code $ref}
     * @param executionContext the execution context
     * @return the referencing schema, or null if none is found
     */
    private static Schema findReferencingSchema(Schema schema, ExecutionContext executionContext) {
        Iterator<Schema> ancestors = executionContext.getEvaluationSchema().descendingIterator();
        while (ancestors.hasNext()) {
            if (ancestors.next() == schema) {
                if (ancestors.hasNext()) {
                    Schema candidate = ancestors.next();
                    if (candidate.getSchemaNode().get(REF) != null) {
                        return candidate;
                    }
                }
                return null;
            }
        }
        return null;
    }

    private static long detectVersion(SchemaContext schemaContext) {
        return schemaContext.getDialect().getSpecificationVersion().getOrder();
    }

    /**
     * Check if the type of the JsonNode's value is number based on the
     * status of typeLoose flag.
     *
     * @param node        the JsonNode to check
     * @param config      the SchemaValidatorsConfig to depend on
     * @return boolean to indicate if it is a number
     */
    public static boolean isNumber(JsonNode node, SchemaRegistryConfig config) {
        if (node.isNumber()) {
            return true;
        } else if (config.isTypeLoose()) {
            if (TypeFactory.getValueNodeType(node, config) == JsonType.STRING) {
                return Strings.isNumeric(node.textValue());
            }
        }
        return false;
    }

    private static boolean isEnumObjectSchema(Schema jsonSchema, ExecutionContext executionContext) {
        
        // There are three conditions for enum object schema
        // 1. The current schema contains key "type", and the value is object
        // 2. The current schema contains key "enum", and the value is an array
        // 3. The parent schema if refer from components, which means the corresponding enum object class would be generated
        JsonNode typeNode = null;
        JsonNode enumNode = null;
        boolean refNode = false;

        if (jsonSchema != null) {
            if (jsonSchema.getSchemaNode() != null) {
                typeNode = jsonSchema.getSchemaNode().get(TYPE);
                enumNode = jsonSchema.getSchemaNode().get(ENUM);
            }
            refNode = REF.equals(executionContext.getEvaluationPath().getParent().getElement(-1));
        }
        if (typeNode != null && enumNode != null && refNode) {
            return TypeFactory.getSchemaNodeType(typeNode) == JsonType.OBJECT && enumNode.isArray();
        }
        return false;
    }
}
