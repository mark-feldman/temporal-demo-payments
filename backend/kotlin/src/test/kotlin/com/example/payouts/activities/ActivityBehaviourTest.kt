package com.example.payouts.activities

import com.example.payouts.model.activity.BankStatusProbeRequest
import com.example.payouts.model.activity.ReverseRailRequest
import com.example.payouts.model.activity.SubmitToRailRequest
import com.example.payouts.model.activity.ValidateFxQuoteRequest
import com.example.payouts.model.activity.ValidatePayoutRequest
import com.example.payouts.model.domain.BankStatus
import com.example.payouts.model.domain.Money
import com.example.payouts.model.domain.Rail
import com.example.payouts.model.domain.Region
import com.example.payouts.scenario.Behavior
import com.example.payouts.scenario.ScenarioConfig
import com.example.payouts.scenario.ScenarioStore
import com.example.payouts.support.productionDataConverter
import com.example.payouts.support.scenarioStoreIn
import io.temporal.activity.ActivityOptions
import io.temporal.activity.setRetryOptions
import io.temporal.client.WorkflowClientOptions
import io.temporal.failure.ApplicationFailure
import io.temporal.testing.TestActivityEnvironment
import io.temporal.testing.TestEnvironmentOptions
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The real activity implementations, exercised through TestActivityEnvironment -- which
 * supplies the ActivityExecutionContext they need in order to read `info.attempt` at all.
 *
 * Two limits of this harness, both visible in TestActivityEnvironmentInternal:
 *
 *  1. It builds the PollActivityTaskQueueResponse without ever calling `setAttempt`, so
 *     `info.attempt` is the proto default, zero, not 1 as it would be on a real server.
 *  2. It contains no retry loop. The RetryOptions on the stub are not simulated; the activity
 *     is invoked exactly once, whatever the policy says.
 *
 * So anything attempt-driven -- the polling loop resolving after N asks, a transient failure
 * clearing on the third try -- is covered by the workflow tests, where the test server drives
 * the retries. What belongs here is the per-invocation logic: the fixed references, the rate
 * table, and the shape of the failures thrown.
 *
 * These run against the genuine `ActivityMock`, delay and all, so the file stays short: each
 * call spends 1-2.5 real seconds.
 */
class ActivityBehaviourTest {

    @TempDir
    lateinit var tmp: Path

    private lateinit var env: TestActivityEnvironment
    private lateinit var scenarios: ScenarioStore
    private lateinit var mock: ActivityMock

    @BeforeEach
    fun setUp() {
        env = TestActivityEnvironment.newInstance(
            TestEnvironmentOptions.newBuilder()
                .setWorkflowClientOptions(
                    WorkflowClientOptions.newBuilder()
                        .setDataConverter(productionDataConverter())
                        .build(),
                )
                .build(),
        )
        scenarios = scenarioStoreIn(tmp)
        mock = ActivityMock(scenarios)
    }

    @AfterEach
    fun tearDown() {
        env.close()
    }

    private inline fun <reified T> stub(impl: Any): T {
        env.registerActivitiesImplementations(impl)
        return env.newActivityStub(
            T::class.java,
            // A retry policy is set for completeness only -- this harness does not act on it.
            ActivityOptions {
                setStartToCloseTimeout(Duration.ofSeconds(30))
                setRetryOptions {
                    setInitialInterval(Duration.ofMillis(10))
                    setBackoffCoefficient(1.0)
                    setMaximumAttempts(1)
                }
            },
        )
    }

    /** Unwraps whatever the SDK wrapped the activity's failure in. */
    private fun applicationFailureFrom(block: () -> Unit): ApplicationFailure {
        try {
            block()
        } catch (e: Exception) {
            var cause: Throwable? = e
            while (cause != null) {
                if (cause is ApplicationFailure) return cause
                cause = cause.cause
            }
            fail("expected an ApplicationFailure, got $e")
        }
        fail("expected a failure")
    }

    @Test
    fun `validation passes and reports what it checked`() {
        val validation = stub<ValidationActivities>(ValidationActivitiesImpl(mock))
        val response = validation.validatePayout(
            ValidatePayoutRequest("po-000001", "cust-1", Money(25_000, "USD"), Rail.HTTP, Region.SG),
        )
        assertTrue(response.valid)
        assertTrue(response.detail.isNotBlank())
    }

