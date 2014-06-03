/*
 * Licensed to CRATE Technology GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
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

package io.crate.metadata.doc;

import com.carrotsearch.hppc.cursors.ObjectCursor;
import com.carrotsearch.hppc.cursors.ObjectObjectCursor;
import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterators;
import com.google.common.collect.UnmodifiableIterator;
import com.google.common.util.concurrent.UncheckedExecutionException;
import io.crate.PartitionName;
import io.crate.blob.v2.BlobIndices;
import io.crate.exceptions.UnhandledServerException;
import io.crate.metadata.TableIdent;
import io.crate.metadata.table.SchemaInfo;
import io.crate.metadata.table.TableInfo;
import org.elasticsearch.action.admin.indices.template.put.TransportPutIndexTemplateAction;
import org.elasticsearch.cluster.ClusterChangedEvent;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ClusterStateListener;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.common.inject.Inject;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ExecutionException;

public class DocSchemaInfo implements SchemaInfo, ClusterStateListener {

    public static final String NAME = "doc";
    private final ClusterService clusterService;
    private final TransportPutIndexTemplateAction transportPutIndexTemplateAction;

    private static final Predicate<String> tablesFilter = new Predicate<String>() {
        @Override
        public boolean apply(String input) {
            return !BlobIndices.isBlobIndex(input);
        }
    };

    private final LoadingCache<String, DocTableInfo> cache = CacheBuilder.newBuilder()
            .maximumSize(10000)
            .build(
                    new CacheLoader<String, DocTableInfo>() {
                        @Override
                        public DocTableInfo load(String key) throws Exception {
                            return innerGetTableInfo(key);
                        }
                    }
            );
    private final Function<String, TableInfo> tableInfoFunction;

    @Inject
    public DocSchemaInfo(ClusterService clusterService,
                         TransportPutIndexTemplateAction transportPutIndexTemplateAction) {
        this.clusterService = clusterService;
        clusterService.add(this);
        this.transportPutIndexTemplateAction = transportPutIndexTemplateAction;
        this.tableInfoFunction = new Function<String, TableInfo>() {
            @Nullable
            @Override
            public TableInfo apply(@Nullable String input) {
                return getTableInfo(input);
            }
        };
    }

    private DocTableInfo innerGetTableInfo(String name) {
        boolean checkAliasSchema = clusterService.state().metaData().settings().getAsBoolean("crate.table_alias.schema_check", true);
        DocTableInfoBuilder builder = new DocTableInfoBuilder(
                new TableIdent(NAME, name), clusterService,
                transportPutIndexTemplateAction, checkAliasSchema);
        return builder.build();
    }

    @Override
    public DocTableInfo getTableInfo(String name) {
        // TODO: implement index based tables
        try {
            return cache.get(name);
        } catch (ExecutionException e) {
            throw new UnhandledServerException("Failed to get TableInfo", e.getCause());
        }
    }

    @Override
    public Collection<String> tableNames() {
        // TODO: once we support closing/opening tables change this to concreteIndices()
        // and add  state info to the TableInfo.
        List<String> tables = new ArrayList<>();
        tables.addAll(Collections2.filter(
                Arrays.asList(clusterService.state().metaData().concreteAllOpenIndices()), tablesFilter));

        // Search for partitioned table templates
        UnmodifiableIterator<String> templates = clusterService.state().metaData().getTemplates().keysIt();
        while(templates.hasNext()) {
            String templateName = templates.next();
            try {
                String tableName = PartitionName.tableName(templateName);
                tables.add(tableName);
            } catch (IllegalArgumentException e) {
                // do nothing
            }
        }

        return tables;
    }

    @Override
    public boolean systemSchema() {
        return false;
    }

    @Override
    public void clusterChanged(ClusterChangedEvent event) {
        if (event.metaDataChanged()) {
            // TODO: should this be done for all?
            if (event.previousState() == null) {
                cache.invalidateAll(event.state().metaData().indices().keys());
                return;
            }
            String source = event.source();
            CacheInvalidator invalidator = getInvalidator(source);
            invalidator.setCache(cache);
            invalidator.invalidate(event);
            // TODO: remove this...
//            HashSet<String> indices = new HashSet<>();
//            indices.addAll(event.indicesCreated());
//            indices.addAll(indicesUpdated(event));
//            indices.addAll(event.indicesDeleted());
//            if (indices.size() > 0) {
//                indices.addAll(getPartitionedTableNames(indices));
//                cache.invalidateAll(indices);
//            } else {
//                cache.invalidateAll();
//            }
        }
    }

    public interface CacheInvalidator {
        public void setCache(Cache cache);
        public void invalidate(ClusterChangedEvent event);
    }

    private final DefaultCacheInvalidator DEFAULT_CACHE_INVALIDATOR = new DefaultCacheInvalidator();
    private final CreateIndexCacheInvalidator CREATE_INDEX_CACHE_INVALIDATOR = new CreateIndexCacheInvalidator();
    private final UpdateMappingCacheInvalidator UPDATE_MAPPING_CACHE_INVALIDATOR = new UpdateMappingCacheInvalidator();
    private final NoOpCacheInvalidator NO_OP_CACHE_INVALIDATOR = new NoOpCacheInvalidator();
    private final DeleteIndexCacheInvalidator DELETE_INDEX_CACHE_INVALIDATOR = new DeleteIndexCacheInvalidator();
    private final RemoveIndexTemplateCacheInvalidator REMOVE_INDEX_TEMPLATE_CACHE_INVALIDATOR = new RemoveIndexTemplateCacheInvalidator();

    private CacheInvalidator getInvalidator(String source) {
        if (source.startsWith("create-index")) {
            return CREATE_INDEX_CACHE_INVALIDATOR;
        } else if (source.startsWith("create-index-template")) {
            return NO_OP_CACHE_INVALIDATOR;
        } else if (source.startsWith("update-mapping")) {
            return UPDATE_MAPPING_CACHE_INVALIDATOR;
        } else if (source.startsWith("delete-index")) {
            return DELETE_INDEX_CACHE_INVALIDATOR;
        } else if (source.startsWith("remove-index-template")) {
            return REMOVE_INDEX_TEMPLATE_CACHE_INVALIDATOR;
        }
        // fallback, invalidate all
        return DEFAULT_CACHE_INVALIDATOR;
    }

    private class DefaultCacheInvalidator implements CacheInvalidator {

        protected Cache cache;

        @Override
        public void setCache(Cache cache) {
            this.cache = cache;
        }

        @Override
        public void invalidate(ClusterChangedEvent event) {
            cache.invalidateAll();
        };

    }

    private class NoOpCacheInvalidator implements CacheInvalidator {

        @Override
        public void setCache(Cache cache) {
            // no-op
        }

        @Override
        public void invalidate(ClusterChangedEvent event) {
            // no-op
        }
    }

    private class CreateIndexCacheInvalidator extends DefaultCacheInvalidator {

        @Override
        public void invalidate(ClusterChangedEvent event) {
            // only update if the partitioned table its mapping differes from other partitions.
            ClusterState previousState = event.previousState();
            for (ObjectCursor<String> cursor : event.state().metaData().indices().keys()) {
                String index = cursor.value;
                // new index
                if (!previousState.metaData().hasIndex(index)) {
                    // if its a partitioned table,
                    if (PartitionName.isPartition(index)) {
                        String tableName = PartitionName.tableName(index);
                        cache.invalidate(tableName);
                        // TODO:
                        // check if partition schema differes from previous
                        // if so, refresh the others as well?
                    } else {
                        // TODO:
                        // what about if current table is a partitioned table (but not the 'paritioned_name')?
//                        DocTableInfo tableInfo = getTableInfo(index);
//                        if (tableInfo.partitionedBy().size() > 0) {
//
//                        }
                    }
                    cache.invalidate(index);
                }
            }
        }

    }

    private class UpdateMappingCacheInvalidator extends DefaultCacheInvalidator {

        @Override
        public void invalidate(ClusterChangedEvent event) {
            // for each (state.metaData().index(indexName) != previousState.metaData().index(indexName)
            for (ObjectObjectCursor<String, IndexMetaData> cursor : event.state().metaData().indices()) {
                String index = cursor.key;
                IndexMetaData indexMetaData = cursor.value;
                if (event.indexMetaDataChanged(indexMetaData)) {
                    cache.invalidate(index);
                }
            }
        }

    }

    private class DeleteIndexCacheInvalidator extends DefaultCacheInvalidator {

        @Override
        public void invalidate(ClusterChangedEvent event) {
            // TODO: define what to do
            ClusterState state = event.state();
            ClusterState previousState = event.previousState();
            for (ObjectCursor<String> cursor : state.metaData().indices().keys()) {
                String index = cursor.value;
                if (!state.metaData().hasIndex(index)) {
                    cache.invalidate(index);
                }
            }
        }

    }

    private class RemoveIndexTemplateCacheInvalidator extends DefaultCacheInvalidator {

        @Override
        public void invalidate(ClusterChangedEvent event) {
            // TODO: define what to do
            super.invalidate(event);
        }

    }

    // TODO: remove this if not required anymore
    /**
     * Check if any index has been updated (e.g. the mapping in IndexMetaData) and return a list with
     * corresponding indices.
     * @param event
     * @return
     */
    private List<String> indicesUpdated(ClusterChangedEvent event) {
        ClusterState state = event.state();
        ClusterState previousState = event.previousState();
        if (previousState == null) {
            return Arrays.asList(state.metaData().indices().keys().toArray(String.class));
        }
        if (!event.metaDataChanged()) {
            return ImmutableList.of();
        }
        List<String> updated = new ArrayList<>();
        for (ObjectObjectCursor<String, IndexMetaData> indexCursor : state.metaData().indices()) {
            String index = indexCursor.key;
            IndexMetaData indexMetaData = indexCursor.value;
            IndexMetaData previousIndexMetaData = previousState.metaData().index(index);
            if (indexMetaData != previousIndexMetaData) {
                updated.add(index);
            }
        }

        return ImmutableList.copyOf(updated);
    }

    // TODO: remove this if not required anymore
    /**
     * checks if <code>indices</code> contains any 'partitioned table names' and returns a list
     * with corresponding indices.
     * @param indices
     * @return
     */
    private HashSet<String> getPartitionedTableNames(HashSet<String> indices) {
        HashSet<String> partitionedIndices = new HashSet<>();
        for (String index : indices) {
            if (PartitionName.isPartition(index)) {
                // add the table name
                String tableName = PartitionName.tableName(index);
                partitionedIndices.add(tableName);
                // add all partitions
                DocTableInfo tableInfo = getTableInfo(tableName);
                for (String conreteIndex : tableInfo.concreteIndices()) {
                    partitionedIndices.add(conreteIndex);
                }
            } else {
                // TODO: this is kind of odd... remove if possible
                try {
                    DocTableInfo tableInfo = getTableInfo(index);
                    if (tableInfo.partitionedBy().size() > 0) {
                        for (String conreteIndex : tableInfo.concreteIndices()) {
                            partitionedIndices.add(conreteIndex);
                        }
                    }
                } catch (UnhandledServerException | UncheckedExecutionException e) {
                    // ignore
                }
            }
        }

        return partitionedIndices;
    }

    @Override
    public Iterator<TableInfo> iterator() {
        return Iterators.transform(tableNames().iterator(), tableInfoFunction);
    }
}
