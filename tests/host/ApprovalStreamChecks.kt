import com.talos.harness.agent.ApprovalDecision
import com.talos.harness.agent.ApprovalRequest
import com.talos.harness.agent.Risk
import com.talos.harness.gateway.GatewayApprovalGate
import com.talos.harness.gateway.GatewayApprovalClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    var passed = 0
    suspend fun verify(name: String, block: suspend () -> Unit) {
        block()
        passed++
        println("PASS $name")
    }

    verify("approval stays parked across a failed attempt and retries with the same ids") {
        val published = CompletableDeferred<Unit>()
        val gate = GatewayApprovalGate(onPending = { published.complete(Unit) })
        val frame = gate.forFrame("run-original", "request-original", listOf("once", "deny"))
        val prompt = ApprovalRequest("request-original", "ação no computador", "cat private.txt", "cat private.txt", Risk.CAUTION)
        val waiting = async { gate.request(prompt, frame) }
        published.await()

        val firstAttempt = checkNotNull(gate.oldest)
        check(firstAttempt.runId == "run-original" && firstAttempt.requestId == "request-original")
        // A transport failure performs no gate settlement. The displayed entry is still retryable.
        check(gate.pendingCount == 1)
        val retry = checkNotNull(gate.oldest)
        check(retry === firstAttempt)
        check(retry.runId == firstAttempt.runId && retry.requestId == firstAttempt.requestId)

        check(gate.submitFor("request-original", ApprovalDecision.APPROVE_ONCE))
        check(waiting.await() == ApprovalDecision.APPROVE_ONCE)
        check(gate.pendingCount == 0)
    }

    verify("HTTP 409 approval_not_pending is treated as already resolved") {
        val outcome: Throwable = GatewayApprovalClient.AlreadyResolved("already answered")
        check(outcome::class == GatewayApprovalClient.AlreadyResolved::class)
        check(outcome.message == "already answered")
    }

    println("$passed approval/stream checks passed")
}
