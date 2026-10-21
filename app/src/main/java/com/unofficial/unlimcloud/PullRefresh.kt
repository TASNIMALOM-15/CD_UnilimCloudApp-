package com.unofficial.unlimcloud

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * নিচে টেনে রিফ্রেশ। Material-এর পরীক্ষামূলক (experimental) API ব্যবহার না করে
 * Compose-এর স্থিতিশীল NestedScrollConnection দিয়ে লেখা, যাতে লাইব্রেরি সংস্করণ বদলালেও না ভাঙে।
 * ভেতরের তালিকা একদম উপরে থাকলে এবং আঙুলে নিচে টানলে কাজ করে।
 */
@Composable
fun PullRefreshBox(
    modifier: Modifier = Modifier,
    onRefresh: () -> Unit,
    content: @Composable BoxScope.() -> Unit
) {
    val density = LocalDensity.current
    val thresholdPx = with(density) { 80.dp.toPx() }
    val maxPullPx = thresholdPx * 1.6f

    var pull by remember { mutableStateOf(0f) }
    var refreshing by remember { mutableStateOf(false) }
    val currentOnRefresh by rememberUpdatedState(onRefresh)

    val connection = remember(thresholdPx) {
        object : NestedScrollConnection {
            // টানা অবস্থায় উপরে ঠেললে আগে টানা অংশ ফিরিয়ে নেওয়া হয়, তারপর তালিকা স্ক্রল করে
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (!refreshing && pull > 0f && available.y < 0f) {
                    val newPull = (pull + available.y).coerceAtLeast(0f)
                    val used = newPull - pull
                    pull = newPull
                    return Offset(0f, used)
                }
                return Offset.Zero
            }

            // তালিকা উপরে পৌঁছে গেছে, তবু আঙুল নিচে টানছে: টান জমা হয়
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (!refreshing && source == NestedScrollSource.Drag && available.y > 0f) {
                    pull = (pull + available.y * 0.5f).coerceAtMost(maxPullPx)
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            // আঙুল ছাড়লে: যথেষ্ট টানা হলে রিফ্রেশ শুরু
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (!refreshing && pull >= thresholdPx) {
                    refreshing = true
                    currentOnRefresh()
                }
                pull = 0f
                return Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                pull = 0f
                return Velocity.Zero
            }
        }
    }

    LaunchedEffect(refreshing) {
        if (refreshing) {
            delay(1000)
            refreshing = false
        }
    }

    Box(modifier.nestedScroll(connection)) {
        content()
        if (refreshing || pull > 0f) {
            val y = if (refreshing) thresholdPx * 0.5f else pull * 0.6f
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .offset { IntOffset(0, y.toInt()) }
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center
            ) {
                if (refreshing) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
                } else {
                    CircularProgressIndicator(
                        progress = (pull / thresholdPx).coerceIn(0f, 1f),
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.5.dp
                    )
                }
            }
        }
    }
}
