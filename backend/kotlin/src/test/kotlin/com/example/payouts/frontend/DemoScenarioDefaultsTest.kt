package com.example.payouts.frontend

import com.example.payouts.api.StartPayoutBody
import com.example.payouts.model.domain.ApprovalThresholds
import com.example.payouts.model.domain.ApprovalTier
import com.example.payouts.model.domain.BankStatus
import com.example.payouts.model.domain.SettlementThresholds
import com.example.payouts.scenario.Behavior
import com.example.payouts.support.repoFile
import com.example.payouts.workflow.DEMO_MIN_ATTEMPTS
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.reflect.full.memberProperties
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The seam no other layer can see: what each demo scenario actually asks the backend for.
 *
 * Every backend test supplies its own request -- `payoutRequest(amountMinor = 250_000)` in the
 * unit suite, `StartPayoutBody(...)` in the integration suite, a literal in
 * `contract-test.sh`. All of them prove the *behaviours* work. None of them proves that the
 * five buttons in the UI still reach those behaviours, because `SCENARIOS` in `app.js` is the
 * only place that mapping exists.
 *
 * Everything asserted here fails silently in the demo rather than loudly:
 *
 *  - an amount that drops below the approval threshold skips the human-approval branch and
 *    the Approve / Reject buttons never appear, because they follow the live business status;
 *  - a renamed `StartPayoutBody` property leaves the UI posting the old key, and Spring Boot
 *    does not fail on unknown properties -- the request returns 200 and the scenario quietly
 *    runs on defaults (confirmed against the running stack: posting a misspelled `behavior`
 *    staged `PASS`);
 *  - a `resolvedStatus` that is not a `BankStatus` name reaches `BankStatus.valueOf` inside an
 *    activity, so it surfaces as a retrying activity rather than a bad request;
 *  - a failure-injection count at or above the retry budget exhausts the policy on some
 *    executions and not others, turning the retry demo into a compensation demo at random.
 *
 * So it reads the shipped `app.js` and checks its values against the production constants
 * rather than against a copy of them. Parsing JavaScript with a regex is a deliberately narrow
 * trade: the alternative is duplicating the scenario table in Kotlin, which is the very drift
 * being tested for, or bringing a Node toolchain into a frontend that deliberately has no
 * build step for JS. The parse asserts its own success -- an `app.js` it cannot read fails the
 * test instead of quietly matching nothing.
 */
class DemoScenarioDefaultsTest {

    /**
     * One request body the UI can post: a scenario's `defaults`, or those defaults with one of
     * scenario 5's `pollOutcomes` merged over them. Values stay as source text -- the point is
     * what gets sent, not what Kotlin would make of it.
     */
    private data class PostedConfig(
        val scenarioId: String,
        val label: String,
        val values: Map<String, String>,
    ) {
        fun long(key: String): Long? = values[key]?.toLongOrNull()
        override fun toString() = "scenario '$scenarioId' ($label)"
    }

    @Test
    fun `the approval scenario starts above the threshold, and no other scenario does`() {
        val scenarios = defaultsByScenario()

        // Anchored, so a rename or a sixth scenario is a deliberate edit here rather than a
        // silently smaller set of assertions.
        assertEquals(
            listOf("successful", "transient", "permanent", "approval", "unknown"),
            scenarios.map { it.scenarioId },
            "the demo ships five scenarios in this order -- the buttons are labelled 1-5 by index",
        )

        val approval = scenarios.single { it.scenarioId == "approval" }
        val approvalAmount = requireNotNull(approval.long("amountMinor")) { "$approval has no amountMinor" }
        assertTrue(
            ApprovalThresholds.tierFor(approvalAmount) != ApprovalTier.NONE,
            "scenario 4 is the human-approval demo, so its default amount must cross a " +
                "threshold. $approvalAmount minor units is ${ApprovalThresholds.tierFor(approvalAmount)}: " +
                "the workflow would run straight past AWAITING_APPROVAL and the Approve / " +
                "Reject buttons, which follow the live status, would never appear. " +
                "L1 starts at ${ApprovalThresholds.L1_FROM_MINOR}.",
        )

        // The inverse matters just as much: an amount that crept over the threshold would park
        // the happy path, the retry demo or the polling demo on a human instead.
        scenarios.filter { it.scenarioId != "approval" }.forEach {
            val amount = requireNotNull(it.long("amountMinor")) { "$it has no amountMinor" }
            assertEquals(
                ApprovalTier.NONE,
                ApprovalThresholds.tierFor(amount),
                "$it is not about approval, but $amount minor units is " +
                    "${ApprovalThresholds.tierFor(amount)} -- it would park on a signal before " +
                    "reaching what it means to show",
            )
        }
    }

    @Test
    fun `every manual scenario still waits for a bank callback`() {
        // Low-value payouts settle inline through settleWithBank: no wait, no timer, and
        // therefore no Bank: completed / rejected / unknown buttons in the UI, because those
        // follow the live business status. The five manual scenarios exist to be driven by
        // hand, so every one of them has to stay ABOVE the sync threshold -- this is the
        // constraint that fixed that threshold at \$100, under the \$250 they start at.
        //
        // Scenario 5 is the sharpest case: a low-value unknown-callback payout would settle
        // inline and never reach the polling path it exists to demonstrate.
        defaultsByScenario().forEach {
            val amount = requireNotNull(it.long("amountMinor")) { "$it has no amountMinor" }
            assertTrue(
                !SettlementThresholds.settlesSynchronously(amount),
                "$it starts at $amount minor units, below the " +
                    "${SettlementThresholds.SYNC_BELOW_MINOR} sync-settlement threshold, so the " +
                    "bank would answer inline and the scenario's Bank buttons would never appear",
            )
        }
    }

