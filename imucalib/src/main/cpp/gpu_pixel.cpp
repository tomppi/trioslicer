// GLES 3.1 compute backend for the calibrator's pixel residual/Jacobian batch.
#include <jni.h>
#include <dlfcn.h>
#include <EGL/egl.h>
#include <GLES3/gl31.h>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>
#include <mutex>
#include <cmath>

namespace {
const char* kShader = R"GLSL(#version 310 es
layout(local_size_x = 64) in;
layout(std430, binding = 0) readonly buffer Coeff { float coeff[]; };
layout(std430, binding = 1) readonly buffer Base { int base[]; };
layout(std430, binding = 2) readonly buffer Basis { float basis[]; };
layout(std430, binding = 3) readonly buffer Points { float points[]; };
layout(std430, binding = 4) readonly buffer Uv { float uv[]; };
layout(std430, binding = 5) writeonly buffer Out { float outv[]; };
layout(std140, binding = 6) uniform Params {
  int N;
  float sigma;
  vec4 cam0; // fx, fy, cx, cy
  vec4 cam1; // k1, k2, p1, p2
} pc;

void main() {
  uint gi = gl_GlobalInvocationID.x;
  if (gi >= uint(pc.N)) return;
  int i = int(gi);
  int b = base[i];
  float u = uv[i * 2];
  float v = uv[i * 2 + 1];
  float c[36];
  for (int q = 0; q < 36; q++) c[q] = coeff[b * 6 + q];
  float p0 = 0.0, p1 = 0.0, p2 = 0.0, q0 = 0.0, q1 = 0.0, q2 = 0.0;
  for (int k = 0; k < 6; k++) {
    float bk = basis[i * 6 + k];
    p0 += bk * c[k * 6];     p1 += bk * c[k * 6 + 1]; p2 += bk * c[k * 6 + 2];
    q0 += bk * c[k * 6 + 3]; q1 += bk * c[k * 6 + 4]; q2 += bk * c[k * 6 + 5];
  }
  float th2 = q0 * q0 + q1 * q1 + q2 * q2;
  float R0, R1, R2, R3, R4, R5, R6, R7, R8;
  if (th2 < 1e-16) { R0 = 1.0; R1 = 0.0; R2 = 0.0; R3 = 0.0; R4 = 1.0; R5 = 0.0; R6 = 0.0; R7 = 0.0; R8 = 1.0; }
  else {
    float th = sqrt(th2); float kx = q0 / th, ky = q1 / th, kz = q2 / th;
    float ca = cos(th), sa = sin(th), va = 1.0 - ca;
    R0 = ca + kx * kx * va; R1 = kx * ky * va - kz * sa; R2 = kx * kz * va + ky * sa;
    R3 = ky * kx * va + kz * sa; R4 = ca + ky * ky * va; R5 = ky * kz * va - kx * sa;
    R6 = kz * kx * va - ky * sa; R7 = kz * ky * va + kx * sa; R8 = ca + kz * kz * va;
  }
  float dx = points[i * 3] - p0, dy = points[i * 3 + 1] - p1, dz = points[i * 3 + 2] - p2;
  float xc = R0 * dx + R3 * dy + R6 * dz;
  float yc = R1 * dx + R4 * dy + R7 * dz;
  float zc = R2 * dx + R5 * dy + R8 * dz;
  float res0 = 1000.0, res1 = 1000.0;
  float dUdxn = 0.0, dUdyn = 0.0, dVdxn = 0.0, dVdyn = 0.0;
  float xn = 0.0, yn = 0.0;
  float fx = pc.cam0.x, fy = pc.cam0.y, cx = pc.cam0.z, cy = pc.cam0.w;
  float k1 = pc.cam1.x, k2 = pc.cam1.y, pp1 = pc.cam1.z, pp2 = pc.cam1.w;
  if (zc > 1e-6) {
    xn = xc / zc; yn = yc / zc;
    float rr = xn * xn + yn * yn;
    float rad = 1.0 + k1 * rr + k2 * rr * rr;
    float dradx = 2.0 * k1 * xn + 4.0 * k2 * rr * xn;
    float drady = 2.0 * k1 * yn + 4.0 * k2 * rr * yn;
    float xd = xn * rad + 2.0 * pp1 * xn * yn + pp2 * (rr + 2.0 * xn * xn);
    float yd = yn * rad + pp1 * (rr + 2.0 * yn * yn) + 2.0 * pp2 * xn * yn;
    res0 = (u - (fx * xd + cx)) / pc.sigma;
    res1 = (v - (fy * yd + cy)) / pc.sigma;
    float dxd_dx = rad + xn * dradx + 2.0 * pp1 * yn + 6.0 * pp2 * xn;
    float dxd_dy = xn * drady + 2.0 * pp1 * xn + 2.0 * pp2 * yn;
    float dyd_dx = yn * dradx + 2.0 * pp1 * xn + 2.0 * pp2 * yn;
    float dyd_dy = rad + yn * drady + 6.0 * pp1 * yn + 2.0 * pp2 * xn;
    dUdxn = fx * dxd_dx; dUdyn = fx * dxd_dy;
    dVdxn = fy * dyd_dx; dVdyn = fy * dyd_dy;
  }
  float jr0, jr1, jr2, jr3, jr4, jr5, jr6, jr7, jr8;
  if (th2 < 1e-16) {
    jr0 = 1.0; jr1 = 0.0; jr2 = 0.0; jr3 = 0.0; jr4 = 1.0; jr5 = 0.0; jr6 = 0.0; jr7 = 0.0; jr8 = 1.0;
  } else {
    float th = sqrt(th2);
    float a = (1.0 - cos(th)) / th2;
    float b2 = (th - sin(th)) / (th2 * th);
    float S0 = 0.0, S1 = -q2, S2 = q1;
    float S3 = q2, S4 = 0.0, S5 = -q0;
    float S6 = -q1, S7 = q0, S8 = 0.0;
    float T0 = S0 * S0 + S1 * S3 + S2 * S6;
    float T1 = S0 * S1 + S1 * S4 + S2 * S7;
    float T2 = S0 * S2 + S1 * S5 + S2 * S8;
    float T3 = S3 * S0 + S4 * S3 + S5 * S6;
    float T4 = S3 * S1 + S4 * S4 + S5 * S7;
    float T5 = S3 * S2 + S4 * S5 + S5 * S8;
    float T6 = S6 * S0 + S7 * S3 + S8 * S6;
    float T7 = S6 * S1 + S7 * S4 + S8 * S7;
    float T8 = S6 * S2 + S7 * S5 + S8 * S8;
    jr0 = 1.0 - a * S0 + b2 * T0; jr1 = -a * S1 + b2 * T1; jr2 = -a * S2 + b2 * T2;
    jr3 = -a * S3 + b2 * T3; jr4 = 1.0 - a * S4 + b2 * T4; jr5 = -a * S5 + b2 * T5;
    jr6 = -a * S6 + b2 * T6; jr7 = -a * S7 + b2 * T7; jr8 = 1.0 - a * S8 + b2 * T8;
  }
  float dUdx = dUdxn / zc, dUdy = dUdyn / zc;
  float dUdz = -(dUdxn * xn + dUdyn * yn) / zc;
  float dVdx = dVdxn / zc, dVdy = dVdyn / zc;
  float dVdz = -(dVdxn * xn + dVdyn * yn) / zc;
  dUdx /= pc.sigma; dUdy /= pc.sigma; dUdz /= pc.sigma;
  dVdx /= pc.sigma; dVdy /= pc.sigma; dVdz /= pc.sigma;
  float X0 = xc, X1 = yc, X2 = zc;
  float sk0 = 0.0, sk1 = -X2, sk2 = X1;
  float sk3 = X2, sk4 = 0.0, sk5 = -X0;
  float sk6 = -X1, sk7 = X0, sk8 = 0.0;
  int o = i * 74;
  outv[o] = res0; outv[o + 1] = res1;
  for (int k = 0; k < 6; k++) {
    float bk = basis[i * 6 + k];
    for (int d = 0; d < 3; d++) {
      float ex = (d == 0) ? 1.0 : 0.0;
      float ey = (d == 1) ? 1.0 : 0.0;
      float ez = (d == 2) ? 1.0 : 0.0;
      float dX0 = -(R0 * ex + R3 * ey + R6 * ez) * bk;
      float dX1 = -(R1 * ex + R4 * ey + R7 * ez) * bk;
      float dX2 = -(R2 * ex + R5 * ey + R8 * ez) * bk;
      int col = k * 6 + d;
      outv[o + 2 + col] = -(dUdx * dX0 + dUdy * dX1 + dUdz * dX2);
      outv[o + 2 + 36 + col] = -(dVdx * dX0 + dVdy * dX1 + dVdz * dX2);
    }
    for (int d = 0; d < 3; d++) {
      float ex = (d == 0) ? 1.0 : 0.0;
      float ey = (d == 1) ? 1.0 : 0.0;
      float ez = (d == 2) ? 1.0 : 0.0;
      float jx = jr0 * ex + jr1 * ey + jr2 * ez;
      float jy = jr3 * ex + jr4 * ey + jr5 * ez;
      float jz = jr6 * ex + jr7 * ey + jr8 * ez;
      float dX0 = (sk0 * jx + sk1 * jy + sk2 * jz) * bk;
      float dX1 = (sk3 * jx + sk4 * jy + sk5 * jz) * bk;
      float dX2 = (sk6 * jx + sk7 * jy + sk8 * jz) * bk;
      int col = k * 6 + 3 + d;
      outv[o + 2 + col] = -(dUdx * dX0 + dUdy * dX1 + dUdz * dX2);
      outv[o + 2 + 36 + col] = -(dVdx * dX0 + dVdy * dX1 + dVdz * dX2);
    }
  }
}
)GLSL";

