/*
 * Copyright 2019, Yahoo Inc.
 * Licensed under the Apache License, Version 2.0
 * See LICENSE file in project root for terms.
 */
package com.yahoo.elide.datastores.aggregation.metadata.models;

import static com.yahoo.elide.datastores.aggregation.metadata.MetaDataStore.isMetricField;
import static com.yahoo.elide.datastores.aggregation.metadata.models.Column.getDataType;

import com.yahoo.elide.annotation.Exclude;
import com.yahoo.elide.annotation.Include;
import com.yahoo.elide.core.EntityDictionary;
import com.yahoo.elide.datastores.aggregation.annotation.Cardinality;
import com.yahoo.elide.datastores.aggregation.annotation.CardinalitySize;
import com.yahoo.elide.datastores.aggregation.annotation.Meta;
import com.yahoo.elide.datastores.aggregation.annotation.Temporal;
import com.yahoo.elide.datastores.aggregation.metadata.enums.TableTag;

import lombok.Data;
import lombok.ToString;

import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.persistence.Id;
import javax.persistence.OneToMany;

/**
 * Super class of all logical or physical tables.
 */
@Include(rootLevel = true, type = "table")
@Data
@ToString
public class Table {
    @Id
    private String name;

    private String longName;

    private String description;

    private String category;

    private CardinalitySize cardinality;

    @OneToMany
    @ToString.Exclude
    private Set<Column> columns;

    @OneToMany
    @ToString.Exclude
    private Set<Metric> metrics;

    @OneToMany
    @ToString.Exclude
    private Set<Dimension> dimensions;

    @ToString.Exclude
    private Set<TableTag> tableTags;

    @Exclude
    @ToString.Exclude
    private final Map<String, Column> columnMap;

    public Table(Class<?> cls, EntityDictionary dictionary) {
        if (!dictionary.getBindings().contains(cls)) {
            throw new IllegalArgumentException(
                    String.format("Table class {%s} is not defined in dictionary.", cls));
        }

        this.name = dictionary.getJsonAliasFor(cls);

        this.columns = constructColumns(cls, dictionary);
        this.columnMap = this.columns.stream().collect(Collectors.toMap(Column::getName, Function.identity()));

        this.metrics = this.columns.stream()
                .filter(col -> col instanceof Metric)
                .map(Metric.class::cast)
                .collect(Collectors.toSet());
        this.dimensions = this.columns.stream()
                .filter(col -> !(col instanceof Metric))
                .map(Dimension.class::cast)
                .collect(Collectors.toSet());

        Meta meta = cls.getAnnotation(Meta.class);
        if (meta != null) {
            this.longName = meta.longName();
            this.description = meta.description();
        }

        Cardinality cardinality = dictionary.getAnnotation(cls, Cardinality.class);
        if (cardinality != null) {
            this.cardinality = cardinality.size();
        }
    }

    /**
     * Construct all columns of this table.
     *
     * @param cls table class
     * @param dictionary dictionary contains the table class
     * @return all resolved column metadata
     */
    private Set<Column> constructColumns(Class<?> cls, EntityDictionary dictionary) {
        Set<Column> columns =  dictionary.getAllFields(cls).stream()
                .filter(field -> getDataType(cls, field, dictionary) != null)
                .map(field -> {
                    if (isMetricField(dictionary, cls, field)) {
                        return constructMetric(cls, field, dictionary);
                    } else if (dictionary.attributeOrRelationAnnotationExists(cls, field, Temporal.class)) {
                        return constructTimeDimension(cls, field, dictionary);
                    } else {
                        return constructDimension(cls, field, dictionary);
                    }
                })
                .collect(Collectors.toSet());

        // add id field if exists
        if (dictionary.getIdFieldName(cls) != null) {
            columns.add(constructDimension(cls, dictionary.getIdFieldName(cls), dictionary));
        }

        return columns;
    }

    /**
     * Construct a Metric instance.
     *
     * @param cls table class
     * @param fieldName field name
     * @param dictionary dictionary contains the table class
     * @return Metric metadata instance
     */
    protected Metric constructMetric(Class<?> cls, String fieldName, EntityDictionary dictionary) {
        return new Metric(cls, fieldName, dictionary);
    }

    /**
     * Construct a Dimension instance.
     *
     * @param cls table class
     * @param fieldName field name
     * @param dictionary dictionary contains the table class
     * @return Dimension metadata instance
     */
    protected TimeDimension constructTimeDimension(Class<?> cls, String fieldName, EntityDictionary dictionary) {
        return new TimeDimension(cls, fieldName, dictionary);
    }

    /**
     * Construct a TimeDimension instance.
     *
     * @param cls table class
     * @param fieldName field name
     * @param dictionary dictionary contains the table class
     * @return TimeDimension metadata instance
     */
    protected Dimension constructDimension(Class<?> cls, String fieldName, EntityDictionary dictionary) {
        return new Dimension(cls, fieldName, dictionary);
    }

    /**
     * Get all columns of a specific class, can be {@link Metric}, {@link TimeDimension} or {@link Dimension}.
     *
     * @param cls metadata class
     * @param <T> metadata class
     * @return columns as requested type if found
     */
    public <T extends Column> Set<T> getColumns(Class<T> cls) {
        return columns.stream()
                .filter(col -> cls.isAssignableFrom(col.getClass()))
                .map(cls::cast)
                .collect(Collectors.toSet());
    }

    /**
     * Get a field column as a specific class, can be {@link Metric}, {@link TimeDimension} or {@link Dimension}.
     *
     * @param cls metadata class
     * @param fieldName logical column name
     * @param <T> metadata class
     * @return column as requested type if found
     */
    protected final <T extends Column> T getColumn(Class<T> cls, String fieldName) {
        Column column = columnMap.get(fieldName);
        return column != null && cls.isAssignableFrom(column.getClass()) ? cls.cast(column) : null;
    }

    public Metric getMetric(String fieldName) {
        return getColumn(Metric.class, fieldName);
    }

    public Dimension getDimension(String fieldName) {
        return getColumn(Dimension.class, fieldName);
    }

    public TimeDimension getTimeDimension(String fieldName) {
        return getColumn(TimeDimension.class, fieldName);
    }

    public boolean isMetric(String fieldName) {
        return getMetric(fieldName) != null;
    }
}