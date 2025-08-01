/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.streaming.api.graph;

import org.apache.flink.annotation.Internal;
import org.apache.flink.api.common.BatchShuffleMode;
import org.apache.flink.api.common.ExecutionConfig;
import org.apache.flink.api.common.RuntimeExecutionMode;
import org.apache.flink.api.common.cache.DistributedCache;
import org.apache.flink.api.common.operators.ResourceSpec;
import org.apache.flink.api.common.operators.util.SlotSharingGroupUtils;
import org.apache.flink.api.connector.source.Boundedness;
import org.apache.flink.api.dag.Transformation;
import org.apache.flink.configuration.BatchExecutionOptions;
import org.apache.flink.configuration.CheckpointingOptions;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.ExecutionOptions;
import org.apache.flink.configuration.JobManagerOptions;
import org.apache.flink.configuration.MemorySize;
import org.apache.flink.configuration.PipelineOptions;
import org.apache.flink.configuration.ReadableConfig;
import org.apache.flink.configuration.StateChangelogOptions;
import org.apache.flink.runtime.clusterframework.types.ResourceProfile;
import org.apache.flink.runtime.jobgraph.ExecutionPlanUtils;
import org.apache.flink.runtime.jobgraph.JobType;
import org.apache.flink.runtime.jobgraph.SavepointRestoreSettings;
import org.apache.flink.runtime.state.KeyGroupRangeAssignment;
import org.apache.flink.streaming.api.environment.CheckpointConfig;
import org.apache.flink.streaming.api.lineage.LineageGraph;
import org.apache.flink.streaming.api.lineage.LineageGraphUtils;
import org.apache.flink.streaming.api.operators.sorted.state.BatchExecutionCheckpointStorage;
import org.apache.flink.streaming.api.operators.sorted.state.BatchExecutionInternalTimeServiceManager;
import org.apache.flink.streaming.api.operators.sorted.state.BatchExecutionStateBackend;
import org.apache.flink.streaming.api.transformations.BroadcastStateTransformation;
import org.apache.flink.streaming.api.transformations.CacheTransformation;
import org.apache.flink.streaming.api.transformations.GlobalCommitterTransform;
import org.apache.flink.streaming.api.transformations.KeyedBroadcastStateTransformation;
import org.apache.flink.streaming.api.transformations.KeyedMultipleInputTransformation;
import org.apache.flink.streaming.api.transformations.LegacySinkTransformation;
import org.apache.flink.streaming.api.transformations.LegacySourceTransformation;
import org.apache.flink.streaming.api.transformations.MultipleInputTransformation;
import org.apache.flink.streaming.api.transformations.OneInputTransformation;
import org.apache.flink.streaming.api.transformations.PartitionTransformation;
import org.apache.flink.streaming.api.transformations.PhysicalTransformation;
import org.apache.flink.streaming.api.transformations.ReduceTransformation;
import org.apache.flink.streaming.api.transformations.SideOutputTransformation;
import org.apache.flink.streaming.api.transformations.SinkTransformation;
import org.apache.flink.streaming.api.transformations.SourceTransformation;
import org.apache.flink.streaming.api.transformations.SourceTransformationWrapper;
import org.apache.flink.streaming.api.transformations.StubTransformation;
import org.apache.flink.streaming.api.transformations.TimestampsAndWatermarksTransformation;
import org.apache.flink.streaming.api.transformations.TwoInputTransformation;
import org.apache.flink.streaming.api.transformations.UnionTransformation;
import org.apache.flink.streaming.api.transformations.WithBoundedness;
import org.apache.flink.streaming.runtime.translators.BroadcastStateTransformationTranslator;
import org.apache.flink.streaming.runtime.translators.CacheTransformationTranslator;
import org.apache.flink.streaming.runtime.translators.GlobalCommitterTransformationTranslator;
import org.apache.flink.streaming.runtime.translators.KeyedBroadcastStateTransformationTranslator;
import org.apache.flink.streaming.runtime.translators.LegacySinkTransformationTranslator;
import org.apache.flink.streaming.runtime.translators.LegacySourceTransformationTranslator;
import org.apache.flink.streaming.runtime.translators.MultiInputTransformationTranslator;
import org.apache.flink.streaming.runtime.translators.OneInputTransformationTranslator;
import org.apache.flink.streaming.runtime.translators.PartitionTransformationTranslator;
import org.apache.flink.streaming.runtime.translators.ReduceTransformationTranslator;
import org.apache.flink.streaming.runtime.translators.SideOutputTransformationTranslator;
import org.apache.flink.streaming.runtime.translators.SinkTransformationTranslator;
import org.apache.flink.streaming.runtime.translators.SourceTransformationTranslator;
import org.apache.flink.streaming.runtime.translators.StubTransformationTranslator;
import org.apache.flink.streaming.runtime.translators.TimestampsAndWatermarksTransformationTranslator;
import org.apache.flink.streaming.runtime.translators.TwoInputTransformationTranslator;
import org.apache.flink.streaming.runtime.translators.UnionTransformationTranslator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.apache.flink.util.Preconditions.checkNotNull;
import static org.apache.flink.util.Preconditions.checkState;

