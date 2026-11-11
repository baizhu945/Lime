// Test-only private access constructs structurally valid serialized corruption;
// no production API, assertions, or deserializer is weakened.
#include <cassert>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <sstream>
#include <memory>
#include <marisa/agent.h>
#include <marisa/grimoire/trie/cache.h>
#include <marisa/grimoire/trie/config.h>
#include <marisa/grimoire/trie/key.h>
#include <marisa/grimoire/trie/tail.h>
#include <marisa/grimoire/vector.h>
#include <marisa/keyset.h>
#define private public
#include <marisa/grimoire/trie/louds-trie.h>
#undef private
#include <rime/dict/table.h>

#ifdef NDEBUG
#error Regression must retain assertions
#endif
using marisa::grimoire::trie::LoudsTrie;

static std::string image(const LoudsTrie& trie) {
  std::ostringstream stream(std::ios::binary);
  marisa::grimoire::Writer writer;
  writer.open(stream);
  trie.write(writer);
  return stream.str();
}

static void check_trie(const std::string& bytes, bool valid) {
  for (bool mapped : {true, false}) {
    bool threw = false;
    try {
      LoudsTrie trie;
      marisa::grimoire::Mapper mapper;
      marisa::grimoire::Reader reader;
      std::istringstream stream(bytes, std::ios::binary);
      if (mapped) {
        mapper.open(static_cast<const void*>(bytes.data()), bytes.size());
        trie.map(mapper);
      } else {
        reader.open(stream);
        trie.read(reader);
      }
      assert(trie.num_keys() > 0);
      // Exercise the reverse lookup which formerly hit cache_[...]'s assert.
      marisa::Agent agent;
      agent.init_state();
      agent.set_query(std::size_t{0});
      trie.reverse_lookup(agent);
      assert(agent.key().length() > 0);
    } catch (const std::exception&) {
      threw = true;
    }
    assert(threw == !valid);
  }
}

static std::string table_image(const std::string& trie) {
  constexpr size_t syllable_offset = 128, index_offset = 160, trie_offset = 256;
  std::string result(trie_offset + trie.size(), '\0');
  auto* metadata = reinterpret_cast<rime::table::Metadata*>(result.data());
  std::strcpy(metadata->format, "Rime::Table/4.0");
  metadata->num_syllables = 1;
  auto* syllables = reinterpret_cast<rime::table::Syllabary*>(result.data() + syllable_offset);
  syllables->size = 1;
  syllables->at[0].str_id() = 0;
  auto* index = reinterpret_cast<rime::table::Index*>(result.data() + index_offset);
  index->size = 1;
  metadata->syllabary = syllables;
  metadata->index = index;
  metadata->string_table = result.data() + trie_offset;
  metadata->string_table_size = trie.size();
  std::memcpy(result.data() + trie_offset, trie.data(), trie.size());
  return result;
}

static void check_table(const std::filesystem::path& file, const std::string& bytes,
                        bool valid) {
  {
    std::ofstream out(file, std::ios::binary);
    out.write(bytes.data(), bytes.size());
  }
  rime::Table table{rime::path(file)};
  assert(table.Load() == valid);
  if (valid) {
    rime::Syllabary syllables;
    assert(table.GetSyllabary(&syllables));
    assert(syllables.size() == 1);
    // Reload a previously open mapping, too.
    assert(table.Load());
  } else {
    assert(!table.IsOpen());
    assert(table.metadata() == nullptr);
    assert(table.GetSyllableById(0).empty());
    assert(!table.Load());
  }
}

void run_build_growth_tests(const std::filesystem::path&, const std::string&);