    @Test
    fun `the rail submission reports its attempt number and a rail-specific reference`() {
        val rail = stub<RailActivities>(RailActivitiesImpl(mock))
        val response = rail.submitToRail(
            SubmitToRailRequest("po-000123", Rail.SFTP, Region.AU, Money(25_000, "USD"), "po-000123-rail-1"),
        )
        assertTrue(response.accepted)
        assertEquals("BANK-SFTP-000123", response.bankReference)
        // Reads straight off the ActivityExecutionContext, which this harness leaves at 0. A
        // local tally would start at 1 and fail here. The real counter is asserted end-to-end
        // in PayoutWorkflowCompensationTest, where a transient failure drives railAttempts to 3.
        assertEquals(0, response.attempt, "the attempt number comes from Temporal, never a local counter")
    }

    @Test
    fun `reversing an instruction returns a reversal reference`() {
        val rail = stub<RailActivities>(RailActivitiesImpl(mock))
        val response = rail.reverseRailInstruction(
            ReverseRailRequest("po-000123", "po-000123-rail-1", Rail.MQ, "BANK_REJECTED"),
        )
        assertTrue(response.reversed)
        assertEquals("REV-MQ-000123", response.reversalReference)
    }

    @Test
    fun `the FX quote converts through the canned rate table`() {
        val fx = stub<FxActivities>(FxActivitiesImpl(mock))

        val sgd = fx.validateFxQuote(ValidateFxQuoteRequest("po-1", Money(100_000, "SGD")))
        assertEquals(0.74, sgd.rate)
        assertEquals(74_000, sgd.usdEquivalentMinor, "the approval tier is judged on this number")

        val unknown = fx.validateFxQuote(ValidateFxQuoteRequest("po-1", Money(100_000, "ZZZ")))
        assertEquals(1.0, unknown.rate, "an unlisted currency falls back to 1:1 rather than failing")
        assertEquals(100_000, unknown.usdEquivalentMinor)
    }

    @Test
    fun `a pending bank is a RETRYABLE failure, which is what lets the retry policy do the polling`() {
        scenarios.put("po-000001", ScenarioConfig(behavior = Behavior.ACCEPTED_NO_CALLBACK, pollsBeforeResolution = 3))
        val bank = stub<BankActivities>(BankActivitiesImpl(mock, scenarios))

        val failure = applicationFailureFrom {
            bank.pollBankStatus(BankStatusProbeRequest("po-000001", "BANK-HTTP-000001"))
        }
        assertEquals("BankStatusPending", failure.type)
        // A non-retryable pending would stop after one ask, so the retry policy could not
        // function as the polling loop.
        assertTrue(!failure.isNonRetryable, "pending must be retryable")
    }

    @Test
    fun `once the poll budget is spent the bank's real answer is returned`() {
        // -1 makes the very first invocation the resolving one, because this harness cannot
        // drive the attempt counter past zero. The realistic shape -- several pending answers
        // and then a real one -- is asserted in PayoutWorkflowBankTest.
        scenarios.put(
            "po-000001",
            ScenarioConfig(
                behavior = Behavior.ACCEPTED_NO_CALLBACK,
                pollsBeforeResolution = -1,
                resolvedStatus = "REJECTED",
            ),
        )
        val bank = stub<BankActivities>(BankActivitiesImpl(mock, scenarios))

        val response = bank.pollBankStatus(BankStatusProbeRequest("po-000001", "BANK-HTTP-000001"))
        assertEquals(BankStatus.REJECTED, response.status, "resolvedStatus decides the outcome")
    }

    @Test
    fun `pollingNeverResolves overrides a spent poll budget and keeps reporting pending`() {
        scenarios.put(
            "po-000001",
            ScenarioConfig(
                behavior = Behavior.ACCEPTED_NO_CALLBACK,
                // Would resolve immediately on its own; the flag has to win regardless.
                pollsBeforeResolution = -1,
                pollingNeverResolves = true,
            ),
        )
        val bank = stub<BankActivities>(BankActivitiesImpl(mock, scenarios))

        val failure = applicationFailureFrom {
            bank.pollBankStatus(BankStatusProbeRequest("po-000001", "BANK-HTTP-000001"))
        }
        assertEquals("BankStatusPending", failure.type)
    }
}