/**
 * A generator that generates a {@link StreamGraph} from a graph of {@link Transformation}s.
 *
 * <p>This traverses the tree of {@code Transformations} starting from the sinks. At each
 * transformation we recursively transform the inputs, then create a node in the {@code StreamGraph}
 * and add edges from the input Nodes to our newly created node. The transformation methods return
 * the IDs of the nodes in the StreamGraph that represent the input transformation. Several IDs can
 * be returned to be able to deal with feedback transformations and unions.
 *
 * <p>Partitioning, split/select and union don't create actual nodes in the {@code StreamGraph}. For
 * these, we create a virtual node in the {@code StreamGraph} that holds the specific property, i.e.
 * partitioning, selector and so on. When an edge is created from a virtual node to a downstream
 * node the {@code StreamGraph} resolved the id of the original node and creates an edge in the
 * graph with the desired property. For example, if you have this graph:
 *
 * <pre>
 *     Map-1 -&gt; HashPartition-2 -&gt; Map-3
 * </pre>
 *
 * <p>where the numbers represent transformation IDs. We first recurse all the way down. {@code
 * Map-1} is transformed, i.e. we create a {@code StreamNode} with ID 1. Then we transform the
 * {@code HashPartition}, for this, we create virtual node of ID 4 that holds the property {@code
 * HashPartition}. This transformation returns the ID 4. Then we transform the {@code Map-3}. We add
 * the edge {@code 4 -> 3}. The {@code StreamGraph} resolved the actual node with ID 1 and creates
 * and edge {@code 1 -> 3} with the property HashPartition.
 */
@Internal
public class StreamGraphGenerator {

    private static final Logger LOG = LoggerFactory.getLogger(StreamGraphGenerator.class);

    public static final int DEFAULT_LOWER_BOUND_MAX_PARALLELISM =
            KeyGroupRangeAssignment.DEFAULT_LOWER_BOUND_MAX_PARALLELISM;

    public static final String DEFAULT_STREAMING_JOB_NAME = "Flink Streaming Job";

    public static final String DEFAULT_BATCH_JOB_NAME = "Flink Batch Job";

    public static final String DEFAULT_SLOT_SHARING_GROUP = "default";

    private final List<Transformation<?>> transformations;

    private final ExecutionConfig executionConfig;

    private final CheckpointConfig checkpointConfig;

    private final Configuration configuration;

    // Records the slot sharing groups and their corresponding fine-grained ResourceProfile
    private final Map<String, ResourceProfile> slotSharingGroupResources = new HashMap<>();

    private SavepointRestoreSettings savepointRestoreSettings;

