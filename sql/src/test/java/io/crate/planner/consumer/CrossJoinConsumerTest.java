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

import com.google.common.collect.FluentIterable;
import com.google.common.collect.Lists;
import io.crate.Constants;
import io.crate.analyze.Analyzer;
import io.crate.analyze.ParameterContext;
import io.crate.analyze.WhereClause;
import io.crate.metadata.ReferenceInfos;
import io.crate.operation.aggregation.impl.AggregationImplModule;
import io.crate.operation.operator.OperatorModule;
import io.crate.operation.operator.OrOperator;
import io.crate.operation.predicate.PredicateModule;
import io.crate.operation.scalar.ScalarFunctionModule;
import io.crate.planner.*;
import io.crate.planner.node.PlanNode;
import io.crate.planner.node.dql.AbstractDQLPlanNode;
import io.crate.planner.node.dql.QueryThenFetch;
import io.crate.planner.node.dql.join.NestedLoop;
import io.crate.planner.projection.FetchProjection;
import io.crate.planner.projection.FilterProjection;
import io.crate.planner.projection.Projection;
import io.crate.planner.projection.TopNProjection;
import io.crate.planner.symbol.Function;
import io.crate.planner.symbol.InputColumn;
import io.crate.planner.symbol.Reference;
import io.crate.planner.symbol.Symbol;
import io.crate.sql.parser.SqlParser;
import io.crate.testing.TestingHelpers;
import io.crate.types.DataType;
import io.crate.types.DataTypes;
import org.elasticsearch.common.inject.Injector;
import org.elasticsearch.common.inject.ModulesBuilder;
import org.elasticsearch.threadpool.ThreadPool;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static io.crate.testing.TestingHelpers.isFunction;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

public class CrossJoinConsumerTest {

    private Analyzer analyzer;
    private Planner planner;

    private ThreadPool threadPool;
    private PlannerTest.TestModule testModule;

    @Rule
    public ExpectedException expectedException = ExpectedException.none();

    @Before
    public void setUp() throws Exception {
        threadPool = TestingHelpers.newMockedThreadPool();
        testModule = PlannerTest.plannerTestModule(threadPool);
        Injector injector = new ModulesBuilder()
                .add(testModule)
                .add(new AggregationImplModule())
                .add(new ScalarFunctionModule())
                .add(new PredicateModule())
                .add(new OperatorModule())
                .createInjector();
        analyzer = injector.getInstance(Analyzer.class);
        planner = injector.getInstance(Planner.class);
    }

    private Plan plan(String statement) {
        return planner.plan(analyzer.analyze(SqlParser.createStatement(statement),
                new ParameterContext(new Object[0], new Object[0][], ReferenceInfos.DEFAULT_SCHEMA_NAME)));
    }

    @Test
    public void testWhereWithNoMatchShouldReturnNoopPlan() throws Exception {


        Plan plan = plan("select * from users u1, users u2 where 1 = 2");
        assertThat(plan, instanceOf(NoopPlan.class));
    }

    @Test
    public void testExplicitCrossJoinWithoutLimitOrOrderBy() throws Exception {

        NestedLoop nestedLoop = (NestedLoop)plan("select * from users cross join parted");

        TopNProjection topNProjection = (TopNProjection)nestedLoop.mergeNode().projections().get(0);
        assertThat(topNProjection.limit(), is(Constants.DEFAULT_SELECT_LIMIT));
        assertThat(topNProjection.offset(), is(0));

        assertThat(nestedLoop.mergeNode().outputTypes().size(), is(8));

        QueryThenFetch left = (QueryThenFetch)nestedLoop.left();
        QueryThenFetch right = (QueryThenFetch)nestedLoop.right();

        assertThat(left.collectNode().limit(), is(Constants.DEFAULT_SELECT_LIMIT)); // TODO: Default Limit should be added by CrossJoinConsumer
        assertThat(right.collectNode().limit(), is(Constants.DEFAULT_SELECT_LIMIT));

        // The same NestedLoopProjection is used for left and right
        assertEquals(left.mergeNode().projections().get(0), right.mergeNode().projections().get(0));
    }

