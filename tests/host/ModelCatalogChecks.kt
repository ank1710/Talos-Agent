import com.talos.harness.local.parseHuggingFaceBundleDetails
import com.talos.harness.local.parseHuggingFaceCandidates
import com.talos.harness.local.verifyDownloadedModelIntegrity
import java.io.IOException

fun main() {
    val revision = "6aa2daf8aba4aa456797fb8040b36a3948bcfda7"
    val search = """[
      {"id":"litert-community/Qwen3-0.6B-int4","sha":"$revision","downloads":123,"gated":false,"pipeline_tag":"text-generation",
       "siblings":[{"rfilename":"README.md"},{"rfilename":"qwen3_0.6b_q4.litertlm"},{"rfilename":"model.safetensors"}]},
      {"id":"private/model","sha":"$revision","downloads":999,"gated":true,"pipeline_tag":"text-generation",
       "siblings":[{"rfilename":"model.litertlm"}]},
      {"id":"../unsafe/model","sha":"$revision","downloads":999,"gated":false,"pipeline_tag":"text-generation",
       "siblings":[{"rfilename":"model.litertlm"}]},
      {"id":"org/no-bundle","sha":"$revision","downloads":1,"gated":false,"pipeline_tag":"text-generation",
       "siblings":[{"rfilename":"weights.gguf"}]},
      {"id":"org/embedding","sha":"$revision","downloads":1000,"gated":false,"pipeline_tag":"feature-extraction",
       "siblings":[{"rfilename":"embedding.litertlm"}]}
    ]"""
    val candidates = parseHuggingFaceCandidates(search)
    check(candidates.size == 1) { "expected only public, safe .litertlm repositories" }
    val candidate = candidates.single()
    check(candidate.repository == "litert-community/Qwen3-0.6B-int4")
    check(candidate.bundleNames == setOf("qwen3_0.6b_q4.litertlm"))

    val details = """{
      "id":"${candidate.repository}","sha":"$revision",
      "cardData":{"license":"apache-2.0"},"tags":["license:apache-2.0"],
      "siblings":[
        {"rfilename":"qwen3_0.6b_q4.litertlm","size":347251840,
         "lfs":{"size":347251840,"sha256":"2df6821ec12702dafd33915e7a1a1adc7c4b053f3672fd9555dfaf3a114c4139"}},
        {"rfilename":"unused.litertlm","size":10,"lfs":{"size":10,"sha256":"${"0".repeat(64)}"}},
        {"rfilename":"weights.gguf","size":347251840}
      ]
    }"""
    val bundles = parseHuggingFaceBundleDetails(details, candidate)
    check(bundles.size == 1) { "must match only .litertlm files listed at the searched revision" }
    val bundle = bundles.single()
    check(bundle.filename == "qwen3_0.6b_q4.litertlm")
    check(bundle.bytes == 347251840L)
    check(bundle.sha256 == "2df6821ec12702dafd33915e7a1a1adc7c4b053f3672fd9555dfaf3a114c4139")
    check(bundle.license == "apache-2.0")
    check(bundle.repositoryUrl == "https://huggingface.co/litert-community/Qwen3-0.6B-int4")

    val expectedSha = bundle.sha256
    verifyDownloadedModelIntegrity(
        expectedBytes = bundle.bytes,
        actualBytes = bundle.bytes,
        expectedSha256 = expectedSha,
        actualSha256 = expectedSha,
    )
    check(runCatching {
        verifyDownloadedModelIntegrity(bundle.bytes, bundle.bytes - 1, expectedSha, expectedSha)
    }.exceptionOrNull() is IOException) { "a truncated bundle must fail size verification" }
    check(runCatching {
        verifyDownloadedModelIntegrity(bundle.bytes, bundle.bytes, expectedSha, "0".repeat(64))
    }.exceptionOrNull() is IOException) { "a mismatched checksum must fail verification" }

    val wrongRevision = details.replace(revision, "a".repeat(40))
    check(parseHuggingFaceBundleDetails(wrongRevision, candidate).isEmpty()) {
        "metadata from a different revision must not be attached to the search result"
    }
    println("Model catalog checks passed (public filter, .litertlm, pinned metadata, size, license, SHA-256).")
}