EGLDisplay display = EGL_NO_DISPLAY;
EGLContext context = EGL_NO_CONTEXT;
EGLSurface surface = EGL_NO_SURFACE;
GLuint program = 0;
GLuint ssbo[6] = {0,0,0,0,0,0};
GLuint ubo = 0;
std::mutex mtx;
std::string status = "not initialised";

struct Params { int N; float sigma; float pad0, pad1; float cam0[4]; float cam1[4]; };


// ---------------------------------------------------------------- OpenCL backend
typedef void* cl_platform_id; typedef void* cl_device_id; typedef void* cl_context;
typedef void* cl_command_queue; typedef void* cl_program; typedef void* cl_kernel; typedef void* cl_mem;
typedef int cl_int; typedef unsigned int cl_uint; typedef unsigned long long cl_ulong;
typedef size_t cl_size_t; typedef long cl_context_properties;
typedef void (*cl_cb)(const char*, const void*, cl_size_t, void*);

#define CL_DEVICE_TYPE_GPU 4
#define CL_SUCCESS 0
#define CL_MEM_READ_ONLY (1<<2)
#define CL_MEM_WRITE_ONLY (1<<1)
#define CL_MEM_COPY_HOST_PTR (1<<5)
#define CL_TRUE 1

cl_int (*p_clGetPlatformIDs)(cl_uint, cl_platform_id*, cl_uint*) = nullptr;
cl_int (*p_clGetDeviceIDs)(cl_platform_id, cl_ulong, cl_uint, cl_device_id*, cl_uint*) = nullptr;
cl_int (*p_clGetDeviceInfo)(cl_device_id, cl_uint, cl_size_t, void*, cl_size_t*) = nullptr;
cl_context (*p_clCreateContext)(const cl_context_properties*, cl_uint, const cl_device_id*, void*, void*, cl_int*) = nullptr;
cl_command_queue (*p_clCreateCommandQueue)(cl_context, cl_device_id, cl_ulong, cl_int*) = nullptr;
cl_program (*p_clCreateProgramWithSource)(cl_context, cl_uint, const char**, const cl_size_t*, cl_int*) = nullptr;
cl_int (*p_clBuildProgram)(cl_program, cl_uint, const cl_device_id*, const char*, cl_cb, void*) = nullptr;
cl_int (*p_clGetProgramBuildInfo)(cl_program, cl_device_id, cl_uint, cl_size_t, void*, cl_size_t*) = nullptr;
cl_kernel (*p_clCreateKernel)(cl_program, const char*, cl_int*) = nullptr;
cl_mem (*p_clCreateBuffer)(cl_context, cl_ulong, cl_size_t, void*, cl_int*) = nullptr;
cl_int (*p_clEnqueueWriteBuffer)(cl_command_queue, cl_mem, cl_uint, cl_size_t, cl_size_t, const void*, cl_uint, const void*, void*) = nullptr;
cl_int (*p_clEnqueueReadBuffer)(cl_command_queue, cl_mem, cl_uint, cl_size_t, cl_size_t, void*, cl_uint, const void*, void*) = nullptr;
cl_int (*p_clSetKernelArg)(cl_kernel, cl_uint, cl_size_t, const void*) = nullptr;
cl_int (*p_clEnqueueNDRangeKernel)(cl_command_queue, cl_kernel, cl_uint, const cl_size_t*, const cl_size_t*, const cl_size_t*, cl_uint, const void*, void*) = nullptr;
cl_int (*p_clFinish)(cl_command_queue) = nullptr;

