package edb.engine

import edb.parser._
import edb.catalog._
import spark.SparkContext
import SparkContext._
import spark.{RDD}

import scala.reflect.BeanProperty
import org.apache.hadoop.io._
import org.apache.hadoop.conf._
import org.apache.hadoop.mapred._
import org.apache.hadoop.fs._
import org.apache.hadoop.util._

object SimpleJob {

  /**
    * A driver to test DBFile
    */
  def run(): 
  Array[(edb.catalog.EdbIntWritable, 
    edb.catalog.SequenceRecord)] = {

    val file = new DBFile()
    val sch = new Schema(5,1)
    val sc =  new SparkContext("local", "Simple Job", "/server/spark",
      List("/Users/mwu/edb/core/target/scala-2.9.3/simple-project_2.9.3-1.0.jar"))

    file.init("hdfs://localhost:9000/edb/file1", sch, new SequenceDBFile(), sc)
    file.link(true)
    file.getRdd.collect()
  }

}



