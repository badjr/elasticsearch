/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */
package org.elasticsearch.client.rollup.job.config;

import org.elasticsearch.client.Validatable;
import org.elasticsearch.client.ValidationException;
import org.elasticsearch.xcontent.ParseField;
import org.elasticsearch.xcontent.ConstructingObjectParser;
import org.elasticsearch.xcontent.ToXContentObject;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentParser;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static org.elasticsearch.xcontent.ConstructingObjectParser.constructorArg;

/**
 * The configuration object for the metrics portion of a rollup job config
 *
 * {
 *     "metrics": [
 *        {
 *            "field": "foo",
 *            "metrics": [ "min", "max", "sum"]
 *        },
 *        {
 *            "field": "bar",
 *            "metrics": [ "max" ]
 *        }
 *     ]
 * }
 */
public class MetricConfig implements Validatable, ToXContentObject {

    static final String NAME = "metrics";
    private static final String FIELD = "field";
    private static final String METRICS = "metrics";

    private static final ConstructingObjectParser<MetricConfig, Void> PARSER;
    static {
        PARSER = new ConstructingObjectParser<>(NAME, true, args -> {
            @SuppressWarnings("unchecked") List<String> metrics = (List<String>) args[1];
            return new MetricConfig((String) args[0], metrics);
        });
        PARSER.declareString(constructorArg(), new ParseField(FIELD));
        PARSER.declareStringArray(constructorArg(), new ParseField(METRICS));
    }

    private final String field;
    private final List<String> metrics;

    public MetricConfig(final String field, final List<String> metrics) {
        this.field = field;
        this.metrics = metrics;
    }

    @Override
    public Optional<ValidationException> validate() {
        final ValidationException validationException = new ValidationException();
        if (field == null || field.isEmpty()) {
            validationException.addValidationError("Field name is required");
        }
        if (metrics == null || metrics.isEmpty()) {
            validationException.addValidationError("Metrics must be a non-null, non-empty array of strings");
        }
        if (validationException.validationErrors().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(validationException);
    }

    /**
     * @return the name of the field used in the metric configuration. Never {@code null}.
     */
    public String getField() {
        return field;
    }

    /**
     * @return the names of the metrics used in the metric configuration. Never {@code null}.
     */
    public List<String> getMetrics() {
        return metrics;
    }

    @Override
    public XContentBuilder toXContent(final XContentBuilder builder, final Params params) throws IOException {
        builder.startObject();
        {
            builder.field(FIELD, field);
            builder.field(METRICS, metrics);
        }
        return builder.endObject();
    }

    @Override
    public boolean equals(final Object other) {
        if (this == other) {
            return true;
        }
        if (other == null || getClass() != other.getClass()) {
            return false;
        }
        final MetricConfig that = (MetricConfig) other;
        return Objects.equals(field, that.field) && Objects.equals(metrics, that.metrics);
    }

    @Override
    public int hashCode() {
        return Objects.hash(field, metrics);
    }

    public static MetricConfig fromXContent(final XContentParser parser) throws IOException {
        return PARSER.parse(parser, null);
    }
}
