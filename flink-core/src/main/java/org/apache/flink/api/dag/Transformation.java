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

package org.apache.flink.api.dag;

import org.apache.flink.annotation.Internal;
import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.api.common.ExecutionConfig;
import org.apache.flink.api.common.attribute.Attribute;
import org.apache.flink.api.common.functions.InvalidTypesException;
import org.apache.flink.api.common.operators.ResourceSpec;
import org.apache.flink.api.common.operators.SlotSharingGroup;
import org.apache.flink.api.common.operators.util.OperatorValidationUtils;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.typeutils.MissingTypeInfo;
import org.apache.flink.core.memory.ManagedMemoryUseCase;

import javax.annotation.Nullable;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.apache.flink.util.Preconditions.checkArgument;
import static org.apache.flink.util.Preconditions.checkNotNull;

/**
 * A {@code Transformation} represents the operation that creates a DataStream. Every DataStream has
 * an underlying {@code Transformation} that is the origin of said DataStream.
 *
 * <p>API operations such as DataStream#map create a tree of {@code Transformation}s underneath.
 * When the stream program is to be executed this graph is translated to a StreamGraph using
 * StreamGraphGenerator.
 *
 * <p>A {@code Transformation} does not necessarily correspond to a physical operation at runtime.
 * Some operations are only logical concepts. Examples of this are union, split/select data stream,
 * partitioning.
 *
 * <p>The following graph of {@code Transformations}:
 *
 * <pre>{@code
 *   Source              Source
 *      +                   +
 *      |                   |
 *      v                   v
 *  Rebalance          HashPartition
 *      +                   +
 *      |                   |
 *      |                   |
 *      +------>Union<------+
 *                +
 *                |
 *                v
 *              Split
 *                +
 *                |
 *                v
 *              Select
 *                +
 *                v
 *               Map
 *                +
 *                |
 *                v
 *              Sink
 * }</pre>
 *
 * <p>Would result in this graph of operations at runtime:
 *
 * <pre>{@code
 * Source              Source
 *   +                   +
 *   |                   |
 *   |                   |
 *   +------->Map<-------+
 *             +
 *             |
 *             v
 *            Sink
 * }</pre>
 *
 * <p>The information about partitioning, union, split/select end up being encoded in the edges that
 * connect the sources to the map operation.
 *
 * @param <T> The type of the elements that result from this {@code Transformation}
 */
@Internal
public abstract class Transformation<T> {

    // Has to be equal to StreamGraphGenerator.UPPER_BOUND_MAX_PARALLELISM
    public static final int UPPER_BOUND_MAX_PARALLELISM = 1 << 15;

    // This is used to assign a unique ID to every Transformation
    private static final AtomicInteger ID_COUNTER = new AtomicInteger(0);

    // If true, the parallelism of the transformation is explicitly set and should be respected.
    // Otherwise the parallelism can be changed at runtime.
    private boolean parallelismConfigured;
    private @Nullable Map<String, String> additionalMetricVariables;

    public static int getNewNodeId() {
        return ID_COUNTER.incrementAndGet();
    }

    protected final int id;

    protected String name;

    protected String description;

    protected TypeInformation<T> outputType;
    // This is used to handle MissingTypeInfo. As long as the outputType has not been queried
    // it can still be changed using setOutputType(). Afterwards an exception is thrown when
    // trying to change the output type.
    protected boolean typeUsed;

    private int parallelism;

    /**
     * The maximum parallelism for this stream transformation. It defines the upper limit for
     * dynamic scaling and the number of key groups used for partitioned state.
     */
    private int maxParallelism = -1;

    /**
     * The minimum resources for this stream transformation. It defines the lower limit for dynamic
     * resources resize in future plan.
     */
    private ResourceSpec minResources = ResourceSpec.DEFAULT;

    /**
     * The preferred resources for this stream transformation. It defines the upper limit for
     * dynamic resource resize in future plan.
     */
    private ResourceSpec preferredResources = ResourceSpec.DEFAULT;

    /**
     * Each entry in this map represents a operator scope use case that this transformation needs
     * managed memory for. The keys indicate the use cases, while the values are the
     * use-case-specific weights for this transformation. Managed memory reserved for a use case
     * will be shared by all the declaring transformations within a slot according to this weight.
     */
    private final Map<ManagedMemoryUseCase, Integer> managedMemoryOperatorScopeUseCaseWeights =
            new EnumMap<>(ManagedMemoryUseCase.class);

