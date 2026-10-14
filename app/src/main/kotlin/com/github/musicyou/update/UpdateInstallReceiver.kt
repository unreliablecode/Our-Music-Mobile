package com.github.musicyou.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build

class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmation = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirmation == null) {
                    UpdateCoordinator.get(context).installationResult(false, "Android did not provide an installation confirmation")
                } else {
                    try { context.startActivity(confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    catch (exception: Exception) { UpdateCoordinator.get(context).installationResult(false, exception.message ?: "Could not open installation confirmation") }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> UpdateCoordinator.get(context).installationResult(true, "Our Music is up to date.")
            else -> UpdateCoordinator.get(context).installationResult(false, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Android could not install the update")
        }
    }
}
