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

package io.crate.planner.node.ddl;

import io.crate.planner.node.PlanVisitor;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;

public class ESClusterUpdateSettingsNode extends DDLPlanNode {

    private final Settings persistentSettings;
    private final Settings transientSettings;

    public ESClusterUpdateSettingsNode(Settings persistentSettings,
                                       Settings transientSettings) {
        this.persistentSettings = persistentSettings;
        // always override transient settings with persistent ones, so they won't get overridden
        // on cluster settings merge, which prefers the transient ones over the persistent ones
        // which we don't
        this.transientSettings = ImmutableSettings.builder().put(persistentSettings).put(transientSettings).build();
    }

    public ESClusterUpdateSettingsNode(Settings persistentSettings) {
        this(persistentSettings, persistentSettings); // override stale transient settings too in that case
    }

    public Settings persistentSettings() {
        return persistentSettings;
    }

    public Settings transientSettings() {
        return transientSettings;
    }

    @Override
    public <C, R> R accept(PlanVisitor<C, R> visitor, C context) {
        return visitor.visitESClusterUpdateSettingsNode(this, context);
    }
}
