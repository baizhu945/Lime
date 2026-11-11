// Linux host-only relocation regression. No production allocator hooks.
#include <algorithm>
#include <cassert>
#include <cstdlib>
#include <filesystem>
#include <iostream>
#include <memory>
#include <string>
#include <vector>
#include <sys/mman.h>
#include <rime/dict/table.h>

// Serialized structures must retain the same fixed-width layout on LP64/ILP32.
static_assert(sizeof(rime::OffsetPtr<char>) == 4);
static_assert(sizeof(rime::table::Metadata) == 68);
static_assert(sizeof(rime::table::Entry) == 8);
static_assert(sizeof(rime::table::HeadIndexNode) == 12);
static_assert(sizeof(rime::table::TrunkIndexNode) == 16);
static_assert(sizeof(rime::table::LongEntry) == 16);

namespace {
bool quarantine = false;
std::vector<size_t> released_sizes;
}
extern "C" int __real_munmap(void*, size_t);
extern "C" int __wrap_munmap(void* address, size_t length) {
  int result = __real_munmap(address, length);
  if (result == 0 && quarantine) {
    // Keep the old address inaccessible and unavailable for reuse. A stale
    // reference faults even when the kernel would normally reuse that address.
    void* guard = mmap(address, length, PROT_NONE,
                       MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED_NOREPLACE, -1, 0);
    if (guard != address) std::abort();
    released_sizes.push_back(length);
  }
  return result;
}

static void finish(rime::Table& table, const rime::Syllabary& syllables,
                   uint32_t checksum) {
  assert(table.Save());
  table.Close();
  assert(table.Load());
  rime::Syllabary actual;
  assert(table.GetSyllabary(&actual));
  assert(actual == syllables);
  assert(table.dict_file_checksum() == checksum);
}

void run_build_growth_tests(const std::filesystem::path& directory,
                            const std::string& which) {
  assert(which.empty() || which == "final" || which == "index");
  std::filesystem::create_directories(directory);
  if (which.empty()) {
    // Existing non-mapped users (e.g. reverse lookup) retain Add/Build/Clear.
    rime::StringTableBuilder builder;
    rime::StringId id = rime::kInvalidStringId;
    builder.Add("direct", 1.0, &id);
    builder.Build();
    assert(builder.GetString(id) == "direct");
    builder.Clear();
    id = rime::kInvalidStringId;
    builder.Add("after-clear", 1.0, &id);
    builder.Build();
    assert(builder.GetString(id) == "after-clear");
  }
  if (which.empty() || which == "final") {
    // One nonempty key, no entries: initial estimate is exactly 4128 bytes.
    rime::Syllabary syllables{std::string(32768, 'q')};
    rime::Table table{rime::path(directory / "growth-final.table.bin")};
    released_sizes.clear();
    quarantine = true;
    assert(table.Build(syllables, rime::Vocabulary{}, 0, 789));
    quarantine = false;
    assert(released_sizes.size() == 1 && released_sizes[0] == 4128);
    assert(table.file_size() > 4128);
    const size_t built_size = table.file_size();
    finish(table, syllables, 789);
    assert(table.QueryWords(0).exhausted());
    std::cout << "PASS growth-final: initial=4128 actual=" << built_size
              << " forced_moves=" << released_sizes.size() << std::endl;
  }
  if (which.empty() || which == "index") {
    rime::Syllabary syllables{"a", "b"};
    rime::Vocabulary vocabulary;
    std::vector<std::vector<std::shared_ptr<rime::ShortDictEntry>>> expected;
    for (int branch = 0; branch < 2; ++branch) {
      rime::Vocabulary* level = &vocabulary;
      for (int depth = 1; depth <= 4; ++depth) {
        auto& page = (*level)[depth == 4 ? -1 : branch];
        expected.emplace_back();
        for (int i = 0; i < 900; ++i) {
          auto entry = std::make_shared<rime::ShortDictEntry>();
          entry->text = "entry-" + std::to_string(branch) + "-" +
                        std::to_string(depth) + "-" + std::to_string(i);
          entry->weight = i + 0.25;
          entry->code.assign(depth == 4 ? 64 + i % 64 : depth, branch);
          page.entries.push_back(entry);
          expected.back().push_back(entry);
        }
        if (depth < 4) {
          page.next_level = std::make_shared<rime::Vocabulary>();
          level = page.next_level.get();
        }
      }
    }
    rime::Table table{rime::path(directory / "growth-index.table.bin")};
    released_sizes.clear();
    quarantine = true;
    // Deliberately underreport 7200 real entries. This is not a capacity test
    // disguised as an empty dictionary: all texts/codes/weights are checked.
    assert(table.Build(syllables, vocabulary, 0, 987));
    quarantine = false;
    assert(released_sizes.size() >= 4 && released_sizes.front() == 4160);
    const size_t built_size = table.file_size();
    finish(table, syllables, 987);
    for (const auto& bucket : expected) {
      auto accessor = table.QueryPhrases(bucket.front()->code);
      assert(accessor.remaining() == bucket.size());
      for (const auto& entry : bucket) {
        assert(!accessor.exhausted());
        assert(table.GetEntryText(*accessor.entry()) == entry->text);
        assert(accessor.entry()->weight == entry->weight);
        const auto code = accessor.code();
        assert(code.size() == entry->code.size());
        assert(std::equal(code.begin(), code.end(), entry->code.begin()));
        accessor.Next();
      }
      assert(accessor.exhausted());
    }
    std::cout << "PASS growth-index: entries=7200 reported=0 initial=4160 actual="
              << built_size << " forced_moves=" << released_sizes.size()
              << " old_capacities=";
    for (auto size : released_sizes) std::cout << size << ',';
    std::cout << std::endl;
  }
}