    private boolean shouldExecuteInBatchMode;

    @SuppressWarnings("rawtypes")
    private static final Map<
                    Class<? extends Transformation>,
                    TransformationTranslator<?, ? extends Transformation>>
            translatorMap;

    static {
        @SuppressWarnings("rawtypes")
        Map<Class<? extends Transformation>, TransformationTranslator<?, ? extends Transformation>>
                tmp = new HashMap<>();
        tmp.put(OneInputTransformation.class, new OneInputTransformationTranslator<>());
        tmp.put(TwoInputTransformation.class, new TwoInputTransformationTranslator<>());
        tmp.put(MultipleInputTransformation.class, new MultiInputTransformationTranslator<>());
        tmp.put(KeyedMultipleInputTransformation.class, new MultiInputTransformationTranslator<>());
        tmp.put(SourceTransformation.class, new SourceTransformationTranslator<>());
        tmp.put(SinkTransformation.class, new SinkTransformationTranslator<>());
        tmp.put(GlobalCommitterTransform.class, new GlobalCommitterTransformationTranslator<>());
        tmp.put(LegacySinkTransformation.class, new LegacySinkTransformationTranslator<>());
        tmp.put(LegacySourceTransformation.class, new LegacySourceTransformationTranslator<>());
        tmp.put(UnionTransformation.class, new UnionTransformationTranslator<>());
        tmp.put(StubTransformation.class, new StubTransformationTranslator<>());
        tmp.put(PartitionTransformation.class, new PartitionTransformationTranslator<>());
        tmp.put(SideOutputTransformation.class, new SideOutputTransformationTranslator<>());
        tmp.put(ReduceTransformation.class, new ReduceTransformationTranslator<>());
        tmp.put(
                TimestampsAndWatermarksTransformation.class,
                new TimestampsAndWatermarksTransformationTranslator<>());
        tmp.put(BroadcastStateTransformation.class, new BroadcastStateTransformationTranslator<>());
        tmp.put(
                KeyedBroadcastStateTransformation.class,
                new KeyedBroadcastStateTransformationTranslator<>());
        tmp.put(CacheTransformation.class, new CacheTransformationTranslator<>());
        translatorMap = Collections.unmodifiableMap(tmp);
    }

    // This is used to assign a unique ID to iteration source/sink
    protected static Integer iterationIdCounter = 0;

    public static int getNewIterationNodeId() {
        iterationIdCounter--;
        return iterationIdCounter;
    }

    private StreamGraph streamGraph;

    // Keep track of which Transforms we have already transformed, this is necessary because
    // we have loops, i.e. feedback edges.
    private Map<Transformation<?>, Collection<Integer>> alreadyTransformed;

    public StreamGraphGenerator(
            final List<Transformation<?>> transformations,
            final ExecutionConfig executionConfig,
            final CheckpointConfig checkpointConfig) {
        this(transformations, executionConfig, checkpointConfig, new Configuration());
    }

    public StreamGraphGenerator(
            List<Transformation<?>> transformations,
            ExecutionConfig executionConfig,
            CheckpointConfig checkpointConfig,
            Configuration configuration) {
        this.transformations = checkNotNull(transformations);
        this.executionConfig = checkNotNull(executionConfig);
        this.checkpointConfig = new CheckpointConfig(checkpointConfig);
        this.configuration = checkNotNull(configuration);
        this.savepointRestoreSettings = SavepointRestoreSettings.fromConfiguration(configuration);
    }

    /**
     * Specify fine-grained resource requirements for slot sharing groups.
     *
     * <p>Note that a slot sharing group hints the scheduler that the grouped operators CAN be
     * deployed into a shared slot. There's no guarantee that the scheduler always deploy the
     * grouped operators together. In cases grouped operators are deployed into separate slots, the
     * slot resources will be derived from the specified group requirements.
     */
    public StreamGraphGenerator setSlotSharingGroupResource(
            Map<String, ResourceProfile> slotSharingGroupResources) {
        slotSharingGroupResources.forEach(
                (name, profile) -> {
                    if (!profile.equals(ResourceProfile.UNKNOWN)) {
                        this.slotSharingGroupResources.put(name, profile);
                    }
                });
        return this;
    }

