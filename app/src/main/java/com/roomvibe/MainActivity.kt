package com.roomvibe

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.roomvibe.ui.CompareScreen
import com.roomvibe.ui.SensorDetailScreen
import com.roomvibe.ui.SensorListScreen
import com.roomvibe.ui.theme.ThermoLogTheme
import com.roomvibe.viewmodel.CompareViewModel
import com.roomvibe.viewmodel.SensorDetailViewModel
import com.roomvibe.viewmodel.SensorListViewModel
import kotlinx.coroutines.launch

private const val PAGE_SENSORS = 0
private const val PAGE_COMPARE = 1
private const val PAGE_COUNT = 2

private val PAGE_NAMES = listOf("Sensors", "Compare")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ThermoLogTheme {
                val navController = rememberNavController()
                NavHost(navController, startDestination = "sensors") {
                    composable("sensors") {
                        HomePager(
                            onOpenSensor = { address -> navController.navigate("sensor/${address}") }
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

/**
 * The two top-level screens sit side by side: the sensor list, and the compare
 * chart one swipe to its right. Both also have buttons that move between them,
 * so the gesture isn't the only way to find the second page.
 */
@Composable
private fun HomePager(onOpenSensor: (String) -> Unit) {
    val pagerState = rememberPagerState(pageCount = { PAGE_COUNT })
    val scope = rememberCoroutineScope()
    val listVm: SensorListViewModel = viewModel()
    val compareVm: CompareViewModel = viewModel()

    fun goTo(page: Int) = scope.launch { pagerState.animateScrollToPage(page) }

    Box(Modifier.fillMaxSize()) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            when (page) {
                PAGE_SENSORS -> SensorListScreen(
                    viewModel = listVm,
                    onOpenSensor = onOpenSensor,
                    onOpenCompare = { goTo(PAGE_COMPARE) }
                )
                else -> CompareScreen(
                    viewModel = compareVm,
                    onBack = { goTo(PAGE_SENSORS) }
                )
            }
        }

        PageDots(
            current = pagerState.currentPage,
            count = PAGE_COUNT,
            onSelect = { goTo(it) },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 4.dp)
        )
    }
}

/**
 * Says there is a second screen and which one you're on — without it the compare
 * page is invisible until someone happens to swipe. Tappable too, since a dot
 * that only reports state wastes a target.
 */
@Composable
private fun PageDots(
    current: Int,
    count: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = Color.Black.copy(alpha = 0.45f)
    ) {
        Row(
            Modifier.padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(count) { page ->
                val isCurrent = page == current
                val name = PAGE_NAMES.getOrElse(page) { "Page ${page + 1}" }
                // The dot is small; the touch target around it is not.
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clickable(onClick = { onSelect(page) }, onClickLabel = name)
                        .semantics {
                            contentDescription = name
                            selected = isCurrent
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        Modifier
                            .size(if (isCurrent) 9.dp else 7.dp)
                            .clip(CircleShape)
                            .background(
                                if (isCurrent) MaterialTheme.colorScheme.primary
                                else Color.White.copy(alpha = 0.35f)
                            )
                    )
                }
            }
        }
    }
}
