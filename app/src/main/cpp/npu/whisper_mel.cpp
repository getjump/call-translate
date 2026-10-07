#include "whisper_mel.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <stdexcept>
#include <thread>

namespace {

constexpr int SAMPLES = 480000;
constexpr int FFT = 400;
constexpr int BINS = 201;
constexpr int HOP = 160;
constexpr int FRAMES = 3000;
constexpr int MELS = 128;
constexpr double QUANT_SCALE = 0.00004746108970721252;
constexpr int QUANT_ZERO_POINT = 31605;
constexpr double PI = 3.14159265358979323846;

void fft(const float* input, int size, int stride, float* output) {
    if (size == 1) {
        output[0] = input[0];
        output[1] = 0;
        return;
    }
    if (size % 2 == 1) {
        for (int frequency = 0; frequency < size; frequency++) {
            double real = 0;
            double imaginary = 0;
            for (int time = 0; time < size; time++) {
                double angle = -2 * PI * frequency * time / size;
                real += input[time * stride] * std::cos(angle);
                imaginary += input[time * stride] * std::sin(angle);
            }
            output[2 * frequency] = static_cast<float>(real);
            output[2 * frequency + 1] = static_cast<float>(imaginary);
        }
        return;
    }
    int half = size / 2;
    std::vector<float> even(2 * half);
    std::vector<float> odd(2 * half);
    fft(input, half, stride * 2, even.data());
    fft(input + stride, half, stride * 2, odd.data());
    for (int frequency = 0; frequency < half; frequency++) {
        double angle = -2 * PI * frequency / size;
        double real = std::cos(angle) * odd[2 * frequency] - std::sin(angle) * odd[2 * frequency + 1];
        double imaginary = std::cos(angle) * odd[2 * frequency + 1] + std::sin(angle) * odd[2 * frequency];
        output[2 * frequency] = even[2 * frequency] + static_cast<float>(real);
        output[2 * frequency + 1] = even[2 * frequency + 1] + static_cast<float>(imaginary);
        output[2 * (frequency + half)] = even[2 * frequency] - static_cast<float>(real);
        output[2 * (frequency + half) + 1] = even[2 * frequency + 1] - static_cast<float>(imaginary);
    }
}

}

WhisperMel::WhisperMel(const std::vector<uint8_t>& filters) : filters_(MELS * BINS), hann_(FFT) {
    if (filters.size() != filters_.size() * sizeof(float))
        throw std::invalid_argument("invalid Whisper 128-bin mel filters");
    std::memcpy(filters_.data(), filters.data(), filters.size());
    for (float filter : filters_) {
        if (!std::isfinite(filter) || filter < 0)
            throw std::invalid_argument("invalid Whisper mel filter value");
    }
    for (int index = 0; index < FFT; index++)
        hann_[index] = 0.5f * (1.0f - std::cos(2.0f * static_cast<float>(PI) * index / FFT));
}

std::vector<uint16_t> WhisperMel::compute(const std::vector<float>& samples,
                                         const std::function<bool()>& canceled) const {
    if (samples.size() < 16000 || samples.size() > SAMPLES)
        throw std::invalid_argument("Whisper audio must contain 1 to 30 seconds at 16 kHz");
    constexpr int PAD = FFT / 2;
    std::vector<float> padded(SAMPLES + 2 * PAD, 0);
    std::copy(samples.begin(), samples.end(), padded.begin() + PAD);
    for (int index = 0; index < PAD; index++) {
        padded[PAD - 1 - index] = padded[PAD + 1 + index];
        padded[PAD + SAMPLES + index] = padded[PAD + SAMPLES - 2 - index];
    }
    std::vector<float> mel(MELS * FRAMES);
    std::vector<std::thread> workers;
    try {
        for (int worker = 0; worker < 4; worker++) {
            workers.emplace_back([&, worker] {
                std::vector<float> frame(FFT), spectrum(2 * FFT), power(BINS);
                for (int time = worker; time < FRAMES; time += 4) {
                    if (canceled()) return;
                    const float* source = padded.data() + time * HOP;
                    for (int index = 0; index < FFT; index++) frame[index] = source[index] * hann_[index];
                    fft(frame.data(), FFT, 1, spectrum.data());
                    for (int bin = 0; bin < BINS; bin++)
                        power[bin] = spectrum[2 * bin] * spectrum[2 * bin] +
                                     spectrum[2 * bin + 1] * spectrum[2 * bin + 1];
                    for (int channel = 0; channel < MELS; channel++) {
                        const float* filter = filters_.data() + channel * BINS;
                        double value = 0;
                        for (int bin = 0; bin < BINS; bin++) value += filter[bin] * power[bin];
                        mel[channel * FRAMES + time] = static_cast<float>(std::log10(std::max(value, 1e-10)));
                    }
                }
            });
        }
    } catch (...) {
        for (auto& worker : workers) worker.join();
        throw;
    }
    for (auto& worker : workers) worker.join();
    if (canceled()) return {};
    float minimum = *std::max_element(mel.begin(), mel.end()) - 8;
    std::vector<uint16_t> quantized(mel.size());
    for (size_t index = 0; index < mel.size(); index++) {
        float value = (std::max(mel[index], minimum) + 4) / 4;
        long quantizedValue = std::lround(value / QUANT_SCALE) + QUANT_ZERO_POINT;
        quantized[index] = static_cast<uint16_t>(std::clamp(quantizedValue, 0L, 65535L));
    }
    return quantized;
}