    public void setSavepointRestoreSettings(SavepointRestoreSettings savepointRestoreSettings) {
        this.savepointRestoreSettings = savepointRestoreSettings;
    }

    public StreamGraph generate() {
        streamGraph =
                new StreamGraph(
                        configuration, executionConfig, checkpointConfig, savepointRestoreSettings);
        shouldExecuteInBatchMode = shouldExecuteInBatchMode();
        configureStreamGraph(streamGraph);

        alreadyTransformed = new IdentityHashMap<>();

        for (Transformation<?> transformation : transformations) {
            transform(transformation);
        }
        streamGraph.setSlotSharingGroupResource(slotSharingGroupResources);

        setFineGrainedGlobalStreamExchangeMode(streamGraph);

        LineageGraph lineageGraph = LineageGraphUtils.convertToLineageGraph(transformations);
        streamGraph.setLineageGraph(lineageGraph);

        for (StreamNode node : streamGraph.getStreamNodes()) {
            if (node.getInEdges().stream()
                    .anyMatch(e -> !e.getPartitioner().isSupportsUnalignedCheckpoint())) {
                for (StreamEdge edge : node.getInEdges()) {
                    edge.setSupportsUnalignedCheckpoints(false);
                }
            }
        }

        final Map<String, DistributedCache.DistributedCacheEntry> distributedCacheEntries =
                ExecutionPlanUtils.prepareUserArtifactEntries(
                        Optional.ofNullable(configuration.get(PipelineOptions.CACHED_FILES))
                                .map(DistributedCache::parseCachedFilesFromString)
                                .orElse(new ArrayList<>())
                                .stream()
                                .collect(Collectors.toMap(e -> e.f0, e -> e.f1)),
                        streamGraph.getJobID());

        for (Map.Entry<String, DistributedCache.DistributedCacheEntry> entry :
                distributedCacheEntries.entrySet()) {
            streamGraph.addUserArtifact(entry.getKey(), entry.getValue());
        }

        streamGraph.serializeAndSaveWatermarkDeclarations();

        final StreamGraph builtStreamGraph = streamGraph;

        alreadyTransformed.clear();
        alreadyTransformed = null;
        streamGraph = null;

        return builtStreamGraph;
    }

    private void setDynamic(final StreamGraph graph) {
        Optional<JobManagerOptions.SchedulerType> schedulerTypeOptional =
                executionConfig.getSchedulerType();
        boolean dynamic =
                shouldExecuteInBatchMode
                        && schedulerTypeOptional.orElse(
                                        JobManagerOptions.SchedulerType.AdaptiveBatch)
                                == JobManagerOptions.SchedulerType.AdaptiveBatch;
        graph.setDynamic(dynamic);
    }

    private void configureStreamGraph(final StreamGraph graph) {
        checkNotNull(graph);

        graph.setVertexDescriptionMode(configuration.get(PipelineOptions.VERTEX_DESCRIPTION_MODE));
        graph.setVertexNameIncludeIndexPrefix(
                configuration.get(PipelineOptions.VERTEX_NAME_INCLUDE_INDEX_PREFIX));
        graph.setAutoParallelismEnabled(
                configuration.get(BatchExecutionOptions.ADAPTIVE_AUTO_PARALLELISM_ENABLED));
        graph.setEnableCheckpointsAfterTasksFinish(
                configuration.get(CheckpointingOptions.ENABLE_CHECKPOINTS_AFTER_TASKS_FINISH));
        setDynamic(graph);

        if (shouldExecuteInBatchMode) {
            configureStreamGraphBatch(graph);
            configuration.set(ExecutionOptions.BUFFER_TIMEOUT_ENABLED, false);
        } else {
            configureStreamGraphStreaming(graph);
        }
    }

