#ifndef MODULES_AUDIO_PROCESSING_AUDIO_BUFFER_H_
#define MODULES_AUDIO_PROCESSING_AUDIO_BUFFER_H_

#include <stddef.h>

#include <vector>

#include "modules/audio_processing/ns/ns_common.h"

namespace webrtc {

// Minimal shim for NoiseSuppressor.
// Stores deinterleaved split-band buffers only.
class AudioBuffer {
 public:
  AudioBuffer(size_t num_channels, size_t num_bands, size_t frame_size)
      : num_channels_(num_channels),
        num_bands_(num_bands),
        frame_size_(frame_size),
        data_(num_channels_, std::vector<std::vector<float>>(
                                num_bands_, std::vector<float>(frame_size_, 0.0f))),
        band_ptrs_(num_channels_, std::vector<float*>(num_bands_, nullptr)) {
    for (size_t ch = 0; ch < num_channels_; ++ch) {
      for (size_t b = 0; b < num_bands_; ++b) {
        band_ptrs_[ch][b] = data_[ch][b].data();
      }
    }
  }

  size_t num_channels() const { return num_channels_; }
  size_t num_bands() const { return num_bands_; }
  size_t frame_size() const { return frame_size_; }

  float* const* split_bands(size_t channel) {
    return band_ptrs_[channel].data();
  }

  const float* const* split_bands_const(size_t channel) const {
    return const_cast<const float* const*>(band_ptrs_[channel].data());
  }

  static const size_t kMaxSplitFrameLength = kNsFrameSize;
  static const size_t kMaxNumBands = 3;

 private:
  size_t num_channels_;
  size_t num_bands_;
  size_t frame_size_;
  std::vector<std::vector<std::vector<float>>> data_;
  std::vector<std::vector<float*>> band_ptrs_;
};

}  // namespace webrtc

#endif  // MODULES_AUDIO_PROCESSING_AUDIO_BUFFER_H_
