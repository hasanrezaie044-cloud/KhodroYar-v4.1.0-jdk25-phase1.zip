// Host driver for khodroyar_engine.cpp: the offline evaluation runs the app's own engine on an ARM64
// machine with the real downloaded files. It mirrors the Android image path: decode -> upright RGB888,
// longest side <= --max-side, SHA-256 of the pixels as the cache id (OfflineLlamaProvider.decodeImage).
//
//   khodroyar-engine-cli --model M.gguf [--mmproj P.gguf] [--ctx 8192] [--threads 4] [--batch 512]
//                        [--image-max-tokens 1024] [--max-side 1536] [--kv-q8]
// stdin : one JSON object per line  {"id":..., "request":{...engine request...}, "images":["a.jpg", ...]}
// stdout: {"event":"loaded", ...} then one line per request {"id", "result":{...}, "wall_ms", "rss_kb", "peak_rss_kb"}

#include "khodroyar_engine.h"

#define STB_IMAGE_STATIC
#define STB_IMAGE_IMPLEMENTATION
#include "stb/stb_image.h"
#include "nlohmann/json.hpp"

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iostream>
#include <string>
#include <vector>

using json = nlohmann::ordered_json;

namespace {

long status_kb(const char * key) {
    std::ifstream f("/proc/self/status");
    std::string line;
    const size_t n = std::strlen(key);
    while (std::getline(f, line)) {
        if (line.compare(0, n, key) == 0) return std::strtol(line.c_str() + n, nullptr, 10);
    }
    return -1;
}

double ms_since(std::chrono::steady_clock::time_point t0) {
    return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
}

// Minimal SHA-256 (FIPS 180-4) so the cache id is computed exactly like the app (SHA-256 of RGB bytes).
std::string sha256_hex(const std::vector<unsigned char> & data) {
    static const uint32_t k[64] = {
        0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,0xd807aa98,0x12835b01,
        0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,
        0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,
        0x06ca6351,0x14292967,0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
        0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,0x19a4c116,0x1e376c08,
        0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,
        0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2};
    uint32_t h[8] = {0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19};
    std::vector<unsigned char> m(data);
    const uint64_t bits = static_cast<uint64_t>(data.size()) * 8;
    m.push_back(0x80);
    while (m.size() % 64 != 56) m.push_back(0);
    for (int i = 7; i >= 0; i--) m.push_back(static_cast<unsigned char>(bits >> (i * 8)));
    auto rotr = [](uint32_t x, int n) { return (x >> n) | (x << (32 - n)); };
    for (size_t off = 0; off < m.size(); off += 64) {
        uint32_t w[64];
        for (int i = 0; i < 16; i++) w[i] = (m[off+4*i] << 24) | (m[off+4*i+1] << 16) | (m[off+4*i+2] << 8) | m[off+4*i+3];
        for (int i = 16; i < 64; i++) {
            const uint32_t s0 = rotr(w[i-15], 7) ^ rotr(w[i-15], 18) ^ (w[i-15] >> 3);
            const uint32_t s1 = rotr(w[i-2], 17) ^ rotr(w[i-2], 19) ^ (w[i-2] >> 10);
            w[i] = w[i-16] + s0 + w[i-7] + s1;
        }
        uint32_t a=h[0],b=h[1],c=h[2],d=h[3],e=h[4],f=h[5],g=h[6],hh=h[7];
        for (int i = 0; i < 64; i++) {
            const uint32_t t1 = hh + (rotr(e,6) ^ rotr(e,11) ^ rotr(e,25)) + ((e & f) ^ (~e & g)) + k[i] + w[i];
            const uint32_t t2 = (rotr(a,2) ^ rotr(a,13) ^ rotr(a,22)) + ((a & b) ^ (a & c) ^ (b & c));
            hh=g; g=f; f=e; e=d+t1; d=c; c=b; b=a; a=t1+t2;
        }
        h[0]+=a; h[1]+=b; h[2]+=c; h[3]+=d; h[4]+=e; h[5]+=f; h[6]+=g; h[7]+=hh;
    }
    char out[65];
    for (int i = 0; i < 8; i++) std::snprintf(out + i * 8, 9, "%08x", h[i]);
    return std::string(out, 64);
}

// Decode to RGB888 and area-downscale so the longest side is <= max_side (the app does the same before JNI).
bool load_image(const std::string & path, int max_side, khodroyar::Image & out) {
    int w = 0, h = 0, comp = 0;
    unsigned char * px = stbi_load(path.c_str(), &w, &h, &comp, 3);
    if (!px) return false;
    const double scale = std::min(1.0, static_cast<double>(max_side) / std::max(w, h));
    const int nw = std::max(1, static_cast<int>(w * scale + 0.5));
    const int nh = std::max(1, static_cast<int>(h * scale + 0.5));
    out.width = nw; out.height = nh;
    out.rgb.assign(static_cast<size_t>(nw) * nh * 3, 0);
    for (int y = 0; y < nh; y++) {
        const int y0 = y * h / nh, y1 = std::max(y0 + 1, (y + 1) * h / nh);
        for (int x = 0; x < nw; x++) {
            const int x0 = x * w / nw, x1 = std::max(x0 + 1, (x + 1) * w / nw);
            for (int c = 0; c < 3; c++) {
                long sum = 0;
                for (int yy = y0; yy < y1; yy++) for (int xx = x0; xx < x1; xx++) sum += px[(static_cast<size_t>(yy) * w + xx) * 3 + c];
                out.rgb[(static_cast<size_t>(y) * nw + x) * 3 + c] = static_cast<unsigned char>(sum / ((y1 - y0) * (x1 - x0)));
            }
        }
    }
    stbi_image_free(px);
    out.id = sha256_hex(out.rgb);
    return true;
}

} // namespace