    private void configureStreamGraphBatch(final StreamGraph graph) {
        graph.setJobType(JobType.BATCH);
        graph.setJobName(deriveJobName(DEFAULT_BATCH_JOB_NAME));

        if (checkpointConfig.isCheckpointingEnabled()) {
            LOG.info(
                    "Disabled Checkpointing. Checkpointing is not supported and not needed when executing jobs in BATCH mode.");
            checkpointConfig.disableCheckpointing();
        }
        setBatchStateBackendAndTimerService(graph);

        graph.setGlobalStreamExchangeMode(deriveGlobalStreamExchangeModeBatch());
        graph.setAllVerticesInSameSlotSharingGroupByDefault(false);
    }

    private void configureStreamGraphStreaming(final StreamGraph graph) {
        graph.setJobType(JobType.STREAMING);
        graph.setJobName(deriveJobName(DEFAULT_STREAMING_JOB_NAME));

        graph.setGlobalStreamExchangeMode(deriveGlobalStreamExchangeModeStreaming());
    }

    private String deriveJobName(String defaultJobName) {
        return configuration.getOptional(PipelineOptions.NAME).orElse(defaultJobName);
    }

    private GlobalStreamExchangeMode deriveGlobalStreamExchangeModeBatch() {
        final BatchShuffleMode shuffleMode = configuration.get(ExecutionOptions.BATCH_SHUFFLE_MODE);
        switch (shuffleMode) {
            case ALL_EXCHANGES_PIPELINED:
                return GlobalStreamExchangeMode.ALL_EDGES_PIPELINED;
            case ALL_EXCHANGES_BLOCKING:
                return GlobalStreamExchangeMode.ALL_EDGES_BLOCKING;
            case ALL_EXCHANGES_HYBRID_FULL:
                return GlobalStreamExchangeMode.ALL_EDGES_HYBRID_FULL;
            case ALL_EXCHANGES_HYBRID_SELECTIVE:
                return GlobalStreamExchangeMode.ALL_EDGES_HYBRID_SELECTIVE;
            default:
                throw new IllegalArgumentException(
                        String.format(
                                "Unsupported shuffle mode '%s' in BATCH runtime mode.",
                                shuffleMode.toString()));
        }
    }

    private GlobalStreamExchangeMode deriveGlobalStreamExchangeModeStreaming() {
        if (checkpointConfig.isApproximateLocalRecoveryEnabled()) {
            checkApproximateLocalRecoveryCompatibility();
            return GlobalStreamExchangeMode.ALL_EDGES_PIPELINED_APPROXIMATE;
        }
        return GlobalStreamExchangeMode.ALL_EDGES_PIPELINED;
    }

    private void checkApproximateLocalRecoveryCompatibility() {
        checkState(
                !checkpointConfig.isUnalignedCheckpointsEnabled(),
                "Approximate Local Recovery and Unaligned Checkpoint can not be used together yet");
    }

    private void setBatchStateBackendAndTimerService(StreamGraph graph) {
        boolean useStateBackend = configuration.get(ExecutionOptions.USE_BATCH_STATE_BACKEND);
        boolean sortInputs = configuration.get(ExecutionOptions.SORT_INPUTS);
        checkState(
                !useStateBackend || sortInputs,
                "Batch state backend requires the sorted inputs to be enabled!");

        if (useStateBackend) {
            LOG.debug("Using BATCH execution state backend and timer service.");
            graph.setStateBackend(new BatchExecutionStateBackend());
            graph.getJobConfiguration().set(StateChangelogOptions.ENABLE_STATE_CHANGE_LOG, false);
            graph.setCheckpointStorage(new BatchExecutionCheckpointStorage());
            graph.setTimerServiceProvider(BatchExecutionInternalTimeServiceManager::create);
            graph.createJobCheckpointingSettings();
        }
    }

