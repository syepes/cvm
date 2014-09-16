/**
 *
 * @author Sebastian YEPES FERNANDEZ (syepes@gmail.com)
 */

package com.allthingsmonitoring.utils

import org.slf4j.*
import groovy.util.logging.Slf4j
import ch.qos.logback.classic.*
import static ch.qos.logback.classic.Level.*
import org.codehaus.groovy.runtime.StackTraceUtils
import groovy.time.*
import groovy.json.JsonSlurper
import static groovy.json.JsonParserType.*

import groovyx.gpars.GParsPool
import groovyx.gpars.util.PoolUtils

import groovy.util.slurpersupport.NodeChild
import groovy.util.slurpersupport.NodeChildren
import wslite.soap.*
import wslite.http.auth.*

import java.util.zip.*
import java.util.jar.Manifest
import java.util.jar.Attributes
import java.util.regex.Pattern
import java.text.SimpleDateFormat
import java.net.URLEncoder

import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.Files
import static java.nio.file.StandardCopyOption.*

import com.jcraft.jsch.*
import net.sf.expectit.*
import net.sf.expectit.echo.EchoOutput
import net.sf.expectit.filter.Filter
import static net.sf.expectit.filter.Filters.chain
import static net.sf.expectit.filter.Filters.removeColors
import static net.sf.expectit.filter.Filters.removeNonPrintable
import net.sf.expectit.matcher.Matcher
import static net.sf.expectit.matcher.Matchers.allOf
import static net.sf.expectit.matcher.Matchers.anyOf
import static net.sf.expectit.matcher.Matchers.anyString
import static net.sf.expectit.matcher.Matchers.contains
import static net.sf.expectit.matcher.Matchers.regexp
import static net.sf.expectit.matcher.Matchers.eof

import org.eclipse.jgit.api.*
import org.eclipse.jgit.lib.*
import org.eclipse.jgit.errors.*
import org.eclipse.jgit.dircache.*

import com.github.fge.jsonschema.main.JsonSchemaFactory
import com.github.fge.jsonschema.core.report.ProcessingReport
import com.github.fge.jsonschema.core.report.ProcessingMessage
import com.github.fge.jackson.JsonLoader
import com.github.fge.jsonschema.main.JsonSchema

import com.allthingsmonitoring.utils.cvm.DebugSend
import com.allthingsmonitoring.utils.cvm.DebugReceive


@Slf4j
class CVM {

  ConfigObject cfg

  Path gitRepo
  String authProfilePath
  String deviceProfilePath
  String deviceSource

  HashMap devProfiles = [:]
  ArrayList authProfiles = []

  Pattern PAT_IP = ~/(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)(\.(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)){3}/
  String PROFILE_SCHEMA = '/com/allthingsmonitoring/utils/cvm/profile_schema.json'


  CVM(String cfgFile='config.groovy') {
    cfg = readConfigFile(cfgFile)

    gitRepo = cfg?.git?.repo ? Paths.get(cfg?.git?.repo) : Paths.get('repository')
    authProfilePath = cfg?.authProfilePath ?: 'authProfiles.groovy'
    deviceProfilePath = cfg?.deviceProfilePath ?: 'profiles'
    deviceSource = cfg?.deviceSource?.src ?: 'file'

    Attributes manifest = getManifestInfo()
    log.info "Initialization ${this.class.name} / Version: ${manifest?.getValue('Specification-Version')} / Built-Date: ${manifest?.getValue('Built-Date')}"
  }



  /**
   * Load configuration settings
   *
   * @param cfgFile Configuration file path
   * @return ConfigObject with the configuration elements
   */
  ConfigObject readConfigFile(String cfgFile) {
    try {
      ConfigObject cfg = new ConfigSlurper().parse(new File(cfgFile).toURL())
      if (cfg) {
        log.trace "The configuration files: ${cfgFile} was read correctly"
        return cfg
      } else {
        log.error "Verify the content of the configuration file: ${cfgFile}"
        throw new RuntimeException("Verify the content of the configuration file: ${cfgFile}")
      }
    } catch(FileNotFoundException e) {
      log.error "The configuration file: ${cfgFile} was not found"
      throw new RuntimeException("The configuration file: ${cfgFile} was not found")
    } catch(Exception e) {
      StackTraceUtils.deepSanitize(e)
      log.error "Configuration file exception: ${e?.message}"
      log.debug "Configuration file exception: ${getStackTrace(e)}"
      throw new RuntimeException("Configuration file exception:\n${getStackTrace(e)}")
    }
  }


  /**
   * Retrieves the Manifest Info from the JAR file
   *
   * @return JAR MainAttributes
   */
  Attributes getManifestInfo() {
    Class clazz = this.getClass()
    String className = clazz.getSimpleName() + ".class"
    String classPath = clazz.getResource(className).toString()
    // Class not from JAR
    if (!classPath.startsWith("jar")) { return null }

    String manifestPath = classPath.substring(0, classPath.lastIndexOf('!') + 1) + "/META-INF/MANIFEST.MF"
      Manifest manifest
      try {
        manifest = new Manifest(new URL(manifestPath).openStream())
      } catch(Exception e) {
        StackTraceUtils.deepSanitize(e)
        log.warn "Manifest: ${e?.message}"
        log.debug "Manifest: ${getStackTrace(e)}"
      }

    return manifest.getMainAttributes()
  }

