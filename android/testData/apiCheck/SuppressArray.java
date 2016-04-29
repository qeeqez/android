package p1.p2;

import android.annotation.SuppressLint;
import android.content.SharedPreferences;

class X {
  public void test1(SharedPreferences prefs) {
    prefs.<error descr="Call requires API level 11 (current min is 1): android.content.SharedPreferences#getStringSet">getStringSet</error>("test", null); // ERROR
  }

  @SuppressLint({ "CommitPrefEdits", "NewApi" })
  public void test2(SharedPreferences prefs) {
    prefs.getStringSet("test", null); // OK: suppressed
  }

  @SuppressLint({ "NewApi", "CommitPrefEdits" })
  public void test3(SharedPreferences prefs) {
    prefs.getStringSet("test", null); // OK: suppressed
  }

  @SuppressLint("NewApi")
  public void test4(SharedPreferences prefs) {
    prefs.getStringSet("test", null); // OK: suppressed
  }
}