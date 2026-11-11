// Real librime Context synchronous-observer and one-shot edit regression.
#include <rime/context.h>
#include <cstdlib>
#include <iostream>
#include <limits>
#include <string>

static void check(bool condition, const char* what) {
  if (!condition) { std::cerr << "FAIL: " << what << '\n'; std::exit(1); }
}

int main() {
  rime::Context c;
  c.set_input("aaaa");
  auto epoch = c.composition_epoch();
  unsigned notifications = 0;
  int mode = 0, depth = 0;
  bool nested_consumed = false, outer_consumed = false;
  c.update_notifier().connect(0, [&](rime::Context* ctx) {
    ++notifications;
    if ((mode == 1 || mode == 2) && depth == 0) {
      ++depth;
      if (mode == 1) ctx->set_caret_pos(ctx->caret_pos());
      else ctx->PushInput('b');
      --depth;
    }
  });
  c.update_notifier().connect(1, [&](rime::Context* ctx) {
    rime::Context::InputEdit edit;
    if (mode == 1) {
      bool got = ctx->ConsumeInputEdit("aaaa", epoch, &edit);
      if (depth) nested_consumed = got; else outer_consumed = got;
    } else if (mode == 2) {
      bool got = ctx->ConsumeInputEdit(depth ? "aaaaa" : "aaaa", depth ? epoch + 1 : epoch, &edit);
      if (depth) {
        nested_consumed = got;
        check(got && edit.start == 5 && edit.old_end == 5 && edit.new_input == "aaaaab", "nested edit coordinates");
        check(!ctx->ConsumeInputEdit("aaaaa", epoch + 1, &edit), "nested edit consumed twice");
      } else outer_consumed = got;
    } else if (mode == 3) {
      check(ctx->ConsumeInputEdit("aaaa", epoch, &edit), "trusted edit missing");
      check(edit.start == 2 && edit.old_end == 2 && edit.new_input == "aaaaa", "same-byte insertion coordinates");
      check(!ctx->ConsumeInputEdit("aaaa", epoch, &edit), "edit reused");
    }
  });
  unsigned option_notifications = 0;
  c.option_update_notifier().connect([&](rime::Context*, const std::string&) { ++option_notifications; });
  auto option_epoch = c.composition_epoch();
  c.set_option("unknown_probe", false);
  check(c.composition_epoch() == option_epoch && option_notifications == 1, "unset false semantics");
  c.set_option("unknown_probe", true);
  check(c.composition_epoch() == ++option_epoch && option_notifications == 2, "unknown true change");
  c.set_option("unknown_probe", true);
  check(c.composition_epoch() == option_epoch && option_notifications == 3, "same true notification");
  c.set_option("unknown_probe", false);
  check(c.composition_epoch() == ++option_epoch && option_notifications == 4, "unknown false change");
  c.set_option("ascii_mode", true);
  check(c.composition_epoch() == ++option_epoch && option_notifications == 5, "ascii true change");
  c.set_option("ascii_mode", false);
  check(c.composition_epoch() == ++option_epoch && option_notifications == 6, "ascii false change");
  epoch = c.composition_epoch();
  c.set_caret_pos(2);
  notifications = 0;
  check(!c.DeleteInput(std::numeric_limits<size_t>::max()), "overflow delete accepted");
  check(c.input() == "aaaa" && c.composition_epoch() == epoch && notifications == 0, "overflow delete mutated context");
  c.PushInput(std::string()); c.PopInput(0); c.DeleteInput(0);
  check(c.input() == "aaaa" && c.composition_epoch() == epoch && notifications == 0, "no-op changed epoch or notified");
  mode = 3; c.PushInput('a'); mode = 0;
  c.set_input("aaaa"); c.set_caret_pos(4); epoch = c.composition_epoch();
  mode = 1; nested_consumed = outer_consumed = false; c.PushInput('a'); mode = 0;
  check(!nested_consumed && !outer_consumed, "caret reentry inherited stale edit");
  c.set_input("aaaa"); c.set_caret_pos(4); epoch = c.composition_epoch();
  mode = 2; nested_consumed = outer_consumed = false; c.PushInput('a'); mode = 0;
  check(nested_consumed && !outer_consumed && c.input() == "aaaaab", "nested edit reuse");
  rime::Context::InputEdit stale;
  check(!c.ConsumeInputEdit("aaaa", epoch, &stale), "event survived notifier scope");
  epoch = c.composition_epoch();
  c.set_input(c.input());
  check(c.composition_epoch() != epoch && !c.ConsumeInputEdit("aaaaab", epoch, &stale), "unknown same-input replacement trusted");
  std::cout << "PASS real Context no-op, one-shot and nested observers\n";
}
