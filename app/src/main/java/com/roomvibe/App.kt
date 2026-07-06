package com.roomvibe

import android.app.Application
import com.roomvibe.data.AppDatabase

class App : Application() {
    val database by lazy { AppDatabase.getInstance(this) }
}
