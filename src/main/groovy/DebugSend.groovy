package com.allthingsmonitoring.utils.cvm

import org.slf4j.*
import groovy.util.logging.Slf4j
import ch.qos.logback.classic.*
import static ch.qos.logback.classic.Level.*
import org.codehaus.groovy.runtime.StackTraceUtils

@Slf4j
class DebugSend implements Appendable {
  @Override
  public Appendable append(CharSequence csq, int start, int end) throws IOException {
    throw new UnsupportedOperationException()
  }
  @Override
  public Appendable append(char c) throws IOException {
    throw new UnsupportedOperationException()
  }
  @Override
  public Appendable append(CharSequence c) throws IOException {
    log.trace "SEND: ${c.toString()}"
  }
}
