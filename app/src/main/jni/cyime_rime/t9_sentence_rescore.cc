#include "t9_sentence_rescore.h"

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstring>
#include <functional>
#include <limits>
#include <unordered_map>
#include <vector>

#include <rime/gear/poet.h>
#include <rime/gear/script_translator.h>
#include <rime/gear/translator_commons.h>
#include <t9_ngram_model.h>

#include "spatial_correction_policy.h"

namespace rime {

// ln P(text) over splits into model words, plus the sentence-position rules Rime applied.
static double ModelScore(const T9NgramModel* ngram, bool ngram_sentence_start, Poet* poet,
                         const Sentence& sentence) {
  thread_local std::unordered_map<string, int32_t> ids;
  thread_local const T9NgramModel* ids_model = nullptr;
  if (ids_model != ngram || ids.size() > 65536) { ids.clear(); ids_model = ngram; }
  auto word_id = [&](const string& word) {
    auto found = ids.find(word);
    if (found != ids.end()) return found->second;
    const int32_t id = ngram->Id(word);
    ids.emplace(word, id);
    return id;
  };
  const string& text = sentence.text();
  vector<size_t> at{0};
  for (size_t i = 0; i < text.size();) {
    const unsigned char c = static_cast<unsigned char>(text[i]);
    i += c < 0x80 ? 1 : c < 0xE0 ? 2 : c < 0xF0 ? 3 : 4;
    at.push_back(std::min(i, text.size()));
  }
  const size_t n = at.size() - 1;
  struct State { double value; int32_t prev2, prev1; bool started; };
  constexpr size_t kStates = 4;
  auto log_add = [](double a, double b) {
    if (!std::isfinite(a)) return b;
    if (!std::isfinite(b)) return a;
    return std::max(a, b) + std::log1p(std::exp(-std::fabs(a - b)));
  };
  const int32_t root = ngram_sentence_start ? ngram->Bos() : ::T9NgramModel::kUnseen;
  vector<vector<State>> states(n + 1);
  states[0].push_back({0, root, root, false});
  for (size_t end = 1; end <= n; ++end) {
    auto& here = states[end];
    for (size_t start = end > 8 ? end - 8 : 0; start < end; ++start) {
      if (states[start].empty()) continue;
      const int32_t id = word_id(text.substr(at[start], at[end] - at[start]));
      if (id == ::T9NgramModel::kUnseen && end - start > 1) continue;
      for (const State& from : states[start]) {
        const double value = from.value + (from.started
            ? ngram->LogProb3(from.prev2, from.prev1, id) : ngram->Start(id, ngram_sentence_start));
        const State next{value, from.started ? from.prev1 : root, id, true};
        auto same = std::find_if(here.begin(), here.end(), [&](const State& s) { return s.prev1 == id; });
        if (same != here.end()) {
          const double sum = log_add(same->value, value);
          if (value > same->value) *same = next;
          same->value = sum;
          continue;
        }
        if (here.size() < kStates) { here.push_back(next); continue; }
        auto worst = std::min_element(here.begin(), here.end(),
                                      [](const State& a, const State& b) { return a.value < b.value; });
        if (value > worst->value) *worst = next;
      }
    }
  }
  double total = -std::numeric_limits<double>::infinity();
  for (const State& s : states[n]) total = log_add(total, s.value);
  const auto& words = sentence.components();
  if (!words.empty() && poet) {
    total += poet->PositionAdjust(words.front().text, true, words.size() == 1);
    if (words.size() > 1) total += poet->PositionAdjust(words.back().text, false, true);
  }
  return total;
}

// The same path ScriptSyllabifier::syllabify_dfs walks: typed edges before corrections,
// longer spellings first. `edits` receives one entry per corrected syllable on that path.
static bool ReadEdits(const T9ScriptQuery& q, const Sentence& sentence, spatial_policy::EditStats* edits) {
  const Code& code = sentence.code();
  const size_t target = sentence.end() - q.start;
  spatial_policy::EditStats stats;
  std::function<bool(size_t, size_t)> walk = [&](size_t pos, size_t depth) -> bool {
    if (depth == code.size()) return pos == target;
    const auto row = q.syllables.edges.find(pos);
    if (row == q.syllables.edges.end()) return false;
    for (const bool want_correction : {false, true}) {
      for (auto it = row->second.rbegin(); it != row->second.rend(); ++it) {
        if (it->first > target) continue;
        const auto spelling = it->second.find(code[depth]);
        if (spelling == it->second.end() || spelling->second.is_correction != want_correction) continue;
        const int saved_count = stats.count;
        const double saved_sum = stats.sum;
        const double saved_worst = stats.worst;
        if (want_correction) {
          const double cost = spelling->second.credibility;
          if (stats.count == 0 || cost < stats.worst) stats.worst = cost;
          stats.sum += cost;
          ++stats.count;
        }
        if (walk(it->first, depth + 1)) return true;
        stats.count = saved_count;
        stats.sum = saved_sum;
        stats.worst = saved_worst;
      }
    }
    return false;
  };
  if (!walk(0, 0) || stats.count == 0) return false;
  *edits = stats;
  return true;
}

// Drops corrected words so a second sentence search sees only the letters that were hit.
static WordGraph StripCorrections(const T9ScriptQuery& q, const WordGraph& sentence_graph) {
  vector<std::pair<size_t, size_t>> corrected_edges;
  for (const auto& from : q.syllables.edges)
    for (const auto& to : from.second)
      for (const auto& spelling : to.second)
        if (spelling.second.is_correction) {
          corrected_edges.emplace_back(from.first, to.first);
          break;
        }
  WordGraph typed;
  for (const auto& from : sentence_graph)
    for (const auto& to : from.second) {
      const bool clean = std::none_of(corrected_edges.begin(), corrected_edges.end(), [&](const auto& edge) {
        return edge.first >= static_cast<size_t>(from.first) && edge.second <= static_cast<size_t>(to.first);
      });
      auto& bucket = typed[from.first][to.first];
      if (clean) {
        bucket = to.second;
        continue;
      }
      for (const auto& entry : to.second)
        if (!q.IsCorrection(entry->code, to.first, from.first)) bucket.push_back(entry);
    }
  return typed;
}

void T9RescoreSentences(const T9ScriptQuery& q, const T9NgramModel* ngram, bool ngram_sentence_start, Poet* poet,
                        const WordGraph* sentence_graph, int max_sentences, double cutoff,
                        const an<Sentence>& exact_sentence, const an<Sentence>& rime_best,
                        deque<an<Sentence>>* sentences) {
  // translator/max_sentences gave the n-best to choose from; the menu keeps one sentence, as with
  // max_sentences: 1 (followed by Rime's best when that one is a correction).
  auto corrected = [&](const an<Sentence>& sentence) {
    return q.enable_correction && q.IsCorrection(sentence->code(), sentence->end() - q.start);
  };
  an<Sentence> chosen = sentences->front();
  // The sentence spelled by the keys that were hit, kept beside a correction that replaced it.
  an<Sentence> typed_anchor;
  const string& in = q.input;
  size_t groups = 0;
  bool vowel = false, consonants_only = !in.empty();
  for (char ch : in) {
    // 14-key merged keys are capitals; E/U/O/A carry the vowels (er, ui, op, as).
    const bool v = ch && std::strchr("aeiouvEUOA", ch);
    if (v && !vowel) ++groups;
    vowel = v;
    if (ch < 'a' || ch > 'z' || v) consonants_only = false;
  }
  // Initials and short abbreviations (zjqa, zdky) keep Rime's dictionary order.
  if (ngram && !consonants_only && groups >= 2) {
    auto score = [&](const Sentence& sentence) {
      double value = ModelScore(ngram, ngram_sentence_start, poet, sentence);
      // With measured touches the correction edge credibility is log P(letter | touch) relative to
      // the typed letter, so the sentence's final score is the word model times the touch model.
      if (q.spatial_active) {
        double penalty = 0;
        if (q.SpatialPenalty(sentence.code(), sentence.end() - q.start, &penalty))
          value += penalty;
      }
      return value;
    };
    // A typo correction never wins on the word model alone. Corrected sentences would also take
    // n-best places from typed readings (qunawanle lost 你去哪玩了 to 你去哪完了 once swapped letters
    // were corrections), so without a touch model the choice is among sentences made from the
    // same graph without corrected words: the typed n-best, made again when corrections left two
    // typed sentences or fewer (rebuilding whenever one place was taken cost a per-key p95 of
    // +3.5%, this +1.6%).
    // With a touch model the typed sentence stays the anchor. A correction replaces it only when
    // each slip is a plausible touch and the language-model gain pays for it (spatial_policy):
    // one letter in the gap between two keys can move a long sentence, ordinary scatter cannot,
    // and several slips do not all apply at once.
    deque<an<Sentence>> pool;
    for (const auto& sentence : *sentences)
      if (q.spatial_active || !corrected(sentence)) pool.push_back(sentence);
    if (!q.spatial_active && pool.size() <= 2 && pool.size() < sentences->size() && sentence_graph && poet) {
      WordGraph typed = StripCorrections(q, *sentence_graph);
      auto remade = poet->MakeSentences(typed, q.syllables.interpreted_length,
                                        q.translator->GetPrecedingText(q.start), max_sentences, cutoff);
      for (auto& sentence : remade) {
        sentence->Offset(q.start);
        sentence->set_syllabifier(q.syllabifier);
      }
      if (exact_sentence && !corrected(exact_sentence)) remade.push_front(exact_sentence);
      if (!remade.empty()) pool = std::move(remade);
    }
    if (q.spatial_active) {
      auto lm_only = [&](const Sentence& sentence) {
        return ModelScore(ngram, ngram_sentence_start, poet, sentence);
      };
      double literal_lm = -std::numeric_limits<double>::infinity();
      auto consider_literal = [&](const an<Sentence>& sentence) {
        if (!sentence || corrected(sentence)) return;
        const double value = lm_only(*sentence);
        if (value > literal_lm) {
          literal_lm = value;
          typed_anchor = sentence;
        }
      };
      for (const auto& sentence : pool) consider_literal(sentence);
      consider_literal(exact_sentence);
      // Incremental composition often has no exact sentence cached, and corrections can fill
      // every n-best slot. One more search on the typed graph restores the anchor.
      if (!typed_anchor && sentence_graph && poet) {
        WordGraph typed = StripCorrections(q, *sentence_graph);
        auto sentence = poet->MakeSentence(typed, q.syllables.interpreted_length,
                                           q.translator->GetPrecedingText(q.start));
        if (sentence) {
          sentence->Offset(q.start);
          sentence->set_syllabifier(q.syllabifier);
          consider_literal(sentence);
        }
      }
      if (typed_anchor) {
        chosen = typed_anchor;
        double best_adj = literal_lm;
        for (const auto& sentence : pool) {
          if (!sentence || !corrected(sentence)) continue;
          spatial_policy::EditStats edits;
          if (!ReadEdits(q, *sentence, &edits)) continue;
          const double lm = lm_only(*sentence);
          if (!spatial_policy::BeatsLiteral(edits, lm - literal_lm, in.size())) continue;
          const double adj = lm + edits.sum;
          if (adj > best_adj) {
            best_adj = adj;
            chosen = sentence;
          }
        }
      } else {
        chosen.reset();
        double best_adj = -std::numeric_limits<double>::infinity();
        for (const auto& sentence : pool) {
          if (!sentence) continue;
          spatial_policy::EditStats edits;
          const bool is_correction = corrected(sentence);
          if (is_correction && !ReadEdits(q, *sentence, &edits)) continue;
          if (is_correction && !spatial_policy::AllowedWithoutLiteral(edits, in.size())) continue;
          double value = lm_only(*sentence);
          if (is_correction) value += edits.sum;
          if (!chosen || value > best_adj) {
            best_adj = value;
            chosen = sentence;
          }
        }
        if (!chosen) chosen = sentences->front();
      }
    } else {
      double chosen_score = -std::numeric_limits<double>::infinity();
      for (const auto& sentence : pool) {
        const double value = score(*sentence);
        if (value > chosen_score) { chosen_score = value; chosen = sentence; }
      }
    }
    // With stray initials (Syllabifier::MarkStrayInitials) the typed reading stands only through initials weighed as
    // slips, so the corrected n-best is chosen by the word model too and leads (清晨 over 清真 in 是清晨的一杯).
    if (q.syllables.cyime_stray_initials && q.enable_correction) {
      an<Sentence> fixed;
      double fixed_score = -std::numeric_limits<double>::infinity();
      for (const auto& sentence : *sentences) {
        if (!corrected(sentence)) continue;
        const double value = score(*sentence);
        if (value > fixed_score) { fixed_score = value; fixed = sentence; }
      }
      if (fixed) {
        sentences->clear();
        sentences->push_back(fixed);
        sentences->push_back(chosen);
        return;
      }
    }
  }
  deque<an<Sentence>> spatial_kept;
  for (const auto& sentence : *sentences) {
    double penalty = 0;
    if (sentence && q.CheapSpatial(sentence->code(), sentence->end() - q.start, &penalty) &&
        spatial_kept.size() < 3)
      spatial_kept.push_back(sentence);
  }
  sentences->clear();
  sentences->push_back(chosen);
  if (typed_anchor && chosen && typed_anchor->text() != chosen->text())
    sentences->push_back(typed_anchor);
  for (const auto& extra : spatial_kept) {
    if (!extra || extra->text() == chosen->text()) continue;
    if (typed_anchor && extra->text() == typed_anchor->text()) continue;
    sentences->push_back(extra);
  }
  if (rime_best && rime_best != chosen && corrected(rime_best)) {
    bool already = false;
    for (const auto& sentence : *sentences)
      if (sentence && sentence->text() == rime_best->text()) already = true;
    if (!already) sentences->push_back(rime_best);
  }
}

void T9PromoteSpatial(const T9ScriptQuery& q, const T9NgramModel* ngram, bool ngram_sentence_start, Poet* poet,
                      deque<an<Sentence>>* sentences) {
  if (!ngram || !sentences || sentences->size() < 2 || !sentences->front()) return;
  const string& in = q.input;
  size_t groups = 0;
  bool vowel = false, consonants_only = !in.empty();
  for (char ch : in) {
    const bool v = ch && std::strchr("aeiouvEUOA", ch);
    if (v && !vowel) ++groups;
    vowel = v;
    if (ch < 'a' || ch > 'z' || v) consonants_only = false;
  }
  if (consonants_only || groups < 2) return;
  const auto& front = sentences->front();
  // A measured touch already chose in T9RescoreSentences. Promote only a slip that beats the
  // current first sentence by that same margin, so a later pass cannot put a weak correction back.
  if (q.spatial_active) {
    spatial_policy::EditStats front_edits;
    if (ReadEdits(q, *front, &front_edits)) return;
    const double front_lm = ModelScore(ngram, ngram_sentence_start, poet, *front);
    size_t best_index = 0;
    double best = -std::numeric_limits<double>::infinity();
    for (size_t i = 1; i < sentences->size(); ++i) {
      const auto& sentence = (*sentences)[i];
      if (!sentence) continue;
      spatial_policy::EditStats edits;
      if (!ReadEdits(q, *sentence, &edits)) continue;
      const double lm = ModelScore(ngram, ngram_sentence_start, poet, *sentence);
      if (!spatial_policy::BeatsLiteral(edits, lm - front_lm, in.size())) continue;
      const double value = lm + edits.sum;
      if (value > best) { best = value; best_index = i; }
    }
    if (best_index == 0) return;
    auto chosen = (*sentences)[best_index];
    sentences->erase(sentences->begin() + static_cast<std::ptrdiff_t>(best_index));
    sentences->push_front(chosen);
    return;
  }
  double front_penalty = 0;
  if (q.CheapSpatial(front->code(), front->end() - q.start, &front_penalty)) return;
  const double front_score = ModelScore(ngram, ngram_sentence_start, poet, *front);
  size_t best_index = 0;
  double best = -std::numeric_limits<double>::infinity();
  for (size_t i = 1; i < sentences->size(); ++i) {
    const auto& sentence = (*sentences)[i];
    if (!sentence) continue;
    double penalty = 0;
    if (!q.CheapSpatial(sentence->code(), sentence->end() - q.start, &penalty)) continue;
    const double value = ModelScore(ngram, ngram_sentence_start, poet, *sentence) + penalty;
    if (value > best) { best = value; best_index = i; }
  }
  if (best_index == 0 || !(best > front_score + std::log(1.2))) return;
  auto chosen = (*sentences)[best_index];
  sentences->erase(sentences->begin() + static_cast<std::ptrdiff_t>(best_index));
  sentences->push_front(chosen);
}

}  // namespace rime
