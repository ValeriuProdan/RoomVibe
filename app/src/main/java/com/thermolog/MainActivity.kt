package com.thermolog

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.thermolog.ui.SensorDetailScreen
import com.thermolog.ui.SensorListScreen
import com.thermolog.ui.theme.ThermoLogTheme
import com.thermolog.viewmodel.SensorDetailViewModel
import com.thermolog.viewmodel.SensorListViewModel

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
