package com.fuelize.code;

import android.app.admin.DeviceAdminReceiver;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;

public class MyAdmin extends DeviceAdminReceiver {

    @SuppressWarnings("unused")
    public static void RemoveKioskMode(Context context) {
        ComponentName devAdminReceiver = new ComponentName(context, MyAdmin.class);
        DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        dpm.removeActiveAdmin(devAdminReceiver);
    }
}
