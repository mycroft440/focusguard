package com.focusguard.usage

import com.focusguard.database.BlockSession
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UsageImpactRouterTest {
    private val now = 1_000_000L

    private fun session(
        type: String = "TIME",
        active: Boolean = true,
        startTime: Long = now - 10_000L,
        endTime: Long? = now + 10_000L
    ) = BlockSession(
        id = 7,
        startTime = startTime,
        endTime = endTime,
        isActive = active,
        sessionType = type,
        isFixed24h = true
    )

    @Test
    fun `active time session is eligible for impact metrics`() {
        assertThat(UsageImpactRouter.isTimedSessionCandidate(session(), now)).isTrue()
    }

    @Test
    fun `password session never enters timed impact route`() {
        assertThat(
            UsageImpactRouter.isTimedSessionCandidate(session(type = "PASSWORD"), now)
        ).isFalse()
    }

    @Test
    fun `expired time session does not enter impact route`() {
        assertThat(
            UsageImpactRouter.isTimedSessionCandidate(
                session(endTime = now),
                now
            )
        ).isFalse()
    }

    @Test
    fun `future time session does not enter impact route`() {
        assertThat(
            UsageImpactRouter.isTimedSessionCandidate(
                session(startTime = now + 1L),
                now
            )
        ).isFalse()
    }

    @Test
    fun `inactive time session does not enter impact route`() {
        assertThat(
            UsageImpactRouter.isTimedSessionCandidate(session(active = false), now)
        ).isFalse()
    }
}
