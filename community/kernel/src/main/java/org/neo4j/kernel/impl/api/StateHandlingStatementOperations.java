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
package org.neo4j.kernel.impl.api;

import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;

import org.neo4j.collection.primitive.PrimitiveIntCollection;
import org.neo4j.collection.primitive.PrimitiveIntIterator;
import org.neo4j.collection.primitive.PrimitiveIntSet;
import org.neo4j.collection.primitive.PrimitiveIntStack;
import org.neo4j.collection.primitive.PrimitiveLongIterator;
import org.neo4j.collection.primitive.PrimitiveLongResourceIterator;
import org.neo4j.cursor.Cursor;
import org.neo4j.graphdb.NotFoundException;
import org.neo4j.kernel.api.DataWriteOperations;
import org.neo4j.kernel.api.LegacyIndex;
import org.neo4j.kernel.api.LegacyIndexHits;
import org.neo4j.kernel.api.Statement;
import org.neo4j.kernel.api.exceptions.EntityNotFoundException;
import org.neo4j.kernel.api.exceptions.InvalidTransactionTypeKernelException;
import org.neo4j.kernel.api.exceptions.LabelNotFoundKernelException;
import org.neo4j.kernel.api.exceptions.PropertyKeyIdNotFoundKernelException;
import org.neo4j.kernel.api.exceptions.RelationshipTypeIdNotFoundKernelException;
import org.neo4j.kernel.api.exceptions.TransactionFailureException;
import org.neo4j.kernel.api.exceptions.index.IndexNotApplicableKernelException;
import org.neo4j.kernel.api.exceptions.index.IndexNotFoundKernelException;
import org.neo4j.kernel.api.exceptions.legacyindex.AutoIndexingKernelException;
import org.neo4j.kernel.api.exceptions.legacyindex.LegacyIndexNotFoundKernelException;
import org.neo4j.kernel.api.exceptions.schema.AlreadyConstrainedException;
import org.neo4j.kernel.api.exceptions.schema.ConstraintValidationException;
import org.neo4j.kernel.api.exceptions.schema.CreateConstraintFailureException;
import org.neo4j.kernel.api.exceptions.schema.DropIndexFailureException;
import org.neo4j.kernel.api.exceptions.schema.IllegalTokenNameException;
import org.neo4j.kernel.api.exceptions.schema.IndexBrokenKernelException;
import org.neo4j.kernel.api.exceptions.schema.SchemaRuleNotFoundException;
import org.neo4j.kernel.api.exceptions.schema.TooManyLabelsException;
import org.neo4j.kernel.api.exceptions.schema.UniquePropertyValueValidationException;
import org.neo4j.kernel.api.index.InternalIndexState;
import org.neo4j.kernel.api.legacyindex.AutoIndexing;
import org.neo4j.kernel.api.properties.DefinedProperty;
import org.neo4j.kernel.api.properties.Property;
import org.neo4j.kernel.api.properties.PropertyKeyIdIterator;
import org.neo4j.kernel.api.schema.IndexQuery;
import org.neo4j.kernel.api.schema.LabelSchemaDescriptor;
import org.neo4j.kernel.api.schema.OrderedPropertyValues;
import org.neo4j.kernel.api.schema.RelationTypeSchemaDescriptor;
import org.neo4j.kernel.api.schema.SchemaDescriptor;
import org.neo4j.kernel.api.schema.SchemaUtil;
import org.neo4j.kernel.api.schema.constaints.ConstraintDescriptor;
import org.neo4j.kernel.api.schema.constaints.ConstraintDescriptorFactory;
import org.neo4j.kernel.api.schema.constaints.IndexBackedConstraintDescriptor;
import org.neo4j.kernel.api.schema.constaints.NodeExistenceConstraintDescriptor;
import org.neo4j.kernel.api.schema.constaints.NodeKeyConstraintDescriptor;
import org.neo4j.kernel.api.schema.constaints.RelExistenceConstraintDescriptor;
import org.neo4j.kernel.api.schema.constaints.UniquenessConstraintDescriptor;
import org.neo4j.kernel.api.schema.index.IndexDescriptor;
import org.neo4j.kernel.api.schema.index.IndexDescriptorFactory;
import org.neo4j.kernel.api.txstate.TransactionCountingStateVisitor;
import org.neo4j.kernel.impl.api.operations.CountsOperations;
import org.neo4j.kernel.impl.api.operations.EntityOperations;
import org.neo4j.kernel.impl.api.operations.KeyReadOperations;
import org.neo4j.kernel.impl.api.operations.KeyWriteOperations;
import org.neo4j.kernel.impl.api.operations.LegacyIndexReadOperations;
import org.neo4j.kernel.impl.api.operations.LegacyIndexWriteOperations;
import org.neo4j.kernel.impl.api.operations.SchemaReadOperations;
import org.neo4j.kernel.impl.api.operations.SchemaWriteOperations;
import org.neo4j.kernel.impl.api.state.ConstraintIndexCreator;
import org.neo4j.kernel.impl.api.state.IndexTxStateUpdater;
import org.neo4j.kernel.impl.api.store.RelationshipIterator;
import org.neo4j.kernel.impl.index.IndexEntityType;
import org.neo4j.kernel.impl.index.LegacyIndexStore;
import org.neo4j.register.Register.DoubleLongRegister;
import org.neo4j.storageengine.api.BatchingLongProgression;
import org.neo4j.storageengine.api.Direction;
import org.neo4j.storageengine.api.EntityType;
import org.neo4j.storageengine.api.NodeItem;
import org.neo4j.storageengine.api.PropertyItem;
import org.neo4j.storageengine.api.RelationshipItem;
import org.neo4j.storageengine.api.StorageProperty;
import org.neo4j.storageengine.api.StoreReadLayer;
import org.neo4j.storageengine.api.Token;
import org.neo4j.storageengine.api.schema.IndexReader;
import org.neo4j.storageengine.api.schema.PopulationProgress;
import org.neo4j.storageengine.api.txstate.NodeState;
import org.neo4j.storageengine.api.txstate.ReadableDiffSets;
import org.neo4j.storageengine.api.txstate.ReadableTransactionState;
import org.neo4j.storageengine.api.txstate.RelationshipState;

import static java.lang.String.format;
import static org.neo4j.collection.primitive.PrimitiveIntCollections.filter;
import static org.neo4j.collection.primitive.PrimitiveLongCollections.resourceIterator;
import static org.neo4j.collection.primitive.PrimitiveLongCollections.single;
import static org.neo4j.helpers.collection.Iterators.filter;
import static org.neo4j.helpers.collection.Iterators.iterator;
import static org.neo4j.helpers.collection.Iterators.singleOrNull;
import static org.neo4j.kernel.api.StatementConstants.NO_SUCH_NODE;
import static org.neo4j.kernel.api.properties.DefinedProperty.NO_SUCH_PROPERTY;
import static org.neo4j.kernel.impl.api.state.IndexTxStateUpdater.LabelChangeType.ADDED_LABEL;
import static org.neo4j.kernel.impl.api.state.IndexTxStateUpdater.LabelChangeType.REMOVED_LABEL;
import static org.neo4j.register.Registers.newDoubleLongRegister;
import static org.neo4j.storageengine.api.txstate.TxStateVisitor.EMPTY;

