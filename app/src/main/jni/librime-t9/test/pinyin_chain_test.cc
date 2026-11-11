// Standalone host regression for lock serialization and graph pruning.
#include "pinyin_syllable_selection.h"
#include <cassert>

int main() {
  using namespace cyime::pinyin_selection;
  Lock lock{"rime_ice", "nihao", "", 3, 11, 12, 0, 0, 2, 7};
  lock.following.push_back({2, 5, 8});
  Lock decoded;
  assert(Decode(Encode(lock), &decoded));
  assert(decoded.end == 2 && decoded.following.size() == 1);
  assert(decoded.following[0].start == 2 && decoded.following[0].end == 5 &&
         decoded.following[0].syllable == 8);
  assert(!Decode(Encode(lock) + " 1 3 9", &decoded));
  assert(!Decode(Encode(lock) + " junk", &decoded));

  rime::SyllableGraph graph;
  graph.input_length = 2;
  graph.edges[0][2][7] = rime::EdgeProperties();
  graph.edges[0][2][9] = rime::EdgeProperties();
  graph.edges[0][1][10] = rime::EdgeProperties();
  graph.edges[1][2][11] = rime::EdgeProperties();
  assert(ConstrainGraph(&graph, 0, 2, 7));
  assert(graph.edges.size() == 1 && graph.edges[0].size() == 1 &&
         graph.edges[0][2].size() == 1 && graph.edges[0][2].count(7));
  assert(graph.indices[0][7].size() == 1);

  rime::SyllableGraph corrected;
  corrected.input_length = 2;
  corrected.edges[0][2][7] = rime::EdgeProperties();
  corrected.edges[0][2][7].is_correction = true;
  corrected.edges[0][2][7].credibility = -2.0;
  corrected.edges[0][2][9] = rime::EdgeProperties();
  assert(ConstrainGraph(&corrected, 0, 2, 7));
  assert(corrected.edges[0][2].size() == 1);
  assert(corrected.edges[0][2][7].is_correction);
  assert(corrected.edges[0][2][7].credibility == -2.0);
}
