package com.huangkai.sensors_android

import android.app.AlertDialog
import android.os.Bundle
import android.support.v4.app.DialogFragment

class ConfirmationDialog : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?) =
            AlertDialog.Builder(activity)
                .setMessage(R.string.permission_request)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    parentFragment?.requestPermissions(VIDEO_PERMISSIONS, REQUEST_VIDEO_PERMISSIONS)
                }
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    parentFragment?.activity?.finish()
                }
                .create()
}
