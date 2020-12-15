/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.tools.idea.emulator

import com.android.testutils.file.DelegatingFileSystemProvider
import com.android.testutils.file.createInMemoryFileSystem
import com.android.testutils.file.getExistingFiles
import com.android.testutils.file.someRoot
import com.android.testutils.truth.PathSubject.assertThat
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.IOException
import java.io.OutputStream
import java.nio.file.FileSystem
import java.nio.file.Files
import java.nio.file.OpenOption
import java.nio.file.Path
import kotlin.test.fail

/**
 * Test for functions defined in `KeyValueFileUtils.kt`.
 */
class KeyValueFileUtilsTest {
  private var exception: IOException? = null

  @Test
  fun testUpdateKeyValueFile() {
    val fileSystem = MockFileSystemProvider(createInMemoryFileSystem()).fileSystem
    val file = fileSystem.someRoot.resolve("test.ini")
    Files.write(file, listOf("AvdId = Pixel_4_XL_API_30",
                             "PlayStore.enabled = false",
                             "avd.ini.displayname = Pixel 4 XL API 30",
                             "fastboot.chosenSnapshotFile = snapshot42",
                             "fastboot.forceChosenSnapshotBoot = yes",
                             "hw.sensors.orientation = yes"))
    // Check normal update.
    updateKeyValueFile(file, mapOf("PlayStore.enabled" to "true",
                                   "fastboot.chosenSnapshotFile" to null,
                                   "fastboot.forceChosenSnapshotBoot" to "no",
                                   "fastboot.forceFastBoot" to "yes"))
    assertThat(file).hasContents("AvdId=Pixel_4_XL_API_30",
                                 "PlayStore.enabled=true",
                                 "avd.ini.displayname=Pixel 4 XL API 30",
                                 "fastboot.forceChosenSnapshotBoot=no",
                                 "fastboot.forceFastBoot=yes",
                                 "hw.sensors.orientation=yes")
    assertThat(fileSystem.getExistingFiles()).isEqualTo(arrayOf("$file")) // No extra files left behind.

    // Check with I/O errors.
    exception = IOException()
    try {
      updateKeyValueFile(file, mapOf("PlayStore.enabled" to "false"))
      fail("Expected an exception to be logged")
    }
    catch (e: AssertionError) {
      assertThat(e.message).isEqualTo("Error writing $file")
    }
    assertThat(fileSystem.getExistingFiles()).isEqualTo(arrayOf("$file")) // No extra files left behind.

    exception = IOException("something horrible happened")
    try {
      updateKeyValueFile(file, mapOf("PlayStore.enabled" to "false"))
      fail("Expected an exception to be logged")
    }
    catch (e: AssertionError) {
      assertThat(e.message).isEqualTo("Error writing $file - something horrible happened")
    }
    assertThat(fileSystem.getExistingFiles()).isEqualTo(arrayOf("$file")) // No extra files left behind.
  }

  private inner class MockFileSystemProvider(fileSystem: FileSystem) : DelegatingFileSystemProvider(fileSystem) {
    override fun newOutputStream(path: Path, vararg options: OpenOption): OutputStream {
      exception?.let { throw it }
      return super.newOutputStream(path, *options)
    }
  }
}