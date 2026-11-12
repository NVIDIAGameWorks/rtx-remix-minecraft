/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

#include "water_normals.h"

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <complex>
#include <cstring>
#include <exception>
#include <limits>
#include <mutex>
#include <new>
#include <thread>
#include <vector>

#if defined(_M_X64)
#include <intrin.h>
#include <immintrin.h>
#elif defined(_M_ARM64)
#include <arm64_neon.h>
#endif

namespace {

constexpr int kFrameSize = 1024;
constexpr int kCols = 12;
constexpr int kRows = 10;
constexpr int kFrames = kCols * kRows;
constexpr int kFps = 20;
constexpr int kDefaultWorkerLimit = 4;
constexpr double kTileMetres = 10.0;
constexpr double kSpeed = 0.72;
constexpr double kSpectrumPower = 4.2;
constexpr double kSwellMetres = 3.0;
constexpr double kRippleMetres = 0.016;
constexpr double kSlopeRms = 0.0325;
constexpr double kGravity = 9.81;
constexpr double kPi = 3.141592653589793238462643383279502884;

using Complex = std::complex<float>;

int workerCount() {
    const unsigned int hardware = std::thread::hardware_concurrency();
    return hardware == 0
        ? 1
        : std::min(kDefaultWorkerLimit, static_cast<int>(hardware));
}

bool hasWideSimd() {
#if defined(_M_X64)
    static const bool available = [] {
        int registers[4]{};
        __cpuid(registers, 0);
        if (registers[0] < 7)
            return false;
        __cpuid(registers, 1);
        constexpr int osxsave = 1 << 27;
        constexpr int avx = 1 << 28;
        if ((registers[2] & (osxsave | avx)) != (osxsave | avx)
                || (_xgetbv(0) & 0x6) != 0x6)
            return false;
        __cpuidex(registers, 7, 0);
        constexpr int avx2 = 1 << 5;
        return (registers[1] & avx2) != 0;
    }();
    return available;
#elif defined(_M_ARM64)
    return true;
#else
    return false;
#endif
}

struct Ocean {
    std::vector<float> k;
    std::vector<Complex> h0;
    std::vector<Complex> h0ConjNeg;
    std::vector<int> cycles;
    int phaseStride = 0;
    std::vector<Complex> phases;
};

struct Image {
    int width = 0;
    int height = 0;
    std::vector<float> xy;
};

struct FftStage {
    int length = 0;
    std::vector<Complex> factors;
};

struct FftPlan {
    std::vector<int> bitReversal;
    std::vector<FftStage> stages;
};

struct FrameWorker {
    std::vector<Complex> transformed;
    std::vector<Complex> transposeScratch;
    Image image;
    double slopeSquare = 0.0;
    double encodedMax = 0.0;
};

std::uint64_t splitmix64(std::uint64_t seed, std::uint64_t index) {
    std::uint64_t z = seed + (index + 1) * UINT64_C(0x9E3779B97F4A7C15);
    z = (z ^ (z >> 30)) * UINT64_C(0xBF58476D1CE4E5B9);
    z = (z ^ (z >> 27)) * UINT64_C(0x94D049BB133111EB);
    return z ^ (z >> 31);
}

double uniform(std::uint64_t bits) {
    return (static_cast<double>(bits >> 11) + 0.5) * 0x1.0p-53;
}

double gaussian(std::uint64_t seed, std::size_t index, std::size_t count) {
    const double u0 = uniform(splitmix64(seed, index));
    const double u1 = uniform(splitmix64(seed, count + index));
    return std::sqrt(-2.0 * std::log(u0)) * std::cos(2.0 * kPi * u1);
}

double fftFrequency(int coordinate) {
    const int signedCoordinate = coordinate < kFrameSize / 2
        ? coordinate
        : coordinate - kFrameSize;
    return 2.0 * kPi / kTileMetres * signedCoordinate;
}

Ocean buildOcean(std::uint64_t seed) {
    const std::size_t pixels = static_cast<std::size_t>(kFrameSize) * kFrameSize;
    const std::size_t gaussianCount = 2 * pixels;
    Ocean ocean;
    ocean.k.resize(kFrameSize);
    ocean.h0.resize(pixels);
    ocean.h0ConjNeg.resize(pixels);
    ocean.cycles.resize(pixels);
    for (int i = 0; i < kFrameSize; ++i)
        ocean.k[i] = static_cast<float>(fftFrequency(i));

    for (int y = 0; y < kFrameSize; ++y) {
        for (int x = 0; x < kFrameSize; ++x) {
            const std::size_t i = static_cast<std::size_t>(y) * kFrameSize + x;
            const double kx = ocean.k[x];
            const double ky = ocean.k[y];
            const double magnitude = std::hypot(kx, ky);
            double spectrum = 0.0;
            if (magnitude > 0.0 && x != kFrameSize / 2 && y != kFrameSize / 2) {
                spectrum = std::exp(
                    -1.0 / std::pow(magnitude * kSwellMetres, 2.0)
                    -std::pow(magnitude * kRippleMetres, 2.0))
                    / std::pow(magnitude, kSpectrumPower);
            }
            const double amplitude = std::sqrt(spectrum / 2.0);
            ocean.h0[i] = Complex(
                static_cast<float>(gaussian(seed, i, gaussianCount) * amplitude),
                static_cast<float>(gaussian(seed, pixels + i, gaussianCount) * amplitude));
        }
    }

    for (int y = 0; y < kFrameSize; ++y) {
        const int negativeY = (kFrameSize - y) % kFrameSize;
        for (int x = 0; x < kFrameSize; ++x) {
            const int negativeX = (kFrameSize - x) % kFrameSize;
            const std::size_t i = static_cast<std::size_t>(y) * kFrameSize + x;
            const std::size_t negative =
                static_cast<std::size_t>(negativeY) * kFrameSize + negativeX;
            ocean.h0ConjNeg[i] = std::conj(ocean.h0[negative]);
        }
    }

    double slopePower = 0.0;
    for (int y = 0; y < kFrameSize; ++y) {
        for (int x = 0; x < kFrameSize; ++x) {
            const std::size_t i = static_cast<std::size_t>(y) * kFrameSize + x;
            const double power = std::norm(ocean.h0[i]) + std::norm(ocean.h0ConjNeg[i]);
            slopePower += (ocean.k[x] * ocean.k[x] + ocean.k[y] * ocean.k[y]) * power;
        }
    }
    const double n2 = static_cast<double>(pixels);
    const double slopeVariance = slopePower / (2.0 * n2 * n2);
    const double scale = kSlopeRms / std::sqrt(slopeVariance);
    for (std::size_t i = 0; i < pixels; ++i) {
        ocean.h0[i] *= static_cast<float>(scale);
        ocean.h0ConjNeg[i] *= static_cast<float>(scale);
    }

    const double loopSeconds = static_cast<double>(kFrames) / kFps;
    int maximumCycle = 0;
    for (int y = 0; y < kFrameSize; ++y) {
        for (int x = 0; x < kFrameSize; ++x) {
            const std::size_t i = static_cast<std::size_t>(y) * kFrameSize + x;
            const bool live = (x != 0 || y != 0)
                && x != kFrameSize / 2 && y != kFrameSize / 2;
            if (!live) {
                ocean.cycles[i] = 0;
                continue;
            }
            const double magnitude = std::hypot(ocean.k[x], ocean.k[y]);
            const double cycles = kSpeed * std::sqrt(kGravity * magnitude)
                * loopSeconds / (2.0 * kPi);
            ocean.cycles[i] = static_cast<int>(std::max(1.0, std::nearbyint(cycles)));
            maximumCycle = std::max(maximumCycle, ocean.cycles[i]);
        }
    }
    ocean.phaseStride = maximumCycle + 1;
    ocean.phases.resize(static_cast<std::size_t>(kFrames) * ocean.phaseStride);
    for (int frame = 0; frame < kFrames; ++frame) {
        const double frameFraction = static_cast<double>(frame) / kFrames;
        for (int cycle = 0; cycle <= maximumCycle; ++cycle) {
            const double angle = 2.0 * kPi * static_cast<double>(cycle) * frameFraction;
            ocean.phases[static_cast<std::size_t>(frame) * ocean.phaseStride + cycle] =
                Complex(static_cast<float>(std::cos(angle)), static_cast<float>(std::sin(angle)));
        }
    }
    return ocean;
}

FftPlan buildFftPlan(int count) {
    FftPlan plan;
    plan.bitReversal.resize(count);
    for (int i = 1, j = 0; i < count; ++i) {
        int bit = count >> 1;
        for (; j & bit; bit >>= 1)
            j ^= bit;
        j ^= bit;
        plan.bitReversal[i] = j;
    }
    for (int length = 2; length <= count; length <<= 1) {
        FftStage stage;
        stage.length = length;
        stage.factors.resize(length / 2);
        const Complex root = std::polar(
            1.0f, static_cast<float>(2.0 * kPi / static_cast<double>(length)));
        Complex factor(1.0f, 0.0f);
        for (int i = 0; i < length / 2; ++i) {
            stage.factors[i] = factor;
            factor *= root;
        }
        plan.stages.push_back(std::move(stage));
    }
    return plan;
}

#if defined(_M_X64)
__declspec(noinline) void inverseFftStagesAvx2(
    Complex* values,
    int count,
    const FftPlan& plan) {
    for (const FftStage& stage : plan.stages) {
        const int length = stage.length;
        const int half = length / 2;
        for (int start = 0; start < count; start += length) {
            int i = 0;
            for (; i + 4 <= half; i += 4) {
                const __m256 even = _mm256_loadu_ps(
                    reinterpret_cast<const float*>(values + start + i));
                const __m256 oddValues = _mm256_loadu_ps(
                    reinterpret_cast<const float*>(values + start + half + i));
                const __m256 factors = _mm256_loadu_ps(
                    reinterpret_cast<const float*>(stage.factors.data() + i));
                const __m256 oddReal = _mm256_moveldup_ps(oddValues);
                const __m256 oddImag = _mm256_movehdup_ps(oddValues);
                const __m256 swappedFactors = _mm256_permute_ps(factors, 0xB1);
                const __m256 odd = _mm256_addsub_ps(
                    _mm256_mul_ps(oddReal, factors),
                    _mm256_mul_ps(oddImag, swappedFactors));
                _mm256_storeu_ps(
                    reinterpret_cast<float*>(values + start + i),
                    _mm256_add_ps(even, odd));
                _mm256_storeu_ps(
                    reinterpret_cast<float*>(values + start + half + i),
                    _mm256_sub_ps(even, odd));
            }
            for (; i < half; ++i) {
                const Complex even = values[start + i];
                const Complex odd = values[start + half + i] * stage.factors[i];
                values[start + i] = even + odd;
                values[start + half + i] = even - odd;
            }
        }
    }
    const __m256 inverseCount = _mm256_set1_ps(1.0f / count);
    for (int i = 0; i < count; i += 4) {
        float* destination = reinterpret_cast<float*>(values + i);
        _mm256_storeu_ps(
            destination,
            _mm256_mul_ps(_mm256_loadu_ps(destination), inverseCount));
    }
    _mm256_zeroupper();
}
#endif

void inverseFft(Complex* values, int count, const FftPlan& plan) {
    for (int i = 1; i < count; ++i) {
        const int j = plan.bitReversal[i];
        if (i < j)
            std::swap(values[i], values[j]);
    }
#if defined(_M_X64)
    if (hasWideSimd()) {
        inverseFftStagesAvx2(values, count, plan);
        return;
    }
#endif
    for (const FftStage& stage : plan.stages) {
        const int length = stage.length;
        for (int start = 0; start < count; start += length) {
            for (int i = 0; i < length / 2; ++i) {
                const Complex even = values[start + i];
                const Complex odd =
                    values[start + i + length / 2] * stage.factors[i];
                values[start + i] = even + odd;
                values[start + i + length / 2] = even - odd;
            }
        }
    }
    const float inverseCount = 1.0f / count;
    for (int i = 0; i < count; ++i)
        values[i] *= inverseCount;
}

void blockedTranspose(
    const std::vector<Complex>& source,
    std::vector<Complex>& destination,
    int size) {
    constexpr int blockSize = 32;
    const std::size_t values = static_cast<std::size_t>(size) * size;
    if (destination.size() < values)
        destination.resize(values);
    for (int blockY = 0; blockY < size; blockY += blockSize) {
        for (int blockX = 0; blockX < size; blockX += blockSize) {
            const int endY = std::min(blockY + blockSize, size);
            const int endX = std::min(blockX + blockSize, size);
            for (int y = blockY; y < endY; ++y) {
                for (int x = blockX; x < endX; ++x) {
                    destination[static_cast<std::size_t>(x) * size + y] =
                        source[static_cast<std::size_t>(y) * size + x];
                }
            }
        }
    }
}

#if defined(_M_X64)
__declspec(noinline) void encodeOctahedralAvx2(
    const Complex* slopes,
    float* encoded,
    std::size_t pixels,
    double& slopeSquare,
    double& encodedMax) {
    const __m256 signMask = _mm256_set1_ps(-0.0f);
    const __m256 one = _mm256_set1_ps(1.0f);
    for (std::size_t i = 0; i < pixels; i += 4) {
        const float* source = reinterpret_cast<const float*>(slopes + i);
        const __m256 values = _mm256_loadu_ps(source);
        const __m256 absolute = _mm256_andnot_ps(signMask, values);
        const __m256 swapped = _mm256_permute_ps(absolute, 0xB1);
        const __m256 denominator = _mm256_add_ps(_mm256_add_ps(absolute, swapped), one);
        const __m256 result = _mm256_xor_ps(_mm256_div_ps(values, denominator), signMask);
        _mm256_storeu_ps(encoded + 2 * i, result);

        for (std::size_t pixel = i; pixel < i + 4; ++pixel) {
            const float sx = slopes[pixel].real();
            const float sy = slopes[pixel].imag();
            slopeSquare += sx * sx + sy * sy;
            encodedMax = std::max(
                encodedMax,
                static_cast<double>(std::max(
                    std::abs(encoded[2 * pixel]), std::abs(encoded[2 * pixel + 1]))));
        }
    }
    _mm256_zeroupper();
}
#elif defined(_M_ARM64)
void encodeOctahedralNeon(
    const Complex* slopes,
    float* encoded,
    std::size_t pixels,
    double& slopeSquare,
    double& encodedMax) {
    const float32x4_t one = vdupq_n_f32(1.0f);
    for (std::size_t i = 0; i < pixels; i += 2) {
        const float* source = reinterpret_cast<const float*>(slopes + i);
        const float32x4_t values = vld1q_f32(source);
        const float32x4_t absolute = vabsq_f32(values);
        const float32x4_t swapped = vrev64q_f32(absolute);
        const float32x4_t denominator = vaddq_f32(vaddq_f32(absolute, swapped), one);
        vst1q_f32(encoded + 2 * i, vnegq_f32(vdivq_f32(values, denominator)));

        for (std::size_t pixel = i; pixel < i + 2; ++pixel) {
            const float sx = slopes[pixel].real();
            const float sy = slopes[pixel].imag();
            slopeSquare += sx * sx + sy * sy;
            encodedMax = std::max(
                encodedMax,
                static_cast<double>(std::max(
                    std::abs(encoded[2 * pixel]), std::abs(encoded[2 * pixel + 1]))));
        }
    }
}
#endif

void frameEncoding(
    const Ocean& ocean,
    const FftPlan& fftPlan,
    int frame,
    double& slopeSquare,
    double& encodedMax,
    std::vector<Complex>& transformed,
    std::vector<Complex>& transposeScratch,
    Image& image) {
    const std::size_t pixels = static_cast<std::size_t>(kFrameSize) * kFrameSize;
    if (transformed.size() < pixels)
        transformed.resize(pixels);
    for (int y = 0; y < kFrameSize; ++y) {
        for (int x = 0; x < kFrameSize; ++x) {
            const std::size_t i = static_cast<std::size_t>(y) * kFrameSize + x;
            const int cycle = ocean.cycles[i];
            const Complex phase =
                ocean.phases[static_cast<std::size_t>(frame) * ocean.phaseStride + cycle];
            const Complex h =
                ocean.h0[i] * phase + ocean.h0ConjNeg[i] * std::conj(phase);
            transformed[i] = Complex(0.0f, ocean.k[x]) * h - ocean.k[y] * h;
        }
    }
    for (int y = 0; y < kFrameSize; ++y)
        inverseFft(
            transformed.data() + static_cast<std::size_t>(y) * kFrameSize,
            kFrameSize,
            fftPlan);
    blockedTranspose(transformed, transposeScratch, kFrameSize);
    for (int x = 0; x < kFrameSize; ++x) {
        inverseFft(
            transposeScratch.data() + static_cast<std::size_t>(x) * kFrameSize,
            kFrameSize,
            fftPlan);
    }
    blockedTranspose(transposeScratch, transformed, kFrameSize);

    image.width = kFrameSize;
    image.height = kFrameSize;
    if (image.xy.size() < 2 * pixels)
        image.xy.resize(2 * pixels);
#if defined(_M_X64)
    if (hasWideSimd()) {
        encodeOctahedralAvx2(
            transformed.data(), image.xy.data(), pixels, slopeSquare, encodedMax);
        return;
    }
#elif defined(_M_ARM64)
    encodeOctahedralNeon(
        transformed.data(), image.xy.data(), pixels, slopeSquare, encodedMax);
    return;
#endif
    for (std::size_t i = 0; i < pixels; ++i) {
        const float sx = transformed[i].real();
        const float sy = transformed[i].imag();
        slopeSquare += sx * sx + sy * sy;
        const float denominator = std::abs(sx) + std::abs(sy) + 1.0f;
        const float ox = -sx / denominator;
        const float oy = -sy / denominator;
        image.xy[2 * i] = ox;
        image.xy[2 * i + 1] = oy;
        encodedMax = std::max(
            encodedMax,
            static_cast<double>(std::max(std::abs(ox), std::abs(oy))));
    }
}

std::uint8_t roundedByte(float value) {
    const float rounded = std::nearbyint(value);
    return static_cast<std::uint8_t>(std::max(0.0f, std::min(255.0f, rounded)));
}

void writeFrameRg8(
    const Image& image,
    std::uint8_t* sheet,
    int sheetWidth,
    int frameRow,
    int frameColumn) {
    for (int y = 0; y < image.height; ++y) {
        const float* source =
            image.xy.data() + static_cast<std::size_t>(y) * image.width * 2;
        std::uint8_t* destination = sheet
            + (static_cast<std::size_t>(frameRow * image.height + y) * sheetWidth
                + frameColumn * image.width) * 2;
#if defined(_M_X64)
        if (hasWideSimd()) {
            const __m256 half = _mm256_set1_ps(0.5f);
            const __m256 scale = _mm256_set1_ps(255.0f);
            int value = 0;
            for (; value + 8 <= image.width * 2; value += 8) {
                const __m256 normalized = _mm256_mul_ps(
                    _mm256_add_ps(_mm256_mul_ps(_mm256_loadu_ps(source + value), half), half),
                    scale);
                const __m256i integers = _mm256_cvtps_epi32(normalized);
                const __m128i words = _mm_packus_epi32(
                    _mm256_castsi256_si128(integers),
                    _mm256_extracti128_si256(integers, 1));
                const __m128i bytes = _mm_packus_epi16(words, words);
                const std::uint64_t packed = static_cast<std::uint64_t>(_mm_cvtsi128_si64(bytes));
                std::memcpy(destination + value, &packed, sizeof(packed));
            }
            _mm256_zeroupper();
            continue;
        }
#endif
        for (int value = 0; value < image.width * 2; ++value)
            destination[value] = roundedByte((source[value] * 0.5f + 0.5f) * 255.0f);
    }
}

void copyError(const std::string& source, char* destination, std::size_t capacity) {
    if (!destination || capacity == 0)
        return;
    const std::size_t count = std::min(source.size(), capacity - 1);
    std::memcpy(destination, source.data(), count);
    destination[count] = '\0';
}

} // namespace

