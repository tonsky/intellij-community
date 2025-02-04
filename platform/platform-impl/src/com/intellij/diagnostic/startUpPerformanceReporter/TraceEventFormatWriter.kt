// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.diagnostic.startUpPerformanceReporter

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonGenerator
import com.intellij.diagnostic.ActivityImpl
import com.intellij.util.io.jackson.array
import com.intellij.util.io.jackson.obj
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit

private const val VERSION = "1"

// https://docs.google.com/document/d/1CvAClvFfyA5R-PhYUmn5OOQtYMH4h6I0nSsKchNAySU/edit#
// ph - phase
// dur - duration
// pid - process id
// tid - thread id
internal class TraceEventFormatWriter(private val timeOffset: Long,
                                      private val instantEvents: List<ActivityImpl>,
                                      private val threadNameManager: ThreadNameManager) {
  fun writeInstantEvents(writer: JsonGenerator) {
    for (event in instantEvents) {
      writer.obj {
        writeCommonFields(event, writer)
        writer.writeStringField("ph", "i")
        writer.writeStringField("s", "g")
      }
    }
  }

  fun write(mainEvents: List<ActivityImpl>, categoryToActivity: Map<String, List<ActivityImpl>>, outputWriter: OutputStreamWriter) {
    val writer = JsonFactory().createGenerator(outputWriter)
    writer.prettyPrinter = MyJsonPrettyPrinter()
    writer.use {
      writer.obj {
        writer.writeStringField("version", VERSION)
        writer.array("traceEvents") {
          writeInstantEvents(writer)

          for (event in mainEvents) {
            writer.obj {
              writeCompleteEvent(event, writer)
            }
          }

          for ((category, events) in categoryToActivity) {
            for (event in events) {
              writer.obj {
                writeCompleteEvent(event, writer)
                writer.writeStringField("cat", category)
              }
            }
          }
        }
      }
    }
  }

  private fun writeCompleteEvent(event: ActivityImpl, writer: JsonGenerator) {
    writeCommonFields(event, writer)
    writer.writeStringField("ph", "X")
    writer.writeNumberField("dur", TimeUnit.NANOSECONDS.toMicros(event.end - event.start))
    if (event.description != null) {
      writer.obj("args") {
        writer.writeStringField("description", event.description)
      }
    }
  }

  private fun writeCommonFields(event: ActivityImpl, writer: JsonGenerator) {
    writer.writeStringField("name", event.name)
    writer.writeNumberField("ts", TimeUnit.NANOSECONDS.toMicros(event.start - timeOffset))
    writer.writeNumberField("pid", 1)
    writer.writeStringField("tid", threadNameManager.getThreadName(event))
  }
}