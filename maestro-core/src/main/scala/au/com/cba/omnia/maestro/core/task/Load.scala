//   Copyright 2014 Commonwealth Bank of Australia
//
//   Licensed under the Apache License, Version 2.0 (the "License");
//   you may not use this file except in compliance with the License.
//   You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
//   Unless required by applicable law or agreed to in writing, software
//   distributed under the License is distributed on an "AS IS" BASIS,
//   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//   See the License for the specific language governing permissions and
//   limitations under the License.

package au.com.cba.omnia.maestro.core
package task

import java.nio.ByteBuffer
import java.security.{MessageDigest, SecureRandom}

import scala.util.hashing.MurmurHash3

import scalaz.{Tag => _, _}, Scalaz._

import cascading.flow.{FlowDef, FlowProcess}
import cascading.operation._
import cascading.tuple.Tuple

import com.twitter.scalding._, TDsl._, Dsl._

import com.twitter.scrooge.ThriftStruct

import au.com.cba.omnia.maestro.core.codec._
import au.com.cba.omnia.maestro.core.clean.Clean
import au.com.cba.omnia.maestro.core.filter.RowFilter
import au.com.cba.omnia.maestro.core.scalding.Errors
import au.com.cba.omnia.maestro.core.split.Splitter
import au.com.cba.omnia.maestro.core.validate.Validator

sealed trait TimeSource {
  def getTime(path: String): String = this match {
    case Predetermined(time) => time
    case FromPath(extract)  => extract(path)
  }
}

case class Predetermined(time: String) extends TimeSource
case class FromPath(extract: String => String) extends TimeSource

case class RawRow(line: String, extraFields: List[String])

trait Load {
  /**
    * Loads the supplied text files and converts them to the specified thrift struct.

    * The operations performed are:
    *  1. Append a time field to each line using the provided time source.
    *  1. Split each line into columns/fields using the provided delimiter.
    *  1. Apply the provided filter to each list of fields.
    *  1. Clean each field using the provided cleaner.
    *  1. Convert the list of fields into the provided thrift struct.
    *  1. Validate each struct.
    */
  def load[A <: ThriftStruct : Decode : Tag : Manifest]
    (delimiter: String, sources: List[String], errors: String, timeSource: TimeSource, clean: Clean,
      validator: Validator[A], filter: RowFilter)
    (implicit flowDef: FlowDef, mode: Mode): TypedPipe[A] =
    loadProcess(
      sources
        .map(p => TextLine(p).map(l => (p, l)))
        .reduceLeft(_ ++ _)
        .map { case (p, l) => RawRow(l, List(timeSource.getTime(p))) },
      Splitter.delimited(delimiter),
      errors,
      clean,
      validator,
      filter
    )