    @Test
    public void testCrossJoinWithJoinCriteriaInOrderBy() throws Exception {

        QueryThenFetch qtf = (QueryThenFetch)plan("select id + 5 from users order by 1");

        NestedLoop nl = (NestedLoop)plan("select u1.id + u2.id, u1.name from users u1, users u2 order by 1");

        TopNProjection topN = (TopNProjection)nl.mergeNode().projections().get(0);
        assertThat(topN.limit(), is(Constants.DEFAULT_SELECT_LIMIT));
        assertThat(topN.offset(), is(0));

        QueryThenFetch leftQTF = (QueryThenFetch)(nl.left());
        assertThat(leftQTF.collectNode().limit(), is(Constants.DEFAULT_SELECT_LIMIT));

        QueryThenFetch rightQTF = (QueryThenFetch)(nl.right());
        assertThat(rightQTF.collectNode().limit(), is(Constants.DEFAULT_SELECT_LIMIT));

        TopNProjection topNProjection = (TopNProjection) nl.resultNode().projections().get(0);

        // sorting is done by the mergeProjections
        // TODO: verify correct ordering
        // TODO: verify correct outputs
        /*assertThat(topNProjection.isOrdered(), is(false));

        MergeNode mergeProjection = nl.mergeNode();

        Symbol orderBy = topNProjection.orderBy().get(0);
        assertThat(orderBy, isFunction(AddFunction.NAME));
        Function function = (Function) orderBy;

        for (Symbol arg : function.arguments()) {
            assertThat(arg, instanceOf(InputColumn.class));
        }*/
    }

    @Test
    public void testOrderOfColumnsInOutputIsCorrect() throws Exception {
        IterablePlan plan = (IterablePlan)plan("select t1.name, t2.name, t1.id from users t1 cross join characters t2");
        Iterator<PlanNode> iterator = plan.iterator();
        PlanNode planNode = iterator.next();
        assertThat(planNode, instanceOf(NestedLoop.class));
        NestedLoop nl = (NestedLoop) planNode;

        QueryThenFetch left = (QueryThenFetch) nl.left();
        QueryThenFetch right = (QueryThenFetch) nl.right();

        List<Symbol> leftAndRightOutputs = Lists.newArrayList(FluentIterable.from(outputs(left.collectNode())).append(outputs(right.collectNode())));

        TopNProjection topNProjection = (TopNProjection) nl.resultNode().projections().get(0);
        InputColumn in1 = (InputColumn) topNProjection.outputs().get(0);
        InputColumn in2 = (InputColumn) topNProjection.outputs().get(1);
        InputColumn in3 = (InputColumn) topNProjection.outputs().get(2);

        Reference t1Name = (Reference) leftAndRightOutputs.get(in1.index());
        assertThat(t1Name.ident().columnIdent().name(), is("name"));
        assertThat(t1Name.ident().tableIdent().name(), is("users"));

        Reference t2Name = (Reference) leftAndRightOutputs.get(in2.index());
        assertThat(t2Name.ident().columnIdent().name(), is("name"));
        assertThat(t2Name.ident().tableIdent().name(), is("characters"));

        Reference t1Id = (Reference) leftAndRightOutputs.get(in3.index());
        assertThat(t1Id.ident().columnIdent().name(), is("id"));
        assertThat(t1Id.ident().tableIdent().name(), is("users"));
    }