namespace water_normals {

std::size_t textureSize() {
    constexpr int width = kCols * kFrameSize;
    constexpr int height = kRows * kFrameSize;
    return static_cast<std::size_t>(width) * height * 2;
}

int generate(
    std::uint8_t* output,
    std::size_t outputSize,
    std::uint64_t seed,
    Stats* stats,
    std::string* error) {
    if (!output || outputSize < textureSize()) {
        if (error)
            *error = "Output buffer is null or too small.";
        return 1;
    }
    const auto started = std::chrono::steady_clock::now();
    try {
        constexpr int width = kCols * kFrameSize;
        constexpr int height = kRows * kFrameSize;
        const Ocean ocean = buildOcean(seed);
        const FftPlan fftPlan = buildFftPlan(kFrameSize);
        const int workers = workerCount();
        std::vector<FrameWorker> scratch(static_cast<std::size_t>(workers));
        std::atomic<int> nextFrame{0};
        std::atomic<bool> failed{false};
        std::exception_ptr failure;
        std::mutex failureMutex;
        const auto runWorker = [&](int workerIndex) {
            FrameWorker& state = scratch[workerIndex];
            try {
                while (!failed.load(std::memory_order_relaxed)) {
                    const int frame = nextFrame.fetch_add(1, std::memory_order_relaxed);
                    if (frame >= kFrames)
                        return;
                    const int row = frame / kCols;
                    const int col = frame % kCols;
                    frameEncoding(
                        ocean,
                        fftPlan,
                        frame,
                        state.slopeSquare,
                        state.encodedMax,
                        state.transformed,
                        state.transposeScratch,
                        state.image);
                    writeFrameRg8(state.image, output, width, row, col);
                }
            } catch (...) {
                failed.store(true, std::memory_order_relaxed);
                std::lock_guard<std::mutex> lock(failureMutex);
                if (!failure)
                    failure = std::current_exception();
            }
        };
        if (workers == 1) {
            runWorker(0);
        } else {
            std::vector<std::thread> threads;
            threads.reserve(static_cast<std::size_t>(workers));
            try {
                for (int worker = 0; worker < workers; ++worker)
                    threads.emplace_back(runWorker, worker);
            } catch (...) {
                failed.store(true, std::memory_order_relaxed);
                for (std::thread& thread : threads)
                    thread.join();
                throw;
            }
            for (std::thread& thread : threads)
                thread.join();
        }
        if (failure)
            std::rethrow_exception(failure);

        double slopeSquare = 0.0;
        double encodedMax = 0.0;
        for (const FrameWorker& state : scratch) {
            slopeSquare += state.slopeSquare;
            encodedMax = std::max(encodedMax, state.encodedMax);
        }
        const auto finished = std::chrono::steady_clock::now();
        if (stats) {
            const double sampleCount =
                static_cast<double>(kFrames) * kFrameSize * kFrameSize * 2.0;
            stats->slopeRms = std::sqrt(slopeSquare / sampleCount);
            stats->encodedMax = encodedMax;
            stats->generationSeconds = std::chrono::duration<double>(finished - started).count();
            stats->elapsedSeconds = stats->generationSeconds;
        }
        if (error)
            error->clear();
        return 0;
    } catch (const std::bad_alloc&) {
        if (error)
            *error = "Not enough memory to generate the water-normal flipbook.";
        return 4;
    } catch (const std::exception& exception) {
        if (error)
            *error = exception.what();
        return 5;
    } catch (...) {
        if (error)
            *error = "Unknown water-normal generator failure.";
        return 6;
    }
}

} // namespace water_normals

REMIXBRIDGE_EXPORT std::size_t remixbridge_water_normals_texture_size() {
    return water_normals::textureSize();
}

REMIXBRIDGE_EXPORT int remixbridge_generate_water_normals(
    void* output,
    std::size_t outputSize,
    std::uint64_t seed,
    double* timings,
    std::size_t timingCount,
    char* error,
    std::size_t errorCapacity) {
    std::string message;
    water_normals::Stats stats{};
    const int status = water_normals::generate(
        static_cast<std::uint8_t*>(output), outputSize, seed, &stats, &message);
    if (timings && timingCount > 0) {
        const std::array<double, 2> values = {
            stats.generationSeconds,
            stats.elapsedSeconds,
        };
        std::copy_n(values.begin(), std::min(timingCount, values.size()), timings);
    }
    copyError(message, error, errorCapacity);
    return status;
}
