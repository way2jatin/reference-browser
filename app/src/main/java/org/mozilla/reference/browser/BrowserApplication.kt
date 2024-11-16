/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import mozilla.components.browser.state.action.SystemAction
import mozilla.components.concept.push.PushProcessor
import mozilla.components.support.base.log.Log
import mozilla.components.support.base.log.logger.Logger
import mozilla.components.support.base.log.sink.AndroidLogSink
import mozilla.components.support.ktx.android.content.isMainProcess
import mozilla.components.support.ktx.android.content.runOnlyInMainProcess
import mozilla.components.support.rusthttp.RustHttpConfig
import mozilla.components.support.rustlog.RustLog
import org.mozilla.reference.browser.ext.isCrashReportActive
import org.mozilla.reference.browser.push.PushFxaIntegration
import org.mozilla.reference.browser.push.WebPushEngineIntegration
import java.util.concurrent.TimeUnit

open class BrowserApplication : Application() {
    val components by lazy { Components(this) }
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)


    override fun onCreate() {
        super.onCreate()

        applicationScope.launch {
            setupCrashReporting(this@BrowserApplication)
            RustHttpConfig.setClient(lazy { components.core.client })
            setupLogging()
        }


        if (!isMainProcess()) {
            // If this is not the main process then do not continue with the initialization here. Everything that
            // follows only needs to be done in our app's main process and should not be done in other processes like
            // a GeckoView child process or the crash handling process. Most importantly we never want to end up in a
            // situation where we create a GeckoRuntime from the Gecko child process (
            return
        }

        components.core.engine.warmUp()
        restoreBrowserState()

        initializeComponents()
    }

    private fun initializeComponents() {
        components.push.feature?.let {
            Logger.info("AutoPushFeature is configured, initializing it...")

            PushProcessor.install(it)

            // WebPush integration to observe and deliver push messages to engine.
            WebPushEngineIntegration(components.core.engine, it).start()

            // Perform a one-time initialization of the account manager if a message is received.
            PushFxaIntegration(it, lazy { components.backgroundServices.accountManager }).launch()

            // Initialize the push feature and service.
            it.initialize()
        }
        applicationScope.launch {
            components.core.fileUploadsDirCleaner.cleanUploadsDirectory()
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        runOnlyInMainProcess {
            components.core.store.dispatch(SystemAction.LowMemoryAction(level))
            components.core.icons.onTrimMemory(level)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onLowMemory() {
        super.onLowMemory()
        applicationScope.cancel("onLowMemory() called")
    }

    private fun restoreBrowserState() {
        applicationScope.launch(Dispatchers.Main) {
            val store = components.core.store
            val sessionStorage = components.core.sessionStorage

            components.useCases.tabsUseCases.restore(sessionStorage)
            sessionStorage.autoSave(store)
                .periodicallyInForeground(interval = 30, unit = TimeUnit.SECONDS)
                .whenGoingToBackground()
                .whenSessionsChange()
        }
    }

    companion object {
        const val NON_FATAL_CRASH_BROADCAST = "org.mozilla.reference.browser"
    }
}

private fun setupLogging() {
    // We want the log messages of all builds to go to Android logcat
    Log.addSink(AndroidLogSink())
    RustLog.enable()
}

private fun setupCrashReporting(application: BrowserApplication) {
    if (isCrashReportActive) {
        application
            .components
            .analytics
            .crashReporter.install(application)
    }
}
