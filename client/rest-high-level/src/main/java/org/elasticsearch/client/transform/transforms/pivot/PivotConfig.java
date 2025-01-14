/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.client.transform.transforms.pivot;

import org.elasticsearch.xcontent.ParseField;
import org.elasticsearch.xcontent.ConstructingObjectParser;
import org.elasticsearch.xcontent.ToXContentObject;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentParser;
import org.elasticsearch.search.aggregations.AggregatorFactories;

import java.io.IOException;
import java.util.Objects;

import static org.elasticsearch.xcontent.ConstructingObjectParser.constructorArg;
import static org.elasticsearch.xcontent.ConstructingObjectParser.optionalConstructorArg;

/**
 * Class describing how to pivot data via {@link GroupConfig} and {@link AggregationConfig} objects
 */
public class PivotConfig implements ToXContentObject {

    private static final ParseField GROUP_BY = new ParseField("group_by");
    private static final ParseField AGGREGATIONS = new ParseField("aggregations");
    private static final ParseField MAX_PAGE_SEARCH_SIZE = new ParseField("max_page_search_size");

    private final GroupConfig groups;
    private final AggregationConfig aggregationConfig;
    private final Integer maxPageSearchSize;

    private static final ConstructingObjectParser<PivotConfig, Void> PARSER = new ConstructingObjectParser<>(
        "pivot_config",
        true,
        args -> new PivotConfig((GroupConfig) args[0], (AggregationConfig) args[1], (Integer) args[2])
    );

    static {
        PARSER.declareObject(constructorArg(), (p, c) -> (GroupConfig.fromXContent(p)), GROUP_BY);
        PARSER.declareObject(optionalConstructorArg(), (p, c) -> AggregationConfig.fromXContent(p), AGGREGATIONS);
        PARSER.declareInt(optionalConstructorArg(), MAX_PAGE_SEARCH_SIZE);
    }

    public static PivotConfig fromXContent(final XContentParser parser) {
        return PARSER.apply(parser, null);
    }

    PivotConfig(GroupConfig groups, final AggregationConfig aggregationConfig, Integer maxPageSearchSize) {
        this.groups = groups;
        this.aggregationConfig = aggregationConfig;
        this.maxPageSearchSize = maxPageSearchSize;
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        builder.field(GROUP_BY.getPreferredName(), groups);
        builder.field(AGGREGATIONS.getPreferredName(), aggregationConfig);
        if (maxPageSearchSize != null) {
            builder.field(MAX_PAGE_SEARCH_SIZE.getPreferredName(), maxPageSearchSize);
        }
        builder.endObject();
        return builder;
    }

    public AggregationConfig getAggregationConfig() {
        return aggregationConfig;
    }

    public GroupConfig getGroupConfig() {
        return groups;
    }

    @Deprecated
    public Integer getMaxPageSearchSize() {
        return maxPageSearchSize;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }

        if (other == null || getClass() != other.getClass()) {
            return false;
        }

        final PivotConfig that = (PivotConfig) other;

        return Objects.equals(this.groups, that.groups)
            && Objects.equals(this.aggregationConfig, that.aggregationConfig)
            && Objects.equals(this.maxPageSearchSize, that.maxPageSearchSize);
    }

    @Override
    public int hashCode() {
        return Objects.hash(groups, aggregationConfig, maxPageSearchSize);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private GroupConfig groups;
        private AggregationConfig aggregationConfig;
        private Integer maxPageSearchSize;

        /**
         * Set how to group the source data
         * @param groups The configuration describing how to group and pivot the source data
         * @return the {@link Builder} with the interval set.
         */
        public Builder setGroups(GroupConfig groups) {
            this.groups = groups;
            return this;
        }

        /**
         * Set the aggregated fields to include in the pivot config
         * @param aggregationConfig The configuration describing the aggregated fields
         * @return the {@link Builder} with the aggregations set.
         */
        public Builder setAggregationConfig(AggregationConfig aggregationConfig) {
            this.aggregationConfig = aggregationConfig;
            return this;
        }

        /**
         * Set the aggregated fields to include in the pivot config
         * @param aggregations The aggregated field builders
         * @return the {@link Builder} with the aggregations set.
         */
        public Builder setAggregations(AggregatorFactories.Builder aggregations) {
            this.aggregationConfig = new AggregationConfig(aggregations);
            return this;
        }

        /**
         * Sets the paging maximum paging maxPageSearchSize that date frame transform can use when
         * pulling the data from the source index.
         *
         * If OOM is triggered, the paging maxPageSearchSize is dynamically reduced so that the transform can continue to gather data.
         * Deprecated, use {@link org.elasticsearch.client.transform.transforms.SettingsConfig.Builder#setMaxPageSearchSize} instead
         * @param maxPageSearchSize Integer value between 10 and 10_000
         * @return the {@link Builder} with the paging maxPageSearchSize set.
         */
        @Deprecated
        public Builder setMaxPageSearchSize(Integer maxPageSearchSize) {
            this.maxPageSearchSize = maxPageSearchSize;
            return this;
        }

        public PivotConfig build() {
            return new PivotConfig(groups, aggregationConfig, maxPageSearchSize);
        }
    }
}
