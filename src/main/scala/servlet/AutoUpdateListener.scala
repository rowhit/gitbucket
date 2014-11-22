package servlet

import java.io.File
import java.sql.{DriverManager, Connection}
import org.apache.commons.io.FileUtils
import javax.servlet.{ServletContext, ServletContextListener, ServletContextEvent}
import org.apache.commons.io.IOUtils
import org.slf4j.LoggerFactory
import util.Directory._
import util.ControlUtil._
import org.eclipse.jgit.api.Git
import util.Directory
import plugin.PluginUpdateJob
import service.SystemSettingsService

object AutoUpdate {
  
  /**
   * Version of GitBucket
   * 
   * @param majorVersion the major version
   * @param minorVersion the minor version
   */
  case class Version(majorVersion: Int, minorVersion: Int){
    
    private val logger = LoggerFactory.getLogger(classOf[servlet.AutoUpdate.Version])
    
    /**
     * Execute update/MAJOR_MINOR.sql to update schema to this version.
     * If corresponding SQL file does not exist, this method do nothing.
     */
    def update(conn: Connection): Unit = {
      val sqlPath = s"update/${majorVersion}_${minorVersion}.sql"

      using(Thread.currentThread.getContextClassLoader.getResourceAsStream(sqlPath)){ in =>
        if(in != null){
          val sql = IOUtils.toString(in, "UTF-8")
          using(conn.createStatement()){ stmt =>
            logger.debug(sqlPath + "=" + sql)
            stmt.executeUpdate(sql)
          }
        }
      }
    }
    
    /**
     * MAJOR.MINOR
     */
    val versionString = s"${majorVersion}.${minorVersion}"
  }

  /**
   * The history of versions. A head of this sequence is the current BitBucket version.
   */
  val versions = Seq(
    new Version(2, 6),
    new Version(2, 5),
    new Version(2, 4),
    new Version(2, 3) {
      override def update(conn: Connection): Unit = {
        super.update(conn)
        using(conn.createStatement.executeQuery("SELECT ACTIVITY_ID, ADDITIONAL_INFO FROM ACTIVITY WHERE ACTIVITY_TYPE='push'")){ rs =>
          while(rs.next) {
            val info = rs.getString("ADDITIONAL_INFO")
            val newInfo = info.split("\n").filter(_ matches "^[0-9a-z]{40}:.*").mkString("\n")
            if (info != newInfo) {
              val id = rs.getString("ACTIVITY_ID")
              using(conn.prepareStatement("UPDATE ACTIVITY SET ADDITIONAL_INFO=? WHERE ACTIVITY_ID=?")) { sql =>
                sql.setString(1, newInfo)
                sql.setLong(2, id.toLong)
                sql.executeUpdate
              }
            }
          }
        }
        FileUtils.deleteDirectory(Directory.getPluginCacheDir())
        FileUtils.deleteDirectory(new File(Directory.PluginHome))
      }
    },
    new Version(2, 2),
    new Version(2, 1),
    new Version(2, 0){
      override def update(conn: Connection): Unit = {
        import eu.medsea.mimeutil.{MimeUtil2, MimeType}

        val mimeUtil = new MimeUtil2()
        mimeUtil.registerMimeDetector("eu.medsea.mimeutil.detector.MagicMimeMimeDetector")

        super.update(conn)
        using(conn.createStatement.executeQuery("SELECT USER_NAME, REPOSITORY_NAME FROM REPOSITORY")){ rs =>
          while(rs.next){
            defining(Directory.getAttachedDir(rs.getString("USER_NAME"), rs.getString("REPOSITORY_NAME"))){ dir =>
              if(dir.exists && dir.isDirectory){
                dir.listFiles.foreach { file =>
                  if(file.getName.indexOf('.') < 0){
                    val mimeType = MimeUtil2.getMostSpecificMimeType(mimeUtil.getMimeTypes(file, new MimeType("application/octet-stream"))).toString
                    if(mimeType.startsWith("image/")){
                      file.renameTo(new File(file.getParent, file.getName + "." + mimeType.split("/")(1)))
                    }
                  }
                }
              }
            }
          }
        }
      }
    },
    Version(1, 13),
    Version(1, 12),
    Version(1, 11),
    Version(1, 10),
    Version(1, 9),
    Version(1, 8),
    Version(1, 7),
    Version(1, 6),
    Version(1, 5),
    Version(1, 4),
    new Version(1, 3){
      override def update(conn: Connection): Unit = {
        super.update(conn)
        // Fix wiki repository configuration
        using(conn.createStatement.executeQuery("SELECT USER_NAME, REPOSITORY_NAME FROM REPOSITORY")){ rs =>
          while(rs.next){
            using(Git.open(getWikiRepositoryDir(rs.getString("USER_NAME"), rs.getString("REPOSITORY_NAME")))){ git =>
              defining(git.getRepository.getConfig){ config =>
                if(!config.getBoolean("http", "receivepack", false)){
                  config.setBoolean("http", null, "receivepack", true)
                  config.save
                }
              }
            }
          }
        }
      }
    },
    Version(1, 2),
    Version(1, 1),
    Version(1, 0),
    Version(0, 0)
  )
  