  // Gets the StackTrace and returns a string
  String getStackTrace(Throwable t) {
    StringWriter sw = new StringWriter()
    PrintWriter pw = new PrintWriter(sw, true)
    t.printStackTrace(pw)
    pw.flush()
    sw.flush()
    return sw.toString()
  }

  /**
   * ExpectIt Debuging
   *
   * @param r Expect Result
   */
  void eitDebug (Result r) {
    log.trace "eit.result: ${r.isSuccessful()}"
    if (r instanceof MultiResult) {
      for (int i = 0; i < r.getResults().size(); i++) {
        if (r.getResults().getAt(i).isSuccessful()) {
          log.trace "eit.result(${i}): ${r.getResults().getAt(i).isSuccessful()} (${r.getResults().getAt(i).group()})"
        } else { log.trace "eit.result(${i}): ${r.getResults().getAt(i).isSuccessful()}" }
      }
    }
    for (int i = 0; i <= r.groupCount(); i++) { log.trace "eit.match(${i}): ${r.group(i)}" }
    log.trace "eit.fullOutput: ${r.getBefore()}"
  }

  /**
   * Setup Logger for the SSH
   *
   */
  static class jschLogger implements com.jcraft.jsch.Logger {
    private ArrayList sev = ['debug','info','warn','error','error']

    @Override
    public boolean isEnabled(int level) { return true }

    @Override
    public void log(int level, String message) {
      if (level == 1 || level == 2) {
        log.trace message
      } else {
        log.(sev[level]) message
      }
    }
  }


  /**
   * Establish device shell connection
   *
   * @param dprofile Device Profile
   * @return connection channel, session and expect
   */
  HashMap deviceShellOpen(HashMap dprofile) {
    HashMap con = [:]
    if (!dprofile) { return con }

    String host = dprofile?.auth?.host
    int port = dprofile?.auth?.port?.toInteger() ?: 22
    String user = dprofile?.auth?.user
    String password = dprofile?.auth?.password
    int sshTimeout = dprofile?.auth?.timeout ? dprofile?.auth?.timeout*1000 : 1000

    Long timeout = dprofile?.access?.explet_timeout ? dprofile?.access?.explet_timeout*1000 : null
    int bufferSize = dprofile?.access?.explet_bufferSize ?: 1024

    JSch client = new JSch()
    client.setLogger(new jschLogger())

    Session session
    Channel channel
    Expect eit
    try {
      session = client.getSession(user, host, port)
      session.setPassword(password)
      session.setConfig('compression.s2c', 'zlib@openssh.com,zlib,none')
      session.setConfig('compression.c2s', 'zlib@openssh.com,zlib,none')
      session.setConfig('compression_level', '9')
      session.setConfig('HashKnownHosts', 'yes')
      session.setConfig('StrictHostKeyChecking', 'no')
      session.setConfig('ConnectionAttempts', '2')
      session.connect(sshTimeout)

      channel = session.openChannel('shell')

      eit = eitSession(channel, bufferSize)
      channel.connect(sshTimeout)

      Result r
      Matcher[] mList = dprofile?.access?.prompt_standard.collect { regexp(it) }

      if (timeout) {
        r = eit.expect(timeout, anyOf(mList))
      } else {
        r = eit.expect(anyOf(mList))
      }

      if (r.isSuccessful()) {
        log.info "Successfully logged to the device: ${host}:${port}@${user}"
        con = ['channel':channel, 'session':session, 'eit':eit]
      } else {
        log.error "Failed to detect shell prompt: '${dprofile.access.prompt_standard}' of the device: ${host}:${port}@${user}"
      }
      eitDebug(r)

    } catch(AssertionError e) {
      log.error "Failed to detect shell prompt: '${dprofile.access.prompt_standard}' of the device: ${host}:${port}@${user} - ${e?.message}"
      con.clear()

      dprofile?.access?.cmds_disconnect?.each { eit.sendLine(it) }
      channel?.disconnect()
      eit?.close()

    } catch(Exception e) {
      StackTraceUtils.deepSanitize(e)
      log.error "Unable to connect to device: ${host}:${port}@${user} - ${e?.message}"
      log.debug "Unable to connect to device: ${host}:${port}@${user} - ${getStackTrace(e)}"
      con.clear()
    }

    if (!channel?.isConnected()) {
      log.error "Was unable to connect to device: ${host}:${port}@${user}"
      con.clear()
    }

    return con
  }


  /**
   * Close device shell connection
   *
   * @param con Shell session HashMap
   * @param aprofile Device Access Profile
   */
  void deviceShellClose(HashMap con, HashMap aprofile) {
    int timeout = aprofile.timeout * 1000

    try {
      aprofile?.cmds_disconnect?.each { con.eit.sendLine(it) }

      Result r = con.eit.expect(timeout, eof())
      if (r.isSuccessful()) {
        log.info "Successfully logged off device"
      } else {
        log.warn "Failed to correctly logged off device"
      }
      eitDebug(r)

    } catch(Exception|AssertionError e) {
      StackTraceUtils.deepSanitize(e)
      log.error "Failed to correctly logged off device: ${e?.message}"
      log.debug "Failed to correctly logged off device: ${getStackTrace(e)}"
    } finally {
      // Commented: Caught an exception, leaving main loop due to Socket closed
      //con?.session?.disconnect()
      con?.channel?.disconnect()
      con?.eit?.close()
    }
  }

