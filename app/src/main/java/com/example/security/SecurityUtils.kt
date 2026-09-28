package com.example.security

import java.io.File

object SecurityUtils {
    fun isDeviceRooted(): Boolean {
        val paths = arrayOf(
            "/system/app/Superuser.apk",
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/su"
        )
        try {
            for (path in paths) {
                if (File(path).exists()) return true
            }
        } catch (e: Exception) {
            // Ignore
        }
        return false
    }
}