    /**
     * This map is a cache that stores transitive predecessors and used in {@code
     * getTransitivePredecessors()}.
     */
    private final Map<Transformation<T>, List<Transformation<?>>> predecessorsCache =
            new HashMap<>();

    /** Slot scope use cases that this transformation needs managed memory for. */
    private final Set<ManagedMemoryUseCase> managedMemorySlotScopeUseCases = new HashSet<>();

    /**
     * User-specified ID for this transformation. This is used to assign the same operator ID across
     * job restarts. There is also the automatically generated {@link #id}, which is assigned from a
     * static counter. That field is independent from this.
     */
    private String uid;

    private String userProvidedNodeHash;

    protected long bufferTimeout = -1;

    private Optional<SlotSharingGroup> slotSharingGroup;

    @Nullable private String coLocationGroupKey;

    private Attribute attribute = new Attribute.Builder().build();

    /**
     * Creates a new {@code Transformation} with the given name, output type and parallelism.
     *
     * @param name The name of the {@code Transformation}, this will be shown in Visualizations and
     *     the Log
     * @param outputType The output type of this {@code Transformation}
     * @param parallelism The parallelism of this {@code Transformation}
     */
    public Transformation(String name, TypeInformation<T> outputType, int parallelism) {
        this(name, outputType, parallelism, true);
    }

    /**
     * Creates a new {@code Transformation} with the given name, output type and parallelism.
     *
     * @param name The name of the {@code Transformation}, this will be shown in Visualizations and
     *     the Log
     * @param outputType The output type of this {@code Transformation}
     * @param parallelism The parallelism of this {@code Transformation}
     * @param parallelismConfigured If true, the parallelism of the transformation is explicitly set
     *     and should be respected. Otherwise the parallelism can be changed at runtime.
     */
    public Transformation(
            String name,
            TypeInformation<T> outputType,
            int parallelism,
            boolean parallelismConfigured) {
        this.id = getNewNodeId();
        this.name = checkNotNull(name);
        this.outputType = outputType;
        this.parallelism = parallelism;
        this.slotSharingGroup = Optional.empty();
        this.parallelismConfigured =
                parallelismConfigured && parallelism != ExecutionConfig.PARALLELISM_DEFAULT;
    }

    /** Returns the unique ID of this {@code Transformation}. */
    public int getId() {
        return id;
    }

    /** Changes the name of this {@code Transformation}. */
    public void setName(String name) {
        this.name = name;
    }

    /** Returns the name of this {@code Transformation}. */
    public String getName() {
        return name;
    }

    /** Returns the predecessorsCache of this {@code Transformation}. */
    @VisibleForTesting
    Map<Transformation<T>, List<Transformation<?>>> getPredecessorsCache() {
        return predecessorsCache;
    }

    /** Changes the description of this {@code Transformation}. */
    public void setDescription(String description) {
        this.description = checkNotNull(description);
    }

    /** Returns the description of this {@code Transformation}. */
    public String getDescription() {
        return description;
    }

    /** Returns the parallelism of this {@code Transformation}. */
    public int getParallelism() {
        return parallelism;
    }

    /**
     * Sets the parallelism of this {@code Transformation}.
     *
     * @param parallelism The new parallelism to set on this {@code Transformation}.
     */
    public void setParallelism(int parallelism) {
        setParallelism(parallelism, true);
    }

    public void setParallelism(int parallelism, boolean parallelismConfigured) {
        OperatorValidationUtils.validateParallelism(parallelism);
        this.parallelism = parallelism;
        this.parallelismConfigured =
                parallelismConfigured && parallelism != ExecutionConfig.PARALLELISM_DEFAULT;
    }

    public boolean isParallelismConfigured() {
        return parallelismConfigured;
    }

    /**
     * Gets the maximum parallelism for this stream transformation.
     *
     * @return Maximum parallelism of this transformation.
     */
    public int getMaxParallelism() {
        return maxParallelism;
    }

    /**
     * Sets the maximum parallelism for this stream transformation.
     *
     * @param maxParallelism Maximum parallelism for this stream transformation.
     */
    public void setMaxParallelism(int maxParallelism) {
        OperatorValidationUtils.validateMaxParallelism(maxParallelism, UPPER_BOUND_MAX_PARALLELISM);
        this.maxParallelism = maxParallelism;
    }

    /**
     * Sets the minimum and preferred resources for this stream transformation.
     *
     * @param minResources The minimum resource of this transformation.
     * @param preferredResources The preferred resource of this transformation.
     */
    public void setResources(ResourceSpec minResources, ResourceSpec preferredResources) {
        OperatorValidationUtils.validateMinAndPreferredResources(minResources, preferredResources);
        this.minResources = minResources;
        this.preferredResources = preferredResources;
    }

