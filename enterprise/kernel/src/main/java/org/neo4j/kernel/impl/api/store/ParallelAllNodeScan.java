/*
 * Copyright (c) 2002-2017 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.impl.api.store;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.neo4j.kernel.impl.store.NodeStore;
import org.neo4j.storageengine.api.BatchingLongProgression;

class ParallelAllNodeScan implements BatchingLongProgression
{
    private final NodeStore nodeStore;
    private final AtomicLong nextPageId;
    private final long lastPageId;

    private final AtomicBoolean done = new AtomicBoolean();
    private final AtomicBoolean append = new AtomicBoolean( true );

    ParallelAllNodeScan( NodeStore nodeStore )
    {
        this.nodeStore = nodeStore;
        // start from the page containing the first non reserved id
        this.nextPageId = new AtomicLong( nodeStore.pageIdForRecord( nodeStore.getNumberOfReservedLowIds() ) );
        // last page to process is the one containing the highest id in use
        this.lastPageId = nodeStore.pageIdForRecord( nodeStore.getHighestPossibleIdInUse() );
    }

    @Override
    public boolean nextBatch( Batch batch )
    {
        while ( true )
        {
            if ( done.get() )
            {
                batch.nothing();
                return false;
            }

            long pageId = nextPageId.getAndIncrement();
            if ( pageId < lastPageId )
            {
                long first = nodeStore.firstRecordOnPage( pageId );
                long last = nodeStore.firstRecordOnPage( pageId + 1 ) - 1;
                batch.init( first, last );
                return true;
            }
            else if ( !done.get() && done.compareAndSet( false, true ) )
            {
                long first = nodeStore.firstRecordOnPage( lastPageId );
                long last = nodeStore.getHighestPossibleIdInUse();
                batch.init( first, last );
                return true;
            }
        }
    }

    @Override
    public boolean appendAdded()
    {
        return append.compareAndSet( true, false );
    }

    @Override
    public boolean fetchAdded()
    {
        return false;
    }
}
