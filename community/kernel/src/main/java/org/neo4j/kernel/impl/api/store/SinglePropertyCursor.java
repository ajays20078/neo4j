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

import java.util.function.Consumer;

import org.neo4j.kernel.api.properties.DefinedProperty;
import org.neo4j.kernel.impl.locking.Lock;
import org.neo4j.kernel.impl.store.PropertyStore;
import org.neo4j.storageengine.api.txstate.PropertyContainerState;

public class SinglePropertyCursor extends AbstractPropertyCursor
{
    private final Consumer<SinglePropertyCursor> consumer;
    private int propertyKeyId;

    SinglePropertyCursor( PropertyStore propertyStore, Consumer<SinglePropertyCursor> consumer )
    {
        super( propertyStore  );
        this.consumer = consumer;
    }

    public SinglePropertyCursor init( int propertyKeyId, long firstPropertyId, Lock lock,
            PropertyContainerState state )
    {
        this.propertyKeyId = propertyKeyId;
        initialize( key -> key == propertyKeyId, firstPropertyId, lock, state );
        return this;
    }

    @Override
    protected boolean loadNextFromDisk()
    {
        // no if we have already fetched the property
        if ( fetched )
        {
            return false;
        }

        // go to disk if the state does not contain the keyId we are looking for
        return state.getAddedProperty( propertyKeyId ) == null;
    }

    @Override
    protected DefinedProperty nextAdded()
    {
        return !fetched ? (DefinedProperty) state.getAddedProperty( propertyKeyId ) : null;
    }

    @Override
    protected void doClose()
    {
        consumer.accept( this );
    }
}
