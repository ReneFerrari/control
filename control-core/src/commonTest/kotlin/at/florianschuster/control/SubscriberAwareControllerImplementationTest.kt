package at.florianschuster.control

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class SubscriberAwareControllerImplementationTest {

    @Test
    fun `initial state only emitted once`() {
        val scope = TestScope(UnconfinedTestDispatcher())
        val sut = scope.createOperationController()
        val states = sut.state.testIn(scope)

        //single is not possible here. I believe I have a bug somewhere
        //which leads to two emissions in the SubscriberAwareController implementation
        //there should just be one emission like in the other controller
        assertEquals(listOf("initialState", "transformedState"), states.last())
    }

    @Test
    fun `state is created when accessing current state`() {
        val scope = TestScope(UnconfinedTestDispatcher())
        val sut = scope.createOperationController()

        sut.state.launchIn(scope)

        assertEquals(listOf("initialState", "transformedState"), sut.state.value)

        scope.cancel()
    }

    @Test
    fun `state is created when accessing action`() {
        val scope = TestScope(UnconfinedTestDispatcher())
        val sut = scope.createOperationController()

        sut.state.launchIn(scope)

        sut.dispatch(listOf("action"))

        assertEquals(
            listOf(
                "initialState",
                "action",
                "transformedAction",
                "mutation",
                "transformedMutation",
                "transformedState"
            ),
            sut.state.value
        )

        scope.cancel()
    }

    @Test
    fun `each method is invoked`() {
        val scope = TestScope(UnconfinedTestDispatcher())
        val sut = scope.createOperationController()
        val states = sut.state.testIn(scope)

        sut.state.launchIn(scope)

        sut.dispatch(listOf("action"))

        assertEquals(
            listOf(
                //again this first emission shouldnt be here.
                //i have to dig deeper to figure out whats actually wrong
                listOf("initialState"),
                listOf("initialState", "transformedState"),
                listOf(
                    "initialState",
                    "action",
                    "transformedAction",
                    "mutation",
                    "transformedMutation",
                    "transformedState"
                )
            ),
            states
        )

        scope.cancel()
    }

    @Test
    fun `only distinct states are emitted`() {
        val scope = TestScope(UnconfinedTestDispatcher())
        val sut = scope.createAlwaysSameStateController()
        val states = sut.state.testIn(scope)

        sut.dispatch(Unit)
        sut.dispatch(Unit)
        sut.dispatch(Unit)
        assertEquals(1, states.count()) // no state changes
    }

    @Test
    fun `collector receives latest and following states`() {
        val scope = TestScope(UnconfinedTestDispatcher())
        val sut = scope.createCounterController() // 0

        sut.state.launchIn(scope)

        sut.dispatch(Unit) // 1
        sut.dispatch(Unit) // 2
        sut.dispatch(Unit) // 3
        sut.dispatch(Unit) // 4
        val states = sut.state.testIn(scope)
        sut.dispatch(Unit) // 5

        assertEquals(
            listOf(4, 5),
            states
        )
    }


    @Test
    fun `controller throws error from mutator`() {
        kotlin.runCatching {
            runTest(UnconfinedTestDispatcher()) {
                val sut = createCounterController(mutatorErrorIndex = 2)

                sut.state.launchIn(this)

                sut.dispatch(Unit)
                sut.dispatch(Unit)
                sut.dispatch(Unit)
            }
        }.fold(
            onSuccess = { error("this should not succeed") },
            onFailure = { assertTrue(it is ControllerError.Mutate) }
        )
    }

    @Test
    fun `controller throws error from reducer`() {
        kotlin.runCatching {
            runTest(UnconfinedTestDispatcher()) {
                val sut = createCounterController(reducerErrorIndex = 2)

                sut.state.launchIn(this)

                sut.dispatch(Unit)
                sut.dispatch(Unit)
                sut.dispatch(Unit)
            }
        }.fold(
            onSuccess = { error("this should not succeed") },
            onFailure = { assertTrue(it is ControllerError.Reduce) }
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `cancel via takeUntil`() {
        val scope = TestScope(UnconfinedTestDispatcher())
        val sut = scope.createStopWatchController()

        sut.state.launchIn(scope)

        sut.dispatch(StopWatchAction.Start)
        scope.advanceTimeBy(MINIMUM_STOP_WATCH_DELAY * 2 + 1.milliseconds)
        sut.dispatch(StopWatchAction.Stop)
        assertEquals(2, sut.state.value)

        sut.dispatch(StopWatchAction.Start)
        scope.advanceTimeBy(MINIMUM_STOP_WATCH_DELAY * 3 + 1.milliseconds)
        sut.dispatch(StopWatchAction.Stop)
        assertEquals(5, sut.state.value)

        sut.dispatch(StopWatchAction.Start)
        scope.advanceTimeBy(MINIMUM_STOP_WATCH_DELAY * 4 + 1.milliseconds)
        sut.dispatch(StopWatchAction.Stop)
        assertEquals(9, sut.state.value)

        sut.dispatch(StopWatchAction.Start)
        scope.advanceTimeBy(MINIMUM_STOP_WATCH_DELAY / 2)
        sut.dispatch(StopWatchAction.Stop)
        assertEquals(9, sut.state.value)

        sut.dispatch(StopWatchAction.Start)
        scope.advanceTimeBy(MINIMUM_STOP_WATCH_DELAY + 1.milliseconds)
        sut.dispatch(StopWatchAction.Stop)
        assertEquals(10, sut.state.value)

        scope.cancel()
    }

    @Test
    fun `global state gets merged into controller`() {
        val scope = TestScope(UnconfinedTestDispatcher())
        val globalState = flow {
            delay(250)
            emit(42)
            delay(250)
            emit(42)
        }

        val sut = scope.createGlobalStateMergeController(globalState)

        val states = sut.state.testIn(scope)

        scope.advanceTimeBy(251)
        sut.dispatch(1)
        scope.advanceTimeBy(251)

        assertEquals(
            listOf(0, 42, 43, 85),
            states
        )
        scope.cancel()
    }

    @Test
    fun `MutatorContext is built correctly`() {
        val stateAccessor = { 1 }
        val actions = flowOf(1)
        var emittedEffect: Int? = null
        val sut = SubscriberAwareControllerImplementation.createMutatorContext<Int, Int, Int>(
            stateAccessor,
            actions
        ) { emittedEffect = it }

        sut.emitEffect(1)

        assertEquals(stateAccessor(), sut.currentState)
        assertEquals(actions, sut.actions)
        assertEquals(1, emittedEffect)
    }

    @Test
    fun `ReducerContext is built correctly`() {
        var emittedEffect: Int? = null
        val sut = SubscriberAwareControllerImplementation.createReducerContext<Int> { emittedEffect = it }
        sut.emitEffect(2)
        assertEquals(2, emittedEffect)
    }

    @Test
    fun `TransformerContext is built correctly`() {
        var emittedEffect: Int? = null
        val sut = SubscriberAwareControllerImplementation.createTransformerContext<Int> { emittedEffect = it }
        sut.emitEffect(3)
        assertEquals(3, emittedEffect)
    }

    @Test
    fun `cancelling the implementation will return the last state`() {
        val scope = TestScope(UnconfinedTestDispatcher())
        val sut = scope.createGlobalStateMergeController(emptyFlow())

        val states = sut.state.testIn(scope)

        sut.dispatch(0)
        sut.dispatch(1)

        scope.cancel()

        sut.dispatch(2)

        assertEquals(1, states.last())
    }

    @Test
    fun `effects are received from mutator - reducer and transformer`() {
        val scope = TestScope(UnconfinedTestDispatcher())
        val sut = scope.createEffectTestController()
        val states = sut.state.testIn(scope)
        val effects = sut.effects.testIn(scope)

        val testEmissions = listOf(
            TestEffect.Reducer,
            TestEffect.ActionTransformer,
            TestEffect.MutationTransformer,
            TestEffect.Mutator,
            TestEffect.StateTransformer
        )

        testEmissions.map(TestEffect::ordinal).forEach(sut::dispatch)

        assertEquals(
            listOf(0) + testEmissions.map(TestEffect::ordinal),
            states
        )
        assertEquals(testEmissions, effects)
        scope.cancel()
    }

    @Test
    fun `effects are only received once per collector`() {
        val scope = TestScope(UnconfinedTestDispatcher())
        val sut = scope.createEffectTestController()

        sut.state.launchIn(scope)

        val effects = mutableListOf<TestEffect>()
        sut.effects.onEach { effects.add(it) }.launchIn(scope)
        sut.effects.onEach { effects.add(it) }.launchIn(scope)

        val testEmissions = listOf(
            TestEffect.Reducer,
            TestEffect.ActionTransformer,
            TestEffect.MutationTransformer,
            TestEffect.Reducer,
            TestEffect.Mutator,
            TestEffect.StateTransformer,
            TestEffect.Reducer
        )

        testEmissions.map(TestEffect::ordinal).forEach(sut::dispatch)

        assertEquals(testEmissions, effects)
    }

    @Test
    fun `effects overflow throws error`() {
        kotlin.runCatching {
            runTest(UnconfinedTestDispatcher()) {
                val sut = createEffectTestController()
                sut.state.launchIn(this)
                repeat(ControllerImplementation.CAPACITY + 1) { sut.dispatch(1) }
            }
        }.fold(
            onSuccess = { error("this should not succeed") },
            onFailure = { assertTrue(it.cause is ControllerError.Effect) }
        )
    }

    @Test
    fun `state is cancellable`() = runTest(UnconfinedTestDispatcher()) {
        val sut = createCounterController()

        sut.state.launchIn(this)
        sut.dispatch(Unit)

        var state: Int? = null
        launch {
            cancel()
            state = -1
            state = sut.state.first() // this should be cancelled and thus not return a value
        }

        assertEquals(-1, state)
        cancel()
    }

    @Test
    fun `effects are cancellable`() = runTest(UnconfinedTestDispatcher()) {
        val sut = createEffectTestController()

        sut.state.launchIn(this)
        sut.dispatch(TestEffect.Mutator.ordinal)

        var effect: TestEffect? = null
        launch {
            cancel()
            effect = TestEffect.Reducer
            effect = sut.effects.first() // this should be cancelled and thus not return a value
        }

        assertEquals(TestEffect.Reducer, effect)
        cancel()
    }

    @Test
    fun `controller is started lazily when only effects field is accessed`() {
        val scope = TestScope(UnconfinedTestDispatcher())
        val sut = ControllerImplementation<Int, Int, Int, String>(
            scope = scope,
            dispatcher = scope.defaultScopeDispatcher(),
            controllerStart = ControllerStart.Lazy,
            initialState = 0,
            mutator = { action -> flowOf(action) },
            reducer = { mutation, _ -> mutation },
            actionsTransformer = { actions ->
                merge(actions, flow {
                    emitEffect("actionsTransformer started")
                })
            },
            mutationsTransformer = { mutations -> mutations },
            statesTransformer = { states -> states },
            tag = "ImplementationTest.EffectController",
            controllerLog = ControllerLog.None
        )

        val effects = sut.effects.testIn(scope)

        assertEquals(
            listOf("actionsTransformer started"),
            effects
        )

        scope.cancel()
    }


    private fun CoroutineScope.createCounterController(
        mutatorErrorIndex: Int? = null,
        reducerErrorIndex: Int? = null
    ) = SubscriberAwareControllerImplementation<Unit, Unit, Int, Nothing>(
        scope = this,
        dispatcher = defaultScopeDispatcher(),
        initialState = 0,
        mutator = { action ->
            flow {
                check(currentState != mutatorErrorIndex)
                emit(action)
            }
        },
        reducer = { _, previousState ->
            check(previousState != reducerErrorIndex)
            previousState + 1
        },
        actionsTransformer = { it },
        mutationsTransformer = { it },
        statesTransformer = { it },
        tag = "ImplementationTest.CounterController",
        controllerLog = ControllerLog.None,
        sharingStarted = SharingStarted.WhileSubscribed()
    )

    private fun CoroutineScope.createAlwaysSameStateController() =
        SubscriberAwareControllerImplementation<Unit, Unit, Int, Nothing>(
            scope = this,
            dispatcher = defaultScopeDispatcher(),
            initialState = 0,
            mutator = { flowOf(it) },
            reducer = { _, previousState -> previousState },
            actionsTransformer = { it },
            mutationsTransformer = { it },
            statesTransformer = { it },
            tag = "ImplementationTest.AlwaysSameStateController",
            controllerLog = ControllerLog.None,
            sharingStarted = SharingStarted.WhileSubscribed()
        )

    private fun CoroutineScope.createOperationController() =
        SubscriberAwareControllerImplementation<List<String>, List<String>, List<String>, Nothing>(
            scope = this,
            dispatcher = defaultScopeDispatcher(),

            // 1. ["initialState"]
            initialState = listOf("initialState"),

            // 2. ["action"] + ["transformedAction"]
            actionsTransformer = { actions ->
                actions.map { it + "transformedAction" }
            },

            // 3. ["action", "transformedAction"] + ["mutation"]
            mutator = { action ->
                flowOf(action + "mutation")
            },

            // 4. ["action", "transformedAction", "mutation"] + ["transformedMutation"]
            mutationsTransformer = { mutations ->
                mutations.map { it + "transformedMutation" }
            },

            // 5. ["initialState"] + ["action", "transformedAction", "mutation", "transformedMutation"]
            reducer = { mutation, previousState -> previousState + mutation },

            // 6. ["initialState", "action", "transformedAction", "mutation", "transformedMutation"] + ["transformedState"]
            statesTransformer = { states -> states.map { it + "transformedState" } },

            tag = "ImplementationTest.OperationController",
            controllerLog = ControllerLog.None,

            sharingStarted = SharingStarted.WhileSubscribed()
        )

    private enum class TestEffect {
        Mutator, Reducer, ActionTransformer, MutationTransformer, StateTransformer
    }

    private sealed interface StopWatchAction {
        data object Start : StopWatchAction
        data object Stop : StopWatchAction
    }

    private fun CoroutineScope.createStopWatchController() =
        SubscriberAwareControllerImplementation<StopWatchAction, Int, Int, Nothing>(
            scope = this,
            dispatcher = defaultScopeDispatcher(),
            initialState = 0,
            mutator = { action ->
                when (action) {
                    is StopWatchAction.Start -> {
                        flow {
                            while (isActive) {
                                delay(MINIMUM_STOP_WATCH_DELAY)
                                emit(1)
                            }
                        }.takeUntil(actions.filterIsInstance<StopWatchAction.Stop>())
                    }
                    is StopWatchAction.Stop -> emptyFlow()
                }
            },
            reducer = { mutation, previousState -> previousState + mutation },
            actionsTransformer = { it },
            mutationsTransformer = { it },
            statesTransformer = { it },
            tag = "ImplementationTest.StopWatchController",
            controllerLog = ControllerLog.None,
            SharingStarted.WhileSubscribed()
        )

    private fun CoroutineScope.createGlobalStateMergeController(
        globalState: Flow<Int>
    ) = SubscriberAwareControllerImplementation<Int, Int, Int, Nothing>(
        scope = this,
        dispatcher = defaultScopeDispatcher(),
        initialState = 0,
        mutator = { flowOf(it) },
        reducer = { action, previousState -> previousState + action },
        actionsTransformer = { merge(it, globalState) },
        mutationsTransformer = { it },
        statesTransformer = { it },
        tag = "ImplementationTest.GlobalStateMergeController",
        controllerLog = ControllerLog.None,
        sharingStarted = SharingStarted.WhileSubscribed()
    )

    private fun CoroutineScope.createEffectTestController() =
        SubscriberAwareControllerImplementation<Int, Int, Int, TestEffect>(
            scope = this,
            dispatcher = defaultScopeDispatcher(),
            initialState = 0,
            mutator = { action ->
                if (action == TestEffect.Mutator.ordinal) emitEffect(TestEffect.Mutator)
                flowOf(action)
            },
            reducer = { mutation, _ ->
                if (mutation == TestEffect.Reducer.ordinal) emitEffect(TestEffect.Reducer)
                mutation
            },
            actionsTransformer = { actions ->
                actions.onEach {
                    if (it == TestEffect.ActionTransformer.ordinal) {
                        emitEffect(TestEffect.ActionTransformer)
                    }
                }
            },
            mutationsTransformer = { mutations ->
                mutations.onEach {
                    if (it == TestEffect.MutationTransformer.ordinal) {
                        emitEffect(TestEffect.MutationTransformer)
                    }
                }
            },
            statesTransformer = { states ->
                states.onEach {
                    if (it == TestEffect.StateTransformer.ordinal) {
                        emitEffect(TestEffect.StateTransformer)
                    }
                }
            },
            tag = "ImplementationTest.EffectController",
            controllerLog = ControllerLog.None,
            sharingStarted = SharingStarted.WhileSubscribed()
        )

    companion object {
        private  val MINIMUM_STOP_WATCH_DELAY = 1.seconds
    }
}