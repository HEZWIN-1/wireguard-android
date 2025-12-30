/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.wireguard.android.updater

import android.view.View
import com.google.android.material.snackbar.BaseTransientBottomBar
import com.google.android.material.snackbar.Snackbar
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class SnackbarUpdateShower(private val fragment: Fragment) {

    private class SwapableSnackbar(fragment: Fragment, view: View, anchor: View?) {
        private val statusSnackbar = makeSnackbar(fragment, view, anchor)
        private var showingStatus = false

        private fun makeSnackbar(fragment: Fragment, view: View, anchor: View?): Snackbar {
            val snackbar = Snackbar.make(fragment.requireContext(), view, "", Snackbar.LENGTH_INDEFINITE)
            if (anchor != null) snackbar.anchorView = anchor
            snackbar.setTextMaxLines(6)
            snackbar.behavior = object : BaseTransientBottomBar.Behavior() {
                override fun canSwipeDismissView(child: View): Boolean = false
            }
            return snackbar
        }

        fun showText(text: String) {
            statusSnackbar.setText(text)
            if (!showingStatus) {
                statusSnackbar.show()
                showingStatus = true
            }
        }

        fun dismiss() {
            statusSnackbar.dismiss()
            showingStatus = false
        }
    }

    fun attach(view: View, anchor: View?) {
        val snackbar = SwapableSnackbar(fragment, view, anchor)
        val context = fragment.requireContext()

        Updater.state.onEach { progress ->
            when (progress) {
                is Updater.Progress.Complete -> snackbar.dismiss()
                is Updater.Progress.Downloading -> snackbar.showText("Downloading update...")
                is Updater.Progress.Installing -> snackbar.showText("Installing update...")
                is Updater.Progress.Failure -> snackbar.showText("Update failed: ${progress.error.message}")
            }
        }.launchIn(fragment.lifecycleScope)
    }
}