    @Test
    fun `every key and enum value the UI posts is one the backend still understands`() {
        val known = StartPayoutBody::class.memberProperties.map { it.name }.toSet()
        val behaviours = Behavior.entries.map { it.name }.toSet()
        val bankStatuses = BankStatus.entries.map { it.name }.toSet()

        postedConfigs().forEach { config ->
            config.values.keys.forEach { key ->
                assertTrue(
                    key in known,
                    "$config posts '$key', which is not a property of StartPayoutBody " +
                        "($known). Unknown properties are dropped rather than rejected, so the " +
                        "scenario would return 200 and silently run on the defaults",
                )
            }
            config.values["behavior"]?.let {
                assertTrue(it in behaviours, "$config posts behavior='$it'; Behavior has $behaviours")
            }
            config.values["resolvedStatus"]?.let {
                assertTrue(
                    it in bankStatuses,
                    "$config posts resolvedStatus='$it', which reaches BankStatus.valueOf inside " +
                        "pollBankStatus -- an unknown name fails there, as a retrying activity",
                )
            }
        }
    }

    @Test
    fun `the injected failure counts stay inside the retry budget`() {
        // Injection counts are "fail this many attempts, then succeed", so resolution lands on
        // attempt n+1 and needs n+1 <= the smallest cap the RNG can draw.
        postedConfigs()
            .filterNot { it.values["pollingNeverResolves"] == "true" }
            .forEach { config ->
                listOf("transientFailures", "pollsBeforeResolution").forEach { key ->
                    val count = config.long(key) ?: return@forEach
                    assertTrue(
                        count < DEMO_MIN_ATTEMPTS,
                        "$config injects $key=$count, which resolves on attempt ${count + 1}. " +
                            "The retry policy draws $DEMO_MIN_ATTEMPTS-10 attempts per " +
                            "execution, so that exhausts on some runs and not others: the " +
                            "scenario would compensate at random instead of recovering",
                    )
                }
            }
    }

    @Test
    fun `each scenario posts its own name, which is what a page refresh reads back`() {
        // The workflow id is payout-{scenarioName}-{payoutId}, and App() recovers the selected
        // scenario from `?payout=` by splitting that id on '-'. A defaults.scenario that did
        // not match its own entry id would select a different panel on refresh.
        defaultsByScenario().forEach {
            assertEquals(
                it.scenarioId,
                it.values["scenario"],
                "scenario '${it.scenarioId}' posts scenario='${it.values["scenario"]}'",
            )
        }
    }

    // ---- parsing ----

    /**
     * Scenario entries open `id: 'x', name: 'Y'` on one line. The `pollOutcomes` entries
     * nested inside scenario 5 also start with `id:`, so the following key is load-bearing:
     * it is what distinguishes a scenario from an option within one.
     */
    private val scenarioHeader = Regex("""id:\s*'([\w-]+)',\s*name:""")
    private val pollOutcome = Regex("""id:\s*'([\w-]+)',\s*label:[^{]*cfg:\s*\{([^}]*)}""")
    private val defaultsBlock = Regex("""defaults:\s*\{([^}]*)}""")
    private val entry = Regex("""(\w+):\s*(?:'([^']*)'|(true|false|\d+))""")

    /** One config per scenario: its `defaults` exactly as posted. */
    private fun defaultsByScenario(): List<PostedConfig> =
        postedConfigs().filter { it.label == "defaults" }

    /**
     * Every body the UI can post: each scenario's defaults, plus one per `pollOutcomes` entry
     * with its `cfg` merged over those defaults -- which is what `start()` in `app.js` does.
     */
    private fun postedConfigs(): List<PostedConfig> {
        val block = scenariosBlock()
        val headers = scenarioHeader.findAll(block).toList()
        if (headers.isEmpty()) fail("found no scenario entries in the SCENARIOS array")

        return headers.flatMapIndexed { index, header ->
            val id = header.groupValues[1]
            val body = block.substring(
                header.range.last,
                headers.getOrNull(index + 1)?.range?.first ?: block.length,
            )
            val defaults = parse(
                defaultsBlock.find(body)?.groupValues?.get(1) ?: fail("scenario '$id' has no defaults block"),
            )
            listOf(PostedConfig(id, "defaults", defaults)) +
                pollOutcome.findAll(body).map { outcome ->
                    PostedConfig(id, "outcome '${outcome.groupValues[1]}'", defaults + parse(outcome.groupValues[2]))
                }
        }
    }

    private fun parse(braceBody: String): Map<String, String> =
        entry.findAll(braceBody).associate { match ->
            match.groupValues[1] to match.groupValues[2].ifEmpty { match.groupValues[3] }
        }

    private fun scenariosBlock(): String =
        Files.readString(appJs())
            .substringAfter("const SCENARIOS = [", missingDelimiterValue = "")
            .substringBefore("\n]")
            .ifBlank { fail("could not find the SCENARIOS array in ${appJs()}") }

    private fun appJs(): Path = repoFile(
        "src/main/resources/static/assets/app.js",
        "backend/kotlin/src/main/resources/static/assets/app.js",
    )
}
