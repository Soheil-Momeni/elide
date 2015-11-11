/*
 * Copyright 2015, Yahoo Inc.
 * Licensed under the Apache License, Version 2.0
 * See LICENSE file in project root for terms.
 */
package com.yahoo.elide.utils.coerse.converters;

import com.yahoo.elide.core.exceptions.InvalidAttributeException;
import org.apache.commons.beanutils.Converter;
import org.apache.commons.lang3.ClassUtils;

/**
 * Converter to Enum
 */
public class ToEnumConverter implements Converter {
    /**
     * Convert value to Enum
     * @param cls enum to convert to
     * @param value value to convert
     * @param <T>
     * @return
     */
    @Override
    public <T> T convert(Class<T> cls, Object value) {

        try {
            if (ClassUtils.isAssignable(value.getClass(), String.class)) {
                return stringToEnum(cls, (String) value);
            } else if (ClassUtils.isAssignable(value.getClass(), Integer.class, true)) {
                return intToEnum(cls, (Integer) value);
            } else {
               throw new UnsupportedOperationException(value.getClass().getSimpleName() + " to Enum no supported");
            }
        } catch (IndexOutOfBoundsException | ReflectiveOperationException
                | UnsupportedOperationException | IllegalArgumentException e) {
            throw new InvalidAttributeException("Unknown " + cls.getSimpleName() + " value " + value);
        }
    }

    /**
     * Convert digit to enum
     * @param cls enum to convert to
     * @param value value to convert
     * @param <T>
     * @return
     * @throws ReflectiveOperationException
     */
    private <T> T intToEnum(Class<?> cls, Integer value) throws ReflectiveOperationException {
        Object[] values = (Object[]) cls.getMethod("values").invoke(null, (Object[]) null);
        return (T) values[value];
    }

    /**
     * Convert string to enum
     * @param cls enum to convert to
     * @param value value to convert
     * @param <T>
     * @return
     */
    private <T> T stringToEnum(Class<?> cls, String value) {
        Enum e = Enum.valueOf((Class<Enum>) cls, value);
        return (T) e;
    }
}