  /**
   * The head version of BitBucket.
   */
  val headVersion = versions.head
  
  /**
   * The version file (GITBUCKET_HOME/version).
   */
  lazy val versionFile = new File(GitBucketHome, "version")
  
  /**
   * Returns the current version from the version file.
   */
  def getCurrentVersion(): Version = {
    if(versionFile.exists){
      FileUtils.readFileToString(versionFile, "UTF-8").trim.split("\\.") match {
        case Array(majorVersion, minorVersion) => {
          versions.find { v => 
            v.majorVersion == majorVersion.toInt && v.minorVersion == minorVersion.toInt
          }.getOrElse(Version(0, 0))
        }
        case _ => Version(0, 0)
      }
    } else Version(0, 0)
  }
  
}

/**
 * Update database schema automatically in the context initializing.
 */
class AutoUpdateListener extends ServletContextListener {
  import org.quartz.impl.StdSchedulerFactory
  import AutoUpdate._

  private val logger = LoggerFactory.getLogger(classOf[AutoUpdateListener])
  private val scheduler = StdSchedulerFactory.getDefaultScheduler
  
  override def contextInitialized(event: ServletContextEvent): Unit = {
    val dataDir = event.getServletContext.getInitParameter("gitbucket.home")
    if(dataDir != null){
      System.setProperty("gitbucket.home", dataDir)
    }
    org.h2.Driver.load()

    val context = event.getServletContext
    context.setInitParameter("db.url", s"jdbc:h2:${DatabaseHome};MVCC=true")

    defining(getConnection(event.getServletContext)){ conn =>
      logger.debug("Start schema update")
      try {
        defining(getCurrentVersion()){ currentVersion =>
          if(currentVersion == headVersion){
            logger.debug("No update")
          } else if(!versions.contains(currentVersion)){
            logger.warn(s"Skip migration because ${currentVersion.versionString} is illegal version.")
          } else {
            versions.takeWhile(_ != currentVersion).reverse.foreach(_.update(conn))
            FileUtils.writeStringToFile(versionFile, headVersion.versionString, "UTF-8")
            logger.debug(s"Updated from ${currentVersion.versionString} to ${headVersion.versionString}")
          }
        }
      } catch {
        case ex: Throwable => {
          logger.error("Failed to schema update", ex)
          ex.printStackTrace()
          conn.rollback()
        }
      }
      logger.debug("End schema update")
    }

    if(SystemSettingsService.enablePluginSystem){
      getDatabase(context).withSession { implicit session =>
        logger.debug("Starting plugin system...")
        try {
          plugin.PluginSystem.init()

          scheduler.start()
          PluginUpdateJob.schedule(scheduler)
          logger.debug("PluginUpdateJob is started.")

          logger.debug("Plugin system is initialized.")
        } catch {
          case ex: Throwable => {
            logger.error("Failed to initialize plugin system", ex)
            ex.printStackTrace()
            throw ex
          }
        }
      }
    }
  }

  def contextDestroyed(sce: ServletContextEvent): Unit = {
    scheduler.shutdown()
  }

  private def getConnection(servletContext: ServletContext): Connection =
    DriverManager.getConnection(
      servletContext.getInitParameter("db.url"),
      servletContext.getInitParameter("db.user"),
      servletContext.getInitParameter("db.password"))

  private def getDatabase(servletContext: ServletContext): scala.slick.jdbc.JdbcBackend.Database =
    slick.jdbc.JdbcBackend.Database.forURL(
      servletContext.getInitParameter("db.url"),
      servletContext.getInitParameter("db.user"),
      servletContext.getInitParameter("db.password"))

}
