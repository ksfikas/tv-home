package com.mihir.tvhome;

import android.app.admin.DeviceAdminReceiver;

/**
 * Lets the sleep timer put the box to standby (DevicePolicyManager.lockNow). Its only policy is
 * "force-lock". Turned on once over ADB:
 *   adb shell dpm set-active-admin com.mihir.tvhome/.SleepAdmin
 * and off again with  adb shell dpm remove-active-admin com.mihir.tvhome/.SleepAdmin
 */
public class SleepAdmin extends DeviceAdminReceiver { }
