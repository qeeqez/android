package test.pkg;

import android.media.MediaDrm;
import android.media.UnsupportedSchemeException;

import java.util.UUID;

public class Class {
    public static void test(UUID uuid) {
        try {
            if (uuid != null) {
                <error descr="Call requires API level 18 (current min is 1): android.media.MediaDrm#MediaDrm">new MediaDrm(uuid)</error>;
            }
        } catch (<error descr="Class requires API level 21 (current min is 1): MediaDrmStateException">MediaDrm.MediaDrmStateException</error> | <error descr="Class requires API level 18 (current min is 1): UnsupportedSchemeException">UnsupportedSchemeException</error> e) {
            e.printStackTrace();
        }
    }
}