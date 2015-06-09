/*
 * Licensed to CRATE Technology GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
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

package io.crate.planner.consumer;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import io.crate.Constants;
import io.crate.analyze.*;
import io.crate.analyze.relations.AnalyzedRelation;
import io.crate.analyze.relations.AnalyzedRelationVisitor;
import io.crate.analyze.relations.PlannedAnalyzedRelation;
import io.crate.analyze.relations.TableRelation;
import io.crate.exceptions.ValidationException;
import io.crate.metadata.ReferenceInfo;
import io.crate.operation.projectors.FetchProjector;
import io.crate.operation.projectors.TopN;
import io.crate.planner.Plan;
import io.crate.planner.PlanAndPlannedAnalyzedRelation;
import io.crate.planner.PlanNodeBuilder;
import io.crate.planner.Planner;
import io.crate.planner.node.NoopPlannedAnalyzedRelation;
import io.crate.planner.node.dql.MergeNode;
import io.crate.planner.node.dql.QueryThenFetch;
import io.crate.planner.node.dql.join.NestedLoop;
import io.crate.planner.projection.*;
import io.crate.planner.projection.builder.ProjectionBuilder;
import io.crate.planner.symbol.*;
import io.crate.sql.tree.QualifiedName;
import io.crate.types.DataTypes;
import org.elasticsearch.common.Nullable;

import java.util.*;


public class CrossJoinConsumer implements Consumer {

    private final CrossJoinVisitor visitor;
    private final static InputColumnProducer INPUT_COLUMN_PRODUCER = new InputColumnProducer();
    private static final InputColumn DEFAULT_DOC_ID_INPUT_COLUMN = new InputColumn(0, DataTypes.STRING);


    public CrossJoinConsumer(AnalysisMetaData analysisMetaData) {
        visitor = new CrossJoinVisitor(analysisMetaData);
    }

    @Override
    public boolean consume(AnalyzedRelation rootRelation, ConsumerContext context) {
        AnalyzedRelation analyzedRelation = visitor.process(rootRelation, context);
        if (analyzedRelation != null) {
            context.rootRelation(analyzedRelation);
            return true;
        }
        return false;
    }

    private static class CrossJoinVisitor extends AnalyzedRelationVisitor<ConsumerContext, PlannedAnalyzedRelation> {

        private final AnalysisMetaData analysisMetaData;

        public CrossJoinVisitor(AnalysisMetaData analysisMetaData) {
            this.analysisMetaData = analysisMetaData;
        }

        @Override
        protected PlannedAnalyzedRelation visitAnalyzedRelation(AnalyzedRelation relation, ConsumerContext context) {
            return null;
        }

        @SuppressWarnings("ConstantConditions")
        //@Override
        public PlannedAnalyzedRelation visitMultiSourceSelectOld(MultiSourceSelect statement, ConsumerContext context) {
            // TODO: erase limit and offset from subreleation if there is a remaining query on the statement
            // see: https://github.com/crate/crate/compare/cross_joins#diff-c724bfe9e65eb0f7914a5bcc44976d89R171

            if (statement.sources().size() < 2) {
                return null;
            }

            List<Symbol> groupBy = statement.querySpec().groupBy();
            if (groupBy != null && !groupBy.isEmpty()) {
                context.validationException(new ValidationException("GROUP BY on CROSS JOIN is not supported"));
                return null;
            }
            if (statement.querySpec().hasAggregates()) {
                context.validationException(new ValidationException("AGGREGATIONS on CROSS JOIN is not supported"));
                return null;
            }

            // check that every inner relation is planned
            for (AnalyzedRelation relation : statement.sources().values()) {
                if (relation instanceof PlannedAnalyzedRelation) {
                    return null;
                    // TODO: check whereClause No match for each relation
                }
            }

            /**
             * Example statement:
             *
             *      select
             *          t1.name,
             *          t2.x + cast(substr(t3.foo, 1, 1) as integer) - t1.y
             *      from t1, t2, t3
             *      order by
             *          t3.x,
             *          t1.x + t2.x
             *
             * Generate map with outputs per relation:
             *
             *      qtf t1:
             *          outputs: [name, y, x]   // x is included because of order by t1.x + t2.x
             *                                  // need to execute order by in topN projection
             *
             *      qtf t2:
             *          outputs: [x]
             *
             *      qtf t3:
             *          outputs: [foo]          // order by t3.x not included in output, QTF result will be pre-sorted
             *
             * root-nestedLoop:
             *  outputs: [t1.name, t1.y, t2.x, cast(substr(3.foo, 1, 1) as integer)]
             *           [  0,      1,    2,      3                                ]
             *
             * postOutputs: [in(0), subtract( add( in(1), in(3)), in(1) ]
             */

            WhereClause where = MoreObjects.firstNonNull(statement.querySpec().where(), WhereClause.MATCH_ALL);
            if (where.noMatch()) {
                return new NoopPlannedAnalyzedRelation(statement);
            }
            List<QueriedTable> queriedTables = new ArrayList<>();

            /**
             * create a map to track which relation to order in the nestedLoopNode:
             *
             * e.g. select * from t1, t2, t3 order by t2.x, t3.y
             *
             * orderByOrder: {
             *     t2: 0        (first)
             *     t3: 1        (second)
             * }
             */
            final Map<Object, Integer> orderByOrder = new IdentityHashMap<>();
            OrderBy orderBy = statement.querySpec().orderBy();
            if (orderBy != null && orderBy.isSorted()) {
                int idx = 0;
                for (Symbol orderBySymbol : orderBy.orderBySymbols()) {
                    for (AnalyzedRelation analyzedRelation : statement.sources().values()) {
                        QuerySplitter.RelationCount relationCount = QuerySplitter.getRelationCount(analyzedRelation, orderBySymbol);
                        if (relationCount != null && relationCount.numOther == 0 && relationCount.numThis > 0) {
                            orderByOrder.put(analyzedRelation, idx);
                        }
                    }
                    idx++;
                }
            }

            for (Map.Entry<QualifiedName, AnalyzedRelation> entry : statement.sources().entrySet()) {
                AnalyzedRelation analyzedRelation = entry.getValue();
                if (!(analyzedRelation instanceof TableRelation)) {
                    context.validationException(new ValidationException("CROSS JOIN with sub queries is not supported"));
                    return null;
                }
                TableRelation tableRelation = (TableRelation) analyzedRelation;
                final QueriedTable queriedTable = QueriedTable.newSubRelation(entry.getKey(), tableRelation, statement.querySpec());
                queriedTable.normalize(analysisMetaData);
                where = statement.querySpec().where();
                orderBy = statement.querySpec().orderBy();
                // erase limit and offset if this relation is part of remaining query or of remaining order by
                queriedTables.add(queriedTable);

            }
            boolean hasRemainingQuery = where.hasQuery() && !(where.query() instanceof Literal);
            boolean hasRemainingOrderBy = orderBy != null && orderBy.isSorted();
            // erase
            if (hasRemainingQuery || hasRemainingOrderBy) {
                for (QueriedTable queriedTable : queriedTables) {
                    queriedTable.querySpec().limit(null);
                    queriedTable.querySpec().offset(TopN.NO_OFFSET);
                }
            }

            int rootLimit = MoreObjects.firstNonNull(statement.querySpec().limit(), Constants.DEFAULT_SELECT_LIMIT);
            Collections.sort(queriedTables, new Comparator<QueriedTable>() {
                @Override
                public int compare(QueriedTable o1, QueriedTable o2) {
                    return Integer.compare(
                            MoreObjects.firstNonNull(orderByOrder.get(o1.tableRelation()), Integer.MAX_VALUE),
                            MoreObjects.firstNonNull(orderByOrder.get(o2.tableRelation()), Integer.MAX_VALUE));
                }
            });

            // get new remaining order by

            boolean pushDownLimit = !hasRemainingOrderBy && !hasRemainingQuery;
            NestedLoop nestedLoop = toNestedLoop(queriedTables, rootLimit, statement.querySpec().offset(), pushDownLimit, context);
            List<Symbol> queriedTablesOutputs = getAllOutputs(queriedTables);

            ImmutableList.Builder<Projection> projectionBuilder = ImmutableList.builder();

            if (hasRemainingQuery) { // TODO: equi join ^^
                Symbol filter = replaceFieldsWithInputColumns(where.query(), queriedTablesOutputs);
                projectionBuilder.add(new FilterProjection(filter));
            }

            /**
             * TopN for:
             *
             * #1 Reorder
             *      need to always use topN to re-order outputs,
             *
             *      e.g. select t1.name, t2.name, t1.id
             *
             *      left outputs:
             *          [ t1.name, t1.id ]
             *
             *      right outputs:
             *          [ t2.name ]
             *
             *      left + right outputs:
             *          [ t1.name, t1.id, t2.name]
             *
             *      final outputs (topN):
             *          [ in(0), in(2), in(1)]
             *
             * #2 Execute functions that reference more than 1 relations
             *
             *      select t1.x + t2.x
             *
             *      left: x
             *      right: x
             *
             *      topN:  add(in(0), in(1))
             *
             * #3 Apply Limit (and Order by once supported..)
             */
            List<Symbol> postOutputs = replaceFieldsWithInputColumns(statement.querySpec().outputs(), queriedTablesOutputs);
            TopNProjection topNProjection;

            if (orderBy != null && orderBy.isSorted()) {
                 topNProjection = new TopNProjection(
                         rootLimit,
                         statement.querySpec().offset(),
                         replaceFieldsWithInputColumns(orderBy.orderBySymbols(), queriedTablesOutputs),
                         orderBy.reverseFlags(),
                         orderBy.nullsFirst()
                 );
            } else {
                topNProjection = new TopNProjection(rootLimit, statement.querySpec().offset());
            }
            topNProjection.outputs(postOutputs);
            projectionBuilder.add(topNProjection);

           // nestedLoop.projections(projectionBuilder.build());
           // nestedLoop.outputTypes(Symbols.extractTypes(postOutputs));
            return nestedLoop;
        }

        @Override
        public PlannedAnalyzedRelation visitMultiSourceSelect(MultiSourceSelect statement, ConsumerContext context) {
            // TODO: erase limit and offset from subrelation if there is a remaining query on the statement

            if (statement.sources().size() < 2) {
                return null;
            }

            List<Symbol> groupBy = statement.querySpec().groupBy();
            if (groupBy != null && !groupBy.isEmpty()) {
                context.validationException(new ValidationException("GROUP BY on CROSS JOIN is not supported"));
                return null;
            }
            if (statement.querySpec().hasAggregates()) {
                context.validationException(new ValidationException("AGGREGATIONS on CROSS JOIN is not supported"));
                return null;
            }

            // check that every inner relation is planned
            List<Symbol> allCollectorOutputs = new ArrayList<>();
            for (AnalyzedRelation relation : statement.sources().values()) {
                if (relation instanceof PlannedAnalyzedRelation) {
                    if (relation instanceof QueryThenFetch) {
                        allCollectorOutputs.addAll(((QueryThenFetch) relation).collectNode().toCollect());
                    } else if (relation instanceof NoopPlannedAnalyzedRelation) {
                        return (NoopPlannedAnalyzedRelation)relation;
                    }
                } else {
                    return null;
                }
            }

            WhereClause where = MoreObjects.firstNonNull(statement.querySpec().where(), WhereClause.MATCH_ALL);
            if (where.noMatch()) {
                return new NoopPlannedAnalyzedRelation(statement);
            }

            // TODO: calculate rootLimit
            // TODO: check remaining query
            // TODO: consider orderByOrder
            ProjectionBuilder projectionBuilder = new ProjectionBuilder(statement.querySpec());


            NestedLoop nl = null;
            Iterator<AnalyzedRelation> iterator = statement.sources().values().iterator();
            while (iterator.hasNext()) {
                PlanAndPlannedAnalyzedRelation plannedAnalyzedRelation = (PlanAndPlannedAnalyzedRelation)iterator.next();
                if (nl == null) {
                    assert iterator.hasNext();
                    PlanAndPlannedAnalyzedRelation secondAnalyzedRelation = (PlanAndPlannedAnalyzedRelation)iterator.next();
                    final NestedLoopProjection proj = createNestedLoopProjection(plannedAnalyzedRelation, secondAnalyzedRelation);
                    List<Projection> projections = new ArrayList(){{add(proj);}};

                    QueryThenFetch right = (QueryThenFetch)secondAnalyzedRelation;
                    QueryThenFetch left = (QueryThenFetch)plannedAnalyzedRelation;
                    finalizeInnerPlan(left, projections, context.plannerContext());

                    final TopNProjection simpleTopNProjection = projectionBuilder.topNProjection(right.collectNode().toCollect(), null, 0, null, right.collectNode().toCollect());
                    finalizeInnerPlan(secondAnalyzedRelation, new ArrayList<Projection>(){{add(simpleTopNProjection);}}, context.plannerContext());

                    right.mergeNode().downstreamNodes(left.mergeNode().executionNodes());
                    right.mergeNode().downstreamExecutionNodeId(left.mergeNode().executionNodeId());

                    left.mergeNode().downstreamNodes(Sets.newHashSet(context.plannerContext().clusterService().state().nodes().localNodeId()));
                    left.mergeNode().downstreamExecutionNodeId(right.mergeNode().executionNodeId() + 1);

                    nl = new NestedLoop(plannedAnalyzedRelation, secondAnalyzedRelation, null, true);
                } else {
                    // use the already created nestedLoop as left innerPlan
                    NestedLoopProjection proj = createNestedLoopProjection(nl, plannedAnalyzedRelation);
                    nl = new NestedLoop(nl, plannedAnalyzedRelation, null, true);
                }
            }

            List<Projection> mergeProjections = new ArrayList<>();

            int rootLimit = MoreObjects.firstNonNull(statement.querySpec().limit(), Constants.DEFAULT_SELECT_LIMIT);

            //TopNProjection topNProjection = new TopNProjection(rootLimit, statement.querySpec().offset());

            // TODO: List<Symbol> postOutputs = replaceFieldsWithInputColumns(statement.querySpec().outputs(), allCollectorOutputs);
            // TODO: add postOutputs to topNProjection
            //topNProjection.outputs(allCollectorOutputs);

            TopNProjection topNProjection = projectionBuilder.topNProjection(
                    allCollectorOutputs,
                    null,
                    statement.querySpec().offset(),
                    rootLimit,
                    null);

            mergeProjections.add(topNProjection);

            int bulkSize = FetchProjector.NO_BULK_REQUESTS;
            if (topNProjection.limit() > Constants.DEFAULT_SELECT_LIMIT) {
                bulkSize = Constants.DEFAULT_SELECT_LIMIT;
            }

            FetchProjection fetchProjection = buildFetchProjection(statement.sources().values(), bulkSize, context.plannerContext());
            if(fetchProjection != null) {
                mergeProjections.add(fetchProjection);
            }
            MergeNode localMergeNode;
            OrderBy orderBy = statement.querySpec().orderBy();
            if (orderBy != null && orderBy.isSorted()) {
                localMergeNode = PlanNodeBuilder.sortedLocalMerge(
                        mergeProjections,
                        orderBy,
                        replaceFieldsWithInputColumns(orderBy.orderBySymbols(), allCollectorOutputs),
                        null,
                        nl.resultNode(),
                        context.plannerContext());
            } else {
                localMergeNode = PlanNodeBuilder.localMerge(
                        mergeProjections,
                        nl.resultNode(),
                        context.plannerContext());
            }
            nl.mergeNode(localMergeNode);
            return nl;
        }

        @Override
        public PlannedAnalyzedRelation visitInsertFromQuery(InsertFromSubQueryAnalyzedStatement insertFromSubQueryAnalyzedStatement, ConsumerContext context) {
            InsertFromSubQueryConsumer.planInnerRelation(insertFromSubQueryAnalyzedStatement, context, this);
            return null;
        }

        private void finalizeInnerPlan(PlannedAnalyzedRelation plan, List<Projection> projections, Planner.Context context) {
            // TODO: create visitor instead of checking instanceof
            if(plan instanceof QueryThenFetch) {
                QueryThenFetch qtf = (QueryThenFetch)plan;

                qtf.collectNode().downstreamNodes(Lists.newArrayList(qtf.collectNode().routing().nodes()));

                MergeNode mergeNode = PlanNodeBuilder.distributedMerge(
                        qtf.collectNode(),
                        context,
                        projections
                );

                //mergeNode.downstreamNodes(Sets.newHashSet(context.clusterService().state().nodes().localNodeId()));
                //mergeNode.downstreamExecutionNodeId(mergeNode.downstreamExecutionNodeId() + 1);
                qtf.collectNode().downstreamExecutionNodeId(mergeNode.executionNodeId());
                qtf.mergeNode(mergeNode);
            }
        }

        private NestedLoopProjection createNestedLoopProjection(PlannedAnalyzedRelation left, PlannedAnalyzedRelation right) {
            NestedLoopProjection projection = new NestedLoopProjection();
            projection.outputs(getAllOutputs(left, right));
            return projection;
        }

        private List<Symbol> getAllOutputs(PlannedAnalyzedRelation...relations) {
            ImmutableList.Builder<Symbol> builder = ImmutableList.builder();
            for (PlannedAnalyzedRelation relation : relations) {
                if (relation instanceof QueryThenFetch) {
                    builder.addAll(((QueryThenFetch) relation).collectNode().toCollect());
                }
            }
            return builder.build();
        }


        private List<Symbol> getAllOutputs(Collection<QueriedTable> queriedTables) {
            ImmutableList.Builder<Symbol> builder = ImmutableList.builder();
            for (QueriedTable table : queriedTables) {
                builder.addAll(table.fields());
            }
            return builder.build();
        }

        @Nullable
        private FetchProjection buildFetchProjection(Collection<AnalyzedRelation> planNodes, int bulkSize, Planner.Context context) {
            List<Symbol> collectSymbols = new ArrayList<>();
            List<Symbol> outputSymbols = new ArrayList<>();
            List<ReferenceInfo> partitionedByColumns = new ArrayList<>(); // TODO: check that's correct
            Map<Integer, List<String>> executionNodes = new HashMap<>();
            for (AnalyzedRelation planNode : planNodes) {
                if (planNode instanceof QueryThenFetch) {
                    QueryThenFetch qtf = (QueryThenFetch)planNode;
                    collectSymbols.addAll(qtf.collectNode().toCollect());
                    outputSymbols.addAll(qtf.context().outputs());
                    executionNodes.put(qtf.collectNode().executionNodeId(), new ArrayList<>(qtf.collectNode().executionNodes()));
                    partitionedByColumns.addAll(qtf.context().partitionedByColumns());
                }
            }

            FetchProjection fetchProjection = new FetchProjection(
                    context.jobSearchContextIdToExecutionNodeId(),
                    DEFAULT_DOC_ID_INPUT_COLUMN, collectSymbols, outputSymbols,
                    partitionedByColumns,
                    executionNodes,
                    bulkSize,
                    true,
                    context.jobSearchContextIdToNode(),
                    context.jobSearchContextIdToShard()
            );
            return fetchProjection;
        }

        /**
         * generates new symbols that will use InputColumn symbols to point to the output of the given relations
         *
         * @param statementOutputs: [ u1.id,  add(u1.id, u2.id) ]
         * @param inputSymbols:
         * {
         *     [ u1.id, u2.id ],
         * }
         *
         * @return [ in(0), add( in(0), in(1) ) ]
         */
        private List<Symbol> replaceFieldsWithInputColumns(Collection<? extends Symbol> statementOutputs,
                                                           List<Symbol> inputSymbols) {
            List<Symbol> result = new ArrayList<>();
            for (Symbol statementOutput : statementOutputs) {
                result.add(replaceFieldsWithInputColumns(statementOutput, inputSymbols));
            }
            return result;
        }

        private Symbol replaceFieldsWithInputColumns(Symbol symbol, List<Symbol> inputSymbols) {
            return INPUT_COLUMN_PRODUCER.process(symbol, new InputColumnProducerContext(inputSymbols));
        }

        /**
         * creates the nestedLoop.
         *
         * The queriedTables must be ordered in such a way that the left node of the NL will always be the one to order by ("leftOuterLoop = true")
         */
        private NestedLoop toNestedLoop(List<QueriedTable> queriedTables, int limit, int offset, boolean pushDownLimit,
                                            ConsumerContext context) {
            Iterator<QueriedTable> iterator = queriedTables.iterator();
            NestedLoop nl = null;
           /* Plan left;
            Plan right;

            int nestedLimit = pushDownLimit ? limit + offset : TopN.NO_LIMIT;
            int nestedOffset = 0;

            while (iterator.hasNext()) {
                QueriedTable next = iterator.next();
                int currentLimit;
                int currentOffset;
                if (nl == null) {
                    assert iterator.hasNext();
                    QueriedTable second = iterator.next();
                    currentLimit = iterator.hasNext() ? nestedLimit : limit;
                    currentOffset = iterator.hasNext() ? nestedOffset : offset;

                    left = planSubRelation(next);
                    right = planSubRelation(second);
                    assert left != null && right != null;

                    nl = new NestedLoopNode(left, right, true, currentLimit, currentOffset);
                    nl.outputTypes(ImmutableList.<DataType>builder()
                            .addAll(left.outputTypes())
                            .addAll(right.outputTypes()).build());
                } else {
                    currentLimit = iterator.hasNext() ? nestedLimit : limit;
                    currentOffset = iterator.hasNext() ? nestedOffset : offset;
                    NestedLoopNode lastNL = nl;
                    right = consumingPlanner.plan(next);
                    assert right != null;
                    nl = new NestedLoopNode(new IterablePlan(lastNL), right, true, currentLimit, currentOffset);
                    nl.outputTypes(ImmutableList.<DataType>builder()
                            .addAll(lastNL.outputTypes())
                            .addAll(right.outputTypes()).build());
                }
            }*/
            return nl;
        }

        /**
         * uses the consumingPlanner to planSubRelation a subrelation.
         *
         * Therefore a new ConsumerContext is used with a null rootRelation,
         * because the rootRelation is just used to write the result of the
         * consumer and to check if the subRelation
         * is the rootNode of the Plan (by comparing relation with rootRelation),
         * but in this case it's always a childNode and we don't want that
         * the real rootNode is overwritten.
         *
         */
        private Plan planSubRelation(AnalyzedRelation relation) {
           // Plan plan = consumingPlanner.plan(relation, new ConsumerContext(null)); // TODO: check insertFromSubQuery
           // return plan;
            return null;
        }
    }

    private static class InputColumnProducerContext {

        private List<Symbol> inputs;

        public InputColumnProducerContext(List<Symbol> inputs) {
            this.inputs = inputs;
        }
    }

    private static class InputColumnProducer extends SymbolVisitor<InputColumnProducerContext, Symbol> {

        @Override
        public Symbol visitFunction(Function function, InputColumnProducerContext context) {
            int idx = 0;
            for (Symbol input : context.inputs) {
                if (input.equals(function)) {
                    return new InputColumn(idx, input.valueType());
                }
                idx++;
            }
            List<Symbol> newArgs = new ArrayList<>(function.arguments().size());
            for (Symbol argument : function.arguments()) {
                newArgs.add(process(argument, context));
            }
            return new Function(function.info(), newArgs);
        }

        @Override
        public Symbol visitField(Field field, InputColumnProducerContext context) {
            int idx = 0;
            for (Symbol input : context.inputs) {
                if (input.equals(field)) {
                    return new InputColumn(idx, input.valueType());
                }
                idx++;
            }
            return field;
        }

        @Override
        public Symbol visitLiteral(Literal literal, InputColumnProducerContext context) {
            return literal;
        }
    }


    public static <R, C> void planInnerRelations(MultiSourceSelect statement, C context, AnalyzedRelationVisitor<C, R> visitor, AnalysisMetaData analysisMetaData) throws ValidationException{
        if (statement.sources().size() < 2) {
            return;
        }

        List<Symbol> groupBy = statement.querySpec().groupBy();
        if (groupBy != null && !groupBy.isEmpty()) {
            throw new ValidationException("GROUP BY on CROSS JOIN is not supported");
        }
        if (statement.querySpec().hasAggregates()) {
            throw new ValidationException("AGGREGATIONS on CROSS JOIN is not supported");
        }

        WhereClause where = MoreObjects.firstNonNull(statement.querySpec().where(), WhereClause.MATCH_ALL);
        if (where.noMatch()) {
            // TODO: replace rootRelation with NoopPlannedAnalyzedRelation
            return;
        }

        for (Map.Entry<QualifiedName, AnalyzedRelation> entry : statement.sources().entrySet()) {
            AnalyzedRelation analyzedRelation = entry.getValue();
            // relation is already planned
            if (analyzedRelation instanceof PlannedAnalyzedRelation) {
                continue;
            }
            if (!(analyzedRelation instanceof TableRelation)) {
                throw new ValidationException("CROSS JOIN with sub queries is not supported");
            }
            TableRelation tableRelation = (TableRelation) analyzedRelation;
            final QueriedTable queriedTable = QueriedTable.newSubRelation(entry.getKey(), tableRelation, statement.querySpec());
            queriedTable.normalize(analysisMetaData);

            R innerRelation = visitor.process(queriedTable, context);
            if (innerRelation != null && innerRelation instanceof PlannedAnalyzedRelation) {
                statement.sources().put(entry.getKey(), (PlannedAnalyzedRelation)innerRelation);
            } else {
                // TODO: write a test for that
                statement.sources().put(entry.getKey(), queriedTable);
            }

        }
    }

}
