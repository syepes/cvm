import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.core.ConsoleAppender
import ch.qos.logback.core.status.OnConsoleStatusListener
import ch.qos.logback.core.rolling.RollingFileAppender
import ch.qos.logback.core.rolling.FixedWindowRollingPolicy
import ch.qos.logback.core.rolling.SizeBasedTriggeringPolicy
import ch.qos.logback.core.rolling.TimeBasedRollingPolicy
import static ch.qos.logback.classic.Level.*
import ch.qos.logback.classic.filter.*

import ch.qos.logback.classic.jmx.*
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.sift.GSiftingAppender
import ch.qos.logback.classic.sift.MDCBasedDiscriminator

import java.lang.management.ManagementFactory
//https://github.com/qos-ch/logback/pull/70

String baseName = 'cvm'
ArrayList classNames = ['com.allthingsmonitoring.utils.CVM','com.allthingsmonitoring.utils.cvm.DebugSend','com.allthingsmonitoring.utils.cvm.DebugReceive']
Map defaultLevels = setLoggerLevels()

if (System.properties['app.env']?.toUpperCase() == 'DEBUG'){ statusListener(OnConsoleStatusListener) }
scan("30 seconds")
setupAppenders(baseName,defaultLevels)
setupLoggers(classNames)
//jmxConfigurator()


Map setLoggerLevels() {
  Map defaultLevels = [:]
  String env = System.properties['app.env']?.toUpperCase() ?: 'PROD'

  if(env == 'PROD'){ // Only file (info)
    defaultLevels['CONSOLE'] = ERROR
    defaultLevels['FILE'] = INFO
  }else if(env == 'DEV'){ // File (debug) and console (info)
    defaultLevels['CONSOLE'] = INFO
    defaultLevels['FILE'] = DEBUG
  }else if(env == 'DEBUG'){
    defaultLevels['CONSOLE'] = INFO
    defaultLevels['FILE'] = TRACE
  }else{
    defaultLevels['CONSOLE'] = OFF
    defaultLevels['FILE'] = OFF
  }

  return defaultLevels
}

void setupAppenders(String baseName, Map defaultLevels) {
  String HOSTNAME = hostname?.split('\\.')?.getAt(0)?.replaceAll(~/[\s-\.]/, "-")?.toLowerCase() // Get only the hostname of the FQDN

  appender("CONSOLE", ConsoleAppender) {
    // Deny all events with a level below INFO, that is TRACE and DEBUG
    filter(ThresholdFilter) { level = defaultLevels['CONSOLE'] }
    encoder(PatternLayoutEncoder) {
      pattern = "%-35(%d{HH:mm:ss} [%thread]) %highlight(%-5level) %logger - %msg%n%rEx"
    }
  }

  appender("FILE", GSiftingAppender) {
    String pid = System.properties['pid'] ?: '#'

    discriminator(MDCBasedDiscriminator) {
      key = 'device'
      defaultValue = baseName
    }
    sift {
      String filePath
      if (device == baseName) {
        filePath = "./logs/${device}.log"
      } else {
        filePath = "./logs/devices/${device}.log"
      }
      appender("FILE-${device}", RollingFileAppender) {
        file = filePath
        filter(ThresholdFilter) { level = defaultLevels['FILE'] }
        encoder(PatternLayoutEncoder) {
          pattern = "%-35(%d{dd-MM-yyyy - HH:mm:ss.SSS} [${HOSTNAME}] ${pid}:[%thread]) %highlight(%-5level) %logger - %msg%n%rEx"
        }

        if (device == baseName) {
          rollingPolicy(TimeBasedRollingPolicy) {
            fileNamePattern = "./logs/${device}_%d{yyyy-MM-dd}.log"
            maxHistory = 7
          }
        } else {
          rollingPolicy(FixedWindowRollingPolicy) {
            fileNamePattern = "${filePath}.%i"
            minIndex = 1
            maxIndex = 5
          }
          triggeringPolicy(SizeBasedTriggeringPolicy) {
            maxFileSize = "100MB"
          }
        }
      }
    }
  }
}

void setupLoggers(ArrayList classNames) {
  classNames.each { String cn ->
    logger cn, TRACE, ['CONSOLE', 'FILE']
  }
}

void jmxConfigurator() {
  def contextName = context.name
  def objectNameAsString = MBeanUtil.getObjectNameFor(contextName, JMXConfigurator.class)
  def objectName = MBeanUtil.string2ObjectName(context, this, objectNameAsString)
  def platformMBeanServer = ManagementFactory.getPlatformMBeanServer()
  if (!MBeanUtil.isRegistered(platformMBeanServer, objectName)) {
    JMXConfigurator jmxConfigurator = new JMXConfigurator((LoggerContext) context, platformMBeanServer, objectName)
    try {
      platformMBeanServer.registerMBean(jmxConfigurator, objectName)
    } catch (all) {
      addError("Failed to create mbean", all)
    }
  }
}
