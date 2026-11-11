import com.kingzcheung.xime.rime.RimeEngine;
import java.io.File;
import java.util.Arrays;

/** Exercises the deployed prism, including mixed exact letters and ambiguous taps. */
public final class MergedLetterProbe {
  public static void main(String[] args) throws Exception {
    System.load(new File(args[0], "librime_jni.so").getAbsolutePath());
    File logs = new File(args[1], "logs");
    if (!logs.isDirectory() && !logs.mkdirs()) throw new Exception("cannot create native log directory");
    RimeEngine e = new RimeEngine();
    e.nativeInitialize(args[1], args[1]);
    try {
      e.nativeStartMaintenance(true);
      long until = System.currentTimeMillis() + 180000;
      while (e.nativeIsMaintaining()) {
        if (System.currentTimeMillis() > until) throw new Exception("deployment timeout");
        Thread.sleep(100);
      }
      if (!e.nativeCreateSession()) throw new Exception("session unavailable");
      if (args.length > 2 && args[2].equals("--prefix-options")) {
        reset(e, "pinyin_14jian", "BUn");
        System.out.println("PREFIX OPTIONS " + Arrays.toString(e.nativePinyinSyllableOptions()));
        option(e, "ni");
        return;
      }
      verify(e, "pinyin_14jian", "bugao", "bu gao", "ni hao", "bu hao");
      verify(e, "pinyin_14jian", "bUGaO", "bu hao", "ni hao");
      verify(e, "pinyin_14jian", "BUGaO", "ni hao");
      verify(e, "pinyin_14jian", "nihao", "ni hao", "bu hao");
      verify(e, "double_pinyin_flypy_14jian", "bugj", "bu gan", "ni gan", "bu han");
      verify(e, "double_pinyin_flypy_14jian", "bUGJ", "bu han", "ni gan");
      verify(e, "double_pinyin_flypy_14jian", "BUGJ", "ni gan");
      System.out.println("PASS exact, ambiguous and mixed merged-key inputs");
      selection(e, "pinyin_14jian", "BUGaO", "ni", "bu");
      selection(e, "double_pinyin_flypy_14jian", "BUGJ", "ni", "bu");
      selection(e, "double_pinyin_flypy", "lo", "lo", "luo");
      selection(e, "rime_ice", "xian", "xian", "xi");
      exactLetter(e);
      confirmedPrefix(e);
      lockedBoundary(e);
      delimiterAndIncompletePrefix(e);
      invalidation(e);
      System.out.println("PASS native syllable selection: real code, competing fresh choices, confirmed prefix, stale rejection and invalidation");
    } finally { e.nativeDestroy(); }
  }
  static void verify(RimeEngine e, String schema, String code, String required, String... excluded) throws Exception {
    if (!e.nativeSwitchSchema(schema)) throw new Exception("schema unavailable: " + schema);
    e.nativeSetOption("ascii_mode", false);
    for (char c : code.toCharArray()) if (!e.nativeProcessKey(c, 0)) throw new Exception("unhandled key " + c);
    if (!e.nativeGetInput().equals(code)) throw new Exception("input changed: " + e.nativeGetInput());
    String[][] rows = e.nativeInspectCandidates(100);
    System.out.println(schema + " " + code + " => " + Arrays.deepToString(Arrays.copyOf(rows, Math.min(rows.length, 6))));
    boolean found = false;
    for (String[] row : rows) {
      if (row.length < 7 || !row[6].equals(Integer.toString(code.length()))) continue;
      if (row[1].equals(required)) found = true;
      for (String bad : excluded) if (row[1].equals(bad)) throw new Exception("wrong exact letter: " + code + " => " + Arrays.toString(row));
    }
    if (!found) throw new Exception("missing expected reading " + required + " for " + code);
    e.nativeProcessKey(0xff1b, 0);
  }

  static void reset(RimeEngine e, String schema, String code) throws Exception {
    e.nativeProcessKey(0xff1b, 0);
    if (!e.nativeSwitchSchema(schema)) throw new Exception("schema unavailable: " + schema);
    e.nativeSetOption("ascii_mode", false);
    for (char c : code.toCharArray()) if (!e.nativeProcessKey(c, 0)) throw new Exception("unhandled key " + c);
    require(code.equals(e.nativeGetInput()), "raw input changed before selection");
  }