  /**
   * Build Expect session
   *
   * @param chn SSH Channel
   * @param bufferSize Expect input bufferSize
   * @return Expect session
   */
  Expect eitSession(Channel chn, int bufferSize) {
     Expect eit
     Filter eitFilters = chain(removeColors(), removeNonPrintable())

     try {
       eit = new ExpectBuilder().withOutput(chn.getOutputStream())
                                .withInputs(chn.getInputStream(), chn.getExtInputStream())
                                .withEchoOutput(new DebugSend())
                                .withEchoInput(new DebugReceive())
                                .withBufferSize(bufferSize)
                                .withInputFilters(eitFilters)
                                .withErrorOnTimeout(true)
                                .build()

     } catch(Exception|AssertionError e) {
       StackTraceUtils.deepSanitize(e)
       log.error "ExpectBuilder exception: ${e?.message}"
       log.debug "ExpectBuilder exception: ${getStackTrace(e)}"
     }

    return eit
  }


  /**
   * Execute the device Disabling more prompt and Post Login commands
   *
   * @param con Device shell connection
   * @param aprofile Device Access Profile
   */
  void devicePostLoginCommands(HashMap con, HashMap aprofile) {
      if (aprofile?.cmds_post_login) {
        try {
          log.info "Sending Post Login cmds: ${aprofile?.cmds_post_login}"
          aprofile?.cmds_post_login?.each { con.eit.sendLine(it) }

          Result r
          Matcher[] mList = aprofile?.explet_post_login?.collect { regexp(it) }

          Long timeout = aprofile?.explet_timeout ? aprofile?.explet_timeout*1000 : null
          if(timeout) {
            r = con.eit.expect(timeout, anyOf(mList))
          } else {
            r = con.eit.expect(anyOf(mList))
          }
          eitDebug(r)

        } catch(Exception|AssertionError e) {
          StackTraceUtils.deepSanitize(e)
          log.error "Disabling more prompt exception: ${e?.message}"
          log.debug "Disabling more prompt exception: ${getStackTrace(e)}"
        }
      }

      if (aprofile?.cmds_disable_more_prompt) {
        try {
          log.info "Disabling more prompt: ${aprofile?.cmds_disable_more_prompt}"
          aprofile?.cmds_disable_more_prompt?.each { con.eit.sendLine(it) }

          Result r
          Matcher[] mList = aprofile?.explet_disable_more_prompt?.collect { regexp(it) }

          // TODO Very ugly workaround until I find whats causing some outputs to get mixed
          sleep(3*1000)

          Long timeout = aprofile?.explet_timeout ? aprofile?.explet_timeout*1000 : null
          if(timeout) {
            r = con.eit.expect(timeout, anyOf(mList))
          } else {
            r = con.eit.expect(anyOf(mList))
          }
          eitDebug(r)

        } catch(Exception|AssertionError e) {
          StackTraceUtils.deepSanitize(e)
          log.error "Disabling more prompt exception: ${e?.message}"
          log.debug "Disabling more prompt exception: ${getStackTrace(e)}"
        }
      }
  }


  /**
   * Execute the device commands and saves them to the GIT Repo
   *
   * @param con Device shell connection
   * @param device Device name
   * @param cmds List of device commands
   * @return ReturnCode 0=OK,1=GitChange
   */
  int deviceCommands(HashMap con, String device, ArrayList cmds) {
    int rc = 0
    log.info "Running ${cmds?.size} commands on ${device}"

    ArrayList rList = []
    cmds?.each { cmd ->
      HashMap r = [:]
      Long timeout = cmd?.explet_timeout ? cmd?.explet_timeout*1000 : null

      try {
        log.info "+Run: ${cmd?.send}"

        log.trace "eit.sending: ${cmd?.send}"
        cmd?.send?.each { con.eit.sendLine(it) }

        Matcher[] mList = cmd?.explet?.collect { regexp(it) }
        log.trace "eit.explet: ${mList}"

        // TODO Very ugly workaround until I find whats causing some outputs to get mixed
        sleep(3*1000)

        if(timeout) {
          r[cmd?.name] = con.eit.expect(timeout, anyOf(mList))
        } else {
          r[cmd?.name] = con.eit.expect(anyOf(mList))
        }
        rList << [(cmd?.name): r[cmd?.name].isSuccessful()]

        eitDebug(r[cmd?.name])

        String fullOutputStriped = stripHeaderFooter(r[cmd?.name].getBefore(), cmd?.strip_top, cmd?.strip_down)
        fullOutputStriped = cleanupPatterns(fullOutputStriped, cmd?.cleanup_patterns as ArrayList)

        saveStringToFile(device, cmd?.storage, fullOutputStriped)

      } catch(Exception|AssertionError e) {
        rList << [(cmd?.name): false]

        StackTraceUtils.deepSanitize(e)
        log.error "Error sending/receiving commands: ${e?.message}"
        log.debug "Error sending/receiving commands: ${getStackTrace(e)}"
      }
    }


    // Save changes to Git
    Git g = repOpen(this.gitRepo.resolve(device))
    if(g) {
      if( !g.status().call().isClean() ) {
        addAndCommit(g, '.', "eit.result: ${rList}")
        log.warn "Found changes in Git Repository, refLog size: ${g.reflog().call().size()}"
        rc = 1
      } else {
        log.info "No changes found in Git Repository"
      }

      g?.getRepository()?.close()
    } else {
      log.error "Skipping device, Unable to open Git Repository: ${this.gitRepo}"
    }

    return rc
  }