bool openclReady = false;
cl_context oclCtx = nullptr;
cl_command_queue oclQueue = nullptr;
cl_program oclProgram = nullptr;
cl_kernel oclKernel = nullptr;
cl_device_id oclDevice = nullptr;
cl_mem oclCoeff = nullptr, oclBase = nullptr, oclBasis = nullptr, oclPts = nullptr, oclUv = nullptr, oclOut = nullptr;
int oclCapCoeff = 0, oclCapBase = 0, oclCapBasis = 0, oclCapPts = 0, oclCapUv = 0, oclCapOut = 0;

struct OclCam { float fx, fy, cx, cy, k1, k2, p1, p2, sigma; };

static bool loadOclApi() {
  void* h = dlopen("libOpenCL.so", RTLD_NOW);
  if (!h) { status = std::string("opencl dlopen: ") + dlerror(); return false; }
  bool ok = true;
  auto get = [&](const char* n) -> void* { void* p = dlsym(h, n); if (!p) ok = false; return p; };
  *(void**)(&p_clGetPlatformIDs) = get("clGetPlatformIDs");
  *(void**)(&p_clGetDeviceIDs) = get("clGetDeviceIDs");
  *(void**)(&p_clGetDeviceInfo) = get("clGetDeviceInfo");
  *(void**)(&p_clCreateContext) = get("clCreateContext");
  *(void**)(&p_clCreateCommandQueue) = get("clCreateCommandQueue");
  *(void**)(&p_clCreateProgramWithSource) = get("clCreateProgramWithSource");
  *(void**)(&p_clBuildProgram) = get("clBuildProgram");
  *(void**)(&p_clGetProgramBuildInfo) = get("clGetProgramBuildInfo");
  *(void**)(&p_clCreateKernel) = get("clCreateKernel");
  *(void**)(&p_clCreateBuffer) = get("clCreateBuffer");
  *(void**)(&p_clEnqueueWriteBuffer) = get("clEnqueueWriteBuffer");
  *(void**)(&p_clEnqueueReadBuffer) = get("clEnqueueReadBuffer");
  *(void**)(&p_clSetKernelArg) = get("clSetKernelArg");
  *(void**)(&p_clEnqueueNDRangeKernel) = get("clEnqueueNDRangeKernel");
  *(void**)(&p_clFinish) = get("clFinish");
  if (!ok) { status = "opencl symbol missing"; return false; }
  return true;
}

