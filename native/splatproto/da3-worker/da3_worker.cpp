// Persistent QNN HTP worker for Depth-Anything-3 context binaries.
//
// Loads libQnnHtp.so + libQnnSystem.so via dlopen, parses the context binary's
// graph/tensor metadata, creates the QNN context ONCE on the Hexagon HTP (in
// BURST power mode), and serves repeated INFER <in_raw> <out_raw> requests over
// stdin/stdout with zero model reload between frames.

#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dlfcn.h>
#include <fstream>
#include <iostream>
#include <sstream>
#include <string>
#include <vector>

#include "QnnCommon.h"
#include "QnnInterface.h"
#include "QnnTypes.h"
#include "System/QnnSystemInterface.h"
#include "HTP/QnnHtpDevice.h"
#include "HTP/QnnHtpPerfInfrastructure.h"

namespace {

using QnnInterfaceGetProvidersFn = Qnn_ErrorHandle_t (*)(const QnnInterface_t***, uint32_t*);
using QnnSystemInterfaceGetProvidersFn = Qnn_ErrorHandle_t (*)(const QnnSystemInterface_t***, uint32_t*);

void qnnLogCallback(const char* /*fmt*/, QnnLog_Level_t /*level*/, uint64_t /*ts*/, va_list /*args*/) {}

size_t dataTypeBytes(Qnn_DataType_t dt) {
    switch (dt) {
        case QNN_DATATYPE_INT_8:
        case QNN_DATATYPE_UINT_8:
        case QNN_DATATYPE_SFIXED_POINT_8:
        case QNN_DATATYPE_UFIXED_POINT_8:
        case QNN_DATATYPE_BOOL_8:
        case QNN_DATATYPE_FLOAT_8: return 1;
        case QNN_DATATYPE_INT_16:
        case QNN_DATATYPE_UINT_16:
        case QNN_DATATYPE_FLOAT_16:
        case QNN_DATATYPE_BFLOAT_16:
        case QNN_DATATYPE_SFIXED_POINT_16:
        case QNN_DATATYPE_UFIXED_POINT_16: return 2;
        case QNN_DATATYPE_INT_32:
        case QNN_DATATYPE_UINT_32:
        case QNN_DATATYPE_FLOAT_32:
        case QNN_DATATYPE_SFIXED_POINT_32:
        case QNN_DATATYPE_UFIXED_POINT_32: return 4;
        case QNN_DATATYPE_INT_64:
        case QNN_DATATYPE_UINT_64:
        case QNN_DATATYPE_FLOAT_64: return 8;
        default: return 0;
    }
}

struct OwnedTensor {
    Qnn_Tensor_t tensor = QNN_TENSOR_INIT;
    std::string name;
    std::vector<uint32_t> dims;
    std::vector<uint8_t> buf;
};

bool cloneTensor(const Qnn_Tensor_t& src, OwnedTensor& dst) {
    dst.tensor = src;
    uint32_t rank = 0;
    const uint32_t* dims = nullptr;
    Qnn_DataType_t dt = QNN_DATATYPE_UNDEFINED;
    const char* name = nullptr;
    if (src.version == QNN_TENSOR_VERSION_1) {
        rank = src.v1.rank;
        dims = src.v1.dimensions;
        dt = src.v1.dataType;
        name = src.v1.name;
    } else if (src.version == QNN_TENSOR_VERSION_2) {
        rank = src.v2.rank;
        dims = src.v2.dimensions;
        dt = src.v2.dataType;
        name = src.v2.name;
    } else {
        return false;
    }
    dst.name = name ? name : "";
    dst.dims.assign(dims, dims + rank);
    size_t elemSize = dataTypeBytes(dt);
    if (elemSize == 0 || rank == 0) return false;
    size_t count = 1;
    for (uint32_t d : dst.dims) count *= static_cast<size_t>(d);
    dst.buf.assign(count * elemSize, 0);
    if (dst.tensor.version == QNN_TENSOR_VERSION_1) {
        dst.tensor.v1.name = dst.name.c_str();
        dst.tensor.v1.dimensions = dst.dims.data();
        dst.tensor.v1.memType = QNN_TENSORMEMTYPE_RAW;
        dst.tensor.v1.clientBuf.data = dst.buf.data();
        dst.tensor.v1.clientBuf.dataSize = static_cast<uint32_t>(dst.buf.size());
    } else {
        dst.tensor.v2.name = dst.name.c_str();
        dst.tensor.v2.dimensions = dst.dims.data();
        dst.tensor.v2.memType = QNN_TENSORMEMTYPE_RAW;
        dst.tensor.v2.clientBuf.data = dst.buf.data();
        dst.tensor.v2.clientBuf.dataSize = static_cast<uint32_t>(dst.buf.size());
        dst.tensor.v2.isDynamicDimensions = nullptr;
    }
    return true;
}

bool extractGraphMeta(
    const QnnSystemContext_BinaryInfo_t* info,
    std::string& graphName,
    std::vector<OwnedTensor>& inputs,
    std::vector<OwnedTensor>& outputs
) {
    if (!info) return false;
    uint32_t numGraphs = 0;
    const QnnSystemContext_GraphInfo_t* graphs = nullptr;
    if (info->version == QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_1) {
        numGraphs = info->contextBinaryInfoV1.numGraphs;
        graphs = info->contextBinaryInfoV1.graphs;
    } else if (info->version == QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_2) {
        numGraphs = info->contextBinaryInfoV2.numGraphs;
        graphs = info->contextBinaryInfoV2.graphs;
    } else if (info->version == QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_3) {
        numGraphs = info->contextBinaryInfoV3.numGraphs;
        graphs = info->contextBinaryInfoV3.graphs;
    } else {
        return false;
    }
    if (numGraphs == 0 || !graphs) return false;
    const auto& g = graphs[0];
    const char* gName = nullptr;
    uint32_t nIn = 0, nOut = 0;
    const Qnn_Tensor_t* tIn = nullptr;
    const Qnn_Tensor_t* tOut = nullptr;
    if (g.version == QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_1) {
        gName = g.graphInfoV1.graphName;
        nIn = g.graphInfoV1.numGraphInputs; tIn = g.graphInfoV1.graphInputs;
        nOut = g.graphInfoV1.numGraphOutputs; tOut = g.graphInfoV1.graphOutputs;
    } else if (g.version == QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_2) {
        gName = g.graphInfoV2.graphName;
        nIn = g.graphInfoV2.numGraphInputs; tIn = g.graphInfoV2.graphInputs;
        nOut = g.graphInfoV2.numGraphOutputs; tOut = g.graphInfoV2.graphOutputs;
    } else if (g.version == QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_3) {
        gName = g.graphInfoV3.graphName;
        nIn = g.graphInfoV3.numGraphInputs; tIn = g.graphInfoV3.graphInputs;
        nOut = g.graphInfoV3.numGraphOutputs; tOut = g.graphInfoV3.graphOutputs;
    } else {
        return false;
    }
    if (!gName || nIn == 0 || nOut == 0 || !tIn || !tOut) return false;
    graphName = gName;
    inputs.resize(nIn);
    for (uint32_t i = 0; i < nIn; ++i) {
        if (!cloneTensor(tIn[i], inputs[i])) return false;
    }
    outputs.resize(nOut);
    for (uint32_t i = 0; i < nOut; ++i) {
        if (!cloneTensor(tOut[i], outputs[i])) return false;
    }
    return true;
}

} // namespace

