package me.gm.cleaner.client.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import me.gm.cleaner.BuildConfig
import me.gm.cleaner.client.LaunchReason
import me.gm.cleaner.client.ServerStateMachine

class AppListViewModel(application: Application) : AppListViewModelBase(application) {
    /** Expose queryText changes for logging (debug only). */
    val queryTextFlow: Flow<String> get() = _queryTextFlow

    /** 在飞的加载任务：init 与 onResume 都会调 loadApps，不必并发跑两遍全量。 */
    private var loadJob: Job? = null

    fun loadApps() {
        if (loadJob?.isActive == true) {
            if (BuildConfig.DEBUG) Log.i("CleanerTest", "AppListViewModel.loadApps: already running, skipped")
            return
        }
        loadJob = viewModelScope.launch {
            if (BuildConfig.DEBUG) Log.i("CleanerTest", "AppListViewModel.loadApps: start")
            _appsFlow.value = AppListState.Loading
            // 先出列表：服务不可用时走本地降级，不把列表卡在服务恢复之后。
            val isFullList = loadAppsCommon()
            val recovered = ServerStateMachine.recoverIfTargetRunning(
                getApplication(), LaunchReason.RECOVERY
            )
            if (AppListLoadPlan.shouldReloadAfterRecovery(isFullList, recovered)) {
                loadAppsCommon()
            }
        }
    }

    init {
        loadApps()
    }
}

sealed class AppListState {
    data object Loading : AppListState()
    data class Done(val list: List<AppListModel>) : AppListState()
    data class Error(val message: String) : AppListState()
}
