/*
 * Copyright (C) 2018. OpenLattice, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * You can contact the owner of the copyright at support@openlattice.com
 *
 */
package com.openlattice.chronicle.configuration

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import java.io.Serializable


private const val ELASTICSEARCH_URL = "elasticsearchUrl"
private const val ELASTICSEARCH_CLUSTER = "elasticsearchCluster"
private const val ELASTICSEARCH_PORT = "elasticsearchPort"
private const val ELASTICSEARCH_REST_PORT = "elasticsearchRestPort"
private const val NUM_REPLICAS = "numReplicas"
private const val NUM_SHARDS = "numShards"
private const val DEFAULT_NUM_REPLICAS = 2
private const val DEFAULT_NUM_SHARDS = 5

public data class SearchConfiguration @JsonCreator constructor(
        @param:JsonProperty(ELASTICSEARCH_URL) val elasticsearchUrl: String,
        @param:JsonProperty(ELASTICSEARCH_CLUSTER) val elasticsearchCluster: String,
        @param:JsonProperty(ELASTICSEARCH_PORT) val elasticsearchPort: Int,
        @param:JsonProperty(ELASTICSEARCH_REST_PORT) val elasticsearchRestPort: Int,
        @param:JsonProperty(NUM_REPLICAS) val numReplicas: Int = DEFAULT_NUM_REPLICAS,
        @param:JsonProperty(NUM_SHARDS) val numShards: Int = DEFAULT_NUM_SHARDS
) : Serializable {
    internal companion object {
        private const val serialVersionUID = 1018452800248369401L
    }
}
