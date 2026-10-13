import com.talos.harness.agent.ApprovalRequest
import com.talos.harness.agent.Risk
import com.talos.harness.agent.interactiveRequiresApproval
import com.talos.harness.store.ApprovalPermissiveness

fun main() {
    val safe = ApprovalRequest("1", "device_info", "{}", "info", Risk.SAFE)
    val caution = ApprovalRequest("2", "fetch_url", "{}", "GET", Risk.CAUTION)
    val privateReader = ApprovalRequest("3", "list_dir", "{}", "listar", Risk.SAFE)
    val shell = ApprovalRequest("4", "shell", "{}", "pwd", Risk.CAUTION)
    val dangerous = ApprovalRequest("5", "anything", "{}", "perigoso", Risk.DANGEROUS)

    check(interactiveRequiresApproval(safe, ApprovalPermissiveness.STRICT))
    check(!interactiveRequiresApproval(safe, ApprovalPermissiveness.BALANCED))
    check(interactiveRequiresApproval(caution, ApprovalPermissiveness.BALANCED))
    check(!interactiveRequiresApproval(caution, ApprovalPermissiveness.LOOSE))
    check(interactiveRequiresApproval(privateReader, ApprovalPermissiveness.LOOSE))
    check(interactiveRequiresApproval(shell, ApprovalPermissiveness.LOOSE))
    ApprovalPermissiveness.entries.forEach { check(interactiveRequiresApproval(dangerous, it)) }
    println("8 interactive approval policy checks passed")
}
