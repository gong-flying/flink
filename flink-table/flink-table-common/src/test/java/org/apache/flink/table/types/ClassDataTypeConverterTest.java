/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.types;

import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.expressions.TimeIntervalUnit;
import org.apache.flink.table.types.logical.SymbolType;
import org.apache.flink.table.types.utils.ClassDataTypeConverter;
import org.apache.flink.types.Row;
import org.apache.flink.types.variant.BinaryVariant;
import org.apache.flink.types.variant.Variant;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nullable;

import java.math.BigDecimal;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.of;

/** Tests for {@link ClassDataTypeConverter}. */
class ClassDataTypeConverterTest {

    private static Stream<Arguments> testData() {
        return Stream.of(
                of(long.class, DataTypes.BIGINT().notNull().bridgedTo(long.class)),
                of(byte[].class, DataTypes.BYTES().nullable().bridgedTo(byte[].class)),
                of(Long.class, DataTypes.BIGINT().nullable().bridgedTo(Long.class)),
                of(
                        java.sql.Time.class,
                        DataTypes.TIME(0).nullable().bridgedTo(java.sql.Time.class)),
                of(
                        java.time.Duration.class,
                        DataTypes.INTERVAL(DataTypes.SECOND(9))
                                .bridgedTo(java.time.Duration.class)),
                of(
                        java.time.Period.class,
                        DataTypes.INTERVAL(DataTypes.YEAR(4), DataTypes.MONTH())
                                .bridgedTo(java.time.Period.class)),
                of(BigDecimal.class, null),
                of(
                        byte[][].class,
                        DataTypes.ARRAY(DataTypes.BYTES().nullable().bridgedTo(byte[].class))
                                .nullable()
                                .bridgedTo(byte[][].class)),
                of(
                        Byte[].class,
                        DataTypes.ARRAY(DataTypes.TINYINT().nullable().bridgedTo(Byte.class))
                                .nullable()
                                .bridgedTo(Byte[].class)),
                of(
                        Byte[][].class,
                        DataTypes.ARRAY(
                                        DataTypes.ARRAY(
                                                        DataTypes.TINYINT()
                                                                .nullable()
                                                                .bridgedTo(Byte.class))
                                                .nullable()
                                                .bridgedTo(Byte[].class))
                                .nullable()
                                .bridgedTo(Byte[][].class)),
                of(
                        Integer[].class,
                        DataTypes.ARRAY(DataTypes.INT().nullable().bridgedTo(Integer.class))
                                .nullable()
                                .bridgedTo(Integer[].class)),
                of(
                        int[].class,
                        DataTypes.ARRAY(DataTypes.INT().notNull().bridgedTo(int.class))
                                .nullable()
                                .bridgedTo(int[].class)),
                of(
                        TimeIntervalUnit.class,
                        new AtomicDataType(new SymbolType<>()).bridgedTo(TimeIntervalUnit.class)),
                of(Row.class, null),
                of(Variant.class, DataTypes.VARIANT()),
                of(BinaryVariant.class, DataTypes.VARIANT().bridgedTo(BinaryVariant.class)));
    }

    @ParameterizedTest(name = "[{index}] class: {0} type: {1}")
    @MethodSource("testData")
    void testClassToDataTypeConversion(Class<?> clazz, @Nullable DataType dataType) {
        if (dataType == null) {
            assertThat(ClassDataTypeConverter.extractDataType(clazz)).isEmpty();
        } else {
            assertThat(ClassDataTypeConverter.extractDataType(clazz)).contains(dataType);
        }
    }
}