  /**
    * Same as `load` but also appends a unique key to each line. The last field of `A`
    * needs to be set aside to receive the key as type string.
    *
    * The key is generated by:
    *  1. Creating a random 4 byte seed for each load
    *  1. For each file hashing the seed and the path and then taking the last 8 bytes.
    *  1. Hashing each line and taking 12 bytes, taking the map task number (current slice number) and the offset into the file.
    *  1. Concatenating the file hash from 2, the slice number, byte offset and line hash
    *
    * This produces a 256 bit key.
    */
  def loadWithKey[A <: ThriftStruct : Decode : Tag : Manifest]
    (delimiter: String, sources: List[String], errors: String, timeSource: TimeSource, clean: Clean,
      validator: Validator[A], filter: RowFilter)
    (implicit flowDef: FlowDef, mode: Mode): TypedPipe[A] = {
    val d    = delimiter
    val rnd  = new SecureRandom()
    val seed = rnd.generateSeed(4)
    val md = MessageDigest.getInstance("SHA-1")
    val hashes =
      sources
        .map(k => k -> md.digest(seed ++ k.getBytes("UTF-8")).drop(12).map("%02x".format(_)).mkString)
        .toMap

    loadProcess(
      sources.map(p =>
        TextLine(p)
          .read
          .each(('offset, 'line), 'result)(_ => new GenerateKey(delimiter, p, timeSource, hashes))
          .toTypedPipe[RawRow]('result)
      ).reduceLeft(_ ++ _),
      Splitter.delimited(delimiter),
      errors,
      clean,
      validator,
      filter
    )
  }

  /**
    *  Same as `load` but uses a list of column lengths to split the string
    *   rather than a delimeter.
    */
  def loadFixedLength[A <: ThriftStruct : Decode : Tag : Manifest]
    (lengths: List[Int], sources: List[String], errors: String, timeSource: TimeSource,
      clean: Clean, validator: Validator[A], filter: RowFilter)
    (implicit flowDef: FlowDef, mode: Mode): TypedPipe[A] =
    loadProcess(
      sources
        .map(p => TextLine(p).map(l => (p, l)))
        .reduceLeft(_ ++ _)
        .map { case (p, l) => RawRow(l, List(timeSource.getTime(p))) },
      Splitter.fixed(lengths),
      errors,
      clean,
      validator,
      filter
    )

  def loadProcess[A <: ThriftStruct : Decode : Tag : Manifest]
    (in: TypedPipe[RawRow], splitter: Splitter, errors: String, clean: Clean,
       validator: Validator[A], filter: RowFilter)
    (implicit flowDef: FlowDef, mode: Mode): TypedPipe[A] = {
    val pipe =
      in
        .map(row => splitter.run(row.line) ++ row.extraFields)
        .flatMap(filter.run(_).toList)
        .map(record =>
          Tag.tag[A](record).map { case (column, field) => clean.run(field, column) }
        ).map(record => Decode.decode[A](UnknownDecodeSource(record)))
        .map {
          case DecodeOk(value) =>
            validator.run(value).disjunction.leftMap(errors => s"""The following errors occured: ${errors.toList.mkString(",")}""")
          case e @ DecodeError(remainder, counter, reason) =>
            reason match {
              case ValTypeMismatch(value, expected) =>
                s"unexpected type: $e".left
              case ParseError(value, expected, error) =>
                s"unexpected type: $e".left
              case NotEnoughInput(required, expected) =>
                s"not enough fields in record: $e".left
              case TooMuchInput =>
                s"too many fields in record: $e".left
            }
      }

    Errors.safely(errors)(pipe)
  }
}

class GenerateKey(delimiter: String, path: String, timeSource: TimeSource, hashes: Map[String, String])
    extends BaseOperation[(ByteBuffer, MessageDigest, Tuple)](('result)) with Function[(ByteBuffer, MessageDigest, Tuple)] {
  val d = delimiter

  def uid(path: String, slice: Int, offset: Long, line: String, byteBuffer: ByteBuffer, md: MessageDigest): String = {
    val hash = hashes(path)
    val lineHash = md.digest(line.getBytes("UTF-8")).drop(8)

    byteBuffer.clear

    val lineInfo  = byteBuffer.putInt(slice).putLong(offset).array

    val hex = (lineInfo ++ lineHash).map("%02x".format(_)).mkString

    s"$hash$hex"
  }

  override def prepare(flow: FlowProcess[_], call: OperationCall[(ByteBuffer, MessageDigest, Tuple)]): Unit = {
    val md = MessageDigest.getInstance("SHA-1")
    call.setContext((ByteBuffer.allocate(12), md, Tuple.size(2)));
  }

  def operate(flow: FlowProcess[_], call: FunctionCall[(ByteBuffer, MessageDigest, Tuple)]): Unit = {
    val entry  = call.getArguments
    val offset = entry.getLong(0)
    val line   = entry.getString(1)
    val slice  = flow.getCurrentSliceNum

    val (byteBuffer, md, resultTuple) = call.getContext
    val time = timeSource.getTime(path)
    val key = uid(path, slice, offset, line, byteBuffer, md)

    // representation of RawRow: tuple with string and List elements
    resultTuple.set(0, line)
    resultTuple.set(1, List(time, key))
    call.getOutputCollector.add(resultTuple)
  }
}