  /**
   * Strip header/footer from the output
   *
   * @param data Cmd String Output data
   * @param header Number of lines to strip from the header
   * @param footer Number of lines to strip from the footer
   * @return Cmd String Output data
   */
  String stripHeaderFooter(String data, Object header=0, Object footer=0) {
    if (!header && !footer) { log.info "Striping Header/Footer, Returning unmodified data"; return data }
    if( footer == 0 ) { footer = -1 } else { footer = -footer - 1 }
    log.info "Striping output Header:${header} / Footer:${footer}"

    ArrayList dataList
    try {
      dataList = data?.split("\r?\n|\r")?.getAt( [header..footer] )
    } catch (IndexOutOfBoundsException e) {
      log.error "Check your indexes, returning unmodified data"
    } catch(Exception e) {
      log.error "Error Striping Header/Footer, returning unmodified data: ${e?.message}"
    }

    dataList?.join('\n') ?: data
  }

  /**
   * CleanUp the output
   *
   * @param data Cmd String Output data
   * @param patterns Collection of patterns for cleaning [["RegEx","ReplaceString"], ["RegEx2","ReplaceString2"]]
   * @return Cmd String Output data
   */
  String cleanupPatterns(String data, ArrayList patterns) {
    if (!patterns) { log.info "CleanUp Returning unmodified data"; return data }

    try {
      log.info "Running ${patterns.collate(2).size()} CleanUp Patterns"

      patterns.collate(2).each { ArrayList pat ->
        if (pat.size != 2) {
          log.error "Ignoring CleanUp Pattern: ${pat}"
          return
        }

        if (pat?.getAt(0) =~ /\/g$/) {
          log.debug "CleanUp Pattern (Global): '${pat[0][1..-3]}' -> '${pat?.getAt(1)}'"
          data = data.replaceAll(pat?.getAt(0)[1..-3], pat?.getAt(1))

        } else if (pat?.getAt(0) =~ /\/$/) {
          log.debug "CleanUp Pattern (First): '${pat[0][1..-2]}' -> '${pat?.getAt(1)}'"
          data = data.replaceFirst(pat?.getAt(0)[1..-2], pat?.getAt(1))
        }
      }

    } catch(Exception e) {
      StackTraceUtils.deepSanitize(e)
      log.error "Error CleaningUP data: ${e?.message}"
      log.debug "Error CleaningUP data: ${getStackTrace(e)}"
    }

    return data
  }



  /**
   * Save the gathered data to the local FileSystem
   *
   * @param device SubFolder
   * @param storage Local file name
   * @param data Cmd String Output data
   */
  void saveStringToFile(String device, String storage, String data) {
    if (!data) { log.info "Saving local file: Ignored as content was empty"; return }
    if (!Files.exists(this.gitRepo.resolve(device))) {
      Files.createDirectories(this.gitRepo.resolve(device))
    }

    try {
      Path file = this.gitRepo.resolve(device).resolve(storage)
      log.info "Saving local file: ${file}"
      new FileWriter(file.toFile()).withWriter { it.write(data) }

    } catch(Exception e) {
      StackTraceUtils.deepSanitize(e)
      log.error "Error svaing local file to ${file}: ${e?.message}"
      log.debug "Error svaing local file to ${file}: ${getStackTrace(e)}"
    }
  }



  /**
   * Validate that Device Profile has the correct structure
   *
   */
  void validateDeviceProfiles() {
    Path folder = Paths.get(this.deviceProfilePath)

    if (Files.exists(folder)) {
      println " Checking Device Profiles ".center(40, '-')

      JsonSchema profileSchema
      try {
        JsonSchemaFactory factory = JsonSchemaFactory.byDefault()
        def profileMetadata = JsonLoader.fromResource(PROFILE_SCHEMA)
        profileSchema = factory.getJsonSchema(profileMetadata)

      } catch(com.fasterxml.jackson.core.JsonParseException e) {
        StackTraceUtils.deepSanitize(e)
        println " - Error Parsing Device Profile Schema: ${e?.message}"
        return
      } catch(Exception e) {
        StackTraceUtils.deepSanitize(e)
        println " - Error Loading Device Profile Schema: ${e?.message}"
        return
      }

      new File(folder.toFile().toString()).eachFileMatch(~/.*\.json/) { File it ->
        ProcessingReport profileReport
        boolean profileValid

        try {
          def profile = JsonLoader.fromFile(it)
          profileReport = profileSchema.validate(profile)
          profileValid = profileReport.isSuccess()

        } catch(com.fasterxml.jackson.core.JsonParseException e) {
          StackTraceUtils.deepSanitize(e)
          println " - Error Parsing Device Profile '${it.name}': ${e?.message}"
          return
        } catch(Exception e) {
          StackTraceUtils.deepSanitize(e)
          println " - Error Loading Device Profile '${it.name}': ${e?.message}"
          return
        }

        if (profileValid) {
          println " + The Device Profile: '${it.name}' is Valid"

        } else {
          println " - The Device Profile: '${it.name}' is NOT Valid:"
          println(' - BEGIN REPORT ----')
          profileReport.each { ProcessingMessage msg -> println msg }
          println(' - END REPORT ----')
        }
      }
    } else {
      println "The Device Profile folder: '${folder}' does not exist"
    }
  }

