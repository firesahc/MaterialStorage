package me.gm.cleaner.client.ui

import android.app.Application
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

class MountAppPickerViewModel(application: Application) : AppListViewModelBase(application) {

    /** 选择页展示全部应用，预取已配置应用图标收益有限，避免额外首屏延迟。 */
    override val shouldPreloadIcons: Boolean = false

    fun loadApps() {
        viewModelScope.launch {
            loadAppsCommon()
        }
    }

    init {
        loadApps()
    }
}
