/*
 * Licensed to CRATE Technology GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */

package io.crate.operation.scalar.geo;

import com.google.common.base.Preconditions;
import com.google.common.collect.Sets;
import io.crate.metadata.*;
import io.crate.operation.Input;
import io.crate.operation.scalar.ScalarFunctionModule;
import io.crate.planner.DataTypeVisitor;
import io.crate.planner.symbol.Function;
import io.crate.planner.symbol.Literal;
import io.crate.planner.symbol.Symbol;
import io.crate.planner.symbol.SymbolFormatter;
import io.crate.types.ArrayType;
import io.crate.types.DataType;
import io.crate.types.DataTypes;
import org.elasticsearch.common.geo.GeoDistance;
import org.elasticsearch.common.unit.DistanceUnit;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

public class DistanceFunction implements Scalar<Double, Object> {

    public static final String NAME = "distance";

    private final FunctionInfo info;
    private final static FunctionInfo geoPointInfo =
            genInfo(Arrays.<DataType>asList(DataTypes.GEO_POINT, DataTypes.GEO_POINT));

    public static void register(ScalarFunctionModule module) {
        module.register(NAME, new Resolver());
    }

    private static FunctionInfo genInfo(List<DataType> argumentTypes) {
        return new FunctionInfo(new FunctionIdent(NAME, argumentTypes), DataTypes.DOUBLE);
    }

    DistanceFunction(FunctionInfo info) {
        this.info = info;
    }

    @Override
    public FunctionInfo info() {
        return info;
    }

    @Override
    public Double evaluate(Input[] args) {
        assert args.length == 2;
        return evaluate(args[0], args[1]);
    }

    public Double evaluate(Input arg1, Input arg2) {
        Object value1 = arg1.value();
        if (value1 == null) {
            return null;
        }
        Object value2 = arg2.value();
        if (value2 == null) {
            return null;
        }

        assert value1 instanceof Double[];
        assert value2 instanceof Double[];

        Double[] p1 = (Double[]) value1;
        Double[] p2 = (Double[]) value2;

        assert p1.length == 2;
        assert p2.length == 2;

        return GeoDistance.SLOPPY_ARC.calculate(p1[0], p1[1], p2[0], p2[1], DistanceUnit.METERS);
    }

    @Override
    public Symbol normalizeSymbol(Function symbol) {
        Symbol arg1 = symbol.arguments().get(0);
        Symbol arg2 = symbol.arguments().get(1);
        DataType arg1Type = DataTypeVisitor.fromSymbol(arg1);
        DataType arg2Type = DataTypeVisitor.fromSymbol(arg2);

        boolean literalConverted = false;
        short numLiterals = 0;

        if (arg1.symbolType().isValueSymbol()) {
            numLiterals++;
            if (!arg1Type.equals(DataTypes.GEO_POINT)) {
                literalConverted = true;
                arg1 = Literal.toLiteral(arg1, DataTypes.GEO_POINT);
            }
        } else {
            validateType(arg1, arg1Type);
        }

        if (arg2.symbolType().isValueSymbol()) {
            numLiterals++;
            if (!arg2Type.equals(DataTypes.GEO_POINT)) {
                literalConverted = true;
                arg2 = Literal.toLiteral(arg2, DataTypes.GEO_POINT);
            }
        } else {
            validateType(arg2, arg2Type);
        }

        if (numLiterals == 2) {
            return Literal.newLiteral(evaluate((Input) arg1, (Input) arg2));
        }

        if (literalConverted) {
            return new Function(geoPointInfo, Arrays.asList(arg1, arg2));
        }
        return symbol;
    }

    private void validateType(Symbol symbol, DataType dataType) {
        if (!dataType.equals(DataTypes.GEO_POINT)) {
            throw new IllegalArgumentException(SymbolFormatter.format(
                    "Cannot convert \"%s\" to a geo point", symbol));
        }
    }

    static class Resolver implements DynamicFunctionResolver {

        private final static Set<DataType> ALLOWED_TYPES = Sets.<DataType>newHashSet(
                DataTypes.STRING, DataTypes.GEO_POINT, new ArrayType(DataTypes.DOUBLE)
        );

        @Override
        public FunctionImplementation<Function> getForTypes(List<DataType> dataTypes) throws IllegalArgumentException {
            Preconditions.checkArgument(dataTypes.size() == 2,
                    "%s takes 2 arguments, not %s", NAME, dataTypes.size());
            validateType(dataTypes.get(0));
            validateType(dataTypes.get(1));
            return new DistanceFunction(genInfo(dataTypes));
        }

        private void validateType(DataType dataType) {
            if (!ALLOWED_TYPES.contains(dataType)) {
                throw new IllegalArgumentException(String.format(
                        "%s can't handle arguments of type \"%s\"", NAME, dataType.getName()));

            }
        }
    }
}
