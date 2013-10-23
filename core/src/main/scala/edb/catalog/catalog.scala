package edb.catalog

import edb.parser._
import scala.{List=>MList,Iterator=>MIterator}
import scala.io._
import scala.reflect.BeanProperty
import scala.util.control.Breaks._
import scala.collection.mutable.{Map => MMap,Set=>MSet}
import scala.collection.JavaConversions._

import java.util._
import java.io.OutputStream
import org.apache.hadoop.fs._
import org.apache.hadoop.conf._
import org.apache.hadoop.io._
import org.apache.hadoop.util._

class Catalog (location: String) extends Serializable  { 

  val DEFAULT_LOCATION = "hdfs://localhost:9000/edb/catalog"
  val conf = new Configuration()
  conf.addResource(new Path("/server/hadoop/conf/core-site.xml"))
  val fs = FileSystem.get(conf)

  //init cache
  var catalogCache: Properties = null

  def initCache() {
    var loc = new Path(location)

    if (!fs.exists(loc)) {
      loc = new Path(DEFAULT_LOCATION) 
    }
    val cache = new Properties()
    cache.load(fs.open(loc))
    catalogCache =cache
  }
}

object Catalog extends Serializable {

  //create a table schema in catalog
  def createTable(metaTable: CREATE_TABLE_QB) {

    val name = metaTable.name
    val eItr: MIterator[TABLE_ELEMENT] = 
    metaTable.elementList.iterator 

    //table name should not be used
    if (getTableSchema (name) != null) {
      throw new 
      EdbException("table name exists in the catalog")
    }

    val newSchemaID = getSchemaIDs.toList.sorted.last + 1
    val iter = getSchemaIDs.toList.sorted.iterator

    var schIDs = ""
    while (iter.hasNext){
      schIDs = schIDs + iter.next + ","
    }

    schIDs = schIDs + newSchemaID
    catalog.catalogCache.setProperty("SchemaIDs", schIDs)

    //set name
    var key = newSchemaID + ".name"
    var value = name
    catalog.catalogCache.setProperty(key, value)

    //set version
    key = newSchemaID + ".latestVersion"
    value = "1"
    catalog.catalogCache.setProperty(key, value)

    //set storage 
    key = newSchemaID + ".hdfsStorage"
    value = "/edb/" + name + "/"
    catalog.catalogCache.setProperty(key, value)

    //set schema
    key = newSchemaID + ".1"
    value =""
    var default = ""

    while(eItr.hasNext){

      val ele = eItr.next
      val colName = ele.name
      val colType = ele.coltype

      if (eItr.hasNext) {
        value = value + colType + " " + colName+ "," 

        if (colType.toString.equals("string"))
          default = default + "Unknown" + ","
        else 
          default = default + "-1" + ","
      }
      else {
        value = value + colType + " " + colName 
        if (colType.toString.equals("string"))
          default = default + "Unknown" 
        else 
          default = default + "-1" 
      }
    }
    catalog.catalogCache.setProperty(key, value)

    //set default 
    key = newSchemaID + ".1.default"

    persistCatalog(catalog.catalogCache)
    //reset cache
    catalog.initCache

  }//end create table

  /**
    * persist catalog to a hdfs file
    */
  def persistCatalog(cache: Properties) {

    val DEFAULT_LOCATION = 
    "hdfs://localhost:9000/edb/catalog1"
    val CATALOG_LOCATION = 
    "hdfs://localhost:9000/edb/catalog"

    val conf = new Configuration()
    conf.addResource(
      new Path("/server/hadoop/conf/core-site.xml"))
    val fs = FileSystem.get(conf)
    val loc = new Path(DEFAULT_LOCATION)

    if (fs.exists(loc)){
      fs.delete(loc)
    }
    val out= fs.create(loc)
    // out.writeUTF(kvlist)
    val kvMap: scala.collection.mutable.Map[String, String]= cache

    val sortedKVs = kvMap.toSeq.sortBy(_._1)

    //add line break to bypass utf prefix, utf always have a len 
    //of string written first 
    var kvList: String = "\n"
    for (x <- sortedKVs) {
      kvList = kvList + x._1 + "="+ x._2 + "\n"
    }

    out.writeUTF(kvList)
    out.close()

    val catalogLoc = new Path(CATALOG_LOCATION)
    if (fs.exists(catalogLoc)){
      fs.delete(catalogLoc)
    }
    fs.rename(loc, catalogLoc)
  }

  /**
    * Display catalog table name list
    */
  def showTables(){

    println()
    println()
    println("EDB has " + getSchemaIDs().size + " tables:")
    println("-------------------------------------")
    println()
    getSchemaIDs() map  {
      i=> println("\n" +getTableName(i))
    }

    println()
    println("-------------------------------------")

    println()
    println()
  }

  def descTable(name: String){
    println()
    println()
    println("-------------------------------------")
    println()
    println(getTableSchema (name))
    println()
    println("-------------------------------------")
    println()
    println()
  }

  //private var catalog: Properties = (new Catalog("test")).loadCatalog()
  private var catalog = new Catalog("/test")
   catalog.initCache

  //sch is uniquely identified by id_version string
  //the colIdxMap is colName->colIdx
  private val sch2ColIdxMap = 
  MMap[String, MMap[String,Int]]()