    private void setFineGrainedGlobalStreamExchangeMode(StreamGraph graph) {
        // There might be a resource deadlock when applying fine-grained resource management in
        // batch jobs with PIPELINE edges. We convert all edges to BLOCKING before we fix
        // that issue.
        if (shouldExecuteInBatchMode && graph.hasFineGrainedResource()) {
            graph.setGlobalStreamExchangeMode(GlobalStreamExchangeMode.ALL_EDGES_BLOCKING);
        }
    }

    private boolean shouldExecuteInBatchMode() {
        final RuntimeExecutionMode configuredMode =
                configuration.get(ExecutionOptions.RUNTIME_MODE);

        final boolean existsUnboundedSource = existsUnboundedSource();

        checkState(
                configuredMode != RuntimeExecutionMode.BATCH || !existsUnboundedSource,
                "Detected an UNBOUNDED source with the '"
                        + ExecutionOptions.RUNTIME_MODE.key()
                        + "' set to 'BATCH'. "
                        + "This combination is not allowed, please set the '"
                        + ExecutionOptions.RUNTIME_MODE.key()
                        + "' to STREAMING or AUTOMATIC");

        if (checkNotNull(configuredMode) != RuntimeExecutionMode.AUTOMATIC) {
            return configuredMode == RuntimeExecutionMode.BATCH;
        }
        return !existsUnboundedSource;
    }

    private boolean existsUnboundedSource() {
        return transformations.stream()
                .anyMatch(
                        transformation ->
                                isUnboundedSource(transformation)
                                        || transformation.getTransitivePredecessors().stream()
                                                .anyMatch(this::isUnboundedSource));
    }

    private boolean isUnboundedSource(final Transformation<?> transformation) {
        checkNotNull(transformation);
        return transformation instanceof WithBoundedness
                && ((WithBoundedness) transformation).getBoundedness() != Boundedness.BOUNDED;
    }

    /**
     * Transforms one {@code Transformation}.
     *
     * <p>This checks whether we already transformed it and exits early in that case. If not it
     * delegates to one of the transformation specific methods.
     */
    private Collection<Integer> transform(Transformation<?> transform) {
        if (alreadyTransformed.containsKey(transform)) {
            return alreadyTransformed.get(transform);
        }

        LOG.debug("Transforming " + transform);

        if (transform.getMaxParallelism() <= 0) {

            // if the max parallelism hasn't been set, then first use the job wide max parallelism
            // from the ExecutionConfig.
            int globalMaxParallelismFromConfig = executionConfig.getMaxParallelism();
            if (globalMaxParallelismFromConfig > 0) {
                transform.setMaxParallelism(globalMaxParallelismFromConfig);
            }
        }

        transform
                .getSlotSharingGroup()
                .ifPresent(
                        slotSharingGroup -> {
                            final ResourceSpec resourceSpec =
                                    SlotSharingGroupUtils.extractResourceSpec(slotSharingGroup);
                            if (!resourceSpec.equals(ResourceSpec.UNKNOWN)) {
                                slotSharingGroupResources.compute(
                                        slotSharingGroup.getName(),
                                        (name, profile) -> {
                                            if (profile == null) {
                                                return ResourceProfile.fromResourceSpec(
                                                        resourceSpec, MemorySize.ZERO);
                                            } else if (!ResourceProfile.fromResourceSpec(
                                                            resourceSpec, MemorySize.ZERO)
                                                    .equals(profile)) {
                                                throw new IllegalArgumentException(
                                                        "The slot sharing group "
                                                                + slotSharingGroup.getName()
                                                                + " has been configured with two different resource spec.");
                                            } else {
                                                return profile;
                                            }
                                        });
                            }
                        });

        // call at least once to trigger exceptions about MissingTypeInfo
        transform.getOutputType();

        @SuppressWarnings("unchecked")
        final TransformationTranslator<?, Transformation<?>> translator =
                (TransformationTranslator<?, Transformation<?>>)
                        translatorMap.get(transform.getClass());

        Collection<Integer> transformedIds;
        if (translator != null) {
            transformedIds = translate(translator, transform);
        } else {
            transformedIds = legacyTransform(transform);
        }

        // need this check because the iterate transformation adds itself before
        // transforming the feedback edges
        if (!alreadyTransformed.containsKey(transform)) {
            alreadyTransformed.put(transform, transformedIds);
        }

        return transformedIds;
    }

