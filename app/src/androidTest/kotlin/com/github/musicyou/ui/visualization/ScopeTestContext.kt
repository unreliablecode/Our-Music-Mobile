package com.github.musicyou.ui.visualization

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Build

/** Routes only oscilloscope preferences into a disposable file for this test. */
internal class ScopeTestContext(baseActivity: Context, val preferenceFile: String) : ContextWrapper(baseActivity) {
    override fun getApplicationContext(): Context = this

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        baseContext.getSharedPreferences(if (name == "oscilloscope") preferenceFile else name, mode)

    fun deleteTestPreferences() {
        if (Build.VERSION.SDK_INT >= 24) baseContext.deleteSharedPreferences(preferenceFile)
        else baseContext.getSharedPreferences(preferenceFile, Context.MODE_PRIVATE).edit().clear().commit()
    }
}