int main(int argc, char ** argv) {
    std::string model, mmproj;
    int ctx = 8192, threads = 4, batch = 512, image_max_tokens = 1024, max_side = 1536;
    bool kv_q8 = false;
    for (int i = 1; i < argc; i++) {
        const std::string a = argv[i];
        auto next = [&]() -> std::string { if (i + 1 >= argc) { std::fprintf(stderr, "missing value for %s\n", a.c_str()); std::exit(2); } return argv[++i]; };
        if (a == "--model") model = next();
        else if (a == "--mmproj") mmproj = next();
        else if (a == "--ctx") ctx = std::stoi(next());
        else if (a == "--threads") threads = std::stoi(next());
        else if (a == "--batch") batch = std::stoi(next());
        else if (a == "--image-max-tokens") image_max_tokens = std::stoi(next());
        else if (a == "--max-side") max_side = std::stoi(next());
        else if (a == "--kv-q8") kv_q8 = true;
        else { std::fprintf(stderr, "unknown argument %s\n", a.c_str()); return 2; }
    }
    if (model.empty()) { std::fprintf(stderr, "--model is required\n"); return 2; }

    const json backends = json::parse(khodroyar::init_backends(""));
    const auto t_load = std::chrono::steady_clock::now();
    khodroyar::Engine * engine = khodroyar::load(model, ctx, threads, batch, kv_q8);
    if (!engine) { std::cout << json{{"event", "load_failed"}}.dump() << std::endl; return 1; }
    const double load_ms = ms_since(t_load);
    double vision_ms = 0;
    bool vision = false;
    if (!mmproj.empty()) {
        const auto t0 = std::chrono::steady_clock::now();
        vision = khodroyar::attach_vision(engine, mmproj, image_max_tokens);
        vision_ms = ms_since(t0);
    }
    std::cout << json{{"event", "loaded"}, {"load_ms", load_ms}, {"vision", vision}, {"vision_load_ms", vision_ms},
                      {"info", json::parse(khodroyar::info(engine))}, {"backends", backends},
                      {"settings", {{"ctx", ctx}, {"threads", threads}, {"batch", batch}, {"image_max_tokens", image_max_tokens},
                                    {"max_side", max_side}, {"kv", kv_q8 ? "q8_0" : "f16"}}},
                      {"rss_kb", status_kb("VmRSS:")}, {"peak_rss_kb", status_kb("VmHWM:")}}.dump() << std::endl;

    std::string line;
    while (std::getline(std::cin, line)) {
        if (line.empty()) continue;
        json in = json::parse(line, nullptr, false);
        if (in.is_discarded()) { std::cout << json{{"error", "bad_input"}}.dump() << std::endl; continue; }
        std::vector<khodroyar::Image> images;
        bool ok = true;
        for (const auto & p : in.value("images", json::array())) {
            khodroyar::Image img;
            if (!load_image(p.get<std::string>(), max_side, img)) { ok = false; break; }
            images.push_back(std::move(img));
        }
        json out = {{"id", in.value("id", json())}};
        if (!ok) {
            out["result"] = {{"ok", false}, {"error", "bad_image"}, {"message", "could not decode image file"}};
        } else {
            const auto t0 = std::chrono::steady_clock::now();
            const std::string result = khodroyar::chat(engine, in.at("request").dump(), images,
                [](const std::string &) { return true; });
            out["wall_ms"] = ms_since(t0);
            out["result"] = json::parse(result, nullptr, false);
        }
        out["rss_kb"] = status_kb("VmRSS:");
        out["peak_rss_kb"] = status_kb("VmHWM:");
        std::cout << out.dump() << std::endl;
    }
    khodroyar::free(engine);
    return 0;
}
