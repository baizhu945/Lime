// Nine-key joint decoder (t9/joint_decoder): dictionary and user words of every span in one beam,
// scored by the word n-gram model (or dictionary weight + grammar without it). Formerly
// cmake/T9JointDecoder.inc, compiled inside Rime's script translator.
#include "t9_joint_decoder.h"

#include <algorithm>
#include <cmath>
#include <deque>
#include <tuple>
#include <unordered_map>

#include <boost/algorithm/string/join.hpp>
#include <gram_db.h>
#include <gram_encoding.h>
#include <rime/dict/dictionary.h>
#include <rime/dict/prism.h>
#include <rime/dict/user_dictionary.h>
#include <rime/gear/grammar.h>
#include <rime/gear/poet.h>
#include <rime/gear/script_translator.h>
#include <rime/gear/translator_commons.h>
#include <t9_candidate_pool.h>
#include <t9_decode_cache.h>
#include <t9_lookup_trace.h>
#include <t9_ngram_model.h>
#include <t9_sentence_scorer.h>
#include <t9_suppression.h>
#include <utf8.h>

#include "t9_script_options.h"

namespace rime {

// System graphs and versioned, detached decoded results have separate caches.
namespace {
struct T9Line {
  an<T9Line> previous;
  an<DictEntry> entry;
  size_t end = 0;
  double score = 0;
  double prior = -1;
  double matched_length = 0;
  string text;
  Code code;
  int32_t lm_id = ::T9NgramModel::kUnseen;    // last model word, history of the next word
  int32_t lm_prev = ::T9NgramModel::kUnseen;  // the model word before it
};

static double T9WholeTextPrior(GramDb* db, const string& text) {
  if (!db || utf8::distance(text.begin(), text.end()) > grammar::kMaxEncodedUnicode) return -1;
  T9_WORK_COUNT(whole_text_priors); T9_WORK_COUNT(gram_lookups);
  const auto encoded = grammar::encode(text);
  GramDb::Match matches[GramDb::kMaxResults];
  const auto size = db->Lookup("", encoded, matches);
  for (int i = 0; i < size; ++i)
    if (matches[i].length == encoded.size()) return matches[i].value;
  return -1;
}


template <class QueryResult>
static void T9Collect(map<int, DictEntryList>& destination,
                      const an<QueryResult>& result, size_t budget) {
  if (!result) return;
  for (auto& end : *result) {
    auto& entries = destination[end.first];
    // Bound each source independently, before the source-neutral merge.
    for (size_t n = 0; n < budget && !end.second.exhausted(); ++n) {
      entries.push_back(end.second.Peek());
      end.second.Next();
    }
  }
}
}  // namespace

struct T9Path { Sentence sentence; double prior; double coverage; };
struct T9JointFrame {
  EdgeMap edges;
  size_t total = 0, bytes = 0;
  WordGraph merged;
  map<int, size_t> system_reach, user_reach, omitted_end;
  map<size_t, vector<an<T9Line>>> states;
};
struct T9JointCache {
  T9DecodeCache<vector<T9Path>> results;
  deque<T9JointFrame> frames;
  size_t frame_bytes = 0;
  uint32_t dict_checksum = 0, schema_checksum = 0;
  uint64_t user_revision = 0, user_tick = 0;
  const UserDictionary* user_dict = nullptr;
  string preceding;
  hash_set<string> blacklist;
  using Options = std::tuple<size_t, size_t, size_t, size_t, size_t,
                            double, double, double, string, bool, bool, bool,
                            const void*, double, bool, double, bool, double>;
  Options options;
};

namespace {
size_t T9EntryBytes(const DictEntry& entry) {
  return sizeof(DictEntry) + entry.text.capacity() + entry.comment.capacity() +
         entry.preedit.capacity() + entry.custom_code.capacity() +
         entry.code.capacity() * sizeof(SyllableId);
}
size_t T9SentenceBytes(const Sentence& sentence) {
  // Outer Sentence storage is counted by the vector. Count each component
  // object once; double-counting its slab evicts short rollback prefixes early.
  size_t bytes = T9EntryBytes(sentence.entry()) + 32 +
                 sentence.word_lengths().capacity() * sizeof(size_t) +
                 (sentence.components().capacity() - sentence.components().size()) * sizeof(DictEntry);
  for (const auto& entry : sentence.components()) bytes += T9EntryBytes(entry);
  return bytes;
}
}  // namespace

bool T9DecodeJoint(const T9ScriptQuery& q, const T9ScriptOptions& o, bool (*is_numeric_input)(const string&),
                   Dictionary* dict, UserDictionary* user_dict, deque<an<Sentence>>* sentences) {
  const auto& syllables = q.syllables;
  const size_t total = syllables.interpreted_length;
  if (!total) return false;
  T9_WORK_COUNT(joint_decodes);
  const auto& input = q.input;
  const bool single_t9_key = input.size() == 1 &&
                             input.front() >= '2' && input.front() <= '9';
  // A first key is still ambiguous between several initials and a complete
  // vowel. Soften only the extra sentence-level abbreviation cost; dictionary
  // credibility and the full-spelling candidate pool keep their normal role.
  const double spelling_penalty = o.t9_spelling_penalty_ * (single_t9_key ? 0.75 : 1.0);
  const string preceding = q.translator->GetPrecedingText(q.start);
  // Decoded results also depend on the committed text before the composition (n-gram history).
  // With neighbour correction the syllable graph also depends on the measured key geometry.
  const string cache_context = preceding + '\x1f' + o.t9_left_context_ +
      (q.enable_correction ? '\x1f' + o.t9_neighbors_ : string());
  T9JointCache* cache = nullptr;
  // Rebuild native objects with this query's syllabifier/spans. Neither Menu
  // indices nor mutable Phrase entries from a previous query are reused.
  auto restore = [&](const vector<T9Path>& paths) {
    vector<string> texts;
    for (const auto& path : paths) texts.push_back(preceding + path.sentence.text());
    vector<double> neural;
    const bool ready = T9RequestSentenceScores(o.t9_sentence_model_, texts, &neural);
    const bool apply = T9RefiningSentences() && ready && neural.size() == paths.size() &&
        std::all_of(neural.begin(), neural.end(), [](double n) { return std::isfinite(n); });
    vector<std::pair<size_t, double>> order;
    for (size_t i = 0; i < paths.size(); ++i)
      order.emplace_back(i, paths[i].sentence.entry().weight + (apply ? o.t9_sentence_weight_ * neural[i] : 0));
    std::sort(order.begin(), order.end(), [&](const auto& a, const auto& b) {
      if (a.second != b.second) return a.second > b.second;
      const auto& x = paths[a.first]; const auto& y = paths[b.first];
      if (x.prior != y.prior) return x.prior > y.prior;
      if (x.sentence.text() != y.sentence.text()) return x.sentence.text() < y.sentence.text();
      return x.sentence.code() < y.sentence.code();
    });
    set<string> displayed;
#ifndef T9_ALGO_ONLY_BUILD
    // Sentences the user deleted (long press) are skipped here, so the next best one takes the slot.
    const auto suppressed = GetT9SuppressionStore();
#endif
    for (const auto& item : order) {
      // t9/display_sentences: whole-input sentences shown before the dictionary words
      // (1 = the best sentence, then shorter words, as stock Rime; 0 = all decoded).
      if (o.t9_display_sentences_ && sentences->size() >= o.t9_display_sentences_) break;
      // Further whole-input sentences only from t9/more_sentences_min_keys keys on: on short input they
      // are weak alternatives that push dictionary words (的, 觉得) out of the first four places.
      if (!sentences->empty() && total < o.t9_more_sentences_min_keys_) break;
      const auto& path = paths[item.first];
#ifndef T9_ALGO_ONLY_BUILD
      if (suppressed && suppressed->Contains("t9_pinyin", path.sentence.text())) continue;
#endif
      if (!displayed.insert(path.sentence.text()).second) continue;
      auto sentence = New<Sentence>(path.sentence);
      sentence->Offset(q.start);
      sentence->set_syllabifier(q.syllabifier);
      sentence->set_weight(item.second);
      sentence->set_quality(path.coverage + 0.1 * (0.5 + std::atan(item.second / 10.0) / 3.141592653589793) + q.translator->initial_quality());
      sentences->push_back(std::move(sentence));
    }
  };
  const string cache_key = q.input + "#" + std::to_string(q.start) +
                           "#" + std::to_string(q.end_of_input);
  if (o.t9_decoded_cache_ && (!q.enable_correction || o.t9_cache_with_correction_) &&
      !q.translator->strict_spelling()) {
    auto& owned = q.translator->t9_joint_cache;
    if (!owned) owned = New<T9JointCache>();
    cache = owned.get();
    const auto revision = T9UserDictionaryRevision().load(std::memory_order_relaxed);
    const auto tick = user_dict ? user_dict->tick() : 0;
    auto* prism = dict->prism().get();
    T9JointCache::Options options{o.t9_source_budget_, o.t9_pool_budget_, o.t9_dictionary_beam_,
        o.t9_sentence_beam_, o.t9_result_budget_, o.t9_spelling_penalty_, o.t9_word_penalty_,
        o.t9_sentence_weight_, o.t9_sentence_model_, q.translator->enable_completion(),
        q.translator->enable_word_completion(), q.translator->complete_short_prefix(),
        o.t9_ngram_.get(), o.t9_ngram_user_weight_, o.t9_ngram_sentence_start_, o.t9_ngram_eos_weight_,
        o.t9_ngram_merge_segmentations_, o.t9_ngram_dictionary_prior_};
    if (cache->dict_checksum != prism->dict_file_checksum() ||
        cache->schema_checksum != prism->schema_file_checksum() ||
        cache->user_revision != revision || cache->user_tick != tick ||
        cache->user_dict != user_dict || cache->preceding != cache_context ||
        cache->blacklist != q.translator->blacklist() || cache->options != options) {
      cache->results.Clear();
      cache->frames.clear(); cache->frame_bytes = 0;
      cache->dict_checksum = prism->dict_file_checksum();
      cache->schema_checksum = prism->schema_file_checksum();
      cache->user_revision = revision;
      cache->user_tick = tick;
      cache->user_dict = user_dict;
      cache->preceding = cache_context;
      cache->blacklist = q.translator->blacklist();
      cache->options = std::move(options);
    }
    if (const auto* paths = cache->results.Find(cache_key)) {
      T9_WORK_COUNT(joint_cache_hits);
      restore(*paths);
      return !sentences->empty();
    }
  }
  // Compare actual post-pruning graph rows, not just an assumed unchanged
  // digit prefix. A new key can change abbreviation pruning much earlier.
  auto sameRow = [](const EndVertexMap& a, const EndVertexMap& b) {
    if (a.size() != b.size()) return false;
    auto x = a.begin(); auto y = b.begin();
    for (; x != a.end(); ++x, ++y) {
      if (x->first != y->first || x->second.size() != y->second.size()) return false;
      auto u = x->second.begin(); auto v = y->second.begin();
      for (; u != x->second.end(); ++u, ++v) {
        const auto& p = u->second; const auto& q = v->second;
        if (u->first != v->first || p.type != q.type || p.end_pos != q.end_pos ||
            p.credibility != q.credibility || p.is_correction != q.is_correction ||
            p.tips != q.tips || p.ambiguous_source_positions != q.ambiguous_source_positions) return false;
      }
    }
    return true;
  };
  // Pruning may alternate between two valid graph shapes as a syllable
  // completes. Keep bounded recent shapes instead of losing the stable prefix
  // every other key. Selection uses exact graph rows, never a digit heuristic.
  const T9JointFrame* previous = nullptr;
  size_t best_prefix = 0;
  if (cache) for (const auto& frame : cache->frames) {
    if (frame.total >= total) continue;
    size_t prefix = frame.total;
    for (const auto& row : syllables.edges) {
      auto old = frame.edges.find(row.first);
      if (old == frame.edges.end() || !sameRow(row.second, old->second)) { prefix = std::min(prefix, row.first); break; }
    }
    for (const auto& old : frame.edges) if (!syllables.edges.count(old.first)) { prefix = std::min(prefix, old.first); break; }
    if (!previous || prefix > best_prefix) { previous = &frame; best_prefix = prefix; }
  }
  set<size_t> dirty;
  if (previous) {
    T9_WORK_COUNT(joint_frame_reuse);
    for (const auto& row : syllables.edges) {
      auto old = previous->edges.find(row.first);
      if (old == previous->edges.end() || !sameRow(row.second, old->second)) dirty.insert(row.first);
    }
    for (const auto& old : previous->edges)
      if (!syllables.edges.count(old.first)) dirty.insert(old.first);
    if (previous->total != total) dirty.insert(std::min(previous->total, total));
  }
  auto reusable = [&](int origin, const map<int, size_t>& reach) {
    const auto old = reach.find(origin);
    const auto changed = dirty.lower_bound(origin);
    return old != reach.end() && (changed == dirty.end() || *changed > old->second);
  };
  const size_t stable = previous && previous->total > 0
      ? std::min(previous->total - 1, dirty.empty() ? previous->total - 1 : *dirty.begin()) : 0;
  WordGraph graph, system;
  map<int, size_t> system_reach, user_reach, omitted_end;
  vector<size_t> starts;
  for (const auto& row : syllables.edges) {
    const bool needs_evicted_edges = previous && previous->omitted_end.count(row.first) &&
        previous->omitted_end.at(row.first) > stable;
    if (previous && !needs_evicted_edges && reusable(row.first, previous->system_reach) && reusable(row.first, previous->user_reach)) {
      if (previous->omitted_end.count(row.first)) omitted_end[row.first] = previous->omitted_end.at(row.first);
      const auto found = previous->merged.find(row.first);
      if (found != previous->merged.end()) graph[row.first] = found->second;
      system_reach[row.first] = previous->system_reach.at(row.first);
      user_reach[row.first] = previous->user_reach.at(row.first);
      T9_WORK_COUNT(joint_origins_reused);
    } else { starts.push_back(row.first); system_reach[row.first] = row.first; }
  }
  T9_WORK_ADD(joint_origins_queried, starts.size());
  {
    T9LookupScope scope(&system_reach);
    auto buckets = dict->LookupAll(syllables, starts, &q.translator->blacklist(), false, 0, o.t9_dictionary_beam_,
                                   is_numeric_input(input));
    for (auto& start : buckets) T9Collect(system[start.first], start.second, o.t9_source_budget_);
  }
  for (const auto start : starts) {
    map<int, DictEntryList> user;
    user_reach[start] = start;
    if (user_dict) {
      T9LookupScope scope(&user_reach); T9OriginScope origin(start);
      T9Collect(user, user_dict->Lookup(syllables, start, 0), o.t9_source_budget_);
    }
    auto merged = std::move(system[start]);
    for (const auto& end : merged) T9_WORK_ADD(system_entries, end.second.size());
    for (const auto& end : user) {
      T9_WORK_ADD(user_entries_collected, end.second.size());
      auto& entries = merged[end.first];
      entries.insert(entries.end(), end.second.begin(), end.second.end());
    }
    if (o.t9_prefer_user_entries_) {
      // A learned word and the same system word share text and code; the merge keeps the higher
      // dictionary weight and would drop the user entry, so the user layer never applied.
      for (auto& end : merged) {
        auto& list = end.second;
        // Copy the learned identities first: remove_if moves elements while it runs, so the
        // predicate must not read the list itself (it saw moved-from null entries and crashed).
        vector<std::pair<string, Code>> learned;
        for (const auto& entry : list)
          if (entry && entry->commit_count > 0) learned.emplace_back(entry->text, entry->code);
        if (learned.empty()) continue;
        list.erase(std::remove_if(list.begin(), list.end(), [&](const an<DictEntry>& entry) {
          return entry && entry->commit_count <= 0 &&
                 std::any_of(learned.begin(), learned.end(), [&](const auto& other) {
                   return other.first == entry->text && other.second == entry->code;
                 });
        }), list.end());
      }
    }
    for (auto& end : merged) {
      T9_WORK_COUNT(merge_calls); T9_WORK_ADD(merge_entries_in, end.second.size());
      DictEntryList learned;
      if (o.t9_prefer_user_entries_)
        for (const auto& entry : end.second) if (entry->commit_count > 0) learned.push_back(entry);
      T9MergeEntries(end.second, o.t9_pool_budget_);
      // Learned words survive the pool budget: their user-dictionary weight is on another scale.
      for (const auto& entry : learned)
        if (std::find(end.second.begin(), end.second.end(), entry) == end.second.end()) end.second.push_back(entry);
      T9_WORK_ADD(merge_entries_out, end.second.size());
      graph[start][end.first] = std::move(end.second);
    }
  }
  // Whole-input dictionary phrases bypass the prefix beam (26-key initials: the beam ranks prefixes by
  // single-character weight and can drop 化妆品 for hzp before the word model sees it).
  if (o.t9_whole_input_phrases_ > 0) {
    auto whole = dict->Lookup(syllables, 0, &q.translator->blacklist(), false, 0.0, 0, false, false);
    if (whole) {
      auto bucket = whole->find(static_cast<int>(total));
      if (bucket != whole->end()) {
        auto& list = graph[0][static_cast<int>(total)];
        for (size_t n = 0; n < o.t9_whole_input_phrases_ && !bucket->second.exhausted(); ++n) {
          auto entry = bucket->second.Peek();
          if (!entry->IsPredictiveMatch() &&
              std::none_of(list.begin(), list.end(), [&](const an<DictEntry>& x) { return x->text == entry->text; }))
            list.push_back(entry);
          bucket->second.Next();
        }
      }
    }
  }
  map<size_t, vector<an<T9Line>>> states;
  if (previous) for (const auto& state : previous->states) if (state.first <= stable) states.insert(state);
  if (states.empty()) {
    auto root = New<T9Line>();
    // Committed text before the composition ("\x1e" + text from the app; absent = unknown):
    // its last two model words are the first word's history. Nothing or a non-Han character
    // right before the composition is a sentence start, as for the training Han runs.
    if (o.t9_ngram_ && !o.t9_left_context_.empty() && o.t9_left_context_[0] == '\x1e') {
      const string text = o.t9_left_context_.substr(1);
      auto run = text.end();
      size_t chars = 0;
      bool bounded = false;
      while (chars < 16) {
        if (run == text.begin()) break;
        auto at = run;
        const uint32_t c = utf8::unchecked::prior(at);
        if (!((c >= 0x3400 && c <= 0x4DBF) || (c >= 0x4E00 && c <= 0x9FFF) || c == 0x3007)) { bounded = true; break; }
        run = at; ++chars;
      }
      if (!chars) {
        root->lm_id = o.t9_ngram_->Bos();
      } else {
        vector<int32_t> parts;
        o.t9_ngram_->Decompose(string(run, text.end()), &parts);
        root->lm_id = parts.back();
        root->lm_prev = parts.size() >= 2 ? parts[parts.size() - 2]
                                          : bounded ? o.t9_ngram_->Bos() : ::T9NgramModel::kUnseen;
        if (root->lm_id == ::T9NgramModel::kUnseen) root->lm_prev = ::T9NgramModel::kUnseen;
      }
    }
    states[0].push_back(root);
  }
  // Model word of each entry; an entry the corpus never contained is kept as its parts,
  // so a long unseen entry never wins by paying a single unseen-word cost.
  struct T9LmEntry { int32_t id; vector<int32_t> parts; double reading = 0; };
  std::unordered_map<const DictEntry*, T9LmEntry> lm_entries;
  auto lm_entry_of = [&](const an<DictEntry>& entry) -> const T9LmEntry& {
    const auto found = lm_entries.find(entry.get());
    if (found != lm_entries.end()) return found->second;
    T9LmEntry lm{o.t9_ngram_->Id(entry->text), {}};
    if (lm.id == ::T9NgramModel::kUnseen) o.t9_ngram_->Decompose(entry->text, &lm.parts);
    if (o.t9_readings_) {
      const auto readings = o.t9_readings_->find(entry->text);
      vector<string> spelling;
      if (readings != o.t9_readings_->end() && dict->Decode(entry->code, &spelling)) {
        const string code = boost::algorithm::join(spelling, " ");
        for (const auto& reading : readings->second)
          if (reading.first == code) lm.reading = reading.second;
      }
    }
    return lm_entries.emplace(entry.get(), std::move(lm)).first->second;
  };
  auto better = [](const an<T9Line>& a, const an<T9Line>& b) {
    if (a->score != b->score) return a->score > b->score;
    if (a->prior != b->prior) return a->prior > b->prior;
    if (a->text != b->text) return a->text < b->text;
    return a->code < b->code;
  };
  for (const auto& start : graph) {
    const auto found = states.find(start.first);
    if (found == states.end()) continue;
    for (const auto& end : start.second) {
      if (end.first <= start.first || static_cast<size_t>(end.first) > total || static_cast<size_t>(end.first) <= stable) continue;
      auto& target = states[end.first];
      for (const auto& previous : found->second) {
        for (const auto& entry : end.second) {
          if (entry->IsPredictiveMatch()) continue;
          T9_WORK_COUNT(beam_evaluations);
          double score = previous->score;
          int32_t lm_id = ::T9NgramModel::kUnseen, lm_prev = ::T9NgramModel::kUnseen;
          if (o.t9_ngram_) {
            // One weight scale: ln P(word | previous words) from one set of corpus counts
            // replaces the dictionary weight, the grammar and the per-word penalty.
            T9_WORK_COUNT(ngram_lookups);
            // The root carries a history only when the committed text before it is known.
            const bool first = !previous->entry && previous->lm_id == ::T9NgramModel::kUnseen;
            const auto& lm = lm_entry_of(entry);
            double lp;
            if (!lm.parts.empty()) {
              lp = o.t9_ngram_->Chain(previous->lm_prev, previous->lm_id, first, o.t9_ngram_sentence_start_,
                                    lm.parts, &lm_prev, &lm_id);
              if (o.t9_ngram_dictionary_prior_ > 0 && std::isfinite(entry->weight)) {
                // A dictionary (or cell-pack) word the corpus never contained: its dictionary
                // frequency is the only evidence for the whole word, so mix it in as a unigram
                // probability of the entry, like the user layer; the chain of parts keeps 1-μ.
                lp = std::log((1 - o.t9_ngram_dictionary_prior_) * std::exp(lp) +
                              o.t9_ngram_dictionary_prior_ * std::exp(entry->weight));
              }
            } else if (first) {
              lm_id = lm.id;
              lm_prev = o.t9_ngram_sentence_start_ ? o.t9_ngram_->Bos() : ::T9NgramModel::kUnseen;
              lp = o.t9_ngram_->Start(lm.id, o.t9_ngram_sentence_start_);
            } else {
              lm_id = lm.id;
              lm_prev = previous->lm_id;
              lp = o.t9_ngram_->LogProb3(previous->lm_prev, previous->lm_id, lm.id);
            }
            if (entry->commit_count > 0 && o.t9_ngram_user_weight_ > 0) {
              // User layer: P = (1-λ)·P(base) + λ·P(user), where P(user) is the user dictionary's own
              // estimate (commit share plus a recency term that fades over a few hundred commits).
              // The base model is read-only and keeps weight 1-λ, so learning never forgets grammar.
              const double user = std::min(1.0, std::exp(entry->weight));
              lp = std::log((1 - o.t9_ngram_user_weight_) * std::exp(lp) + o.t9_ngram_user_weight_ * user);
            }
            // P(reading | word): the user's own entries were learned with this reading.
            if (entry->commit_count <= 0) score += lm.reading;
            score += lp;
          } else {
            score += Grammar::Evaluate(
                previous->text.empty() ? preceding : previous->text,
                entry->text, entry->weight, static_cast<size_t>(end.first) == total, o.t9_grammar_.get());
            // Discourage over-segmentation.
            score -= o.t9_word_penalty_;
          }
          // Apply the same score before allocating a path. Strictly worse
          // paths cannot enter a full beam; equal scores still reach tie-breaks.
          // Keyboard-neighbour corrections (26-key touch typing): the word model scores a corrected
          // word as if it had been typed, so charge it t9/correction_penalty (nine-key has no corrector).
          if (q.enable_correction && q.IsCorrection(entry->code, end.first, start.first))
            score += o.t9_correction_penalty_;
          // Penalize abbreviated/fuzzy spans.
          score -= spelling_penalty * std::max(0.0,
              static_cast<double>(end.first - start.first) - entry->quality_len);
          if (target.size() >= o.t9_sentence_beam_ && score < target.back()->score) { T9_WORK_COUNT(beam_rejected); continue; }
          auto line = New<T9Line>();
          line->previous = previous;
          line->entry = entry;
          line->matched_length = previous->matched_length + entry->quality_len;
          line->end = end.first;
          line->lm_id = lm_id;
          line->lm_prev = lm_prev;
          line->text = previous->text + entry->text;
          if (line->end == total) line->prior = T9WholeTextPrior(o.t9_gram_db_, line->text);
          line->code = previous->code;
          line->code.insert(line->code.end(), entry->code.begin(), entry->code.end());
          line->score = score;
          auto duplicate = std::find_if(target.begin(), target.end(), [&](const auto& other) {
            return line->text == other->text && line->code == other->code;
          });
          if (duplicate != target.end()) {
            if (o.t9_ngram_ && o.t9_ngram_merge_segmentations_) {
              // The same text and spelling through other words: P(text) sums its segmentations.
              // The better segmentation keeps its words as the history of what follows; lines
              // ending here have not been extended yet.
              const double a = line->score, b = (*duplicate)->score;
              if (!better(line, *duplicate)) line = *duplicate;
              target.erase(duplicate);
              line->score = std::max(a, b) + std::log1p(std::exp(-std::fabs(a - b)));
            } else {
              if (!better(line, *duplicate)) continue;
              target.erase(duplicate);
            }
          }
          const auto pos = std::lower_bound(target.begin(), target.end(), line, better);
          target.insert(pos, line);
          T9_WORK_COUNT(beam_insertions);
          if (target.size() > o.t9_sentence_beam_) target.pop_back();
        }
      }
    }
  }
  if (cache) {
    // Keep the search frontier, not every dictionary bucket behind it. The
    // lattice already owns the winning prefixes. If future pruning moves the
    // stable boundary back past any evicted bucket, that origin is re-queried.
    WordGraph frontier = graph;
    const size_t cutoff = total > 12 ? total - 12 : 0;
    for (auto& start : frontier) for (auto it = start.second.begin(); it != start.second.end();) {
      if (static_cast<size_t>(it->first) <= cutoff) {
        omitted_end[start.first] = std::max(omitted_end[start.first], static_cast<size_t>(it->first));
        it = start.second.erase(it);
      } else ++it;
    }
    // Conservative accounting includes containers and copied line text/codes.
    size_t bytes = 0;
    for (const auto& start : frontier) {
      bytes += 128;
      for (const auto& end : start.second) {
        bytes += 128 + end.second.size() * 32;
        for (const auto& entry : end.second) bytes += T9EntryBytes(*entry);
      }
    }
    for (const auto& state : states) for (const auto& line : state.second)
      bytes += sizeof(T9Line) + 128 + line->text.capacity() + line->code.capacity() * sizeof(SyllableId);
    for (const auto& row : syllables.edges) for (const auto& end : row.second) bytes += 128 + end.second.size() * 256;
    // Frames are kept for any input length; the byte limits below bound the memory. A 128-key cap made
    // every key past it (about 45 syllables) decode the whole input again: 150 syllables took 90 ms a key.
    if (bytes <= 16 * 1024 * 1024) {
      // Retain distinct prefix shapes, rather than filling the budget with
      // near-identical consecutive suffixes and evicting a recurring shape.
      // Discarding a frame only affects reuse: every surviving frame still
      // goes through the exact dependency checks above.
      for (auto it = cache->frames.begin(); it != cache->frames.end();) {
        const size_t prefix = it->total > 12 ? it->total - 12 : 0;
        bool same_prefix = prefix > 0;
        for (const auto& row : it->edges) {
          if (row.first >= prefix) break;
          const auto current = syllables.edges.find(row.first);
          if (current == syllables.edges.end() || !sameRow(row.second, current->second)) { same_prefix = false; break; }
        }
        if (same_prefix) for (const auto& row : syllables.edges) {
          if (row.first >= prefix) break;
          if (!it->edges.count(row.first)) { same_prefix = false; break; }
        }
        if (same_prefix) { cache->frame_bytes -= it->bytes; it = cache->frames.erase(it); }
        else ++it;
      }
      while (!cache->frames.empty() && (cache->frames.size() >= 16 || cache->frame_bytes + bytes > 32 * 1024 * 1024)) {
        cache->frame_bytes -= cache->frames.front().bytes; cache->frames.pop_front();
      }
      cache->frames.push_back({syllables.edges, total, bytes, std::move(frontier),
          std::move(system_reach), std::move(user_reach), std::move(omitted_end), states});
      cache->frame_bytes += bytes;
    }
  }
  // Explicit final beam: all survivors receive the same bounded second pass.
  // Reorder the actual native Sentence objects before filters and Menu indexing.
  // Sentence end (n-gram </s>, weighted by t9/ngram_eos_weight): how naturally a finished
  // path could end here. It only orders the finished paths; states kept for later keys
  // are unchanged, since the composition may continue.
  vector<std::pair<double, an<T9Line>>> final;
  for (const auto& line : states[total]) {
    double rank = line->score;
    if (o.t9_ngram_ && o.t9_ngram_eos_weight_ > 0)
      rank += o.t9_ngram_eos_weight_ * o.t9_ngram_->LogProb3(line->lm_prev, line->lm_id, o.t9_ngram_->Eos());
    final.emplace_back(rank, line);
  }
  std::stable_sort(final.begin(), final.end(), [](const auto& a, const auto& b) { return a.first > b.first; });
  // Compare exact dictionary words with the winning pronunciation using the same
  // dictionary interpolation already granted to OOV words. Do not let that
  // unigram evidence override a different pronunciation or committed context.
  if (o.t9_ngram_ && o.t9_ngram_dictionary_prior_ > 0 && is_numeric_input(input) &&
      preceding.empty() && (o.t9_left_context_.empty() || o.t9_left_context_ == "\x1e") && !final.empty()) {
    const Code best_code = final.front().second->code;
    for (auto& item : final) {
      const auto& line = item.second;
      if (!line->entry || !line->previous || line->previous->entry || line->code != best_code ||
          line->entry->commit_count > 0 || line->matched_length < total ||
          !std::isfinite(line->entry->weight)) continue;
      const auto& lm = lm_entry_of(line->entry);
      if (!lm.parts.empty()) continue;  // already smoothed during decoding
      const double reading = lm.reading;
      const double mu = o.t9_ngram_dictionary_prior_;
      item.first = reading + std::log((1 - mu) * std::exp(item.first - reading) +
                                      mu * std::exp(line->entry->weight));
    }
    std::stable_sort(final.begin(), final.end(), [](const auto& a, const auto& b) { return a.first > b.first; });
  }
  if (final.size() > o.t9_result_budget_) final.resize(o.t9_result_budget_);
  vector<T9Path> paths;
  double coverage = 0;
  for (const auto& ranked : final)
    coverage = std::max(coverage, std::min(1.0, ranked.second->matched_length / total));
  for (const auto& [rank, line] : final) {
    auto sentence = New<Sentence>(q.translator->language());
    vector<an<T9Line>> components;
    for (auto cursor = line; cursor && cursor->entry; cursor = cursor->previous)
      components.push_back(cursor);
    for (auto it = components.rbegin(); it != components.rend(); ++it)
      sentence->Extend(*(*it)->entry, (*it)->end, (*it)->score);
    sentence->set_weight(rank);
    // All hypotheses in this translation share a match-coverage baseline,
    // preserving monotonic scores. Exact English can outrank an all-abbreviated
    // Chinese stream (e.g. two keys), without a forced language/source slot.
    sentence->set_quality(coverage + 0.1 *
                           (0.5 + std::atan(rank / 10.0) / 3.141592653589793));
    paths.push_back({*sentence, line->prior, coverage});
    if (paths.size() >= o.t9_result_budget_) break;
  }
  restore(paths);
  if (cache) {
    size_t bytes = paths.capacity() * sizeof(T9Path);
    for (const auto& path : paths) bytes += T9SentenceBytes(path.sentence);
    cache->results.Put(cache_key, std::move(paths), bytes);
  }
  return !sentences->empty();
}

}  // namespace rime
