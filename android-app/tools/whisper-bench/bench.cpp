// Host benchmark harness for the exact decode parameters the app uses.
#include "whisper.h"
#include <chrono>
#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <mutex>
#include <string>
#include <vector>

static std::mutex g_mutex;
static double g_encode_ms = 0, g_decode_ms = 0, g_total_ms = 0, g_mel_ms = 0;
static int g_encode_runs = 0, g_decode_runs = 0, g_fb_p = 0, g_fb_h = 0;
static bool g_active = false;

static void logCallback(enum ggml_log_level level, const char * text, void *) {
    if (text == nullptr || !g_active) return;
    if (level == GGML_LOG_LEVEL_ERROR || level == GGML_LOG_LEVEL_WARN) return;
    std::lock_guard<std::mutex> guard(g_mutex);
    const char * p;
    if ((p = std::strstr(text, "mel time")) && std::strchr(p, '=')) std::sscanf(std::strchr(p, '=') + 1, "%lf", &g_mel_ms);
    if ((p = std::strstr(text, "encode time")) && std::strchr(p, '=')) std::sscanf(std::strchr(p, '=') + 1, "%lf ms / %d runs", &g_encode_ms, &g_encode_runs);
    if ((p = std::strstr(text, "decode time")) && std::strchr(p, '=')) std::sscanf(std::strchr(p, '=') + 1, "%lf ms / %d runs", &g_decode_ms, &g_decode_runs);
    if ((p = std::strstr(text, "total time")) && std::strchr(p, '=')) std::sscanf(std::strchr(p, '=') + 1, "%lf", &g_total_ms);
    if ((p = std::strstr(text, "fallbacks")) && std::strchr(p, '=')) std::sscanf(std::strchr(p, '=') + 1, "%d p / %d h", &g_fb_p, &g_fb_h);
}

// Minimal 16-bit PCM mono WAV reader.
static std::vector<float> readWav(const char * path, int * sample_rate, int * channels) {
    FILE * f = std::fopen(path, "rb");
    if (!f) { std::fprintf(stderr, "cannot open %s\n", path); std::exit(1); }
    char riff[4]; std::fread(riff, 1, 4, f);
    std::fseek(f, 22, SEEK_SET);
    uint16_t ch = 0, bits = 0; std::fread(&ch, 2, 1, f);
    uint32_t rate = 0; std::fread(&rate, 4, 1, f);
    std::fseek(f, 34, SEEK_SET); std::fread(&bits, 2, 1, f);
    // walk chunks to find data
    std::fseek(f, 12, SEEK_SET);
    uint32_t data_size = 0; long data_pos = 0;
    while (!std::feof(f)) {
        char id[4]; uint32_t size = 0;
        if (std::fread(id, 1, 4, f) != 4) break;
        if (std::fread(&size, 4, 1, f) != 1) break;
        if (std::memcmp(id, "data", 4) == 0) { data_size = size; data_pos = std::ftell(f); break; }
        std::fseek(f, size + (size & 1), SEEK_CUR);
    }
    std::vector<float> out;
    if (data_pos > 0 && bits == 16) {
        std::vector<int16_t> raw(data_size / 2);
        std::fseek(f, data_pos, SEEK_SET);
        std::fread(raw.data(), 2, raw.size(), f);
        out.resize(raw.size());
        for (size_t i = 0; i < raw.size(); ++i) out[i] = raw[i] / 32768.0f;
    }
    std::fclose(f);
    if (sample_rate) *sample_rate = (int) rate;
    if (channels) *channels = ch;
    return out;
}

int main(int argc, char ** argv) {
    if (argc < 8) {
        std::fprintf(stderr, "usage: %s model wav threads audio_ctx best_of temperature temp_inc [runs] [language] [seconds]\n", argv[0]);
        return 2;
    }
    const char * model = argv[1];
    const char * wav = argv[2];
    const int threads = std::atoi(argv[3]);
    const int audio_ctx = std::atoi(argv[4]);
    const int best_of = std::atoi(argv[5]);
    const float temperature = std::atof(argv[6]);
    const float temp_inc = std::atof(argv[7]);
    const int runs = argc > 8 ? std::atoi(argv[8]) : 1;
    const char * language = argc > 9 ? argv[9] : "en";
    const double seconds = argc > 10 ? std::atof(argv[10]) : 0.0;

    int rate = 0, channels = 0;
    std::vector<float> pcm = readWav(wav, &rate, &channels);
    if (pcm.empty()) { std::fprintf(stderr, "empty wav\n"); return 1; }
    if (seconds > 0.0) {
        size_t limit = (size_t)(seconds * rate);
        if (limit < pcm.size()) pcm.resize(limit);
    }
    std::fprintf(stderr, "audio: %zu samples, %.2fs at %d Hz\n", pcm.size(), (double) pcm.size() / rate, rate);

    whisper_log_set(logCallback, nullptr);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    auto t0 = std::chrono::steady_clock::now();
    whisper_context * ctx = whisper_init_from_file_with_params(model, cparams);
    auto load_ms = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - t0).count();
    if (!ctx) { std::fprintf(stderr, "load failed\n"); return 1; }
    std::fprintf(stderr, "model: %s load_ms=%lld n_audio_ctx=%d\n", model, (long long) load_ms, whisper_model_n_audio_ctx(ctx));

    for (int r = 0; r < runs; ++r) {
        whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
        p.print_realtime = false; p.print_progress = false; p.print_timestamps = false;
        p.print_special = false; p.translate = false; p.single_segment = false;
        p.no_context = true; p.n_threads = threads;
        p.greedy.best_of = best_of;
        p.temperature = temperature;
        p.temperature_inc = temp_inc;
        p.audio_ctx = audio_ctx;
        p.language = language;
        {
            std::lock_guard<std::mutex> guard(g_mutex);
            g_encode_ms = g_decode_ms = g_total_ms = g_mel_ms = 0;
            g_encode_runs = g_decode_runs = g_fb_p = g_fb_h = 0;
        }
        g_active = true;
        whisper_reset_timings(ctx);
        auto s = std::chrono::steady_clock::now();
        const int rc = whisper_full(ctx, p, pcm.data(), (int) pcm.size());
        auto wall = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - s).count();
        whisper_print_timings(ctx);
        g_active = false;
        std::string text;
        for (int i = 0; i < whisper_full_n_segments(ctx); ++i) {
            const char * seg = whisper_full_get_segment_text(ctx, i);
            if (seg) text += seg;
        }
        for (auto & c : text) if (c == '\n') c = ' ';
        std::lock_guard<std::mutex> guard(g_mutex);
        std::printf("RESULT run=%d rc=%d wall_ms=%lld mel_ms=%.1f encode_ms=%.1f encode_runs=%d decode_ms=%.1f decode_runs=%d fallbacks_p=%d fallbacks_h=%d audio_ctx=%d threads=%d best_of=%d temp_inc=%.2f text=%s\n",
                    r, rc, (long long) wall, g_mel_ms, g_encode_ms, g_encode_runs, g_decode_ms, g_decode_runs,
                    g_fb_p, g_fb_h, audio_ctx, threads, best_of, temp_inc, text.c_str());
        std::fflush(stdout);
    }
    whisper_free(ctx);
    return 0;
}