static bool initOpenCL() {
  if (openclReady) return true;
  if (!loadOclApi()) return false;
  cl_uint np = 0;
  if (p_clGetPlatformIDs(0, nullptr, &np) != CL_SUCCESS || np == 0) { status = "opencl: no platform"; return false; }
  std::vector<cl_platform_id> plats(np);
  p_clGetPlatformIDs(np, plats.data(), nullptr);
  for (cl_uint i = 0; i < np && !oclDevice; i++) {
    cl_uint nd = 0;
    if (p_clGetDeviceIDs(plats[i], CL_DEVICE_TYPE_GPU, 0, nullptr, &nd) == CL_SUCCESS && nd > 0) {
      std::vector<cl_device_id> ds(nd);
      p_clGetDeviceIDs(plats[i], CL_DEVICE_TYPE_GPU, nd, ds.data(), nullptr);
      oclDevice = ds[0];
    }
  }
  if (!oclDevice) { status = "opencl: no GPU device"; return false; }
  cl_int err = 0;
  oclCtx = p_clCreateContext(nullptr, 1, &oclDevice, nullptr, nullptr, &err);
  if (!oclCtx) { status = "opencl: context"; return false; }
  oclQueue = p_clCreateCommandQueue(oclCtx, oclDevice, 0, &err);
  if (!oclQueue) { status = "opencl: queue"; return false; }
  const char* src = R"OCL(typedef struct { float fx, fy, cx, cy, k1, k2, p1, p2, sigma; } Cam;

__kernel void pixeljac(__global const float* coeff, __global const int* base,
                       __global const float* basis, __global const float* points,
                       __global const float* uv, __global float* out,
                       const int N, const Cam cam) {
  int i = get_global_id(0);
  if (i >= N) return;
  int b = base[i];
  __global const float* bb = basis + i * 6;
  __global const float* X = points + i * 3;
  float u = uv[i * 2];
  float v = uv[i * 2 + 1];

  float c[36];
  for (int q = 0; q < 36; q++) c[q] = coeff[b * 6 + q];

  float p0 = 0.f, p1 = 0.f, p2 = 0.f, q0 = 0.f, q1 = 0.f, q2 = 0.f;
  for (int k = 0; k < 6; k++) {
    float bk = bb[k];
    p0 += bk * c[k * 6];     p1 += bk * c[k * 6 + 1]; p2 += bk * c[k * 6 + 2];
    q0 += bk * c[k * 6 + 3]; q1 += bk * c[k * 6 + 4]; q2 += bk * c[k * 6 + 5];
  }
  float th2 = q0 * q0 + q1 * q1 + q2 * q2;
  float R0, R1, R2, R3, R4, R5, R6, R7, R8;
  if (th2 < 1e-16f) { R0 = 1.f; R1 = 0.f; R2 = 0.f; R3 = 0.f; R4 = 1.f; R5 = 0.f; R6 = 0.f; R7 = 0.f; R8 = 1.f; }
  else {
    float th = sqrt(th2); float kx = q0 / th, ky = q1 / th, kz = q2 / th;
    float ca = cos(th), sa = sin(th), va = 1.f - ca;
    R0 = ca + kx * kx * va; R1 = kx * ky * va - kz * sa; R2 = kx * kz * va + ky * sa;
    R3 = ky * kx * va + kz * sa; R4 = ca + ky * ky * va; R5 = ky * kz * va - kx * sa;
    R6 = kz * kx * va - ky * sa; R7 = kz * ky * va + kx * sa; R8 = ca + kz * kz * va;
  }
  float dx = X[0] - p0, dy = X[1] - p1, dz = X[2] - p2;
  float xc = R0 * dx + R3 * dy + R6 * dz;
  float yc = R1 * dx + R4 * dy + R7 * dz;
  float zc = R2 * dx + R5 * dy + R8 * dz;

  float res0, res1;
  float dUdxn = 0.f, dUdyn = 0.f, dVdxn = 0.f, dVdyn = 0.f;
  float xn = 0.f, yn = 0.f;
  if (zc > 1e-6f) {
    xn = xc / zc; yn = yc / zc;
    float rr = xn * xn + yn * yn;
    float rad = 1.f + cam.k1 * rr + cam.k2 * rr * rr;
    float dradx = 2.f * cam.k1 * xn + 4.f * cam.k2 * rr * xn;
    float drady = 2.f * cam.k1 * yn + 4.f * cam.k2 * rr * yn;
    float xd = xn * rad + 2.f * cam.p1 * xn * yn + cam.p2 * (rr + 2.f * xn * xn);
    float yd = yn * rad + cam.p1 * (rr + 2.f * yn * yn) + 2.f * cam.p2 * xn * yn;
    res0 = (u - (cam.fx * xd + cam.cx)) / cam.sigma;
    res1 = (v - (cam.fy * yd + cam.cy)) / cam.sigma;
    float dxd_dx = rad + xn * dradx + 2.f * cam.p1 * yn + 6.f * cam.p2 * xn;
    float dxd_dy = xn * drady + 2.f * cam.p1 * xn + 2.f * cam.p2 * yn;
    float dyd_dx = yn * dradx + 2.f * cam.p1 * xn + 2.f * cam.p2 * yn;
    float dyd_dy = rad + yn * drady + 6.f * cam.p1 * yn + 2.f * cam.p2 * xn;
    dUdxn = cam.fx * dxd_dx; dUdyn = cam.fx * dxd_dy;
    dVdxn = cam.fy * dyd_dx; dVdyn = cam.fy * dyd_dy;
  } else {
    res0 = 1000.f; res1 = 1000.f;
  }

  // So3 right Jacobian Jr.
  float jr0, jr1, jr2, jr3, jr4, jr5, jr6, jr7, jr8;
  if (th2 < 1e-16f) {
    jr0 = 1.f; jr1 = 0.f; jr2 = 0.f; jr3 = 0.f; jr4 = 1.f; jr5 = 0.f; jr6 = 0.f; jr7 = 0.f; jr8 = 1.f;
  } else {
    float th = sqrt(th2);
    float a = (1.f - cos(th)) / th2;
    float bb2 = (th - sin(th)) / (th2 * th);
    // skew(theta)
    float S0 = 0.f, S1 = -q2, S2 = q1;
    float S3 = q2, S4 = 0.f, S5 = -q0;
    float S6 = -q1, S7 = q0, S8 = 0.f;
    float S20 = S0 * S0 + S1 * S3 + S2 * S6;
    float S21 = S0 * S1 + S1 * S4 + S2 * S7;
    float S22 = S0 * S2 + S1 * S5 + S2 * S8;
    float S23 = S3 * S0 + S4 * S3 + S5 * S6;
    float S24 = S3 * S1 + S4 * S4 + S5 * S7;
    float S25 = S3 * S2 + S4 * S5 + S5 * S8;
    float S26 = S6 * S0 + S7 * S3 + S8 * S6;
    float S27 = S6 * S1 + S7 * S4 + S8 * S7;
    float S28 = S6 * S2 + S7 * S5 + S8 * S8;
    jr0 = 1.f - a * S0 + bb2 * S20; jr1 = -a * S1 + bb2 * S21; jr2 = -a * S2 + bb2 * S22;
    jr3 = -a * S3 + bb2 * S23; jr4 = 1.f - a * S4 + bb2 * S24; jr5 = -a * S5 + bb2 * S25;
    jr6 = -a * S6 + bb2 * S26; jr7 = -a * S7 + bb2 * S27; jr8 = 1.f - a * S8 + bb2 * S28;
  }

  // dUV/dXcam columns (2x3), scaled by 1/sigma.
  float dUdx = dUdxn / zc;
  float dUdy = dUdyn / zc;
  float dUdz = -(dUdxn * xn + dUdyn * yn) / zc;
  float dVdx = dVdxn / zc;
  float dVdy = dVdyn / zc;
  float dVdz = -(dVdxn * xn + dVdyn * yn) / zc;
  dUdx /= cam.sigma; dUdy /= cam.sigma; dUdz /= cam.sigma;
  dVdx /= cam.sigma; dVdy /= cam.sigma; dVdz /= cam.sigma;

  // skew(Xcam) rows
  float X0 = xc, X1 = yc, X2 = zc;
  float sk0 = 0.f, sk1 = -X2, sk2 = X1;
  float sk3 = X2, sk4 = 0.f, sk5 = -X0;
  float sk6 = -X1, sk7 = X0, sk8 = 0.f;

  __global float* o = out + i * 74;
  o[0] = res0; o[1] = res1;
  for (int k = 0; k < 6; k++) {
    float bk = bb[k];
    // translation coefficients 0..2: dX/dc = -R^T e_d * bk
    for (int d = 0; d < 3; d++) {
      float ex = (d == 0) ? 1.f : 0.f, ey = (d == 1) ? 1.f : 0.f, ez = (d == 2) ? 1.f : 0.f;
      float dX0 = -(R0 * ex + R3 * ey + R6 * ez) * bk;
      float dX1 = -(R1 * ex + R4 * ey + R7 * ez) * bk;
      float dX2 = -(R2 * ex + R5 * ey + R8 * ez) * bk;
      int col = k * 6 + d;
      o[2 + col] = -(dUdx * dX0 + dUdy * dX1 + dUdz * dX2);
      o[2 + 36 + col] = -(dVdx * dX0 + dVdy * dX1 + dVdz * dX2);
    }
    // rotation coefficients 3..5: dX/dc = skew(Xcam) * Jr * e_d * bk
    for (int d = 0; d < 3; d++) {
      float ex = (d == 0) ? 1.f : 0.f, ey = (d == 1) ? 1.f : 0.f, ez = (d == 2) ? 1.f : 0.f;
      float jx = jr0 * ex + jr1 * ey + jr2 * ez;
      float jy = jr3 * ex + jr4 * ey + jr5 * ez;
      float jz = jr6 * ex + jr7 * ey + jr8 * ez;
      float dX0 = (sk0 * jx + sk1 * jy + sk2 * jz) * bk;
      float dX1 = (sk3 * jx + sk4 * jy + sk5 * jz) * bk;
      float dX2 = (sk6 * jx + sk7 * jy + sk8 * jz) * bk;
      int col = k * 6 + 3 + d;
      o[2 + col] = -(dUdx * dX0 + dUdy * dX1 + dUdz * dX2);
      o[2 + 36 + col] = -(dVdx * dX0 + dVdy * dX1 + dVdz * dX2);
    }
  }
}
)OCL";
  cl_size_t len = strlen(src);
  oclProgram = p_clCreateProgramWithSource(oclCtx, 1, &src, &len, &err);
  if (!oclProgram) { status = "opencl: program"; return false; }
  err = p_clBuildProgram(oclProgram, 1, &oclDevice, "-cl-fast-relaxed-math -cl-mad-enable", nullptr, nullptr);
  if (err != CL_SUCCESS) {
    cl_size_t n = 0; p_clGetProgramBuildInfo(oclProgram, oclDevice, 0x1183, 0, nullptr, &n);
    std::string log(n, '\0');
    if (n) p_clGetProgramBuildInfo(oclProgram, oclDevice, 0x1183, n, &log[0], &n);
    status = "opencl build: " + log.substr(0, 400);
    return false;
  }
  oclKernel = p_clCreateKernel(oclProgram, "pixeljac", &err);
  if (!oclKernel) { status = "opencl: kernel"; return false; }
  openclReady = true;
  return true;
}