    @Test
    public void testExplicitCrossJoinWith3Tables() throws Exception {
        IterablePlan plan = (IterablePlan)plan("select u1.name, u2.name, u3.name from users u1 cross join users u2 cross join users u3");
        Iterator<PlanNode> iterator = plan.iterator();
        PlanNode planNode = iterator.next();
        assertThat(planNode, instanceOf(NestedLoop.class));
        NestedLoop nl = (NestedLoop) planNode;
        // TODO: assertThat(nl.limit(), is(Constants.DEFAULT_SELECT_LIMIT));
        // TODO: assertThat(nl.offset(), is(0));

        assertThat(nl.resultNode().outputTypes().size(), is(3));
        for (DataType dataType : nl.resultNode().outputTypes()) {
            assertThat(dataType, equalTo((DataType) DataTypes.STRING));
        }
        // TODO: assertThat(nl.left().outputTypes().size() + nl.right().outputTypes().size(), is(3));

       /* PlanNode left = ((IterablePlan)nl.left()).iterator().next();
        NestedLoop nested;
        QueryThenFetch qtf;
        if (left instanceof NestedLoop) {
            nested = (NestedLoop)left;
            qtf = (QueryThenFetch)((IterablePlan) nl.right()).iterator().next();
        } else {
            nested = (NestedLoop)((IterablePlan) nl.right()).iterator().next();
            qtf = (QueryThenFetch) left;
        }
        // TODO: assertThat(nested.limit(), is(Constants.DEFAULT_SELECT_LIMIT));
        // TODO: assertThat(nested.offset(), is(0));

        assertThat(qtf.collectNode().limit(), is(nullValue()));*/
        // TODO: assertThat(qtf.offset(), is(0));
    }

    // TODO: test a plan without fetch phase

    @Test
    public void testCrossJoinTwoTablesWithLimit() throws Exception {
        NestedLoop nl = (NestedLoop)plan("select * from users u1, users u2 limit 2");
        QueryThenFetch left = (QueryThenFetch)nl.left();
        QueryThenFetch right = (QueryThenFetch)nl.right();

        assertThat(left.collectNode().limit(), is(2));
        assertThat(right.collectNode().limit(), is(2)); // TODO: is that really true???

        assertThat(nl.resultNode().projections().size(), is(2));
        assertThat(nl.resultNode().projections().get(0), instanceOf(TopNProjection.class));
        assertThat(nl.resultNode().projections().get(1), instanceOf(FetchProjection.class));

        TopNProjection topNProjection = (TopNProjection)nl.mergeNode().projections().get(0);
        assertThat(topNProjection.limit(), is(2));
    }

    @Test
    public void testAddLiteralIsEvaluatedEarlyInQTF() throws Exception {
        IterablePlan plan = (IterablePlan)plan("select t1.id * (t2.id + 2) from users t1, users t2 limit 1");
        PlanNode planNode = plan.iterator().next();
        assertThat(planNode, instanceOf(NestedLoop.class));
        NestedLoop nl = (NestedLoop) planNode;

        TopNProjection topN = (TopNProjection) nl.resultNode().projections().get(0);

        Function multiply = (Function) topN.outputs().get(0);
        for (Symbol symbol : multiply.arguments()) {
            assertThat(symbol, instanceOf(InputColumn.class));
        }
    }

    @Test
    public void testCrossJoinWithTwoColumnsAndAddSubtractInResultColumns() throws Exception {
        IterablePlan plan = (IterablePlan)plan("select t1.id, t2.id, t1.id + cast(t2.id as integer) from users t1, characters t2");

        PlanNode planNode = plan.iterator().next();
        assertThat(planNode, instanceOf(NestedLoop.class));
        NestedLoop nl = (NestedLoop) planNode;

        QueryThenFetch left = (QueryThenFetch) nl.left();
        QueryThenFetch right = (QueryThenFetch) nl.right();

        // t1 outputs: [ id ]
        // t2 outputs: [ id, cast(id as int) ]

        TopNProjection topNProjection = (TopNProjection) nl.resultNode().projections().get(0);
        InputColumn inputCol1 = (InputColumn) topNProjection.outputs().get(0);
        InputColumn inputCol2 = (InputColumn) topNProjection.outputs().get(1);
        Function add = (Function) topNProjection.outputs().get(2);

        assertThat((InputColumn) add.arguments().get(0), equalTo(inputCol1));

        InputColumn inputCol3 = (InputColumn) add.arguments().get(1);

        // topN projection outputs: [ {point to t1.id}, {point to t2.id}, add( {point to t2.id}, {point to cast(t2.id) }]
        List<Symbol> allOutputs = new ArrayList<>(outputs(left.collectNode()));
        allOutputs.addAll(outputs(right.collectNode()));

        Reference ref1 = (Reference) allOutputs.get(inputCol1.index());
        assertThat(ref1.ident().columnIdent().name(), is("id"));
        assertThat(ref1.ident().tableIdent().name(), is("users"));

        Reference ref2 = (Reference) allOutputs.get(inputCol2.index());
        assertThat(ref2.ident().columnIdent().name(), is("id"));
        assertThat(ref2.ident().tableIdent().name(), is("characters"));

        Symbol castFunction = allOutputs.get(inputCol3.index());
        assertThat(castFunction, isFunction("toInt"));
    }

