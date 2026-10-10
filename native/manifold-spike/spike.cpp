// Manifold boolean spike for Android arm64.
//
// Exercises exactly what the split-and-snap feature needs - SUBTRACT, UNION,
// SUBTRACT - on a real STL and on a generated ~100k-triangle sphere, and reports
// for every step: the manifold status, whether the output is edge-closed, the
// triangle count, the enclosed volume and the wall-clock milliseconds.
//
// Build: scripts/build-manifold-android.sh (arm64-v8a, android-29).
// Run on device:  manifold_spike large <out-prefix>
//                 manifold_spike small <in.stl> <out-prefix>
//
// STL is read and written here, by hand: Manifold's own file IO lives in extras/
// behind assimp (ASSIMP_ENABLE), which this build deliberately does not pull in.
#include <manifold/manifold.h>
#include <manifold/version.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <functional>
#include <iomanip>
#include <iostream>
#include <map>
#include <numeric>
#include <sstream>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

using namespace manifold;

namespace {

const char* ErrName(Manifold::Error e) {
  switch (e) {
    case Manifold::Error::NoError: return "NoError";
    case Manifold::Error::NonFiniteVertex: return "NonFiniteVertex";
    case Manifold::Error::NotManifold: return "NotManifold";
    case Manifold::Error::VertexOutOfBounds: return "VertexOutOfBounds";
    case Manifold::Error::PropertiesWrongLength: return "PropertiesWrongLength";
    case Manifold::Error::MissingPositionProperties: return "MissingPositionProperties";
    case Manifold::Error::MergeVectorsDifferentLengths: return "MergeVectorsDifferentLengths";
    case Manifold::Error::MergeIndexOutOfBounds: return "MergeIndexOutOfBounds";
    case Manifold::Error::TransformWrongLength: return "TransformWrongLength";
    case Manifold::Error::RunIndexWrongLength: return "RunIndexWrongLength";
    case Manifold::Error::FaceIDWrongLength: return "FaceIDWrongLength";
    case Manifold::Error::InvalidConstruction: return "InvalidConstruction";
    case Manifold::Error::ResultTooLarge: return "ResultTooLarge";
    case Manifold::Error::InvalidTangents: return "InvalidTangents";
    case Manifold::Error::Cancelled: return "Cancelled";
  }
  return "Unknown";
}

// ---------------------------------------------------------------- STL input

// Binary STL: 80 byte header, uint32 count, then 50 bytes per facet (normal,
// three vertices, uint16 attribute). Vertex positions are copied out as a
// triangle soup; Merge() welds them afterwards, exactly as an STL importer has to.
bool ReadBinaryStl(std::istream& in, std::vector<float>& soup, std::string& err) {
  char header[80];
  uint32_t count = 0;
  in.read(header, sizeof(header));
  in.read(reinterpret_cast<char*>(&count), 4);
  if (!in) {
    err = "short header";
    return false;
  }
  if (count == 0 || count > 200000000u) {
    err = "implausible facet count " + std::to_string(count);
    return false;
  }
  soup.resize(static_cast<size_t>(count) * 9);
  for (uint32_t i = 0; i < count; ++i) {
    float facet[12];
    uint16_t attr = 0;
    in.read(reinterpret_cast<char*>(facet), sizeof(facet));
    in.read(reinterpret_cast<char*>(&attr), 2);
    if (!in) {
      err = "truncated at facet " + std::to_string(i) + " of " + std::to_string(count);
      return false;
    }
    std::memcpy(&soup[static_cast<size_t>(i) * 9], &facet[3], 9 * sizeof(float));
  }
  return true;
}

// ASCII STL: the three "vertex x y z" lines after every facet. Only the vertex
// lines are read; normals are recomputed from the winding when writing.
bool ReadAsciiStl(std::istream& in, std::vector<float>& soup, std::string& err) {
  std::string line;
  std::vector<float> pending;
  while (std::getline(in, line)) {
    std::istringstream ls(line);
    std::string word;
    ls >> word;
    if (word != "vertex") continue;
    float x = 0, y = 0, z = 0;
    if (!(ls >> x >> y >> z)) {
      err = "malformed vertex line";
      return false;
    }
    pending.push_back(x);
    pending.push_back(y);
    pending.push_back(z);
  }
  if (pending.empty() || pending.size() % 9 != 0) {
    err = "vertex count " + std::to_string(pending.size() / 3) + " is not a multiple of 3";
    return false;
  }
  soup.swap(pending);
  return true;
}

bool ReadStl(const std::string& path, std::vector<float>& soup, std::string& err) {
  std::ifstream in(path, std::ios::binary);
  if (!in) {
    err = "cannot open " + path;
    return false;
  }
  char head[6] = {0};
  in.read(head, 6);
  in.clear();
  in.seekg(0);
  if (std::strncmp(head, "solid", 5) == 0) {
    // "solid" alone is not decisive: plenty of binary STLs also start with it.
    std::vector<float> binary_soup;
    std::string binary_err;
    std::ifstream bin(path, std::ios::binary);
    if (ReadBinaryStl(bin, binary_soup, binary_err) && binary_soup.size() >= 9) {
      soup.swap(binary_soup);
      return true;
    }
    std::ifstream ascii(path);
    return ReadAsciiStl(ascii, soup, err);
  }
  return ReadBinaryStl(in, soup, err);
}

MeshGL SoupToMesh(const std::vector<float>& soup) {
  MeshGL mesh;
  mesh.numProp = 3;
  mesh.vertProperties = soup;
  const size_t tris = soup.size() / 9;
  mesh.triVerts.resize(tris * 3);
  std::iota(mesh.triVerts.begin(), mesh.triVerts.end(), 0u);
  mesh.Merge();
  return mesh;
}

// GetMeshGL() is only meaningful for a valid Manifold; an empty mesh keeps the
// checker and the writer on the error path instead of throwing.
MeshGL SafeMesh(const Manifold& m) {
  if (m.Status() != Manifold::Error::NoError) return MeshGL();
  return m.GetMeshGL();
}

// ------------------------------------------------------------------ STL out

bool WriteStl(const std::string& path, const MeshGL& in, std::string& err) {
  MeshGL mesh = in;
  if (!mesh.mergeFromVert.empty()) mesh.Merge();
  const size_t tris = mesh.NumTri();
  std::ofstream out(path, std::ios::binary);
  if (!out) {
    err = "cannot write " + path;
    return false;
  }
  char header[80] = {0};
  std::snprintf(header, sizeof(header), "TrioSlicer manifold spike (Manifold %d.%d.%d, Apache-2.0)",
                MANIFOLD_VERSION_MAJOR, MANIFOLD_VERSION_MINOR, MANIFOLD_VERSION_PATCH);
  out.write(header, sizeof(header));
  const uint32_t count = static_cast<uint32_t>(tris);
  out.write(reinterpret_cast<const char*>(&count), 4);
  for (size_t t = 0; t < tris; ++t) {
    float v[9];
    for (int k = 0; k < 3; ++k) {
      const uint32_t vi = mesh.triVerts[t * 3 + k];
      for (int c = 0; c < 3; ++c) v[k * 3 + c] = mesh.vertProperties[vi * mesh.numProp + c];
    }
    float n[3];
    const float e1[3] = {v[3] - v[0], v[4] - v[1], v[5] - v[2]};
    const float e2[3] = {v[6] - v[0], v[7] - v[1], v[8] - v[2]};
    n[0] = e1[1] * e2[2] - e1[2] * e2[1];
    n[1] = e1[2] * e2[0] - e1[0] * e2[2];
    n[2] = e1[0] * e2[1] - e1[1] * e2[0];
    const float len = std::sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
    for (int c = 0; c < 3; ++c) n[c] = len > 0 ? n[c] / len : 0.0f;
    out.write(reinterpret_cast<const char*>(n), sizeof(n));
    out.write(reinterpret_cast<const char*>(v), sizeof(v));
    uint16_t attr = 0;
    out.write(reinterpret_cast<const char*>(&attr), 2);
  }
  if (!out) {
    err = "write failed";
    return false;
  }
  return true;
}

// ------------------------------------------------------------------ checks

// A watertight mesh has every undirected edge shared by exactly two triangles.
// That is the property a slicer needs, checked here independently of Manifold's
// own status so "closed" is not just Manifold agreeing with itself.
struct Closure {
  size_t bad_edges = 0;
  size_t degenerate = 0;
  bool closed = false;
};

Closure CheckClosed(const MeshGL& in) {
  MeshGL mesh = in;
  // Merge() only rewrites the merge vectors - triVerts keep indexing the unmerged
  // vertices - so they have to be applied before the edges mean anything. This is
  // exactly what an STL importer relies on: the file is a triangle soup.
  mesh.Merge();
  std::vector<uint32_t> parent(mesh.NumVert());
  std::iota(parent.begin(), parent.end(), 0u);
  std::function<uint32_t(uint32_t)> find = [&](uint32_t v) {
    while (parent[v] != v) {
      parent[v] = parent[parent[v]];
      v = parent[v];
    }
    return v;
  };
  for (size_t i = 0; i < mesh.mergeFromVert.size(); ++i) {
    const uint32_t a = find(mesh.mergeFromVert[i]), b = find(mesh.mergeToVert[i]);
    if (a != b) parent[a] = b;
  }
  std::map<std::pair<uint32_t, uint32_t>, int> edges;
  Closure c;
  const size_t tris = mesh.NumTri();
  for (size_t t = 0; t < tris; ++t) {
    const uint32_t v[3] = {find(mesh.triVerts[t * 3]), find(mesh.triVerts[t * 3 + 1]),
                           find(mesh.triVerts[t * 3 + 2])};
    for (int k = 0; k < 3; ++k) {
      const uint32_t a = v[k];
      const uint32_t b = v[(k + 1) % 3];
      if (a == b) {
        ++c.degenerate;
        continue;
      }
      ++edges[{std::min(a, b), std::max(a, b)}];
    }
  }
  for (const auto& e : edges) {
    if (e.second != 2) ++c.bad_edges;
  }
  c.closed = (c.bad_edges == 0 && c.degenerate == 0 && tris > 0);
  return c;
}

long StatusKb(const char* key) {
  std::ifstream in("/proc/self/status");
  std::string line;
  const size_t key_len = std::strlen(key);
  while (std::getline(in, line)) {
    if (line.compare(0, key_len, key) == 0) {
      std::istringstream ls(line.substr(key_len));
      long kb = 0;
      ls >> kb;
      return kb;
    }
  }
  return -1;
}

// ------------------------------------------------------------------ report

double NowMs() {
  using clock = std::chrono::steady_clock;
  return std::chrono::duration<double, std::milli>(clock::now().time_since_epoch()).count();
}

struct Step {
  std::string name;
  std::string status;
  bool closed = false;
  bool valid = false;
  size_t tris = 0;
  size_t verts = 0;
  double volume = 0;
  double ms = 0;
};

// Manifold defers booleans: `a - b` only builds a CSG tree, and the actual
// intersection work happens when the result is first evaluated. Status() forces
// that evaluation, so every operation is timed as "build + evaluate" - which is
// what the app will pay - rather than as the tree construction alone.
template <class F>
double TimeOp(F&& op, Manifold& out) {
  const double t0 = NowMs();
  out = op();
  out.Status();
  return NowMs() - t0;
}

Step Measure(const std::string& name, const std::string& op, const Manifold& m, double ms) {
  Step s;
  s.name = name;
  s.ms = ms;
  const Manifold::Error err = m.Status();
  s.status = ErrName(err);
  s.valid = (err == Manifold::Error::NoError);
  const MeshGL gl = SafeMesh(m);
  if (s.valid) {
    s.tris = m.NumTri();
    s.verts = m.NumVert();
    s.volume = m.Volume();
  } else {
    s.tris = gl.NumTri();
    s.verts = gl.NumVert();
  }
  const Closure c = CheckClosed(gl);
  s.closed = c.closed;
  std::cout << std::fixed << std::setprecision(3) << "STEP " << s.name << " op=" << op
            << " status=" << s.status << " closed=" << (s.closed ? "yes" : "no")
            << " bad_edges=" << c.bad_edges << " degenerate=" << c.degenerate
            << " tris=" << s.tris << " verts=" << s.verts << " volume=" << s.volume
            << " ms=" << s.ms << " rss_kb=" << StatusKb("VmRSS:") << "\n";
  return s;
}

// ------------------------------------------------------------- generation

// UV sphere as a triangle soup: 2 * slices * (stacks - 1) triangles, so the
// triangle count is exact and the input is big enough for the timing to mean
// something. Every triangle carries its own vertices, like an STL does.
std::vector<float> MakeSphereSoup(int slices, int stacks, double radius) {
  std::vector<float> ring(static_cast<size_t>(stacks + 1) * slices * 3);
  for (int i = 0; i <= stacks; ++i) {
    const double phi = M_PI * i / stacks;
    for (int j = 0; j < slices; ++j) {
      const double theta = 2 * M_PI * j / slices;
      float* p = &ring[(static_cast<size_t>(i) * slices + j) * 3];
      p[0] = static_cast<float>(radius * std::sin(phi) * std::cos(theta));
      p[1] = static_cast<float>(radius * std::sin(phi) * std::sin(theta));
      p[2] = static_cast<float>(radius * std::cos(phi));
    }
  }
  // sin(0) * cos(theta) is +0.0 or -0.0 depending on the quadrant, which would
  // leave the pole as up to four bit-different vertices; write it once instead.
  for (int j = 0; j < slices; ++j) {
    float* north = &ring[static_cast<size_t>(j) * 3];
    float* south = &ring[(static_cast<size_t>(stacks) * slices + j) * 3];
    north[0] = north[1] = 0.0f;
    north[2] = static_cast<float>(radius);
    south[0] = south[1] = 0.0f;
    south[2] = static_cast<float>(-radius);
  }
  auto at = [&](int i, int j) { return &ring[(static_cast<size_t>(i) * slices + (j % slices)) * 3]; };
  std::vector<float> soup;
  soup.reserve(static_cast<size_t>(2 * slices * (stacks - 1)) * 9);
  auto push = [&](const float* a, const float* b, const float* c) {
    soup.insert(soup.end(), a, a + 3);
    soup.insert(soup.end(), b, b + 3);
    soup.insert(soup.end(), c, c + 3);
  };
  for (int i = 0; i < stacks; ++i) {
    for (int j = 0; j < slices; ++j) {
      const float* a = at(i, j);
      const float* b = at(i + 1, j);
      const float* c = at(i + 1, j + 1);
      const float* d = at(i, j + 1);
      // The first and last quad rows collapse to a pole: at i == 0 the quad's a
      // and d are both the north pole, at i == stacks - 1 its b and c are both the
      // south pole, so one half of each of those quads is a zero-area triangle.
      if (i != stacks - 1) push(a, b, c);
      if (i != 0) push(a, c, d);
    }
  }
  return soup;
}

Manifold BoxAt(const vec3& lo, const vec3& hi) {
  return Manifold::Cube(hi - lo, false).Translate(lo);
}

int Run(int argc, char** argv) {
#ifdef MANIFOLD_PAR
  std::cout << "manifold_spike version=" << MANIFOLD_VERSION_MAJOR << "." << MANIFOLD_VERSION_MINOR
            << "." << MANIFOLD_VERSION_PATCH << " manifold_par=" << MANIFOLD_PAR << "\n";
#else
  std::cout << "manifold_spike version=" << MANIFOLD_VERSION_MAJOR << "." << MANIFOLD_VERSION_MINOR
            << "." << MANIFOLD_VERSION_PATCH << " manifold_par=unknown\n";
#endif

  if (argc < 3) {
    std::cerr << "usage: manifold_spike large <out-prefix> [slices]\n"
                 "       manifold_spike small <in.stl> <out-prefix>\n";
    return 2;
  }
  const std::string mode = argv[1];
  std::string input_path;
  std::string out_prefix;
  if (mode == "large") {
    out_prefix = argv[2];
  } else if (mode == "small") {
    if (argc < 4) {
      std::cerr << "usage: manifold_spike small <in.stl> <out-prefix>\n";
      return 2;
    }
    input_path = argv[2];
    out_prefix = argv[3];
  } else {
    std::cerr << "unknown mode " << mode << "\n";
    return 2;
  }

  Manifold model;
  Step input_step;
  if (mode == "large") {
    // 250 x 201 is 100,000 triangles; the optional third argument trades triangles
    // for time so the scaling can be measured.
    const int slices = argc > 3 ? std::max(8, std::atoi(argv[3])) : 250;
    const std::vector<float> soup = MakeSphereSoup(slices, slices * 4 / 5 + 1, 20.0);
    const double t0 = NowMs();
    model = Manifold(SoupToMesh(soup));
    input_step = Measure("input", "generate-sphere", model, NowMs() - t0);
  } else {
    std::vector<float> soup;
    std::string err;
    const double t0 = NowMs();
    if (!ReadStl(input_path, soup, err)) {
      std::cerr << "ERROR reading " << input_path << ": " << err << "\n";
      return 1;
    }
    model = Manifold(SoupToMesh(soup));
    input_step = Measure("input", "load-stl", model, NowMs() - t0);
  }
  if (!input_step.valid) {
    std::cerr << "ERROR input is not a manifold solid: " << input_step.status << "\n";
    return 1;
  }

  const Box bb = model.BoundingBox();
  const vec3 lo = bb.min, hi = bb.max, size = hi - lo;
  const double diag = linalg::length(size);
  const double cut_x = 0.5 * (lo.x + hi.x);
  const double clearance = std::max(0.2, 0.001 * diag);
  const double beam_len = std::min(std::max(0.3 * size.x, 2.0), 0.6 * size.x);
  const double beam_w =
      std::min({std::max(0.25 * std::min(size.y, size.z), 2.0), 0.5 * size.y, 0.5 * size.z});
  const double beam_h = 0.7 * beam_w;
  const double cy = 0.5 * (lo.y + hi.y), cz = 0.5 * (lo.z + hi.z);
  const double embed = std::min(beam_len * 0.25, 2.0);

  std::cout << std::setprecision(4) << "MODEL size=" << size.x << "x" << size.y << "x" << size.z
            << " diag=" << diag << " cut_x=" << cut_x << " clearance=" << clearance << " beam="
            << beam_len << "x" << beam_w << "x" << beam_h << "\n";

  const double margin = 2 * diag;
  const Manifold keep_left = BoxAt(vec3(cut_x, lo.y - margin, lo.z - margin),
                                   vec3(hi.x + margin, hi.y + margin, hi.z + margin));
  const Manifold keep_right = BoxAt(vec3(lo.x - margin, lo.y - margin, lo.z - margin),
                                    vec3(cut_x, hi.y + margin, hi.z + margin));
  const Manifold beam = BoxAt(vec3(cut_x - embed, cy - beam_w / 2, cz - beam_h / 2),
                              vec3(cut_x + beam_len, cy + beam_w / 2, cz + beam_h / 2));
  const Manifold socket = BoxAt(
      vec3(cut_x - clearance, cy - beam_w / 2 - clearance, cz - beam_h / 2 - clearance),
      vec3(cut_x + beam_len + clearance, cy + beam_w / 2 + clearance, cz + beam_h / 2 + clearance));
  // Adversarial: a beam that only touches the cut face, with no overlap at all,
  // and a socket whose face is exactly coplanar with it. Both are the classic
  // degenerate cases for a boolean engine.
  const Manifold beam_flush = BoxAt(vec3(cut_x, cy - beam_w / 2, cz - beam_h / 2),
                                    vec3(cut_x + beam_len, cy + beam_w / 2, cz + beam_h / 2));
  const Manifold socket_flush = BoxAt(vec3(lo.x - 1, cy - beam_w / 2, cz - beam_h / 2),
                                      vec3(cut_x, cy + beam_w / 2, cz + beam_h / 2));

  Manifold half_a, half_b, a_beam, b_socket, a_flush, b_flush;
  const double ms_split_a = TimeOp([&] { return model - keep_left; }, half_a);
  const double ms_split_b = TimeOp([&] { return model - keep_right; }, half_b);
  const double ms_union = TimeOp([&] { return half_a + beam; }, a_beam);
  const double ms_subtract = TimeOp([&] { return half_b - socket; }, b_socket);
  const double ms_union_flush = TimeOp([&] { return half_a + beam_flush; }, a_flush);
  const double ms_subtract_flush = TimeOp([&] { return half_b - socket_flush; }, b_flush);

  const std::string tag = mode == "large" ? "large" : "small";
  const Step s_a = Measure(tag + "/split-A", "subtract", half_a, ms_split_a);
  const Step s_b = Measure(tag + "/split-B", "subtract", half_b, ms_split_b);
  Measure(tag + "/beam", "union", a_beam, ms_union);
  Measure(tag + "/socket", "subtract", b_socket, ms_subtract);
  Measure(tag + "/beam-flush", "union-coplanar", a_flush, ms_union_flush);
  Measure(tag + "/socket-flush", "subtract-coplanar", b_flush, ms_subtract_flush);

  std::cout << std::setprecision(6) << "CONSERVATION volume_model=" << input_step.volume
            << " volume_split_sum=" << (s_a.volume + s_b.volume)
            << " delta=" << std::fabs(input_step.volume - (s_a.volume + s_b.volume)) << "\n";

  // Repeat the two feature operations and compare: an engine that varies run to
  // run cannot be trusted to keep the beam and its socket matching.
  double vol_beam_first = 0, vol_socket_first = 0;
  double ms_union_sum = 0, ms_subtract_sum = 0;
  const int kRepeat = 4;
  for (int i = 0; i < kRepeat; ++i) {
    Manifold a2, b2;
    const double ms2 = TimeOp([&] { return half_a + beam; }, a2);
    const double ms2b = TimeOp([&] { return half_b - socket; }, b2);
    ms_union_sum += ms2;
    ms_subtract_sum += ms2b;
    std::cout << std::fixed << std::setprecision(3) << "REPEAT " << i << " union_ms=" << ms2
              << " subtract_ms=" << ms2b << std::setprecision(6)
              << " union_volume=" << a2.Volume() << " subtract_volume=" << b2.Volume()
              << " union_status=" << ErrName(a2.Status())
              << " subtract_status=" << ErrName(b2.Status()) << "\n";
    if (i == 0) {
      vol_beam_first = a2.Volume();
      vol_socket_first = b2.Volume();
    } else {
      std::cout << "REPEAT-DELTA " << i << " union=" << std::fabs(a2.Volume() - vol_beam_first)
                << " subtract=" << std::fabs(b2.Volume() - vol_socket_first) << "\n";
    }
  }
  std::cout << std::fixed << std::setprecision(3) << "REPEAT-MEAN union_ms="
            << (ms_union_sum / kRepeat) << " subtract_ms=" << (ms_subtract_sum / kRepeat) << "\n";

  std::string err;
  if (!WriteStl(out_prefix + ".stl", SafeMesh(a_beam), err) ||
      !WriteStl(out_prefix + "-socket.stl", SafeMesh(b_socket), err)) {
    std::cerr << "ERROR " << err << "\n";
    return 1;
  }
  // The written STL has to survive a re-import: that round trip is what the
  // slicer's own loader does with the file.
  for (const std::string& path : {out_prefix + ".stl", out_prefix + "-socket.stl"}) {
    std::vector<float> soup;
    std::string rerr;
    if (!ReadStl(path, soup, rerr)) {
      std::cerr << "ERROR re-reading " << path << ": " << rerr << "\n";
      return 1;
    }
    MeshGL gl = SoupToMesh(soup);
    Manifold back(gl);
    const Closure c = CheckClosed(gl);
    std::cout << "ROUNDTRIP " << path << " status=" << ErrName(back.Status())
              << " closed=" << (c.closed ? "yes" : "no") << " bad_edges=" << c.bad_edges
              << " degenerate=" << c.degenerate << " tris=" << back.NumTri()
              << std::setprecision(6) << " volume=" << back.Volume() << "\n";
  }
  std::cout << std::setprecision(3) << "WROTE " << out_prefix
            << ".stl (split - box, + beam) and " << out_prefix
            << "-socket.stl (split - box, - socket with " << clearance
            << "mm clearance)\n";
  std::cout << "MEM VmHWM=" << StatusKb("VmHWM:") << "kB VmRSS=" << StatusKb("VmRSS:") << "kB\n";
  std::cout << "DONE " << tag << "\n";
  return 0;
}

}  // namespace

int main(int argc, char** argv) {
  try {
    return Run(argc, argv);
  } catch (const std::exception& e) {
    std::cout << "EXCEPTION " << e.what() << "\n";
    return 3;
  } catch (...) {
    std::cout << "EXCEPTION unknown\n";
    return 3;
  }
}
