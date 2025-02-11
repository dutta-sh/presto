/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.sql.rewrite;

import com.facebook.airlift.log.Logger;
import com.facebook.presto.Session;
import com.facebook.presto.SystemSessionProperties;
import com.facebook.presto.common.QualifiedObjectName;
import com.facebook.presto.metadata.Metadata;
import com.facebook.presto.security.AccessControl;
import com.facebook.presto.spi.ConnectorMaterializedViewDefinition;
import com.facebook.presto.spi.MaterializedViewStatus;
import com.facebook.presto.spi.WarningCollector;
import com.facebook.presto.sql.analyzer.MaterializedViewCandidateExtractor;
import com.facebook.presto.sql.analyzer.MaterializedViewQueryOptimizer;
import com.facebook.presto.sql.analyzer.QueryExplainer;
import com.facebook.presto.sql.parser.SqlParser;
import com.facebook.presto.sql.relational.RowExpressionDomainTranslator;
import com.facebook.presto.sql.tree.AstVisitor;
import com.facebook.presto.sql.tree.Expression;
import com.facebook.presto.sql.tree.Node;
import com.facebook.presto.sql.tree.NodeRef;
import com.facebook.presto.sql.tree.Parameter;
import com.facebook.presto.sql.tree.Query;
import com.facebook.presto.sql.tree.Statement;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.facebook.presto.SystemSessionProperties.isMaterializedViewDataConsistencyEnabled;
import static com.facebook.presto.common.RuntimeMetricName.MANY_PARTITIONS_MISSING_IN_MATERIALIZED_VIEW_COUNT;
import static com.facebook.presto.common.RuntimeMetricName.OPTIMIZED_WITH_MATERIALIZED_VIEW_COUNT;
import static com.facebook.presto.common.RuntimeUnit.NONE;
import static java.util.Objects.requireNonNull;

public class MaterializedViewOptimizationRewrite
        implements StatementRewrite.Rewrite
{
    private static final Logger log = Logger.get(MaterializedViewOptimizationRewrite.class);
    @Override
    public Statement rewrite(
            Session session,
            Metadata metadata,
            SqlParser parser,
            Optional<QueryExplainer> queryExplainer,
            Statement node,
            List<Expression> parameters,
            Map<NodeRef<Parameter>, Expression> parameterLookup,
            AccessControl accessControl,
            WarningCollector warningCollector)
    {
        return (Statement) new MaterializedViewOptimizationRewrite
                .Visitor(metadata, session, parser, accessControl)
                .process(node, null);
    }

    private static final class Visitor
            extends AstVisitor<Node, Void>
    {
        private final Metadata metadata;
        private final Session session;
        private final SqlParser sqlParser;
        private final AccessControl accessControl;

        public Visitor(
                Metadata metadata,
                Session session,
                SqlParser parser,
                AccessControl accessControl)
        {
            this.metadata = requireNonNull(metadata, "metadata is null");
            this.session = requireNonNull(session, "session is null");
            this.sqlParser = requireNonNull(parser, "queryPreparer is null");
            this.accessControl = requireNonNull(accessControl, "access control is null");
        }

        @Override
        protected Node visitNode(Node node, Void context)
        {
            return node;
        }

        protected Node visitQuery(Query query, Void context)
        {
            if (SystemSessionProperties.isQueryOptimizationWithMaterializedViewEnabled(session)) {
                return optimizeQueryUsingMaterializedView(metadata, session, sqlParser, accessControl, query);
            }
            return query;
        }
    }

    private static Query optimizeQueryUsingMaterializedView(
            Metadata metadata,
            Session session,
            SqlParser sqlParser,
            AccessControl accessControl,
            Query node)
    {
        MaterializedViewCandidateExtractor materializedViewCandidateExtractor = new MaterializedViewCandidateExtractor(session, metadata);
        materializedViewCandidateExtractor.process(node);
        Map<QualifiedObjectName, List<QualifiedObjectName>> baseTableToMaterializedViewCandidateMap = materializedViewCandidateExtractor.getMaterializedViewCandidatesForTable();
        // TODO: Select the most compatible and efficient materialized view for query rewrite optimization https://github.com/prestodb/presto/issues/16431
        // TODO: Refactor query optimization code https://github.com/prestodb/presto/issues/16759

        Map<QualifiedObjectName, List<ConnectorMaterializedViewDefinition>> baseTableToMaterializedViewDefinitionMap = new HashMap<>();
        baseTableToMaterializedViewCandidateMap.forEach((baseTable, materializedViewNames) -> {
            for (QualifiedObjectName materializedViewName : materializedViewNames) {
                MaterializedViewStatus materializedViewStatus = metadata.getMaterializedViewStatus(session, materializedViewName);
                // TODO: Refactor this so we increment metric only when rewrite would have occurred
                if (!(materializedViewStatus.isPartiallyMaterialized() || materializedViewStatus.isFullyMaterialized())
                        && isMaterializedViewDataConsistencyEnabled(session)) {
                    session.getRuntimeStats().addMetricValue(MANY_PARTITIONS_MISSING_IN_MATERIALIZED_VIEW_COUNT, NONE, 1);
                    continue;
                }
                ConnectorMaterializedViewDefinition materializedView = metadata.getMaterializedView(session, materializedViewName).orElseThrow(() ->
                        new IllegalStateException("Materialized view definition not present in metadata as expected."));
                baseTableToMaterializedViewDefinitionMap.putIfAbsent(baseTable, new ArrayList<>());
                baseTableToMaterializedViewDefinitionMap.get(baseTable).add(materializedView);
            }
        });

        // No fresh materialized views to use - no need to attempt rewrite
        if (baseTableToMaterializedViewDefinitionMap.isEmpty()) {
            log.warn("Failed to optimize query as no fresh materialized view was available for tables: " +
                    Joiner.on(",").join(baseTableToMaterializedViewDefinitionMap.keySet()));
            return node;
        }

        Query rewritten = (Query) new MaterializedViewQueryOptimizer(
                metadata,
                session,
                sqlParser,
                accessControl,
                new RowExpressionDomainTranslator(metadata),
                ImmutableMap.copyOf(baseTableToMaterializedViewDefinitionMap))
                .process(node);

        if (rewritten != node) {
            session.getRuntimeStats().addMetricValue(OPTIMIZED_WITH_MATERIALIZED_VIEW_COUNT, NONE, 1);
            return rewritten;
        }

        return node;
    }
}
