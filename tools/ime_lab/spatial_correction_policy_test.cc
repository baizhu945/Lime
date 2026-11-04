#include "spatial_correction_policy.h"

#include <cstdio>
#include <cstdlib>

using rime::spatial_policy::BeatsLiteral;
using rime::spatial_policy::AllowedWithoutLiteral;
using rime::spatial_policy::EditStats;

static int failed = 0;

static void Expect(const char* name, bool got, bool want) {
  if (got == want) return;
  std::fprintf(stderr, "FAIL %s: got %s\n", name, got ? "true" : "false");
  ++failed;
}

static EditStats Edits(int count, double worst, double sum) {
  return EditStats{count, sum, worst};
}

int main() {
  // A long sentence the typed letters already spell. Ordinary scatter must not rewrite it.
  Expect("three slips", BeatsLiteral(Edits(3, -1.2, -3.6), 8.0, 24), false);
  Expect("two off-centre slips", BeatsLiteral(Edits(2, -1.2, -2.4), 6.0, 20), false);
  Expect("one off-centre, modest gain", BeatsLiteral(Edits(1, -1.3, -1.3), 2.0, 20), false);
  Expect("one off-centre, real typo", BeatsLiteral(Edits(1, -1.3, -1.3), 5.0, 20), true);
  Expect("one gap touch", BeatsLiteral(Edits(1, -0.35, -0.35), 1.2, 22), true);
  Expect("one gap touch, weak gain", BeatsLiteral(Edits(1, -0.35, -0.35), 0.4, 22), false);
  Expect("centre tap, modest gain", BeatsLiteral(Edits(1, -2.4, -2.4), 4.0, 20), false);
  Expect("centre tap, overwhelming", BeatsLiteral(Edits(1, -2.4, -2.4), 8.0, 20), true);
  Expect("two gap touches need a large gain", BeatsLiteral(Edits(2, -0.4, -0.8), 3.0, 20), false);
  Expect("two gap touches, broken literal", BeatsLiteral(Edits(2, -0.4, -0.8), 6.0, 20), true);

  // Short input still corrects one ambiguous letter (xiang / xianh) and one real miss (nihso).
  Expect("short boundary", BeatsLiteral(Edits(1, -0.4, -0.4), 1.5, 5), true);
  Expect("short centre, huge gain", BeatsLiteral(Edits(1, -2.5, -2.5), 6.0, 5), true);
  Expect("short centre, small gain", BeatsLiteral(Edits(1, -2.3, -2.3), 1.0, 6), false);

  // No sentence covers the typed letters: one adjacent miss is allowed, three are not.
  Expect("uncovered one", AllowedWithoutLiteral(Edits(1, -2.5, -2.5), 5), true);
  Expect("uncovered two confident", AllowedWithoutLiteral(Edits(2, -2.0, -4.0), 12), false);
  Expect("uncovered three", AllowedWithoutLiteral(Edits(3, -0.4, -1.2), 18), false);

  if (failed) {
    std::fprintf(stderr, "%d failed\n", failed);
    return 1;
  }
  std::puts("PASS spatial correction policy");
  return 0;
}
