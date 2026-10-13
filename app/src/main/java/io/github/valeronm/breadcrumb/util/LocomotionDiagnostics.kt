package io.github.valeronm.breadcrumb.util

import io.github.valeronm.breadcrumb.locomotion.EngineDiagnosticEvent
import io.github.valeronm.breadcrumb.locomotion.EngineDiagnosticJournal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** App-facing observable view of the same bounded journal the engine appends to. */
object LocomotionDiagnostics {
    private val journal = EngineDiagnosticJournal()
    private val _events = MutableStateFlow(emptyList<EngineDiagnosticEvent>())
    val events: StateFlow<List<EngineDiagnosticEvent>> = _events

    @Synchronized
    fun record(event: EngineDiagnosticEvent) {
        journal.append(event)
        _events.value = journal.recent()
    }

    @Synchronized
    fun clear() {
        journal.clear()
        _events.value = emptyList()
    }
}
