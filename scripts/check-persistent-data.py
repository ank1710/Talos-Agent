#!/usr/bin/env python3
"""Host logic checks against classes from assembleDebug; never invokes Gradle or Android."""
import os
import argparse
from pathlib import Path
import subprocess
import tempfile
import sys
import zipfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--source-check", action="store_true", help="Compile current non-UI sources against cached app dependencies")
parser.add_argument("--suite", action="append", choices=["persistent", "settings", "markdown", "catalog", "approval_stream", "approval_policy"],
                    help="Run only the named suite(s); default runs all")
options = parser.parse_args()

repo = Path(__file__).resolve().parents[1]
cache = Path(os.environ.get("TALOS_GRADLE_CACHE", repo.parent / ".gradle-home/caches/modules-2/files-2.1"))
classes = repo / "app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes"
java = Path(os.environ.get("JAVA_HOME", "/opt/homebrew/opt/openjdk@21")) / "bin/java"

def jar(group, artifact, version):
    matches = list((cache / group / artifact / version).glob("*/*.jar"))
    if len(matches) != 1:
        raise SystemExit(f"Expected one cached {artifact}:{version}; found {len(matches)}")
    return str(matches[0])

if not (classes / "com/talos/harness/store/MemoryStore.class").exists():
    raise SystemExit("Compile the app before running host checks.")
