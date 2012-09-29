/*
 * Licensed to ElasticSearch and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. ElasticSearch licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.cluster;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.elasticsearch.ElasticSearchIllegalArgumentException;
import org.elasticsearch.cluster.block.ClusterBlock;
import org.elasticsearch.cluster.block.ClusterBlocks;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.metadata.IndexTemplateMetaData;
import org.elasticsearch.cluster.metadata.MappingMetaData;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.cluster.routing.*;
import org.elasticsearch.cluster.routing.allocation.AllocationExplanation;
//import org.elasticsearch.cluster.routing.allocation.RoutingAllocation;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.collect.MapBuilder;
import org.elasticsearch.common.compress.BasicCompressedString;
import org.elasticsearch.common.io.stream.*;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.settings.SettingsFilter;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.index.shard.ShardId;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.elasticsearch.cluster.routing.allocation.RoutingAllocationResult;

import static org.elasticsearch.common.collect.MapBuilder.newMapBuilder;

/**
 *
 */
public class ClusterState implements ToXContent {

    public interface Custom {

        interface Factory<T extends Custom> {

            String type();

            T readFrom(StreamInput in) throws IOException;

            void writeTo(T customState, StreamOutput out) throws IOException;

            void toXContent(T customState, XContentBuilder builder, ToXContent.Params params);
        }
    }

    public static Map<String, Custom.Factory> customFactories = new HashMap<String, Custom.Factory>();

    /**
     * Register a custom index meta data factory. Make sure to call it from a static block.
     */
    public static void registerFactory(String type, Custom.Factory factory) {
        customFactories.put(type, factory);
    }

    @Nullable
    public static <T extends Custom> Custom.Factory<T> lookupFactory(String type) {
        return customFactories.get(type);
    }

    public static <T extends Custom> Custom.Factory<T> lookupFactorySafe(String type) throws ElasticSearchIllegalArgumentException {
        Custom.Factory<T> factory = customFactories.get(type);
        if (factory == null) {
            throw new ElasticSearchIllegalArgumentException("No custom state factory registered for type [" + type + "]");
        }
        return factory;
    }


    private final long version;

    private final RoutingTable routingTable;

    private final DiscoveryNodes nodes;

    private final MetaData metaData;

    private final ClusterBlocks blocks;

    private final AllocationExplanation allocationExplanation;

    private final ImmutableMap<String, Custom> customs;

    // built on demand
    private volatile RoutingNodes routingNodes;

    private SettingsFilter settingsFilter;

    public ClusterState(long version, ClusterState state) {
        this(version, state.metaData(), state.routingTable(), state.nodes(), state.blocks(), state.allocationExplanation(), state.customs());
    }

    public ClusterState(long version, MetaData metaData, RoutingTable routingTable, DiscoveryNodes nodes, ClusterBlocks blocks, AllocationExplanation allocationExplanation, ImmutableMap<String, Custom> customs) {
        this.version = version;
        this.metaData = metaData;
        this.routingTable = routingTable;
        this.nodes = nodes;
        this.blocks = blocks;
        this.allocationExplanation = allocationExplanation;
        this.customs = customs;
    }

    public long version() {
        return this.version;
    }

    public long getVersion() {
        return version();
    }

    public DiscoveryNodes nodes() {
        return this.nodes;
    }

    public DiscoveryNodes getNodes() {
        return nodes();
    }

    public MetaData metaData() {
        return this.metaData;
    }

    public MetaData getMetaData() {
        return metaData();
    }

    public RoutingTable routingTable() {
        return routingTable;
    }

    public RoutingTable getRoutingTable() {
        return routingTable();
    }

    public RoutingNodes routingNodes() {
        return routingTable.routingNodes(this);
    }

    public RoutingNodes getRoutingNodes() {
        return readOnlyRoutingNodes();
    }

    public ClusterBlocks blocks() {
        return this.blocks;
    }

    public ClusterBlocks getBlocks() {
        return blocks;
    }

    public AllocationExplanation allocationExplanation() {
        return this.allocationExplanation;
    }

    public AllocationExplanation getAllocationExplanation() {
        return allocationExplanation();
    }

    public ImmutableMap<String, Custom> customs() {
        return this.customs;
    }