static void oclEnsure(cl_mem& buf, int& cap, int bytes, cl_ulong flags, void* host) {
  if (cap == bytes && buf) return;
  buf = p_clCreateBuffer(oclCtx, flags, (cl_size_t)bytes, host, nullptr);
  cap = bytes;
}

static bool runOpenCL(const std::vector<float>& coeff, const std::vector<int>& base,
                      const std::vector<float>& basis, const std::vector<float>& xyz,
                      const std::vector<float>& uv, int nObs, const std::vector<double>& cam,
                      double sigma, std::vector<float>& out) {
  if (!openclReady) return false;
  const int nc = (int)coeff.size(), nb = (int)base.size(), nba = (int)basis.size(), nx = (int)xyz.size(), nu = (int)uv.size();
  oclEnsure(oclCoeff, oclCapCoeff, nc * 4, CL_MEM_READ_ONLY, (void*)coeff.data());
  oclEnsure(oclBase, oclCapBase, nb * 4, CL_MEM_READ_ONLY, (void*)base.data());
  oclEnsure(oclBasis, oclCapBasis, nba * 4, CL_MEM_READ_ONLY, (void*)basis.data());
  oclEnsure(oclPts, oclCapPts, nx * 4, CL_MEM_READ_ONLY, (void*)xyz.data());
  oclEnsure(oclUv, oclCapUv, nu * 4, CL_MEM_READ_ONLY, (void*)uv.data());
  oclEnsure(oclOut, oclCapOut, (int)out.size() * 4, CL_MEM_WRITE_ONLY, nullptr);
  if (!oclCoeff || !oclBase || !oclBasis || !oclPts || !oclUv || !oclOut) return false;
  OclCam c{(float)cam[0], (float)cam[1], (float)cam[2], (float)cam[3], (float)cam[4], (float)cam[5], (float)cam[6], (float)cam[7], (float)sigma};
  int N = nObs;
  p_clSetKernelArg(oclKernel, 0, sizeof(cl_mem), &oclCoeff);
  p_clSetKernelArg(oclKernel, 1, sizeof(cl_mem), &oclBase);
  p_clSetKernelArg(oclKernel, 2, sizeof(cl_mem), &oclBasis);
  p_clSetKernelArg(oclKernel, 3, sizeof(cl_mem), &oclPts);
  p_clSetKernelArg(oclKernel, 4, sizeof(cl_mem), &oclUv);
  p_clSetKernelArg(oclKernel, 5, sizeof(cl_mem), &oclOut);
  p_clSetKernelArg(oclKernel, 6, sizeof(int), &N);
  p_clSetKernelArg(oclKernel, 7, sizeof(OclCam), &c);
  cl_size_t gws = ((cl_size_t)nObs + 63) / 64 * 64, lws = 64;
  if (p_clEnqueueNDRangeKernel(oclQueue, oclKernel, 1, nullptr, &gws, &lws, 0, nullptr, nullptr) != CL_SUCCESS) return false;
  if (p_clFinish(oclQueue) != CL_SUCCESS) return false;
  if (p_clEnqueueReadBuffer(oclQueue, oclOut, CL_TRUE, 0, (cl_size_t)out.size() * 4, out.data(), 0, nullptr, nullptr) != CL_SUCCESS) return false;
  return true;
}