    /**
     * Gets the minimum resource of this stream transformation.
     *
     * @return The minimum resource of this transformation.
     */
    public ResourceSpec getMinResources() {
        return minResources;
    }

    /**
     * Gets the preferred resource of this stream transformation.
     *
     * @return The preferred resource of this transformation.
     */
    public ResourceSpec getPreferredResources() {
        return preferredResources;
    }

    /**
     * Declares that this transformation contains certain operator scope managed memory use case.
     *
     * @param managedMemoryUseCase The use case that this transformation declares needing managed
     *     memory for.
     * @param weight Use-case-specific weights for this transformation. Used for sharing managed
     *     memory across transformations for OPERATOR scope use cases. Check the individual {@link
     *     ManagedMemoryUseCase} for the specific weight definition.
     * @return The previous weight, if exist.
     */
    public Optional<Integer> declareManagedMemoryUseCaseAtOperatorScope(
            ManagedMemoryUseCase managedMemoryUseCase, int weight) {
        checkNotNull(managedMemoryUseCase);
        checkArgument(
                managedMemoryUseCase.scope == ManagedMemoryUseCase.Scope.OPERATOR,
                "Use case is not operator scope.");
        checkArgument(weight > 0, "Weights for operator scope use cases must be greater than 0.");

        return Optional.ofNullable(
                managedMemoryOperatorScopeUseCaseWeights.put(managedMemoryUseCase, weight));
    }

    /**
     * Declares that this transformation contains certain slot scope managed memory use case.
     *
     * @param managedMemoryUseCase The use case that this transformation declares needing managed
     *     memory for.
     */
    public void declareManagedMemoryUseCaseAtSlotScope(ManagedMemoryUseCase managedMemoryUseCase) {
        checkNotNull(managedMemoryUseCase);
        checkArgument(managedMemoryUseCase.scope == ManagedMemoryUseCase.Scope.SLOT);

        managedMemorySlotScopeUseCases.add(managedMemoryUseCase);
    }

    protected void updateManagedMemoryStateBackendUseCase(boolean hasStateBackend) {
        if (hasStateBackend) {
            managedMemorySlotScopeUseCases.add(ManagedMemoryUseCase.STATE_BACKEND);
        } else {
            managedMemorySlotScopeUseCases.remove(ManagedMemoryUseCase.STATE_BACKEND);
        }
    }

    /**
     * Get operator scope use cases that this transformation needs managed memory for, and the
     * use-case-specific weights for this transformation. The weights are used for sharing managed
     * memory across transformations for the use cases. Check the individual {@link
     * ManagedMemoryUseCase} for the specific weight definition.
     */
    public Map<ManagedMemoryUseCase, Integer> getManagedMemoryOperatorScopeUseCaseWeights() {
        return Collections.unmodifiableMap(managedMemoryOperatorScopeUseCaseWeights);
    }

    /** Get slot scope use cases that this transformation needs managed memory for. */
    public Set<ManagedMemoryUseCase> getManagedMemorySlotScopeUseCases() {
        return Collections.unmodifiableSet(managedMemorySlotScopeUseCases);
    }

    /**
     * Sets an user provided hash for this operator. This will be used AS IS the create the
     * JobVertexID.
     *
     * <p>The user provided hash is an alternative to the generated hashes, that is considered when
     * identifying an operator through the default hash mechanics fails (e.g. because of changes
     * between Flink versions).
     *
     * <p><strong>Important</strong>: this should be used as a workaround or for trouble shooting.
     * The provided hash needs to be unique per transformation and job. Otherwise, job submission
     * will fail. Furthermore, you cannot assign user-specified hash to intermediate nodes in an
     * operator chain and trying so will let your job fail.
     *
     * <p>A use case for this is in migration between Flink versions or changing the jobs in a way
     * that changes the automatically generated hashes. In this case, providing the previous hashes
     * directly through this method (e.g. obtained from old logs) can help to reestablish a lost
     * mapping from states to their target operator.
     *
     * @param uidHash The user provided hash for this operator. This will become the JobVertexID,
     *     which is shown in the logs and web ui.
     */
    public void setUidHash(String uidHash) {

        checkNotNull(uidHash);
        checkArgument(
                uidHash.matches("^[0-9A-Fa-f]{32}$"),
                "Node hash must be a 32 character String that describes a hex code. Found: "
                        + uidHash);

        this.userProvidedNodeHash = uidHash;
    }

