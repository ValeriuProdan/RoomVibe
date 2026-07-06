package com.roomvibe

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.roomvibe.ui.SensorDetailScreen
import com.roomvibe.ui.SensorListScreen
import com.roomvibe.ui.theme.ThermoLogTheme
import com.roomvibe.viewmodel.SensorDetailViewModel
import com.roomvibe.viewmodel.SensorListViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ThermoLogTheme {
                val navController = rememberNavController()
                NavHost(navController, startDestination = "sensors") {
                    composable("sensors") {
                        val vm: SensorListViewModel = viewModel()
                        SensorListScreen(
                            viewModel = vm,
                            onOpenSensor = { address ->
                                navController.navigate("sensor/${address}")
                            }
                        )
                    }
                    composable(
                        route = "sensor/{address}",
                        arguments = listOf(navArgument("address") { type = NavType.StringType })
                    ) {
                        val vm: SensorDetailViewModel = viewModel()
                        SensorDetailScreen(
                            viewModel = vm,
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }
}