int main(int argc, char** argv) {
    if (argc < 2) {
        std::fprintf(stderr, "usage: da3-worker <context_binary.bin> [libQnnHtp.so] [libQnnSystem.so]\n");
        return 2;
    }
    const std::string modelPath = argv[1];
    const std::string htpLibPath = argc >= 3 ? argv[2] : "libQnnHtp.so";
    const std::string sysLibPath = argc >= 4 ? argv[3] : "libQnnSystem.so";

    const auto tLoad0 = std::chrono::steady_clock::now();

    void* htpLib = dlopen(htpLibPath.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (!htpLib) {
        std::fprintf(stderr, "dlopen(%s) failed: %s\n", htpLibPath.c_str(), dlerror());
        return 3;
    }
    void* sysLib = dlopen(sysLibPath.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (!sysLib) {
        std::fprintf(stderr, "dlopen(%s) failed: %s\n", sysLibPath.c_str(), dlerror());
        return 4;
    }

    auto getProviders = reinterpret_cast<QnnInterfaceGetProvidersFn>(dlsym(htpLib, "QnnInterface_getProviders"));
    auto getSysProviders = reinterpret_cast<QnnSystemInterfaceGetProvidersFn>(dlsym(sysLib, "QnnSystemInterface_getProviders"));
    if (!getProviders || !getSysProviders) {
        std::fprintf(stderr, "missing QNN provider symbols\n");
        return 5;
    }

    const QnnInterface_t** providers = nullptr;
    uint32_t numProviders = 0;
    if (getProviders(&providers, &numProviders) != QNN_SUCCESS || numProviders == 0) {
        std::fprintf(stderr, "QnnInterface_getProviders failed\n");
        return 6;
    }
    const QNN_INTERFACE_VER_TYPE* qnn = nullptr;
    for (uint32_t i = 0; i < numProviders; ++i) {
        if (providers[i]->apiVersion.coreApiVersion.major == QNN_API_VERSION_MAJOR &&
            providers[i]->apiVersion.coreApiVersion.minor >= QNN_API_VERSION_MINOR) {
            qnn = &providers[i]->QNN_INTERFACE_VER_NAME;
            break;
        }
    }
    if (!qnn) qnn = &providers[0]->QNN_INTERFACE_VER_NAME;

    const QnnSystemInterface_t** sysProviders = nullptr;
    uint32_t numSysProviders = 0;
    if (getSysProviders(&sysProviders, &numSysProviders) != QNN_SUCCESS || numSysProviders == 0) {
        std::fprintf(stderr, "QnnSystemInterface_getProviders failed\n");
        return 7;
    }
    const QNN_SYSTEM_INTERFACE_VER_TYPE* qnnSys = &sysProviders[0]->QNN_SYSTEM_INTERFACE_VER_NAME;

    // Read context binary into memory.
    std::ifstream mf(modelPath, std::ios::binary | std::ios::ate);
    if (!mf) {
        std::fprintf(stderr, "cannot open model: %s\n", modelPath.c_str());
        return 8;
    }
    const auto modelSize = mf.tellg();
    mf.seekg(0, std::ios::beg);
    std::vector<uint8_t> binBuf(static_cast<size_t>(modelSize));
    if (!mf.read(reinterpret_cast<char*>(binBuf.data()), modelSize)) {
        std::fprintf(stderr, "failed reading model\n");
        return 9;
    }

    // Inspect context binary metadata.
    QnnSystemContext_Handle_t sysCtx = nullptr;
    if (qnnSys->systemContextCreate(&sysCtx) != QNN_SUCCESS) {
        std::fprintf(stderr, "systemContextCreate failed\n");
        return 10;
    }
    const QnnSystemContext_BinaryInfo_t* binInfo = nullptr;
    Qnn_ContextBinarySize_t binInfoSize = 0;
    if (qnnSys->systemContextGetBinaryInfo(sysCtx, binBuf.data(), binBuf.size(), &binInfo, &binInfoSize) != QNN_SUCCESS) {
        std::fprintf(stderr, "systemContextGetBinaryInfo failed\n");
        qnnSys->systemContextFree(sysCtx);
        return 11;
    }
    std::string graphName;
    std::vector<OwnedTensor> ownedIn, ownedOut;
    if (!extractGraphMeta(binInfo, graphName, ownedIn, ownedOut)) {
        std::fprintf(stderr, "extractGraphMeta failed\n");
        qnnSys->systemContextFree(sysCtx);
        return 12;
    }
    qnnSys->systemContextFree(sysCtx);

    // Pick the depth output tensor (named *depth* if multiple outputs exist, else 0).
    size_t depthOutIdx = 0;
    for (size_t i = 0; i < ownedOut.size(); ++i) {
        if (ownedOut[i].name.find("depth") != std::string::npos) { depthOutIdx = i; break; }
    }

    // Build flat Qnn_Tensor_t arrays pointing at owned buffers (stable addresses!).
    std::vector<Qnn_Tensor_t> inTensors(ownedIn.size());
    std::vector<Qnn_Tensor_t> outTensors(ownedOut.size());
    for (size_t i = 0; i < ownedIn.size(); ++i) inTensors[i] = ownedIn[i].tensor;
    for (size_t i = 0; i < ownedOut.size(); ++i) outTensors[i] = ownedOut[i].tensor;

    // Initialize QNN HTP backend, device, BURST power config, and persistent context.
    Qnn_LogHandle_t logHandle = nullptr;
    if (qnn->logCreate) qnn->logCreate(qnnLogCallback, QNN_LOG_LEVEL_ERROR, &logHandle);

    Qnn_BackendHandle_t backend = nullptr;
    if (qnn->backendCreate(logHandle, nullptr, &backend) != QNN_SUCCESS) {
        std::fprintf(stderr, "backendCreate failed\n");
        return 13;
    }

    Qnn_DeviceHandle_t device = nullptr;
    if (qnn->deviceCreate) {
        const auto drc = qnn->deviceCreate(logHandle, nullptr, &device);
        if (drc != QNN_SUCCESS && drc != QNN_COMMON_ERROR_NOT_SUPPORTED) {
            std::fprintf(stderr, "deviceCreate warning rc=%lu\n", static_cast<unsigned long>(drc));
            device = nullptr;
        }
    }

    // Best-effort HTP BURST performance profile (matches htp_config.json).
    QnnHtpDevice_PerfInfrastructure_t perfInfra = QNN_HTP_DEVICE_PERF_INFRASTRUCTURE_INIT;
    uint32_t powerConfigId = 0;
    bool havePowerId = false;
    if (qnn->deviceGetInfrastructure) {
        QnnDevice_Infrastructure_t infraPtr = nullptr;
        if (qnn->deviceGetInfrastructure(&infraPtr) == QNN_SUCCESS && infraPtr) {
            auto* htpInfra = reinterpret_cast<QnnHtpDevice_Infrastructure_t*>(infraPtr);
            if (htpInfra->infraType == QNN_HTP_DEVICE_INFRASTRUCTURE_TYPE_PERF) {
                perfInfra = htpInfra->perfInfra;
                if (perfInfra.createPowerConfigId && perfInfra.createPowerConfigId(0, 0, &powerConfigId) == QNN_SUCCESS) {
                    havePowerId = true;
                    QnnHtpPerfInfrastructure_PowerConfig_t dcvsCfg{};
                    dcvsCfg.option = QNN_HTP_PERF_INFRASTRUCTURE_POWER_CONFIGOPTION_DCVS_V3;
                    dcvsCfg.dcvsV3Config.contextId = powerConfigId;
                    dcvsCfg.dcvsV3Config.setDcvsEnable = 1;
                    dcvsCfg.dcvsV3Config.dcvsEnable = 1;
                    dcvsCfg.dcvsV3Config.powerMode = QNN_HTP_PERF_INFRASTRUCTURE_POWERMODE_PERFORMANCE_MODE;
                    dcvsCfg.dcvsV3Config.setSleepDisable = 1;
                    dcvsCfg.dcvsV3Config.sleepDisable = 1;
                    dcvsCfg.dcvsV3Config.setBusParams = 1;
                    dcvsCfg.dcvsV3Config.busVoltageCornerMin = DCVS_VOLTAGE_VCORNER_TURBO;
                    dcvsCfg.dcvsV3Config.busVoltageCornerTarget = DCVS_VOLTAGE_VCORNER_TURBO;
                    dcvsCfg.dcvsV3Config.busVoltageCornerMax = DCVS_VOLTAGE_VCORNER_TURBO_PLUS;
                    dcvsCfg.dcvsV3Config.setCoreParams = 1;
                    dcvsCfg.dcvsV3Config.coreVoltageCornerMin = DCVS_VOLTAGE_VCORNER_TURBO;
                    dcvsCfg.dcvsV3Config.coreVoltageCornerTarget = DCVS_VOLTAGE_VCORNER_TURBO;
                    dcvsCfg.dcvsV3Config.coreVoltageCornerMax = DCVS_VOLTAGE_VCORNER_TURBO_PLUS;
                    QnnHtpPerfInfrastructure_PowerConfig_t rpcCfg{};
                    rpcCfg.option = QNN_HTP_PERF_INFRASTRUCTURE_POWER_CONFIGOPTION_RPC_CONTROL_LATENCY;
                    rpcCfg.rpcControlLatencyConfig = 100;
                    const QnnHtpPerfInfrastructure_PowerConfig_t* cfgs[] = { &dcvsCfg, &rpcCfg, nullptr };
                    if (perfInfra.setPowerConfig) perfInfra.setPowerConfig(powerConfigId, cfgs);
                }
            }
        }
    }

    Qnn_ContextHandle_t context = nullptr;
    const auto crc = qnn->contextCreateFromBinary(
        backend, device, nullptr, binBuf.data(), static_cast<Qnn_ContextBinarySize_t>(binBuf.size()), &context, nullptr);
    if (crc != QNN_SUCCESS || !context) {
        std::fprintf(stderr, "contextCreateFromBinary failed rc=%lu\n", static_cast<unsigned long>(crc));
        return 14;
    }
    // Binary buffer no longer needed once deserialized onto HTP.
    binBuf.clear();
    binBuf.shrink_to_fit();

    Qnn_GraphHandle_t graph = nullptr;
    if (qnn->graphRetrieve(context, graphName.c_str(), &graph) != QNN_SUCCESS || !graph) {
        std::fprintf(stderr, "graphRetrieve(%s) failed\n", graphName.c_str());
        return 15;
    }

    const auto loadMs = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - tLoad0).count();
    std::printf("__DA3_READY__ in=%zu out=%zu graph=%s load_ms=%.1f\n",
                ownedIn[0].buf.size(), ownedOut[depthOutIdx].buf.size(), graphName.c_str(), loadMs);
    std::fflush(stdout);

    // Persistent command loop.
    std::string line;
    while (std::getline(std::cin, line)) {
        if (line.empty()) continue;
        std::istringstream iss(line);
        std::string cmd;
        iss >> cmd;
        if (cmd == "QUIT") break;
        if (cmd != "INFER") {
            std::printf("__DA3_ERR__ unknown_cmd\n");
            std::fflush(stdout);
            continue;
        }
        std::string inPath, outPath;
        iss >> inPath >> outPath;
        if (inPath.empty() || outPath.empty()) {
            std::printf("__DA3_ERR__ bad_args\n");
            std::fflush(stdout);
            continue;
        }
        std::ifstream fin(inPath, std::ios::binary);
        if (!fin || !fin.read(reinterpret_cast<char*>(ownedIn[0].buf.data()),
                              static_cast<std::streamsize>(ownedIn[0].buf.size()))) {
            std::printf("__DA3_ERR__ read_input_failed\n");
            std::fflush(stdout);
            continue;
        }
        const auto t0 = std::chrono::steady_clock::now();
        const auto erc = qnn->graphExecute(
            graph,
            inTensors.data(), static_cast<uint32_t>(inTensors.size()),
            outTensors.data(), static_cast<uint32_t>(outTensors.size()),
            nullptr, nullptr);
        const auto execMs = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
        if (erc != QNN_SUCCESS) {
            std::printf("__DA3_ERR__ execute_failed_%lu\n", static_cast<unsigned long>(erc));
            std::fflush(stdout);
            continue;
        }
        std::ofstream fout(outPath, std::ios::binary | std::ios::trunc);
        if (!fout || !fout.write(reinterpret_cast<const char*>(ownedOut[depthOutIdx].buf.data()),
                                 static_cast<std::streamsize>(ownedOut[depthOutIdx].buf.size()))) {
            std::printf("__DA3_ERR__ write_output_failed\n");
            std::fflush(stdout);
            continue;
        }
        fout.close();
        std::printf("__DA3_OK__ %.1f\n", execMs);
        std::fflush(stdout);
    }

    // Clean teardown.
    if (context && qnn->contextFree) qnn->contextFree(context, nullptr);
    if (havePowerId && perfInfra.destroyPowerConfigId) perfInfra.destroyPowerConfigId(powerConfigId);
    if (device && qnn->deviceFree) qnn->deviceFree(device);
    if (backend && qnn->backendFree) qnn->backendFree(backend);
    if (logHandle && qnn->logFree) qnn->logFree(logHandle);
    return 0;
}
