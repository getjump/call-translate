#pragma once

#include <cstdint>
#include <functional>
#include <vector>

class WhisperMel {
public:
    explicit WhisperMel(const std::vector<uint8_t>& filters);
    std::vector<uint16_t> compute(const std::vector<float>& samples,
                                  const std::function<bool()>& canceled) const;

private:
    std::vector<float> filters_;
    std::vector<float> hann_;
};