    /**
     * Gets the user provided hash.
     *
     * @return The user provided hash.
     */
    public String getUserProvidedNodeHash() {
        return userProvidedNodeHash;
    }

    /**
     * Sets an ID for this {@link Transformation}. This is will later be hashed to a uidHash which
     * is then used to create the JobVertexID (that is shown in logs and the web ui).
     *
     * <p>The specified ID is used to assign the same operator ID across job submissions (for
     * example when starting a job from a savepoint).
     *
     * <p><strong>Important</strong>: this ID needs to be unique per transformation and job.
     * Otherwise, job submission will fail.
     *
     * @param uid The unique user-specified ID of this transformation.
     */
    public void setUid(String uid) {
        this.uid = uid;
    }

    /**
     * Returns the user-specified ID of this transformation.
     *
     * @return The unique user-specified ID of this transformation.
     */
    public String getUid() {
        return uid;
    }

    /**
     * @return additional variables that will be added to scope of the metrics reported from this
     *     {@link Transformation}.
     */
    public @Nullable Map<String, String> getAdditionalMetricVariables() {
        return additionalMetricVariables;
    }

    /**
     * Adds additional variables that will be added to scope of the metrics reported from this
     * operator.
     *
     * <p>Some transformations might be translated into multiple operators and in such cases, metric
     * variables might be assigned to just one specific operator. For example {@code
     * SinkTransformation}'s additional variables are only inherited by the writer operator. They
     * are not used for committer or global committer.
     *
     * @param key
     * @param value
     */
    public Transformation<T> addMetricVariable(String key, String value) {
        if (additionalMetricVariables == null) {
            additionalMetricVariables = new HashMap<>();
        }
        additionalMetricVariables.put(key, value);
        return this;
    }

    /**
     * Returns the slot sharing group of this transformation if present.
     *
     * @see #setSlotSharingGroup(SlotSharingGroup)
     */
    public Optional<SlotSharingGroup> getSlotSharingGroup() {
        return slotSharingGroup;
    }

    /**
     * Sets the slot sharing group of this transformation. Parallel instances of operations that are
     * in the same slot sharing group will be co-located in the same TaskManager slot, if possible.
     *
     * <p>Initially, an operation is in the default slot sharing group. This can be explicitly set
     * using {@code setSlotSharingGroup("default")}.
     *
     * @param slotSharingGroupName The slot sharing group's name.
     */
    public void setSlotSharingGroup(String slotSharingGroupName) {
        this.slotSharingGroup =
                Optional.of(SlotSharingGroup.newBuilder(slotSharingGroupName).build());
    }

    /**
     * Sets the slot sharing group of this transformation. Parallel instances of operations that are
     * in the same slot sharing group will be co-located in the same TaskManager slot, if possible.
     *
     * <p>Initially, an operation is in the default slot sharing group. This can be explicitly set
     * with constructing a {@link SlotSharingGroup} with name {@code "default"}.
     *
     * @param slotSharingGroup which contains name and its resource spec.
     */
    public void setSlotSharingGroup(SlotSharingGroup slotSharingGroup) {
        this.slotSharingGroup = Optional.of(slotSharingGroup);
    }

    /**
     * <b>NOTE:</b> This is an internal undocumented feature for now. It is not clear whether this
     * will be supported and stable in the long term.
     *
     * <p>Sets the key that identifies the co-location group. Operators with the same co-location
     * key will have their corresponding subtasks placed into the same slot by the scheduler.
     *
     * <p>Setting this to null means there is no co-location constraint.
     */
    public void setCoLocationGroupKey(@Nullable String coLocationGroupKey) {
        this.coLocationGroupKey = coLocationGroupKey;
    }

    /**
     * <b>NOTE:</b> This is an internal undocumented feature for now. It is not clear whether this
     * will be supported and stable in the long term.
     *
     * <p>Gets the key that identifies the co-location group. Operators with the same co-location
     * key will have their corresponding subtasks placed into the same slot by the scheduler.
     *
     * <p>If this is null (which is the default), it means there is no co-location constraint.
     */
    @Nullable
    public String getCoLocationGroupKey() {
        return coLocationGroupKey;
    }

