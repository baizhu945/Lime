// When a measured touch may replace the sentence spelled by the keys the finger actually hit.
//
// Gboard keeps that literal reading unless a slip is spatially plausible and the language
// model gain pays for it. A long composition is not rewritten by several ordinary misses:
// one letter between two keys can move, a tap near a key centre cannot, and two or three
// slips almost never all apply at once.
#ifndef CYIME_SPATIAL_CORRECTION_POLICY_H_
#define CYIME_SPATIAL_CORRECTION_POLICY_H_

#include <cmath>
#include <cstddef>

namespace rime {
namespace spatial_policy {

// Same threshold as corrector::kSpatialCheapCredibility. A neighbour within about 5× of the
// typed key (log-odds above this) is an ambiguous touch; further below, the tap belongs to
// the key that was hit.
inline constexpr double kAmbiguous = -1.6;
// The finger is in the gap, not merely a little off the key centre
// (about two-thirds of the way from the centre to the shared edge).
inline constexpr double kBoundary = -0.85;

struct EditStats {
  int count = 0;
  double sum = 0;
  double worst = 0;
};

// No literal sentence covers the input. At most two slips, and the second must itself
// be ambiguous — a long string is still not rewritten in several confident places.
inline bool AllowedWithoutLiteral(const EditStats& edits, size_t letters) {
  if (edits.count <= 0 || edits.count >= 3) return false;
  if (edits.count == 2 && edits.worst <= kAmbiguous) return false;
  if (edits.count == 2 && letters >= 18 && edits.worst <= kBoundary) return false;
  return true;
}

// `lm_gain` is log P(corrected text) − log P(literal text). `edits.sum` is the sum of
// log P(substituted letter | touch) − log P(typed letter | touch), so both are in nats.
inline bool BeatsLiteral(const EditStats& edits, double lm_gain, size_t letters) {
  if (edits.count <= 0 || edits.count >= 3) return false;
  if (edits.count >= 2 && edits.worst <= kAmbiguous) return false;
  if (edits.count >= 2 && letters >= 10 && edits.worst <= kBoundary) return false;
  // A confident tap rewrites a sentence the typed letters already spell only when that
  // one letter's correction is overwhelmingly the better sentence.
  if (edits.worst <= kAmbiguous) {
    if (edits.count != 1) return false;
    const double need = std::log(letters >= 16 ? 12.0 : letters >= 8 ? 6.0 : 3.0);
    return lm_gain + edits.sum > need;
  }
  double margin = std::log(edits.count == 1 ? 1.6 : 6.0);
  // Past a short word, a merely off-centre tap (still nearer the typed key) needs a
  // clearer language-model win. A touch in the gap does not.
  if (letters >= 12 && edits.count == 1 && edits.worst <= kBoundary)
    margin += std::log(letters >= 24 ? 3.5 : 2.2);
  if (letters >= 16 && edits.count >= 2) margin += std::log(3.0);
  return lm_gain + edits.sum > margin;
}

}  // namespace spatial_policy
}  // namespace rime

#endif  // CYIME_SPATIAL_CORRECTION_POLICY_H_
