package com.foo;

import android.app.Activity;
import android.os.Bundle;

public class MyActivity extends Activity {
  @Override
  public void onCreate(Bundle savedInstanceState) {
    String name = getResources().getResourceName(R.drawable.ic_laun<caret>cher);
  }

  public static final class R {
    public static final class drawable {
      public static final int ic_launcher = 0x7f0a000e;
    }
  }
}
