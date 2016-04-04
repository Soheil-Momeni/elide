/*
 * Copyright 2016, Yahoo Inc.
 * Licensed under the Apache License, Version 2.0
 * See LICENSE file in project root for terms.
 */
package com.yahoo.elide.core;

import com.yahoo.elide.annotation.ReadPermission;
import com.yahoo.elide.parsers.expression.CriterionExpressionVisitor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.antlr.v4.runtime.tree.ParseTree;

import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Scope for filter processing.  Contains requestScope and checks.
 */
@Slf4j
public class FilterScope {

    @Getter private final RequestScope requestScope;
    @Getter private final ParseTree permissions;

    public FilterScope(RequestScope requestScope, Class<?> resourceClass) {
        this.requestScope = requestScope;
        this.permissions = requestScope.getDictionary().getPermissionsForClass(resourceClass, ReadPermission.class);
    }

    public <T> T getCriterion(Function<T, T> criterionNegater,
                              BiFunction<T, T, T> andCriterionJoiner,
                              BiFunction<T, T, T> orCriterionJoiner) {
        if (permissions == null) {
            return null;
        }

        CriterionExpressionVisitor<T> visitor = new CriterionExpressionVisitor<>(
                requestScope,
                requestScope.getDictionary(),
                criterionNegater,
                orCriterionJoiner,
                andCriterionJoiner
        );
        return visitor.visit(permissions);
    }

    /**
     * Returns true if pagination limits were added to this query.
     *
     * NOTE: This method is often used in GET transaction implementations
     *
     * @return true if there is pagination filtering
     */
    public boolean hasSortingRules() {
        return !requestScope.getSorting().isDefaultInstance();
    }

    /**
     * Returns true if pagination limits were added to this query.
     *
     * NOTE: This method is often used in GET transaction implementations
     *
     * @return true if there is pagination filtering
     */
    public boolean hasPagination() {
        return !requestScope.getPagination().isDefaultInstance();
    }

    /**
     * Returns true if filters are applied to this query.
     *
     * NOTE: This method is often used in transaction implementations.
     *
     * @return true if there are filters
     */
    public boolean hasPredicates() {
        return !requestScope.getPredicates().isEmpty();
    }
}
