package com.huangkai.sensors_android

import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import android.os.Message
import android.support.v4.app.DialogFragment

class ErrorDialog : DialogFragment() {
    private val ARG_MESSAGE = "message"

    override fun onCreateDialog(savedInstanceState: Bundle?): AlertDialog =
            AlertDialog.Builder(activity)
                .setMessage(arguments?.getString(ARG_MESSAGE))
                .setPositiveButton(android.R.string.ok) {_, _ ->
                    activity?.finish()
                }
                .create()

    companion object {
        fun newInstance(message: String) = ErrorDialog().apply {
            arguments = Bundle().apply { putString(ARG_MESSAGE, message) }
        }
    }
}
