/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */
package org.elasticsearch.cluster.metadata;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.ElasticsearchStatusException;
import org.elasticsearch.ResourceAlreadyExistsException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.indices.create.CreateIndexClusterStateUpdateRequest;
import org.elasticsearch.action.admin.indices.rollover.MetadataRolloverService;
import org.elasticsearch.action.support.ActiveShardCount;
import org.elasticsearch.action.support.ActiveShardsObserver;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.cluster.AckedClusterStateUpdateTask;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ack.ClusterStateUpdateRequest;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.Priority;
import org.elasticsearch.common.Strings;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.mapper.DataStreamTimestampFieldMapper;
import org.elasticsearch.index.mapper.MappingLookup;
import org.elasticsearch.index.mapper.MetadataFieldMapper;
import org.elasticsearch.indices.SystemDataStreamDescriptor;
import org.elasticsearch.indices.SystemIndexDescriptor;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.threadpool.ThreadPool;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

public class MetadataCreateDataStreamService {

    private static final Logger logger = LogManager.getLogger(MetadataCreateDataStreamService.class);

    private final ClusterService clusterService;
    private final ActiveShardsObserver activeShardsObserver;
    private final MetadataCreateIndexService metadataCreateIndexService;

    public MetadataCreateDataStreamService(
        ThreadPool threadPool,
        ClusterService clusterService,
        MetadataCreateIndexService metadataCreateIndexService
    ) {
        this.clusterService = clusterService;
        this.activeShardsObserver = new ActiveShardsObserver(clusterService, threadPool);
        this.metadataCreateIndexService = metadataCreateIndexService;
    }

    public void createDataStream(CreateDataStreamClusterStateUpdateRequest request, ActionListener<AcknowledgedResponse> finalListener) {
        AtomicReference<String> firstBackingIndexRef = new AtomicReference<>();
        ActionListener<AcknowledgedResponse> listener = ActionListener.wrap(response -> {
            if (response.isAcknowledged()) {
                String firstBackingIndexName = firstBackingIndexRef.get();
                assert firstBackingIndexName != null;
                activeShardsObserver.waitForActiveShards(
                    new String[] { firstBackingIndexName },
                    ActiveShardCount.DEFAULT,
                    request.masterNodeTimeout(),
                    shardsAcked -> finalListener.onResponse(AcknowledgedResponse.TRUE),
                    finalListener::onFailure
                );
            } else {
                finalListener.onResponse(AcknowledgedResponse.FALSE);
            }
        }, finalListener::onFailure);
        clusterService.submitStateUpdateTask(
            "create-data-stream [" + request.name + "]",
            new AckedClusterStateUpdateTask(Priority.HIGH, request, listener) {
                @Override
                public ClusterState execute(ClusterState currentState) throws Exception {
                    ClusterState clusterState = createDataStream(metadataCreateIndexService, currentState, request);
                    firstBackingIndexRef.set(clusterState.metadata().dataStreams().get(request.name).getIndices().get(0).getName());
                    return clusterState;
                }
            }
        );
    }

    public ClusterState createDataStream(CreateDataStreamClusterStateUpdateRequest request, ClusterState current) throws Exception {
        return createDataStream(metadataCreateIndexService, current, request);
    }

    public static final class CreateDataStreamClusterStateUpdateRequest extends ClusterStateUpdateRequest<
        CreateDataStreamClusterStateUpdateRequest> {

        private final String name;
        private final long startTime;
        private final SystemDataStreamDescriptor descriptor;

        public CreateDataStreamClusterStateUpdateRequest(String name) {
            this(name, System.currentTimeMillis(), null, TimeValue.ZERO, TimeValue.ZERO);
        }

        public CreateDataStreamClusterStateUpdateRequest(
            String name,
            SystemDataStreamDescriptor systemDataStreamDescriptor,
            TimeValue masterNodeTimeout,
            TimeValue timeout
        ) {
            this(name, System.currentTimeMillis(), systemDataStreamDescriptor, masterNodeTimeout, timeout);
        }

        public CreateDataStreamClusterStateUpdateRequest(
            String name,
            long startTime,
            SystemDataStreamDescriptor systemDataStreamDescriptor,
            TimeValue masterNodeTimeout,
            TimeValue timeout
        ) {
            this.name = name;
            this.startTime = startTime;
            this.descriptor = systemDataStreamDescriptor;
            masterNodeTimeout(masterNodeTimeout);
            ackTimeout(timeout);
        }

        public boolean isSystem() {
            return descriptor != null;
        }

        public SystemDataStreamDescriptor getSystemDataStreamDescriptor() {
            return descriptor;
        }
    }