  /**
   * Validate that Device Profile has the correct structure
   *
   * @param File Device Profile File
   * @return boolean true = Valid / false Not valid
   */
  boolean validateDeviceProfile(File f) {
    JsonSchema profileSchema

    try {
      JsonSchemaFactory factory = JsonSchemaFactory.byDefault()
      def profileMetadata = JsonLoader.fromResource(PROFILE_SCHEMA)
      profileSchema = factory.getJsonSchema(profileMetadata)

    } catch(com.fasterxml.jackson.core.JsonParseException e) {
      StackTraceUtils.deepSanitize(e)
      log.error "Error Parsing Device Profile Schema: ${e?.message}"
      log.debug "Error Parsing Device Profile Schema: ${getStackTrace(e)}"
      return false

    } catch(Exception e) {
      StackTraceUtils.deepSanitize(e)
      log.error "Error Loading Device Profile Schema: ${e?.message}"
      log.debug "Error Loading Device Profile Schema: ${getStackTrace(e)}"
      return false
    }

    ProcessingReport profileReport
    boolean profileValid
    try {
      def profile = JsonLoader.fromFile(f)

      profileReport = profileSchema.validate(profile)
      profileValid = profileReport.isSuccess()

    } catch(com.fasterxml.jackson.core.JsonParseException e) {
      StackTraceUtils.deepSanitize(e)
      log.error "Error Parsing Device Profile '${f.name}': ${e?.message}"
      log.debug "Error Parsing Device Profile '${f.name}': ${getStackTrace(e)}"
      return false

    } catch(Exception e) {
      StackTraceUtils.deepSanitize(e)
      log.error "Error Loading Device Profile '${f.name}': ${e?.message}"
      log.debug "Error Loading Device Profile '${f.name}': ${getStackTrace(e)}"
      return false
    }

    if (profileValid) {
      log.info "The Device Profile: '${f.name}' is Valid"
      return true

    } else {
      log.error "The Device Profile: '${f.name}' is not Valid, ${profileReport}"
      return false
    }
  }

  /**
   * Generates the device profile data structure
   *
   */
  void loadDeviceProfiles() {
    Path folder = Paths.get(this.deviceProfilePath)

    if (Files.exists(folder)) {
      new File(folder.toFile().toString()).eachFileMatch(~/.*\.json/) { File it ->

        if (validateDeviceProfile(it)) {
          // TODO Find a better profile management for devices/types
          HashMap profile = loadDeviceProfile(it.toString())
          this.devProfiles[profile.name?.toLowerCase()] = profile

        } else {
          log.warn "The Device Profile: '${it.name}' ignored"
        }
      }
    } else {
      log.error "The Device Profile folder: '${folder}' does not exist"
    }
  }

  /**
   * Loads the json device profile
   *
   * @param file Profile json file path
   * @return profiles data structure
   */
  HashMap loadDeviceProfile(String file){
    JsonSlurper slurper = new JsonSlurper().setType(LAX)
    HashMap profile = [:]

    try {
      profile = slurper.parse(new FileReader(file))
    } catch(Exception e) {
      StackTraceUtils.deepSanitize(e)
      log.error "Error Loading profile '${file}': ${e?.message}"
      log.debug "Error Loading profile '${file}': ${getStackTrace(e)}"
      profile.clear()
    }
    return profile
  }



  /**
   * Open/Create Git Repository
   *
   * @param Path Repository path
   * @return Git Repository
   */
  Git repOpen(Path wDir){
    Git git
    try {
      git = Git.open(wDir.toFile())
    } catch(RepositoryNotFoundException e) {
      log.warn "Git Repository Not found, creating it!"
      git = repCreate(wDir)
    } catch(Exception e) {
      StackTraceUtils.deepSanitize(e)
      log.error "Error opening Git Repository: ${e?.message}"
      log.debug "Error opening Git Repository: ${getStackTrace(e)}"
    }
    return git
  }

  /**
   * Create Git Repository
   *
   * @param Path Repository path
   * @return Git Repository
   */
  Git repCreate(Path wDir){
    Git git
    try {
      InitCommand initCommand = Git.init()
      initCommand.setDirectory(wDir.toFile())
      git = initCommand.call()

      // Set git repo options
      StoredConfig conf = git.getRepository().getConfig()
      conf.setString('core', null, 'autocrlf', 'input')
      conf.setString('core', null, 'safecrlf', 'warn')
      conf.setBoolean('core', null, 'filemode', false)
      conf.save()

    } catch(Exception e) {
      StackTraceUtils.deepSanitize(e)
      log.error "Error creating Git Repository: ${e?.message}"
      log.debug "Error creating Git Repository: ${getStackTrace(e)}"
    }
    return git
  }

  /**
   * Add and Commit Git Repository
   *
   * @param Path Repository path
   * @param String Relative (Not absolute) path if the File/Folder
   * @param String commit message
   */
  void addAndCommit(Git git, String path='.', String msg=''){
    add2Repo(git, path)
    commit(git, msg)
  }

