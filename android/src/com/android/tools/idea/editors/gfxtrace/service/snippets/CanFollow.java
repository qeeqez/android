/*
 * Copyright (C) 2015 The Android Open Source Project
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
 *
 * THIS FILE WAS GENERATED BY codergen. EDIT WITH CARE.
 */
package com.android.tools.idea.editors.gfxtrace.service.snippets;

import org.jetbrains.annotations.NotNull;

import com.android.tools.rpclib.binary.*;
import com.android.tools.rpclib.schema.*;

import java.io.IOException;

public final class CanFollow extends KindredSnippets implements BinaryObject {
  //<<<Start:Java.ClassBody:1>>>
  private Pathway myPath;

  // Constructs a default-initialized {@link CanFollow}.
  public CanFollow() {}


  public Pathway getPath() {
    return myPath;
  }

  public CanFollow setPath(Pathway v) {
    myPath = v;
    return this;
  }

  @Override @NotNull
  public BinaryClass klass() { return Klass.INSTANCE; }


  private static final Entity ENTITY = new Entity("snippets", "CanFollow", "", "");

  static {
    ENTITY.setFields(new Field[]{
      new Field("Path", new Interface("Pathway")),
    });
    Namespace.register(Klass.INSTANCE);
  }
  public static void register() {}
  //<<<End:Java.ClassBody:1>>>

  /**
   * find the CanFollow snippet in the snippets.
   * @param snippets any kind of snippets.
   * @return the CanFollow snippet or null.
   */
  public static CanFollow fromSnippets(KindredSnippets[] snippets) {
    for (KindredSnippets obj : snippets) {
      if (obj instanceof CanFollow) {
        return (CanFollow)obj;
      }
    }
    return null;
  }

  public enum Klass implements BinaryClass {
    //<<<Start:Java.KlassBody:2>>>
    INSTANCE;

    @Override @NotNull
    public Entity entity() { return ENTITY; }

    @Override @NotNull
    public BinaryObject create() { return new CanFollow(); }

    @Override
    public void encode(@NotNull Encoder e, BinaryObject obj) throws IOException {
      CanFollow o = (CanFollow)obj;
      e.object(o.myPath == null ? null : o.myPath.unwrap());
    }

    @Override
    public void decode(@NotNull Decoder d, BinaryObject obj) throws IOException {
      CanFollow o = (CanFollow)obj;
      o.myPath = Pathway.wrap(d.object());
    }
    //<<<End:Java.KlassBody:2>>>
  }
}
