// ForestLink TFLite Micro ESP32-S3 Inference Harness
// Requires: Arduino_TensorFlowLite library or esp-nn component

#include <TensorFlowLite_ESP32.h>
#include "tensorflow/lite/micro/all_ops_resolver.h"
#include "tensorflow/lite/micro/micro_error_reporter.h"
#include "tensorflow/lite/micro/micro_interpreter.h"
#include "tensorflow/lite/schema/schema_generated.h"
#include "model_tflite_data.h" // Generated via xxd -i model.tflite

namespace {
    tflite::ErrorReporter* error_reporter = nullptr;
    const tflite::Model* model = nullptr;
    tflite::MicroInterpreter* interpreter = nullptr;
    TfLiteTensor* input = nullptr;
    TfLiteTensor* output = nullptr;

    // Allocate 16KB SRAM for tensor operations
    constexpr int kTensorArenaSize = 16 * 1024;
    uint8_t tensor_arena[kTensorArenaSize];
}

bool init_tflite_route_scorer() {
    static tflite::MicroErrorReporter micro_error_reporter;
    error_reporter = &micro_error_reporter;

    model = tflite::GetModel(g_model_tflite_data);
    if (model->version() != TFLITE_SCHEMA_VERSION) {
        TF_LITE_REPORT_ERROR(error_reporter, "Model schema mismatch!");
        return false;
    }

    static tflite::AllOpsResolver resolver;
    static tflite::MicroInterpreter static_interpreter(
        model, resolver, tensor_arena, kTensorArenaSize, error_reporter);
    interpreter = &static_interpreter;

    if (interpreter->AllocateTensors() != kTfLiteOk) {
        TF_LITE_REPORT_ERROR(error_reporter, "AllocateTensors() failed!");
        return false;
    }

    input = interpreter->input(0);
    output = interpreter->output(0);
    return true;
}

float predict_tflite_reliability(const float* features_12) {
    if (!interpreter) return 0.5f;

    // Load features into input tensor
    for (int i = 0; i < 12; i++) {
        input->data.f[i] = features_12[i];
    }

    if (interpreter->Invoke() != kTfLiteOk) {
        return 0.5f; // Fallback neutral score
    }

    return output->data.f[0]; // Sigmoid probability
}