    private Collection<Integer> legacyTransform(Transformation<?> transform) {
        Collection<Integer> transformedIds;
        if (transform instanceof SourceTransformationWrapper<?>) {
            transformedIds = transform(((SourceTransformationWrapper<?>) transform).getInput());
        } else {
            throw new IllegalStateException("Unknown transformation: " + transform);
        }

        if (transform.getBufferTimeout() >= 0) {
            streamGraph.setBufferTimeout(transform.getId(), transform.getBufferTimeout());
        } else {
            streamGraph.setBufferTimeout(transform.getId(), getBufferTimeout());
        }

        if (transform.getUid() != null) {
            streamGraph.setTransformationUID(transform.getId(), transform.getUid());
        }
        if (transform.getAdditionalMetricVariables() != null) {
            streamGraph.setAdditionalMetricVariables(
                    transform.getId(), transform.getAdditionalMetricVariables());
        }
        if (transform.getUserProvidedNodeHash() != null) {
            streamGraph.setTransformationUserHash(
                    transform.getId(), transform.getUserProvidedNodeHash());
        }

        if (!streamGraph.getExecutionConfig().hasAutoGeneratedUIDsEnabled()) {
            if (transform instanceof PhysicalTransformation
                    && transform.getUserProvidedNodeHash() == null
                    && transform.getUid() == null) {
                throw new IllegalStateException(
                        "Auto generated UIDs have been disabled "
                                + "but no UID or hash has been assigned to operator "
                                + transform.getName());
            }
        }

        if (transform.getMinResources() != null && transform.getPreferredResources() != null) {
            streamGraph.setResources(
                    transform.getId(),
                    transform.getMinResources(),
                    transform.getPreferredResources());
        }

        streamGraph.setManagedMemoryUseCaseWeights(
                transform.getId(),
                transform.getManagedMemoryOperatorScopeUseCaseWeights(),
                transform.getManagedMemorySlotScopeUseCases());

        return transformedIds;
    }

    private long getBufferTimeout() {
        return configuration.get(ExecutionOptions.BUFFER_TIMEOUT_ENABLED)
                ? configuration.get(ExecutionOptions.BUFFER_TIMEOUT).toMillis()
                : ExecutionOptions.DISABLED_NETWORK_BUFFER_TIMEOUT;
    }

    private Collection<Integer> translate(
            final TransformationTranslator<?, Transformation<?>> translator,
            final Transformation<?> transform) {
        checkNotNull(translator);
        checkNotNull(transform);

        final List<Collection<Integer>> allInputIds = getParentInputIds(transform.getInputs());

        // the recursive call might have already transformed this
        if (alreadyTransformed.containsKey(transform)) {
            return alreadyTransformed.get(transform);
        }

        final String slotSharingGroup =
                determineSlotSharingGroup(
                        transform.getSlotSharingGroup().isPresent()
                                ? transform.getSlotSharingGroup().get().getName()
                                : null,
                        allInputIds.stream()
                                .flatMap(Collection::stream)
                                .collect(Collectors.toList()));

        final TransformationTranslator.Context context =
                new ContextImpl(
                        this, streamGraph, slotSharingGroup, configuration, transformations);

        return shouldExecuteInBatchMode
                ? translator.translateForBatch(transform, context)
                : translator.translateForStreaming(transform, context);
    }

