#include <jni.h>

#include <string>

#include <basalt/utils/vio_config.h>

namespace {
constexpr const char* kBasaltCommit =
    "0f3b2b52c807f70ff4e2973ce253c73329eea7bc";
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_trackstudio_artracker_BasaltNativeBridge_nativeBuildInfo(
    JNIEnv* env, jclass) {
  // Constructing the upstream config forces a real link-time/runtime reference
  // to libbasalt.so. This smoke test does not start the VIO estimator.
  basalt::VioConfig config;
  const std::string info =
      std::string("Basalt JNI linked; commit=") + kBasaltCommit +
      "; optical_flow_type=" + config.optical_flow_type;
  return env->NewStringUTF(info.c_str());
}
