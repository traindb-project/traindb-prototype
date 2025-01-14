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

package traindb.adapter.file;

import com.google.common.collect.ImmutableList;
import java.util.List;
import org.apache.calcite.adapter.enumerable.EnumerableConvention;
import org.apache.calcite.adapter.enumerable.EnumerableRel;
import org.apache.calcite.adapter.enumerable.EnumerableRelImplementor;
import org.apache.calcite.adapter.enumerable.PhysType;
import org.apache.calcite.adapter.enumerable.PhysTypeImpl;
import org.apache.calcite.linq4j.tree.Blocks;
import org.apache.calcite.linq4j.tree.Expressions;
import org.apache.calcite.linq4j.tree.Primitive;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptCost;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelWriter;
import org.apache.calcite.rel.core.TableScan;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Relational expression representing a scan of a CSV file.
 *
 * <p>Like any table scan, it serves as a leaf node of a query tree.
 */
public class CsvTableScan extends TableScan implements EnumerableRel {
  final TrainDBFileTable csvTable;
  final int[] fields;

  public CsvTableScan(RelOptCluster cluster, RelOptTable table, TrainDBFileTable csvTable,
                      int[] fields) {
    super(cluster, cluster.traitSetOf(EnumerableConvention.INSTANCE), ImmutableList.of(), table);
    this.csvTable = csvTable;
    this.fields = fields;

    assert csvTable != null;
  }

  @Override
  public RelNode copy(RelTraitSet traitSet, List<RelNode> inputs) {
    assert inputs.isEmpty();
    return new CsvTableScan(getCluster(), table, csvTable, fields);
  }

  @Override
  public RelWriter explainTerms(RelWriter pw) {
    return super.explainTerms(pw)
        .item("fields", Primitive.asList(fields));
  }

  @Override
  public RelDataType deriveRowType() {
    final List<RelDataTypeField> fieldList = table.getRowType().getFieldList();
    final RelDataTypeFactory.Builder builder = getCluster().getTypeFactory().builder();
    for (int field : fields) {
      builder.add(fieldList.get(field));
    }
    return builder.build();
  }

  @Override
  public void register(RelOptPlanner planner) {
    planner.addRule(CsvRules.PROJECT_SCAN);
  }

  @Override
  public @Nullable RelOptCost computeSelfCost(RelOptPlanner planner,
                                              RelMetadataQuery mq) {
    // Multiply the cost by a factor that makes a scan more attractive if it
    // has significantly fewer fields than the original scan.
    //
    // The "+ 2D" on top and bottom keeps the function fairly smooth.
    //
    // For example, if table has 3 fields, project has 1 field,
    // then factor = (1 + 2) / (3 + 2) = 0.6
    return super.computeSelfCost(planner, mq)
        .multiplyBy(((double) fields.length + 2D)
            / ((double) table.getRowType().getFieldCount() + 2D));
  }

  @Override
  public EnumerableRel.Result implement(EnumerableRelImplementor implementor,
                                        EnumerableRel.Prefer pref) {
    PhysType physType =
        PhysTypeImpl.of(
            implementor.getTypeFactory(),
            getRowType(),
            pref.preferArray());

    return implementor.result(
        physType,
        Blocks.toBlock(
            Expressions.call(table.getExpression(TrainDBFileTable.class),
                "project", implementor.getRootExpression(),
                Expressions.constant(fields))));
  }

  public TrainDBFileTable getCsvTable() {
    return csvTable;
  }
}
