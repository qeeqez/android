/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.tools.profilers.cpu.atrace;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.containers.Predicate;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import perfetto.protos.PerfettoTrace;
import trebuchet.io.BufferProducer;
import trebuchet.io.DataSlice;

/**
 * This class converts perfetto traces to {@link DataSlice} objects. The {@link DataSlice} objects are then used by the
 * {@link trebuchet.task.ImportTask} to create a {@link trebuchet.model.Model}.
 * This model is used by the profilers UI to render systrace data.
 */
public class PerfettoProducer implements BufferProducer {
  // Required line for trebuchet to parse as ftrace.
  private static final String FTRACE_HEADER = "# tracer: nop";
  // Supported events are events that we know how to convert from perfetto format to systrace format.
  // The current set of supported events are the only events that we need as they are the only events that trebuchet supports.
  private static final Predicate<PerfettoTrace.FtraceEvent> IS_SUPPORTED_EVENT = event ->
    event.hasSchedSwitch() ||
    event.hasSchedWakeup() ||
    event.hasSchedWaking() ||
    event.hasPrint();

  // Maps thread id to thread group id. A tgid is the thread id at the root of the tree. This is also known as the PID in user space.
  private final Map<Integer, Integer> myTidToTgid = new HashMap<>();
  private final Map<Integer, String> myTidToName = new HashMap<>();
  private Iterator<String> myTimeSortedLines;

  private static PerfettoTrace.Trace loadTrace(File file) {
    try {
      // TODO: Write own custom ByteBuffer class to stream data in if this is slow.
      return PerfettoTrace.Trace.parseFrom(new FileInputStream(file));
    }
    catch (IOException ex) {
      getLogger().error(ex);
      return null;
    }
  }

  public static boolean verifyFileHasPerfettoTraceHeader(@NotNull File file) {
    PerfettoTrace.Trace trace = loadTrace(file);
    return trace != null && trace.getPacketCount() != 0;
  }

  private static Logger getLogger() {
    return Logger.getInstance(PerfettoProducer.class);
  }

  public PerfettoProducer(File file) {
    PerfettoTrace.Trace trace = loadTrace(file);
    assert trace != null;
    convertToTraceLines(trace);
  }

  private void convertToTraceLines(PerfettoTrace.Trace trace) {
    // Add a special case name for thread id 0.
    // Thread id 0 is used for events that are generated by the system not associated with any process.
    // In systrace and perfetto they use <idle> as the name for events generated with this thread id.
    myTidToName.put(0, "<idle>");
    // TODO (b/125865941) Handle tid recycling when mapping tids to names.
    // Loop all packets building thread names, and a map of thread ids to process ids.
    List<PerfettoTrace.TracePacket> ftracePacketsToProcess = new ArrayList<>();
    trace.getPacketList().forEach((packet -> {
      if (packet.hasFtraceEvents()) {
        ftracePacketsToProcess.add(packet);
        PerfettoTrace.FtraceEventBundle bundle = packet.getFtraceEvents();
        for (PerfettoTrace.FtraceEvent event : bundle.getEventList()) {
          if (!event.hasSchedSwitch()) {
            continue;
          }
          PerfettoTrace.SchedSwitchFtraceEvent schedSwitch = event.getSchedSwitch();
          myTidToName.putIfAbsent(schedSwitch.getPrevPid(), schedSwitch.getPrevComm());
          myTidToName.putIfAbsent(schedSwitch.getNextPid(), schedSwitch.getNextComm());
        }
      }
      else if (packet.hasProcessTree()) {
        PerfettoTrace.ProcessTree processTree = packet.getProcessTree();
        for (PerfettoTrace.ProcessTree.Process process : processTree.getProcessesList()) {
          // Main threads will have the same pid as tgid.
          myTidToTgid.putIfAbsent(process.getPid(), process.getPid());
        }
        for (PerfettoTrace.ProcessTree.Thread thread : processTree.getThreadsList()) {
          myTidToTgid.putIfAbsent(thread.getTid(), thread.getTgid());
          if (thread.hasName()) {
            myTidToName.putIfAbsent(thread.getTid(), thread.getName());
          }
        }
      }
    }));

    // Build systrace lines for each packet.
    // Note: lines need to be sorted by time else assumptions in trebuchet break.
    SortedMap<Long, String> timeSortedLines = new TreeMap<>();
    timeSortedLines.put(0L, "# Initial Data Required by Importer");
    timeSortedLines.put(1L, FTRACE_HEADER);
    ftracePacketsToProcess.forEach(packet -> {
      PerfettoTrace.FtraceEventBundle bundle = packet.getFtraceEvents();
      for (PerfettoTrace.FtraceEvent event : bundle.getEventList()) {
        if (!IS_SUPPORTED_EVENT.apply(event)) {
          continue;
        }
        String line = formatEventPrefix(event.getTimestamp(), bundle.getCpu(), event) + formatEvent(event);
        timeSortedLines.put(event.getTimestamp(), line);
      }
    });
    myTimeSortedLines = timeSortedLines.values().iterator();
  }

