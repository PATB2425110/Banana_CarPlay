package com.dbtool.carplay.bridge.update

import com.dbtool.carplay.api.update.*

/** This product has no remote update/check/download/install implementation. */
object DisabledProductUpdates : CarUpdateHost {
    override fun state() = CarUpdateState(silentUpdateEnabled = false)
    override fun checkAsync(manual: Boolean, onState: (CarUpdateState) -> Unit) = Unit
    override fun maybeAutoCheck(onState: (CarUpdateState) -> Unit) = Unit
    override fun download(onState: (CarUpdateState) -> Unit, silent: Boolean) = false
    override fun install() = CarInstallOutcome.FAILED
    override fun notifyIdleWindow() = Unit
    override fun setSilentUpdateEnabled(enabled: Boolean) = state()
    override fun dismiss() = state()
    override fun reportStatus(status: String, detail: String) = Unit
}