bool ensureCurrent() {
  if (display == EGL_NO_DISPLAY) return false;
  if (eglGetCurrentContext() == context) return true;
  return eglMakeCurrent(display, surface, surface, context) == EGL_TRUE;
}

GLuint compile(GLenum type, const char* src) {
  GLuint s = glCreateShader(type);
  glShaderSource(s, 1, &src, nullptr);
  glCompileShader(s);
  GLint ok = 0; glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
  if (!ok) {
    char log[4096]; GLsizei n = 0; glGetShaderInfoLog(s, sizeof(log)-1, &n, log); log[n] = 0;
    status = std::string("shader compile: ") + log;
    glDeleteShader(s);
    return 0;
  }
  return s;
}

bool initGL() {
  if (program) return true;
  display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
  if (display == EGL_NO_DISPLAY) { status = "no EGL display"; return false; }
  EGLint major, minor;
  if (!eglInitialize(display, &major, &minor)) { status = "eglInitialize failed"; return false; }
  const EGLint cfgAttrs[] = {
    EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
    EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
    EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
    EGL_NONE
  };
  EGLConfig cfg; EGLint n = 0;
  if (!eglChooseConfig(display, cfgAttrs, &cfg, 1, &n) || n < 1) { status = "eglChooseConfig"; return false; }
  const EGLint ctxAttrs[] = { EGL_CONTEXT_MAJOR_VERSION, 3, EGL_CONTEXT_MINOR_VERSION, 1, EGL_NONE };
  context = eglCreateContext(display, cfg, EGL_NO_CONTEXT, ctxAttrs);
  if (context == EGL_NO_CONTEXT) { status = "eglCreateContext (ES3.1)"; return false; }
  const EGLint pbAttrs[] = { EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE };
  surface = eglCreatePbufferSurface(display, cfg, pbAttrs);
  if (surface == EGL_NO_SURFACE) { status = "eglCreatePbufferSurface"; return false; }
  if (!eglMakeCurrent(display, surface, surface, context)) { status = "eglMakeCurrent"; return false; }
  GLuint vs = compile(GL_COMPUTE_SHADER, kShader);
  if (!vs) return false;
  program = glCreateProgram();
  glAttachShader(program, vs);
  glLinkProgram(program);
  GLint ok = 0; glGetProgramiv(program, GL_LINK_STATUS, &ok);
  if (!ok) { char log[4096]; GLsizei ln = 0; glGetProgramInfoLog(program, sizeof(log)-1, &ln, log); log[ln] = 0; status = std::string("link: ") + log; return false; }
  glDeleteShader(vs);
  glGenBuffers(6, ssbo);
  glGenBuffers(1, &ubo);
  glBindBuffer(GL_UNIFORM_BUFFER, ubo);
  glBufferData(GL_UNIFORM_BUFFER, sizeof(Params), nullptr, GL_DYNAMIC_DRAW);
  GLuint block = glGetUniformBlockIndex(program, "Params");
  if (block != GL_INVALID_INDEX) glUniformBlockBinding(program, block, 6);
  glBindBufferBase(GL_UNIFORM_BUFFER, 6, ubo);
  status = "ok: GLES compute";
  return true;
}
}  // namespace



