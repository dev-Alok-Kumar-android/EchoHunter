package com.appsbyalok.echohunter

import android.content.Context
import android.content.SharedPreferences

object SaveManager {
    private lateinit var prefs: SharedPreferences

    var totalData: Int = 0
        private set

    fun init(context: Context) {
        prefs = context.getSharedPreferences("EchoSaveInfo", Context.MODE_PRIVATE)
        totalData = prefs.getInt("totalData", 0)
    }

    fun addData(amount: Int) {
        totalData += amount
        prefs.edit().putInt("totalData", totalData).apply()
    }
}