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

package org.apache.spark.examples.terasort

import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.rdd.ShuffledRDD
import org.apache.spark.util.Utils

object TeraSort {

  implicit val caseInsensitiveOrdering = UnsignedBytes.lexicographicalComparator

class RecordWrapper(val bytes: Array[Byte]) extends Product2[Array[Byte], Array[Byte]] {
  override def _1 = bytes
  override def _2 = bytes
  override def canEqual(that: Any): Boolean = ???
}


object TeraSort {

  def main(args: Array[String]) {

    if (args.length < 2) {
      println("usage:")
      println("DRIVER_MEMORY=[mem] bin/run-example org.apache.spark.examples.terasort.TeraSort " +
        "[input-size] [input-parts] [output-parts]")
      println(" ")
      println("example:")
      println("DRIVER_MEMORY=50g bin/run-example terasort.TeraSort " +
        "/home/myuser/terasort_in /home/myuser/terasort_out")
    }

    // Process command line arguments
    val master = sys.env.getOrElse("MASTER", "local")
    val inputSize = Utils.memoryStringToMb(args(0)).toLong * 1024 * 1024 // in bytes
    val parts = args(1).toInt
    val recordsPerPartition = inputSize / 100 / parts.toLong
    val outputParts = args(2).toInt
    val numRecords = recordsPerPartition * parts.toLong

    println("===========================================================================")
    println("===========================================================================")
    println(s"Input size: ${Utils.bytesToString(inputSize)}")
    println(s"Total number of records: $numRecords")
    println(s"Number of input partitions: $parts")
    println(s"Number of output partitions: $outputParts")
    println("Number of records/input partition: " + (numRecords / parts))
    println("Number of records/output partition: " + (numRecords / outputParts))
    println("===========================================================================")
    println("===========================================================================")

    assert(recordsPerPartition < Int.MaxValue, s"records per partition > ${Int.MaxValue}")

    val conf = new SparkConf().setMaster(master).setAppName(s"TeraSort ($numRecords records)")
    val sc = new SparkContext(conf)

    // Generate the data on the fly.
    val dataset = sc.parallelize(1 to parts, parts).mapPartitionsWithIndex { case (index, _) =>
      val one = new Unsigned16(1)
      val firstRecordNumber = new Unsigned16(index.toLong * recordsPerPartition.toLong)
      val recordsToGenerate = new Unsigned16(recordsPerPartition)

      val recordNumber = new Unsigned16(firstRecordNumber)
      val lastRecordNumber = new Unsigned16(firstRecordNumber)
      lastRecordNumber.add(recordsToGenerate)

      val rand = Random16.skipAhead(firstRecordNumber)
      val row: Array[Byte] = new Array[Byte](100)

      Iterator.tabulate(recordsPerPartition.toInt) { offset =>
        Random16.nextRand(rand)
        generateRecord(row, rand, recordNumber)
        recordNumber.add(one)
        new RecordWrapper(row)
      }
    }

    val partitioner = new TeraSortPartitioner(outputParts)
    val output =
      new ShuffledRDD[Array[Byte], Array[Byte], Array[Byte], RecordWrapper](dataset, partitioner)
    output.setSerializer(new TeraSortSerializer)

    println("Number of records after shuffling: " + output.count())

    // TODO: Add partition-local (external) sorting using TeraSortRecordOrdering.
  }

  /**
   * Generate a binary record suitable for all sort benchmarks except PennySort.
   *
   * @param recBuf record to return
   */
  def generateRecord(recBuf: Array[Byte], rand: Unsigned16, recordNumber: Unsigned16): Unit = {
    // Generate the 10-byte key using the high 10 bytes of the 128-bit random number
    var i = 0
    while (i < 10) {
      recBuf(i) = rand.getByte(i)
      i += 1
    }

    // Add 2 bytes of "break"
    recBuf(10) = 0x00.toByte
    recBuf(11) = 0x11.toByte

    // Convert the 128-bit record number to 32 bits of ascii hexadecimal
    // as the next 32 bytes of the record.
    i = 0
    while (i < 32) {
      recBuf(12 + i) = recordNumber.getHexDigit(i).toByte
      i += 1
    }

    // Add 4 bytes of "break" data
    recBuf(44) = 0x88.toByte
    recBuf(45) = 0x99.toByte
    recBuf(46) = 0xAA.toByte
    recBuf(47) = 0xBB.toByte

    // Add 48 bytes of filler based on low 48 bits of random number
    i = 0
    while (i < 12) {
      val v = rand.getHexDigit(20 + i).toByte
      recBuf(48 + i * 4) = v
      recBuf(49 + i * 4) = v
      recBuf(50 + i * 4) = v
      recBuf(51 + i * 4) = v
      i += 1
    }

    val dataset = sc.newAPIHadoopFile[Array[Byte], Array[Byte], TeraInputFormat](inputFile)
    val sorted = dataset.sortByKey()
    sorted.saveAsNewAPIHadoopFile[TeraOutputFormat](outputFile)
  }
}