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

import org.apache.calcite.adapter.java.JavaTypeFactory;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.util.Pair;

import java.util.ArrayList;
import java.util.List;

/**
 * Implementation of {@link org.apache.calcite.rel.core.Project}
 * relational expression in Cassandra.
 */
public class CassandraProject extends Project implements CassandraRel {
  public CassandraProject(RelOptCluster cluster, RelTraitSet traitSet,
      RelNode input, List<? extends RexNode> projects, RelDataType rowType) {
    super(cluster, traitSet, input, projects, rowType);
    assert getConvention() == CassandraRel.CONVENTION;
    assert getConvention() == input.getConvention();
  }

  @Override public Project copy(RelTraitSet traitSet, RelNode input,
      List<RexNode> projects, RelDataType rowType) {
    return new CassandraProject(getCluster(), traitSet, input, projects,
        rowType);
  }

  public void implement(Implementor implementor) {
    implementor.visitChild(0, getInput());
    final CassandraRules.RexToCassandraTranslator translator =
        new CassandraRules.RexToCassandraTranslator(
            (JavaTypeFactory) getCluster().getTypeFactory(),
            CassandraRules.cassandraFieldNames(getInput().getRowType()));
    final List<String> fields = new ArrayList<String>();
    for (Pair<RexNode, String> pair : getNamedProjects()) {
      final String name = pair.right;
      final String expr = pair.left.accept(translator);

      // Alias the field if necessary
      if (name.equals(expr)) {
        fields.add(name);
      } else {
        fields.add(name + " AS " + expr);
      }
    }
    implementor.add(fields, null);
  }
}

// End CassandraProject.java