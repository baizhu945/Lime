#ifndef CYIME_PINYIN_SYLLABLE_SELECTION_H_
#define CYIME_PINYIN_SYLLABLE_SELECTION_H_

#include <algorithm>
#include <cstdint>
#include <iomanip>
#include <memory>
#include <map>
#include <sstream>
#include <set>
#include <string>
#include <string_view>
#include <vector>
#include <rime/algo/syllabifier.h>
#include <rime/candidate.h>
#include <rime/context.h>
#include <rime/dict/dictionary.h>
#include <rime/dict/corrector.h>
#include <rime/gear/translator_commons.h>
#include <rime/menu.h>
#include <rime/schema.h>
#include <rime/ticket.h>

namespace cyime {
namespace pinyin_selection {

// A session-local constraint, never a spelling rewrite. The underscore makes
// Rime discard it at the schema boundary. The engine update hook preserves
// an unchanged selected prefix across suffix edits, while clearing a damaged
// prefix immediately so that clearing/retyping cannot revive an old selection.
inline constexpr const char* kProperty = "_cyime_pinyin_syllable_lock_v1";
inline constexpr const char* kAppliedProperty = "_cyime_pinyin_syllable_applied_v1";

struct LockedSpan {
  size_t start = 0;
  size_t end = 0;
  rime::SyllableId syllable = -1;
};

struct Lock {
  std::string schema;
  std::string input;
  std::string confirmed_text;
  uint64_t epoch = 0;
  uint32_t dictionary_checksum = 0;
  uint32_t schema_checksum = 0;
  size_t confirmed = 0;
  size_t start = 0;
  size_t end = 0;
  rime::SyllableId syllable = -1;
  std::vector<LockedSpan> following;
};

struct Option {
  rime::SyllableId syllable = -1;
  std::string pinyin;
  size_t start = 0;
  size_t end = 0;
  bool selected = false;
  double credibility = 0;
};

inline std::string ConfirmedText(const rime::Context& context) {
  const auto confirmed = context.composition().GetConfirmedPosition();
  std::string text;
  for (const auto& segment : context.composition()) {
    if (segment.end > confirmed || segment.status < rime::Segment::kSelected)
      break;
    if (auto candidate = segment.GetSelectedCandidate()) text += candidate->text();
  }
  return text;
}

inline std::string Encode(const Lock& lock) {
  std::ostringstream out;
  out << 1 << ' ' << std::quoted(lock.schema) << ' ' << std::quoted(lock.input)
      << ' ' << std::quoted(lock.confirmed_text) << ' ' << lock.epoch << ' '
      << lock.dictionary_checksum << ' ' << lock.schema_checksum << ' '
      << lock.confirmed << ' ' << lock.start << ' ' << lock.end << ' '
      << lock.syllable;
  for (const auto& span : lock.following)
    out << ' ' << span.start << ' ' << span.end << ' ' << span.syllable;
  return out.str();
}

inline bool Decode(const std::string& value, Lock* lock) {
  if (value.empty() || !lock) return false;
  std::istringstream in(value);
  unsigned version = 0;
  Lock parsed;
  if (!(in >> version >> std::quoted(parsed.schema) >> std::quoted(parsed.input)
        >> std::quoted(parsed.confirmed_text) >> parsed.epoch
        >> parsed.dictionary_checksum >> parsed.schema_checksum
        >> parsed.confirmed >> parsed.start >> parsed.end >> parsed.syllable) ||
      version != 1 || parsed.syllable < 0 || parsed.start >= parsed.end ||
      parsed.end > parsed.input.size() || parsed.confirmed != parsed.start) {
    return false;
  }
  size_t last_end = parsed.end;
  while (in >> std::ws && !in.eof()) {
    LockedSpan span;
    if (!(in >> span.start >> span.end >> span.syllable) ||
        span.start != last_end || span.end <= span.start ||
        span.end > parsed.input.size() || span.syllable < 0) return false;
    parsed.following.push_back(span);
    last_end = span.end;
  }
  *lock = std::move(parsed);
  return true;
}

inline void ClearLock(rime::Context* context) {
  if (!context) return;
  if (!context->get_property(kProperty).empty()) context->set_property(kProperty, "");
  if (!context->get_property(kAppliedProperty).empty())
    context->set_property(kAppliedProperty, "");
}

// Runs before composition. Only exact, one-shot Context edit evidence can
// preserve spans across an input change; an unknown edit clears the chain.
inline void BeforeContextUpdate(rime::Context* context, const rime::Schema* schema) {
  if (!context) return;
  const auto value = context->get_property(kProperty);
  Lock lock;
  const bool decoded = Decode(value, &lock);
  rime::Context::InputEdit edit;
  const bool trusted = context->ConsumeInputEdit(lock.input, lock.epoch, &edit);
  if (value.empty()) return;
  if (!schema || !decoded || context->get_option("ascii_mode") ||
      schema->schema_id() != lock.schema || context->input().empty() ||
      context->composition().GetConfirmedPosition() != lock.confirmed ||
      ConfirmedText(*context) != lock.confirmed_text) {
    ClearLock(context);
    return;
  }
  if (!trusted && (lock.input != context->input() ||
                   lock.epoch != context->composition_epoch())) {
    // set_input() has no InputEdit evidence. Preserve the base span when the
    // locked prefix is byte-identical; a suffix edit after it must not unlock.
    // Multi-span chains stay conservative because a later edit can shift them.
    const auto& next = context->input();
    const bool prefix_intact = lock.epoch == context->composition_epoch() &&
        next.size() >= lock.end && lock.input.size() >= lock.end &&
        next.compare(0, lock.end, lock.input, 0, lock.end) == 0;
    if (!prefix_intact || !lock.following.empty()) {
      ClearLock(context);
      return;
    }
  }
  if (trusted) {
    if (edit.start < lock.end) {
      ClearLock(context);
      return;
    }
    while (!lock.following.empty() && lock.following.back().end > edit.start)
      lock.following.pop_back();
  }
  if (lock.input != context->input() || lock.epoch != context->composition_epoch()) {
    lock.input = context->input();
    lock.epoch = context->composition_epoch();
    context->set_property(kProperty, Encode(lock));
    if (!context->get_property(kAppliedProperty).empty())
      context->set_property(kAppliedProperty, "");
  }
}

// OnSelect has paths that call Compose directly, or only Forward when the
// entire input was selected. Do not wait for a subsequent update/translation.
inline void BeforeCandidateSelection(rime::Context* context) {
  ClearLock(context);
}

inline bool Matches(const Lock& lock, const rime::Context& context,
                    const rime::Schema& schema, const rime::Prism& prism) {
  return !context.get_option("ascii_mode") && lock.schema == schema.schema_id() &&
         lock.input == context.input() && lock.epoch == context.composition_epoch() &&
         lock.dictionary_checksum == prism.dict_file_checksum() &&
         lock.schema_checksum == prism.schema_file_checksum() &&
         lock.confirmed == context.composition().GetConfirmedPosition() &&
         lock.confirmed_text == ConfirmedText(context) &&
         lock.start == lock.confirmed;
}

inline bool ReadLock(rime::Context* context, const rime::Schema* schema,
                     const rime::Prism& prism, Lock* lock) {
  if (!context || !schema || !lock) return false;
  const auto value = context->get_property(kProperty);
  if (value.empty()) return false;
  // Read-only: a failed match may be caused by an option/epoch change, not by an
  // edit. Clearing here destroyed a valid lock merely because the reading rail
  // refreshed. Cleanup stays in BeforeContextUpdate / BeforeCandidateSelection.
  return Decode(value, lock) && Matches(*lock, *context, *schema, prism);
}

// Rebuild pointer-bearing indices after pruning edges. Copying the old index
// would retain dangling EdgeProperties pointers into erased syllable paths.
inline void RebuildIndices(rime::SyllableGraph* graph) {
  graph->indices.clear();
  for (const auto& start : graph->edges) {
    for (auto end = start.second.rbegin(); end != start.second.rend(); ++end) {
      for (const auto& spelling : end->second) {
        graph->indices[start.first][spelling.first].push_back(&spelling.second);
      }
    }
  }
}

// Pure graph operation, also used by the native regression probe. Span ends
// include any trailing speller delimiters, exactly as Rime's graph does.
inline bool ConstrainGraph(rime::SyllableGraph* graph, size_t start, size_t end,
                           rime::SyllableId syllable) {
  if (!graph || start >= end || end > graph->input_length) return false;
  auto from = graph->edges.find(start);
  if (from == graph->edges.end()) return false;
  auto to = from->second.find(end);
  if (to == from->second.end()) return false;
  auto target = to->second.find(syllable);
  if (target == to->second.end() || target->second.type >= rime::kAbbreviation) return false;
  auto chosen = target->second;
  chosen.ambiguous_source_positions.clear();

  graph->indices.clear();
  for (auto source = graph->edges.begin(); source != graph->edges.end();) {
    for (auto edge = source->second.begin(); edge != source->second.end();) {
      if (edge->first <= start || source->first >= end) {
        ++edge;
      } else if (source->first == start && edge->first == end) {
        edge->second.clear();
        edge->second.emplace(syllable, chosen);
        ++edge;
      } else {
        edge = source->second.erase(edge);
      }
    }
    if (source->second.empty()) source = graph->edges.erase(source);
    else ++source;
  }
  for (auto vertex = graph->vertices.upper_bound(start);
       vertex != graph->vertices.end() && vertex->first < end;) {
    vertex = graph->vertices.erase(vertex);
  }
  graph->vertices[start] = rime::kNormalSpelling;
  graph->vertices[end] = chosen.type;
  RebuildIndices(graph);
  return true;
}

// Called only in the script translator, after its actual graph has been built.
// A valid lock that cannot be represented must yield no translation; silently
// ignoring it would show candidates for a different pronunciation.
inline bool ApplyGraphLock(rime::Context* context, rime::Schema* schema,
                           rime::Prism& prism, size_t segment_start,
                           rime::SyllableGraph* graph, bool* constrained = nullptr) {
  if (constrained) *constrained = false;
  Lock lock;
  if (!ReadLock(context, schema, prism, &lock)) return true;
  // Moving the composition caret into the chosen syllable pauses this lock.
  // Moving it out again restores the same reading without rewriting any code.
  if (context->caret_pos() < lock.end) return true;
  if (segment_start >= lock.end || segment_start + graph->input_length <= lock.start)
    return true;
  if (segment_start <= lock.start &&
      lock.end <= segment_start + graph->input_length &&
      ConstrainGraph(graph, lock.start - segment_start,
                     lock.end - segment_start, lock.syllable)) {
    if (constrained) *constrained = true;
    context->set_property(kAppliedProperty, Encode(lock));
    return true;
  }
  graph->indices.clear();
  graph->edges.clear();
  graph->vertices.clear();
  graph->interpreted_length = 0;
  return false;
}

// Append a separately syllabified part using offsets into the unchanged raw
// input. Edge indices point inside maps, so only rebuild them after all parts
// have been copied into their final owner.
inline void AppendGraph(const rime::SyllableGraph& part, size_t offset,
                        rime::SyllableGraph* graph) {
  for (const auto& vertex : part.vertices)
    graph->vertices[vertex.first + offset] = vertex.second;
  for (const auto& source : part.edges) {
    for (const auto& edge : source.second) {
      auto& destination = graph->edges[source.first + offset][edge.first + offset];
      for (const auto& spelling : edge.second) {
        auto properties = spelling.second;
        properties.end_pos += offset;
        std::set<size_t> ambiguous;
        for (const auto position : properties.ambiguous_source_positions)
          ambiguous.insert(position + offset);
        properties.ambiguous_source_positions = std::move(ambiguous);
        destination.emplace(spelling.first, std::move(properties));
      }
    }
  }
}

// An explicit syllable boundary must participate BEFORE global pruning. For
// example ni+n must remain available after choosing ni, even though the normal
// unselected graph prefers the complete syllable nin and removes that path.
// Reuse the translator's own builder so completion/correction/strict options
// are identical; no synthetic delimiter or full-pinyin replacement is written.
inline size_t BuildGraph(rime::Syllabifier& builder, const std::string& input,
                         rime::Prism& prism, rime::Context* context,
                         rime::Schema* schema, size_t segment_start,
                         rime::SyllableGraph* graph,
                         rime::SyllableGraphCache* cache, bool* constrained) {
  if (constrained) *constrained = false;
  Lock lock;
  const bool locked = ReadLock(context, schema, prism, &lock);
  const size_t last_end = locked && !lock.following.empty() ? lock.following.back().end : lock.end;
  if (!locked || context->caret_pos() < last_end || segment_start >= last_end ||
      segment_start + input.size() <= lock.start) {
    return static_cast<size_t>(builder.BuildSyllableGraph(input, prism, graph, cache));
  }
  if (constrained) *constrained = true;
  const auto fail = [&]() -> size_t {
    graph->indices.clear();
    graph->edges.clear();
    graph->vertices.clear();
    graph->input_length = input.size();
    graph->interpreted_length = 0;
    return 0;
  };
  if (segment_start > lock.start || last_end > segment_start + input.size() ||
      context->input().compare(segment_start, input.size(), input) != 0) return fail();
  const size_t start = lock.start - segment_start;
  size_t end = lock.end - segment_start;
  std::string delimiters = " '";
  schema->config()->GetString("speller/delimiter", &delimiters);
  rime::SyllableGraph before;
  if (start != 0 && static_cast<size_t>(builder.BuildSyllableGraph(
          input.substr(0, start), prism, &before)) != start) return fail();
  std::vector<LockedSpan> spans{{lock.start, lock.end, lock.syllable}};
  spans.insert(spans.end(), lock.following.begin(), lock.following.end());
  std::vector<rime::SyllableGraph> chosen(spans.size());
  for (size_t i = 0; i < spans.size(); ++i) {
    const size_t from = spans[i].start - segment_start;
    end = spans[i].end - segment_start;
    if (i + 1 == spans.size())
      while (end < input.size() && delimiters.find(input[end]) != std::string::npos) ++end;
    const size_t length = end - from;
    if (end > input.size() || static_cast<size_t>(builder.BuildSyllableGraph(
            input.substr(from, length), prism, &chosen[i])) != length ||
        !ConstrainGraph(&chosen[i], 0, length, spans[i].syllable)) return fail();
  }
  rime::SyllableGraph after;
  const size_t suffix_length = end < input.size()
      ? static_cast<size_t>(builder.BuildSyllableGraph(input.substr(end), prism, &after))
      : 0;
  graph->indices.clear();
  graph->edges.clear();
  graph->vertices.clear();
  graph->input_length = input.size();
  graph->interpreted_length = end + suffix_length;
  AppendGraph(before, 0, graph);
  for (size_t i = 0; i < spans.size(); ++i)
    AppendGraph(chosen[i], spans[i].start - segment_start, graph);
  AppendGraph(after, end, graph);
  RebuildIndices(graph);
  context->set_property(kAppliedProperty, Encode(lock));
  return graph->interpreted_length;
}

inline std::unique_ptr<rime::Dictionary> OpenDictionary(rime::Schema* schema) {
  if (!schema || !schema->config()) return nullptr;
  auto* config = schema->config();
  auto has_component = [&](const char* path, const std::string& component,
                           bool primary_namespace) {
    const auto list = config->GetList(path);
    if (!list) return false;
    for (const auto& item : *list) {
      const auto value = rime::As<rime::ConfigValue>(item);
      if (!value) continue;
      const auto& name = value->str();
      if (name == component ||
          (primary_namespace && name == component + "@translator") ||
          (!primary_namespace && name.compare(0, component.size() + 1,
                                              component + "@") == 0)) return true;
    }
    return false;
  };
  // Query the primary phonetic translator, not the physical key count. T9 has
  // its own stateful syllable-selection contract and must never enter this API.
  if (!has_component("engine/translators", "script_translator", true) ||
      has_component("engine/processors", "t9_processor", false)) return nullptr;
  std::string alphabet;
  if (!config->GetString("speller/alphabet", &alphabet) ||
      alphabet.find_first_of("abcdefghijklmnopqrstuvwxyz") == std::string::npos ||
      alphabet.find_first_of("0123456789") != std::string::npos) return nullptr;
  auto component = rime::Dictionary::Require("dictionary");
  if (!component) return nullptr;
  std::unique_ptr<rime::Dictionary> dictionary(
      component->Create(rime::Ticket(schema, "translator")));
  if (!dictionary || (!dictionary->loaded() && !dictionary->Load()) ||
      !dictionary->loaded() ||
      !dictionary->prism() || !dictionary->primary_table()) return nullptr;
  // Legacy deployed schemas lack language/scheme metadata. Their dictionary's
  // actual syllabary is the authority, independent of full/double/merged codes.
  // These Mandarin readings distinguish the shared pinyin table from kana and
  // alphabetic word tables without guessing schema or dictionary filenames.
  rime::Syllabary syllabary;
  if (!dictionary->primary_table()->GetSyllabary(&syllabary) ||
      syllabary.count("ni") == 0 || syllabary.count("hao") == 0 ||
      syllabary.count("zhong") == 0) return nullptr;
  return dictionary;
}

class OffsetCorrector : public rime::Corrector {
 public:
  OffsetCorrector(rime::Corrector* inner, size_t offset) : inner_(inner), offset_(offset) {}
  void ToleranceSearch(const rime::Prism& prism, const std::string& key,
                       rime::corrector::Corrections* results, size_t tolerance) override {
    inner_->ToleranceSearch(prism, key, results, tolerance);
  }
  bool CorrectsDoubledLetters() const override { return inner_->CorrectsDoubledLetters(); }
  bool CorrectsExtraLetters() const override { return inner_->CorrectsExtraLetters(); }
  bool CorrectsStrayInitials() const override { return inner_->CorrectsStrayInitials(); }
  bool IsExtraKeyPress(char a, char b, char c) override { return inner_->IsExtraKeyPress(a, b, c); }
  void NoteSpatialOrigin(size_t origin) override { inner_->NoteSpatialOrigin(offset_ + origin); }
 private:
  rime::Corrector* inner_;
  size_t offset_;
};

inline std::vector<Option> Options(rime::Context* context, rime::Schema* schema,
                                   rime::Dictionary* dictionary, rime::Engine* engine) {
  std::vector<Option> result;
  if (!context || !schema || !dictionary || context->get_option("ascii_mode"))
    return result;
  const size_t confirmed = context->composition().GetConfirmedPosition();
  Lock selected;
  const bool has_selected = ReadLock(context, schema, *dictionary->prism(), &selected);
  const size_t start = has_selected
      ? (selected.following.empty() ? selected.end : selected.following.back().end)
      : confirmed;
  const size_t caret = context->caret_pos();
  if (start >= caret || caret > context->input().size()) return result;
  std::string delimiters = " '";
  schema->config()->GetString("speller/delimiter", &delimiters);
  bool strict = false;
  schema->config()->GetBool("translator/strict_spelling", &strict);
  size_t code_start = start;
  while (code_start < caret && delimiters.find(context->input()[code_start]) != std::string::npos)
    ++code_start;
  if (code_start == caret) return result;
  std::vector<rime::Prism::Match> matches;
  dictionary->prism()->CommonPrefixSearch(
      std::string_view(context->input().data() + code_start, caret - code_start), &matches);
  std::map<std::pair<size_t, rime::SyllableId>, size_t> seen;
  // Query the authoritative prism before whole-input pruning: choosing ni from
  // nin is legitimate and locks ni+n rather than making ni disappear from UI.
  for (const auto& match : matches) {
    if (match.length == 0 || match.length > caret - code_start) continue;
    size_t end = code_start + match.length;
    while (end < caret && delimiters.find(context->input()[end]) != std::string::npos) ++end;
    for (auto spelling = dictionary->prism()->QuerySpelling(match.value);
         !spelling.exhausted(); spelling.Next()) {
      const auto properties = spelling.properties();
      if (properties.type >= rime::kAbbreviation || properties.is_correction ||
          (strict && end == caret && properties.type != rime::kNormalSpelling)) continue;
      const auto syllable = spelling.syllable_id();
      const auto key = std::make_pair(end, syllable);
      const auto previous = seen.find(key);
      if (previous != seen.end()) {
        result[previous->second].credibility =
            std::max(result[previous->second].credibility, properties.credibility);
        continue;
      }
      const auto pinyin = dictionary->primary_table()->GetSyllableById(syllable);
      if (pinyin.empty()) continue;
      seen.emplace(key, result.size());
      result.push_back({syllable, pinyin, start, end, false, properties.credibility});
    }
  }
  // Build each possible bounded prefix independently: the full graph prunes
  // shorter paths before a user can choose them. Use the same corrector and
  // spelling settings as the primary translator, without inventing spellings.
  bool correction = false, completion = true;
  auto* config = schema->config();
  config->GetBool("translator/enable_correction", &correction);
  config->GetBool("translator/enable_completion", &completion);
  auto* component = rime::Corrector::Require("corrector");
  std::unique_ptr<rime::Corrector> corrector(
      correction && engine && component ? component->Create(rime::Ticket(engine, "translator")) : nullptr);
  if (corrector) {
    OffsetCorrector offset_corrector(corrector.get(), start);
    rime::Syllabifier builder(delimiters, completion, strict);
    builder.EnableCorrection(&offset_corrector);
    // Rime's correction search looks at at most a short syllable; bound work
    // on long compositions while retaining a trailing delimiter in the span.
    for (size_t end = code_start + 1; end <= caret && end - code_start <= 8; ++end) {
      rime::SyllableGraph graph;
      const auto input = context->input().substr(start, end - start);
      if (static_cast<size_t>(builder.BuildSyllableGraph(input, *dictionary->prism(), &graph)) != input.size()) continue;
      auto from = graph.edges.find(0);
      if (from == graph.edges.end()) continue;
      auto to = from->second.find(input.size());
      if (to == from->second.end()) continue;
      for (const auto& spelling : to->second) {
        const auto& props = spelling.second;
        if (!props.is_correction || props.type >= rime::kAbbreviation) continue;
        const auto key = std::make_pair(end, spelling.first);
        if (seen.count(key)) continue;
        const auto pinyin = dictionary->primary_table()->GetSyllableById(spelling.first);
        if (pinyin.empty()) continue;
        seen.emplace(key, result.size());
        result.push_back({spelling.first, pinyin, start, end, false, props.credibility});
      }
    }
  }
  // Phrase code is engine metadata, not comment/preedit (which filters may
  // rewrite). Ranking is advisory: only graph-derived choices can be locked.
  std::map<rime::SyllableId, size_t> rank;
  const auto& composition = context->composition();
  for (const auto& segment : composition) {
    if (segment.start != confirmed || !segment.menu) continue;
    const size_t code_index = has_selected ? 1 + selected.following.size() : 0;
    for (size_t i = 0; i < 32; ++i) {
      auto candidate = segment.GetCandidateAt(i);
      if (!candidate) break;
      for (const auto& genuine : rime::Candidate::GetGenuineCandidates(candidate)) {
        auto phrase = std::dynamic_pointer_cast<rime::Phrase>(genuine);
        if (phrase && phrase->code().size() > code_index)
          rank.emplace(phrase->code()[code_index], i);
      }
    }
    break;
  }
  std::stable_sort(result.begin(), result.end(), [&](const Option& a, const Option& b) {
    const auto ar = rank.find(a.syllable), br = rank.find(b.syllable);
    const size_t ai = ar == rank.end() ? 32 : ar->second;
    const size_t bi = br == rank.end() ? 32 : br->second;
    if (ai != bi) return ai < bi;
    if (a.credibility != b.credibility) return a.credibility > b.credibility;
    return a.end != b.end ? a.end > b.end : a.pinyin < b.pinyin;
  });
  return result;
}

inline bool Select(rime::Context* context, rime::Schema* schema,
                   const std::string& expected_input, const std::string& expected_schema,
                   size_t start, size_t end, rime::SyllableId syllable,
                   rime::Engine* engine) {
  if (!context || !schema || context->get_option("ascii_mode") ||
      context->input() != expected_input || schema->schema_id() != expected_schema ||
      start >= end || end > context->caret_pos()) return false;
  auto dictionary = OpenDictionary(schema);
  if (!dictionary) return false;
  const auto options = Options(context, schema, dictionary.get(), engine);
  if (std::none_of(options.begin(), options.end(), [&](const Option& option) {
        return option.start == start && option.end == end && option.syllable == syllable;
      })) return false;

  const auto previous_property = context->get_property(kProperty);
  const auto previous_applied = context->get_property(kAppliedProperty);
  const auto previous_composition = context->composition();
  const auto caret = context->caret_pos();
  const auto confirmed_text = ConfirmedText(*context);
  Lock lock;
  if (ReadLock(context, schema, *dictionary->prism(), &lock)) {
    const auto last_end = lock.following.empty() ? lock.end : lock.following.back().end;
    if (start != last_end) return false;
    lock.following.push_back({start, end, syllable});
  } else {
    const auto confirmed = context->composition().GetConfirmedPosition();
    if (start != confirmed) return false;
    lock = Lock{expected_schema, expected_input, confirmed_text,
                context->composition_epoch(), dictionary->prism()->dict_file_checksum(),
                dictionary->prism()->schema_file_checksum(), confirmed, start, end, syllable};
  }
  const auto encoded = Encode(lock);
  context->set_property(kAppliedProperty, "");
  context->set_property(kProperty, encoded);
  const bool refreshed = context->RefreshNonConfirmedComposition();
  if (refreshed && context->caret_pos() == caret && context->HasMenu() &&
      context->get_property(kAppliedProperty) == encoded &&
      Matches(lock, *context, *schema, *dictionary->prism())) return true;

  // No input/caret/commit API participates in a successful selection. Restore
  // the old constraint and composition if the selected graph has no result.
  context->set_property(kProperty, previous_property);
  context->set_property(kAppliedProperty, previous_applied);
  context->set_composition(rime::Composition(previous_composition));
  return false;
}

}  // namespace pinyin_selection
}  // namespace cyime
#endif  // CYIME_PINYIN_SYLLABLE_SELECTION_H_