    @Test
    public void testCrossJoinsWithSubscript() throws Exception {
        IterablePlan plan = (IterablePlan)plan("select address['street'], details['no_such_column'] from users cross join ignored_nested");
        Iterator<PlanNode> iterator = plan.iterator();
        PlanNode planNode = iterator.next();
        assertThat(planNode, instanceOf(NestedLoop.class));
        NestedLoop nl = (NestedLoop) planNode;

        assertThat(nl.resultNode().outputTypes().size(), is(2));
        assertThat(nl.resultNode().outputTypes().get(0).id(), is(DataTypes.STRING.id()));
        assertThat(nl.resultNode().outputTypes().get(1).id(), is(DataTypes.UNDEFINED.id()));
    }

    @Test
    public void testCrossJoinWithWhere() throws Exception {
        IterablePlan plan = (IterablePlan)plan("select * from users t1 cross join users t2 where (t1.id = 1 or t1.id = 2) and (t2.id = 3 or t2.id = 4)");
        Iterator<PlanNode> iterator = plan.iterator();
        PlanNode planNode = iterator.next();
        assertThat(planNode, instanceOf(NestedLoop.class));
        NestedLoop nl = (NestedLoop) planNode;

        /*ESGetNode left = (ESGetNode) ((IterablePlan) nl.left()).iterator().next();
        ESGetNode right = (ESGetNode) ((IterablePlan) nl.right()).iterator().next();

        Iterator<DocKeys.DocKey> t1DocKeyIter;
        Iterator<DocKeys.DocKey> t2DocKeyIter;
        if (left.tableInfo().ident().name().equals("t1")) {
            assertThat(left.querySpec().where().docKeys().isPresent(), is(true));
            t1DocKeyIter = left.querySpec().where().docKeys().get().iterator();
            assertThat(right.querySpec().where().docKeys().isPresent(), is(true));
            t2DocKeyIter = right.querySpec().where().docKeys().get().iterator();
        } else {
            assertThat(right.querySpec().where().docKeys().isPresent(), is(true));
            t1DocKeyIter = right.querySpec().where().docKeys().get().iterator();
            assertThat(left.querySpec().where().docKeys().isPresent(), is(true));
            t2DocKeyIter = left.querySpec().where().docKeys().get().iterator();
        }
        assertThat(t1DocKeyIter.next().values(), contains(anyOf(isLiteral(1L, DataTypes.LONG), isLiteral(2L, DataTypes.LONG))));
        assertThat(t1DocKeyIter.next().values(), contains(anyOf(isLiteral(1L, DataTypes.LONG), isLiteral(2L, DataTypes.LONG))));
        assertThat(t1DocKeyIter.hasNext(), is(false));

        assertThat(t2DocKeyIter.next().values(), contains(anyOf(isLiteral(3L, DataTypes.LONG), isLiteral(4L, DataTypes.LONG))));
        assertThat(t2DocKeyIter.next().values(), contains(anyOf(isLiteral(3L, DataTypes.LONG), isLiteral(4L, DataTypes.LONG))));
        assertThat(t2DocKeyIter.hasNext(), is(false));*/
    }