    /**
     * Tries to fill in the type information. Type information can be filled in later when the
     * program uses a type hint. This method checks whether the type information has ever been
     * accessed before and does not allow modifications if the type was accessed already. This
     * ensures consistency by making sure different parts of the operation do not assume different
     * type information.
     *
     * @param outputType The type information to fill in.
     * @throws IllegalStateException Thrown, if the type information has been accessed before.
     */
    public void setOutputType(TypeInformation<T> outputType) {
        if (typeUsed) {
            throw new IllegalStateException(
                    "TypeInformation cannot be filled in for the type after it has been used. "
                            + "Please make sure that the type info hints are the first call after"
                            + " the transformation function, "
                            + "before any access to types or semantic properties, etc.");
        }
        this.outputType = outputType;
    }

    /**
     * Returns the output type of this {@code Transformation} as a {@link TypeInformation}. Once
     * this is used once the output type cannot be changed anymore using {@link #setOutputType}.
     *
     * @return The output type of this {@code Transformation}
     */
    public TypeInformation<T> getOutputType() {
        if (outputType instanceof MissingTypeInfo) {
            MissingTypeInfo typeInfo = (MissingTypeInfo) this.outputType;
            throw new InvalidTypesException(
                    "The return type of function '"
                            + typeInfo.getFunctionName()
                            + "' could not be determined automatically, due to type erasure. "
                            + "You can give type information hints by using the returns(...) "
                            + "method on the result of the transformation call, or by letting "
                            + "your function implement the 'ResultTypeQueryable' "
                            + "interface.",
                    typeInfo.getTypeException());
        }
        typeUsed = true;
        return this.outputType;
    }

    /**
     * Set the buffer timeout of this {@code Transformation}. The timeout defines how long data may
     * linger in a partially full buffer before being sent over the network.
     *
     * <p>Lower timeouts lead to lower tail latencies, but may affect throughput. For Flink 1.5+,
     * timeouts of 1ms are feasible for jobs with high parallelism.
     *
     * <p>A value of -1 means that the default buffer timeout should be used. A value of zero
     * indicates that no buffering should happen, and all records/events should be immediately sent
     * through the network, without additional buffering.
     */
    public void setBufferTimeout(long bufferTimeout) {
        checkArgument(bufferTimeout >= -1);
        this.bufferTimeout = bufferTimeout;
    }

    /**
     * Returns the buffer timeout of this {@code Transformation}.
     *
     * @see #setBufferTimeout(long)
     */
    public long getBufferTimeout() {
        return bufferTimeout;
    }

    /**
     * Returns all transitive predecessor {@code Transformation}s of this {@code Transformation}.
     * This is, for example, used when determining whether a feedback edge of an iteration actually
     * has the iteration head as a predecessor.
     *
     * @return The list of transitive predecessors.
     */
    protected abstract List<Transformation<?>> getTransitivePredecessorsInternal();

    /**
     * Returns all transitive predecessor {@code Transformation}s of this {@code Transformation}.
     * This is, for example, used when determining whether a feedback edge of an iteration actually
     * has the iteration head as a predecessor. This method is just a wrapper on top of {@code
     * getTransitivePredecessorsInternal} method with public access. It uses caching internally.
     *
     * @return The list of transitive predecessors.
     */
    public final List<Transformation<?>> getTransitivePredecessors() {
        return predecessorsCache.computeIfAbsent(this, key -> getTransitivePredecessorsInternal());
    }

    /**
     * Returns the {@link Transformation transformations} that are the immediate predecessors of the
     * current transformation in the transformation graph.
     */
    public abstract List<Transformation<?>> getInputs();

    /** Enabling the async state for this transformation. */
    public void enableAsyncState() {
        // Subclass should override this method if they support async state processing.
        throw new UnsupportedOperationException(
                "The transformation does not support async state, "
                        + "or you are enabling the async state without a keyed context "
                        + "(not behind a keyBy()).");
    }

    @Override
    public String toString() {
        return getClass().getSimpleName()
                + "{"
                + "id="
                + id
                + ", name='"
                + name
                + '\''
                + ", outputType="
                + outputType
                + ", parallelism="
                + parallelism
                + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Transformation)) {
            return false;
        }

        Transformation<?> that = (Transformation<?>) o;
        return Objects.equals(bufferTimeout, that.bufferTimeout)
                && Objects.equals(id, that.id)
                && Objects.equals(additionalMetricVariables, that.additionalMetricVariables)
                && Objects.equals(parallelism, that.parallelism)
                && Objects.equals(name, that.name)
                && Objects.equals(outputType, that.outputType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name, outputType, parallelism, bufferTimeout);
    }

    public void setAttribute(Attribute attribute) {
        this.attribute = attribute;
    }

    public Attribute getAttribute() {
        return attribute;
    }
}