    static ClusterState createDataStream(
        MetadataCreateIndexService metadataCreateIndexService,
        ClusterState currentState,
        CreateDataStreamClusterStateUpdateRequest request
    ) throws Exception {
        return createDataStream(metadataCreateIndexService, currentState, request, List.of(), null);
    }

    /**
     * Creates a data stream with the specified request, backing indices and write index.
     *
     * @param metadataCreateIndexService Used if a new write index must be created
     * @param currentState               Cluster state
     * @param request                    The create data stream request
     * @param backingIndices             List of backing indices. May be empty
     * @param writeIndex                 Write index for the data stream. If null, a new write index will be created.
     * @return                           Cluster state containing the new data stream
     */
    static ClusterState createDataStream(
        MetadataCreateIndexService metadataCreateIndexService,
        ClusterState currentState,
        CreateDataStreamClusterStateUpdateRequest request,
        List<IndexMetadata> backingIndices,
        IndexMetadata writeIndex
    ) throws Exception {
        String dataStreamName = request.name;
        SystemDataStreamDescriptor systemDataStreamDescriptor = request.getSystemDataStreamDescriptor();
        boolean isSystemDataStreamName = metadataCreateIndexService.getSystemIndices().isSystemDataStream(request.name);
        assert (isSystemDataStreamName && systemDataStreamDescriptor != null)
            || (isSystemDataStreamName == false && systemDataStreamDescriptor == null)
            : "dataStream [" + request.name + "] is system but no system descriptor was provided!";

        Objects.requireNonNull(metadataCreateIndexService);
        Objects.requireNonNull(currentState);
        Objects.requireNonNull(backingIndices);
        if (currentState.metadata().dataStreams().containsKey(dataStreamName)) {
            throw new ResourceAlreadyExistsException("data_stream [" + dataStreamName + "] already exists");
        }

        MetadataCreateIndexService.validateIndexOrAliasName(
            dataStreamName,
            (s1, s2) -> new IllegalArgumentException("data_stream [" + s1 + "] " + s2)
        );

        if (dataStreamName.toLowerCase(Locale.ROOT).equals(dataStreamName) == false) {
            throw new IllegalArgumentException("data_stream [" + dataStreamName + "] must be lowercase");
        }
        if (dataStreamName.startsWith(DataStream.BACKING_INDEX_PREFIX)) {
            throw new IllegalArgumentException(
                "data_stream [" + dataStreamName + "] must not start with '" + DataStream.BACKING_INDEX_PREFIX + "'"
            );
        }

        final boolean isSystem = systemDataStreamDescriptor != null;
        final ComposableIndexTemplate template = isSystem
            ? systemDataStreamDescriptor.getComposableIndexTemplate()
            : lookupTemplateForDataStream(dataStreamName, currentState.metadata());

        if (writeIndex == null) {
            String firstBackingIndexName = DataStream.getDefaultBackingIndexName(dataStreamName, 1, request.startTime);
            CreateIndexClusterStateUpdateRequest createIndexRequest = new CreateIndexClusterStateUpdateRequest(
                "initialize_data_stream",
                firstBackingIndexName,
                firstBackingIndexName
            ).dataStreamName(dataStreamName).systemDataStreamDescriptor(systemDataStreamDescriptor);

            if (isSystem) {
                createIndexRequest.settings(SystemIndexDescriptor.DEFAULT_SETTINGS);
            } else {
                createIndexRequest.settings(MetadataRolloverService.HIDDEN_INDEX_SETTINGS);
            }

            try {
                currentState = metadataCreateIndexService.applyCreateIndexRequest(currentState, createIndexRequest, false);
            } catch (ResourceAlreadyExistsException e) {
                // Rethrow as ElasticsearchStatusException, so that bulk transport action doesn't ignore it during
                // auto index/data stream creation.
                // (otherwise bulk execution fails later, because data stream will also not have been created)
                throw new ElasticsearchStatusException(
                    "data stream could not be created because backing index [{}] already exists",
                    RestStatus.BAD_REQUEST,
                    e,
                    firstBackingIndexName
                );
            }
            writeIndex = currentState.metadata().index(firstBackingIndexName);
        }
        assert writeIndex != null;
        assert writeIndex.mapping() != null : "no mapping found for backing index [" + writeIndex.getIndex().getName() + "]";

        String fieldName = template.getDataStreamTemplate().getTimestampField();
        DataStream.TimestampField timestampField = new DataStream.TimestampField(fieldName);
        List<Index> dsBackingIndices = backingIndices.stream().map(IndexMetadata::getIndex).collect(Collectors.toList());
        dsBackingIndices.add(writeIndex.getIndex());
        boolean hidden = isSystem || template.getDataStreamTemplate().isHidden();
        DataStream newDataStream = new DataStream(
            dataStreamName,
            timestampField,
            dsBackingIndices,
            1L,
            template.metadata() != null ? Map.copyOf(template.metadata()) : null,
            hidden,
            false,
            isSystem,
            template.getDataStreamTemplate().isAllowCustomRouting()
        );
        Metadata.Builder builder = Metadata.builder(currentState.metadata()).put(newDataStream);

        List<String> aliases = new ArrayList<>();
        var resolvedAliases = MetadataIndexTemplateService.resolveAliases(currentState.metadata(), template);
        for (var resolvedAliasMap : resolvedAliases) {
            for (var alias : resolvedAliasMap.values()) {
                aliases.add(alias.getAlias());
                builder.put(alias.getAlias(), dataStreamName, alias.writeIndex(), alias.filter() == null ? null : alias.filter().string());
            }
        }

        logger.info(
            "adding data stream [{}] with write index [{}], backing indices [{}], and aliases [{}]",
            dataStreamName,
            writeIndex.getIndex().getName(),
            Strings.arrayToCommaDelimitedString(backingIndices.stream().map(i -> i.getIndex().getName()).toArray()),
            Strings.collectionToCommaDelimitedString(aliases)
        );

        return ClusterState.builder(currentState).metadata(builder).build();
    }

