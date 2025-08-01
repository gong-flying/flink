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

package org.apache.flink.runtime.metrics.groups;

import org.apache.flink.annotation.Internal;
import org.apache.flink.metrics.CharacterFilter;
import org.apache.flink.metrics.MetricGroup;
import org.apache.flink.metrics.groups.OperatorMetricGroup;
import org.apache.flink.runtime.jobgraph.OperatorID;
import org.apache.flink.runtime.metrics.MetricRegistry;
import org.apache.flink.runtime.metrics.dump.QueryScopeInfo;
import org.apache.flink.runtime.metrics.scope.ScopeFormat;

import java.util.Collections;
import java.util.Map;

import static org.apache.flink.util.Preconditions.checkNotNull;

/** Special {@link org.apache.flink.metrics.MetricGroup} representing an Operator. */
@Internal
public class InternalOperatorMetricGroup extends ComponentMetricGroup<TaskMetricGroup>
        implements OperatorMetricGroup {
    private final String operatorName;
    private final OperatorID operatorID;
    private final InternalOperatorIOMetricGroup ioMetrics;
    private final Map<String, String> additionalVariables;

    InternalOperatorMetricGroup(
            MetricRegistry registry,
            TaskMetricGroup parent,
            OperatorID operatorID,
            String operatorName,
            Map<String, String> additionalVariables) {
        super(
                registry,
                registry.getScopeFormats()
                        .getOperatorFormat()
                        .formatScope(checkNotNull(parent), operatorID, operatorName),
                parent);
        this.operatorID = operatorID;
        this.operatorName = operatorName;
        this.additionalVariables = additionalVariables;

        ioMetrics = new InternalOperatorIOMetricGroup(this);
    }

    // ------------------------------------------------------------------------

    public final TaskIOMetricGroup getTaskIOMetricGroup() {
        return parent.getIOMetricGroup();
    }

    public final MetricGroup getTaskMetricGroup() {
        return parent;
    }

    @Override
    protected QueryScopeInfo.OperatorQueryScopeInfo createQueryServiceMetricInfo(
            CharacterFilter filter) {
        return new QueryScopeInfo.OperatorQueryScopeInfo(
                this.parent.parent.jobId.toString(),
                this.parent.vertexId.toString(),
                this.parent.subtaskIndex,
                this.parent.attemptNumber(),
                filter.filterCharacters(this.operatorName));
    }

    /**
     * Returns the OperatorIOMetricGroup for this operator.
     *
     * @return OperatorIOMetricGroup for this operator.
     */
    @Override
    public InternalOperatorIOMetricGroup getIOMetricGroup() {
        return ioMetrics;
    }

    // ------------------------------------------------------------------------
    //  Component Metric Group Specifics
    // ------------------------------------------------------------------------

    @Override
    protected void putVariables(Map<String, String> variables) {
        variables.put(ScopeFormat.SCOPE_OPERATOR_ID, String.valueOf(operatorID));
        variables.put(ScopeFormat.SCOPE_OPERATOR_NAME, operatorName);
        variables.putAll(additionalVariables);
        // we don't enter the subtask_index as the task group does that already
    }

    @Override
    protected Iterable<? extends ComponentMetricGroup<?>> subComponents() {
        return Collections.emptyList();
    }

    @Override
    protected String getGroupName(CharacterFilter filter) {
        return "operator";
    }
}
