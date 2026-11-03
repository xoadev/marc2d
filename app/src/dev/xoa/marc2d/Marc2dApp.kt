package dev.xoa.marc2d

import android.app.Application

class Marc2dApp : Application() {
	val runtime: PanelRuntime by lazy { PanelRuntime(this) }
}
