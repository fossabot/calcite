/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.calcite.adapter.cassandra;

import org.apache.calcite.adapter.java.AbstractQueryableTable;
import org.apache.calcite.linq4j.AbstractEnumerable;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.Enumerator;
import org.apache.calcite.linq4j.QueryProvider;
import org.apache.calcite.linq4j.Queryable;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.rel.RelFieldCollation;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelDataTypeImpl;
import org.apache.calcite.rel.type.RelDataTypeSystem;
import org.apache.calcite.rel.type.RelProtoDataType;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.TranslatableTable;
import org.apache.calcite.schema.impl.AbstractTableQueryable;
import org.apache.calcite.sql.type.SqlTypeFactoryImpl;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.util.Pair;
import org.apache.calcite.util.Util;

import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Session;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Table based on a Cassandra column family
 */
public class CassandraTable extends AbstractQueryableTable
    implements TranslatableTable {
  RelProtoDataType protoRowType;
  Pair<List<String>, List<String>> keyFields;
  List<RelFieldCollation> clusteringOrder;
  private final CassandraSchema schema;
  private final String columnFamily;
  private final boolean view;

  public CassandraTable(CassandraSchema schema, String columnFamily, boolean view) {
    super(Object[].class);
    this.schema = schema;
    this.columnFamily = columnFamily;
    this.view = view;
  }

  public CassandraTable(CassandraSchema schema, String columnFamily) {
    this(schema, columnFamily, false);
  }

  public String toString() {
    return "CassandraTable {" + columnFamily + "}";
  }

  public RelDataType getRowType(RelDataTypeFactory typeFactory) {
    if (protoRowType == null) {
      protoRowType = schema.getRelDataType(columnFamily, view);
    }
    return protoRowType.apply(typeFactory);
  }

  public Pair<List<String>, List<String>> getKeyFields() {
    if (keyFields == null) {
      keyFields = schema.getKeyFields(columnFamily, view);
    }
    return keyFields;
  }

  public List<RelFieldCollation> getClusteringOrder() {
    if (clusteringOrder == null) {
      clusteringOrder = schema.getClusteringOrder(columnFamily, view);
    }
    return clusteringOrder;
  }

  public Enumerable<Object> query(final Session session) {
    return query(session, Collections.<Map.Entry<String, Class>>emptyList(),
        Collections.<String, String>emptyMap(), Collections.<String>emptyList(),
        Collections.<String>emptyList(), null);
  }

  /** Executes a CQL query on the underlying table.
   *
   * @param session Cassandra session
   * @param fields List of fields to project
   * @param predicates A list of predicates which should be used in the query
   * @return Enumerator of results
   */
  public Enumerable<Object> query(final Session session, List<Map.Entry<String, Class>> fields,
        final Map<String, String> selectFields, List<String> predicates,
        List<String> order, String limit) {
    // Build the type of the resulting row based on the provided fields
    final RelDataTypeFactory typeFactory =
        new SqlTypeFactoryImpl(RelDataTypeSystem.DEFAULT);
    final RelDataTypeFactory.FieldInfoBuilder fieldInfo = typeFactory.builder();
    final RelDataType rowType = protoRowType.apply(typeFactory);
    for (Map.Entry<String, String> field : selectFields.entrySet()) {
      String fieldName = field.getKey();
      SqlTypeName typeName = rowType.getField(fieldName, true, false).getType().getSqlTypeName();
      fieldInfo.add(fieldName, typeFactory.createSqlType(typeName)).nullable(true);
    }
    final RelProtoDataType resultRowType = RelDataTypeImpl.proto(fieldInfo.build());

    // Construct the list of fields to project
    final String selectString;
    if (fields.isEmpty()) {
      selectString = "*";
    } else {
      selectString = Util.toString(new Iterable<String>() {
        public Iterator<String> iterator() {
          final Iterator<Map.Entry<String, String>> selectIterator =
              selectFields.entrySet().iterator();

          return new Iterator<String>() {
            public boolean hasNext() {
              return selectIterator.hasNext();
            }

            public String next() {
              Map.Entry<String, String> entry = selectIterator.next();
              return entry.getKey() + " AS " + entry.getValue();
            }
          };
        }
      }, "", ", ", "");
    }

    // Combine all predicates conjunctively
    String whereClause = "";
    if (!predicates.isEmpty()) {
      whereClause = " WHERE ";
      whereClause += Util.toString(predicates, "", " AND ", "");
    }

    // Build and issue the query and return an Enumerator over the results
    StringBuilder queryBuilder = new StringBuilder("SELECT ");
    queryBuilder.append(selectString);
    queryBuilder.append(" FROM \"" + columnFamily + "\"");
    queryBuilder.append(whereClause);
    if (!order.isEmpty()) {
      queryBuilder.append(Util.toString(order, " ORDER BY ", ", ", ""));
    }
    if (limit != null) {
      queryBuilder.append(" LIMIT " + limit);
    }
    queryBuilder.append(" ALLOW FILTERING");
    final String query = queryBuilder.toString();

    return new AbstractEnumerable<Object>() {
      public Enumerator<Object> enumerator() {
        final ResultSet results = session.execute(query);
        return new CassandraEnumerator(results, resultRowType);
      }
    };
  }

  public <T> Queryable<T> asQueryable(QueryProvider queryProvider,
      SchemaPlus schema, String tableName) {
    return new CassandraQueryable<>(queryProvider, schema, this, tableName);
  }

  public RelNode toRel(
      RelOptTable.ToRelContext context,
      RelOptTable relOptTable) {
    final RelOptCluster cluster = context.getCluster();
    return new CassandraTableScan(cluster, cluster.traitSetOf(CassandraRel.CONVENTION),
        relOptTable, this, null);
  }

  /** Implementation of {@link org.apache.calcite.linq4j.Queryable} based on
   * a {@link org.apache.calcite.adapter.cassandra.CassandraTable}. */
  public static class CassandraQueryable<T> extends AbstractTableQueryable<T> {
    public CassandraQueryable(QueryProvider queryProvider, SchemaPlus schema,
        CassandraTable table, String tableName) {
      super(queryProvider, schema, table, tableName);
    }

    public Enumerator<T> enumerator() {
      //noinspection unchecked
      final Enumerable<T> enumerable =
          (Enumerable<T>) getTable().query(getSession());
      return enumerable.enumerator();
    }

    private CassandraTable getTable() {
      return (CassandraTable) table;
    }

    private Session getSession() {
      return schema.unwrap(CassandraSchema.class).session;
    }

    /** Called via code-generation.
     *
     * @see org.apache.calcite.adapter.cassandra.CassandraMethod#CASSANDRA_QUERYABLE_QUERY
     */
    @SuppressWarnings("UnusedDeclaration")
    public Enumerable<Object> query(List<Map.Entry<String, Class>> fields,
        Map<String, String> selectFields, List<String> predicates,
        List<String> order, String limit) {
      return getTable().query(getSession(), fields, selectFields, predicates,
          order, limit);
    }
  }
}

// End CassandraTable.java
