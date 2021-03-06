/*
 * Copyright (c) 2002-2017 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
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
 */
package org.neo4j.kernel.impl.api.store;

import java.util.Iterator;
import java.util.function.Consumer;

import org.neo4j.collection.primitive.PrimitiveIntSet;
import org.neo4j.cursor.Cursor;
import org.neo4j.function.Disposable;
import org.neo4j.io.pagecache.PageCursor;
import org.neo4j.kernel.impl.locking.Lock;
import org.neo4j.kernel.impl.locking.LockService;
import org.neo4j.kernel.impl.store.NodeLabelsField;
import org.neo4j.kernel.impl.store.NodeStore;
import org.neo4j.kernel.impl.store.record.NodeRecord;
import org.neo4j.kernel.impl.store.record.Record;
import org.neo4j.kernel.impl.util.IoPrimitiveUtils;
import org.neo4j.storageengine.api.BatchingLongProgression;
import org.neo4j.storageengine.api.NodeItem;
import org.neo4j.storageengine.api.txstate.NodeState;
import org.neo4j.storageengine.api.txstate.NodeTransactionStateView;

import static org.neo4j.collection.primitive.PrimitiveIntCollections.asSet;
import static org.neo4j.kernel.impl.locking.LockService.NO_LOCK;
import static org.neo4j.kernel.impl.locking.LockService.NO_LOCK_SERVICE;
import static org.neo4j.kernel.impl.store.record.RecordLoad.CHECK;
import static org.neo4j.kernel.impl.util.IoPrimitiveUtils.safeCastLongToInt;

public class NodeCursor implements NodeItem, Cursor<NodeItem>, Disposable
{
    private final Batch batch = new Batch();
    private final NodeRecord nodeRecord;
    private final Consumer<NodeCursor> instanceCache;
    private final NodeStore nodeStore;
    private final LockService lockService;
    private final PageCursor pageCursor;

    private BatchingLongProgression progression;
    private boolean fetched;
    private long[] labels;
    private Iterator<Long> added;
    private NodeTransactionStateView stateView;

    NodeCursor( NodeStore nodeStore, Consumer<NodeCursor> instanceCache, LockService lockService )
    {
        this.nodeStore = nodeStore;
        this.pageCursor = nodeStore.newPageCursor();
        this.nodeRecord = nodeStore.newRecord();
        this.instanceCache = instanceCache;
        this.lockService = lockService;
    }

    public Cursor<NodeItem> init( BatchingLongProgression progression, NodeTransactionStateView stateView )
    {
        this.progression = progression;
        this.added = progression.appendAdded() ? stateView.addedAndRemovedNodes().getAdded().iterator() : null;
        this.stateView = stateView;
        return this;
    }

    @Override
    public boolean next()
    {
        return fetched = fetchNext();
    }

    private boolean fetchNext()
    {
        labels = null;
        while ( progression != null && (batch.hasNext() || progression.nextBatch( batch ) ) )
        {
            long id = batch.next();
            if ( progression.fetchAdded() && stateView.nodeIsAddedInThisTx( id ) )
            {
                recordFromTxState( id );
                return true;
            }

            if ( !stateView.nodeIsDeletedInThisTx( id ) &&
                    nodeStore.readRecord( id, nodeRecord, CHECK, pageCursor ).inUse() )
            {
                return true;
            }
        }

        if ( added != null && added.hasNext() )
        {
            recordFromTxState( added.next() );
            return true;
        }

        return false;
    }

    private void recordFromTxState( long id )
    {
        nodeRecord.clear();
        nodeRecord.setId( id );
    }

    @Override
    public void close()
    {
        fetched = false;
        labels = null;
        added = null;
        progression = null;
        stateView = null;
        batch.nothing();
        instanceCache.accept( this );
    }

    @Override
    public void dispose()
    {
        pageCursor.close();
    }

    @Override
    public NodeItem get()
    {
        if ( fetched )
        {
            return this;
        }

        throw new IllegalStateException( "Nothing available" );
    }

    @Override
    public PrimitiveIntSet labels()
    {
        PrimitiveIntSet labels = asSet( loadedLabels(), IoPrimitiveUtils::safeCastLongToInt );
        return stateView.getNodeState( id() ).augmentLabels( labels );
    }

    @Override
    public boolean hasLabel( int labelId )
    {
        NodeState nodeState = stateView.getNodeState( id() );
        if ( nodeState.labelDiffSets().getRemoved().contains( labelId ) )
        {
            return false;
        }

        if ( nodeState.labelDiffSets().getAdded().contains( labelId ) )
        {
            return true;
        }

        for ( long label : loadedLabels() )
        {
            if ( safeCastLongToInt( label ) == labelId )
            {
                return true;
            }
        }
        return false;
    }

    private long[] loadedLabels()
    {
        if ( labels == null )
        {
            labels = NodeLabelsField.get( nodeRecord, nodeStore );
        }
        return labels;
    }

    @Override
    public long id()
    {
        return nodeRecord.getId();
    }

    @Override
    public boolean isDense()
    {
        return nodeRecord.isDense();
    }

    @Override
    public long nextGroupId()
    {
        assert isDense();
        return nextRelationshipId();
    }

    @Override
    public long nextRelationshipId()
    {
        return nodeRecord.getNextRel();
    }

    @Override
    public long nextPropertyId()
    {
        return nodeRecord.getNextProp();
    }

    @Override
    public Lock lock()
    {
        return stateView.nodeIsAddedInThisTx( id() ) ? NO_LOCK : acquireLock();
    }

    private Lock acquireLock()
    {
        Lock lock = lockService.acquireNodeLock( nodeRecord.getId(), LockService.LockType.READ_LOCK );
        if ( lockService != NO_LOCK_SERVICE )
        {
            boolean success = false;
            try
            {
                // It's safer to re-read the node record here, specifically nextProp, after acquiring the lock
                if ( !nodeStore.readRecord( nodeRecord.getId(), nodeRecord, CHECK, pageCursor ).inUse() )
                {
                    // So it looks like the node has been deleted. The current behavior of NodeStore#loadRecord
                    // is to only set the inUse field on loading an unused record. This should (and will)
                    // change to be more of a centralized behavior by the stores. Anyway, setting this pointer
                    // to the primitive equivalent of null the property cursor will just look empty from the
                    // outside and the releasing of the lock will be done as usual.
                    nodeRecord.setNextProp( Record.NO_NEXT_PROPERTY.intValue() );
                }
                success = true;
            }
            finally
            {
                if ( !success )
                {
                    lock.release();
                }
            }
        }
        return lock;
    }
}
