// Copyright 2022 JanusGraph Authors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.janusgraph.graphdb.transaction.vertexcache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.RemovalListener;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.google.common.base.Preconditions;
import org.janusgraph.graphdb.internal.InternalVertex;
import org.janusgraph.graphdb.vertices.AbstractVertex;
import org.janusgraph.util.datastructures.Retriever;
import org.jctools.maps.NonBlockingHashMapLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentMap;

public class CaffeineVertexCache implements VertexCache {
    private static final Logger log =
        LoggerFactory.getLogger(CaffeineVertexCache.class);

    private final ConcurrentMap<Long, InternalVertex> volatileVertices;
    private final Cache<Long, InternalVertex> cache;

    private final long createdTime;

    public CaffeineVertexCache(final long maxCacheSize, final int initialDirtySize) {
        volatileVertices = new NonBlockingHashMapLong<>(initialDirtySize);
        log.debug("Created dirty vertex map with initial size {}", initialDirtySize);

        Caffeine<Long, InternalVertex> cacheBuilder = Caffeine.newBuilder().maximumSize(maxCacheSize)
            .removalListener((RemovalListener<Long, InternalVertex>) (key, v, removalCause) -> {
                if (removalCause == RemovalCause.EXPLICIT) {
                    assert volatileVertices.isEmpty();
                    return;
                }
                assert (removalCause == RemovalCause.SIZE || removalCause == RemovalCause.REPLACED) : "Cause: " + removalCause;
                if (((AbstractVertex) v).isTxOpen() && (v.isModified() || v.isRemoved())) {
                    volatileVertices.putIfAbsent(key, v);
                }
            })
            .executor(Runnable::run); // according to the https://github.com/ben-manes/caffeine/discussions/757
        if (log.isDebugEnabled()) {
            cacheBuilder = cacheBuilder.recordStats();
        }
        cache = cacheBuilder.build();
        log.debug("Created vertex cache with max size {}", maxCacheSize);
        createdTime = System.currentTimeMillis();
    }

    @Override
    public boolean contains(long id) {
        Long vertexId = id;
        return cache.getIfPresent(vertexId) != null || volatileVertices.containsKey(vertexId);
    }

    @Override
    public InternalVertex get(long id, Retriever<Long, InternalVertex> retriever) {
        final Long vertexId = id;

        InternalVertex vertex = cache.getIfPresent(vertexId);

        if (vertex == null) {
            InternalVertex newVertex = volatileVertices.get(vertexId);

            if (newVertex == null) {
                newVertex = retriever.get(vertexId);
            }
            assert newVertex != null;
            final InternalVertex v = newVertex;
            try {
                vertex = cache.get(vertexId, (k) -> v);
            } catch (Exception e) { throw new AssertionError("Should not happen: "+e.getMessage()); }
            assert vertex!=null;
        }

        return vertex;
    }

    @Override
    public void add(InternalVertex vertex, long id) {
        Preconditions.checkNotNull(vertex);
        Preconditions.checkArgument(id != 0);
        Long vertexId = id;

        cache.put(vertexId, vertex);
        if (vertex.isNew() || vertex.hasAddedRelations())
            volatileVertices.put(vertexId, vertex);
    }

    @Override
    public List<InternalVertex> getAllNew() {
        final List<InternalVertex> vertices = new ArrayList<>(10);
        for (InternalVertex v : volatileVertices.values()) {
            if (v.isNew()) vertices.add(v);
        }
        return vertices;
    }

    @Override
    public synchronized void close() {
        if (log.isDebugEnabled()) {
            long end = System.currentTimeMillis();
            CacheStats stats = cache.stats();
            log.debug("Caffeine cache (lifespan: {}ms) stats: {}", end - createdTime, stats);
        }
        volatileVertices.clear();
        cache.invalidateAll();
        cache.cleanUp();
    }
}
