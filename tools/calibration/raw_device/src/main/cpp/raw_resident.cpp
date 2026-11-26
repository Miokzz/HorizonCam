#define CL_TARGET_OPENCL_VERSION 120
#include <CL/cl.h>
#include <android/hardware_buffer.h>
#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>
#include "raw_fidelity.h"

#include <litert/c/litert_compiled_model.h>
#include <litert/c/litert_environment.h>
#include <litert/c/litert_event.h>
#include <litert/c/litert_model.h>
#include <litert/c/litert_opaque_options.h>
#include <litert/c/litert_options.h>
#include <litert/c/litert_tensor_buffer.h>
#include <litert/c/litert_tensor_buffer_requirements.h>

#include <algorithm>
#include <array>
#include <chrono>
#include <cstring>
#include <memory>
#include <stdexcept>
#include <string>

namespace {
constexpr int width = 1824, height = 1368, tile = 128, extent = 136;
constexpr int tiles = ((width + tile - 1) / tile) * ((height + tile - 1) / tile);
constexpr size_t samples = 4 * width * height;
constexpr size_t rawBytes = samples * sizeof(float), rgbBytes = samples * 3;
constexpr size_t lookupBytes = 65536;
constexpr size_t parameterBytes = (9 + 4 * (2 + 256 * 256 + 3 * (width + height))) * sizeof(float);
constexpr char tag[] = "HorizonRawResident";
constexpr char shader[] =
#include "raw_resident_kernels.inc"
;

int64_t now() {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
}

void check(int status, const char* action) {
    if (status != 0) throw std::runtime_error(std::string(action) + " returned " + std::to_string(status));
}

void require(bool condition, const char* action) {
    if (!condition) throw std::runtime_error(action);
}

#define CL_FUNCTIONS(X)                                                        \
    X(clGetPlatformIDs) X(clGetDeviceIDs) X(clGetDeviceInfo) X(clCreateContext)   \
    X(clCreateCommandQueue) X(clCreateProgramWithSource) X(clBuildProgram)      \
    X(clGetProgramBuildInfo) X(clCreateKernel) X(clCreateBuffer)                \
    X(clGetMemObjectInfo) X(clSetKernelArg) X(clEnqueueWriteBuffer)              \
    X(clEnqueueReadBuffer) X(clEnqueueFillBuffer) X(clEnqueueNDRangeKernel)       \
    X(clEnqueueMarkerWithWaitList) X(clGetEventInfo) X(clGetEventProfilingInfo)   \
    X(clFinish) X(clReleaseEvent) X(clReleaseMemObject) X(clReleaseKernel)       \
    X(clReleaseProgram) X(clReleaseCommandQueue) X(clReleaseContext)

struct OpenCl {
    void* library = nullptr;
#define DEFINE(name) decltype(&::name) name = nullptr;
    CL_FUNCTIONS(DEFINE)
#undef DEFINE

