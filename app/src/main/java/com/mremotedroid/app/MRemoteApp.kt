package com.mremotedroid.app

import android.app.Application
import com.mremotedroid.app.data.db.AppDatabase
import com.mremotedroid.app.data.repo.ConnectionRepository

class MRemoteApp : Application() {
    lateinit var repository: ConnectionRepository
        private set

    override fun onCreate() {
        super.onCreate()
        val db = AppDatabase.get(this)
        repository = ConnectionRepository(db.nodeDao())
    }
}
