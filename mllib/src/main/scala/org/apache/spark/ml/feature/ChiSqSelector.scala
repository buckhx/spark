/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
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

package org.apache.spark.ml.feature

import org.apache.spark.annotation.Experimental
import org.apache.spark.ml._
import org.apache.spark.ml.attribute.{AttributeGroup, _}
import org.apache.spark.ml.param._
import org.apache.spark.ml.param.shared._
import org.apache.spark.ml.util.Identifiable
import org.apache.spark.ml.util.SchemaUtils
import org.apache.spark.mllib.feature
import org.apache.spark.mllib.linalg.{Vector, VectorUDT}
import org.apache.spark.mllib.regression.LabeledPoint
import org.apache.spark.sql._
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types.{DoubleType, StructField, StructType}

/**
 * Params for [[ChiSqSelector]] and [[ChiSqSelectorModel]].
 */
private[feature] trait ChiSqSelectorParams extends Params
  with HasFeaturesCol with HasOutputCol with HasLabelCol {

  /**
   * Number of features that selector will select (ordered by statistic value descending). If the
   * number of features is < numTopFeatures, then this will select all features. The default value
   * of numTopFeatures is 50.
   * @group param
   */
  final val numTopFeatures = new IntParam(this, "numTopFeatures",
    "Number of features that selector will select, ordered by statistics value descending. If the" +
      " number of features is < numTopFeatures, then this will select all features.",
    ParamValidators.gtEq(1))
  setDefault(numTopFeatures -> 50)

  /** @group getParam */
  def getNumTopFeatures: Int = $(numTopFeatures)
}

/**
 * :: Experimental ::
 * Chi-Squared feature selection, which selects categorical features to use for predicting a
 * categorical label.
 */
@Experimental
final class ChiSqSelector(override val uid: String)
  extends Estimator[ChiSqSelectorModel] with ChiSqSelectorParams {

  def this() = this(Identifiable.randomUID("chiSqSelector"))

  /** @group setParam */
  def setNumTopFeatures(value: Int): this.type = set(numTopFeatures, value)

  /** @group setParam */
  def setFeaturesCol(value: String): this.type = set(featuresCol, value)

  /** @group setParam */
  def setOutputCol(value: String): this.type = set(outputCol, value)

  /** @group setParam */
  def setLabelCol(value: String): this.type = set(labelCol, value)

  override def fit(dataset: DataFrame): ChiSqSelectorModel = {
    transformSchema(dataset.schema, logging = true)
    val input = dataset.select($(labelCol), $(featuresCol)).map {
      case Row(label: Double, features: Vector) =>
        LabeledPoint(label, features)
    }
    val chiSqSelector = new feature.ChiSqSelector($(numTopFeatures)).fit(input)
    copyValues(new ChiSqSelectorModel(uid, chiSqSelector).setParent(this))
  }

  override def transformSchema(schema: StructType): StructType = {
    SchemaUtils.checkColumnType(schema, $(featuresCol), new VectorUDT)
    SchemaUtils.checkColumnType(schema, $(labelCol), DoubleType)
    SchemaUtils.appendColumn(schema, $(outputCol), new VectorUDT)
  }

  override def copy(extra: ParamMap): ChiSqSelector = defaultCopy(extra)
}

/**
 * :: Experimental ::
 * Model fitted by [[ChiSqSelector]].
 */
@Experimental
final class ChiSqSelectorModel private[ml] (
    override val uid: String,
    private val chiSqSelector: feature.ChiSqSelectorModel)
  extends Model[ChiSqSelectorModel] with ChiSqSelectorParams {

  /** @group setParam */
  def setFeaturesCol(value: String): this.type = set(featuresCol, value)

  /** @group setParam */
  def setOutputCol(value: String): this.type = set(outputCol, value)

  /** @group setParam */
  def setLabelCol(value: String): this.type = set(labelCol, value)

  override def transform(dataset: DataFrame): DataFrame = {
    val transformedSchema = transformSchema(dataset.schema, logging = true)
    val newField = transformedSchema.last
    val selector = udf { chiSqSelector.transform _ }
    dataset.withColumn($(outputCol), selector(col($(featuresCol))), newField.metadata)
  }

  override def transformSchema(schema: StructType): StructType = {
    SchemaUtils.checkColumnType(schema, $(featuresCol), new VectorUDT)
    val newField = prepOutputField(schema)
    val outputFields = schema.fields :+ newField
    StructType(outputFields)
  }

  /**
   * Prepare the output column field, including per-feature metadata.
   */
  private def prepOutputField(schema: StructType): StructField = {
    val selector = chiSqSelector.selectedFeatures.toSet
    val origAttrGroup = AttributeGroup.fromStructField(schema($(featuresCol)))
    val featureAttributes: Array[Attribute] = if (origAttrGroup.attributes.nonEmpty) {
      origAttrGroup.attributes.get.zipWithIndex.filter(x => selector.contains(x._2)).map(_._1)
    } else {
      Array.fill[Attribute](selector.size)(NominalAttribute.defaultAttr)
    }
    val newAttributeGroup = new AttributeGroup($(outputCol), featureAttributes)
    newAttributeGroup.toStructField()
  }

  override def copy(extra: ParamMap): ChiSqSelectorModel = {
    val copied = new ChiSqSelectorModel(uid, chiSqSelector)
    copyValues(copied, extra).setParent(parent)
  }
}
