# Third-party notices

## llama.cpp
- Project: https://github.com/ggml-org/llama.cpp
- Pinned submodule revision: 6c01da6278ab6c69ed67d9124806b50d2b299ba7
- License: MIT. The upstream LICENSE file is included in the submodule at `external/llama.cpp/LICENSE`.
- The app uses the upstream Android JNI sample structure for the native inference binding. No model weights are bundled; users select their own GGUF files.

## Assimp
- Project: https://github.com/assimp/assimp
- Pinned submodule revision: 0623040c1eaf426f660f43892b3469c9fdb99842
- License: BSD 3-Clause. The upstream LICENSE file is included in the submodule at `external/assimp/LICENSE`.
- Used for PMX/PMD static mesh import through its MMD importer.

## Vosk Android
- Project: https://github.com/alphacep/vosk-api
- Artifact: `com.alphacephei:vosk-android:0.3.75`
- License: Apache License 2.0 (upstream Vosk API project).
- The app does not bundle acoustic model weights; the user imports a compatible model archive locally.


## babylon-mmd
- Project: https://github.com/noname0310/babylon-mmd
- Version used by the WebView: 1.3.0 (loaded from unpkg CDN)
- License: MIT. The viewer does not bundle the library; it loads the pinned UMD build at runtime.
- The viewer also loads Babylon.js from the official Babylon CDN.