    public ImmutableMap<String, Custom> getCustoms() {
        return this.customs;
    }

    /**
     * Returns a built (on demand) routing nodes view of the routing table. <b>NOTE, the routing nodes
     * are mutable, use them just for read operations</b>
     */
    public RoutingNodes readOnlyRoutingNodes() {
        if (routingNodes != null) {
            return routingNodes;
        }
        routingNodes = routingTable.routingNodes(this);
        return routingNodes;
    }

    public ClusterState settingsFilter(SettingsFilter settingsFilter) {
        this.settingsFilter = settingsFilter;
        return this;
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        if (!params.paramAsBoolean("filter_nodes", false)) {
            builder.field("master_node", nodes().masterNodeId());
        }

        // blocks
        if (!params.paramAsBoolean("filter_blocks", false)) {
            builder.startObject("blocks");

            if (!blocks().global().isEmpty()) {
                builder.startObject("global");
                for (ClusterBlock block : blocks().global()) {
                    block.toXContent(builder, params);
                }
                builder.endObject();
            }

            if (!blocks().indices().isEmpty()) {
                builder.startObject("indices");
                for (Map.Entry<String, ImmutableSet<ClusterBlock>> entry : blocks().indices().entrySet()) {
                    builder.startObject(entry.getKey());
                    for (ClusterBlock block : entry.getValue()) {
                        block.toXContent(builder, params);
                    }
                    builder.endObject();
                }
                builder.endObject();
            }

            builder.endObject();
        }

        // nodes
        if (!params.paramAsBoolean("filter_nodes", false)) {
            builder.startObject("nodes");
            for (DiscoveryNode node : nodes()) {
                builder.startObject(node.id(), XContentBuilder.FieldCaseConversion.NONE);
                builder.field("name", node.name());
                builder.field("transport_address", node.address().toString());

                builder.startObject("attributes");
                for (Map.Entry<String, String> attr : node.attributes().entrySet()) {
                    builder.field(attr.getKey(), attr.getValue());
                }
                builder.endObject();

                builder.endObject();
            }
            builder.endObject();
        }

        // meta data
        if (!params.paramAsBoolean("filter_metadata", false)) {
            builder.startObject("metadata");

            builder.startObject("templates");
            for (IndexTemplateMetaData templateMetaData : metaData().templates().values()) {
                builder.startObject(templateMetaData.name(), XContentBuilder.FieldCaseConversion.NONE);

                builder.field("template", templateMetaData.template());
                builder.field("order", templateMetaData.order());

                builder.startObject("settings");
                Settings settings = settingsFilter.filterSettings(templateMetaData.settings());
                for (Map.Entry<String, String> entry : settings.getAsMap().entrySet()) {
                    builder.field(entry.getKey(), entry.getValue());
                }
                builder.endObject();

                builder.startObject("mappings");
                for (Map.Entry<String, BasicCompressedString> entry : templateMetaData.mappings().entrySet()) {
                    byte[] mappingSource = entry.getValue().uncompressed();
                    XContentParser parser = XContentFactory.xContent(mappingSource).createParser(mappingSource);
                    Map<String, Object> mapping = parser.map();
                    if (mapping.size() == 1 && mapping.containsKey(entry.getKey())) {
                        // the type name is the root value, reduce it
                        mapping = (Map<String, Object>) mapping.get(entry.getKey());
                    }
                    builder.field(entry.getKey());
                    builder.map(mapping);
                }
                builder.endObject();


                builder.endObject();
            }
            builder.endObject();

            builder.startObject("indices");
            for (IndexMetaData indexMetaData : metaData()) {
                builder.startObject(indexMetaData.index(), XContentBuilder.FieldCaseConversion.NONE);

                builder.field("state", indexMetaData.state().toString().toLowerCase(Locale.ENGLISH));

                builder.startObject("settings");
                Settings settings = settingsFilter.filterSettings(indexMetaData.settings());
                for (Map.Entry<String, String> entry : settings.getAsMap().entrySet()) {
                    builder.field(entry.getKey(), entry.getValue());
                }
                builder.endObject();

                builder.startObject("mappings");
                for (Map.Entry<String, MappingMetaData> entry : indexMetaData.mappings().entrySet()) {
                    byte[] mappingSource = entry.getValue().source().uncompressed();
                    XContentParser parser = XContentFactory.xContent(mappingSource).createParser(mappingSource);
                    Map<String, Object> mapping = parser.map();
                    if (mapping.size() == 1 && mapping.containsKey(entry.getKey())) {
                        // the type name is the root value, reduce it
                        mapping = (Map<String, Object>) mapping.get(entry.getKey());
                    }
                    builder.field(entry.getKey());
                    builder.map(mapping);
                }
                builder.endObject();

                builder.startArray("aliases");
                for (String alias : indexMetaData.aliases().keySet()) {
                    builder.value(alias);
                }
                builder.endArray();

                builder.endObject();
            }
            builder.endObject();

            builder.endObject();
        }

        // routing table
        if (!params.paramAsBoolean("filter_routing_table", false)) {
            builder.startObject("routing_table");
            builder.startObject("indices");
            for (IndexRoutingTable indexRoutingTable : routingTable()) {
                builder.startObject(indexRoutingTable.index(), XContentBuilder.FieldCaseConversion.NONE);
                builder.startObject("shards");
                for (IndexShardRoutingTable indexShardRoutingTable : indexRoutingTable) {
                    builder.startArray(Integer.toString(indexShardRoutingTable.shardId().id()));
                    for (ShardRouting shardRouting : indexShardRoutingTable) {
                        shardRouting.toXContent(builder, params);
                    }
                    builder.endArray();
                }
                builder.endObject();
                builder.endObject();
            }
            builder.endObject();
            builder.endObject();
        }

        // routing nodes
        if (!params.paramAsBoolean("filter_routing_table", false)) {
            builder.startObject("routing_nodes");
            builder.startArray("unassigned");
            for (ShardRouting shardRouting : readOnlyRoutingNodes().unassigned()) {
                shardRouting.toXContent(builder, params);
            }
            builder.endArray();

            builder.startObject("nodes");
            for (RoutingNode routingNode : readOnlyRoutingNodes()) {
                builder.startArray(routingNode.nodeId(), XContentBuilder.FieldCaseConversion.NONE);
                for (ShardRouting shardRouting : routingNode) {
                    shardRouting.toXContent(builder, params);
                }
                builder.endArray();
            }
            builder.endObject();

            builder.endObject();
        }

        if (!params.paramAsBoolean("filter_routing_table", false)) {
            builder.startArray("allocations");
            for (Map.Entry<ShardId, List<AllocationExplanation.NodeExplanation>> entry : allocationExplanation().explanations().entrySet()) {
                builder.startObject();
                builder.field("index", entry.getKey().index().name());
                builder.field("shard", entry.getKey().id());
                builder.startArray("explanations");
                for (AllocationExplanation.NodeExplanation nodeExplanation : entry.getValue()) {
                    builder.field("desc", nodeExplanation.description());
                    if (nodeExplanation.node() != null) {
                        builder.startObject("node");
                        builder.field("id", nodeExplanation.node().id());
                        builder.field("name", nodeExplanation.node().name());
                        builder.endObject();
                    }
                }
                builder.endArray();
                builder.endObject();
            }
            builder.endArray();
        }

        if (!params.paramAsBoolean("filter_customs", false)) {
            for (Map.Entry<String, Custom> entry : customs().entrySet()) {
                builder.startObject(entry.getKey());
                lookupFactorySafe(entry.getKey()).toXContent(entry.getValue(), builder, params);
                builder.endObject();
            }
        }

        return builder;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static Builder newClusterStateBuilder() {
        return new Builder();
    }

    public static class Builder {

        private long version = 0;

        private MetaData metaData = MetaData.EMPTY_META_DATA;

        private RoutingTable routingTable = RoutingTable.EMPTY_ROUTING_TABLE;

        private DiscoveryNodes nodes = DiscoveryNodes.EMPTY_NODES;

        private ClusterBlocks blocks = ClusterBlocks.EMPTY_CLUSTER_BLOCK;

        private AllocationExplanation allocationExplanation = AllocationExplanation.EMPTY;

        private MapBuilder<String, Custom> customs = newMapBuilder();

        public Builder nodes(DiscoveryNodes.Builder nodesBuilder) {
            return nodes(nodesBuilder.build());
        }

        public Builder nodes(DiscoveryNodes nodes) {
            this.nodes = nodes;
            return this;
        }

        public Builder routingTable(RoutingTable.Builder routingTable) {
            return routingTable(routingTable.build());
        }

        public Builder routingResult(RoutingAllocationResult routingResult) {
            this.routingTable = routingResult.routingTable();
            this.allocationExplanation = routingResult.explanation();
            return this;
        }

        public Builder routingTable(RoutingTable routingTable) {
            this.routingTable = routingTable;
            return this;
        }

        public Builder metaData(MetaData.Builder metaDataBuilder) {
            return metaData(metaDataBuilder.build());
        }

        public Builder metaData(MetaData metaData) {
            this.metaData = metaData;
            return this;
        }

        public Builder blocks(ClusterBlocks.Builder blocksBuilder) {
            return blocks(blocksBuilder.build());
        }

        public Builder blocks(ClusterBlocks block) {
            this.blocks = block;
            return this;
        }

        public Builder allocationExplanation(AllocationExplanation allocationExplanation) {
            this.allocationExplanation = allocationExplanation;
            return this;
        }

        public Builder version(long version) {
            this.version = version;
            return this;
        }

        public Custom getCustom(String type) {
            return customs.get(type);
        }

        public Builder putCustom(String type, Custom custom) {
            customs.put(type, custom);
            return this;
        }

        public Builder removeCustom(String type) {
            customs.remove(type);
            return this;
        }

        public Builder state(ClusterState state) {
            this.version = state.version();
            this.nodes = state.nodes();
            this.routingTable = state.routingTable();
            this.metaData = state.metaData();
            this.blocks = state.blocks();
            this.allocationExplanation = state.allocationExplanation();
            this.customs.clear().putAll(state.customs());
            return this;
        }

        public ClusterState build() {
            return new ClusterState(version, metaData, routingTable, nodes, blocks, allocationExplanation, customs.immutableMap());
        }

        public static byte[] toBytes(ClusterState state) throws IOException {
            ClientCachedStreamOutput.Entry cachedEntry = ClientCachedStreamOutput.popEntry();
            try {
                BytesStreamOutput os = cachedEntry.bytes();
                writeTo(state, os);
                return os.bytes().copyBytesArray().toBytes();
            } finally {
                ClientCachedStreamOutput.pushEntry(cachedEntry);
            }
        }

        public static ClusterState fromBytes(byte[] data, DiscoveryNode localNode) throws IOException {
            return readFrom(new BytesStreamInput(data, false), localNode);
        }

        public static void writeTo(ClusterState state, StreamOutput out) throws IOException {
            out.writeLong(state.version());
            MetaData.Builder.writeTo(state.metaData(), out);
            RoutingTable.Builder.writeTo(state.routingTable(), out);
            DiscoveryNodes.Builder.writeTo(state.nodes(), out);
            ClusterBlocks.Builder.writeClusterBlocks(state.blocks(), out);
            state.allocationExplanation().writeTo(out);
            out.writeVInt(state.customs().size());
            for (Map.Entry<String, Custom> entry : state.customs().entrySet()) {
                out.writeString(entry.getKey());
                lookupFactorySafe(entry.getKey()).writeTo(entry.getValue(), out);
            }
        }

        public static ClusterState readFrom(StreamInput in, @Nullable DiscoveryNode localNode) throws IOException {
            Builder builder = new Builder();
            builder.version = in.readLong();
            builder.metaData = MetaData.Builder.readFrom(in);
            builder.routingTable = RoutingTable.Builder.readFrom(in);
            builder.nodes = DiscoveryNodes.Builder.readFrom(in, localNode);
            builder.blocks = ClusterBlocks.Builder.readClusterBlocks(in);
            builder.allocationExplanation = AllocationExplanation.readAllocationExplanation(in);
            int customSize = in.readVInt();
            for (int i = 0; i < customSize; i++) {
                String type = in.readString();
                Custom customIndexMetaData = lookupFactorySafe(type).readFrom(in);
                builder.putCustom(type, customIndexMetaData);
            }
            return builder.build();
        }
    }
}
