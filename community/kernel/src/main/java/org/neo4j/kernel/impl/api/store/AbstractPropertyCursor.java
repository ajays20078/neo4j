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

import java.util.function.IntPredicate;

import org.neo4j.cursor.Cursor;
import org.neo4j.function.Disposable;
import org.neo4j.io.pagecache.PageCursor;
import org.neo4j.kernel.api.properties.DefinedProperty;
import org.neo4j.kernel.impl.locking.Lock;
import org.neo4j.kernel.impl.store.PropertyStore;
import org.neo4j.kernel.impl.store.record.PropertyRecord;
import org.neo4j.kernel.impl.store.record.Record;
import org.neo4j.storageengine.api.PropertyItem;
import org.neo4j.storageengine.api.txstate.PropertyContainerState;

import static org.neo4j.kernel.api.StatementConstants.NO_SUCH_PROPERTY;
import static org.neo4j.kernel.impl.store.record.RecordLoad.FORCE;

public abstract class AbstractPropertyCursor implements PropertyItem, Cursor<PropertyItem>, Disposable
{
    private final PropertyPayloadCursor payload;
    private final PageCursor cursor;
    private final PropertyRecord record;
    private final PropertyStore propertyStore;

    protected boolean fetched;
    private boolean doneTraversingTheChain;
    private DefinedProperty property;
    private IntPredicate propertyKeyIds;
    private long nextPropertyId;
    private Lock lock;
    protected PropertyContainerState state;

    AbstractPropertyCursor( PropertyStore propertyStore )
    {
        this.cursor = propertyStore.newPageCursor();
        this.propertyStore = propertyStore;
        this.record = propertyStore.newRecord();
        this.payload = new PropertyPayloadCursor( propertyStore.getStringStore(), propertyStore.getArrayStore() );
    }

    protected final void initialize( IntPredicate propertyKeyIds, long firstPropertyId, Lock lock,
            PropertyContainerState state )
    {
        this.propertyKeyIds = propertyKeyIds;
        this.nextPropertyId = firstPropertyId;
        this.lock = lock;
        this.state = state;
    }

    @Override
    public final boolean next()
    {
        return fetched = fetchNext();
    }

    private boolean fetchNext()
    {
        property = null;
        while ( !doneTraversingTheChain && loadNextFromDisk() )
        {
            // Are there more properties to return for this current record we're at?
            if ( payloadHasNext() )
            {
                return true;
            }

            // No, OK continue down the chain and hunt for more...
            if ( Record.NO_NEXT_PROPERTY.is( nextPropertyId ) )
            {
                // No more records in this chain, i.e. no more properties.
                doneTraversingTheChain = true;
            }
            else
            {
                PropertyRecord propertyRecord = propertyStore.readRecord( nextPropertyId, record, FORCE, cursor );
                nextPropertyId = propertyRecord.getNextProp();
                if ( propertyRecord.inUse() )
                {
                    payload.init( propertyKeyIds, propertyRecord.getBlocks(), propertyRecord.getNumberOfBlocks() );
                    if ( payloadHasNext() )
                    {
                        return true;
                    }
                }
            }

            // Sort of alright, this record isn't in use, but could just be due to concurrent delete.
            // Continue to next record in the chain and try there.
        }

        assert property == null;
        return (property = nextAdded()) != null;
    }

    private boolean payloadHasNext()
    {
        boolean next = payload.next();
        while ( next )
        {
            int propertyKeyId = payload.propertyKeyId();
            if ( !state.isPropertyRemoved( propertyKeyId ) )
            {
                assert property == null;
                property = (DefinedProperty) state.getChangedProperty( propertyKeyId );
                return true;
            }
            next = payload.next();
        }
        return false;
    }

    protected abstract boolean loadNextFromDisk();

    protected abstract DefinedProperty nextAdded();

    @Override
    public final PropertyItem get()
    {
        if ( !fetched )
        {
            throw new IllegalStateException( "No property has been fetched!" );
        }

        return this;
    }

    @Override
    public final int propertyKeyId()
    {
        return property == null ? payload.propertyKeyId() : property.propertyKeyId();
    }

    @Override
    public final Object value()
    {
        return property == null ? payload.value() : property.value();
    }

    @Override
    public final void close()
    {
        try
        {
            fetched = false;
            doneTraversingTheChain = false;
            payload.close();
            propertyKeyIds = null;
            property = null;
            state = null;
            nextPropertyId = NO_SUCH_PROPERTY;
            doClose();
        }
        finally
        {
            lock.release();
            lock = null;
        }
    }

    protected abstract void doClose();

    @Override
    public void dispose()
    {
        cursor.close();
        payload.dispose();
    }
}
