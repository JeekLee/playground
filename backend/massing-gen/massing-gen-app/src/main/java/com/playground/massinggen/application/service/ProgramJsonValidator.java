package com.playground.massinggen.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.playground.massinggen.domain.exception.MassingErrorCode;
import com.playground.massinggen.domain.exception.MassingException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * JSON-Schema validator for the LLM's programJson response per ADR-18 §9.
 *
 * <p>Loads {@code schemas/programJson.schema.json} from the classpath once
 * at construction (Draft 2020-12) using
 * {@code com.networknt:json-schema-validator:1.5.3}. Validation failures
 * (missing required, extra fields, wrong types, out-of-range values) throw
 * {@link MassingException} with {@link MassingErrorCode#BRIEF_EXTRACTION_FAILED}.
 *
 * <p>This component lives in {@code massing-gen-app} so the schema is
 * loadable by both the production adapter (in -infra) and the JUnit
 * extraction test fixtures.
 */
@Component
public class ProgramJsonValidator {

    private static final String SCHEMA_RESOURCE = "/schemas/programJson.schema.json";

    private final JsonSchema schema;

    public ProgramJsonValidator() {
        this.schema = loadSchema();
    }

    public void validate(JsonNode programJson) {
        if (programJson == null) {
            throw new MassingException(
                    MassingErrorCode.BRIEF_EXTRACTION_FAILED,
                    "programJson is null");
        }
        Set<ValidationMessage> errors = schema.validate(programJson);
        if (!errors.isEmpty()) {
            String detail = errors.stream()
                    .map(ValidationMessage::getMessage)
                    .reduce((a, b) -> a + "; " + b)
                    .orElse("schema violation");
            throw new MassingException(
                    MassingErrorCode.BRIEF_EXTRACTION_FAILED,
                    "schema=" + detail);
        }
    }

    private static JsonSchema loadSchema() {
        try (InputStream in = ProgramJsonValidator.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(
                        "programJson schema resource not found at " + SCHEMA_RESOURCE);
            }
            JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
            SchemaValidatorsConfig config = new SchemaValidatorsConfig();
            config.setFailFast(false);
            return factory.getSchema(in, config);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load programJson schema", e);
        }
    }
}
