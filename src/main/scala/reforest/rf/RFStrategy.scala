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

package reforest.rf

import org.apache.commons.math3.distribution.PoissonDistribution
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.rdd.RDD
import reforest.TypeInfo
import reforest.data._
import reforest.rf.rotation.RFRotationMatrix
import reforest.rf.split._
import reforest.util.{GCInstrumented, MemoryUtil}

import scala.reflect.ClassTag
import scala.util.Random

abstract class RFStrategy[T, U](strategyFeature: RFStrategyFeature) extends Serializable {
  var sampleSize: Option[Long] = Option.empty

  def getSampleSize: Long = {
    assert(sampleSize.isDefined)
    sampleSize.get
  }

  def generateBagging(size: Int, distribution: PoissonDistribution): Array[Byte]

  def getStrategyFeature(): RFStrategyFeature = strategyFeature

  def findSplits(input: RDD[RawDataLabeled[T, U]],
                 typeInfo: Broadcast[TypeInfo[T]],
                 typeInfoWorking: Broadcast[TypeInfo[U]],
                 instrumented: Broadcast[GCInstrumented],
                 categoricalFeatureInfo: Broadcast[RFCategoryInfo]): (RFSplitterManager[T, U], MemoryUtil)

  def prepareData(numTrees: Int,
                  macroIteration: Int,
                  splitterManager: RFSplitterManager[T, U],
                  partitionIndex: Int,
                  instances: Iterator[RawDataLabeled[T, U]],
                  instrumented: GCInstrumented,
                  memoryUtil: MemoryUtil): Iterator[StaticData[U]]

  def findSplitSampleInput(property: RFProperty,
                           input: RDD[RawDataLabeled[T, U]],
                           instrumented: Broadcast[GCInstrumented]) = {
    sampleSize = Some(input.count)
    println("SAMPLE SIZE: " + sampleSize.get)

    val memoryUtil = new MemoryUtil(getSampleSize, property)

    instrumented.value.gcALL
    val requiredSamples = math.min(math.max(property.binNumber * property.binNumber, 10000), getSampleSize)
    val fraction = requiredSamples.toDouble / getSampleSize
    val sampledInput = input.sample(withReplacement = false, fraction)
    instrumented.value.gcALL

    (memoryUtil, sampledInput)
  }
}

class RFStrategyStandard[T: ClassTag, U: ClassTag](property: RFProperty, strategyFeature: RFStrategyFeature) extends RFStrategy[T, U](strategyFeature) {
  def generateBagging(size: Int, distribution: PoissonDistribution) = {
    val toReturn = new Array[Byte](size)
    var i = 0
    while (i < toReturn.length) {
      toReturn(i) = distribution.sample().toByte
      i += 1
    }
    toReturn
  }

  def findSplits(input: RDD[RawDataLabeled[T, U]],
                 typeInfo: Broadcast[TypeInfo[T]],
                 typeInfoWorking: Broadcast[TypeInfo[U]],
                 instrumented: Broadcast[GCInstrumented],
                 categoricalFeatureInfo: Broadcast[RFCategoryInfo]): (RFSplitterManager[T, U], MemoryUtil) = {
    val (memoryUtil, sampledInput) = findSplitSampleInput(property, input, instrumented)

    (new RFSplitterManagerSingle[T, U](property.strategySplit.findSplitsSimple(sampledInput, property.binNumber, property.featureNumber, memoryUtil.maximumConcurrentNumberOfFeature, typeInfo, typeInfoWorking, instrumented, categoricalFeatureInfo)), memoryUtil)
  }

  def prepareData(numTrees: Int,
                  macroIteration: Int,
                  splitterManager: RFSplitterManager[T, U],
                  partitionIndex: Int,
                  instances: Iterator[RawDataLabeled[T, U]],
                  instrumented: GCInstrumented,
                  memoryUtil: MemoryUtil): Iterator[StaticData[U]] = {
    val poisson = new PoissonDistribution(property.poissonMean)
    poisson.reseedRandomGenerator(0 + partitionIndex + 1)

    instances.map { instance =>
      val sampleArray = generateBagging(numTrees, poisson)
      instance.features match {
        case v: RawDataSparse[T, U] => {
          if (((property.permitSparseWorkingData && (v.indices.size + v.indices.size * 4) < v.size) || property.featureNumber > memoryUtil.maximumConcurrentNumberOfFeature) && (v.indices.size + v.indices.size * 4) < v.size) {
            new StaticDataClassic[U](instance.label.toByte, v.toWorkingDataSparse(splitterManager.getSplitter(macroIteration)), sampleArray)
          } else {
            new StaticDataClassic[U](instance.label.toByte, v.toWorkingDataDense(splitterManager.getSplitter(macroIteration)), sampleArray)
          }
        }
        case v: RawDataDense[T, U] => {
          new StaticDataClassic[U](instance.label.toByte, v.toWorkingDataDense(splitterManager.getSplitter(macroIteration)), sampleArray)
        }
        case _ => throw new ClassCastException
      }
    }
  }
}

class RFStrategyRotation[T: ClassTag, U: ClassTag](property: RFProperty, strategyFeature: RFStrategyFeature, rotationMatrix: Broadcast[Array[RFRotationMatrix[T, U]]]) extends RFStrategy[T, U](strategyFeature) {

  def generateBagging(size: Int, distribution: PoissonDistribution) = {
    Array.tabulate(size)(i => 1.toByte)
  }

  def findSplits(input: RDD[RawDataLabeled[T, U]],
                 typeInfo: Broadcast[TypeInfo[T]],
                 typeInfoWorking: Broadcast[TypeInfo[U]],
                 instrumented: Broadcast[GCInstrumented],
                 categoricalFeatureInfo: Broadcast[RFCategoryInfo]): (RFSplitterManager[T, U], MemoryUtil) = {
    val (memoryUtil, sampledInput) = findSplitSampleInput(property, input, instrumented)

    val splitterArray = new Array[RFSplitter[T, U]](rotationMatrix.value.length)
    var count = 0

    while (count < splitterArray.length) {
      splitterArray(count) = property.strategySplit.findSplitsSimple(sampledInput.map(t => rotationMatrix.value(count).rotate(t)), property.binNumber, property.featureNumber, memoryUtil.maximumConcurrentNumberOfFeature, typeInfo, typeInfoWorking, instrumented, categoricalFeatureInfo)
      count += 1
    }

    (new RFSplitterManagerCollection[T, U](splitterArray, property.binNumber, property.numTrees, splitterArray.length, categoricalFeatureInfo.value), memoryUtil)
  }

  def prepareData(numTrees: Int,
                  macroIteration: Int,
                  splitterManager: RFSplitterManager[T, U],
                  partitionIndex: Int,
                  instances: Iterator[RawDataLabeled[T, U]],
                  instrumented: GCInstrumented,
                  memoryUtil: MemoryUtil): Iterator[StaticData[U]] = {
    instances.map { instance =>
      instance.features match {
        case v: RawData[T, U] => {
          new StaticDataRotationSingle[U](instance.label.toByte, rotationMatrix.value(macroIteration).rotateRawData(v).toWorkingDataDense(splitterManager.getSplitter(macroIteration)))
        }
        case _ => throw new ClassCastException
      }
    }
  }
}