static std::string probeOpenCLPlain() {
  void* h = dlopen("libOpenCL.so", RTLD_NOW);
  if (!h) return std::string("opencl dlopen: ") + dlerror();
  auto f = (int (*)(unsigned int, void*, unsigned int*))dlsym(h, "clGetPlatformIDs");
  if (!f) return "opencl: no clGetPlatformIDs";
  unsigned int n = 0; int e = f(0, nullptr, &n);
  char b[128]; snprintf(b, sizeof(b), "opencl err=%d plats=%u", e, n); return b;
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_rawmechatronics_imucalib_GpuPixelBatch_nativeInit(JNIEnv* env, jobject, jstring) {
  std::lock_guard<std::mutex> lk(mtx);
  initGL();
  // Release the probe thread's current context; the solve thread makes it current itself.
  if (display != EGL_NO_DISPLAY && context != EGL_NO_CONTEXT) {
    eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
  }
  if (initOpenCL()) status = "opencl: " + std::string("ready"); else status += "; " + probeOpenCLPlain();
  return env->NewStringUTF(status.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_rawmechatronics_imucalib_GpuPixelBatch_nativeStatus(JNIEnv* env, jobject) {
  return env->NewStringUTF(status.c_str());
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_rawmechatronics_imucalib_GpuPixelBatch_nativeEvaluate(
    JNIEnv* env, jobject, jdoubleArray jcoeff, jintArray jbase, jdoubleArray jbasis,
    jdoubleArray jxyz, jdoubleArray juv, jint nObs, jint localCount,
    jdoubleArray jcamera, jdouble sigma) {
  std::lock_guard<std::mutex> lk(mtx);
  if (!initGL()) return nullptr;
  if (!ensureCurrent()) { status = "eglMakeCurrent in evaluate"; return nullptr; }
  const int nc = (int)env->GetArrayLength(jcoeff);
  const int nb = (int)env->GetArrayLength(jbase);
  const int nba = (int)env->GetArrayLength(jbasis);
  const int nx = (int)env->GetArrayLength(jxyz);
  const int nu = (int)env->GetArrayLength(juv);
  std::vector<float> coeff(nc), basis(nba), xyz(nx), uv(nu);
  std::vector<int> base(nb);
  std::vector<double> cam(9);
  {
    std::vector<double> tmp(nc); env->GetDoubleArrayRegion(jcoeff, 0, nc, tmp.data());
    for (int i = 0; i < nc; i++) coeff[i] = (float)tmp[i];
  }
  {
    std::vector<double> tmp(nba); env->GetDoubleArrayRegion(jbasis, 0, nba, tmp.data());
    for (int i = 0; i < nba; i++) basis[i] = (float)tmp[i];
  }
  {
    std::vector<double> tmp(nx); env->GetDoubleArrayRegion(jxyz, 0, nx, tmp.data());
    for (int i = 0; i < nx; i++) xyz[i] = (float)tmp[i];
  }
  {
    std::vector<double> tmp(nu); env->GetDoubleArrayRegion(juv, 0, nu, tmp.data());
    for (int i = 0; i < nu; i++) uv[i] = (float)tmp[i];
  }
  env->GetIntArrayRegion(jbase, 0, nb, base.data());
  env->GetDoubleArrayRegion(jcamera, 0, 9, cam.data());
  const int width = 2 + 2 * 36;
  std::vector<float> out((size_t)nObs * width);
  if (openclReady && runOpenCL(coeff, base, basis, xyz, uv, nObs, cam, sigma, out)) {
      jdoubleArray r = env->NewDoubleArray((jsize)out.size());
      if (!r) return nullptr;
      std::vector<double> d(out.size());
      for (size_t i = 0; i < out.size(); i++) d[i] = out[i];
      env->SetDoubleArrayRegion(r, 0, (jsize)d.size(), d.data());
      return r;
  }
  auto upload = [&](int idx, const void* data, size_t bytes) {
    glBindBuffer(GL_SHADER_STORAGE_BUFFER, ssbo[idx]);
    glBufferData(GL_SHADER_STORAGE_BUFFER, bytes, data, GL_DYNAMIC_DRAW);
    glBindBufferBase(GL_SHADER_STORAGE_BUFFER, idx, ssbo[idx]);
  };
  upload(0, coeff.data(), coeff.size() * 4);
  upload(1, base.data(), base.size() * 4);
  upload(2, basis.data(), basis.size() * 4);
  upload(3, xyz.data(), xyz.size() * 4);
  upload(4, uv.data(), uv.size() * 4);
  upload(5, nullptr, out.size() * 4);
  Params pr{};
  pr.N = nObs; pr.sigma = (float)sigma;
  pr.cam0[0] = (float)cam[0]; pr.cam0[1] = (float)cam[1]; pr.cam0[2] = (float)cam[2]; pr.cam0[3] = (float)cam[3];
  pr.cam1[0] = (float)cam[4]; pr.cam1[1] = (float)cam[5]; pr.cam1[2] = (float)cam[6]; pr.cam1[3] = (float)cam[7];
  glBindBuffer(GL_UNIFORM_BUFFER, ubo);
  glBufferSubData(GL_UNIFORM_BUFFER, 0, sizeof(pr), &pr);
  glBindBufferBase(GL_UNIFORM_BUFFER, 6, ubo);
  glUseProgram(program);
  glDispatchCompute((GLuint)((nObs + 63) / 64), 1, 1);
  glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT | GL_BUFFER_UPDATE_BARRIER_BIT);
  glFinish();
  GLenum err = glGetError();
  if (err != GL_NO_ERROR) { status = "gl error " + std::to_string((int)err); return nullptr; }
  glBindBuffer(GL_SHADER_STORAGE_BUFFER, ssbo[5]);
  void* p = glMapBufferRange(GL_SHADER_STORAGE_BUFFER, 0, out.size() * 4, GL_MAP_READ_BIT);
  if (!p) { status = "map output failed"; return nullptr; }
  std::memcpy(out.data(), p, out.size() * 4);
  glUnmapBuffer(GL_SHADER_STORAGE_BUFFER);
  jdoubleArray result = env->NewDoubleArray((jsize)out.size());
  if (!result) return nullptr;
  std::vector<double> dout(out.size());
  for (size_t i = 0; i < out.size(); i++) dout[i] = out[i];
  env->SetDoubleArrayRegion(result, 0, (jsize)dout.size(), dout.data());
  return result;
}
