# On-device Qwen3.5 runtime: official llama.cpp (libllama + libmtmd vision) + the app's JNI bridge.
# The llama.cpp source is the exact v1.0.0 release tag commit, fetched from GitHub with a pinned
# archive SHA-256. Nothing vendor-specific is built: only ggml's CPU backend, in the Android variants
# llama.cpp selects at run time from the CPU's feature bits (dotprod / fp16 / i8mm / SVE2 / SME).
cmake_minimum_required(VERSION 3.22.1)
project(khodroyar_llama LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Android: arm64-v8a only. Host (tools/offline-eval on an ARM64 runner): the same engine source plus a
# small CLI, so the evaluation measures the code the app ships rather than a different llama.cpp front end.
if(ANDROID AND NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "The offline llama.cpp runtime is built for arm64-v8a only (got ${ANDROID_ABI})")
endif()

include(FetchContent)
set(KHODROYAR_LLAMA_CPP_COMMIT b1b770d90d94cad9e9ea5b993bdbc383a522fb28) # tag v1.0.0
FetchContent_Declare(llama_cpp
    URL https://codeload.github.com/ggml-org/llama.cpp/tar.gz/${KHODROYAR_LLAMA_CPP_COMMIT}
    URL_HASH SHA256=ae8556670b17c25931cd39eb7c95617650eaf6091bbcff206750583ddde1acb4
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE)

# Same switches as llama.cpp's own examples/llama.android library and docs/android.md.
set(BUILD_SHARED_LIBS       ON  CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON      ON  CACHE BOOL "" FORCE)
set(LLAMA_BUILD_MTMD        ON  CACHE BOOL "" FORCE) # standalone libmtmd for bindings
set(LLAMA_BUILD_TOOLS       OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS       OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER      OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP         OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL           OFF CACHE BOOL "" FORCE)
set(LLAMA_SUBPROCESS        OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE             OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL         ON  CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS   ON  CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE          OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP             OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN             OFF CACHE BOOL "" FORCE) # CPU baseline; see OfflineLlamaProvider
FetchContent_MakeAvailable(llama_cpp)

set(KHODROYAR_INCLUDES ${llama_cpp_SOURCE_DIR}/common ${llama_cpp_SOURCE_DIR}/tools/mtmd)

if(ANDROID)
    add_library(khodroyar_llama SHARED khodroyar_llama.cpp khodroyar_engine.cpp)
    target_include_directories(khodroyar_llama PRIVATE ${KHODROYAR_INCLUDES})
    target_link_libraries(khodroyar_llama PRIVATE llama llama-common mtmd ggml android log)
    target_compile_options(khodroyar_llama PRIVATE -O2 -fvisibility=hidden)
    target_link_options(khodroyar_llama PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,--gc-sections")
else()
    add_executable(khodroyar-engine-cli khodroyar_engine_cli.cpp khodroyar_engine.cpp)
    target_include_directories(khodroyar-engine-cli PRIVATE ${KHODROYAR_INCLUDES} ${llama_cpp_SOURCE_DIR}/vendor)
    target_link_libraries(khodroyar-engine-cli PRIVATE llama llama-common mtmd ggml)
    # Relocatable: the CLI, libllama/libmtmd and the CPU variant modules all live in bin/ and travel together.
    set_target_properties(khodroyar-engine-cli PROPERTIES RUNTIME_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/bin
        BUILD_RPATH "$ORIGIN" INSTALL_RPATH "$ORIGIN")
endif()