  static String[] option(RimeEngine e, String reading) throws Exception {
    for (String encoded : e.nativePinyinSyllableOptions()) {
      String[] value = encoded.split("\\t", -1);
      if (value.length == 5 && value[1].equals(reading)) return value;
    }
    throw new Exception("missing reading " + reading + " for " + e.nativeGetInput() + ": " +
        Arrays.toString(e.nativePinyinSyllableOptions()));
  }

  static String[] lastChoice;

  static void choose(RimeEngine e, String reading) throws Exception {
    String[] value = option(e, reading);
    String[] before = e.nativePinyinEditSnapshot();
    require(e.nativeSelectPinyinSyllable(before[0], e.nativeGetCurrentSchema(),
        Integer.parseInt(value[2]), Integer.parseInt(value[3]), Integer.parseInt(value[0])),
        "selection failed: " + reading + " " + Arrays.toString(value));
    String[] after = e.nativePinyinEditSnapshot();
    for (int i = 0; i < 4; ++i) require(before[i].equals(after[i]),
        "selection changed raw/caret/confirmed state " + Arrays.toString(before) + " => " + Arrays.toString(after));
    lastChoice = value;
    assertConstrained(e, reading);
    System.out.println("LOCK " + e.nativeGetCurrentSchema() + " " + before[0] + " => " + reading +
        " id=" + value[0] + " raw/caret/confirmed preserved");
  }

