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
import com.triptracker.app.data.local.TripDatabase
import com.triptracker.app.data.repository.RoomTripRepository
import com.triptracker.app.presentation.TripTrackerScreen
import com.triptracker.app.presentation.TripViewModel
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
            val model: TripViewModel = viewModel(factory = viewModelFactory {
                initializer { TripViewModel(app.repository, Clock.systemDefaultZone()) }
            })
            TripTrackerScreen(model)
        }
    }
}

class TripTrackerApplication : android.app.Application() {
    private val database by lazy { Room.databaseBuilder(this, TripDatabase::class.java, "triptracker.db").build() }
    val repository by lazy { RoomTripRepository(database, Clock.systemDefaultZone()) }
}
