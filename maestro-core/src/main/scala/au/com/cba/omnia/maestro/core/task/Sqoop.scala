package au.com.cba.omnia.maestro.core.task

import java.io.File

import cascading.flow.FlowDef
import cascading.tap.Tap

import com.twitter.scalding._, TDsl._, Dsl._

import org.apache.log4j.Logger
import org.joda.time.{DateTime, DateTimeZone}

import au.com.cba.omnia.parlour.{ExportSqoopJob, ImportSqoopJob, ParlourExportOptions, ParlourImportOptions}
import au.com.cba.omnia.parlour.SqoopSyntax.{ParlourExportDsl, ParlourImportDsl}

/**
  * Import and export data between a database and HDFS.
  * 
  * Methods return a Job that can be added to a cascade.
  * 
  * See the example at `au.com.cba.omnia.maestro.example.CustomerSqoopExample`.
  * 
  *
  */
trait Sqoop {

  /**
   * For a given `domain` and `tableName`, [[ImportPath]] creates a standard
   * HDFS location: `\$hdfsRoot/source/\$source/\$domain/\$tableName/<year>/<month>/<day>`.
   *
   * Year, month and day are derived from the current date.
   *
   * @param hdfsRoot
   * @param source
   * @param domain
   * @param tableName
   */
  case class ImportPath(hdfsRoot: String, source: String, domain: String, tableName: String) {
    def path = {
      val date = DateTime.now(DateTimeZone.UTC).toString(List("yyyy","MM", "dd") mkString File.separator)
      List(hdfsRoot, "source", source, domain, tableName, date) mkString File.separator
    }
  }

  /**
   * Run a sqoop import using parlour import options
   *
   * <b>Use this method ONLY if non-standard settings are required</b>
   * 
   * @param options: Parlor import options
   */
  def sqoopImport(
    nextJob: Job, 
    options: ParlourImportOptions[_]
  )(args: Args)(implicit flowDef: FlowDef, mode: Mode): Job = {
    val logger = Logger.getLogger("Sqoop")
    val sqoopOptions = options.toSqoopOptions
    logger.info("Start of sqoop import")
    logger.info(s"connectionString = ${sqoopOptions.getConnectString}")
    logger.info(s"tableName        = ${sqoopOptions.getTableName}")
    logger.info(s"targetDir        = ${sqoopOptions.getTargetDir}")
    val sink:Tap[_,_,_] = nextJob.buildFlow.getSourcesCollection().iterator().next()
    new ImportSqoopJob(sqoopOptions, sink)(args)
  }

  /**
    * Runs a sqoop import from a database to HDFS.
    *
    * Data will be copied to the provided [[ImportPath]]
    * 
    * @param importPath: Path to import the data to
    * @param tableName: Table name or file name in database or project
    * @param connectionString: Jdbc url for connecting to the database
    * @param username: Username for connecting to the database
    * @param password: Password for connecting to the database
    * @param outputFieldsTerminatedBy: Marker to terminate output fields with
    * @param options: Extra import options 
    * @return Job for this import
    */
  def sqoopImport[T <: ParlourImportOptions[T]](
    nextJob: Job,
    importPath: ImportPath,
    tableName: String,
    connectionString: String,
    username: String,
    password: String,
    outputFieldsTerminatedBy: Char,
    options: ParlourImportOptions[T] = ParlourImportDsl()
  )(args: Args)(implicit flowDef: FlowDef, mode: Mode): Job = {
    options.connectionString(connectionString)
      .username(username)
      .password(password)
      .tableName(tableName)
      .targetDir(importPath.path)
      .fieldsTerminatedBy(outputFieldsTerminatedBy)
    sqoopImport(nextJob, options)(args)
  }

  /**
    * Runs a sqoop export from HDFS to a database.
    *
    * @param options: Custom export options 
    * @return Job for this export
    */
  def sqoopExport[T <: ParlourExportOptions[T]](
    previousJob: Job,
    options: ParlourExportOptions[T]
  )(args: Args)(implicit flowDef: FlowDef, mode: Mode): Job = {
    val source: Tap[_,_,_] = previousJob.buildFlow.getSinksCollection.iterator().next()
    new ExportSqoopJob(options.toSqoopOptions, source)(args)
  }

  /**
    * Runs a sqoop export from HDFS to a database.
    *
    * @param exportDir: Directory containing data to be exported
    * @param tableName: Table name or file name in database or project
    * @param connectionString: Jdbc url for connecting to the database
    * @param username: Username for connecting to the database
    * @param password: Password for connecting to the database
    * @param options: Extra export options 
    * @return List of jobs for this export
    */
  def sqoopExport[T <: ParlourExportOptions[T]](
    previousJob: Job,
    exportDir: String,
    tableName: String,
    connectionString: String,
    username: String,
    password: String,
    inputFieldsTerminatedBy: Char,
    options: ParlourExportOptions[T] = ParlourExportDsl()
  )(args: Args)(implicit flowDef: FlowDef, mode: Mode): Job = {
    options.exportDir(exportDir)
      .tableName(tableName)
      .connectionString(connectionString)
      .username(username)
      .password(password)
      .inputFieldsTerminatedBy(inputFieldsTerminatedBy)
    sqoopExport(previousJob, options)(args)
  }
}