    @Test
    public void testCrossJoinWhereWithJoinCondition() throws Exception {
        IterablePlan plan = (IterablePlan)plan("select * from users t1 cross join users t2 where t1.id = 1 or t2.id = 2");
        NestedLoop nl = (NestedLoop) plan.iterator().next();
        // TODO: assertThat(nl.limit(), is(Constants.DEFAULT_SELECT_LIMIT));
        // TODO: assertThat(nl.offset(), is(0));

        Projection projection = nl.resultNode().projections().get(0);
        assertThat(projection, instanceOf(FilterProjection.class));
        Symbol query = ((FilterProjection) projection).query();
        assertThat(query, isFunction(OrOperator.NAME));
    }

    @Test
    public void testCrossJoinWhereSingleBooleanField() throws Exception {
        IterablePlan plan = (IterablePlan)plan("select * from users t1 cross join users t2 where t1.is_awesome");
        Iterator<PlanNode> iterator = plan.iterator();
        PlanNode planNode = iterator.next();
        assertThat(planNode, instanceOf(NestedLoop.class));
        NestedLoop nl = (NestedLoop) planNode;

        QueryThenFetch left = (QueryThenFetch) nl.left();
        QueryThenFetch right = (QueryThenFetch) nl.right();


        WhereClause leftWhereClause = left.collectNode().whereClause();
        WhereClause rightWhereClause = right.collectNode().whereClause();
        // left and right isn't deterministic... but one needs to have a query and the other shouldn't have one
        if (leftWhereClause.hasQuery()) {
            assertThat(rightWhereClause.hasQuery(), is(false));
        } else {
            assertThat(rightWhereClause.hasQuery(), is(true));
        }
    }

    @Test
    public void testCrossJoinThreeTablesWithLimitAndOffset() throws Exception {
        IterablePlan plan = (IterablePlan)plan("select * from users t1 cross join users t2 cross join users t3 limit 10 offset 2");
        Iterator<PlanNode> iterator = plan.iterator();
        PlanNode planNode = iterator.next();
        assertThat(planNode, instanceOf(NestedLoop.class));
        NestedLoop nl = (NestedLoop) planNode;

        // TODO: assertThat(nl.limit(), is(10));
        // TODO: assertThat(nl.offset(), is(2));

       /* IterablePlan leftPlan = (IterablePlan) nl.left();
        IterablePlan rightPlan = (IterablePlan) nl.right();

        NestedLoop nested;
        QueryThenFetch qtf;
        if (leftPlan.iterator().next() instanceof NestedLoop) {
            nested = (NestedLoop) leftPlan.iterator().next();
            qtf = (QueryThenFetch) rightPlan.iterator().next();
        } else {
            nested = (NestedLoop) rightPlan.iterator().next();
            qtf = (QueryThenFetch) leftPlan.iterator().next();
        }
        // TODO: assertThat(nested.limit(), is(12));
        // TODO: assertThat(nested.offset(), is(0));
        assertThat(qtf.collectNode().limit(), is(12));
        // TODO: assertThat(qtf.offset(), is(0));

        leftPlan = (IterablePlan) nested.left();
        rightPlan = (IterablePlan) nested.right();

        QueryThenFetch qtfLeftNested = (QueryThenFetch) leftPlan.iterator().next();
        QueryThenFetch qtfRightNested = (QueryThenFetch) rightPlan.iterator().next();

        assertThat(qtfLeftNested.collectNode().limit(), is(12));
        // TODO: assertThat(qtfLeftNested.offset(), is(0));
        assertThat(qtfRightNested.collectNode().limit(), is(12));
        //TODO: assertThat(qtfRightNested.offset(), is(0));*/
    }