int main(int argc, char** argv) {
  assert(argc == 2 || argc == 3);
  if (argc == 2) {
    run_build_growth_tests(argv[1], "");
  } else if (std::string(argv[2]) != "legacy") {
    run_build_growth_tests(argv[1], argv[2]);
    return 0;
  }
  std::filesystem::path directory(argv[1]);
  std::filesystem::create_directories(directory);
  // Also verify a real Table::Build/Save image, not only synthetic envelopes.
  const auto built_path = directory / "built.table.bin";
  {
    rime::Table table{rime::path(built_path)};
    rime::Vocabulary vocabulary;
    auto entry = std::make_shared<rime::ShortDictEntry>();
    entry->text = "你";
    entry->code.push_back(0);
    entry->weight = 1;
    vocabulary[0].entries.push_back(entry);
    assert(table.Build(rime::Syllabary{"ni"}, vocabulary, 1, 123));
    assert(table.Save());
    table.Close();
    assert(table.Load());
    assert(table.GetSyllableById(0) == "ni");
    assert(table.dict_file_checksum() == 123);
  }
  LoudsTrie single;
  marisa::Keyset single_keys;
  single_keys.push_back("ni");
  single.build(single_keys, 1 | MARISA_BINARY_TAIL);
  assert(!single.next_trie_);
  check_trie(image(single), true);

  LoudsTrie trie;
  marisa::Keyset keys;
  // Shared long suffixes force a nested trie with MARISA_NUM_TRIES > 1.
  for (int i = 0; i < 200; ++i) {
    std::string key = std::to_string(i) + "abcdefghijklmnopqrstuvwxy";
    keys.push_back(key.c_str());
  }
  trie.build(keys, 3 | MARISA_BINARY_TAIL);
  assert(trie.next_trie_);
  auto valid = image(trie);
  check_trie(valid, true);
  check_table(directory / "valid.table.bin", table_image(valid), true);

  marisa::grimoire::Vector<marisa::grimoire::trie::Cache> saved_cache;
  saved_cache.swap(trie.cache_);
  trie.cache_.resize(0);
  auto empty_top = image(trie);
  check_trie(empty_top, false);
  check_table(directory / "empty-top.table.bin", table_image(empty_top), false);
  trie.cache_.resize(3);
  auto odd_top = image(trie);
  check_trie(odd_top, false);
  check_table(directory / "odd-top.table.bin", table_image(odd_top), false);
  trie.cache_.swap(saved_cache);

  trie.next_trie_->cache_.clear();
  auto empty_child = image(trie);
  check_trie(empty_child, false);
  check_table(directory / "empty-child.table.bin", table_image(empty_child), false);
  trie.next_trie_->cache_.resize(3);
  check_trie(image(trie), false);

  auto bad = table_image(valid);
  reinterpret_cast<rime::table::Metadata*>(bad.data())->string_table_size = UINT32_MAX;
  check_table(directory / "bad-span.table.bin", bad, false);
  bad = table_image(valid);
  reinterpret_cast<rime::table::Syllabary*>(bad.data() + 128)->at[0].str_id() = UINT32_MAX;
  check_table(directory / "bad-id.table.bin", bad, false);
  bad = table_image(valid);
  reinterpret_cast<rime::table::Index*>(bad.data() + 160)->size = UINT32_MAX;
  check_table(directory / "bad-index.table.bin", bad, false);
  bad = table_image(valid);
  reinterpret_cast<rime::table::Metadata*>(bad.data())->syllabary =
      reinterpret_cast<rime::table::Syllabary*>(bad.data() + bad.size() - 1);
  check_table(directory / "bad-offset.table.bin", bad, false);
  check_table(directory / "short.table.bin", "truncated", false);
  bad = table_image(valid);
  std::memset(reinterpret_cast<rime::table::Metadata*>(bad.data())->format, 'x', 32);
  check_table(directory / "unterminated-format.table.bin", bad, false);
  bad = table_image(valid);
  reinterpret_cast<rime::table::Syllabary*>(bad.data() + 128)->size = UINT32_MAX;
  check_table(directory / "bad-syllabary.table.bin", bad, false);
  bad = table_image(valid);
  reinterpret_cast<rime::table::Metadata*>(bad.data())->num_syllables = UINT32_MAX;
  reinterpret_cast<rime::table::Syllabary*>(bad.data() + 128)->size = UINT32_MAX;
  // Matching corrupt counts must still fail range validation, including on 32-bit ABIs
  // where header + UINT32_MAX * element_size cannot be represented by size_t.
  check_table(directory / "overflow-syllabary.table.bin", bad, false);

  // A rejected table is closed and can be rebuilt in place; siblings survive.
  {
    const auto damaged = directory / "empty-child.table.bin";
    rime::Table table{rime::path(damaged)};
    assert(!table.Load());
    rime::Vocabulary vocabulary;
    auto entry = std::make_shared<rime::ShortDictEntry>();
    entry->text = "你";
    entry->code.push_back(0);
    vocabulary[0].entries.push_back(entry);
    assert(table.Build(rime::Syllabary{"ni"}, vocabulary, 1, 456));
    assert(table.Save());
    table.Close();
    assert(table.Load());
    assert(table.GetSyllableById(0) == "ni");
    assert(table.dict_file_checksum() == 456);
    rime::Table sibling{rime::path(built_path)};
    assert(sibling.Load());
    assert(sibling.dict_file_checksum() == 123);
    assert(sibling.GetSyllableById(0) == "ni");
  }
  std::cout << "PASS: map/read, top/child cache validation, table envelopes and IDs\n";
}