  /**
   * Add File/Folder to the Git Repository
   *
   * @param Git Repository
   * @param String Relative (Not absolute) path if the File/Folder
   */
  void add2Repo(Git git, String pathToAdd){
    try {
      DirCache dc = git.add().addFilepattern(pathToAdd).call()
      log.info "Added ${dc.entryCount} files to Git Repository"
    } catch(Exception e) {
      StackTraceUtils.deepSanitize(e)
      log.error "Error adding file '${pathToAdd}' to Git Repository: ${e?.message}"
      log.debug "Error adding file '${pathToAdd}' to Git Repository: ${getStackTrace(e)}"
    }
  }

  /**
   * Commit staged files
   *
   * @param Git Repository
   * @param String commit message
   */
  void commit(Git git, String msg){
    try {
      git.commit().setAll(true).setMessage(msg).setCommitter(new PersonIdent('CVM','dummy@account.com')).call()
    } catch(Exception e) {
      StackTraceUtils.deepSanitize(e)
      log.error "Error committing Git Repository: ${e?.message}"
      log.debug "Error committing Git Repository: ${getStackTrace(e)}"
    }
  }


  /**
   * Load the Authentication Profiles from the file (authProfilePath)
   *
   * @return ArrayList containing the Authentication Profiles
   */
  ArrayList loadAuthProfiles() {
    Path file = Paths.get(this.authProfilePath)

    if (Files.exists(file)) {
      ConfigObject auth

      try {
        auth = readConfigFile(file.toString())
        auth?.authProfiles?.each {
          // Convert String Expression back to RegEx Patterns
          if (it.pattern instanceof String) {
            it.pattern = it.pattern.contains('~/') ? Eval.me(it.pattern) : Pattern.compile(Pattern.quote(it.pattern))
          } else { it }
        }
        if (auth?.authProfiles?.size) {
          log.debug "Successfully Loaded ${auth?.authProfiles?.size} Authentication Profiles"
          this.authProfiles = auth?.authProfiles
        } else {
          log.error "Fail Loading Authentication Profiles"
        }

      } catch(Exception e) {
        StackTraceUtils.deepSanitize(e)
        log.error "Error Loading Authentication Profiles: ${e?.message}"
        log.debug "Error Loading Authentication Profiles: ${getStackTrace(e)}"
      }
    } else {
      log.error "The Authentication Profile '${file}' does not exist"
    }
  }


  /**
   * Build the Device Profile
   *
   * @param device Device Profile
   * @return profile data structure
   */
  HashMap buildDeviceProfile (HashMap device) {
    HashMap dprofile = [:]

    if (!this.devProfiles?.containsKey(device?.vendor?.toLowerCase())) {
      log.error "Could not find any Device Profile for this device: ${device}"
      return dprofile
    }
    if(!this.authProfiles) {
      log.error "Could not load Authentication Profile"
      return dprofile
    }

    try {
      boolean foundAuth = false
      dprofile.access = this.devProfiles[device?.vendor?.toLowerCase()].access
      dprofile.commands = this.devProfiles[device?.vendor?.toLowerCase()].commands
      dprofile.auth = ['host':device?.device,
                       'port': device?.port?.toInteger() ?: 22,
                       'timeout':this.devProfiles[device?.vendor?.toLowerCase()].access.timeout]

      // First priority, find device specific usr/pwd
      for ( a in this.authProfiles ) {
        if (a?.type?.toLowerCase() == 'device') {
          if(device?.device ==~ a.pattern) {
            log.debug "Found auth profile for specific device: ${device?.device}"
            dprofile.auth.user = a?.auth?.getAt(0)
            dprofile.auth.password = a?.auth?.getAt(1)
            if (a?.auth?.getAt(2)) {
              //dprofile.auth.port = a?.auth?.getAt(2).toInteger()
              //TODO ENABLE
              dprofile.auth.password_enable = a?.auth?.getAt(2).toString()
            }
            foundAuth = true
            break
          }
        }
      }

      if (!foundAuth) {
        // Second priority, find vendor specific usr/pwd
        for ( a in this.authProfiles ) {
          if (a?.type?.toLowerCase() == 'vendor') {
            if(device?.vendor ==~ a.pattern) {
              log.debug "Found auth profile for specific vendor: ${device?.vendor}"
              dprofile.auth.user = a?.auth?.getAt(0)
              dprofile.auth.password = a?.auth?.getAt(1)
              if (a?.auth?.getAt(2)) {
                //dprofile.auth.port = a?.auth?.getAt(2).toInteger()
                dprofile.auth.password_enable = a?.auth?.getAt(2).toString()
              }
              break
            }
          }
        }
      }

    } catch(Exception e) {
      StackTraceUtils.deepSanitize(e)
      log.error "Error building profile for the device ${device}: ${e?.message}"
      log.debug "Error building profile for the device ${device}: ${getStackTrace(e)}"
      dprofile.clear()
    }

    if (!dprofile) {
      log.error "Could not find any matching Device Profile for this device: ${device}"
    }

    return dprofile
  }