public class StateHandlingStatementOperations
        implements KeyReadOperations, KeyWriteOperations, EntityOperations, SchemaReadOperations, SchemaWriteOperations,
        CountsOperations, LegacyIndexReadOperations, LegacyIndexWriteOperations
{
    private final StoreReadLayer storeLayer;
    private final AutoIndexing autoIndexing;
    private final ConstraintIndexCreator constraintIndexCreator;
    private final LegacyIndexStore legacyIndexStore;
    private final IndexTxStateUpdater indexTxStateUpdater;

    public StateHandlingStatementOperations( StoreReadLayer storeLayer, AutoIndexing propertyTrackers,
            ConstraintIndexCreator constraintIndexCreator, LegacyIndexStore legacyIndexStore )
    {
        this.storeLayer = storeLayer;
        this.autoIndexing = propertyTrackers;
        this.constraintIndexCreator = constraintIndexCreator;
        this.legacyIndexStore = legacyIndexStore;
        this.indexTxStateUpdater = new IndexTxStateUpdater( storeLayer, this );
    }

    // <Cursors>

    @Override
    public BatchingLongProgression parallelNodeScanProgression( KernelStatement statement )
    {
        return storeLayer.parallelNodeScanProgression();
    }

    @Override
    public Cursor<NodeItem> nodeGetCursor( KernelStatement statement, BatchingLongProgression progression )
    {
        return storeLayer.nodeGetCursor( progression, statement.readableTxState() );
    }

    @Override
    public Cursor<NodeItem> nodeGetAllCursor( KernelStatement statement )
    {
        return storeLayer.nodeGetAllCursor( statement.readableTxState() );
    }

    @Override
    public Cursor<NodeItem> nodeCursorById( KernelStatement statement, long nodeId ) throws EntityNotFoundException
    {
        Cursor<NodeItem> node = storeLayer.nodeGetSingleCursor( nodeId, statement.readableTxState() );
        if ( !node.next() )
        {
            node.close();
            throw new EntityNotFoundException( EntityType.NODE, nodeId );
        }
        return node;
    }

    @Override
    public Cursor<RelationshipItem> relationshipCursorById( KernelStatement statement, long relationshipId )
            throws EntityNotFoundException
    {
        Cursor<RelationshipItem> relationship =
                storeLayer.relationshipGetSingleCursor( relationshipId, statement.readableTxState() );
        if ( !relationship.next() )
        {
            relationship.close();
            throw new EntityNotFoundException( EntityType.RELATIONSHIP, relationshipId );
        }
        return relationship;
    }

    @Override
    public Cursor<RelationshipItem> relationshipCursorGetAll( KernelStatement statement )
    {
        ReadableTransactionState state = statement.readableTxState();
        return storeLayer.relationshipsGetAllCursor( state );
    }

    @Override
    public Cursor<RelationshipItem> nodeGetRelationships( KernelStatement statement, NodeItem node,
            Direction direction )
    {
        ReadableTransactionState state = statement.readableTxState();
        return storeLayer.nodeGetRelationships( node, direction, state );
    }

    @Override
    public Cursor<RelationshipItem> nodeGetRelationships( KernelStatement statement, NodeItem node, Direction direction,
            int[] relTypes )
    {
        ReadableTransactionState state = statement.readableTxState();
        return storeLayer.nodeGetRelationships( node, direction, relTypes, state );
    }

    @Override
    public Cursor<PropertyItem> nodeGetProperties( KernelStatement statement, NodeItem node )
    {
        NodeState nodeState = statement.readableTxState().getNodeState( node.id() );
        return storeLayer.nodeGetProperties( node, nodeState );
    }

    @Override
    public PrimitiveIntCollection nodeGetPropertyKeys( KernelStatement statement, NodeItem node )
    {
        PrimitiveIntStack keys = new PrimitiveIntStack();
        try ( Cursor<PropertyItem> properties = nodeGetProperties( statement, node ) )
        {
            while ( properties.next() )
            {
                keys.push( properties.get().propertyKeyId() );
            }
        }

        return keys;
    }

    @Override
    public Object nodeGetProperty( KernelStatement statement, NodeItem node, int propertyKeyId )
    {
        try ( Cursor<PropertyItem> cursor = nodeGetPropertyCursor( statement, node, propertyKeyId ) )
        {
            if ( cursor.next() )
            {
                return cursor.get().value();
            }
        }
        catch ( NotFoundException e )
        {
            return null;
        }

        return null;
    }

    @Override
    public boolean nodeHasProperty( KernelStatement statement, NodeItem node, int propertyKeyId )
    {
        try ( Cursor<PropertyItem> cursor = nodeGetPropertyCursor( statement, node, propertyKeyId ) )
        {
            return cursor.next();
        }
    }

    private Cursor<PropertyItem> nodeGetPropertyCursor( KernelStatement statement, NodeItem node, int propertyKeyId )
    {
        NodeState nodeState = statement.readableTxState().getNodeState( node.id() );
        return storeLayer.nodeGetProperty( node, propertyKeyId, nodeState );
    }

    @Override
    public Cursor<PropertyItem> relationshipGetProperties( KernelStatement statement, RelationshipItem relationship )
    {
        RelationshipState relationshipState = statement.readableTxState().getRelationshipState( relationship.id() );
        return storeLayer.relationshipGetProperties( relationship, relationshipState );
    }

    @Override
    public PrimitiveIntCollection relationshipGetPropertyKeys( KernelStatement statement,
            RelationshipItem relationship )
    {
        PrimitiveIntStack keys = new PrimitiveIntStack();
        try ( Cursor<PropertyItem> properties = relationshipGetProperties( statement, relationship ) )
        {
            while ( properties.next() )
            {
                keys.push( properties.get().propertyKeyId() );
            }
        }

        return keys;
    }

    @Override
    public Object relationshipGetProperty( KernelStatement statement, RelationshipItem relationship, int propertyKeyId )
    {
        try ( Cursor<PropertyItem> cursor = relationshipGetPropertyCursor( statement, relationship, propertyKeyId ) )
        {
            if ( cursor.next() )
            {
                return cursor.get().value();
            }
        }
        catch ( NotFoundException e )
        {
            return null;
        }

        return null;
    }

    @Override
    public boolean relationshipHasProperty( KernelStatement statement, RelationshipItem relationship,
            int propertyKeyId )
    {
        try ( Cursor<PropertyItem> cursor = relationshipGetPropertyCursor( statement, relationship, propertyKeyId ) )
        {
            return cursor.next();
        }
    }

    private Cursor<PropertyItem> relationshipGetPropertyCursor( KernelStatement statement,
            RelationshipItem relationship, int propertyKeyId )
    {
        RelationshipState relationshipState = statement.readableTxState().getRelationshipState( relationship.id() );
        return storeLayer.relationshipGetProperty( relationship, propertyKeyId, relationshipState );
    }

    // </Cursors>

    @Override
    public long nodeCreate( KernelStatement state )
    {
        long nodeId = storeLayer.reserveNode();
        state.writableTxState().nodeDoCreate( nodeId );
        return nodeId;
    }

    @Override
    public void nodeDelete( KernelStatement state, long nodeId )
            throws AutoIndexingKernelException, EntityNotFoundException, InvalidTransactionTypeKernelException
    {
        autoIndexing.nodes().entityRemoved( state.dataWriteOperations(), nodeId );
        try ( Cursor<NodeItem> cursor = nodeCursorById( state, nodeId ) )
        {
            state.writableTxState().nodeDoDelete( cursor.get().id() );
        }
    }

    @Override
    public int nodeDetachDelete( KernelStatement state, long nodeId )
            throws EntityNotFoundException, AutoIndexingKernelException, InvalidTransactionTypeKernelException
    {
        nodeDelete( state, nodeId );
        return 0;
    }

    @Override
    public long relationshipCreate( KernelStatement state, int relationshipTypeId, long startNodeId, long endNodeId )
            throws EntityNotFoundException
    {
        try ( Cursor<NodeItem> startNode = nodeCursorById( state, startNodeId ) )
        {
            try ( Cursor<NodeItem> endNode = nodeCursorById( state, endNodeId ) )
            {
                long id = storeLayer.reserveRelationship();
                state.writableTxState()
                        .relationshipDoCreate( id, relationshipTypeId, startNode.get().id(), endNode.get().id() );
                return id;
            }
        }
    }

    @Override
    public void relationshipDelete( final KernelStatement state, long relationshipId )
            throws EntityNotFoundException, InvalidTransactionTypeKernelException, AutoIndexingKernelException
    {
        try ( Cursor<RelationshipItem> cursor = relationshipCursorById( state, relationshipId ) )
        {
            RelationshipItem relationship = cursor.get();

            // NOTE: We implicitly delegate to neoStoreTransaction via txState.legacyState here. This is because that
            // call returns modified properties, which node manager uses to update legacy tx state. This will be cleaned up
            // once we've removed legacy tx state.
            autoIndexing.relationships().entityRemoved( state.dataWriteOperations(), relationshipId );
            if ( state.readableTxState().relationshipIsAddedInThisTx( relationship.id() ) )
            {
                state.writableTxState().relationshipDoDeleteAddedInThisTx( relationship.id() );
            }
            else
            {
                state.writableTxState()
                        .relationshipDoDelete( relationship.id(), relationship.type(), relationship.startNode(),
                                relationship.endNode() );
            }
        }
    }

    @Override
    public PrimitiveLongIterator nodesGetAll( KernelStatement state )
    {
        return state.readableTxState().augmentNodesGetAll( storeLayer.nodesGetAll() );
    }

    @Override
    public RelationshipIterator relationshipsGetAll( KernelStatement state )
    {
        return state.readableTxState().augmentRelationshipsGetAll( storeLayer.relationshipsGetAll() );
    }

    @Override
    public boolean nodeAddLabel( KernelStatement state, long nodeId, int labelId ) throws EntityNotFoundException
    {
        try ( Cursor<NodeItem> cursor = nodeCursorById( state, nodeId ) )
        {
            NodeItem node = cursor.get();
            if ( node.hasLabel( labelId ) )
            {
                // Label is already in state or in store, no-op
                return false;
            }

            state.writableTxState().nodeDoAddLabel( labelId, node.id() );

            indexTxStateUpdater.onLabelChange( state, labelId, node, ADDED_LABEL );

            return true;
        }
    }

    @Override
    public boolean nodeRemoveLabel( KernelStatement state, long nodeId, int labelId ) throws EntityNotFoundException
    {
        try ( Cursor<NodeItem> cursor = nodeCursorById( state, nodeId ) )
        {
            NodeItem node = cursor.get();
            if ( !node.hasLabel( labelId ) )
            {
                // Label does not exist in state or in store, no-op
                return false;
            }

            state.writableTxState().nodeDoRemoveLabel( labelId, node.id() );

            indexTxStateUpdater.onLabelChange( state, labelId, node, REMOVED_LABEL );
            return true;
        }
    }

    @Override
    public PrimitiveLongIterator nodesGetForLabel( KernelStatement state, int labelId )
    {
        PrimitiveLongIterator source = storeLayer.nodesGetForLabel( state.schemaResources(), labelId );
        PrimitiveLongIterator wLabelChanges =
                state.readableTxState().nodesWithLabelChanged( labelId ).augment( source );
        return state.readableTxState().addedAndRemovedNodes().augmentWithRemovals( wLabelChanges );
    }

    @Override
    public long nodesGetCount( KernelStatement state )
    {
        return storeLayer.nodesGetCount() + state.readableTxState().addedAndRemovedNodes().delta();
    }

    @Override
    public long relationshipsGetCount( KernelStatement state )
    {
        return storeLayer.relationshipsGetCount() + state.readableTxState().addedAndRemovedRelationships().delta();
    }

    @Override
    public IndexDescriptor indexCreate( KernelStatement state, LabelSchemaDescriptor descriptor )
    {
        IndexDescriptor indexDescriptor = IndexDescriptorFactory.forSchema( descriptor );
        state.writableTxState().indexRuleDoAdd( indexDescriptor );
        return indexDescriptor;
    }

    @Override
    public void indexDrop( KernelStatement state, IndexDescriptor descriptor ) throws DropIndexFailureException
    {
        state.writableTxState().indexDoDrop( descriptor );
    }

    @Override
    public void uniqueIndexDrop( KernelStatement state, IndexDescriptor descriptor ) throws DropIndexFailureException
    {
        state.writableTxState().indexDoDrop( descriptor );
    }

    private IndexBackedConstraintDescriptor indexBackedConstraintCreate( KernelStatement state, IndexBackedConstraintDescriptor constraint )
            throws CreateConstraintFailureException
    {
        LabelSchemaDescriptor descriptor = constraint.schema();
        try
        {
            if ( state.readableTxState().hasChanges() &&
                 state.writableTxState().indexDoUnRemove( constraint.ownedIndexDescriptor() ) ) // ..., DROP, *CREATE*
            { // creation is undoing a drop
                if ( !state.writableTxState().constraintDoUnRemove( constraint ) ) // CREATE, ..., DROP, *CREATE*
                { // ... the drop we are undoing did itself undo a prior create...
                    state.writableTxState().constraintDoAdd( constraint,
                            state.readableTxState().indexCreatedForConstraint( constraint ) );
                }
            }
            else // *CREATE*
            { // create from scratch
                Iterator<ConstraintDescriptor> it = storeLayer.constraintsGetForSchema( descriptor );
                while ( it.hasNext() )
                {
                    if ( it.next().equals( constraint ) )
                    {
                        return constraint;
                    }
                }
                long indexId = constraintIndexCreator.createUniquenessConstraintIndex( state, this, descriptor );
                state.writableTxState().constraintDoAdd( constraint, indexId );
            }
            return constraint;
        }
        catch ( UniquePropertyValueValidationException |
                DropIndexFailureException |
                TransactionFailureException |
                AlreadyConstrainedException e )
        {
            throw new CreateConstraintFailureException( constraint, e );
        }
    }

    @Override
    public NodeKeyConstraintDescriptor nodeKeyConstraintCreate( KernelStatement state,
            LabelSchemaDescriptor descriptor )
            throws CreateConstraintFailureException
    {
        NodeKeyConstraintDescriptor constraint = ConstraintDescriptorFactory.nodeKeyForSchema( descriptor );
        indexBackedConstraintCreate( state, constraint );
        return constraint;
    }

    @Override
    public UniquenessConstraintDescriptor uniquePropertyConstraintCreate( KernelStatement state, LabelSchemaDescriptor descriptor )
            throws CreateConstraintFailureException
    {
        UniquenessConstraintDescriptor constraint = ConstraintDescriptorFactory.uniqueForSchema( descriptor );
        indexBackedConstraintCreate( state, constraint );
        return constraint;
    }

    @Override
    public NodeExistenceConstraintDescriptor nodePropertyExistenceConstraintCreate(
            KernelStatement state,
            LabelSchemaDescriptor descriptor
    ) throws CreateConstraintFailureException
    {
        NodeExistenceConstraintDescriptor constraint = ConstraintDescriptorFactory.existsForSchema( descriptor );
        state.writableTxState().constraintDoAdd( constraint );
        return constraint;
    }

    @Override
    public RelExistenceConstraintDescriptor relationshipPropertyExistenceConstraintCreate(
            KernelStatement state,
            RelationTypeSchemaDescriptor descriptor
    ) throws AlreadyConstrainedException, CreateConstraintFailureException
    {
        RelExistenceConstraintDescriptor constraint = ConstraintDescriptorFactory.existsForSchema( descriptor );
        state.writableTxState().constraintDoAdd( constraint );
        return constraint;
    }

    @Override
    public Iterator<ConstraintDescriptor> constraintsGetForSchema( KernelStatement state, SchemaDescriptor descriptor )
    {
        Iterator<ConstraintDescriptor> constraints = storeLayer.constraintsGetForSchema( descriptor );
        return state.readableTxState().constraintsChangesForSchema( descriptor ).apply( constraints );
    }

    @Override
    public boolean constraintExists( KernelStatement state, ConstraintDescriptor descriptor )
    {
        boolean inStore = storeLayer.constraintExists( descriptor );
        ReadableDiffSets<ConstraintDescriptor> diffSet =
                state.readableTxState().constraintsChangesForSchema( descriptor.schema() );
        return diffSet.isAdded( descriptor ) || (inStore && !diffSet.isRemoved( descriptor ));
    }

    @Override
    public Iterator<ConstraintDescriptor> constraintsGetForLabel( KernelStatement state, int labelId )
    {
        Iterator<ConstraintDescriptor> constraints = storeLayer.constraintsGetForLabel( labelId );
        return state.readableTxState().constraintsChangesForLabel( labelId ).apply( constraints );
    }

    @Override
    public Iterator<ConstraintDescriptor> constraintsGetForRelationshipType( KernelStatement state,
            int typeId )
    {
        Iterator<ConstraintDescriptor> constraints = storeLayer.constraintsGetForRelationshipType( typeId );
        return state.readableTxState().constraintsChangesForRelationshipType( typeId ).apply( constraints );
    }

    @Override
    public Iterator<ConstraintDescriptor> constraintsGetAll( KernelStatement state )
    {
        Iterator<ConstraintDescriptor> constraints = storeLayer.constraintsGetAll();
        return state.readableTxState().constraintsChanges().apply( constraints );
    }

    @Override
    public void constraintDrop( KernelStatement state, ConstraintDescriptor constraint )
    {
        state.writableTxState().constraintDoDrop( constraint );
    }

    @Override
    public IndexDescriptor indexGetForSchema( KernelStatement state, LabelSchemaDescriptor descriptor )
    {
        IndexDescriptor indexDescriptor = storeLayer.indexGetForSchema( descriptor );
        Iterator<IndexDescriptor> rules = filter( SchemaDescriptor.equalTo( descriptor ),
                state.readableTxState().indexDiffSetsByLabel( descriptor.getLabelId() )
                        .apply( iterator( indexDescriptor ) ) );
        return singleOrNull( rules );
    }

    @Override
    public InternalIndexState indexGetState( KernelStatement state, IndexDescriptor descriptor )
            throws IndexNotFoundKernelException
    {
        // If index is in our state, then return populating
        if ( checkIndexState( descriptor,
                state.readableTxState().indexDiffSetsByLabel( descriptor.schema().getLabelId() ) ) )
        {
            return InternalIndexState.POPULATING;
        }

        return storeLayer.indexGetState( descriptor );
    }

    @Override
    public PopulationProgress indexGetPopulationProgress( KernelStatement state, IndexDescriptor descriptor )
            throws IndexNotFoundKernelException
    {
        // If index is in our state, then return 0%
        if ( checkIndexState( descriptor,
                state.readableTxState().indexDiffSetsByLabel( descriptor.schema().getLabelId() ) ) )
        {
            return PopulationProgress.NONE;
        }

        return storeLayer.indexGetPopulationProgress( descriptor.schema() );
    }

    private boolean checkIndexState( IndexDescriptor index, ReadableDiffSets<IndexDescriptor> diffSet )
            throws IndexNotFoundKernelException
    {
        if ( diffSet.isAdded( index ) )
        {
            return true;
        }
        if ( diffSet.isRemoved( index ) )
        {
            throw new IndexNotFoundKernelException( format( "Index on %s has been dropped in this transaction.",
                    index.userDescription( SchemaUtil.idTokenNameLookup ) ) );
        }
        return false;
    }

    @Override
    public Iterator<IndexDescriptor> indexesGetForLabel( KernelStatement state, int labelId )
    {
        return state.readableTxState().indexDiffSetsByLabel( labelId )
                .apply( storeLayer.indexesGetForLabel( labelId ) );
    }

    @Override
    public Iterator<IndexDescriptor> indexesGetAll( KernelStatement state )
    {
        return state.readableTxState().indexChanges().apply( storeLayer.indexesGetAll() );
    }

    @Override
    public long nodeGetFromUniqueIndexSeek( KernelStatement statement, IndexDescriptor index,
            IndexQuery.ExactPredicate... query )
            throws IndexNotFoundKernelException, IndexBrokenKernelException, IndexNotApplicableKernelException
    {
        IndexReader reader = storeLayer.indexGetFreshReader( statement.schemaResources(), index );

        /* Here we have an intricate scenario where we need to return the PrimitiveLongIterator
         * since subsequent filtering will happen outside, but at the same time have the ability to
         * close the IndexReader when done iterating over the lookup result. This is because we get
         * a fresh reader that isn't associated with the current transaction and hence will not be
         * automatically closed. */
        PrimitiveLongResourceIterator committed = resourceIterator( reader.query( query ), reader );
        PrimitiveLongIterator exactMatches = LookupFilter.exactIndexMatches( this, statement, committed, query );
        PrimitiveLongIterator changesFiltered =
                filterIndexStateChangesForSeek( statement, exactMatches, index, OrderedPropertyValues.of( query ) );
        return single( resourceIterator( changesFiltered, committed ), NO_SUCH_NODE );
    }

    @Override
    public PrimitiveLongIterator indexQuery( KernelStatement statement, IndexDescriptor index,
            IndexQuery... predicates ) throws IndexNotFoundKernelException, IndexNotApplicableKernelException
    {
        IndexReader reader = storeLayer.indexGetReader( statement.schemaResources(), index );
        PrimitiveLongIterator committed = reader.query( predicates );
        PrimitiveLongIterator exactMatches = LookupFilter.exactIndexMatches( this, statement, committed, predicates );

        IndexQuery firstPredicate = predicates[0];
        switch ( firstPredicate.type() )
        {
        case exact:
            IndexQuery.ExactPredicate[] exactPreds = assertOnlyExactPredicates( predicates );
            return filterIndexStateChangesForSeek( statement, exactMatches, index, OrderedPropertyValues.of( exactPreds ) );
        case stringSuffix:
        case stringContains:
        case exists:
            return filterIndexStateChangesForScan( statement, exactMatches, index );

        case rangeNumeric:
        {
            assertSinglePredicate( predicates );
            IndexQuery.NumberRangePredicate numPred = (IndexQuery.NumberRangePredicate) firstPredicate;
            return filterIndexStateChangesForRangeSeekByNumber( statement, index, numPred.from(),
                    numPred.fromInclusive(), numPred.to(), numPred.toInclusive(), exactMatches );
        }
        case rangeString:
        {
            assertSinglePredicate( predicates );
            IndexQuery.StringRangePredicate strPred = (IndexQuery.StringRangePredicate) firstPredicate;
            return filterIndexStateChangesForRangeSeekByString(
                    statement, index, strPred.from(), strPred.fromInclusive(), strPred.to(),
                    strPred.toInclusive(), committed );
        }
        case stringPrefix:
        {
            assertSinglePredicate( predicates );
            IndexQuery.StringPrefixPredicate strPred = (IndexQuery.StringPrefixPredicate) firstPredicate;
            return filterIndexStateChangesForRangeSeekByPrefix( statement, index, strPred.prefix(), committed );
        }
        default:
            throw new UnsupportedOperationException( "Query not supported: " + Arrays.toString( predicates ) );
        }
    }

    private IndexQuery.ExactPredicate[] assertOnlyExactPredicates( IndexQuery[] predicates )
    {
        IndexQuery.ExactPredicate[] exactPredicates;
        if ( predicates.getClass() == IndexQuery.ExactPredicate[].class )
        {
            exactPredicates = (IndexQuery.ExactPredicate[]) predicates;
        }
        else
        {
            exactPredicates = new IndexQuery.ExactPredicate[predicates.length];
            for ( int i = 0; i < predicates.length; i++ )
            {
                if ( predicates[i] instanceof IndexQuery.ExactPredicate )
                {
                    exactPredicates[i] = (IndexQuery.ExactPredicate) predicates[i];
                }
                else
                {
                    // TODO: what to throw?
                    throw new UnsupportedOperationException( "Query not supported: " + Arrays.toString( predicates ) );
                }
            }
        }
        return exactPredicates;
    }

    private void assertSinglePredicate( IndexQuery[] predicates )
    {
        if ( predicates.length != 1 )
        {
            // TODO: what to throw?
            throw new UnsupportedOperationException( "Query not supported: " + Arrays.toString( predicates ) );
        }
    }

    @Override
    public long nodesCountIndexed( KernelStatement statement, IndexDescriptor index, long nodeId, Object value )
            throws IndexNotFoundKernelException, IndexBrokenKernelException
    {
        IndexReader reader = storeLayer.indexGetReader( statement.schemaResources(), index );
        return reader.countIndexedNodes( nodeId, value );
    }

    private PrimitiveLongIterator filterIndexStateChangesForScan(
            KernelStatement state, PrimitiveLongIterator nodeIds, IndexDescriptor index )
    {
        ReadableDiffSets<Long> labelPropertyChanges = state.readableTxState().indexUpdatesForScan( index );
        ReadableDiffSets<Long> nodes = state.readableTxState().addedAndRemovedNodes();

        // Apply to actual index lookup
        return nodes.augmentWithRemovals( labelPropertyChanges.augment( nodeIds ) );
    }

    private PrimitiveLongIterator filterIndexStateChangesForSeek( KernelStatement state, PrimitiveLongIterator nodeIds,
            IndexDescriptor index, OrderedPropertyValues propertyValues )
    {
        ReadableDiffSets<Long> labelPropertyChanges =
                state.readableTxState().indexUpdatesForSeek( index, propertyValues );
        ReadableDiffSets<Long> nodes = state.readableTxState().addedAndRemovedNodes();

        // Apply to actual index lookup
        return nodes.augmentWithRemovals( labelPropertyChanges.augment( nodeIds ) );
    }

    private PrimitiveLongIterator filterIndexStateChangesForRangeSeekByNumber( KernelStatement state,
            IndexDescriptor index,
            Number lower, boolean includeLower,
            Number upper, boolean includeUpper,
            PrimitiveLongIterator nodeIds )
    {
        ReadableDiffSets<Long> labelPropertyChangesForNumber = state.readableTxState()
                .indexUpdatesForRangeSeekByNumber( index, lower, includeLower, upper, includeUpper );
        ReadableDiffSets<Long> nodes = state.readableTxState().addedAndRemovedNodes();
        // Apply to actual index lookup
        return nodes.augmentWithRemovals( labelPropertyChangesForNumber.augment( nodeIds ) );
    }

    private PrimitiveLongIterator filterIndexStateChangesForRangeSeekByString( KernelStatement state,
            IndexDescriptor index,
            String lower, boolean includeLower,
            String upper, boolean includeUpper,
            PrimitiveLongIterator nodeIds )
    {
        ReadableDiffSets<Long> labelPropertyChangesForString = state.readableTxState()
                .indexUpdatesForRangeSeekByString( index, lower, includeLower, upper, includeUpper );
        ReadableDiffSets<Long> nodes = state.readableTxState().addedAndRemovedNodes();

        // Apply to actual index lookup
        return nodes.augmentWithRemovals( labelPropertyChangesForString.augment( nodeIds ) );
    }

    private PrimitiveLongIterator filterIndexStateChangesForRangeSeekByPrefix( KernelStatement state,
            IndexDescriptor index,
            String prefix,
            PrimitiveLongIterator nodeIds )
    {
        ReadableDiffSets<Long> labelPropertyChangesForPrefix =
                state.readableTxState().indexUpdatesForRangeSeekByPrefix( index, prefix );
        ReadableDiffSets<Long> nodes = state.readableTxState().addedAndRemovedNodes();

        // Apply to actual index lookup
        return nodes.augmentWithRemovals( labelPropertyChangesForPrefix.augment( nodeIds ) );
    }

    @Override
    public Property nodeSetProperty( KernelStatement state, long nodeId, DefinedProperty property )
            throws EntityNotFoundException, InvalidTransactionTypeKernelException, AutoIndexingKernelException
    {
        DataWriteOperations ops = state.dataWriteOperations();
        try ( Cursor<NodeItem> cursor = nodeCursorById( state, nodeId ) )
        {
            NodeItem node = cursor.get();
            DefinedProperty existingProperty = NO_SUCH_PROPERTY;
            try ( Cursor<PropertyItem> properties = nodeGetPropertyCursor( state, node, property.propertyKeyId() ) )
            {
                if ( !properties.next() )
                {
                    autoIndexing.nodes().propertyAdded( ops, nodeId, property );
                }
                else
                {
                    existingProperty = Property.property( properties.get().propertyKeyId(), properties.get().value() );
                    autoIndexing.nodes().propertyChanged( ops, nodeId, existingProperty, property );
                }
            }

            if ( existingProperty == NO_SUCH_PROPERTY )
            {
                state.writableTxState().nodeDoAddProperty( node.id(), property );
                indexTxStateUpdater.onPropertyAdd( state, node, property );
                return Property.noProperty( property.propertyKeyId(), EntityType.NODE, node.id() );
            }
            else
            {
                if ( !property.equals( existingProperty ) )
                {
                    state.writableTxState().nodeDoChangeProperty( node.id(), existingProperty, property );
                    indexTxStateUpdater.onPropertyChange( state, node, existingProperty, property );
                }
                return existingProperty;
            }
        }
    }

    @Override
    public Property relationshipSetProperty( KernelStatement state, long relationshipId, DefinedProperty property )
            throws EntityNotFoundException, InvalidTransactionTypeKernelException, AutoIndexingKernelException
    {
        DataWriteOperations ops = state.dataWriteOperations();
        try ( Cursor<RelationshipItem> cursor = relationshipCursorById( state, relationshipId ) )
        {
            RelationshipItem relationship = cursor.get();
            Property existingProperty;
            try ( Cursor<PropertyItem> properties = relationshipGetPropertyCursor( state, relationship,
                    property.propertyKeyId() ) )
            {
                if ( !properties.next() )
                {
                    autoIndexing.relationships().propertyAdded( ops, relationshipId, property );
                    existingProperty =
                            Property.noProperty( property.propertyKeyId(), EntityType.RELATIONSHIP, relationship.id() );
                }
                else
                {
                    existingProperty = Property.property( properties.get().propertyKeyId(), properties.get().value() );
                    autoIndexing.relationships().propertyChanged( ops, relationshipId, existingProperty, property );
                }
            }
            if ( !property.equals( existingProperty ) )
            {
                state.writableTxState().relationshipDoReplaceProperty( relationship.id(), existingProperty, property );
            }
            return existingProperty;
        }
    }

    @Override
    public Property graphSetProperty( KernelStatement state, DefinedProperty property )
    {
        Object existingPropertyValue = graphGetProperty( state, property.propertyKeyId() );
        Property existingProperty = existingPropertyValue == null ?
                Property.noGraphProperty( property.propertyKeyId() ) :
                Property.property( property.propertyKeyId(), existingPropertyValue );

        if ( !property.equals( existingProperty ) )
        {
            state.writableTxState().graphDoReplaceProperty( existingProperty, property );
        }

        return existingProperty;
    }

    @Override
    public Property nodeRemoveProperty( KernelStatement state, long nodeId, int propertyKeyId )
            throws EntityNotFoundException, InvalidTransactionTypeKernelException, AutoIndexingKernelException
    {
        DataWriteOperations ops = state.dataWriteOperations();
        try ( Cursor<NodeItem> cursor = nodeCursorById( state, nodeId ) )
        {
            NodeItem node = cursor.get();
            DefinedProperty existingProperty = NO_SUCH_PROPERTY;
            try ( Cursor<PropertyItem> properties = nodeGetPropertyCursor( state, node, propertyKeyId ) )
            {
                if ( properties.next() )
                {
                    existingProperty = Property.property( properties.get().propertyKeyId(), properties.get().value() );

                    autoIndexing.nodes().propertyRemoved( ops, nodeId, propertyKeyId );
                    state.writableTxState().nodeDoRemoveProperty( node.id(), existingProperty );

                    indexTxStateUpdater.onPropertyRemove( state, node, existingProperty );
                }
            }

            if ( existingProperty == NO_SUCH_PROPERTY )
            {
                return Property.noProperty( propertyKeyId, EntityType.NODE, node.id() );
            }
            return existingProperty;
        }
    }

    @Override
    public Property relationshipRemoveProperty( KernelStatement state, long relationshipId, int propertyKeyId )
            throws EntityNotFoundException, InvalidTransactionTypeKernelException, AutoIndexingKernelException
    {
        DataWriteOperations ops = state.dataWriteOperations();
        try ( Cursor<RelationshipItem> cursor = relationshipCursorById( state, relationshipId ) )
        {
            RelationshipItem relationship = cursor.get();
            Property existingProperty;
            try ( Cursor<PropertyItem> properties = relationshipGetPropertyCursor( state, relationship,
                    propertyKeyId ) )
            {
                if ( !properties.next() )
                {
                    existingProperty = Property.noProperty( propertyKeyId, EntityType.RELATIONSHIP, relationship.id() );
                }
                else
                {
                    existingProperty = Property.property( properties.get().propertyKeyId(), properties.get().value() );

                    autoIndexing.relationships().propertyRemoved( ops, relationshipId, propertyKeyId );
                    state.writableTxState()
                            .relationshipDoRemoveProperty( relationship.id(), (DefinedProperty) existingProperty );
                }
            }
            return existingProperty;
        }
    }

    @Override
    public Property graphRemoveProperty( KernelStatement state, int propertyKeyId )
    {
        Object existingPropertyValue = graphGetProperty( state, propertyKeyId );
        if ( existingPropertyValue != null )
        {
            DefinedProperty existingProperty = Property.property( propertyKeyId, existingPropertyValue );
            state.writableTxState().graphDoRemoveProperty( existingProperty );
            return existingProperty;
        }
        return Property.noGraphProperty( propertyKeyId );
    }

    @Override
    public PrimitiveIntIterator graphGetPropertyKeys( KernelStatement state )
    {
        if ( state.readableTxState().hasChanges() )
        {
            return new PropertyKeyIdIterator( graphGetAllProperties( state ) );
        }

        return storeLayer.graphGetPropertyKeys();
    }

    @Override
    public boolean graphHasProperty( KernelStatement state, int propertyKeyId )
    {
        return graphGetProperty( state, propertyKeyId ) != null;
    }

    @Override
    public Object graphGetProperty( KernelStatement state, int propertyKeyId )
    {
        Iterator<StorageProperty> properties = graphGetAllProperties( state );
        while ( properties.hasNext() )
        {
            DefinedProperty property = (DefinedProperty) properties.next();
            if ( property.propertyKeyId() == propertyKeyId )
            {
                return property.value();
            }
        }
        return null;
    }

    private Iterator<StorageProperty> graphGetAllProperties( KernelStatement state )
    {
        return state.readableTxState().augmentGraphProperties( storeLayer.graphGetAllProperties() );
    }

    @Override
    public long countsForNode( final KernelStatement statement, int labelId )
    {
        long count = countsForNodeWithoutTxState( statement, labelId );
        if ( statement.readableTxState().hasChanges() )
        {
            CountsRecordState counts = new CountsRecordState();
            try
            {
                statement.readableTxState().accept(
                        new TransactionCountingStateVisitor( EMPTY, storeLayer, statement.readableTxState(), counts ) );
                if ( counts.hasChanges() )
                {
                    count += counts.nodeCount( labelId, newDoubleLongRegister() ).readSecond();
                }
            }
            catch ( ConstraintValidationException | CreateConstraintFailureException e )
            {
                throw new IllegalArgumentException( "Unexpected error: " + e.getMessage() );
            }
        }
        return count;
    }

    @Override
    public long countsForNodeWithoutTxState( final KernelStatement statement, int labelId )
    {
        return storeLayer.countsForNode( labelId );
    }

    @Override
    public long countsForRelationship( KernelStatement statement, int startLabelId, int typeId, int endLabelId )
    {
        long count = countsForRelationshipWithoutTxState( statement, startLabelId, typeId, endLabelId );
        if ( statement.readableTxState().hasChanges() )
        {
            CountsRecordState counts = new CountsRecordState();
            try
            {
                statement.readableTxState().accept(
                        new TransactionCountingStateVisitor( EMPTY, storeLayer, statement.readableTxState(), counts ) );
                if ( counts.hasChanges() )
                {
                    count += counts.relationshipCount( startLabelId, typeId, endLabelId, newDoubleLongRegister() )
                            .readSecond();
                }
            }
            catch ( ConstraintValidationException | CreateConstraintFailureException e )
            {
                throw new IllegalArgumentException( "Unexpected error: " + e.getMessage() );
            }
        }
        return count;
    }

    @Override
    public long countsForRelationshipWithoutTxState( KernelStatement statement, int startLabelId, int typeId,
            int endLabelId )
    {
        return storeLayer.countsForRelationship( startLabelId, typeId, endLabelId );
    }

    @Override
    public long indexSize( KernelStatement statement, IndexDescriptor descriptor )
            throws IndexNotFoundKernelException
    {
        return storeLayer.indexSize( descriptor.schema() );
    }

    @Override
    public double indexUniqueValuesPercentage( KernelStatement statement, IndexDescriptor descriptor )
            throws IndexNotFoundKernelException
    {
        return storeLayer.indexUniqueValuesPercentage( descriptor.schema() );
    }

    @Override
    public DoubleLongRegister indexUpdatesAndSize( KernelStatement statement, IndexDescriptor index,
            DoubleLongRegister target ) throws IndexNotFoundKernelException
    {
        return storeLayer.indexUpdatesAndSize( index.schema(), target );
    }

    @Override
    public DoubleLongRegister indexSample( KernelStatement statement, IndexDescriptor index, DoubleLongRegister target )
            throws IndexNotFoundKernelException
    {
        return storeLayer.indexSample( index.schema(), target );
    }

    //
    // Methods that delegate directly to storage
    //

    @Override
    public Long indexGetOwningUniquenessConstraintId( KernelStatement state, IndexDescriptor index )
            throws SchemaRuleNotFoundException
    {
        return storeLayer.indexGetOwningUniquenessConstraintId( index );
    }

    @Override
    public long indexGetCommittedId( KernelStatement state, IndexDescriptor index )
            throws SchemaRuleNotFoundException
    {
        return storeLayer.indexGetCommittedId( index );
    }

    @Override
    public String indexGetFailure( Statement state, IndexDescriptor descriptor ) throws IndexNotFoundKernelException
    {
        return storeLayer.indexGetFailure( descriptor.schema() );
    }

    @Override
    public int labelGetForName( Statement state, String labelName )
    {
        return storeLayer.labelGetForName( labelName );
    }

    @Override
    public String labelGetName( Statement state, int labelId ) throws LabelNotFoundKernelException
    {
        return storeLayer.labelGetName( labelId );
    }

    @Override
    public int propertyKeyGetForName( Statement state, String propertyKeyName )
    {
        return storeLayer.propertyKeyGetForName( propertyKeyName );
    }

    @Override
    public String propertyKeyGetName( Statement state, int propertyKeyId ) throws PropertyKeyIdNotFoundKernelException
    {
        return storeLayer.propertyKeyGetName( propertyKeyId );
    }

    @Override
    public Iterator<Token> propertyKeyGetAllTokens( Statement state )
    {
        return storeLayer.propertyKeyGetAllTokens();
    }

    @Override
    public Iterator<Token> labelsGetAllTokens( Statement state )
    {
        return storeLayer.labelsGetAllTokens();
    }

    @Override
    public Iterator<Token> relationshipTypesGetAllTokens( Statement state )
    {
        return storeLayer.relationshipTypeGetAllTokens();
    }

    @Override
    public int relationshipTypeGetForName( Statement state, String relationshipTypeName )
    {
        return storeLayer.relationshipTypeGetForName( relationshipTypeName );
    }

    @Override
    public String relationshipTypeGetName( Statement state, int relationshipTypeId )
            throws RelationshipTypeIdNotFoundKernelException
    {
        return storeLayer.relationshipTypeGetName( relationshipTypeId );
    }

    @Override
    public int labelGetOrCreateForName( Statement state, String labelName )
            throws IllegalTokenNameException, TooManyLabelsException
    {
        return storeLayer.labelGetOrCreateForName( labelName );
    }

    @Override
    public int propertyKeyGetOrCreateForName( Statement state, String propertyKeyName ) throws IllegalTokenNameException
    {
        return storeLayer.propertyKeyGetOrCreateForName( propertyKeyName );
    }

    @Override
    public int relationshipTypeGetOrCreateForName( Statement state, String relationshipTypeName )
            throws IllegalTokenNameException
    {
        return storeLayer.relationshipTypeGetOrCreateForName( relationshipTypeName );
    }

    @Override
    public void labelCreateForName( KernelStatement state, String labelName, int id )
            throws IllegalTokenNameException, TooManyLabelsException
    {
        state.writableTxState().labelDoCreateForName( labelName, id );
    }

    @Override
    public void propertyKeyCreateForName( KernelStatement state, String propertyKeyName, int id )
            throws IllegalTokenNameException
    {
        state.writableTxState().propertyKeyDoCreateForName( propertyKeyName, id );

    }

    @Override
    public void relationshipTypeCreateForName( KernelStatement state, String relationshipTypeName, int id )
            throws IllegalTokenNameException
    {
        state.writableTxState().relationshipTypeDoCreateForName( relationshipTypeName, id );
    }

    @Override
    public int labelCount( KernelStatement statement )
    {
        return storeLayer.labelCount();
    }

    @Override
    public int propertyKeyCount( KernelStatement statement )
    {
        return storeLayer.propertyKeyCount();
    }

    @Override
    public int relationshipTypeCount( KernelStatement statement )
    {
        return storeLayer.relationshipTypeCount();
    }

    // <Legacy index>
    @Override
    public LegacyIndexHits nodeLegacyIndexGet( KernelStatement statement, String indexName, String key, Object value )
            throws LegacyIndexNotFoundKernelException
    {
        return statement.legacyIndexTxState().nodeChanges( indexName ).get( key, value );
    }

    @Override
    public LegacyIndexHits nodeLegacyIndexQuery( KernelStatement statement, String indexName, String key,
            Object queryOrQueryObject ) throws LegacyIndexNotFoundKernelException
    {
        return statement.legacyIndexTxState().nodeChanges( indexName ).query( key, queryOrQueryObject );
    }

    @Override
    public LegacyIndexHits nodeLegacyIndexQuery( KernelStatement statement, String indexName,
            Object queryOrQueryObject ) throws LegacyIndexNotFoundKernelException
    {
        return statement.legacyIndexTxState().nodeChanges( indexName ).query( queryOrQueryObject );
    }

    @Override
    public LegacyIndexHits relationshipLegacyIndexGet( KernelStatement statement, String indexName, String key,
            Object value, long startNode, long endNode ) throws LegacyIndexNotFoundKernelException
    {
        LegacyIndex index = statement.legacyIndexTxState().relationshipChanges( indexName );
        if ( startNode != -1 || endNode != -1 )
        {
            return index.get( key, value, startNode, endNode );
        }
        return index.get( key, value );
    }

    @Override
    public LegacyIndexHits relationshipLegacyIndexQuery( KernelStatement statement, String indexName, String key,
            Object queryOrQueryObject, long startNode, long endNode ) throws LegacyIndexNotFoundKernelException
    {
        LegacyIndex index = statement.legacyIndexTxState().relationshipChanges( indexName );
        if ( startNode != -1 || endNode != -1 )
        {
            return index.query( key, queryOrQueryObject, startNode, endNode );
        }
        return index.query( key, queryOrQueryObject );
    }

    @Override
    public LegacyIndexHits relationshipLegacyIndexQuery( KernelStatement statement, String indexName,
            Object queryOrQueryObject, long startNode, long endNode ) throws LegacyIndexNotFoundKernelException
    {
        LegacyIndex index = statement.legacyIndexTxState().relationshipChanges( indexName );
        if ( startNode != -1 || endNode != -1 )
        {
            return index.query( queryOrQueryObject, startNode, endNode );
        }
        return index.query( queryOrQueryObject );
    }

    @Override
    public void nodeLegacyIndexCreateLazily( KernelStatement statement, String indexName,
            Map<String,String> customConfig )
    {
        legacyIndexStore.getOrCreateNodeIndexConfig( indexName, customConfig );
    }

    @Override
    public void nodeLegacyIndexCreate( KernelStatement statement, String indexName, Map<String,String> customConfig )
    {
        statement.legacyIndexTxState().createIndex( IndexEntityType.Node, indexName, customConfig );
    }

    @Override
    public void relationshipLegacyIndexCreateLazily( KernelStatement statement, String indexName,
            Map<String,String> customConfig )
    {
        legacyIndexStore.getOrCreateRelationshipIndexConfig( indexName, customConfig );
    }

    @Override
    public void relationshipLegacyIndexCreate( KernelStatement statement, String indexName,
            Map<String,String> customConfig )
    {
        statement.legacyIndexTxState().createIndex( IndexEntityType.Relationship, indexName, customConfig );
    }

    @Override
    public void nodeAddToLegacyIndex( KernelStatement statement, String indexName, long node, String key, Object value )
            throws LegacyIndexNotFoundKernelException
    {
        statement.legacyIndexTxState().nodeChanges( indexName ).addNode( node, key, value );
    }

    @Override
    public void nodeRemoveFromLegacyIndex( KernelStatement statement, String indexName, long node, String key,
            Object value ) throws LegacyIndexNotFoundKernelException
    {
        statement.legacyIndexTxState().nodeChanges( indexName ).remove( node, key, value );
    }

    @Override
    public void nodeRemoveFromLegacyIndex( KernelStatement statement, String indexName, long node, String key )
            throws LegacyIndexNotFoundKernelException
    {
        statement.legacyIndexTxState().nodeChanges( indexName ).remove( node, key );
    }

    @Override
    public void nodeRemoveFromLegacyIndex( KernelStatement statement, String indexName, long node )
            throws LegacyIndexNotFoundKernelException
    {
        statement.legacyIndexTxState().nodeChanges( indexName ).remove( node );
    }

    @Override
    public void relationshipAddToLegacyIndex( final KernelStatement statement, final String indexName,
            final long relId, final String key, final Object value )
            throws EntityNotFoundException, LegacyIndexNotFoundKernelException
    {
        try ( Cursor<RelationshipItem> cursor = relationshipCursorById( statement, relId ) )
        {
            RelationshipItem rel = cursor.get();
            statement.legacyIndexTxState().relationshipChanges( indexName )
                    .addRelationship( relId, key, value, rel.startNode(), rel.endNode() );
        }
    }

    @Override
    public void relationshipRemoveFromLegacyIndex( final KernelStatement statement, final String indexName,
            long relId, final String key, final Object value )
            throws LegacyIndexNotFoundKernelException, EntityNotFoundException
    {
        try ( Cursor<RelationshipItem> cursor = storeLayer.relationshipGetSingleCursor( relId, statement.readableTxState() ) )
        {
            // Apparently it is OK if the relationship does not exist
            if ( cursor.next() )
            {
                RelationshipItem rel = cursor.get();
                statement.legacyIndexTxState().relationshipChanges( indexName )
                        .removeRelationship( relId, key, value, rel.startNode(), rel.endNode() );
            }
        }
    }

    @Override
    public void relationshipRemoveFromLegacyIndex( final KernelStatement statement, final String indexName,
            long relId, final String key ) throws EntityNotFoundException, LegacyIndexNotFoundKernelException
    {

        try ( Cursor<RelationshipItem> cursor = storeLayer.relationshipGetSingleCursor( relId, statement.readableTxState() ) )
        {
            // Apparently it is OK if the relationship does not exist
            if ( cursor.next() )
            {
                RelationshipItem rel = cursor.get();
                statement.legacyIndexTxState().relationshipChanges( indexName )
                        .removeRelationship( relId, key, rel.startNode(), rel.endNode() );
            }
        }
    }

    @Override
    public void relationshipRemoveFromLegacyIndex( final KernelStatement statement, final String indexName,
            long relId ) throws LegacyIndexNotFoundKernelException, EntityNotFoundException
    {
        try ( Cursor<RelationshipItem> cursor = storeLayer.relationshipGetSingleCursor( relId, statement.readableTxState() ) )
        {
            if ( cursor.next() )
            {
                RelationshipItem rel = cursor.get();
                statement.legacyIndexTxState().relationshipChanges( indexName )
                        .removeRelationship( relId, rel.startNode(), rel.endNode() );
            }
            else
            {
                // This is a special case which is still OK. This method is called lazily where deleted relationships
                // that still are referenced by a legacy index will be added for removal in this transaction.
                // Ideally we'd want to include start/end node too, but we can't since the relationship doesn't exist.
                // So we do the "normal" remove call on the legacy index transaction changes. The downside is that
                // Some queries on this transaction state that include start/end nodes might produce invalid results.
                statement.legacyIndexTxState().relationshipChanges( indexName ).remove( relId );
            }
        }
    }

    @Override
    public void nodeLegacyIndexDrop( KernelStatement statement, String indexName )
            throws LegacyIndexNotFoundKernelException
    {
        statement.legacyIndexTxState().nodeChanges( indexName ).drop();
        statement.legacyIndexTxState().deleteIndex( IndexEntityType.Node, indexName );
    }

    @Override
    public void relationshipLegacyIndexDrop( KernelStatement statement, String indexName )
            throws LegacyIndexNotFoundKernelException
    {
        statement.legacyIndexTxState().relationshipChanges( indexName ).drop();
        statement.legacyIndexTxState().deleteIndex( IndexEntityType.Relationship, indexName );
    }

    @Override
    public String nodeLegacyIndexSetConfiguration( KernelStatement statement, String indexName, String key,
            String value ) throws LegacyIndexNotFoundKernelException
    {
        return legacyIndexStore.setNodeIndexConfiguration( indexName, key, value );
    }

    @Override
    public String relationshipLegacyIndexSetConfiguration( KernelStatement statement, String indexName, String key,
            String value ) throws LegacyIndexNotFoundKernelException
    {
        return legacyIndexStore.setRelationshipIndexConfiguration( indexName, key, value );
    }

    @Override
    public String nodeLegacyIndexRemoveConfiguration( KernelStatement statement, String indexName, String key )
            throws LegacyIndexNotFoundKernelException
    {
        return legacyIndexStore.removeNodeIndexConfiguration( indexName, key );
    }

    @Override
    public String relationshipLegacyIndexRemoveConfiguration( KernelStatement statement, String indexName, String key )
            throws LegacyIndexNotFoundKernelException
    {
        return legacyIndexStore.removeRelationshipIndexConfiguration( indexName, key );
    }

    @Override
    public Map<String,String> nodeLegacyIndexGetConfiguration( KernelStatement statement, String indexName )
            throws LegacyIndexNotFoundKernelException
    {
        return legacyIndexStore.getNodeIndexConfiguration( indexName );
    }

    @Override
    public Map<String,String> relationshipLegacyIndexGetConfiguration( KernelStatement statement, String indexName )
            throws LegacyIndexNotFoundKernelException
    {
        return legacyIndexStore.getRelationshipIndexConfiguration( indexName );
    }

    @Override
    public String[] nodeLegacyIndexesGetAll( KernelStatement statement )
    {
        return legacyIndexStore.getAllNodeIndexNames();
    }

    @Override
    public String[] relationshipLegacyIndexesGetAll( KernelStatement statement )
    {
        return legacyIndexStore.getAllRelationshipIndexNames();
    }
    // </Legacy index>

    @Override
    public boolean nodeExists( KernelStatement statement, long id )
    {
        ReadableTransactionState txState = statement.readableTxState();
        if ( txState.nodeIsDeletedInThisTx( id ) )
        {
            return false;
        }
        else if ( txState.nodeIsAddedInThisTx( id ) )
        {
            return true;
        }
        return storeLayer.nodeExists( id );
    }

    @Override
    public PrimitiveIntSet relationshipTypes( KernelStatement statement, NodeItem node )
    {
        if ( statement.readableTxState().nodeIsAddedInThisTx( node.id() ) )
        {
            return statement.readableTxState().getNodeState( node.id() ).relationshipTypes();
        }

        // Read types in the current transaction
        PrimitiveIntSet types = statement.readableTxState().getNodeState( node.id() ).relationshipTypes();

        // Augment with types stored on disk, minus any types where all rels of that type are deleted
        // in current tx.
        types.addAll( filter( storeLayer.relationshipTypes( node ).iterator(),
                ( current ) -> !types.contains( current ) && degree( statement, node, Direction.BOTH, current ) > 0 ) );

        return types;
    }

    @Override
    public int degree( KernelStatement statement, NodeItem node, Direction direction )
    {
        ReadableTransactionState state = statement.readableTxState();
        return storeLayer.countDegrees( node, direction, state );
    }

    @Override
    public int degree( KernelStatement statement, NodeItem node, Direction direction, int relType )
    {
        ReadableTransactionState state = statement.readableTxState();
        return storeLayer.countDegrees( node, direction, relType, state );
    }
}
