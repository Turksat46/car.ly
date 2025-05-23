package com.turksat46.carlydashboard.other

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

object NavigationInfoHolder {
    private val _currentNavigationInfo = MutableLiveData<NavigationInfo?>()
    val currentNavigationInfo: LiveData<NavigationInfo?> = _currentNavigationInfo

    fun updateNavigationInfo(info: NavigationInfo?) {
        _currentNavigationInfo.postValue(info)
    }

    fun clearNavigationInfo() {
        _currentNavigationInfo.postValue(NavigationInfo(isActive = false)) // Oder einfach null, je nach Preferenz
    }
}