  /**
   * Get DeviceList from either NNMi or File deviceList.groovy
   *
   * @return ArrayList Device List data structure
   */
  ArrayList getDeviceList() {
    if (this.deviceSource?.toLowerCase() == 'file') {
      String fpath = this.cfg?.deviceSource?.file_path ?: 'deviceList.groovy'
      Path file = Paths.get(fpath)

      if (Files.exists(file)) {
        ConfigObject devCfg

        try {
          devCfg = readConfigFile(file.toString())
          if (devCfg?.deviceList) {
            log.info "Successfully Loaded ${devCfg.deviceList.size} Devices from '${this.deviceSource}'"
            return devCfg.deviceList
          }

        } catch(Exception e) {
          StackTraceUtils.deepSanitize(e)
          log.error "Error Loading Device List from ${this.deviceSource}: ${e?.message}"
          log.debug "Error Loading Device List from ${this.deviceSource}: ${getStackTrace(e)}"
        }
      } else {
        log.error "The Device List file: '${file}' does not exist"
      }

    } else if(this.deviceSource?.toLowerCase() == 'nnmi') {
      ArrayList devList = buildNNMiDeviceList(this.cfg?.deviceSource?.nnmi_deviceTypes as ArrayList, this.cfg?.deviceSource?.nnmi_nodeGroup)
      log.info "Successfully Loaded ${devList.size} Devices from '${this.deviceSource}'"
      return devList

    } else {
      log.error "Unknown Device List source: '${this.deviceSource}'"
    }

    log.error "No Devices were found!, verify the Device Source: '${this.deviceSource}'"
    return []
  }

  /**
   * Get DeviceList from either NNMi or File deviceList.groovy
   *
   * @param deviceList ArrayList Device List
   */
  void printDeviceList(ArrayList deviceList) {
    deviceList.each { HashMap d ->
      println d
    }
    println " Device List: ${deviceList.size()} ".center(30, '-')
  }


  /**
   * Main collection process
   *
   * @param deviceList ArrayList Device List
   */
  void collectData(ArrayList deviceList) {
    if(!deviceList) { return }
    if(!this.devProfiles) { return }

    try {
      Date timeStart = new Date()
      log.info "+ Start Collecting Process of ${deviceList.size} Devices"

      GParsPool.withPool() {
        deviceList.eachParallel { HashMap d ->
          int rc = 0
          String host = d?.device ==~ PAT_IP ? d?.device : d?.device?.split('\\.')?.getAt(0)?.toLowerCase()
          String type = d?.type?.toLowerCase() ?: 'other'
          log.info "${Thread.currentThread().name}: ${host} (${d?.vendor?.toLowerCase()}/${d?.model?.toLowerCase()}/${type})"

          MDC.put('device', host)
          final HashMap dprofile = buildDeviceProfile(d)
          final HashMap con = deviceShellOpen(dprofile)
          if (con) {
            devicePostLoginCommands(con, dprofile.access as HashMap)
            rc = deviceCommands(con, type+"/"+host, dprofile.commands as ArrayList)
            deviceShellClose(con, dprofile.access as HashMap)

            saveStringToFile(type+"/"+host, '.git/description', "${d?.vendor?.toLowerCase()}/${d?.model?.toLowerCase()}")
          }
          MDC.remove('device')

          if (!con) { log.error "${Thread.currentThread().name}: ${host} - Unable to connect to device" }
          if (rc) { log.warn "${Thread.currentThread().name}: ${host} - Found changes in Git Repository" }
        }
      }

      Date timeEnd = new Date()
      log.info "+ Ended Collecting Process in ${TimeCategory.minus(timeEnd,timeStart)}"

    } catch (Exception e) {
      StackTraceUtils.deepSanitize(e)
      log.error "Collecting Process Error: ${e?.message}"
      log.debug "Collecting Process Error: ${getStackTrace(e)}"
    }
    return
  }