    /**
     * Returns a list of lists containing the ids of the nodes in the transformation graph that
     * correspond to the provided transformations. Each transformation may have multiple nodes.
     *
     * <p>Parent transformations will be translated if they are not already translated.
     *
     * @param parentTransformations the transformations whose node ids to return.
     * @return the nodeIds per transformation or an empty list if the {@code parentTransformations}
     *     are empty.
     */
    private List<Collection<Integer>> getParentInputIds(
            @Nullable final Collection<Transformation<?>> parentTransformations) {
        final List<Collection<Integer>> allInputIds = new ArrayList<>();
        if (parentTransformations == null) {
            return allInputIds;
        }

        for (Transformation<?> transformation : parentTransformations) {
            allInputIds.add(transform(transformation));
        }
        return allInputIds;
    }

    /**
     * Determines the slot sharing group for an operation based on the slot sharing group set by the
     * user and the slot sharing groups of the inputs.
     *
     * <p>If the user specifies a group name, this is taken as is. If nothing is specified and the
     * input operations all have the same group name then this name is taken. Otherwise the default
     * group is chosen.
     *
     * @param specifiedGroup The group specified by the user.
     * @param inputIds The IDs of the input operations.
     */
    private String determineSlotSharingGroup(String specifiedGroup, Collection<Integer> inputIds) {
        if (specifiedGroup != null) {
            return specifiedGroup;
        } else {
            String inputGroup = null;
            for (int id : inputIds) {
                String inputGroupCandidate = streamGraph.getSlotSharingGroup(id);
                if (inputGroup == null) {
                    inputGroup = inputGroupCandidate;
                } else if (!inputGroup.equals(inputGroupCandidate)) {
                    return DEFAULT_SLOT_SHARING_GROUP;
                }
            }
            return inputGroup == null ? DEFAULT_SLOT_SHARING_GROUP : inputGroup;
        }
    }

    private static class ContextImpl implements TransformationTranslator.Context {

        private final StreamGraphGenerator streamGraphGenerator;

        private final StreamGraph streamGraph;

        private final String slotSharingGroup;

        private final ReadableConfig config;

        private final Collection<Transformation<?>> transformations;

        public ContextImpl(
                final StreamGraphGenerator streamGraphGenerator,
                final StreamGraph streamGraph,
                final String slotSharingGroup,
                final ReadableConfig config,
                Collection<Transformation<?>> transformations) {
            this.streamGraphGenerator = checkNotNull(streamGraphGenerator);
            this.streamGraph = checkNotNull(streamGraph);
            this.slotSharingGroup = checkNotNull(slotSharingGroup);
            this.config = checkNotNull(config);
            this.transformations =
                    checkNotNull(transformations, "transformations must not be null");
        }

        @Override
        public StreamGraph getStreamGraph() {
            return streamGraph;
        }

        @Override
        public Collection<Integer> getStreamNodeIds(final Transformation<?> transformation) {
            checkNotNull(transformation);
            final Collection<Integer> ids =
                    streamGraphGenerator.alreadyTransformed.get(transformation);
            checkState(
                    ids != null,
                    "Parent transformation \"" + transformation + "\" has not been transformed.");
            return ids;
        }

        @Override
        public String getSlotSharingGroup() {
            return slotSharingGroup;
        }

        @Override
        public long getDefaultBufferTimeout() {
            return streamGraphGenerator.getBufferTimeout();
        }

        @Override
        public ReadableConfig getGraphGeneratorConfig() {
            return config;
        }

        @Override
        public Collection<Integer> transform(Transformation<?> transformation) {
            return streamGraphGenerator.transform(transformation);
        }

        @Override
        public Collection<Transformation<?>> getSinkTransformations() {
            return transformations;
        }
    }
}
