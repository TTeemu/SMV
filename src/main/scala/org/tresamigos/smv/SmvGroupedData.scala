/*
 * This file is licensed under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.tresamigos.smv

import org.apache.spark.rdd.RDD
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.{Column, ColumnName}
import org.apache.spark.sql.GroupedData
import org.apache.spark.sql.functions._
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.analysis._
import org.apache.spark.sql.catalyst.plans.{JoinType, Inner}

case class SmvGroupedData(df: DataFrame, keys: Seq[String]) {
  def toDF: DataFrame = df
  def toGroupedData: GroupedData = df.groupBy(keys(0), keys.tail: _*)
}

class SmvGroupedDataFunc(smvGD: SmvGroupedData) {
  private val df = smvGD.df
  private val keys = smvGD.keys
  
  
  def smvMapGroup(gdo: SmvGDO): SmvGroupedData = {
    val smvSchema = SmvSchema.fromDataFrame(df)
    val ordinals = smvSchema.getIndices(keys: _*)
    val rowToKeys: Row => Seq[Any] = {row =>
      ordinals.map{i => row(i)}
    }
    
    val inGroupMapping =  gdo.createInGroupMapping(smvSchema) 
    val rdd = df.rdd.
      groupBy(rowToKeys).
      flatMapValues(rowsInGroup => inGroupMapping(rowsInGroup)).
      values

    val newdf = df.sqlContext.applySchemaToRowRDD(rdd, gdo.createOutSchema(smvSchema))
    SmvGroupedData(newdf, keys ++ gdo.inGroupKeys)
  }
 
  def smvMapGroup(cds: SmvCDS): SmvGroupedData = {
    val gdo = new SmvCDSAsGDO(cds)
    smvMapGroup(gdo)
  }
  
  /**
   * smvPivot on SmvGroupedData is similar to smvPivot on DF
   * 
   * Input
   *  | id  | month | product | count |
   *  | --- | ----- | ------- | ----- |
   *  | 1   | 5/14  |   A     |   100 |
   *  | 1   | 6/14  |   B     |   200 |
   *  | 1   | 5/14  |   B     |   300 |
   * 
   * Output
   *  | id  | count_5_14_A | count_5_14_B | count_6_14_A | count_6_14_B |
   *  | --- | ------------ | ------------ | ------------ | ------------ |
   *  | 1   | 100          | NULL         | NULL         | NULL         |
   *  | 1   | NULL         | NULL         | NULL         | 200          |
   *  | 1   | NULL         | 300          | NULL         | NULL         |
   * 
   * df.groupBy("id").smvPivot(Seq("month", "product"))("count")(
   *    "5_14_A", "5_14_B", "6_14_A", "6_14_B")
   **/
  def smvPivot(pivotCols: Seq[String]*)(valueCols: String*)(baseOutput: String*): SmvGroupedData = {
    // TODO: handle baseOutput == null with inferring using getBaseOutputColumnNames
    val pivot= SmvPivot(pivotCols, valueCols.map{v => (v, v)}, baseOutput)
    SmvGroupedData(pivot.createSrdd(df, keys), keys)
  }
  
  /**
   * smvPivotSum is a helper function on smvPivot
   * 
   * df.groupBy("id").smvPivotSum(Seq("month", "product"))("count")("5_14_A", "5_14_B", "6_14_A", "6_14_B")
   * 
   * Output
   *  | id  | count_5_14_A | count_5_14_B | count_6_14_A | count_6_14_B |
   *  | --- | ------------ | ------------ | ------------ | ------------ |
   *  | 1   | 100          | 300          | NULL         | 200          |
   **/
  def smvPivotSum(pivotCols: Seq[String]*)(valueCols: String*)(baseOutput: String*): DataFrame = {
    import df.sqlContext.implicits._
    // TODO: handle baseOutput == null with inferring using getBaseOutputColumnNames
    val pivot= SmvPivot(pivotCols, valueCols.map{v => (v, v)}, baseOutput)
    val outCols = pivot.outCols().map{l=>(sum(l) as l)}
    val aggCols = keys.map{k => df(k)} ++ outCols
    smvPivot(pivotCols: _*)(valueCols: _*)(baseOutput: _*).agg(aggCols(0), aggCols.tail: _*)
  }
  
  def smvQuantile(valueCol: String, numBins: Integer): DataFrame = {
    smvMapGroup(new SmvQuantile(valueCol, numBins)).toDF
  }
  
  def smvDecile(valueCol: String): DataFrame = {
    smvMapGroup(new SmvQuantile(valueCol, 10)).toDF
  }
  
  /**
   * See RollupCubeOp for details.
   * 
   * Example:
   *   df.smvGroupBy("year").smvCube("zip", "month").agg("year", "zip", "month", sum("v") as "v")
   * 
   * For zip & month columns with input values:
   *   90001, 201401
   *   10001, 201501
   * 
   * The "cubed" values on those 2 columns are:
   *   90001, *
   *   10001, *
   *   *, 201401
   *   *, 201501
   *   90001, 201401
   *   10001, 201501
   * 
   * where * stand for "any" 
   * 
   * Also have a version on DF.
   **/
  def smvCube(cols: String*): SmvGroupedData = {
    new RollupCubeOp(df, keys, cols).cube()
  }
  
  /**
   * See RollupCubeOp for details.
   * 
   * Example:
   *   df.smvGroupBy("year").smvRollup("county", "zip").agg("year", "county", "zip", sum("v") as "v")
   * 
   * For county & zip with input values:
   *   10234, 92101
   *   10234, 10019
   * 
   * The "rolluped" values are:
   *   10234, *
   *   10234, 92101
   *   10234, 10019
   * 
   * Also have a version on DF
   **/
  def smvRollup(cols: String*): SmvGroupedData = {
    new RollupCubeOp(df, keys, cols).rollup()
  }
  
  def smvTopNRecs(maxElems: Int, orders: Column*) = {
    val cds = SmvTopNRecsCDS(maxElems, orders.map{o => o.toExpr})
    smvMapGroup(cds).toDF
  }
  
  def aggWithKeys(cols: Column*) = {
    val allCols = keys.map{k => new ColumnName(k)} ++ cols
    smvGD.toGroupedData.agg(allCols(0), allCols.tail: _*)
  }
  
  def inMemAgg(aggCols: SmvCDSAggColumn*): DataFrame = {
    val gdo = new SmvCDSAggGDO(aggCols)
    
    /* Since SmvCDSAggGDO grouped aggregations with the same CDS together, the ordering of the 
       columns is no more the same as the input list specified. Here to put them in order */
    val colNames = aggCols.map{a => a.aggExpr.asInstanceOf[NamedExpression].name}
    smvMapGroup(gdo).toDF.select(colNames(0), colNames.tail: _*)
  }
  
  def runAgg(aggCols: SmvCDSAggColumn*): DataFrame = {
    val gdo = new SmvCDSRunAggGDO(aggCols)
    val colNames = aggCols.map{a => a.aggExpr.asInstanceOf[NamedExpression].name}
    smvMapGroup(gdo).toDF.select(colNames(0), colNames.tail: _*)
  }
}
