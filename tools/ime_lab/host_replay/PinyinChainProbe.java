import com.kingzcheung.xime.rime.RimeEngine;
import java.io.File;
import java.util.Arrays;

/** Native regression: successive choices lock different spans without rewriting input. */
public final class PinyinChainProbe {
  static void check(boolean ok, String message) throws Exception {
    if (!ok) throw new Exception(message);
  }
  static String[] choose(RimeEngine e, String reading, int start, int end) throws Exception {
    for (String record : e.nativePinyinSyllableOptions()) {
      String[] p = record.split("\\t", -1);
      if (p.length == 5 && p[1].equals(reading) && Integer.parseInt(p[2]) == start &&
          Integer.parseInt(p[3]) == end) {
        String input = e.nativeGetInput();
        check(e.nativeSelectPinyinSyllable(input, e.nativeGetCurrentSchema(), start, end,
            Integer.parseInt(p[0])), "selection failed: " + record);
        check(input.equals(e.nativeGetInput()), "selection rewrote input");
        return p;
      }
    }
    throw new Exception("missing " + reading + " at " + start + ": " +
        Arrays.toString(e.nativePinyinSyllableOptions()));
  }
  static void type(RimeEngine e, String text) throws Exception {
    for (char c : text.toCharArray()) check(e.nativeProcessKey(c, 0), "key rejected: " + c);
  }
  static void backspace(RimeEngine e, int count) throws Exception {
    for (int i = 0; i < count; i++) check(e.nativeProcessKey(0xff08, 0), "backspace rejected");
  }
  static void correctedChain(RimeEngine e) throws Exception {
    e.nativeSetInput("");
    type(e, "dsdaochaotian");
    choose(e, "da", 0, 2);
    choose(e, "dao", 2, 5);
    choose(e, "chao", 5, 9);
    choose(e, "tian", 9, 13);
    check(e.nativePinyinSyllableOptions().length == 0, "chain not finished");
  }
  public static void main(String[] args) throws Exception {
    System.load(new File(args[0], "librime_jni.so").getAbsolutePath());
    new File(args[1], "logs").mkdirs();
    RimeEngine e = new RimeEngine();
    e.nativeInitialize(args[1], args[1]);
    try {
      e.nativeStartMaintenance(true);
      while (e.nativeIsMaintaining()) Thread.sleep(100);
      check(e.nativeCreateSession(), "session unavailable");
      check(e.nativeSwitchSchema("rime_ice"), "schema unavailable");
      e.nativeSetOption("ascii_mode", false);
      for (char c : "nihao".toCharArray()) check(e.nativeProcessKey(c, 0), "key rejected");
      String[] ni = choose(e, "ni", 0, 2);
      e.nativeSetOption("ascii_mode", false);
      e.nativeSetOption("context_probe_unknown", false);
      choose(e, "hao", 2, 5);
      check(e.nativePinyinSyllableOptions().length == 0, "same options lost lock");
      e.nativeSetOption("context_probe_unknown", true);
      choose(e, "ni", 0, 2);
      e.nativeSetOption("ascii_mode", true);
      e.nativeSetOption("ascii_mode", false);
      choose(e, "ni", 0, 2);
      String[] hao = choose(e, "hao", 2, 5);
      check(e.nativePinyinSyllableOptions().length == 0, "finished chain still offers options");
      check(!e.nativeSelectPinyinSyllable("nihao", "rime_ice", 0, 2,
          Integer.parseInt(ni[0])), "old span accepted");
      boolean found = false;
      for (String[] row : e.nativeInspectCandidates(500)) {
        if (row.length < 9 || row[8].isEmpty() || !row[0].codePoints().anyMatch(
            c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN)) continue;
        String[] ids = row[8].split(",");
        if (ids.length < 2) continue;
        found = true;
        check(ids[0].equals(ni[0]) && ids[1].equals(hao[0]), "escaped lock: " + Arrays.toString(row));
      }
      check(found, "no inspectable two-syllable candidate");
      e.nativeSetInput("");
      e.nativeSetNeighborMap("s:adw;d:sfec");
      for (char c : "dsdaochaotian".toCharArray()) check(e.nativeProcessKey(c, 0), "key rejected");
      String[] da = choose(e, "da", 0, 2);
      String[] dao = choose(e, "dao", 2, 5);
      found = false;
      for (String[] row : e.nativeInspectCandidates(500)) {
        if (row.length < 9 || row[8].isEmpty()) continue;
        String[] ids = row[8].split(",");
        if (ids.length < 2) continue;
        found = true;
        check(ids[0].equals(da[0]) && ids[1].equals(dao[0]), "corrected lock escaped: " + Arrays.toString(row));
      }
      check(found, "no inspectable corrected candidate");
      // A fully locked chain: deleting and retyping the final character must
      // release only tian, not the three earlier selections.
      correctedChain(e);
      backspace(e, 1);
      type(e, "n");
      choose(e, "tian", 9, 13);
      check(e.nativePinyinSyllableOptions().length == 0, "last lock not restored");

      // Delete through tian and into chao; retyping cannot resurrect either.
      backspace(e, 5);
      type(e, "otian");
      choose(e, "chao", 5, 9);
      choose(e, "tian", 9, 13);
      check(e.nativePinyinSyllableOptions().length == 0, "cross-boundary locks");

      // Break the first span, restore it, and ensure the old chain is gone.
      e.nativeSetCaret(2);
      backspace(e, 1);
      type(e, "s");
      e.nativeSetCaret(13);
      choose(e, "da", 0, 2);
      choose(e, "dao", 2, 5);
      choose(e, "chao", 5, 9);
      choose(e, "tian", 9, 13);

      // An unselected suffix may grow or shrink without losing the prefix.
      e.nativeSetInput("");
      type(e, "dsdaochaot");
      choose(e, "da", 0, 2);
      choose(e, "dao", 2, 5);
      choose(e, "chao", 5, 9);
      type(e, "ia");
      backspace(e, 1);
      type(e, "an");
      choose(e, "tian", 9, 13);

      // An insertion inside a lock invalidates that span and all after it,
      // even if the inserted character is immediately removed.
      e.nativeSetCaret(7);
      type(e, "x");
      backspace(e, 1);
      e.nativeSetCaret(13);
      choose(e, "chao", 5, 9);
      choose(e, "tian", 9, 13);

      // Inserting the same final byte of chao must not preserve chao.
      e.nativeSetCaret(8);
      type(e, "o");
      backspace(e, 1);
      e.nativeSetCaret(13);
      choose(e, "chao", 5, 9);
      choose(e, "tian", 9, 13);

      // Add an identical byte just after chao, then delete its original last
      // byte. The visible result is unchanged from the original input.
      e.nativeSetCaret(9);
      type(e, "o");
      e.nativeSetCaret(9);
      backspace(e, 1);
      e.nativeSetCaret(13);
      choose(e, "chao", 5, 9);
      choose(e, "tian", 9, 13);

      // A caret-only update keeps every span.
      e.nativeSetCaret(3);
      e.nativeSetCaret(13);
      check(e.nativePinyinSyllableOptions().length == 0, "caret lost chain");

      // Forward delete inside the second locked span must release it onward.
      e.nativeSetCaret(3);
      check(e.nativeProcessKey(0xffff, 0), "forward delete rejected");
      type(e, "a");
      e.nativeSetCaret(13);
      choose(e, "dao", 2, 5);
      choose(e, "chao", 5, 9);
      choose(e, "tian", 9, 13);

      // An identical unknown replacement is still an edit, not a caret move.
      check(e.nativeSetInput("dsdaochaotian"), "same setInput rejected");
      choose(e, "da", 0, 2);
      choose(e, "dao", 2, 5);
      choose(e, "chao", 5, 9);
      choose(e, "tian", 9, 13);

      // setInput has no trusted edit coordinates, even for a suffix edit.
      check(e.nativeSetInput("dsdaochaotia"), "setInput delete rejected");
      check(e.nativeSetInput("dsdaochaotian"), "setInput append rejected");
      choose(e, "da", 0, 2);
      choose(e, "dao", 2, 5);
      choose(e, "chao", 5, 9);
      choose(e, "tian", 9, 13);
      check(e.nativeSetInput("dsdaochxotian"), "setInput replacement rejected");
      check(e.nativeSetInput("dsdaochaotian"), "setInput restore rejected");
      choose(e, "da", 0, 2);
      choose(e, "dao", 2, 5);
      choose(e, "chao", 5, 9);
      choose(e, "tian", 9, 13);
      check(e.nativeSetInput(""), "setInput clear rejected");
      type(e, "dsdaochaotian");
      choose(e, "da", 0, 2);
      choose(e, "dao", 2, 5);
      choose(e, "chao", 5, 9);
      choose(e, "tian", 9, 13);

      // Explicit cancellation and a fresh composition cannot revive a lock.
      check(e.nativeProcessKey(0xff1b, 0), "escape rejected");
      check(e.nativeGetInput().isEmpty(), "escape did not clear input");
      type(e, "dsdaochaotian");
      choose(e, "da", 0, 2);
      choose(e, "dao", 2, 5);
      choose(e, "chao", 5, 9);
      choose(e, "tian", 9, 13);
      check(e.nativeSelectCandidateByGlobalIndex(0), "candidate commit rejected");
      check(e.nativeGetInput().isEmpty(), "candidate did not commit composition");
      type(e, "dsdaochaotian");
      choose(e, "da", 0, 2);
      System.out.println("PASS chain edits, insertion, cancellation and commit");
    } finally { e.nativeDestroy(); }
  }
}
