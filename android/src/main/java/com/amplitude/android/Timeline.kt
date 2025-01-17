package com.amplitude.android

import com.amplitude.core.Storage
import com.amplitude.core.events.BaseEvent
import com.amplitude.core.platform.Timeline
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

class Timeline : Timeline() {
    private val eventMessageChannel: Channel<EventQueueMessage> = Channel(Channel.UNLIMITED)
    var sessionId: Long = -1
        private set
    internal var lastEventId: Long = 0
    var lastEventTime: Long = -1

    internal fun start() {
        amplitude.amplitudeScope.launch(amplitude.storageIODispatcher) {
            amplitude.isBuilt.await()

            sessionId = amplitude.storage.read(Storage.Constants.PREVIOUS_SESSION_ID)?.toLong() ?: -1
            lastEventId = amplitude.storage.read(Storage.Constants.LAST_EVENT_ID)?.toLong() ?: 0
            lastEventTime = amplitude.storage.read(Storage.Constants.LAST_EVENT_TIME)?.toLong() ?: -1

            for (message in eventMessageChannel) {
                processEventMessage(message)
            }
        }
    }

    internal fun stop() {
        this.eventMessageChannel.cancel()
    }

    override fun process(incomingEvent: BaseEvent) {
        if (incomingEvent.timestamp == null) {
            incomingEvent.timestamp = System.currentTimeMillis()
        }

        eventMessageChannel.trySend(EventQueueMessage(incomingEvent, (amplitude as Amplitude).inForeground))
    }

    private suspend fun processEventMessage(message: EventQueueMessage) {
        val event = message.event
        var sessionEvents: Iterable<BaseEvent>? = null
        val eventTimestamp = event.timestamp!!
        var skipEvent = false

        if (event.eventType == Amplitude.START_SESSION_EVENT) {
            if (event.sessionId < 0) { // dummy start_session event
                skipEvent = true
                sessionEvents = startNewSessionIfNeeded(eventTimestamp)
            } else {
                setSessionId(event.sessionId)
                refreshSessionTime(eventTimestamp)
            }
        } else if (event.eventType == Amplitude.END_SESSION_EVENT) {
            // do nothing
        } else {
            if (!message.inForeground) {
                sessionEvents = startNewSessionIfNeeded(eventTimestamp)
            } else {
                refreshSessionTime(eventTimestamp)
            }
        }

        if (!skipEvent && event.sessionId < 0) {
            event.sessionId = sessionId
        }

        val savedLastEventId = lastEventId

        sessionEvents ?. let {
            it.forEach { e ->
                e.eventId ?: let {
                    val newEventId = lastEventId + 1
                    e.eventId = newEventId
                    lastEventId = newEventId
                }
            }
        }

        if (!skipEvent) {
            event.eventId ?: let {
                val newEventId = lastEventId + 1
                event.eventId = newEventId
                lastEventId = newEventId
            }
        }

        if (lastEventId > savedLastEventId) {
            amplitude.storage.write(Storage.Constants.LAST_EVENT_ID, lastEventId.toString())
        }

        sessionEvents ?. let {
            it.forEach { e ->
                super.process(e)
            }
        }

        if (!skipEvent) {
            super.process(event)
        }
    }

    private suspend fun startNewSessionIfNeeded(timestamp: Long): Iterable<BaseEvent>? {
        if (inSession() && isWithinMinTimeBetweenSessions(timestamp)) {
            refreshSessionTime(timestamp)
            return null
        }
        return startNewSession(timestamp)
    }

    private suspend fun setSessionId(timestamp: Long) {
        sessionId = timestamp
        amplitude.storage.write(Storage.Constants.PREVIOUS_SESSION_ID, sessionId.toString())
    }

    private suspend fun startNewSession(timestamp: Long): Iterable<BaseEvent> {
        val sessionEvents = mutableListOf<BaseEvent>()
        val trackingSessionEvents = (amplitude.configuration as Configuration).trackingSessionEvents

        // end previous session
        if (trackingSessionEvents && inSession()) {
            val sessionEndEvent = BaseEvent()
            sessionEndEvent.eventType = Amplitude.END_SESSION_EVENT
            sessionEndEvent.timestamp = if (lastEventTime > 0) lastEventTime else null
            sessionEndEvent.sessionId = sessionId
            sessionEvents.add(sessionEndEvent)
        }

        // start new session
        setSessionId(timestamp)
        refreshSessionTime(timestamp)
        if (trackingSessionEvents) {
            val sessionStartEvent = BaseEvent()
            sessionStartEvent.eventType = Amplitude.START_SESSION_EVENT
            sessionStartEvent.timestamp = timestamp
            sessionStartEvent.sessionId = sessionId
            sessionEvents.add(sessionStartEvent)
        }

        return sessionEvents
    }

    private suspend fun refreshSessionTime(timestamp: Long) {
        if (!inSession()) {
            return
        }
        lastEventTime = timestamp
        amplitude.storage.write(Storage.Constants.LAST_EVENT_TIME, lastEventTime.toString())
    }

    private fun isWithinMinTimeBetweenSessions(timestamp: Long): Boolean {
        val sessionLimit: Long = (amplitude.configuration as Configuration).minTimeBetweenSessionsMillis
        return timestamp - lastEventTime < sessionLimit
    }

    private fun inSession(): Boolean {
        return sessionId >= 0
    }
}

data class EventQueueMessage(
    val event: BaseEvent,
    val inForeground: Boolean
)
