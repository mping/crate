/*
 * Licensed to CRATE Technology GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
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
package io.crate.operation.scalar;

import com.google.common.collect.ImmutableList;
import io.crate.metadata.FunctionIdent;
import io.crate.metadata.FunctionInfo;
import io.crate.operation.Input;
import io.crate.planner.symbol.*;
import io.crate.types.DataType;
import io.crate.types.DataTypes;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.common.rounding.DateTimeUnit;
import org.joda.time.DateTimeZone;

public class DateTruncTimeZoneAwareFunction extends BaseDateTruncFunction {

    public static void register(ScalarFunctionModule module) {
        module.register(new DateTruncTimeZoneAwareFunction(new FunctionInfo(
                new FunctionIdent(NAME, ImmutableList.<DataType>of(
                        DataTypes.STRING, DataTypes.STRING, DataTypes.TIMESTAMP)),
                DataTypes.TIMESTAMP)
        ));
        module.register(new DateTruncTimeZoneAwareFunction(new FunctionInfo(
                new FunctionIdent(NAME, ImmutableList.<DataType>of(
                        DataTypes.STRING, DataTypes.STRING, DataTypes.LONG)),
                DataTypes.TIMESTAMP)
        ));
        module.register(new DateTruncTimeZoneAwareFunction(new FunctionInfo(
                new FunctionIdent(NAME, ImmutableList.<DataType>of(
                        DataTypes.STRING, DataTypes.STRING, DataTypes.STRING)),
                DataTypes.TIMESTAMP)
        ));
    }

    public DateTruncTimeZoneAwareFunction(FunctionInfo info) {
        super(info);
    }

    @Override
    public Symbol normalizeSymbol(Function symbol) {
        assert (symbol.arguments().size() == 3);

        Literal interval = (Literal) symbol.arguments().get(0);
        isValidInterval(interval, symbol);

        Literal timezone = (Literal) symbol.arguments().get(1);
        try {
            // will throw an IllegalArgumentException if time zone is invalid.
            parseZone((BytesRef)timezone.value());
        } catch (IllegalArgumentException e) {
            // catch and throw our own exception
            throw new IllegalArgumentException(SymbolFormatter.format("invalid time zone format %s for '%s'", timezone, symbol));
        }

        Symbol tsSymbol = symbol.arguments().get(2);
        if (tsSymbol.symbolType().isValueSymbol()) {
            return Literal.newLiteral(
                    DataTypes.TIMESTAMP,
                    evaluate((BytesRef)interval.value(),
                            (BytesRef)timezone.value(),
                            DataTypes.TIMESTAMP.value(((Input) tsSymbol).value()))
            );
        } else {
            assert tsSymbol instanceof DataTypeSymbol;
            if ( !((DataTypeSymbol) tsSymbol).valueType().equals(DataTypes.TIMESTAMP)) {
                throw new IllegalArgumentException(SymbolFormatter.format(
                        "The argument \"%s\" given to the date_trunc function has an invalid data type",
                        tsSymbol));
            }
        }

        return symbol;
    }

    private Long evaluate(BytesRef interval, BytesRef timezone, Long value) {
        assert interval != null && timezone != null;
        if (value == null) {
            return null;
        }
        DateTimeUnit fieldParser = DATE_FIELD_PARSERS.get(interval);
        assert fieldParser != null;
        DateTimeZone tz = parseZone(timezone);
        assert tz != null;
        return truncate(fieldParser, value, tz);
    }

    @Override
    public Long evaluate(Input[] args) {
        assert (args.length == 3);
        return evaluate((BytesRef)args[0].value(), (BytesRef)args[1].value(), (Long)args[2].value());
    }

    private DateTimeZone parseZone(BytesRef zone) throws IllegalArgumentException {
        String text = zone.utf8ToString();
        int index = text.indexOf(':');
        if (index != -1) {
            int beginIndex = text.charAt(0) == '+' ? 1 : 0;
            // format like -02:30
            return DateTimeZone.forOffsetHoursMinutes(
                    Integer.parseInt(text.substring(beginIndex, index)),
                    Integer.parseInt(text.substring(index + 1))
            );
        } else {
            // id, listed here: http://joda-time.sourceforge.net/timezones.html
            // or here: http://www.joda.org/joda-time/timezones.html
            return DateTimeZone.forID(text);
        }
    }

}
