package com.thermolog

import android.app.Application
import com.thermolog.data.AppDatabase

class App : Application() {
    val database by lazy { AppDatabase.getInstance(this) }
}
