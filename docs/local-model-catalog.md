# Local model catalog

The **Modelos locais** settings page searches the Hugging Face Hub for public
`text-generation` repositories that advertise LiteRT-LM and contain one or more
`.litertlm` files. It looks up file sizes and LFS SHA-256 hashes at the same
repository revision returned by search. Ordinary Hugging Face checkpoints,
GGUF files, embedding models, gated repositories, and models without verifiable
bundle metadata are not offered for installation.

The size label compares a bundle's file size with the RAM budget measured by
Talos on this phone. That is an estimate, not a promise that the model will run:
bundle contents, runtime version, backend and device-specific conversions can
still prevent initialization. LiteRT-LM reports the final result when the user
first runs or benchmarks an installed model.

Installing a result always requires a confirmation that shows its repository,
file, size, license, and SHA-256. Talos checks that at least 500 MiB remains
after the download, streams into a hidden temporary file, checks exact byte
count and SHA-256, then moves the verified `.litertlm` into the app-specific
models directory. Cancellation or any verification error deletes the temporary
file. Existing files are never replaced, and a new model is not selected
automatically. The UI exposes download progress and cancellation.

The search uses the public [Hugging Face Hub API](https://huggingface.co/docs/hub/en/api).
The `.litertlm` runtime format and Android CPU/GPU setup are documented by
[LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM/blob/main/docs/api/kotlin/getting_started.md).