  /**
   * Loads NNMi Node Group Assignment Cache
   *
   * @param ng NNMi Node Group Name
   * @return HashMap of the nodes contained in this NG: [NodeID] = NodeName
   */
  HashMap getNNMiNGACache(String ng=null) {
    HashMap ngCache = [:]
    if(!ng) { return ngCache }
    ng = URLEncoder.encode(ng,'UTF-8')

    String host = this.cfg?.deviceSource?.nnmi_vip
    String usr = this.cfg?.deviceSource?.nnmi_usr
    String pwd = this.cfg?.deviceSource?.nnmi_pwd
    String url = "http://${host}/jmx-console/HtmlAdaptor?action=invokeOpByName&name=com.hp.ov.nms.monitoring%3Ambean%3DNodeGroupAssignmentCacheService&methodName=dumpCacheForGroup&argType=java.lang.String&arg0=${ng}"

    String authString = "${usr}:${pwd}".getBytes().encodeBase64().toString()
    HttpURLConnection con = url.toURL().openConnection()
    con.setRequestProperty("Authorization", "Basic ${authString}")
    con.connect()

    if (con.getResponseCode() == 200) {
      // Parse HTML
      XmlSlurper slurper = new XmlSlurper()
      slurper.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
      slurper.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false)

      NodeChild html = slurper.parseText(con.getContent().text)
      html.body.pre.toString().eachLine { String it ->
        if (!it) { return }
        ArrayList nd = it.split(': ')
        ngCache[nd?.getAt(0)] = nd?.getAt(1)
      }
      log.trace "Loaded NNMi NodeGroup Assignment Cache ${ngCache.keySet().size()}, RC: ${con.getResponseCode()} (${con.getResponseMessage()})"
      return ngCache
    } else {
      log.error "Could not load NNMi NodeGroup Assignment Cache, RC: ${con.getResponseCode()} (${con.getResponseMessage()})"
      return ngCache
    }
  }

  /**
   * Retrieves nodes from NNMi of the specified deviceCategory
   *
   * @param deviceCategory NNMi Device Category
   * @return NodeChildren of al the retrieved NNMi Nodes
   */
  NodeChildren getNNMiNodes(String deviceCategory=null){
    String host = this.cfg?.deviceSource?.nnmi_vip
    String usr = this.cfg?.deviceSource?.nnmi_usr
    String pwd = this.cfg?.deviceSource?.nnmi_pwd

    SOAPClient client = new SOAPClient("http://${host}/NodeBeanService/NodeBean?wsdl")
    client.authorization = new HTTPBasicAuthorization(usr, pwd)

    SOAPMessageBuilder msg = new SOAPMessageBuilder().build {
      body {
        "ns1:getNodes"('xmlns:ns1':'http://node.sdk.nms.ov.hp.com/') {
          arg0('xmlns:xsi':'http://www.w3.org/2001/XMLSchema-instance', 'xmlns:ns3':'http://filter.sdk.nms.ov.hp.com/', 'xsi:type':'ns3:expression') {
            operator("AND")
            subFilters('xsi:type':'ns3:condition') {
              name('isSnmpSupported')
              operator('EQ')
              value(true)
            }
            if (deviceCategory) {
              subFilters('xsi:type':'ns3:condition') {
                name('deviceCategory')
                operator('EQ')
                value("com.hp.ov.nms.devices.${deviceCategory.toLowerCase()}")
              }
            }
          }
        }
      }
    }

    SOAPResponse response = client.send(msg.toString())
    log.trace "Found NNMi Nodes: ${response.getNodesResponse.return.item.size()} of deviceCategory: ${deviceCategory}"
    return response.getNodesResponse.return.item
  }

  /**
   * Build Device List from NNMi
   *
   * @param dType ArrayList of NNMi Device Categories
   * @param ng NNMi Node Group Name
   * @return ArrayList containing the Device List retrieved from NNMi
   */
  ArrayList buildNNMiDeviceList(ArrayList dType, String ng=null) {
    ArrayList devList = []
    HashMap ngCache = [:]

    if (ng) {
      ngCache = getNNMiNGACache(ng)
    }

    dType?.each { String dt ->
      NodeChildren nodes = getNNMiNodes(dt)

      nodes?.each { n ->
        // If NG is defined, Filter by NodeGroup
        if (ng && ngCache) {
          if (ngCache.containsKey(n.id.toString())) {
            devList << ['device':n.longName.toString(),
                        'vendor':n.deviceVendor.toString().split('\\.')?.getAt(-1)?.toLowerCase().toString(),
                        'model':n.deviceModel.toString(),
                        'type':n.deviceCategory.toString().split('\\.')?.getAt(-1)?.toLowerCase().toString()]

          } else { log.trace "Node: ${n.longName} is not present in NodeGroup: ${ng}" }
        } else {
           devList << ['device':n.longName.toString(),
                       'vendor':n.deviceVendor.toString().split('\\.')?.getAt(-1)?.toLowerCase().toString(),
                       'model':n.deviceModel.toString(),
                       'type':n.deviceCategory.toString().split('\\.')?.getAt(-1)?.toLowerCase().toString()]
        }
      }
    }

    log.debug "Found: ${devList.size()} NNMi Devices"
    return devList
  }




  /**
   * Main execution loop
   *
   */
  static void main(String[] args) throws Exception {
    addShutdownHook { log.info "Shuting down app..." }

    CliBuilder cli = new CliBuilder(usage: '[-h] [-pc] ([-st <Type RegEx>] [-sn <Node RegEx>] [-ld]) [No paramaters Run on All Devices]', stopAtNonOption: false)
    cli.h(longOpt:'help', 'Usage information')
    cli.st(longOpt:'stype', 'Select Type, OPTIONAL', argName:'RegEx', required:false, type:String, args:1)
    cli.sn(longOpt:'snode', 'Select Node, OPTIONAL', argName:'RegEx', required:false, type:String, args:1)
    cli.ld(longOpt:'ldevices', 'List Selected Devices, OPTIONAL', required:false)
    cli.pc(longOpt:'pcheck', 'Device Profile Check, OPTIONAL', required:false)

    OptionAccessor opt = cli.parse(args)
    if (!opt){ return }
    if (opt.h) {
      cli.usage()
      return
    }

    CVM m
    try {
      m = new CVM()

      if (opt.pc) {
        m.validateDeviceProfiles()
        return
      }

      // Loads profile data
      m.loadDeviceProfiles()
      m.loadAuthProfiles()
      ArrayList deviceList = m.getDeviceList()


      if (opt.st) {
        Pattern tSearch = Pattern.compile(opt.st)
        deviceList = deviceList.findAll { it.type ==~ tSearch }
        log.info "Select Type (${tSearch}) Matched Devices: ${deviceList.size()}"
      }
      if (opt.sn) {
        Pattern nSearch = Pattern.compile(opt.sn)
        deviceList = deviceList.findAll { it.device ==~ nSearch }
        log.info "Select Node (${nSearch}) Matched Devices: ${deviceList.size()}"
      }

      if (opt.ld) {
        m.printDeviceList(deviceList)
        return
      }

      m.collectData(deviceList)

    } catch (Exception e) {
      StackTraceUtils.deepSanitize(e)
      log.error "Main exception: ${e?.message}"
    }
  }
}
