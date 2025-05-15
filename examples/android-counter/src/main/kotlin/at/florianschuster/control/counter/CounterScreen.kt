package at.florianschuster.control.counter

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted

@Composable
internal fun CounterScreen(
    scope: CoroutineScope = rememberCoroutineScope(),
    controller: CounterController = remember(scope) { scope.createCounterController(sharingStarted = SharingStarted.WhileSubscribedOrRetained) },
    showNext: () -> Unit
) {
    val state by controller.state.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
    CounterView(
        state = state,
        dispatch = controller::dispatch,
        showNext = showNext
    )
}

@Composable
private fun CounterView(
    state: CounterState,
    dispatch: (CounterAction) -> Unit = {},
    showNext: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White),
        contentAlignment = Alignment.Center
    ) {
        Image(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 16.dp)
                .statusBarsPadding(),
            painter = painterResource(R.mipmap.ic_launcher_foreground),
            contentDescription = null,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                modifier = Modifier.semantics { contentDescription = "decrement" },
                enabled = !state.loading,
                onClick = { dispatch(CounterAction.Decrement) },
            ) { Text("-") }
            Spacer(modifier = Modifier.width(24.dp))
            Text(
                modifier = Modifier.semantics { contentDescription = "value" },
                text = "Value: ${state.value}"
            )
            Spacer(modifier = Modifier.width(24.dp))
            Button(
                modifier = Modifier.semantics { contentDescription = "increment" },
                enabled = !state.loading,
                onClick = { dispatch(CounterAction.Increment) },
            ) { Text("+") }
        }
        Button(onClick = showNext) { Text("Show other activity") }
        if (state.loading) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp)
                    .semantics { contentDescription = "loading" },
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview() {
    MaterialTheme {
        CounterView(
            state = CounterState(value = 1, loading = false),
            showNext = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview_Loading() {
    MaterialTheme {
        CounterView(
            state = CounterState(value = 2, loading = true),
            showNext = {}
        )
    }
}