    @Test
    public void testCrossJoinThreeTablesWithJoinConditionInOrderBy() throws Exception {
        IterablePlan plan = (IterablePlan)plan("select * from users t1 cross join users t2 cross join users t3 order by t3.id + t2.id limit 10 offset 2");
        Iterator<PlanNode> iterator = plan.iterator();
        PlanNode planNode = iterator.next();
        assertThat(planNode, instanceOf(NestedLoop.class));
        NestedLoop nl = (NestedLoop) planNode;

        // TODO: assertThat(nl.limit(), is(10));
        // TODO: assertThat(nl.offset(), is(2));

        /*IterablePlan leftPlan = (IterablePlan) nl.left();
        IterablePlan rightPlan = (IterablePlan) nl.right();


        NestedLoop nested;
        QueryThenFetch qtf;
        if (leftPlan.iterator().next() instanceof NestedLoop) {
            nested = (NestedLoop) leftPlan.iterator().next();
            qtf = (QueryThenFetch) rightPlan.iterator().next();
        } else {
            nested = (NestedLoop) rightPlan.iterator().next();
            qtf = (QueryThenFetch) leftPlan.iterator().next();
        }
        // TODO: assertThat(nested.limit(), is(TopN.NO_LIMIT));
        // TODO: assertThat(nested.offset(), is(0));
        assertThat(qtf.collectNode().limit(), is(nullValue()));
        // TODO: assertThat(qtf.offset(), is(0));

        leftPlan = (IterablePlan) nested.left();
        rightPlan = (IterablePlan) nested.right();

        QueryThenFetch qtfLeftNested = (QueryThenFetch) leftPlan.iterator().next();
        QueryThenFetch qtfRightNested = (QueryThenFetch) rightPlan.iterator().next();

        assertThat(qtfLeftNested.collectNode().limit(), is(nullValue()));
        // TODO: assertThat(qtfLeftNested.offset(), is(0));

        assertThat(qtfRightNested.collectNode().limit(), is(nullValue()));
        // TODO: assertThat(qtfRightNested.offset(), is(0));*/
    }

    @Test
    public void testCrossJoinThreeTablesWithJoinConditionInWhereClause() throws Exception {
        IterablePlan plan = (IterablePlan)plan("select * from users t1 cross join users t2 cross join users t3 where t3.id = t2.id limit 10 offset 2");
        Iterator<PlanNode> iterator = plan.iterator();
        PlanNode planNode = iterator.next();
        assertThat(planNode, instanceOf(NestedLoop.class));
        NestedLoop nl = (NestedLoop) planNode;

        // TODO: assertThat(nl.limit(), is(10));
        // TODO: assertThat(nl.offset(), is(2));

       /* IterablePlan leftPlan = (IterablePlan) nl.left();
        IterablePlan rightPlan = (IterablePlan) nl.right();

        NestedLoop nested;
        QueryThenFetch qtf;
        if (leftPlan.iterator().next() instanceof NestedLoop) {
            nested = (NestedLoop) leftPlan.iterator().next();
            qtf = (QueryThenFetch) rightPlan.iterator().next();
        } else {
            nested = (NestedLoop) rightPlan.iterator().next();
            qtf = (QueryThenFetch) leftPlan.iterator().next();
        }
        // TODO: assertThat(nested.limit(), is(TopN.NO_LIMIT));
        // TODO: assertThat(nested.offset(), is(0));
        assertThat(qtf.collectNode().limit(), is(nullValue()));
        // TODO: assertThat(qtf.offset(), is(0));

        leftPlan = (IterablePlan) nested.left();
        rightPlan = (IterablePlan) nested.right();

        QueryThenFetch qtfLeftNested = (QueryThenFetch) leftPlan.iterator().next();
        QueryThenFetch qtfRightNested = (QueryThenFetch) rightPlan.iterator().next();

        assertThat(qtfLeftNested.collectNode().limit(), is(nullValue()));
        // TODO: assertThat(qtfLeftNested.offset(), is(0));

        assertThat(qtfRightNested.collectNode().limit(), is(nullValue()));
        //TODO: assertThat(qtfRightNested.offset(), is(0));*/
    }

    private List<Symbol> outputs(AbstractDQLPlanNode planNode) {
        int projectionIdx = planNode.projections().size() - 1;
        return (List<Symbol>)planNode.projections().get(projectionIdx).outputs();
    }
}