  @Nullable
  @Override
  public DataSlice next() {
    if (!myTimeSortedLines.hasNext()) {
      // Null signals end of file.
      return null;
    }
    String line = myTimeSortedLines.next();
    // Trebuchet has a bug where all lines need to be truncated to 1023 characters including the newline.
    byte[] data = String.format("%s\n", line.substring(0, Math.min(1022, line.length()))).getBytes();
    return new DataSlice(data);
  }

  @Override
  public void close() {
    //Clean up
    myTidToName.clear();
    myTidToTgid.clear();
  }

  /**
   * Helper function that builds the prefix for systrace lines. The prefix is in the format of
   * [thread name]-[tid]     ([tgid]) [[cpu]] d..3 [time in seconds].
   * Note d..3 is hard coded as it is expected to be part of the line, however it is not used.
   */
  private String formatEventPrefix(long timestampNs, int cpu, PerfettoTrace.FtraceEvent event) {
    String name = myTidToName.getOrDefault(event.getPid(), "<...>");
    // Convert Ns to seconds as seconds is the expected atrace format.
    String timeSeconds = String.format("%.6f", timestampNs / 1000000.0);
    String tgid = "-----";
    if (myTidToTgid.containsKey(event.getPid())) {
      tgid = String.format("%5d", myTidToTgid.get(event.getPid()));
    }
    return String.format("%s-%d     (%s) [%3d] d..3 %s: ", name, event.getPid(), tgid, cpu, timeSeconds);
  }

  /**
   * Helper function to format specific event protos to the systrace equivalent format.
   * Example: tracing_mark_write: B|123|drawFrame
   */
  private static String formatEvent(PerfettoTrace.FtraceEvent event) {
    if (event.hasSchedSwitch()) {
      PerfettoTrace.SchedSwitchFtraceEvent sched = event.getSchedSwitch();
      return String.format("sched_switch: prev_comm=%s prev_pid=%d prev_prio=%d prev_state=%s ==> next_comm=%s next_pid=%d next_prio=%d",
                           sched.getPrevComm(), sched.getPrevPid(), sched.getPrevPrio(), sched.getPrevState(), sched.getNextComm(),
                           sched.getNextPid(), sched.getNextPrio());
    }
    else if (event.hasSchedCpuHotplug()) {
      PerfettoTrace.SchedCpuHotplugFtraceEvent sched = event.getSchedCpuHotplug();
      return String.format("sched_cpu_hotplug: cpu %d %s error=%d", sched.getAffectedCpu(), sched.getStatus() == 0 ? "offline" : "online",
                           sched.getError());
    }
    else if (event.hasSchedBlockedReason()) {
      PerfettoTrace.SchedBlockedReasonFtraceEvent sched = event.getSchedBlockedReason();
      return String.format("sched_blocked_reason: pid=%d iowait=%d caller=%s", sched.getPid(), sched.getIoWait(), sched.getCaller());
    }
    else if (event.hasSchedWaking()) {
      PerfettoTrace.SchedWakingFtraceEvent sched = event.getSchedWaking();
      return String
        .format("sched_wakeing: comm=%s pid=%d prio=%d success=%d target_cpu=%03d", sched.getComm(), sched.getPid(), sched.getPrio(),
                sched.getSuccess(), sched.getTargetCpu());
    }
    else if (event.hasSchedWakeup()) {
      PerfettoTrace.SchedWakeupFtraceEvent sched = event.getSchedWakeup();
      return String
        .format("sched_wakeup: comm=%s pid=%d prio=%d success=%d target_cpu=%03d", sched.getComm(), sched.getPid(), sched.getPrio(),
                sched.getSuccess(), sched.getTargetCpu());
    }
    else if (event.hasPrint()) {
      return String.format("tracing_mark_write: %s", event.getPrint().getBuf().toString().replace("\n", ""));
    }
    else {
      getLogger().assertTrue(IS_SUPPORTED_EVENT.apply(event), "Attempted to format a non-supported event.");
    }
    return "";
  }
}