    void load() {
        library = dlopen("libOpenCL.so", RTLD_NOW | RTLD_LOCAL);
        require(library != nullptr, "libOpenCL.so unavailable");
#define GET(name)                                                         \
        name = reinterpret_cast<decltype(name)>(dlsym(library, #name));       \
        require(name != nullptr, #name " unavailable");
        CL_FUNCTIONS(GET)
#undef GET
    }

    ~OpenCl() { if (library) dlclose(library); }
};

LiteRtEnvOption integerOption(LiteRtEnvOptionTag key, int64_t value) {
    LiteRtEnvOption option{};
    option.tag = key;
    option.value.type = kLiteRtAnyTypeInt;
    option.value.int_value = value;
    return option;
}

struct FrameEvents {
    explicit FrameEvents(OpenCl& api) : api(api) {}
    ~FrameEvents() { for (cl_event event : events) if (event) api.clReleaseEvent(event); }
    cl_event* next() { return &events.at(count++); }
    OpenCl& api;
    size_t count = 0;
    std::array<cl_event, 3 * tiles + 8 + fidelity::indices.size()> events{};
};

class Resident {
public:
    void init(const char* modelPath, const char* runtimeDir, const void* lutHost, bool external, bool audit, int math,
              std::array<jlong, 3>& times) {
        int64_t started = now();
        external_ = external;
        cl.load();
        cl_uint platforms = 0;
        cl_platform_id platform = nullptr;
        check(cl.clGetPlatformIDs(1, &platform, &platforms), "OpenCL platforms");
        require(platforms > 0, "No OpenCL platform");
        cl_device_id device = nullptr;
        check(cl.clGetDeviceIDs(platform, CL_DEVICE_TYPE_GPU, 1, &device, nullptr), "OpenCL GPU");
        cl_int rc = CL_SUCCESS;
        context = cl.clCreateContext(nullptr, 1, &device, nullptr, nullptr, &rc);
        check(rc, "OpenCL context");
        queue = cl.clCreateCommandQueue(context, device, CL_QUEUE_PROFILING_ENABLE, &rc);
        check(rc, "OpenCL queue");
        LiteRtEnvOption envOptions[] = {
                integerOption(kLiteRtEnvOptionTagOpenClDeviceId, reinterpret_cast<intptr_t>(device)),
                integerOption(kLiteRtEnvOptionTagOpenClPlatformId, reinterpret_cast<intptr_t>(platform)),
                integerOption(kLiteRtEnvOptionTagOpenClContext, reinterpret_cast<intptr_t>(context)),
                integerOption(kLiteRtEnvOptionTagOpenClCommandQueue, reinterpret_cast<intptr_t>(queue)),
                integerOption(kLiteRtEnvOptionTagAutoRegisterAccelerators, kLiteRtHwAcceleratorGpu),
                {}};
        envOptions[5].tag = kLiteRtEnvOptionTagRuntimeLibraryDir;
        envOptions[5].value.type = kLiteRtAnyTypeString;
        envOptions[5].value.str_value = runtimeDir;
        check(LiteRtCreateEnvironment(std::size(envOptions), envOptions, &environment), "LiteRT environment");
        times[0] = now() - started;
        probeAndroidBuffer();

        started = now();
        check(LiteRtCreateModelFromFile(environment, modelPath, &model), "LiteRT model");
        check(LiteRtCreateOptions(&options), "LiteRT options");
        check(LiteRtSetOptionsHardwareAccelerators(options, kLiteRtHwAcceleratorGpu), "GPU only");
        // LiteRT 2.4.0's Maven AAR exports the opaque C API. Its GPU parser
        // consumes the NUL-terminated TOML used by LrtGetOpaqueGpuOptionsData.
        std::string gpuOptions = "backend = " + std::to_string(kLiteRtGpuBackendOpenCl) + '\n' +
                                 "precision = " + std::to_string(kLiteRtDelegatePrecisionFp32) + '\n';
        if (external) {
            gpuOptions += "external_tensors_mode = true\nbuffer_storage_type = " +
                          std::to_string(kLiteRtDelegateBufferStorageTypeBuffer) + '\n';
        }
        auto payload = std::make_unique<char[]>(gpuOptions.size() + 1);
        std::memcpy(payload.get(), gpuOptions.c_str(), gpuOptions.size() + 1);
        LiteRtOpaqueOptions opaque = nullptr;
        check(LiteRtCreateOpaqueOptions("gpu_options", payload.get(),
                  [](void* data) { delete[] static_cast<char*>(data); }, &opaque), "create opaque GPU options");
        payload.release();
        auto status = LiteRtAddOpaqueOptions(options, opaque);
        if (status != kLiteRtStatusOk) LiteRtDestroyOpaqueOptions(opaque);
        check(status, "install GPU options");
        check(LiteRtCreateCompiledModel(environment, model, options, &compiled), "FP32 CompiledModel");
        verifyDelegate();
        times[1] = now() - started;

        started = now();
        LiteRtSignature signature = nullptr;
        check(LiteRtGetModelSignature(model, 0, &signature), "model signature");
        input = makeTensor(signature, true, inputCl);
        output = makeTensor(signature, false, outputCl);
        makeKernels(device, audit, math);
        if (audit) scratch = allocate(sizeof(fidelity::Tile::noise), CL_MEM_READ_WRITE);
        source = allocate(rawBytes, CL_MEM_READ_ONLY);
        raw = allocate(rawBytes, CL_MEM_READ_WRITE);
        rgb = allocate(rgbBytes, CL_MEM_WRITE_ONLY);
        metadata = allocate(parameterBytes, CL_MEM_READ_ONLY);
        lut = allocate(lookupBytes, CL_MEM_READ_ONLY);
        invalid = allocate(sizeof(cl_uint), CL_MEM_READ_WRITE);
        check(cl.clEnqueueWriteBuffer(queue, lut, CL_TRUE, 0, lookupBytes, lutHost, 0, nullptr, nullptr), "sRGB LUT");
        times[2] = now() - started;
        __android_log_print(ANDROID_LOG_INFO, tag,
                            "Resident %s buffers share the OpenCL context, persistent logical CL bytes=%zu",
                            external ? "PHWC4" : "NHWC", allocatedBytes);
    }

    void frame(const void* sourceHost, const jfloat* profile, const float* maps, jint mapBytes,
               void* rawHost, void* rgbHost, std::array<jlong, 13 + tiles>& result, fidelity::Trace* trace,
               const fidelity::Trace* reference, const void* referenceRaw) {
        const int64_t started = now();
        FrameEvents events(cl);
        try {
            work(sourceHost, profile, maps, mapBytes, rawHost, rgbHost, result, events, started, trace, reference, referenceRaw);
        } catch (...) {
            // Drain any commands that still reference the borrowed direct Java buffers.
            cl.clFinish(queue);
            clearTensorEvents();
            throw;
        }
    }

    ~Resident() {
        if (queue) cl.clFinish(queue);
        if (input) LiteRtDestroyTensorBuffer(input);
        if (output) LiteRtDestroyTensorBuffer(output);
        if (compiled) LiteRtDestroyCompiledModel(compiled);
        if (options) LiteRtDestroyOptions(options);
        if (model) LiteRtDestroyModel(model);
        if (environment) LiteRtDestroyEnvironment(environment);
        for (auto memory : {source, raw, rgb, metadata, lut, invalid, scratch}) if (memory) cl.clReleaseMemObject(memory);
        for (auto kernel : {prepare, restore, develop}) if (kernel) cl.clReleaseKernel(kernel);
        if (program) cl.clReleaseProgram(program);
        if (queue) cl.clReleaseCommandQueue(queue);
        if (context) cl.clReleaseContext(context);
    }

private:
    OpenCl cl;
    cl_context context = nullptr;
    cl_command_queue queue = nullptr;
    cl_program program = nullptr;
    cl_kernel prepare = nullptr, restore = nullptr, develop = nullptr;
    cl_mem source = nullptr, raw = nullptr, rgb = nullptr, metadata = nullptr, lut = nullptr, invalid = nullptr;
    cl_mem scratch = nullptr;
    cl_mem inputCl = nullptr, outputCl = nullptr;
    LiteRtEnvironment environment = nullptr;
    LiteRtModel model = nullptr;
    LiteRtOptions options = nullptr;
    LiteRtCompiledModel compiled = nullptr;
    LiteRtTensorBuffer input = nullptr, output = nullptr;
    size_t allocatedBytes = 0;
    bool external_ = false;

    void probeAndroidBuffer() {
        bool supported = false;
        auto status = LiteRtEnvironmentSupportsAhwbClInterop(environment, &supported);
        constexpr uint64_t cpuRead = AHARDWAREBUFFER_USAGE_CPU_READ_RARELY;
        constexpr uint64_t cpuWrite = AHARDWAREBUFFER_USAGE_CPU_WRITE_RARELY;
        AHardwareBuffer_Desc desc{};
        desc.width = 4096;
        desc.height = desc.layers = 1;
        desc.format = AHARDWAREBUFFER_FORMAT_BLOB;
        desc.usage = cpuRead | cpuWrite | AHARDWAREBUFFER_USAGE_GPU_DATA_BUFFER;
        bool allocatable = AHardwareBuffer_isSupported(&desc) != 0;
        __android_log_print(ANDROID_LOG_INFO, tag,
                            "AHardwareBuffer BLOB supported=%d; LiteRT OpenCL import status=%d supported=%d",
                            allocatable, status, supported);
        if (!allocatable || status != kLiteRtStatusOk || !supported) return;

        try {
            AHardwareBuffer* created = nullptr;
            check(AHardwareBuffer_allocate(&desc, &created), "allocate Android buffer");
            std::unique_ptr<AHardwareBuffer, decltype(&AHardwareBuffer_release)> buffer(created, AHardwareBuffer_release);
            LiteRtRankedTensorType shape{};
            shape.element_type = kLiteRtElementTypeFloat32;
            shape.layout.rank = 1;
            shape.layout.dimensions[0] = desc.width / sizeof(float);
            LiteRtTensorBuffer wrapped = nullptr;
            check(LiteRtCreateTensorBufferFromAhwb(environment, &shape, buffer.get(), 0, nullptr, &wrapped),
                  "wrap Android buffer");
            std::unique_ptr<std::remove_pointer_t<LiteRtTensorBuffer>, decltype(&LiteRtDestroyTensorBuffer)>
                    tensor(wrapped, LiteRtDestroyTensorBuffer);
            AHardwareBuffer* original = nullptr;
            check(LiteRtGetTensorBufferAhwb(wrapped, &original), "extract Android buffer");
            require(original == buffer.get(), "LiteRT replaced Android buffer handle");
            cl_mem imported = nullptr;
            check(LiteRtGetTensorBufferOpenClMemory(wrapped, &imported), "import Android buffer into OpenCL");
            cl_context attached = nullptr;
            check(cl.clGetMemObjectInfo(imported, CL_MEM_CONTEXT, sizeof(attached), &attached, nullptr),
                  "check Android buffer context");
            require(attached == context, "Imported Android buffer is in a different OpenCL context");
            // Observes visibility in both directions after synchronization. This
            // cannot establish whether the driver moves any physical memory.
            constexpr uint32_t cpuWord = 0x3f800000, gpuWord = 0x3f000000;
            void* mapped = nullptr;
            check(AHardwareBuffer_lock(buffer.get(), cpuWrite, -1, nullptr, &mapped), "map Android buffer for writing");
            std::memcpy(mapped, &cpuWord, sizeof(cpuWord));
            check(AHardwareBuffer_unlock(buffer.get(), nullptr), "unmap Android write");
            uint32_t observed = 0;
            check(cl.clEnqueueReadBuffer(queue, imported, CL_TRUE, 0, sizeof(observed), &observed, 0, nullptr, nullptr),
                  "observe Android write from OpenCL");
            require(observed == cpuWord, "OpenCL did not observe the Android write");
            check(cl.clEnqueueFillBuffer(queue, imported, &gpuWord, sizeof(gpuWord), 0, sizeof(gpuWord), 0, nullptr, nullptr),
                  "write imported Android buffer from OpenCL");
            check(cl.clFinish(queue), "fence the OpenCL write");
            check(AHardwareBuffer_lock(buffer.get(), cpuRead, -1, nullptr, &mapped), "map Android buffer for reading");
            std::memcpy(&observed, mapped, sizeof(observed));
            check(AHardwareBuffer_unlock(buffer.get(), nullptr), "unmap Android read");
            require(observed == gpuWord, "Android did not observe the OpenCL write");
            __android_log_print(ANDROID_LOG_INFO, tag, "AHardwareBuffer OpenCL visibility verified CPU to GPU and back");
        } catch (const std::exception& error) {
            __android_log_print(ANDROID_LOG_WARN, tag, "AHardwareBuffer OpenCL probe failed: %s", error.what());
        }
    }

    void verifyDelegate() {
        bool fullyAccelerated = false;
        check(LiteRtCompiledModelIsNonCpuFullyAccelerated(compiled, &fullyAccelerated), "non-CPU delegation");
        require(fullyAccelerated, "CompiledModel has CPU fallback");
        LiteRtDelegationMetricsConst metrics = nullptr;
        check(LiteRtCompiledModelGetDelegationMetrics(compiled, &metrics), "delegation metrics");
        int total = 0, delegated = 0, partitions = 0;
        LiteRtParamIndex count = 0;
        check(LiteRtGetDelegationMetricsTotalNodeCount(metrics, &total), "graph node count");
        check(LiteRtGetNumDelegationMetricsAccelerators(metrics, &count), "accelerator count");
        for (LiteRtParamIndex i = 0; i < count; ++i) {
            LiteRtAcceleratorDelegationMetricsConst entry = nullptr;
            LiteRtHwAcceleratorSet type = 0;
            check(LiteRtGetDelegationMetricsAccelerator(metrics, i, &entry), "accelerator metrics");
            check(LiteRtGetAcceleratorDelegationMetricsHardwareType(entry, &type), "accelerator type");
            if (type & kLiteRtHwAcceleratorGpu) {
                int nodes = 0, parts = 0;
                check(LiteRtGetAcceleratorDelegationMetricsDelegatedNodeCount(entry, &nodes), "GPU nodes");
                check(LiteRtGetAcceleratorDelegationMetricsPartitionCount(entry, &parts), "GPU partitions");
                delegated += nodes;
                partitions += parts;
            }
        }
        require(total == 4 && delegated == 4 && partitions == 1, "Expected all four convolutions on GPU");
        __android_log_print(ANDROID_LOG_INFO, tag, "CompiledModel GPU OpenCL nodes=%d total=%d partitions=%d",
                            delegated, total, partitions);
    }

    LiteRtTensorBuffer makeTensor(LiteRtSignature signature, bool sourceTensor, cl_mem& memory) {
        LiteRtTensor tensor = nullptr;
        LiteRtTensorBufferRequirements requirements = nullptr;
        check(sourceTensor ? LiteRtGetSignatureInputTensorByIndex(signature, 0, &tensor)
                           : LiteRtGetSignatureOutputTensorByIndex(signature, 0, &tensor), "read tensor");
        check(sourceTensor ? LiteRtGetCompiledModelInputBufferRequirements(compiled, 0, 0, &requirements)
                           : LiteRtGetCompiledModelOutputBufferRequirements(compiled, 0, 0, &requirements),
              "tensor buffer requirements");
        LiteRtRankedTensorType ranked{};
        check(LiteRtGetRankedTensorType(tensor, &ranked), "tensor layout");
        int channels = sourceTensor ? 8 : 4;
        require(ranked.element_type == kLiteRtElementTypeFloat32 && ranked.layout.rank == 4 &&
                ranked.layout.dimensions[0] == 1 && ranked.layout.dimensions[1] == extent &&
                ranked.layout.dimensions[2] == extent && ranked.layout.dimensions[3] == channels &&
                !ranked.layout.has_strides, "Unexpected FP32 tensor shape or strides");
        const auto preferred = external_ ? kLiteRtTensorBufferTypeOpenClBuffer
                                         : kLiteRtTensorBufferTypeOpenClBufferPacked;
        int available = 0;
        check(LiteRtGetNumTensorBufferRequirementsSupportedBufferTypes(requirements, &available), "buffer type count");
        bool supported = false;
        for (int i = 0; i < available; ++i) {
            LiteRtTensorBufferType candidate;
            check(LiteRtGetTensorBufferRequirementsSupportedTensorBufferType(requirements, i, &candidate), "buffer type");
            supported |= preferred == candidate;
            __android_log_print(ANDROID_LOG_INFO, tag, "%s supported buffer type=%d", sourceTensor ? "input" : "output", candidate);
        }
        require(supported, "Requested OpenCL tensor layout unavailable");
        size_t bufferBytes = 0;
        check(LiteRtGetTensorBufferRequirementsBufferSize(requirements, &bufferBytes), "tensor storage size");
        require(bufferBytes >= extent * extent * channels * sizeof(float), "Tensor capacity is too small");
        LiteRtTensorBuffer value = nullptr;
        check(LiteRtCreateManagedTensorBuffer(environment, preferred, &ranked, bufferBytes, &value), "allocate CL tensor");
        try {
            check(LiteRtGetTensorBufferOpenClMemory(value, &memory), "extract resident cl_mem");
            cl_context attached = nullptr;
            size_t capacity = 0;
            check(cl.clGetMemObjectInfo(memory, CL_MEM_CONTEXT, sizeof(attached), &attached, nullptr), "tensor context");
            check(cl.clGetMemObjectInfo(memory, CL_MEM_SIZE, sizeof(capacity), &capacity, nullptr), "tensor allocation bytes");
            require(attached == context && capacity >= bufferBytes, "Tensor does not share the application OpenCL context");
            allocatedBytes += capacity;
        } catch (...) {
            LiteRtDestroyTensorBuffer(value);
            throw;
        }
        return value;
    }

    void makeKernels(cl_device_id device, bool audit, int math) {
        cl_int rc = CL_SUCCESS;
        const char* code = shader;
        program = cl.clCreateProgramWithSource(context, 1, &code, nullptr, &rc);
        check(rc, "create ISP program");
        cl_device_fp_config fpConfig = 0;
        check(cl.clGetDeviceInfo(device, CL_DEVICE_SINGLE_FP_CONFIG, sizeof(fpConfig), &fpConfig, nullptr),
              "precision capabilities");
        bool correctlyRounded = (fpConfig & CL_FP_CORRECTLY_ROUNDED_DIVIDE_SQRT) != 0;
        __android_log_print(ANDROID_LOG_INFO, tag, "FP32 correctly-rounded divide/sqrt advertised=%d", correctlyRounded);
        std::string flags = "-DAUDIT_CAPTURE=" + std::to_string(audit) + " -DISP_MATH=" + std::to_string(math);
        if (correctlyRounded) flags += " -cl-fp32-correctly-rounded-divide-sqrt";
        rc = cl.clBuildProgram(program, 1, &device, flags.c_str(), nullptr, nullptr);
        if (rc != CL_SUCCESS) {
            size_t length = 0;
            if (cl.clGetProgramBuildInfo(program, device, CL_PROGRAM_BUILD_LOG, 0, nullptr, &length) == CL_SUCCESS && length > 0) {
                std::string message(length, '\0');
                cl.clGetProgramBuildInfo(program, device, CL_PROGRAM_BUILD_LOG, length, message.data(), nullptr);
                __android_log_print(ANDROID_LOG_ERROR, tag, "%s", message.c_str());
            }
            check(rc, "build ISP program");
        }
        prepare = cl.clCreateKernel(program, "prepare", &rc);
        check(rc, "prepare kernel");
        restore = cl.clCreateKernel(program, "restore", &rc);
        check(rc, "restore kernel");
        develop = cl.clCreateKernel(program, "develop", &rc);
        check(rc, "develop kernel");
    }

    cl_mem allocate(size_t bytes, cl_mem_flags flags) {
        cl_int error = CL_SUCCESS;
        cl_mem buffer = cl.clCreateBuffer(context, flags, bytes, nullptr, &error);
        check(error, "allocate ISP CL memory");
        allocatedBytes += bytes;
        return buffer;
    }

    template<class... Args>
    void arguments(cl_kernel kernel, const Args&... args) {
        cl_uint i = 0;
        (check(cl.clSetKernelArg(kernel, i++, sizeof(Args), &args), "ISP kernel argument"), ...);
    }

    void clearTensorEvents() {
        LiteRtClearTensorBufferEvent(input);
        LiteRtClearTensorBufferEvent(output);
    }

    uint64_t profileTime(cl_event event, cl_profiling_info property) {
        cl_ulong value = 0;
        check(cl.clGetEventProfilingInfo(event, property, sizeof(value), &value, nullptr), "read CL profile");
        return value;
    }

    jlong duration(cl_event event) {
        return profileTime(event, CL_PROFILING_COMMAND_END) - profileTime(event, CL_PROFILING_COMMAND_START);
    }

    void clearAudit(size_t bytes) {
        static constexpr cl_uint zero = 0;
        check(cl.clEnqueueFillBuffer(queue, scratch, &zero, sizeof(zero), 0, bytes, 0, nullptr, nullptr),
              "clear audit scratch");
    }

    void readAudit(cl_mem source, void* destination, size_t bytes) {
        check(cl.clEnqueueReadBuffer(queue, source, CL_FALSE, 0, bytes, destination, 0, nullptr, nullptr),
              "read audit snapshot");
    }

    void work(const void* sourceHost, const jfloat* profile, const float* maps, jint mapBytes,
              void* rawHost, void* rgbHost, std::array<jlong, 13 + tiles>& result,
              FrameEvents& events, int64_t started, fidelity::Trace* trace,
              const fidelity::Trace* reference, const void* referenceRaw) {
        require((trace == nullptr) == (scratch == nullptr), "Audit build/frame mismatch");
        require((reference == nullptr) == (referenceRaw == nullptr), "Replay inputs must be paired");
        require(mapBytes > 0 && static_cast<size_t>(mapBytes) <= parameterBytes, "Invalid metadata size");
        cl_int4 offsets{};
        int cursor = 9;
        for (int phase = 0; phase < 4; ++phase) {
            require((cursor + 2) * sizeof(float) <= static_cast<size_t>(mapBytes), "Invalid map offset");
            offsets.s[phase] = cursor;
            cursor += 2 + static_cast<int>(maps[cursor]) * static_cast<int>(maps[cursor + 1]) + 3 * (width + height);
        }
        require(cursor * sizeof(float) == static_cast<size_t>(mapBytes), "Unexpected GainMap layout");
        cl_event* pixelsUpload = events.next();
        cl_event* metadataUpload = events.next();
        check(cl.clEnqueueWriteBuffer(queue, source, CL_FALSE, 0, rawBytes, sourceHost, 0, nullptr, pixelsUpload), "upload RAW");
        check(cl.clEnqueueWriteBuffer(queue, metadata, CL_FALSE, 0, mapBytes, maps, 0, nullptr, metadataUpload), "upload GainMaps");
        cl_uint flag = 0;
        check(cl.clEnqueueFillBuffer(queue, invalid, &flag, sizeof(flag), 0, sizeof(flag), 0, nullptr, nullptr),
              "reset finite check");
        cl_float4 slope{}, offset{};
        for (int i = 0; i < 4; ++i) { slope.s[i] = profile[2 * i]; offset.s[i] = profile[2 * i + 1]; }
        const cl_int external = external_;
        const size_t inputRange[] = {extent, extent};
        std::array<cl_event*, tiles> prepared{}, inferred{}, restored{};
        int index = 0;
        for (int y = 0; y < height; y += tile) for (int x = 0; x < width; x += tile, ++index) {
            const int slot = trace ? fidelity::slot(index) : -1;
            fidelity::Tile* sampled = slot >= 0 ? &trace->tiles[slot] : nullptr;
            const cl_int capture = sampled && !reference;
            prepared[index] = events.next();
            arguments(prepare, source, inputCl, slope, offset, y, x, external, scratch, capture);
            check(cl.clEnqueueNDRangeKernel(queue, prepare, 2, nullptr, inputRange, nullptr, 0, nullptr, prepared[index]),
                  "enqueue Bayer and noise tile");
            if (capture) {
                readAudit(scratch, sampled->noise, sizeof(sampled->noise));
                readAudit(inputCl, sampled->input, sizeof(sampled->input));
            }
            cl_event* inputReady = prepared[index];
            if (sampled && reference) {
                inputReady = events.next();
                check(cl.clEnqueueWriteBuffer(queue, inputCl, CL_FALSE, 0, sizeof(sampled->input),
                          reference->tiles[slot].input, 0, nullptr, inputReady), "replay exact NEON tensor");
                readAudit(inputCl, sampled->input, sizeof(sampled->input));
            }
            LiteRtEvent ready = nullptr;
            check(LiteRtCreateEventFromOpenClEvent(environment, *inputReady, &ready), "wrap input readiness");
            const auto status = LiteRtSetTensorBufferEvent(input, ready);
            if (status != kLiteRtStatusOk) LiteRtDestroyEvent(ready);
            check(status, "attach input readiness");
            bool async = true;
            check(LiteRtRunCompiledModelAsync(compiled, 0, 1, &input, 1, &output, &async), "enqueue the model");
            cl_event fence = nullptr;
            if (async) {
                LiteRtEvent done = nullptr;
                check(LiteRtGetTensorBufferEvent(output, &done), "get asynchronous output readiness");
                require(done != nullptr, "Async inference without a completion event");
                check(LiteRtGetEventOpenClEvent(done, &fence), "OpenCL completion fence");
                require(fence != nullptr, "Missing OpenCL completion fence");
                cl_command_queue submitted = nullptr;
                check(cl.clGetEventInfo(fence, CL_EVENT_COMMAND_QUEUE, sizeof(submitted), &submitted, nullptr), "producer queue");
                result[8] += 1;
                result[9] += submitted == queue;
            }
            inferred[index] = events.next();
            check(cl.clEnqueueMarkerWithWaitList(queue, fence ? 1 : 0, fence ? &fence : nullptr, inferred[index]),
                  "reuse tile after network fence");
            if (sampled) {
                readAudit(outputCl, sampled->prediction, sizeof(sampled->prediction));
                if (reference) {
                    // The CNN snapshot above precedes this overwrite. Feed the
                    // baseline prediction to isolate correction and clipping.
                    check(cl.clEnqueueWriteBuffer(queue, outputCl, CL_FALSE, 0, sizeof(sampled->prediction),
                          reference->tiles[slot].prediction, 0, nullptr, nullptr), "replay exact NEON prediction");
                }
            }
            const size_t useful[] = {static_cast<size_t>(std::min(tile, width - x)),
                                     static_cast<size_t>(std::min(tile, height - y))};
            restored[index] = events.next();
            if (sampled) clearAudit(sizeof(sampled->correction));
            arguments(restore, source, inputCl, outputCl, raw, invalid, y, x, external, scratch, cl_int(sampled != nullptr));
            check(cl.clEnqueueNDRangeKernel(queue, restore, 2, nullptr, useful, nullptr, 0, nullptr, restored[index]),
                  "enqueue Bayer reconstruction");
            if (sampled) readAudit(scratch, sampled->correction, sizeof(sampled->correction));
            clearTensorEvents();
        }
        if (referenceRaw) {
            check(cl.clEnqueueWriteBuffer(queue, raw, CL_FALSE, 0, rawBytes, referenceRaw, 0, nullptr, nullptr),
                  "replay exact NEON restored mosaic");
        }
        const size_t renderRange[] = {2 * width, 2 * height};
        const size_t group[] = {16, 16};
        cl_event* rendered = events.next();
        arguments(develop, raw, metadata, offsets, lut, rgb, scratch);
        check(cl.clEnqueueNDRangeKernel(queue, develop, 2, nullptr, renderRange, group, 0, nullptr, rendered),
              "enqueue fused GainMaps and MHC");
        if (trace) readAudit(scratch, trace->display, sizeof(trace->display));
        cl_event* rawRead = events.next();
        cl_event* rgbRead = events.next();
        cl_event* validRead = events.next();
        check(cl.clEnqueueReadBuffer(queue, raw, CL_FALSE, 0, rawBytes, rawHost, 0, nullptr, rawRead), "read RAW result");
        check(cl.clEnqueueReadBuffer(queue, rgb, CL_FALSE, 0, rgbBytes, rgbHost, 0, nullptr, rgbRead), "read RGB result");
        // Blocking final read reclaims the last host destination before returning, even on failure.
        const int64_t wait = now();
        check(cl.clEnqueueReadBuffer(queue, invalid, CL_TRUE, 0, sizeof(flag), &flag, 0, nullptr, validRead),
              "read validity and wait for final fence");
        result[5] = now() - wait;
        require(flag == 0, "Nonfinite neural output on GPU");
        result[6] = wait - started;
        result[1] = duration(*pixelsUpload) + duration(*metadataUpload);
        result[3] = duration(*rendered);
        result[4] = duration(*rawRead) + duration(*rgbRead) + duration(*validRead);
        result[10] = allocatedBytes;
        result[11] = 2;
        result[12] = 3;
        for (int i = 0; i < tiles; ++i) {
            result[0] += duration(*prepared[i]);
            result[2] += duration(*restored[i]);
            result[13 + i] = profileTime(*inferred[i], CL_PROFILING_COMMAND_END) -
                             profileTime(*prepared[i], CL_PROFILING_COMMAND_END);
            result[7] += result[13 + i];
        }
    }
};

void throwToJava(JNIEnv* env, const std::exception& error) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
}

std::string stringArg(JNIEnv* env, jstring value) {
    const char* utf8 = env->GetStringUTFChars(value, nullptr);
    require(utf8 != nullptr, "Cannot read model argument");
    std::string result(utf8);
    env->ReleaseStringUTFChars(value, utf8);
    return result;
}
}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_miokzz_rawbench_ResidentIsp_create(JNIEnv* env, jclass, jstring model, jstring runtimeDir,
                                           jobject lut, jboolean external, jboolean audit, jint math, jlongArray timing) {
    try {
        std::array<jlong, 3> metrics{};
        auto resident = std::make_unique<Resident>();
        const auto modelPath = stringArg(env, model);
        const auto libraryPath = stringArg(env, runtimeDir);
        resident->init(modelPath.c_str(), libraryPath.c_str(), env->GetDirectBufferAddress(lut), external, audit, math, metrics);
        env->SetLongArrayRegion(timing, 0, metrics.size(), metrics.data());
        return reinterpret_cast<jlong>(resident.release());
    } catch (const std::exception& error) {
        throwToJava(env, error);
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_miokzz_rawbench_ResidentIsp_runFrame(JNIEnv* env, jclass, jlong handle, jobject input, jfloatArray profile,
                                             jobject maps, jint mapBytes, jobject raw, jobject rgb, jlongArray timing,
                                             jobject trace, jobject reference, jobject referenceRaw) {
    try {
        std::array<jlong, 13 + tiles> metrics{};
        jfloat coefficients[8];
        env->GetFloatArrayRegion(profile, 0, 8, coefficients);
        reinterpret_cast<Resident*>(handle)->frame(env->GetDirectBufferAddress(input), coefficients,
                static_cast<const float*>(env->GetDirectBufferAddress(maps)), mapBytes,
                env->GetDirectBufferAddress(raw), env->GetDirectBufferAddress(rgb), metrics,
                trace ? static_cast<fidelity::Trace*>(env->GetDirectBufferAddress(trace)) : nullptr,
                reference ? static_cast<const fidelity::Trace*>(env->GetDirectBufferAddress(reference)) : nullptr,
                referenceRaw ? env->GetDirectBufferAddress(referenceRaw) : nullptr);
        env->SetLongArrayRegion(timing, 0, metrics.size(), metrics.data());
    } catch (const std::exception& error) {
        throwToJava(env, error);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_miokzz_rawbench_ResidentIsp_destroy(JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<Resident*>(handle);
}