  static void assertConstrained(RimeEngine e, String reading) throws Exception {
    String[] value = lastChoice;
    require(value != null && value[1].equals(reading), "unexpected selected reading: " + reading);
    boolean found = false;
    for (String[] row : e.nativeInspectCandidates(500)) {
      // Independent English/custom table translators use another dictionary's
      // syllable IDs. This contract constrains Chinese phonetic candidates.
      if (row.length < 9 || row[8].isEmpty() || !row[5].equals(value[2]) ||
          !row[0].codePoints().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN)) continue;
      found = true;
      require(row[8].split(",")[0].equals(value[0]),
          "candidate escaped selected pronunciation " + reading + ": " + Arrays.toString(row));
    }
    require(found, "selection produced no inspectable phonetic candidate: " + reading);
  }

  static void selection(RimeEngine e, String schema, String code, String first, String second) throws Exception {
    reset(e, schema, code);
    // Both readings must actually exist before the constraint; a UI label is not proof.
    String firstId = option(e, first)[0];
    require(!firstId.equals(option(e, second)[0]), "counterexample has identical syllable IDs");
    choose(e, first);
    // Chained selection advances to the next span; start a fresh composition
    // to exercise the competing choice on the original span.
    reset(e, schema, code);
    choose(e, second);
    String[] before = e.nativePinyinEditSnapshot();
    require(!e.nativeSelectPinyinSyllable(code + "z", schema, 0, 2, Integer.parseInt(firstId)), "stale raw accepted");
    require(!e.nativeSelectPinyinSyllable(code, schema + "_stale", 0, 2, Integer.parseInt(firstId)), "stale schema accepted");
    require(!e.nativeSelectPinyinSyllable(code, schema, 0, code.length() + 1, Integer.parseInt(firstId)), "invalid span accepted");
    require(!e.nativeSelectPinyinSyllable(code, schema, 0, 2, Integer.MAX_VALUE), "invalid syllable accepted");
    require(Arrays.equals(before, e.nativePinyinEditSnapshot()), "rejected selection changed composition");
    assertConstrained(e, second);
  }

  static void exactLetter(RimeEngine e) throws Exception {
    reset(e, "pinyin_14jian", "bUGaO");
    for (String encoded : e.nativePinyinSyllableOptions())
      require(!encoded.split("\\t")[1].equals("ni"), "precise lowercase b became ambiguous");
    choose(e, "bu");
  }

  static void confirmedPrefix(RimeEngine e) throws Exception {
    reset(e, "pinyin_14jian", "niBUGaO");
    int selected = -1;
    for (String[] row : e.nativeInspectCandidates(500)) {
      if (row[0].equals("你") && row[6].equals("2")) { selected = Integer.parseInt(row[4]); break; }
    }
    require(selected >= 0 && e.nativeSelectCandidateByGlobalIndex(selected), "cannot prepare confirmed prefix 你");
    String[] state = e.nativePinyinEditSnapshot();
    require(state[2].equals("2") && state[3].equals("你"), "prefix was committed instead of confirmed: " + Arrays.toString(state));
    choose(e, "bu");
    require(e.nativeGetInput().equals("niBUGaO"), "remaining ambiguous suffix was rewritten");
    e.nativeProcessKey('n', 0);
    assertConstrained(e, "bu");
    state = e.nativePinyinEditSnapshot();
    require(state[2].equals("2") && state[3].equals("你"), "suffix append lost confirmed Chinese text");
  }

  static void noSelection(RimeEngine e, String reason) throws Exception {
    String confirmed = e.nativePinyinEditSnapshot()[2];
    for (String value : e.nativePinyinSyllableOptions()) {
      String[] fields = value.split("\\t", -1);
      require(fields.length == 5 && fields[2].equals(confirmed),
          reason + ": old selection advanced the next span " + value);
    }
  }

  static void lockedBoundary(RimeEngine e) throws Exception {
    reset(e, "pinyin_14jian", "BU");
    choose(e, "ni");
    e.nativeProcessKey('n', 0);
    require(e.nativeGetInput().equals("BUn"), "boundary test lost original grouped code");
    assertConstrained(e, "ni");
    for (String encoded : e.nativePinyinSyllableOptions())
      require(!encoded.split("\\t", -1)[1].equals("nin"), "reading crossed locked ni boundary");
    System.out.println("PASS chosen ni boundary survives competing nin reading in BUn");
  }

  static void delimiterAndIncompletePrefix(RimeEngine e) throws Exception {
    reset(e, "pinyin_14jian", "BU'GaO");
    choose(e, "ni");
    e.nativeProcessKey('n', 0);
    assertConstrained(e, "ni");
    require(e.nativeGetInput().equals("BU'GaOn"), "explicit delimiter was rewritten");
    e.nativeSetCaret(1);
    e.nativePinyinSyllableOptions();
    e.nativeSetCaret(7);
    assertConstrained(e, "ni");
    require(e.nativeSetInput("B"), "incomplete prefix replacement failed");
    noSelection(e, "incomplete prefix");
    require(e.nativeSetInput("BU'GaO"), "restore after incomplete prefix failed");
    noSelection(e, "prefix restored after replacement");
    System.out.println("PASS explicit delimiter/caret preserve selected span; incomplete replacement clears it");
  }

  static void invalidation(RimeEngine e) throws Exception {
    reset(e, "pinyin_14jian", "BUGaO");
    choose(e, "ni");
    e.nativeProcessKey('n', 0);
    assertConstrained(e, "ni");
    e.nativeProcessKey(0xff08, 0);
    require(e.nativeGetInput().equals("BUGaO"), "append/delete test did not restore original raw");
    assertConstrained(e, "ni");
    e.nativeProcessKey(0xff08, 0);
    assertConstrained(e, "ni");
    e.nativeProcessKey('O', 0);
    require(e.nativeGetInput().equals("BUGaO"), "delete/retype test did not restore original raw");
    assertConstrained(e, "ni");
    // set_input has no trustworthy edit range, including for append/delete.
    require(e.nativeSetInput("BUGaOn"), "set_input append failed");
    noSelection(e, "unknown set_input append");
    require(e.nativeSetInput("BUGaO"), "set_input suffix deletion failed");
    noSelection(e, "unknown set_input deletion");
    choose(e, "ni");
    e.nativeSetCaret(1);
    e.nativePinyinSyllableOptions(); // Rendering the truncated graph must not clear the lock.
    e.nativeSetCaret(5);
    assertConstrained(e, "ni");
    for (int i = 0; i < 4; ++i) e.nativeProcessKey(0xff08, 0);
    for (char c : "UGaO".toCharArray()) e.nativeProcessKey(c, 0);
    require(e.nativeGetInput().equals("BUGaO"), "locked-span edit did not restore original raw");
    noSelection(e, "delete inside locked span then retype");
    choose(e, "ni");
    e.nativeProcessKey(0xff1b, 0);
    for (char c : "BUGaO".toCharArray()) e.nativeProcessKey(c, 0);
    noSelection(e, "clear then retype");
    choose(e, "ni");
    reset(e, "double_pinyin_flypy_14jian", "BUGJ");
    noSelection(e, "schema change");
    System.out.println("PASS suffix edits/append retain lock, caret round-trip retains lock, locked-span edit/clear/schema invalidate");
  }

  static void require(boolean value, String message) throws Exception {
    if (!value) throw new Exception(message);
  }
}
