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

package io.crate.types;

import io.crate.Streamer;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;

import java.io.IOException;

public class NullType extends DataType<Void> implements DataTypeFactory, Streamer<Void> {

    public static final int ID = 0;

    public static final NullType INSTANCE = new NullType();
    private NullType() {}

    @Override
    public int id() {
        return 0;
    }

    @Override
    public String getName() {
        return "null";
    }

    @Override
    public Streamer<?> streamer() {
        return this;
    }

    @Override
    public Void value(Object value) {
        return null;
    }

    @Override
    public boolean isConvertableTo(DataType other) {
        return true;
    }

    @Override
    public int compareValueTo(Void val1, Void val2) {
        return 0;
    }

    @Override
    public DataType<?> create() {
        return INSTANCE;
    }

    @Override
    public Void readValueFrom(StreamInput in) throws IOException {
        return null;
    }

    @Override
    public void writeValueTo(StreamOutput out, Object v) throws IOException {
    }
}
