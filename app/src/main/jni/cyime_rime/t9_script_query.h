// What CyIME's sentence code reads from one script translation. ScriptSyllabifier is declared only
// inside Rime's script_translator.cc, so its correction test arrives as a function pointer.
#ifndef CYIME_T9_SCRIPT_QUERY_H_
#define CYIME_T9_SCRIPT_QUERY_H_

#include <cstddef>

#include <rime/algo/syllabifier.h>
#include <rime/common.h>
#include <rime/dict/vocabulary.h>

namespace rime {

class PhraseSyllabifier;
class ScriptTranslator;

struct T9ScriptQuery {
  ScriptTranslator* translator;
  const an<PhraseSyllabifier>& syllabifier;  // given to every sentence made
  const string& input;
  const SyllableGraph& syllables;
  size_t start;
  size_t end_of_input;
  bool enable_correction;
  const void* script_syllabifier;
  bool (*is_correction)(const void* script_syllabifier, const Code& code, size_t code_length, size_t start);
  bool (*cheap_spatial)(const void* script_syllabifier, const Code& code, size_t code_length, size_t start,
                        double* penalty);
  bool (*spatial_penalty)(const void* script_syllabifier, const Code& code, size_t code_length, size_t start,
                          double* penalty);
  // The app measured this composition's touches, so correction credibility is P(letter | touch) log-odds.
  bool spatial_active;

  // ScriptSyllabifier::IsCorrection.
  bool IsCorrection(const Code& code, size_t code_length, size_t from = 0) const {
    return is_correction(script_syllabifier, code, code_length, from);
  }
  bool CheapSpatial(const Code& code, size_t code_length, double* penalty, size_t from = 0) const {
    return cheap_spatial && cheap_spatial(script_syllabifier, code, code_length, from, penalty);
  }
  // Sum of every correction edge's touch log-odds on the path (all of them, however weak).
  bool SpatialPenalty(const Code& code, size_t code_length, double* penalty, size_t from = 0) const {
    return spatial_penalty && spatial_penalty(script_syllabifier, code, code_length, from, penalty);
  }
};

}  // namespace rime

#endif  // CYIME_T9_SCRIPT_QUERY_H_