    public static ComposableIndexTemplate lookupTemplateForDataStream(String dataStreamName, Metadata metadata) {
        final String v2Template = MetadataIndexTemplateService.findV2Template(metadata, dataStreamName, false);
        if (v2Template == null) {
            throw new IllegalArgumentException("no matching index template found for data stream [" + dataStreamName + "]");
        }
        ComposableIndexTemplate composableIndexTemplate = metadata.templatesV2().get(v2Template);
        if (composableIndexTemplate.getDataStreamTemplate() == null) {
            throw new IllegalArgumentException(
                "matching index template [" + v2Template + "] for data stream [" + dataStreamName + "] has no data stream template"
            );
        }
        return composableIndexTemplate;
    }

    public static void validateTimestampFieldMapping(MappingLookup mappingLookup) throws IOException {
        MetadataFieldMapper fieldMapper = (MetadataFieldMapper) mappingLookup.getMapper(DataStreamTimestampFieldMapper.NAME);
        assert fieldMapper != null : DataStreamTimestampFieldMapper.NAME + " meta field mapper must exist";
        // Sanity check: if this fails then somehow the mapping for _data_stream_timestamp has been overwritten and
        // that would be a bug.
        if (mappingLookup.isDataStreamTimestampFieldEnabled() == false) {
            throw new IllegalStateException("[" + DataStreamTimestampFieldMapper.NAME + "] meta field has been disabled");
        }
        // Sanity check (this validation logic should already have been executed when merging mappings):
        fieldMapper.validate(mappingLookup);
    }

}
