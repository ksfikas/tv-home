package io.github.ksfikas.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * Phone remote "Turn on": an invisible screen that Android lets turn the display on
 * (turnScreenOn), which wakes the box from standby; it then hands over to home.
 */
public class WakeActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTurnScreenOn(true);
        setShowWhenLocked(true);
        startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setClass(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        finish();
    }
}
