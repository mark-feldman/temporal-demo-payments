package com.example.payouts.frontend

import com.example.payouts.model.domain.ApprovalThresholds
import com.example.payouts.model.domain.ApprovalTier
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The one seam no other layer can see: the amount each demo scenario starts with.
 *
 * Every backend test supplies its own amount -- `payoutRequest(amountMinor = 250_000)` in the
 * unit suite, `StartPayoutBody(amountMinor = 250_000)` in the integration suite, a literal in
 * `contract-test.sh` -- so all of them prove the approval gate *works* without ever proving
 * that the button labelled "4" in the UI reaches it. `SCENARIOS` in `app.js` is the only place
 * that mapping exists, and a scenario that starts below `L1_FROM_MINOR` skips the human
 * approval branch silently: the workflow succeeds, nothing errors, and the Approve / Reject
 * buttons simply never appear because they follow the live business status.
 *
 * That is the regression this guards, so it reads the shipped `app.js` and checks its numbers
 * against the production thresholds rather than against a copy of them. Parsing JavaScript
 * with a regex is a deliberately narrow trade: the alternative is either duplicating the
 * scenario table in Kotlin, which is the very drift being tested for, or bringing a Node
 * toolchain into a frontend that deliberately has no build step for JS. The parse asserts its
 * own success -- an `app.js` it cannot read fails the test instead of quietly matching nothing.
 */
class DemoScenarioDefaultsTest {

    /**
     * Scenario entries open `id: 'x', name: 'Y'` on one line. The `pollOutcomes` entries
     * nested inside scenario 5 also start with `id:`, so the `name:` is load-bearing: it is
     * what distinguishes a scenario from an option within one.
     */
    private val scenarioHeader = Regex("""id:\s*'([\w-]+)',\s*name:""")
    private val defaultsBlock = Regex("""defaults:\s*\{([^}]*)}""")
    private val amountMinor = Regex("""amountMinor:\s*(\d+)""")
    private val scenarioName = Regex("""scenario:\s*'([\w-]+)'""")

    private data class DemoScenario(val id: String, val scenarioName: String, val amountMinor: Long) {
        val tier: ApprovalTier get() = ApprovalThresholds.tierFor(amountMinor)
    }

    @Test
    fun `the approval scenario starts above the threshold, and no other scenario does`() {
        val scenarios = parseScenarios()

        // Anchored, so a rename or a sixth scenario is a deliberate edit here rather than a
        // silently smaller set of assertions.
        assertEquals(
            listOf("successful", "transient", "permanent", "approval", "unknown"),
            scenarios.map { it.id },
            "the demo ships five scenarios in this order -- the buttons are labelled 1-5 by index",
        )

        val approval = scenarios.single { it.id == "approval" }
        assertTrue(
            approval.tier != ApprovalTier.NONE,
            "scenario 4 is the human-approval demo, so its default amount must cross a " +
                "threshold. ${approval.amountMinor} minor units is ${approval.tier}: the " +
                "workflow would run straight past AWAITING_APPROVAL and the Approve / Reject " +
                "buttons, which follow the live status, would never appear. " +
                "L1 starts at ${ApprovalThresholds.L1_FROM_MINOR}.",
        )

        // The inverse matters just as much: an amount that crept over the threshold would park
        // the happy path, the retry demo or the polling demo on a human instead.
        scenarios.filter { it.id != "approval" }.forEach {
            assertEquals(
                ApprovalTier.NONE,
                it.tier,
                "scenario '${it.id}' is not about approval, but ${it.amountMinor} minor units " +
                    "is ${it.tier} -- it would park on a signal before reaching what it means " +
                    "to show",
            )
        }
    }

    @Test
    fun `each scenario posts its own name, which is what a page refresh reads back`() {
        // The workflow id is payout-{scenarioName}-{payoutId}, and App() recovers the selected
        // scenario from `?payout=` by splitting that id on '-'. A defaults.scenario that did
        // not match its own entry id would select a different panel on refresh.
        parseScenarios().forEach {
            assertEquals(it.id, it.scenarioName, "scenario '${it.id}' posts scenario='${it.scenarioName}'")
        }
    }

    private fun parseScenarios(): List<DemoScenario> {
        val source = Files.readString(appJs())
        val block = source.substringAfter("const SCENARIOS = [", missingDelimiterValue = "")
            .substringBefore("\n]")
        if (block.isBlank()) fail("could not find the SCENARIOS array in ${appJs()}")

        return scenarioHeader.findAll(block).map { header ->
            val id = header.groupValues[1]
            val defaults = defaultsBlock.find(block, header.range.last)
                ?: fail("scenario '$id' has no defaults block")
            val amount = amountMinor.find(defaults.groupValues[1])
                ?: fail("scenario '$id' has no amountMinor in its defaults")
            val name = scenarioName.find(defaults.groupValues[1])
                ?: fail("scenario '$id' has no scenario name in its defaults")
            DemoScenario(id, name.groupValues[1], amount.groupValues[1].toLong())
        }.toList()
    }

    /**
     * A Gradle Test task runs in `backend/kotlin`, but an IDE run configuration may run from
     * the repository root instead, so try both spellings at every level on the way up rather
     * than trusting the working directory.
     */
    private fun appJs(): Path {
        val relative = listOf(
            Path.of("src/main/resources/static/assets/app.js"),
            Path.of("backend/kotlin/src/main/resources/static/assets/app.js"),
        )
        var dir: Path? = Path.of("").toAbsolutePath()
        while (dir != null) {
            relative.map(dir::resolve).firstOrNull(Files::exists)?.let { return it }
            dir = dir.parent
        }
        fail("could not find app.js from ${Path.of("").toAbsolutePath()}")
    }
}
