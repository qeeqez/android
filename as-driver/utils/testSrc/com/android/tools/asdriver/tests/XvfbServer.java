/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.tools.asdriver.tests;

import com.android.testutils.TestUtils;
import com.intellij.openapi.util.SystemInfoRt;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * An X server potentially backed by Xvfb.
 */
public class XvfbServer implements Display {
  private static final String DEFAULT_RESOLUTION = "1280x1024x24";
  private static final int MAX_RETRIES_TO_FIND_DISPLAY = 20;
  private static final String XVFB_LAUNCHER = "tools/vendor/google/testing/display/launch_xvfb.sh";
  private static final String FFMPEG = "tools/vendor/google/testing/display/ffmpeg";

  private Process process;

  /**
   * The display we're using on Linux. This will start with a colon, e.g. ":40981".
   */
  private String display;

  private Boolean cachedCanCallImport = null;
  private Process recorder;

  public XvfbServer() throws IOException {
    String display = System.getenv("DISPLAY");
    if (display == null || display.isEmpty()) {
      // If a display is provided use that, otherwise create one.
      this.display = launchUnusedDisplay();
      this.recorder = launchRecorder(this.display);
      System.out.println("Display: " + this.display);
    } else {
      this.display = display;
      System.out.println("Display inherited from parent: " + display);
    }
  }

  /**
   * Helper function for {@link XvfbServer#debugTakeScreenshot}.
   */
  private boolean canCallImport() {
    if (cachedCanCallImport == null) {
      try {
        ProcessBuilder pb = new ProcessBuilder("import");
        Process p = pb.start();
        p.waitFor(1, TimeUnit.SECONDS);
        cachedCanCallImport = true;
      }
      catch (IOException | InterruptedException e) {
        cachedCanCallImport = false;
      }
    }

    return cachedCanCallImport;
  }

  /**
   * Takes a screenshot of the headless display from Xvfb on Linux.
   * @param fileName A file name for the screenshot, e.g. "before" or "after".
   */
  @Override
  public void debugTakeScreenshot(String fileName) throws IOException {
    if (!SystemInfoRt.isLinux) {
      throw new RuntimeException("debugTakeScreenshot is only available on Linux since it uses \"import\"");
    } else if (!canCallImport()) {
      throw new RuntimeException("Can't take a screenshot on Linux without \"import\"");
    }

    if (fileName == null) {
      fileName = "screenshot";
    }

    Path screenshotsDir = Paths.get(System.getProperty("java.io.tmpdir"),"e2e_screenshots");
    try {
      Files.createDirectories(screenshotsDir);
    }
    catch (IOException e) {
      e.printStackTrace();
      throw e;
    }
    File tempDest = Paths.get(screenshotsDir.toString(), fileName + ".png").toFile();
    try {
      System.out.println("Taking a screenshot and saving it to " + tempDest);
      ProcessBuilder pb = new ProcessBuilder(
        "import",
        "-display",
        display,
        "-window",
        "root",
        tempDest.toString()
      );
      pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
      pb.redirectError(ProcessBuilder.Redirect.INHERIT);
      Process p = pb.start();
      p.waitFor(2, TimeUnit.SECONDS);
      System.out.println("Successfully captured " + tempDest);
    }
    catch (IOException | InterruptedException e) {
      e.printStackTrace();
      throw new RuntimeException(e);
    }
  }

  @Override
  public String getDisplay() {
    return display;
  }

  private Process launchRecorder(String display) throws IOException {
    Path dir = TestUtils.getTestOutputDir();
    Path mp4 = dir.resolve("recording.mp4");
    Path ffmpeg = TestUtils.resolveWorkspacePathUnchecked(FFMPEG);

    // Note that -pix_fmt is required by some players:
    // https://trac.ffmpeg.org/wiki/Encode/H.264#Encodingfordumbplayers
    ProcessBuilder pb = new ProcessBuilder(ffmpeg.toString(), "-framerate", "25", "-f", "x11grab", "-i", display, "-pix_fmt", "yuv420p", mp4.toString());
    pb.redirectOutput(dir.resolve("ffmpeg_stdout.txt").toFile());
    pb.redirectError(dir.resolve("ffmpeg_stderr.txt").toFile());
    return pb.start();
  }

  public String launchUnusedDisplay() {
    int retry = MAX_RETRIES_TO_FIND_DISPLAY;
    Random random = new Random();
    while (retry-- > 0) {
      int candidate = random.nextInt(65535);
      // The only mechanism with our version of Xvfb to know when it's ready
      // to accept connections is to check for the following file. Additionally,
      // this serves as a check to know if another server is using the same
      // display.
      Path socket = Paths.get("/tmp/.X11-unix", "X" + candidate);
      if (Files.exists(socket)) {
        continue;
      }
      String display = String.format(":%d", candidate);
      Process process = launchDisplay(display);
      try {
        boolean exited = false;
        while (!exited && !Files.exists(socket)) {
          exited = process.waitFor(1, TimeUnit.SECONDS);
        }
        if (!exited) {
          this.process = process;
          System.out.println("Launched xvfb on \"" + display + "\"");
          return display;
        }
      }
      catch (InterruptedException e) {
        throw new RuntimeException("Xvfb was interrupted", e);
      }
    }
    throw new RuntimeException("Cannot find an unused display");
  }

  private Process launchDisplay(String display) {
    this.display = display;
    Path launcher = TestUtils.resolveWorkspacePathUnchecked(XVFB_LAUNCHER);
    if (Files.notExists(launcher)) {
      throw new IllegalStateException("Xvfb runfiles does not exist. "
                                      + "Add a data dependency on the runfiles for Xvfb. "
                                      + "It will look something like "
                                      + "//tools/vendor/google/testing/display:xvfb");
    }
    try {
      return new ProcessBuilder(
        launcher.toString(),
        display,
        TestUtils.getWorkspaceRoot().toString(),
        DEFAULT_RESOLUTION
      ).start();
    }
    catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public void close() {
    if (process != null) {
      process.destroyForcibly();
      process = null;
    }
    if (recorder != null) {
      recorder.destroy();
      recorder = null;
    }
  }
}
