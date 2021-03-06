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

package io.crate.analyze;

import io.crate.metadata.MetaDataModule;
import io.crate.operation.operator.OperatorModule;
import org.elasticsearch.common.inject.Module;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class SetAnalyzerTest extends BaseAnalyzerTest {

    @Override
    protected List<Module> getModules() {
        List<Module> modules = super.getModules();
        modules.addAll(Arrays.<Module>asList(
                        new TestModule(),
                        new MetaDataModule(),
                        new OperatorModule())
        );
        return modules;
    }

    @Test
    public void testSet() throws Exception {
        SetAnalysis analysis = (SetAnalysis) analyze("SET GLOBAL PERSISTENT operations_log_size=1");
        assertThat(analysis.isPersistent(), is(true));
        assertThat(analysis.settings().toDelimitedString(','), is("cluster.operations_log_size=1,"));

        analysis = (SetAnalysis) analyze("SET GLOBAL TRANSIENT jobs_log_size=2");
        assertThat(analysis.isPersistent(), is(false));
        assertThat(analysis.settings().toDelimitedString(','), is("cluster.jobs_log_size=2,"));

        analysis = (SetAnalysis) analyze("SET GLOBAL TRANSIENT collect_stats=false, operations_log_size=0, jobs_log_size=0");
        assertThat(analysis.isPersistent(), is(false));
        assertThat(analysis.settings().toDelimitedString(','), is("cluster.collect_stats=false,cluster.operations_log_size=0,cluster.jobs_log_size=0,"));
    }

    @Test
    public void testSetFullQualified() throws Exception {
        SetAnalysis analysis = (SetAnalysis) analyze("SET GLOBAL PERSISTENT cluster['operations_log_size']=1");
        assertThat(analysis.isPersistent(), is(true));
        assertThat(analysis.settings().toDelimitedString(','), is("cluster.operations_log_size=1,"));
    }

    @Test
    public void testSetParameter() throws Exception {
        SetAnalysis analysis = (SetAnalysis) analyze("SET GLOBAL PERSISTENT operations_log_size=?, jobs_log_size=?", new Object[]{1, 2});
        assertThat(analysis.isPersistent(), is(true));
        assertThat(analysis.settings().toDelimitedString(','), is("cluster.operations_log_size=1,cluster.jobs_log_size=2,"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSetParameterInvalidType() throws Exception {
        analyze("SET GLOBAL PERSISTENT operations_log_size=?", new Object[]{"foobar"});
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSetParameterInvalidBooleanType() throws Exception {
        analyze("SET GLOBAL PERSISTENT collect_stats=?", new Object[]{"foobar"});
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSetInvalidSetting() throws Exception {
        analyze("SET GLOBAL PERSISTENT forbidden=1");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSetInvalidValue() throws Exception {
        analyze("SET GLOBAL TRANSIENT jobs_log_size=-1");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSetInvalidValueType() throws Exception {
        analyze("SET GLOBAL TRANSIENT jobs_log_size='some value'");
    }
}