libs = [
    jar("org.jetbrains.kotlin", "kotlin-stdlib", "2.4.21"),
    jar("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.11.0"),
    jar("org.jetbrains.kotlinx", "kotlinx-serialization-core-jvm", "1.11.0"),
    jar("org.jetbrains.kotlinx", "kotlinx-serialization-json-jvm", "1.11.0"),
    jar("org.jetbrains", "annotations", "23.0.0"),
]
compiler = libs + [
    jar("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "2.4.21"),
    jar("org.jetbrains.kotlin", "kotlin-script-runtime", "2.4.21"),
    jar("org.jetbrains.kotlin", "kotlin-reflect", "2.4.10"),
]
with tempfile.TemporaryDirectory(prefix="talos-host-checks-") as directory:
    target = Path(directory) / "checks.jar"
    extras = [jar("com.squareup.okio", "okio-jvm", "3.18.1")]
    for group, artifact, version in [
        ("androidx.datastore", "datastore-core-android", "1.2.1"),
        ("androidx.datastore", "datastore-preferences-core-android", "1.2.1"),
        ("androidx.datastore", "datastore-preferences-android", "1.2.1"),
        ("androidx.datastore", "datastore-android", "1.2.1"),
        ("com.squareup.okhttp3", "okhttp-android", "5.4.0"),
    ]:
        archives = list((cache / group / artifact / version).glob("*/*.aar"))
        if len(archives) != 1:
            raise SystemExit(f"Expected cached AAR {artifact}:{version}")
        output = Path(directory) / f"{artifact}.jar"
        with zipfile.ZipFile(archives[0]) as archive:
            output.write_bytes(archive.read("classes.jar"))
        extras.append(str(output))
    cp = os.pathsep.join([str(classes)] + libs + extras)
    sources = []
    if options.source_check:
        # Check the changed non-UI logic before the one requested APK build.
        base = repo / "app/src/main/java/com/talos/harness"
        sources = [str(base / name) for name in [
            "store/Settings.kt", "store/ConnectionSettings.kt", "store/PlainTextStore.kt",
            "store/MemoryStore.kt", "store/SkillStore.kt", "agent/Approval.kt", "agent/AgentEvent.kt", "tools/SkillTool.kt", "tools/StoredData.kt",
            "tools/HttpTool.kt", "tools/WebSearchTool.kt",
            "tools/MemoryTool.kt", "tools/SessionSearchTool.kt", "tools/SubagentTool.kt", "agent/SystemPrompt.kt",
            "agent/PersistentContext.kt", "gateway/GatewayApprovalGate.kt",
            "gateway/GatewayApprovalClient.kt", "llm/DeepSeekClient.kt",
            "ui/markdown/OrderedListLine.kt", "local/HuggingFaceModelCatalog.kt",
        ]]
    subprocess.run([str(java), "-cp", os.pathsep.join(compiler), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                    "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath", cp,
                    # Tests may inspect internals without widening the app's public API.
                    "-Xfriend-paths=" + str(classes),
                    "-d", str(target)] + [str(p) for p in sorted((repo / "tests/host").glob("*.kt"))] + sources, check=True)
    suites = {"persistent": "PersistentDataChecksKt", "settings": "SettingsChecksKt",
              "markdown": "MarkdownChecksKt", "catalog": "ModelCatalogChecksKt",
              "approval_stream": "ApprovalStreamChecksKt", "approval_policy": "ApprovalPolicyChecksKt"}
    approval_source = (repo / "app/src/main/java/com/talos/harness/ui/ChatViewModel.kt").read_text()
    resolve_source = approval_source.split("fun resolveApproval(", 1)[1].split("\n    /**", 1)[0]
    post_source = approval_source.split("private suspend fun postDecision(", 1)[1].split("\n    fun dismissError", 1)[0]
    assert "gatewayGate.oldest" in resolve_source
    assert "gatewayGate.submit(decision)" not in resolve_source.split("val oldest", 1)[0]
    assert "gatewayGate.submitFor" in post_source and "error is GatewayApprovalClient.AlreadyResolved" in post_source
    gateway_client = (repo / "app/src/main/java/com/talos/harness/gateway/GatewayApprovalClient.kt").read_text()
    assert "response.code == 409 -> Result.failure(" in gateway_client
    assert "AlreadyResolved(" in gateway_client
    deepseek_source = (repo / "app/src/main/java/com/talos/harness/llm/DeepSeekClient.kt").read_text()
    assert "invokeOnCancellation { cancel() }" in deepseek_source
    assert "cancelCallOnCancellation(call)" in deepseek_source
    assert "STREAM_IDLE_TIMEOUT_SECONDS = 60L" in deepseek_source
    assert "timeout().timeout(STREAM_CALL_TIMEOUT_MINUTES" in deepseek_source
    approval_dialog = (repo / "app/src/main/java/com/talos/harness/ui/ChatScreen.kt").read_text()
    assert "request.toolName == \"shell\"" in approval_dialog
    assert "arquivos privados do Talos" in approval_dialog
    print("PASS approval retry and acknowledgement source invariants")
    print("PASS OkHttp stream cancellation and idle timeout source invariant")
    print("PASS shell private-data approval warning source invariant")
    settings_source = (repo / "app/src/main/java/com/talos/harness/store/Settings.kt").read_text()
    assert all(name in settings_source for name in ["ApprovalPermissiveness", "shell_command_prefixes", "web_search_api_key", "web_search_provider"])
    chat_vm = (repo / "app/src/main/java/com/talos/harness/ui/ChatViewModel.kt").read_text()
    assert all(name in chat_vm for name in ["APPROVE_SESSION", "sessionApprovedActions", "shellPrefixGranted", "interactiveRequiresApproval"])
    policy_source = (repo / "app/src/main/java/com/talos/harness/agent/Approval.kt").read_text()
    assert "LOOSE_MODE_PRIVATE_TOOLS" in policy_source and "Risk.DANGEROUS" in policy_source
    search_source = (repo / "app/src/main/java/com/talos/harness/tools/WebSearchTool.kt").read_text()
    assert "override val risk = Risk.CAUTION" in search_source
    assert "storedData(JsonArray(results))" in search_source and "validateDestination(active.url)" in search_source
    assert "validateDestination(next)" in search_source and "MAX_REDIRECTS" in search_source
    assert '"api_key", apiKey' in search_source and '"X-Subscription-Token", apiKey' in search_source
    registry_source = (repo / "app/src/main/java/com/talos/harness/ui/ChatViewModel.kt").read_text()
    worker_source = (repo / "app/src/main/java/com/talos/harness/schedule/ScheduledAgentWorker.kt").read_text()
    assert "WebSearchTool(settings.webSearchProvider, settings.webSearchApiKey)" in registry_source
    assert "WebSearchTool" not in worker_source and "UnattendedApprovalGate" in worker_source
    assert "APPROVE_SESSION" in approval_dialog and "Liberar prefixo" in approval_dialog
    assert "settings.shellCommandPrefixes" in (repo / "app/src/main/java/com/talos/harness/ui/settings/SettingsScreen.kt").read_text()
    print("PASS 10 web-search, interactive-policy, shell-prefix and unattended-boundary source checks")
    print("MODE: current non-UI sources + cached app dependencies" if options.source_check else "MODE: cached assembleDebug classes", flush=True)
    for selected in options.suite or suites:
        suite = suites[selected]
        result = subprocess.run([str(java), "-cp", os.pathsep.join([str(target), cp]), suite],
                                text=True, capture_output=True)
        print(result.stdout, end="")
        if result.returncode:
            print(result.stderr, file=sys.stderr, end="")
            raise SystemExit(f"{suite} failed with exit code {result.returncode}")
