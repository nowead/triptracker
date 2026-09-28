package com.triptracker.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.room.Room
import com.triptracker.app.data.local.MasterSeed
import com.triptracker.app.data.local.TripDatabase
import com.triptracker.app.data.repository.RoomTripRepository
import com.triptracker.app.presentation.ManagementScreen
import com.triptracker.app.presentation.ManagementViewModel
import java.time.Clock

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.WHITE, android.graphics.Color.WHITE),
        )
        val app = application as TripTrackerApplication
        setContent {
            val model: ManagementViewModel = viewModel(factory = viewModelFactory {
                initializer { ManagementViewModel(app.repository, app.repository, Clock.systemDefaultZone()) }
            })
            ManagementScreen(model)
        }
    }
}

class TripTrackerApplication : android.app.Application() {
    private val database by lazy {
        Room.databaseBuilder(this, TripDatabase::class.java, "triptracker.db")
            .addMigrations(TripDatabase.MIGRATION_1_2, TripDatabase.MIGRATION_2_3)
            .addCallback(MasterSeed.callback { assets.open("initial_masters.json").bufferedReader().use { it.readText() } })
            .build()
    }
    val repository by lazy { RoomTripRepository(database, Clock.systemDefaultZone()) }
}