  //create tableName->latestSchema map 
  private var nameToSchema = (getSchemaIDs() map 
    { i=> getTableName(i)-> new Schema(i, getTableLatestVersion(i).toByte)}).toMap

  /* return a set containing all schema IDs in the catalog */
  def getSchemaIDs(): MSet[Int] = {
    val schemaIDs = MSet.empty[Int] 
    val idList = catalog.catalogCache.getProperty("SchemaIDs", "-1");
    val ids: Array[String] = idList.split(",");

    for (id <-ids)
      schemaIDs += Integer.parseInt(id.trim()) 

    if (schemaIDs.size == 1 && schemaIDs.contains(-1)) {
      Console.err.println("cannot find schema ids")
      exit(100)
    }
    schemaIDs
  }


  def getTableName(id: Int):String = {
    catalog.catalogCache.getProperty(id+".name") 
  }

  def getTableLatestVersion(id: Int):Int = {
    (catalog.catalogCache.getProperty(id+".latestVersion")).toInt
  }

  def getTableLocation(id: Int):String = {
    catalog.catalogCache.getProperty(id+".hdfsStorage") 
  }

  def getTableLocation(tableName: String):String = {
    val id = getTableSchema(tableName).getId
    val loc = catalog.catalogCache.getProperty(id+".hdfsStorage") 
    loc
  }



  def getTableSchema (id: Int, version: Byte):String= {
    catalog.catalogCache.getProperty(id+"." + version) 
  }

  def getTableSchema (name: String): Schema = 
  if (nameToSchema contains (name)) nameToSchema(name)
    else null

  /**
    * For the current record, we use identifier 
    * to find the value of the column
    * @param t SequenceRecord
    * @param iden column name
    * @return the edb value for the column
    *
    */
  def getVal(
    t: SequenceRecord, 
    iden: String,
    inSch: Schema): genericValue= { 

    //val sch: Schema = t.getSch()
    val sch = inSch
    assert(sch != null)



    val schKey: String = sch.getId + "_" + sch.getVersion
    var colName2IdxMap: MMap[String,Int] =
    sch2ColIdxMap.get(schKey) match {
      case Some(x)=> x
      case None => null
    }

    //first time, build colName->idx map
    if (colName2IdxMap == null){
      //loop through schema atts,build map
      colName2IdxMap = MMap[String,Int]()

      var i =0;
      for(att<- sch.getAtts()){
        colName2IdxMap += att.getName()->i
        i += 1
      }
      sch2ColIdxMap += schKey->colName2IdxMap
    }

    val vals: Array[genericValue]= t.getData()


    //for group by count(*) agg function, 
    //we want to sent 1 to reducer
    if (iden == "*")
      new IntVal("",1)
    else 
      colName2IdxMap.get(iden) match {
      case Some(x)=> vals(x)
      case None => {
        Console.err.println("att was not found for "+ 
          iden + " in "+ sch.toString)
        throw new EdbException("not supported")
      }
    }
  }

  /**
    * For the current record, we use identifier 
    * to find the col type
    * @param t SequenceRecord
    * @param iden column name
    * @return the edb type for the column
    *
    */
  def getAttType(
    iden: String,
    inSch: Schema): Attribute = { 

    //val sch: Schema = t.getSch()
    val sch = inSch
    assert(sch != null)


    val schKey: String = sch.getId + "_" + sch.getVersion
    var colName2IdxMap: MMap[String,Int] =
    sch2ColIdxMap.get(schKey) match {
      case Some(x)=> x
      case None => null
    }

    //first time, build colName->idx map
    if (colName2IdxMap == null){
      //loop through schema atts,build map
      colName2IdxMap = MMap[String,Int]()

      var i =0;
      for(att<- sch.getAtts()){
        colName2IdxMap += att.getName()->i
        i += 1
      }
      sch2ColIdxMap += schKey->colName2IdxMap
    }

    val atts: Array[Attribute]= inSch.getAtts 

    colName2IdxMap.get(iden) match {
      case Some(x)=> atts(x)
      case None => {
        Console.err.println("att was not found for "+ 
          iden + " in2 "+ sch.toString)
        throw new EdbException("not supported")


      }
    }
  }
  def getAttIdx(
    iden: String,
    inSch: Schema): Int = { 

    assert(inSch != null)
    val sch = inSch

    val schKey: String = sch.getId + "_" + sch.getVersion
    var colName2IdxMap: MMap[String,Int] =
    sch2ColIdxMap.get(schKey) match {
      case Some(x)=> x
      case None => null
    }

    //first time, build colName->idx map
    if (colName2IdxMap == null){
      //loop through schema atts,build map
      colName2IdxMap = MMap[String,Int]()

      var i =0;
      for(att<- sch.getAtts()){
        colName2IdxMap += att.getName()->i
        i += 1
      }
      sch2ColIdxMap += schKey->colName2IdxMap
    }

    val atts: Array[Attribute]= inSch.getAtts 

    colName2IdxMap.get(iden) match {
      case Some(x)=> x
      case None => {
        Console.err.println("att was not found for "+ 
          iden + " in2 "+ sch.toString)
        throw new EdbException("not supported")
      }
    }
  }


}



