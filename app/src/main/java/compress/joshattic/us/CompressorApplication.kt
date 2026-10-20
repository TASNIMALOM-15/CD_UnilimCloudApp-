package compress.joshattic.us

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/**
 * The ViewModel lives here (not in the Activity), so closing / swiping away the
 * screen does NOT stop a running compression. The screen just re-attaches later.
 */
class CompressorApplication : Application(), ViewModelStoreOwner {
    private val appViewModelStore = ViewModelStore()
    override val viewModelStore: ViewModelStore
        get() = appViewModelStore
}
