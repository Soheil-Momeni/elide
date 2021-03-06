/*
 * Copyright 2020, Yahoo Inc.
 * Licensed under the Apache License, Version 2.0
 * See LICENSE file in project root for terms.
 */
package com.yahoo.elide.datastores.aggregation.metadata.models;

import com.yahoo.elide.annotation.Exclude;
import com.yahoo.elide.annotation.Include;
import com.yahoo.elide.core.dictionary.EntityDictionary;
import com.yahoo.elide.datastores.aggregation.annotation.ColumnMeta;
import com.yahoo.elide.datastores.aggregation.annotation.MetricFormula;
import com.yahoo.elide.datastores.aggregation.query.QueryPlanResolver;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.util.HashSet;
import java.util.Set;
import javax.persistence.ManyToOne;

/**
 * Column which supports aggregation.
 */
@Include(rootLevel = false, type = "metric")
@Getter
@EqualsAndHashCode(callSuper = true)
@ToString
public class Metric extends Column {
    @ManyToOne
    @ToString.Exclude
    private final MetricFunction metricFunction;

    @Exclude
    @ToString.Exclude
    private final QueryPlanResolver queryPlanResolver;

    public Metric(Table table, String fieldName, EntityDictionary dictionary) {
        super(table, fieldName, dictionary);
        Class<?> tableClass = dictionary.getEntityClass(table.getName(), table.getVersion());

        ColumnMeta meta = dictionary.getAttributeOrRelationAnnotation(
                tableClass,
                ColumnMeta.class,
                fieldName);

        MetricFormula formula = dictionary.getAttributeOrRelationAnnotation(
                tableClass,
                MetricFormula.class,
                fieldName);

        if (formula != null) {
            this.metricFunction = constructMetricFunction(
                    constructColumnName(tableClass, fieldName, dictionary) + "[" + fieldName + "]",
                    meta == null ? null : meta.description(),
                    new HashSet<>());

            this.queryPlanResolver = dictionary.getInjector().instantiate(formula.queryPlan());
            dictionary.getInjector().inject(this.queryPlanResolver);

        } else {
            throw new IllegalStateException("Trying to construct metric field "
                    + getId() + " without @MetricFormula.");
        }
    }

    /**
     * Dynamically construct a metric function
     *
     * @param id metric function id
     * @param description meta description
     * @param arguments function arguments
     * @return a metric function instance
     */
    protected MetricFunction constructMetricFunction(String id,
                                                     String description,
                                                     Set<FunctionArgument> arguments) {
        return new MetricFunction(id, description, arguments);
    }
}
