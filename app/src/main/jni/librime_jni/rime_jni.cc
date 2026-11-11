// Xime Rime JNI 接口
// 基于 trime 的实现

#include <rime_api.h>
#include <rime/setup.h>
#include <rime/dict/reverse_lookup_dictionary.h>
#include <rime/dict/dictionary.h>
#include <rime/dict/user_dictionary.h>
#include <rime/ticket.h>
#include <rime/service.h>
#include <rime/schema.h>
#include <rime/config/config_data.h>
#include <rime/context.h>
#include <rime/candidate.h>
#include <rime/menu.h>
#include <rime/gear/translator_commons.h>
#include "candidate_policy.h"
#include "pinyin_syllable_selection.h"
#include "t9_processor.h"
#include "t9_patch_utils.h"
#include "t9_digit_userdict.h"
#include "t9_suppression.h"
#include "t9_sentence_scorer.h"
#include <jni.h>
#include <android/log.h>
#include <memory>
#include <sstream>
#include <string>
#include <vector>
#include <unistd.h>  // for usleep
#include <cstring>   // for strcmp
#include <utility>   // for std::pair
#include <ctime>     // for time
#include <signal.h>  // native crash 捕获
#include <fcntl.h>
#include <sys/stat.h>

#define LOG_TAG "XimeRime"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// 打字/输入高频路径的 LOGI/LOGD 写 logcat 会产生大量格式化+IPC 开销，
// 是前台耗电偏高的主因。
// 两级控制：
//   1) 编译期：RIME_JNI_VERBOSE_LOGGING 由 CMake 按构建类型定义
//      （Debug=1, Release=0）。Release 下宏展开为空语句，零开销。
//   2) 运行时：Debug 构建保留运行时开关 g_rime_jni_verbose_logging，
//      Kotlin 可通过 nativeSetVerboseLogging 手动切换，开发时不用重编。
#ifndef RIME_JNI_VERBOSE_LOGGING
#define RIME_JNI_VERBOSE_LOGGING 0
#endif
#if RIME_JNI_VERBOSE_LOGGING == 1
static volatile bool g_rime_jni_verbose_logging = false;
#define LOGI(...) do { if (g_rime_jni_verbose_logging) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__); } while (0)
#define LOGD(...) do { if (g_rime_jni_verbose_logging) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__); } while (0)
#else
#define LOGI(...) ((void)0)
#define LOGD(...) ((void)0)
#endif

extern void rime_require_module_lua();
extern void rime_require_module_octagram();
extern void rime_require_module_predict();
extern void rime_require_module_t9();

static void declare_librime_module_dependencies() {
  rime_require_module_lua();
  rime_require_module_octagram();
  rime_require_module_predict();
  rime_require_module_t9();
}

struct ProcessResult {
    bool processed = false;
    std::string committedText;
    std::string inputText;
    std::string preeditText;
    std::vector<std::pair<std::string, std::string>> candidates;
    // 与 candidates 同序：该候选是用户以前打过的词（候选栏右上角标记）。
    std::vector<bool> learned;
    // 与 candidates 同序：纠错候选逐音节的“实际敲的=正确拼音”，非纠错候选为空。
    std::vector<std::string> corrections;
    bool isAsciiMode = false;
    bool hasNextPage = false;
    bool hasPrevPage = false;
    // T9 快照（仅 T9 会话活跃时填充）：左侧面板状态 + 首音节候选（"pinyin|len,..."）。
    // 合并进 getProcessResult 一次返回，避免 Kotlin 侧重复 JNI 取数（每键 5→3 次）。
    std::string t9PanelState;
    std::string t9SyllableOptions;
};

struct CompositionResult {
    std::string input;
    std::string preedit;
    std::string committedText;
    std::vector<std::pair<std::string, std::string>> candidates;
    std::vector<bool> learned;
    // 与 candidates 同序：纠错候选逐音节的“实际敲的=正确拼音”，非纠错候选为空。
    std::vector<std::string> corrections;
    bool isAsciiMode = false;
    bool hasNextPage = false;
    bool hasPrevPage = false;
};

// Rime 单例类
class Rime {
public:
    Rime() : rime(rime_get_api()) {}
    Rime(Rime const&) = delete;
    void operator=(Rime const&) = delete;

    static Rime& Instance() {
        static Rime instance;
        return instance;
    }

    void startup(const char* user_data_dir, const char* shared_data_dir) {
        if (!rime) {
            LOGE("Rime API not available");
            return;
        }

        declare_librime_module_dependencies();

        user_data_dir_ = user_data_dir;
        shared_data_dir_ = shared_data_dir;

        std::string log_dir = std::string(user_data_dir) + "/logs";
        
        RIME_STRUCT(RimeTraits, traits);
        traits.shared_data_dir = shared_data_dir;
        traits.user_data_dir = user_data_dir;
        traits.log_dir = log_dir.c_str();
        traits.min_log_level = 1;
        traits.app_name = "rime.kime";
        traits.distribution_name = "Xime";
        traits.distribution_code_name = "kime";
        traits.distribution_version = "1.0.0";

        LOGI("Setting up Rime with shared_data_dir=%s, user_data_dir=%s, log_dir=%s", 
             shared_data_dir, user_data_dir, log_dir.c_str());
        
        rime->setup(&traits);
        LOGI("Rime setup completed");
        
        rime->initialize(&traits);
        cyime::InitializeCandidatePolicy(user_data_dir_);
        LOGI("Rime initialize completed");
        initialized_ = true;
        
        // NOTE: start_maintenance 不再在 startup 中调用。
        // 从 Kotlin 侧根据词库文件是否已存在，按需调用 startMaintenance()。
        // 避免每次切换输入法时都触发 librime 的部署流程。
    }

    Bool startMaintenance(bool full) {
        if (!rime) {
            LOGE("startMaintenance: rime not available");
            return false;
        }
        LOGI("Starting maintenance (full=%s)...", full ? "true" : "false");
        Bool result = rime->start_maintenance(full);
        if (!result) {
            LOGE("startMaintenance FAILED: rime->start_maintenance() returned false");
        }
        return result;
    }

    bool createSession() {
        if (!rime) return false;
        session_id_ = rime->create_session();
        if (session_id_ != 0) {
            LOGI("Session created: %lu", (unsigned long)session_id_);
            ensureCandidateComments();
            // 会话重建后的初始 Schema 可能读到部署产物里的旧 page_size
            //（方案自带/第三方为 PC 默认 5），重新对齐到 app 覆盖值
            reapplyPageSizeIfNeeded();
        } else {
            LOGD("Session creation failed (engine may be maintaining)");
        }
        return session_id_ != 0;
    }

    bool hasSession() {
        return session_id_ != 0;
    }

    bool isMaintaining() {
        if (!rime) return false;
        // librime API 使用 is_maintenance_mode
        return rime->is_maintenance_mode();
    }

    std::string getCurrentSchema() {
        if (!rime || !session_id_) return "";
        
        // get_current_schema 需要 buffer 和 buffer_size 参数
        char buffer[256];
        if (rime->get_current_schema(session_id_, buffer, sizeof(buffer))) {
            return std::string(buffer);
        }
        return "";
    }

    bool processKey(int keycode, int mask) {
        if (!rime || !session_id_) {
            LOGE("processKey: rime or session not available");
            return false;
        }
        LOGD("processKey: keycode=%d, mask=%d", keycode, mask);
        ensureCandidateComments();
        // Stop obsolete inference before decoding the next soft-key edit,
        // rather than letting it compete until the new candidate batch exists.
        if (mask == 0 && ((keycode >= '2' && keycode <= '9') || keycode == 0xff08))
            rime::T9CancelSentenceScores();
        bool result = rime->process_key(session_id_, keycode, mask);
        LOGD("processKey result: %d", result);
        return result;
    }

    // Publish the new composition input before the (possibly expensive) candidate page
    // is read. Kotlin's getInput() falls back to this snapshot while the engine lock is
    // held, so candidate rendering cannot delay the observable key response.
    void publishInput(JNIEnv* env, jobject thiz) {
        if (!rime || !session_id_) return;
        jclass cls = env->GetObjectClass(thiz);
        if (!cls) return;
        jfieldID fid = env->GetFieldID(cls, "publishedInput", "Ljava/lang/String;");
        if (fid) {
            const char* input = rime->get_input(session_id_);
            jstring value = env->NewStringUTF(input ? input : "");
            if (value) {
                env->SetObjectField(thiz, fid, value);
                env->DeleteLocalRef(value);
            }
        } else if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        env->DeleteLocalRef(cls);
    }

    ProcessResult processKeyAndGetResult(int keycode, int mask, JNIEnv* env, jobject thiz) {
        ProcessResult result;
        result.processed = false;
        result.isAsciiMode = false;
        result.hasNextPage = false;
        result.hasPrevPage = false;

        if (!rime || !session_id_) {
            LOGE("processKeyAndGetResult: rime or session not available");
            return result;
        }

        ensureCandidateComments();
        result.processed = rime->process_key(session_id_, keycode, mask);
        publishInput(env, thiz);
        readCurrentState(result);
        return result;
    }

    // Both the legacy key entry and the batched key/snapshot entry must request
    // annotations before decoding. UI visibility never changes engine output.
    void ensureCandidateComments() {
        if (!rime->get_option(session_id_, "_cyime_candidate_comments")) {
            rime->set_option(session_id_, "_cyime_candidate_comments", true);
        }
        // Incognito is a transient option: a recreated session must regain it here too.
        if ((rime->get_option(session_id_, "_cyime_incognito") != 0) != incognito_) {
            rime->set_option(session_id_, "_cyime_incognito", incognito_ ? True : False);
        }
    }

    void setIncognito(bool on) {
        incognito_ = on;
        if (rime && session_id_) rime->set_option(session_id_, "_cyime_incognito", on ? True : False);
    }

    ProcessResult readResult(bool processed) {
        ProcessResult result;
        result.processed = processed;
        result.isAsciiMode = false;
        result.hasNextPage = false;
        result.hasPrevPage = false;
        if (!rime || !session_id_) {
            LOGE("readResult: rime or session not available");
            return result;
        }
        readCurrentState(result);
        return result;
    }

    void readCurrentState(ProcessResult& result) {
        RIME_STRUCT(RimeCommit, commit);
        if (rime->get_commit(session_id_, &commit)) {
            result.committedText = commit.text ? commit.text : "";
            rime->free_commit(&commit);
        }

    RIME_STRUCT(RimeContext, context);
    if (rime->get_context(session_id_, &context)) {
        const char* input = rime->get_input(session_id_);
        result.inputText = input ? input : "";
        result.preeditText = context.composition.preedit ?
            context.composition.preedit : "";
        LOGI("readCurrentState: input='%s' preedit='%s' num_candidates=%d", result.inputText.c_str(), result.preeditText.c_str(), context.menu.num_candidates);
        if (context.menu.num_candidates > 0) {
            for (int i = 0; i < context.menu.num_candidates; ++i) {
                const char* text = context.menu.candidates[i].text;
                const char* comment = context.menu.candidates[i].comment;
                LOGI("Candidate[%d]: text='%s' comment='%s'", i, text ? text : "", comment ? comment : "");
                result.candidates.push_back(std::make_pair(
                    text ? text : "",
                    comment ? comment : ""
                ));
            }
            result.learned = learnedMarks(context.menu.page_no * context.menu.page_size, context.menu.num_candidates);
            result.corrections = correctionMarks(context.menu.page_no * context.menu.page_size, context.menu.num_candidates);
        }
        result.hasNextPage = !context.menu.is_last_page;
        result.hasPrevPage = context.menu.page_no > 0;
        rime->free_context(&context);
    } else {
        const char* input = rime->get_input(session_id_);
        result.inputText = input ? input : "";
        result.preeditText = "";
    }

        RIME_STRUCT(RimeStatus, status);
        if (rime->get_status(session_id_, &status)) {
            result.isAsciiMode = status.is_ascii_mode;
            rime->free_status(&status);
        }

        // ── T9 快照（一次 JNI 返回左栏状态 + 首音节候选）──
        // 仅在 T9 处理器活跃时填充；非 T9 场景保持空字符串，Kotlin 侧用默认值兜底。
        rime::T9Processor* t9proc = rime::T9ProcessorRequire();
        if (t9proc) {
            result.t9PanelState = t9proc->GetLeftPanelState();
            // 从面板状态解析当前数字段（第 5 段，已内含分词键锁定/unassigned/选择回退），
            // 为空时回退到 input 中的纯数字（无分隔符的常规输入）。
            std::string panelDigits;
            {
                const std::string& s = result.t9PanelState;
                size_t p = 0;
                for (int i = 0; i < 4 && p != std::string::npos; ++i) {
                    p = s.find(';', p + 1);
                }
                if (p != std::string::npos) {
                    size_t end = s.find(';', p + 1);
                    panelDigits = s.substr(p + 1, end - p - 1);
                }
            }
            if (panelDigits.empty()) {
                for (char c : result.inputText) {
                    if (c >= '0' && c <= '9') panelDigits += c;
                }
            }
            std::vector<std::string> options;
            t9proc->GetFirstSyllableOptions(panelDigits, 20, options);
            std::string joined;
            for (size_t i = 0; i < options.size(); ++i) {
                if (i > 0) joined += ",";
                joined += options[i];
            }
            result.t9SyllableOptions = joined;
        }
    }

    std::string conversionPreview() {
        if (!rime || !session_id_) return "";
        RIME_STRUCT(RimeContext, context);
        if (!rime->get_context(session_id_, &context)) return "";
        std::string text = context.commit_text_preview ? context.commit_text_preview : "";
        rime->free_context(&context);
        return text;
    }

    bool highlightCandidate(int index) {
        return rime && session_id_ && index >= 0 && rime->highlight_candidate(session_id_, index);
    }

    // Editable suffix only: confirmed segments stay owned by Rime and are never replayed.
    std::vector<std::string> pinyinEditSnapshot() {
        auto session = rime::Service::instance().GetSession(static_cast<rime::SessionId>(session_id_));
        if (!session || !session->context()) return {};
        auto ctx = session->context();
        auto confirmed = ctx->composition().GetConfirmedPosition();
        std::string confirmed_text;
        for (const auto& segment : ctx->composition()) {
            if (segment.end > confirmed || segment.status < rime::Segment::kSelected) break;
            if (auto candidate = segment.GetSelectedCandidate()) confirmed_text += candidate->text();
        }
        return {ctx->input(), std::to_string(ctx->caret_pos()), std::to_string(confirmed),
                confirmed_text, ctx->GetPreedit().text};
    }

    void setCaret(int caret) {
        if (rime && session_id_) rime->set_caret_pos(session_id_, static_cast<size_t>(std::max(0, caret)));
    }

    std::vector<std::string> pinyinSyllableOptions() {
        auto session = rime::Service::instance().GetSession(static_cast<rime::SessionId>(session_id_));
        if (!session || !session->context()) return {};
        auto dictionary = cyime::pinyin_selection::OpenDictionary(session->schema());
        if (!dictionary) return {};
        const auto options = cyime::pinyin_selection::Options(
            session->context(), session->schema(), dictionary.get(), session->engine());
        std::vector<std::string> result;
        result.reserve(options.size());
        for (const auto& option : options) {
            result.push_back(std::to_string(option.syllable) + "\t" + option.pinyin + "\t" +
                std::to_string(option.start) + "\t" + std::to_string(option.end) + "\t" +
                (option.selected ? "1" : "0"));
        }
        return result;
    }

    bool selectPinyinSyllable(const std::string& expectedInput, const std::string& expectedSchema,
                              int start, int end, int syllable) {
        if (start < 0 || end <= start || syllable < 0) return false;
        auto session = rime::Service::instance().GetSession(static_cast<rime::SessionId>(session_id_));
        return session && cyime::pinyin_selection::Select(session->context(), session->schema(),
            expectedInput, expectedSchema, static_cast<size_t>(start),
            static_cast<size_t>(end), static_cast<rime::SyllableId>(syllable), session->engine());
    }

    bool setInput(const char* input) {
        if (!rime || !session_id_) {
            LOGE("setInput: rime or session not available");
            return false;
        }
        if (!input || strlen(input) == 0) {
            LOGD("setInput: empty input, clearing composition");
            rime->clear_composition(session_id_);
            return true;
        }
        LOGD("setInput: '%s'", input);
        ensureCandidateComments();
        return rime->set_input(session_id_, input);
    }

    CompositionResult getComposition() {
        CompositionResult result;
        if (!rime || !session_id_) {
            LOGE("getComposition: rime or session not available");
            return result;
        }

        // 1. raw input
        const char* input = rime->get_input(session_id_);
        result.input = input ? input : "";

        // 2. context: preedit + candidates + pagination
        RIME_STRUCT(RimeContext, context);
        if (rime->get_context(session_id_, &context)) {
            if (context.composition.preedit) {
                result.preedit = context.composition.preedit;
            }
            LOGI("getComposition: input='%s' num_candidates=%d", result.input.c_str(), context.menu.num_candidates);
            for (int i = 0; i < context.menu.num_candidates; ++i) {
                const char* text = context.menu.candidates[i].text;
                const char* comment = context.menu.candidates[i].comment;
                LOGI("Candidate[%d]: text='%s' comment='%s'", i, text ? text : "", comment ? comment : "");
                result.candidates.push_back(std::make_pair(
                    text ? text : "",
                    comment ? comment : ""
                ));
            }
            result.learned = learnedMarks(context.menu.page_no * context.menu.page_size, context.menu.num_candidates);
            result.corrections = correctionMarks(context.menu.page_no * context.menu.page_size, context.menu.num_candidates);
            result.hasNextPage = !context.menu.is_last_page;
            result.hasPrevPage = context.menu.page_no > 0;
            rime->free_context(&context);
        }

        // Snapshot reads never consume commit effects. Only command results / commit() do.

        // 4. status: ascii mode
        RIME_STRUCT(RimeStatus, status);
        if (rime->get_status(session_id_, &status)) {
            result.isAsciiMode = status.is_ascii_mode;
            rime->free_status(&status);
        }

        return result;
    }

    const char* getInput() {
        if (!rime || !session_id_) return "";
        const char* input = rime->get_input(session_id_);
        LOGD("getInput: '%s'", input ? input : "(null)");
        return input ? input : "";
    }

    void getCandidates(std::vector<std::string>& candidates) {
        if (!rime || !session_id_) return;
        
        RIME_STRUCT(RimeContext, context);
        if (rime->get_context(session_id_, &context)) {
            LOGD("getCandidates: num_candidates=%d", context.menu.num_candidates);
            if (context.menu.num_candidates > 0) {
                for (int i = 0; i < context.menu.num_candidates; ++i) {
                    const char* text = context.menu.candidates[i].text;
                    LOGD("Candidate %d: '%s'", i, text ? text : "(null)");
                    candidates.push_back(text ? text : "");
                }
            }
            rime->free_context(&context);
        } else {
            LOGD("getCandidates: no context available");
        }
    }

    void getCandidatesWithComments(std::vector<std::pair<std::string, std::string>>& candidates) {
        if (!rime || !session_id_) return;
        
        RIME_STRUCT(RimeContext, context);
        if (rime->get_context(session_id_, &context)) {
            LOGD("getCandidatesWithComments: num_candidates=%d", context.menu.num_candidates);
            if (context.menu.num_candidates > 0) {
                for (int i = 0; i < context.menu.num_candidates; ++i) {
                    const char* text = context.menu.candidates[i].text;
                    const char* comment = context.menu.candidates[i].comment;
                    candidates.push_back(std::make_pair(
                        text ? text : "",
                        comment ? comment : ""
                    ));
                }
            }
            rime->free_context(&context);
        }
    }

    // A candidate the user typed before: a whole dictionary entry (not a sentence assembled from
    // several words) of two or more characters that the user dictionary has committed.
    std::vector<bool> learnedMarks(size_t offset, size_t count) {
        std::vector<bool> marks(count, false);
        auto session = rime::Service::instance().GetSession(static_cast<rime::SessionId>(session_id_));
        if (!session || session->context()->composition().empty()) return marks;
        auto menu = session->context()->composition().back().menu;
        if (!menu) return marks;
        for (size_t i = 0; i < count && offset + i < menu->candidate_count(); ++i) {
            auto genuine = rime::Candidate::GetGenuineCandidate(menu->GetCandidateAt(offset + i));
            const rime::DictEntry* entry = nullptr;
            if (auto sentence = rime::As<rime::Sentence>(genuine)) {
                if (sentence->components().size() == 1) entry = &sentence->components().front();
            } else if (auto phrase = rime::As<rime::Phrase>(genuine)) {
                entry = &phrase->entry();
            }
            size_t chars = 0;
            if (entry) for (unsigned char c : entry->text) chars += (c & 0xC0) != 0x80;
            // Only the user's own words: a learned word the schema's dictionary already has is an
            // ordinary word with a usage count, and marking those covered most common words (2026-10-30).
            marks[i] = entry && entry->commit_count > 0 && chars >= 2 && !inSystemDictionary(entry->text);
        }
        return marks;
    }

    // Whether the current schema's translator dictionary has this text (its reverse lookup table).
    bool inSystemDictionary(const std::string& text) {
        char schema_id[256] = {0};
        if (!rime || !session_id_ || !rime->get_current_schema(session_id_, schema_id, sizeof(schema_id))) return false;
        if (system_dict_schema_ != schema_id) {
            system_dict_schema_ = schema_id;
            system_dict_.reset();
            RimeConfig config = {0};
            if (rime->schema_open(schema_id, &config)) {
                const char* dict = rime->config_get_cstring(&config, "translator/dictionary");
                auto* rldc = dynamic_cast<rime::ReverseLookupDictionaryComponent*>(
                    rime::ReverseLookupDictionary::Require("reverse_lookup_dictionary"));
                if (dict && rldc) {
                    system_dict_.reset(rldc->Create(dict));
                    if (system_dict_ && !system_dict_->Load()) system_dict_.reset();
                }
                rime->config_close(&config);
            }
        }
        std::string code;
        return system_dict_ && system_dict_->ReverseLookup(text, &code);
    }
    std::string system_dict_schema_;
    std::unique_ptr<rime::ReverseLookupDictionary> system_dict_;

    // A candidate reached through spelling correction: its syllables as "typed=correct" for the
    // corrected ones and "typed" for the rest, so the candidate bar can show how it was fixed.
    std::vector<std::string> correctionMarks(size_t offset, size_t count) {
        std::vector<std::string> marks(count);
        auto session = rime::Service::instance().GetSession(static_cast<rime::SessionId>(session_id_));
        if (!session || session->context()->composition().empty()) return marks;
        auto menu = session->context()->composition().back().menu;
        if (!menu) return marks;
        for (size_t i = 0; i < count && offset + i < menu->candidate_count(); ++i) {
            auto displayed = menu->GetCandidateAt(offset + i);
            auto genuine = rime::Candidate::GetGenuineCandidate(displayed);
            if (auto phrase = rime::As<rime::Phrase>(genuine)) {
                marks[i] = phrase->correction_marks();
                if (displayed->type() == "cyime_omission") {
                    // Keep the corrected syllables highlighted without repeating the whole sentence.
                    std::istringstream syllables(marks[i]);
                    marks[i].clear();
                    for (std::string syllable; syllables >> syllable;) {
                        if (syllable.find('=') == std::string::npos) continue;
                        if (!marks[i].empty()) marks[i] += ' ';
                        marks[i] += syllable;
                    }
                }
            }
        }
        return marks;
    }

    // Diagnostic snapshot: preserve actual engine order, type and score, never infer from text.
    std::vector<std::vector<std::string>> inspectCandidates(size_t limit) {
        std::vector<std::vector<std::string>> rows;
        auto session = rime::Service::instance().GetSession(static_cast<rime::SessionId>(session_id_));
        if (!session || session->context()->composition().empty()) return rows;
        auto menu = session->context()->composition().back().menu;
        if (!menu) return rows;
        menu->Prepare(limit);
        for (size_t i = 0; i < limit && i < menu->candidate_count(); ++i) {
            auto c = menu->GetCandidateAt(i);
            if (!c) break;
            auto genuine = rime::Candidate::GetGenuineCandidate(c);
            auto phrase = rime::As<rime::Phrase>(genuine);
            std::string components;
            if (auto sentence = rime::As<rime::Sentence>(genuine)) {
                for (const auto& entry : sentence->components()) {
                    components += entry.text + ":" + std::to_string(entry.weight) + ":" +
                        std::to_string(entry.quality_len) + ";";
                }
            }
            rows.push_back({c->text(), c->comment(), genuine->type(), std::to_string(c->quality()),
                            std::to_string(i), std::to_string(c->start()), std::to_string(c->end()),
                            phrase ? std::to_string(phrase->weight()) : "",
                            phrase ? phrase->code().ToString() : "", components});
        }
        return rows;
    }

    bool selectCandidate(int index) {
        if (!rime || !session_id_) return false;
        return rime->select_candidate_on_current_page(session_id_, index);
    }

    // 跨页遍历整个候选列表（librime candidate_list 迭代器，与引擎分页无关），
    // 供候选展开页本地分页使用；maxCount 防御超大列表。
    bool getAllCandidates(std::vector<std::pair<std::string, std::string>>& candidates,
                          size_t maxCount, int offset = 0) {
        if (!rime || !session_id_) return false;
        RimeCandidateListIterator iterator;
        if (!rime->candidate_list_from_index(session_id_, &iterator, offset)) return false;
        while (candidates.size() < maxCount && rime->candidate_list_next(&iterator)) {
            const char* text = iterator.candidate.text;
            const char* comment = iterator.candidate.comment;
            candidates.push_back(std::make_pair(
                text ? text : "",
                comment ? comment : ""
            ));
        }
        rime->candidate_list_end(&iterator);
        LOGD("getAllCandidates: collected=%zu (max=%zu)", candidates.size(), maxCount);
        return true;
    }

    // 按候选列表全局索引选词（跨页，与 candidate_list 遍历顺序一致）
    bool selectCandidateByGlobalIndex(int index) {
        if (!rime || !session_id_ || index < 0) {
            LOGD("selectCandidateByGlobalIndex: invalid state, index=%d", index);
            return false;
        }
        bool result = rime->select_candidate(session_id_, static_cast<size_t>(index));
        LOGD("selectCandidateByGlobalIndex: index=%d -> %d", index, result ? 1 : 0);
        return result;
    }

    // 删除当前页候选（标准 C API delete_candidate_on_current_page）：
    // librime 对 Phrase 候选执行 userdb tombstone（UpdateEntry -1），
    // 即自造词/调频词删除；非用户词由 rime 侧自行判定，无副作用。
    bool deleteCandidateOnCurrentPage(int index) {
        if (!rime || !session_id_ || index < 0) return false;
        auto session = rime::Service::instance().GetSession(session_id_);
        if (session && session->context() && !session->context()->composition().empty()) {
            int size = 5;
            session->schema()->config()->GetInt("menu/page_size", &size);
            auto& segment = session->context()->composition().back();
            return deleteCandidateByGlobalIndex(
                (segment.selected_index / std::max(1, size)) * std::max(1, size) + index);
        }
        return rime->delete_candidate_on_current_page(session_id_, index);
    }

    // 按候选列表全局索引删除（跨页，与 candidate_list 遍历顺序一致），
    // 供候选展开页本地分页长按删除自造词。
    bool deleteCandidateByGlobalIndex(int index) {
        if (!rime || !session_id_ || index < 0) {
            LOGD("deleteCandidateByGlobalIndex: invalid state, index=%d", index);
            return false;
        }
        auto session = rime::Service::instance().GetSession(session_id_);
        bool policy = false;
        if (session) session->schema()->config()->GetBool("cyime/candidate_policy", &policy);
        if (policy && session->context() && !session->context()->composition().empty()) {
            auto* context = session->context();
            auto menu = context->composition().back().menu;
            auto candidate = menu ? menu->GetCandidateAt(static_cast<size_t>(index)) : nullptr;
            if (!candidate || !cyime::HideCandidate(candidate->text())) return false;
            // Persist first. Never report success on failed storage, and do not
            // erase unrelated learning entries when the action means "hide".
            context->RefreshNonConfirmedComposition();
            return true;
        }
        bool result = rime->delete_candidate(session_id_, static_cast<size_t>(index));
        LOGD("deleteCandidateByGlobalIndex: index=%d -> %d", index, result ? 1 : 0);
        return result;
    }
    
    // 长按删除 = 忘掉这条候选的学习记录：用户词库里删掉它的记录（次数和权重都不留），
    // 词库里本来就有的词回到原来的位置，自造词消失；以后再选它从头学起。
    // 拼出来的整句没有自己的记录，改为忘掉句里学过的词。所有方案一致，不再屏蔽文字。
    // 返回 0 失败、1 已忘掉（词还在候选里）、2 没有学习记录（排序不变）、3 已忘掉且不再出现（自造词）。
    // Developer tool: takes back what automated typing taught the user dictionary of the current schema
    // (rows "text<TAB>commits"); returns the number of commits taken back, or -1.
    int unlearn(const std::string& rows) {
        if (!rime || !session_id_) return -1;
        auto session = rime::Service::instance().GetSession(static_cast<rime::SessionId>(session_id_));
        if (!session || !session->schema()) return -1;
        auto* dictionaries = rime::Dictionary::Require("dictionary");
        auto* userDictionaries = rime::UserDictionary::Require("user_dictionary");
        if (!dictionaries || !userDictionaries) return -1;
        const rime::Ticket ticket(session->schema(), "translator");
        rime::the<rime::Dictionary> dictionary(dictionaries->Create(ticket));
        rime::the<rime::UserDictionary> userDictionary(userDictionaries->Create(ticket));
        if (!dictionary || !userDictionary || !dictionary->Load() || !userDictionary->Load() ||
            userDictionary->readonly()) return -1;
        int taken = 0;
        size_t start = 0;
        while (start < rows.size()) {
            size_t end = rows.find('\n', start);
            if (end == std::string::npos) end = rows.size();
            const std::string line = rows.substr(start, end - start);
            start = end + 1;
            const auto tab = line.find('\t');
            if (tab == std::string::npos) continue;
            const int n = std::atoi(line.c_str() + tab + 1);
            const int got = userDictionary->Unlearn(line.substr(0, tab), n);
            LOGE("unlearn %s: %d of %d", line.substr(0, tab).c_str(), got, n);
            taken += got;
        }
        if (auto* context = session->context()) context->RefreshNonConfirmedComposition();
        return taken;
    }

    // Developer tool: teaches the current schema's user dictionary words the user wrote elsewhere (his own
    // messages), as if each was picked that many times. Rows "text<TAB>pin yin<TAB>commits"; returns the
    // number of words taught, or -1.
    int learn(const std::string& rows) {
        if (!rime || !session_id_) return -1;
        auto session = rime::Service::instance().GetSession(static_cast<rime::SessionId>(session_id_));
        if (!session || !session->schema()) return -1;
        auto* userDictionaries = rime::UserDictionary::Require("user_dictionary");
        if (!userDictionaries) return -1;
        const rime::Ticket ticket(session->schema(), "translator");
        rime::the<rime::UserDictionary> userDictionary(userDictionaries->Create(ticket));
        if (!userDictionary || !userDictionary->Load() || userDictionary->readonly()) return -1;
        int taught = 0;
        size_t start = 0;
        while (start < rows.size()) {
            size_t end = rows.find('\n', start);
            if (end == std::string::npos) end = rows.size();
            const std::string line = rows.substr(start, end - start);
            start = end + 1;
            const auto tab = line.find('\t');
            const auto tab2 = tab == std::string::npos ? std::string::npos : line.find('\t', tab + 1);
            if (tab2 == std::string::npos) continue;
            const int n = std::atoi(line.c_str() + tab2 + 1);
            if (n <= 0) continue;
            rime::DictEntry entry;
            entry.text = line.substr(0, tab);
            entry.custom_code = line.substr(tab + 1, tab2 - tab - 1) + " ";  // the user db's own key form
            if (userDictionary->UpdateEntry(entry, n)) ++taught;
        }
        if (auto* context = session->context()) context->RefreshNonConfirmedComposition();
        return taught;
    }

    // Developer tool: the current schema's user dictionary as a text snapshot at `file`.
    bool exportUserDict(const std::string& file) {
        if (!rime || !session_id_) return false;
        auto session = rime::Service::instance().GetSession(static_cast<rime::SessionId>(session_id_));
        if (!session || !session->schema()) return false;
        auto* dictionaries = rime::Dictionary::Require("dictionary");
        auto* userDictionaries = rime::UserDictionary::Require("user_dictionary");
        if (!dictionaries || !userDictionaries) return false;
        const rime::Ticket ticket(session->schema(), "translator");
        rime::the<rime::UserDictionary> userDictionary(userDictionaries->Create(ticket));
        if (!userDictionary || !userDictionary->Load()) return false;
        return userDictionary->Export(rime::path(file));
    }

    // Developer tool: puts the current schema's user dictionary back to the snapshot at `file` (made by exportUserDict).
    int restoreUserDict(const std::string& file) {
        if (!rime || !session_id_) return -1;
        auto session = rime::Service::instance().GetSession(static_cast<rime::SessionId>(session_id_));
        if (!session || !session->schema()) return -1;
        auto* userDictionaries = rime::UserDictionary::Require("user_dictionary");
        if (!userDictionaries) return -1;
        const rime::Ticket ticket(session->schema(), "translator");
        rime::the<rime::UserDictionary> userDictionary(userDictionaries->Create(ticket));
        if (!userDictionary || !userDictionary->Load()) return -1;
        return userDictionary->RestoreExact(rime::path(file));
    }

    int forgetCandidate(int index, bool global) {
        if (!rime || !session_id_ || index < 0) return 0;
        auto session = rime::Service::instance().GetSession(static_cast<rime::SessionId>(session_id_));
        if (!session) return 0;
        auto* context = session->context();
        if (!context || context->composition().empty()) return 0;
        auto& segment = context->composition().back();
        if (!segment.menu) return 0;
        const auto pageSize = std::max(1, session->schema()->page_size());
        const size_t nativeIndex = global ? static_cast<size_t>(index) :
            segment.selected_index / pageSize * pageSize + index;
        auto candidate = segment.menu->GetCandidateAt(nativeIndex);
        if (!candidate) return 0;
        auto genuine = rime::Candidate::GetGenuineCandidate(candidate);
        auto phrase = rime::As<rime::Phrase>(genuine);
        if (!phrase) return 2;  // symbols, dates and other generated candidates learn nothing
        auto sentence = rime::As<rime::Sentence>(genuine);
        std::vector<const rime::DictEntry*> learned;
        if (sentence) {
            for (const auto& component : sentence->components())
                if (component.commit_count > 0) learned.push_back(&component);
        } else if (phrase->entry().commit_count > 0) {
            learned.push_back(&phrase->entry());
        }
        // Nothing learned: write nothing (a deletion mark would make the next pick count twice).
        if (learned.empty()) return 2;
        // The same user dictionary the translator learns into (shared through the component pool).
        // Its records are erased, so picking the word again learns from the start.
        auto* dictionaries = rime::Dictionary::Require("dictionary");
        auto* userDictionaries = rime::UserDictionary::Require("user_dictionary");
        const rime::Ticket ticket(session->schema(), "translator");
        rime::the<rime::Dictionary> dictionary(dictionaries ? dictionaries->Create(ticket) : nullptr);
        rime::the<rime::UserDictionary> userDictionary(userDictionaries ? userDictionaries->Create(ticket) : nullptr);
        bool forgotten = false;
        if (dictionary && userDictionary && dictionary->Load() && userDictionary->Load() &&
            !userDictionary->readonly()) {
            userDictionary->Attach(dictionary->primary_table(), dictionary->prism());
            for (const auto* entry : learned) forgotten = userDictionary->Forget(*entry) || forgotten;
        }
        const std::string text = candidate->text();
        if (forgotten) {
            context->RefreshNonConfirmedComposition();
        } else {
            // Not in that dictionary (another translator's word): Rime's own deletion by the owning translator.
            if (sentence && sentence->components().size() > 1) return 0;
            if (!context->DeleteCandidate(nativeIndex)) return 0;
        }
        return stillOffered(context, text) ? 1 : 3;
    }

    // Whether `text` is still a candidate of the refreshed menu: a dictionary word stays, a self-made word is gone.
    static bool stillOffered(rime::Context* context, const std::string& text) {
        if (context->composition().empty() || !context->composition().back().menu) return false;
        const auto& menu = context->composition().back().menu;
        for (size_t i = 0; i < 300; ++i) {
            const auto candidate = menu->GetCandidateAt(i);
            if (!candidate) return false;
            if (candidate->text() == text) return true;
        }
        return false;
    }

    bool reloadSessionAfterMaintenance() {
        if (!rime || rime->is_maintenance_mode()) return false;
        const auto schema = getCurrentSchema();
        if (session_id_) rime->destroy_session(session_id_);
        session_id_ = 0;
        if (!createSession()) return false;
        if (!schema.empty() && schema != getCurrentSchema()) rime->select_schema(session_id_, schema.c_str());
        reapplyPageSizeIfNeeded();
        return true;
    }

    bool refineOmission() {
        if (!rime || !session_id_) return false;
        auto session = rime::Service::instance().GetSession(session_id_);
        std::string schema;
        return session && session->schema()->config()->GetString("cyime_omission_schema", &schema) &&
               cyime::RefineOmission(session->context());
    }

    bool refineT9Sentences() {
        if (!rime || !session_id_ || rime::T9SentenceRefinementState() != 2) return false;
        auto session = rime::Service::instance().GetSession(static_cast<rime::SessionId>(session_id_));
        if (!session || session->context()->input().empty() || session->schema()->schema_id() != "t9_pinyin") return false;
        struct RefinementScope {
            RefinementScope() { rime::T9SetRefiningSentences(true); }
            ~RefinementScope() { rime::T9SetRefiningSentences(false); }
        } scope;
        session->context()->RefreshNonConfirmedComposition();
        return true;
    }

    bool restoreSuppressedCandidate(const std::string& text) {
        if (!rime || !session_id_) return false;
        auto session = rime::Service::instance().GetSession(static_cast<rime::SessionId>(session_id_));
        if (!session) return false;
        if (!rime::GetT9SuppressionStore()->Set("t9_pinyin", text, false)) return false;
        if (session->schema()->schema_id() == "t9_pinyin")
            session->context()->RefreshNonConfirmedComposition();
        return true;
    }

    bool pageDown() {
        if (!rime || !session_id_) return false;
        return rime->process_key(session_id_, 0xFF56, 0);
    }
    
    bool pageUp() {
        if (!rime || !session_id_) return false;
        return rime->process_key(session_id_, 0xFF55, 0);
    }
    
    bool hasNextPage() {
        if (!rime || !session_id_) return false;
        RIME_STRUCT(RimeContext, context);
        if (rime->get_context(session_id_, &context)) {
            bool result = !context.menu.is_last_page;
            rime->free_context(&context);
            return result;
        }
        return false;
    }
    
    bool hasPrevPage() {
        if (!rime || !session_id_) return false;
        RIME_STRUCT(RimeContext, context);
        if (rime->get_context(session_id_, &context)) {
            bool result = context.menu.page_no > 0;
            rime->free_context(&context);
            return result;
        }
        return false;
    }

    std::string commit() {
        std::string result;
        if (!rime || !session_id_) return result;
        
        RIME_STRUCT(RimeCommit, commit);
        if (rime->get_commit(session_id_, &commit)) {
            result = commit.text ? commit.text : "";
            LOGD("commit: '%s'", result.c_str());
            rime->free_commit(&commit);
        }
        return result;
    }

    void clearComposition() {
        if (!rime || !session_id_) return;
        rime::T9CancelSentenceScores();
        rime->clear_composition(session_id_);
    }

    bool toggleAsciiMode() {
        if (!rime || !session_id_) {
            LOGE("toggleAsciiMode: rime or session not available");
            return false;
        }
        
        // 获取当前 ascii_mode 状态
        RIME_STRUCT(RimeStatus, status);
        if (!rime->get_status(session_id_, &status)) {
            LOGE("toggleAsciiMode: failed to get status");
            return false;
        }
        
        bool current_ascii_mode = status.is_ascii_mode;
        rime->free_status(&status);
        
        LOGI("toggleAsciiMode: current ascii_mode=%s", current_ascii_mode ? "true" : "false");
        
        // 切换状态
        bool new_ascii_mode = !current_ascii_mode;
        
        // 使用 set_option 来设置 ascii_mode
        rime->set_option(session_id_, "ascii_mode", new_ascii_mode);
        
        // 验证是否设置成功
        RIME_STRUCT(RimeStatus, new_status);
        if (rime->get_status(session_id_, &new_status)) {
            bool result = new_status.is_ascii_mode == new_ascii_mode;
            LOGI("toggleAsciiMode: new ascii_mode=%s, result=%s",
                 new_status.is_ascii_mode ? "true" : "false",
                 result ? "success" : "failed");
            rime->free_status(&new_status);
            return result;
        }
        
        return true;
    }

    bool isAsciiMode() {
        if (!rime || !session_id_) return false;
        
        RIME_STRUCT(RimeStatus, status);
        if (rime->get_status(session_id_, &status)) {
            bool result = status.is_ascii_mode;
            rime->free_status(&status);
            return result;
        }
        return false;
    }

    bool switchSchema(const char* schema_id) {
        if (!rime || !session_id_) {
            LOGE("switchSchema: rime or session not available");
            return false;
        }
        
        LOGI("switchSchema: switching to '%s'", schema_id);
        
        // 直接切换方案，不验证方案是否存在（get_schema_list 不读 default.custom.yaml 的 patch）
        bool result = rime->select_schema(session_id_, schema_id);
        LOGI("select_schema result: %s", result ? "true" : "false");
        
        if (result) {
            // Non-current schema configs are weakly cached. Apply to the new live schema
            // after select_schema, otherwise its bundled menu/page_size (often 5) wins.
            reapplyPageSizeIfNeeded();
            ensureCandidateComments();
            // 验证切换是否成功
            char current_schema[256];
            if (rime->get_current_schema(session_id_, current_schema, sizeof(current_schema))) {
                LOGI("Current schema after switch: %s", current_schema);
                return strcmp(current_schema, schema_id) == 0;
            }
        }
        
        return result;
    }

    void getAvailableSchemas(std::vector<std::pair<std::string, std::string>>& schemas) {
        if (!rime) return;
        
        RimeSchemaList schema_list = {0};
        if (rime->get_schema_list(&schema_list)) {
            LOGI("Available schemas: %zu", schema_list.size);
            for (size_t i = 0; i < schema_list.size; i++) {
                std::string id = schema_list.list[i].schema_id ? schema_list.list[i].schema_id : "";
                std::string name = schema_list.list[i].name ? schema_list.list[i].name : "";
                schemas.push_back(std::make_pair(id, name));
                LOGI("  Schema %zu: %s (%s)", i, id.c_str(), name.c_str());
            }
            rime->free_schema_list(&schema_list);
        } else {
            LOGD("No schemas available yet (deployment may still be running)");
        }
    }
    
    // 查找词汇的编码
    // 使用 reverse_lookup_dictionary API 反查字符编码
    bool lookupText(const char* text, std::string& outCode) {
        if (!rime || !text) return false;
        LOGD("lookupText: word='%s'", text);
        
        auto* component = rime::ReverseLookupDictionary::Require("reverse_lookup_dictionary");
        if (!component) { LOGD("lookupText: component not available"); return false; }
        auto* rldc = dynamic_cast<rime::ReverseLookupDictionaryComponent*>(component);
        if (!rldc) { LOGD("lookupText: not ReverseLookupDictionaryComponent"); return false; }
        
        // 先查当前 schema 使用的字典
        if (session_id_) {
            char schema_id[256] = {0};
            if (rime->get_current_schema(session_id_, schema_id, sizeof(schema_id))) {
                RimeConfig config = {0};
                if (rime->schema_open(schema_id, &config)) {
                    const char* dict = rime->config_get_cstring(&config, "translator/dictionary");
                    if (dict) {
                        LOGD("lookupText: schema '%s' uses dict '%s'", schema_id, dict);
                        auto d = rldc->Create(dict);
                        if (d && d->Load()) {
                            std::string r;
                            if (d->ReverseLookup(text, &r)) { outCode = r; delete d; rime->config_close(&config); return true; }
                        }
                        delete d;
                    }
                    // 也查 reverse_lookup 字典
                    const char* rev = rime->config_get_cstring(&config, "reverse_lookup/dictionary");
                    if (rev) {
                        LOGD("lookupText: schema '%s' reverse_lookup dict '%s'", schema_id, rev);
                        auto d = rldc->Create(rev);
                        if (d && d->Load()) {
                            std::string r;
                            if (d->ReverseLookup(text, &r)) { outCode = r; delete d; rime->config_close(&config); return true; }
                        }
                        delete d;
                    }
                    rime->config_close(&config);
                }
            }
        }
        
        // fallback: 依次尝试已知编码字典
        const char* fallbacks[] = {"wubi86", "pinyin_simp", nullptr};
        for (int i = 0; fallbacks[i]; i++) {
            auto d = rldc->Create(fallbacks[i]);
            if (!d) continue;
            if (d->Load()) {
                std::string r;
                if (d->ReverseLookup(text, &r)) { outCode = r; delete d; return true; }
            }
            delete d;
        }
        
        return false;
    }
    
    bool deploy() {
        if (!rime) {
            LOGE("deploy: rime not available");
            return false;
        }
        
        LOGI("Starting deployment...");
        
        // 先销毁旧session
        if (session_id_) {
            LOGI("Destroying old session before deployment");
            rime->destroy_session(session_id_);
            session_id_ = 0;
        }
        
        // 删除 installation.yaml 以强制触发完整编译
        // librime 的 RimeStartMaintenance 中 installation_update 任务在
        // 检测到 installation.yaml 已存在且版本匹配时会返回 false，
        // 导致不调度任何编译任务直接返回
        std::string install_yaml(user_data_dir_ + "/installation.yaml");
        if (access(install_yaml.c_str(), F_OK) == 0) {
            LOGI("Removing existing installation.yaml to force full deployment");
            remove(install_yaml.c_str());
        }
        
        rime->start_maintenance(true);
        
        // 等待部署完成（不设超时，大词库编译可能很久）
        int wait_count = 0;
        while (rime->is_maintenance_mode()) {
            usleep(100000);  // 100ms
            wait_count++;
            if (wait_count % 10 == 0) {
                LOGI("Waiting for deployment... (%d seconds)", wait_count / 10);
            }
        }
        
        if (rime->is_maintenance_mode()) {
            LOGE("Deployment timeout!");
            return false;
        }
        
        // 重新创建session
        LOGI("Creating new session after deployment");
        session_id_ = rime->create_session();
        if (!session_id_) {
            LOGE("Failed to create session after deployment");
            return false;
        }
        LOGI("New session created: %lu", (unsigned long)session_id_);
        reapplyPageSizeIfNeeded();

        LOGI("Deployment completed successfully");
        return true;
    }
    
    bool deploySchema(const char* schemaId) {
        if (!rime) {
            LOGE("deploySchema: rime not available");
            return false;
        }
        
        // 确保部署模块已加载（schema_update 等任务注册在 levers 模块中）
        rime::LoadModules(rime::kDeployerModules);
        
        // 构造 .schema.yaml 文件名
        std::string schemaFile(schemaId);
        if (schemaFile.find(".schema.yaml") == std::string::npos) {
            schemaFile += ".schema.yaml";
        }
        
        // 在 user_data_dir 和 shared_data_dir 中查找 schema 文件
        std::string schemaPath;
        std::string userPath = user_data_dir_ + "/" + schemaFile;
        std::string sharedPath = shared_data_dir_ + "/" + schemaFile;
        if (access(userPath.c_str(), F_OK) == 0) {
            schemaPath = userPath;
        } else if (access(sharedPath.c_str(), F_OK) == 0) {
            schemaPath = sharedPath;
        } else {
            LOGE("deploySchema: schema file not found at %s or %s",
                 userPath.c_str(), sharedPath.c_str());
            return false;
        }
        
        LOGI("Deploying single schema: %s", schemaPath.c_str());
        
        // 先销毁旧session
        if (session_id_) {
            rime->destroy_session(session_id_);
            session_id_ = 0;
        }
        
        Bool result = rime->deploy_schema(schemaPath.c_str());
        if (!result) {
            LOGE("deploy_schema failed for: %s", schemaPath.c_str());
            // 回退：启动完整维护等待完成
            rime->start_maintenance(true);
            while (rime->is_maintenance_mode()) {
                usleep(100000);
            }
        }
        
        // 重新创建session
        session_id_ = rime->create_session();
        reapplyPageSizeIfNeeded();
        LOGI("Deploy schema completed: %s", schemaId);
        return true;
    }

    void updateLastBuildTime() {
        if (!rime) return;
        RimeConfig config;
        if (rime->config_open("user", &config)) {
            int now = (int)(time(nullptr));
            rime->config_set_int(&config, "var/last_build_time", now);
            LOGI("Updated last_build_time to %d", now);
            rime->config_close(&config);
        }
    }

    // 把记住的每页候选数覆盖值注入引擎（值来自 app 设置页，非 cpp 写死）：
    // 1) 修改方案配置缓存：schema_open 打开的 ConfigData 由组件按 id 弱引用共享，
    //    此处写入后，之后构造的所有 Schema 都会读到覆盖值；
    // 2) 当前会话正在使用该方案时，幂等重构造 Schema 立即生效——Schema 仅在
    //    构造时读取 menu/page_size（FetchUsefulConfigItems），只改配置对已构造的
    //    Schema 无效。旧实现只有第 1 步，部署/会话重建后无人再调 setPageSize 时
    //    候选数漂移回方案自带值（内置/第三方方案多为 PC 默认 5，不适配手机）。
    void applyPageSizeOverride(const char* schema_id) {
        if (!rime || page_size_override_ <= 0 || !schema_id) return;
        // The caller may pass a pointer owned by the schema that ApplySchema replaces.
        const std::string target_schema_id(schema_id);
        schema_id = target_schema_id.c_str();
        RimeConfig config;
        if (rime->schema_open(schema_id, &config)) {
            rime->config_set_int(&config, "menu/page_size", page_size_override_);
            rime->config_close(&config);
        } else {
            LOGE("applyPageSizeOverride: schema_open failed for '%s'", schema_id);
        }
        if (!session_id_) return;
        auto session = rime::Service::instance().GetSession(
            static_cast<rime::SessionId>(session_id_));
        if (!session) return;
        auto schema = session->schema();
        // 仅当会话正用该方案时幂等刷新（重构造 Schema 读到覆盖值）；
        // 目标方案与当前不同时只写缓存，随后的 switchSchema 自然生效
        if (!schema || schema->schema_id() != schema_id) return;
        // Restarting an editor must not rebuild the same schema, clear its composition,
        // or reload all dictionaries when the effective page size already matches.
        if (schema->page_size() == page_size_override_) return;
        session->ApplySchema(new rime::Schema(target_schema_id));
        LOGI("applyPageSizeOverride: re-applied schema '%s' (menu/page_size=%d)",
             schema_id, page_size_override_);
    }

    // 会话重建（创建/部署）后重新对齐覆盖值；无覆盖值时为空操作
    void reapplyPageSizeIfNeeded() {
        if (page_size_override_ <= 0 || !session_id_ || !rime) return;
        auto session = rime::Service::instance().GetSession(
            static_cast<rime::SessionId>(session_id_));
        if (!session) return;
        auto schema = session->schema();
        if (!schema) return;
        applyPageSizeOverride(schema->schema_id().c_str());
    }

    void setPageSize(const char* schema_id, int page_size) {
        if (!rime) {
            LOGE("setPageSize: rime not available");
            return;
        }
        if (page_size <= 0) return;
        page_size_override_ = page_size;
        applyPageSizeOverride(schema_id);
    }

    void setOption(const char* option, Bool value) {
        if (!rime || !session_id_) {
            LOGE("setOption: rime or session not available");
            return;
        }
        rime->set_option(session_id_, option, value);
        LOGI("setOption: %s = %s", option, value ? "true" : "false");
    }

    void setNeighborMap(const char* value) {
        if (!rime || !session_id_) return;
        char existing[512] = {};
        rime->get_property(session_id_, "cyime_neighbors", existing, sizeof(existing));
        if (std::string(existing) != value) rime->set_property(session_id_, "cyime_neighbors", value);
    }

    void setSpatialMap(const char* value) {
        if (!rime || !session_id_) return;
        rime->set_property(session_id_, "cyime_spatial", value ? value : "");
    }

    // 组词前已上屏的文字（"\x1e" + 文字），九键词组模型用作第一个词的上文；空串表示未知。
    void setT9LeftContext(const std::string& value) {
        if (!rime || !session_id_) return;
        rime->set_property(session_id_, "cyime_left_context", value.c_str());
    }

    Bool getOption(const char* option) {
        if (!rime || !session_id_) {
            LOGE("getOption: rime or session not available");
            return false;
        }
        Bool result = rime->get_option(session_id_, option);
        LOGD("getOption: %s = %s", option, result ? "true" : "false");
        return result;
    }

    void destroy() {
        if (rime) {
            if (session_id_) {
                rime->destroy_session(session_id_);
                session_id_ = 0;
            }
            rime->finalize();
        }
        initialized_ = false;
    }

    // 读取方案配置中的字符串项（schema + custom.yaml patch 合并后的最终值）
    std::string getSchemaString(const char* schema_id, const char* key) {
        if (!rime || !initialized_) {
            LOGE("getSchemaString: rime not initialized");
            return "";
        }
        RimeConfig config;
        if (!rime->schema_open(schema_id, &config)) {
            return "";
        }
        std::string value;
        const char* str = rime->config_get_cstring(&config, key);
        if (str) {
            value = str;
        }
        rime->config_close(&config);
        return value;
    }

    // 读取方案配置中的列表项（schema + custom.yaml patch 合并后的最终值）
    std::vector<std::string> getSchemaList(const char* schema_id, const char* key) {
        std::vector<std::string> items;
        if (!rime || !initialized_) {
            LOGE("getSchemaList: rime not initialized");
            return items;
        }
        RimeConfig config;
        if (!rime->schema_open(schema_id, &config)) {
            return items;
        }
        RimeConfigIterator iter;
        if (rime->config_begin_list(&iter, &config, key)) {
            while (rime->config_next(&iter)) {
                const char* value = rime->config_get_cstring(&config, iter.path);
                if (value) {
                    items.emplace_back(value);
                }
            }
            rime->config_end(&iter);
        }
        rime->config_close(&config);
        return items;
    }

    // 读取 user.yaml 用户状态字符串（user_config 组件 auto_save=true）
    std::string getUserConfigString(const char* key) {
        if (!rime || !initialized_) {
            LOGE("getUserConfigString: rime not initialized");
            return "";
        }
        RimeConfig config;
        if (!rime->user_config_open("user", &config)) {
            return "";
        }
        std::string value;
        const char* str = rime->config_get_cstring(&config, key);
        if (str) {
            value = str;
        }
        rime->config_close(&config);
        return value;
    }

    // 读取 user.yaml 用户状态布尔值
    bool getUserConfigBool(const char* key) {
        if (!rime || !initialized_) return false;
        RimeConfig config;
        if (!rime->user_config_open("user", &config)) return false;
        Bool value = False;
        rime->config_get_bool(&config, key, &value);
        rime->config_close(&config);
        return value == True;
    }

    // 写 user.yaml 用户状态字符串（user_config 组件 auto_save=true）
    bool setUserConfigString(const char* key, const char* value) {
        if (!rime || !initialized_) {
            LOGE("setUserConfigString: rime not initialized");
            return false;
        }
        RimeConfig config;
        if (!rime->user_config_open("user", &config)) return false;
        bool result = rime->config_set_string(&config, key, value);
        rime->config_close(&config);
        return result;
    }

    // 写 user.yaml 用户状态布尔值
    bool setUserConfigBool(const char* key, bool value) {
        if (!rime || !initialized_) return false;
        RimeConfig config;
        if (!rime->user_config_open("user", &config)) return false;
        bool result = rime->config_set_bool(&config, key, value ? True : False);
        rime->config_close(&config);
        return result;
    }

    // 数据目录访问器（供 T9 schema 补丁注入等 JNI 逻辑读取 schema/custom.yaml）
    const std::string& get_user_data_dir() const { return user_data_dir_; }
    const std::string& get_shared_data_dir() const { return shared_data_dir_; }

private:
    RimeApi* rime;
    RimeSessionId session_id_ = 0;
    bool incognito_ = false;
    std::string user_data_dir_;
    std::string shared_data_dir_;
    bool initialized_ = false;
    // app 设置的每页候选数覆盖值（<=0 表示未设置）；会话重建后由
    // reapplyPageSizeIfNeeded 重新对齐，保证不被方案自带值（PC 默认 5）漂移
    int page_size_override_ = 0;
};

extern "C" {

static jclass gRimeProcessResultClass = nullptr;
static jmethodID gRimeProcessResultCtor = nullptr;
static jclass gRimeCompositionClass = nullptr;
static jmethodID gRimeCompositionCtor = nullptr;
static jclass gRimeCandidateClass = nullptr;
static jmethodID gRimeCandidateCtor = nullptr;

static void ensureJniCache(JNIEnv* env) {
    if (!gRimeCandidateClass) {
        jclass cls = env->FindClass("com/kingzcheung/xime/rime/RimeCandidate");
        gRimeCandidateClass = (jclass)env->NewGlobalRef(cls);
        gRimeCandidateCtor = env->GetMethodID(gRimeCandidateClass, "<init>",
            "(Ljava/lang/String;Ljava/lang/String;ZLjava/lang/String;)V");
        env->DeleteLocalRef(cls);
    }
    if (!gRimeProcessResultClass) {
        jclass cls = env->FindClass("com/kingzcheung/xime/rime/RimeProcessResult");
        gRimeProcessResultClass = (jclass)env->NewGlobalRef(cls);
        gRimeProcessResultCtor = env->GetMethodID(gRimeProcessResultClass, "<init>",
            "(ZLjava/lang/String;Ljava/lang/String;Ljava/lang/String;[Lcom/kingzcheung/xime/rime/RimeCandidate;ZZZLjava/lang/String;Ljava/lang/String;)V");
        env->DeleteLocalRef(cls);
    }
    if (!gRimeCompositionClass) {
        jclass cls = env->FindClass("com/kingzcheung/xime/rime/RimeComposition");
        gRimeCompositionClass = (jclass)env->NewGlobalRef(cls);
        gRimeCompositionCtor = env->GetMethodID(gRimeCompositionClass, "<init>",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;[Lcom/kingzcheung/xime/rime/RimeCandidate;ZZZ)V");
        env->DeleteLocalRef(cls);
    }
}

// 运行时切换 verbose 日志（仅 Debug 构建生效，Release 下为空操作）
JNIEXPORT void JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeSetVerboseLogging(
    JNIEnv* env,
    jobject thiz,
    jboolean enabled
) {
#if RIME_JNI_VERBOSE_LOGGING == 1
    g_rime_jni_verbose_logging = enabled;
#else
    (void)env;
    (void)thiz;
    (void)enabled;
#endif
}

// 初始化 Rime 引擎
JNIEXPORT void JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeInitialize(
    JNIEnv* env,
    jobject thiz,
    jstring user_data_dir,
    jstring shared_data_dir
) {
    const char* user_dir = env->GetStringUTFChars(user_data_dir, nullptr);
    if (!user_dir) return;
    const char* shared_dir = env->GetStringUTFChars(shared_data_dir, nullptr);
    if (!shared_dir) { env->ReleaseStringUTFChars(user_data_dir, user_dir); return; }
    
    LOGI("Initializing Rime engine with user_dir=%s, shared_dir=%s", user_dir, shared_dir);
    Rime::Instance().startup(user_dir, shared_dir);

    // 初始化 T9 数字序列用户词典持久化路径（全局唯一，不依赖 schema 部署）。
    // SetFilePath 内部 loaded_ 标志防重复 Open。
    {
        static bool t9_db_initialized = false;
        if (!t9_db_initialized) {
            rime::T9DigitUserDict::SetFilePath(
                std::string(user_dir) + "/t9_digit.userdb");
            t9_db_initialized = true;
        }
    }

    env->ReleaseStringUTFChars(user_data_dir, user_dir);
    env->ReleaseStringUTFChars(shared_data_dir, shared_dir);
}

// 创建会话（startup 只初始化引擎，session 延迟创建）
JNIEXPORT jboolean JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeCreateSession(
    JNIEnv* env,
    jobject thiz
) {
    return Rime::Instance().createSession() ? JNI_TRUE : JNI_FALSE;
}

// 检查会话是否存在
JNIEXPORT jboolean JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeHasSession(
    JNIEnv* env,
    jobject thiz
) {
    return Rime::Instance().hasSession() ? JNI_TRUE : JNI_FALSE;
}

// 检查是否正在维护
JNIEXPORT jboolean JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeIsMaintaining(
    JNIEnv* env,
    jobject thiz
) {
    return Rime::Instance().isMaintaining() ? JNI_TRUE : JNI_FALSE;
}

// 获取当前方案
JNIEXPORT jstring JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeGetCurrentSchema(
    JNIEnv* env,
    jobject thiz
) {
    std::string schema = Rime::Instance().getCurrentSchema();
    return env->NewStringUTF(schema.c_str());
}

// 处理按键输入
JNIEXPORT jboolean JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeProcessKey(
    JNIEnv* env,
    jobject thiz,
    jint keycode,
    jint mask
) {
    return Rime::Instance().processKey(keycode, mask) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jobject JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeProcessKeyAndGetResult(
    JNIEnv* env,
    jobject thiz,
    jint keycode,
    jint mask
) {
    ensureJniCache(env);

    ProcessResult result = Rime::Instance().processKeyAndGetResult(keycode, mask, env, thiz);

    jobjectArray candidateArray = env->NewObjectArray(
        result.candidates.size(), gRimeCandidateClass, nullptr);

    for (size_t i = 0; i < result.candidates.size(); ++i) {
        jstring text = env->NewStringUTF(result.candidates[i].first.c_str());
        jstring comment = env->NewStringUTF(result.candidates[i].second.c_str());
        jstring correction = env->NewStringUTF(
            i < result.corrections.size() ? result.corrections[i].c_str() : "");
        jobject candidate = env->NewObject(gRimeCandidateClass, gRimeCandidateCtor, text, comment,
            static_cast<jboolean>(i < result.learned.size() && result.learned[i]), correction);
        env->SetObjectArrayElement(candidateArray, i, candidate);
        env->DeleteLocalRef(text);
        env->DeleteLocalRef(comment);
        env->DeleteLocalRef(correction);
        env->DeleteLocalRef(candidate);
    }

    jstring jCommitted = env->NewStringUTF(result.committedText.c_str());
    jstring jInput = env->NewStringUTF(result.inputText.c_str());
    jstring jPreedit = env->NewStringUTF(result.preeditText.c_str());
    jstring jT9Panel = env->NewStringUTF(result.t9PanelState.c_str());
    jstring jT9Options = env->NewStringUTF(result.t9SyllableOptions.c_str());

    jobject jResult = env->NewObject(gRimeProcessResultClass, gRimeProcessResultCtor,
        result.processed ? JNI_TRUE : JNI_FALSE,
        jCommitted,
        jInput,
        jPreedit,
        candidateArray,
        result.isAsciiMode ? JNI_TRUE : JNI_FALSE,
        result.hasNextPage ? JNI_TRUE : JNI_FALSE,
        result.hasPrevPage ? JNI_TRUE : JNI_FALSE,
        jT9Panel,
        jT9Options);

    env->DeleteLocalRef(jCommitted);
    env->DeleteLocalRef(jInput);
    env->DeleteLocalRef(jPreedit);
    env->DeleteLocalRef(jT9Panel);
    env->DeleteLocalRef(jT9Options);
    env->DeleteLocalRef(candidateArray);

    return jResult;
}

JNIEXPORT jobject JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeGetProcessResult(
    JNIEnv* env,
    jobject thiz,
    jboolean processed
) {
    ensureJniCache(env);

    ProcessResult result = Rime::Instance().readResult(processed);

    jobjectArray candidateArray = env->NewObjectArray(
        result.candidates.size(), gRimeCandidateClass, nullptr);

    for (size_t i = 0; i < result.candidates.size(); ++i) {
        jstring text = env->NewStringUTF(result.candidates[i].first.c_str());
        jstring comment = env->NewStringUTF(result.candidates[i].second.c_str());
        jstring correction = env->NewStringUTF(
            i < result.corrections.size() ? result.corrections[i].c_str() : "");
        jobject candidate = env->NewObject(gRimeCandidateClass, gRimeCandidateCtor, text, comment,
            static_cast<jboolean>(i < result.learned.size() && result.learned[i]), correction);
        env->SetObjectArrayElement(candidateArray, i, candidate);
        env->DeleteLocalRef(text);
        env->DeleteLocalRef(comment);
        env->DeleteLocalRef(correction);
        env->DeleteLocalRef(candidate);
    }

    jstring jCommitted = env->NewStringUTF(result.committedText.c_str());
    jstring jInput = env->NewStringUTF(result.inputText.c_str());
    jstring jPreedit = env->NewStringUTF(result.preeditText.c_str());
    jstring jT9Panel = env->NewStringUTF(result.t9PanelState.c_str());
    jstring jT9Options = env->NewStringUTF(result.t9SyllableOptions.c_str());

    jobject jResult = env->NewObject(gRimeProcessResultClass, gRimeProcessResultCtor,
        result.processed ? JNI_TRUE : JNI_FALSE,
        jCommitted,
        jInput,
        jPreedit,
        candidateArray,
        result.isAsciiMode ? JNI_TRUE : JNI_FALSE,
        result.hasNextPage ? JNI_TRUE : JNI_FALSE,
        result.hasPrevPage ? JNI_TRUE : JNI_FALSE,
        jT9Panel,
        jT9Options);

    env->DeleteLocalRef(jCommitted);
    env->DeleteLocalRef(jInput);
    env->DeleteLocalRef(jPreedit);
    env->DeleteLocalRef(jT9Panel);
    env->DeleteLocalRef(jT9Options);
    env->DeleteLocalRef(candidateArray);

    return jResult;
}

JNIEXPORT jstring JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeConversionPreview(JNIEnv* env, jobject) {
    return env->NewStringUTF(Rime::Instance().conversionPreview().c_str());
}
JNIEXPORT jboolean JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeHighlightCandidate(JNIEnv*, jobject, jint index) {
    return Rime::Instance().highlightCandidate(index) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jobjectArray JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativePinyinEditSnapshot(JNIEnv* env, jobject) {
    auto values = Rime::Instance().pinyinEditSnapshot();
    jclass stringClass = env->FindClass("java/lang/String");
    auto result = env->NewObjectArray(values.size(), stringClass, nullptr);
    for (size_t i = 0; i < values.size(); ++i) {
        auto value = env->NewStringUTF(values[i].c_str());
        env->SetObjectArrayElement(result, i, value);
        env->DeleteLocalRef(value);
    }
    env->DeleteLocalRef(stringClass);
    return result;
}

JNIEXPORT void JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeSetCaret(JNIEnv*, jobject, jint caret) {
    Rime::Instance().setCaret(caret);
}

JNIEXPORT jobjectArray JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativePinyinSyllableOptions(JNIEnv* env, jobject) {
    const auto values = Rime::Instance().pinyinSyllableOptions();
    jclass stringClass = env->FindClass("java/lang/String");
    if (!stringClass) return nullptr;
    auto result = env->NewObjectArray(values.size(), stringClass, nullptr);
    if (result) {
        for (size_t i = 0; i < values.size(); ++i) {
            auto value = env->NewStringUTF(values[i].c_str());
            if (!value) break;
            env->SetObjectArrayElement(result, i, value);
            env->DeleteLocalRef(value);
            if (env->ExceptionCheck()) break;
        }
    }
    env->DeleteLocalRef(stringClass);
    return result;
}

JNIEXPORT jboolean JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeSelectPinyinSyllable(
        JNIEnv* env, jobject, jstring expectedInput, jstring expectedSchema,
        jint start, jint end, jint syllableId) {
    if (!expectedInput || !expectedSchema) return JNI_FALSE;
    const char* input = env->GetStringUTFChars(expectedInput, nullptr);
    if (!input) return JNI_FALSE;
    const char* schema = env->GetStringUTFChars(expectedSchema, nullptr);
    if (!schema) {
        env->ReleaseStringUTFChars(expectedInput, input);
        return JNI_FALSE;
    }
    const bool selected = Rime::Instance().selectPinyinSyllable(input, schema, start, end, syllableId);
    env->ReleaseStringUTFChars(expectedSchema, schema);
    env->ReleaseStringUTFChars(expectedInput, input);
    return selected ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeT9ReplaceEditableSuffix(JNIEnv* env, jobject, jstring input) {
    auto processor = rime::T9ProcessorRequire();
    if (!processor || !input) return JNI_FALSE;
    const char* value = env->GetStringUTFChars(input, nullptr);
    if (!value) return JNI_FALSE;
    const bool replaced = processor->ReplaceEditableSuffix(value);
    env->ReleaseStringUTFChars(input, value);
    return replaced ? JNI_TRUE : JNI_FALSE;
}

// 设置输入字符串（替代逐字符 processKey，减少 JNI 调用次数）
JNIEXPORT jboolean JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeSetInput(
    JNIEnv* env,
    jobject thiz,
    jstring input
) {
    const char* input_str = env->GetStringUTFChars(input, nullptr);
    if (!input_str) {
        LOGE("nativeSetInput: null input");
        return JNI_FALSE;
    }
    bool result = Rime::Instance().setInput(input_str);
    env->ReleaseStringUTFChars(input, input_str);
    return result ? JNI_TRUE : JNI_FALSE;
}

// 一次性获取当前 composition 全部信息：input/preedit/commit/candidates/paging/ascii_mode
// 将 updateUI 所需的多次 JNI 查询合并为一次，减少 JNI 往返开销。
JNIEXPORT jobject JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeGetComposition(
    JNIEnv* env,
    jobject thiz
) {
    ensureJniCache(env);

    CompositionResult result = Rime::Instance().getComposition();

    jobjectArray candidateArray = env->NewObjectArray(
        result.candidates.size(), gRimeCandidateClass, nullptr);

    for (size_t i = 0; i < result.candidates.size(); ++i) {
        jstring text = env->NewStringUTF(result.candidates[i].first.c_str());
        jstring comment = env->NewStringUTF(result.candidates[i].second.c_str());
        jstring correction = env->NewStringUTF(
            i < result.corrections.size() ? result.corrections[i].c_str() : "");
        jobject candidate = env->NewObject(gRimeCandidateClass, gRimeCandidateCtor, text, comment,
            static_cast<jboolean>(i < result.learned.size() && result.learned[i]), correction);
        env->SetObjectArrayElement(candidateArray, i, candidate);
        env->DeleteLocalRef(text);
        env->DeleteLocalRef(comment);
        env->DeleteLocalRef(correction);
        env->DeleteLocalRef(candidate);
    }

    jstring jInput = env->NewStringUTF(result.input.c_str());
    jstring jPreedit = env->NewStringUTF(result.preedit.c_str());
    jstring jCommitted = env->NewStringUTF(result.committedText.c_str());

    jobject jComposition = env->NewObject(gRimeCompositionClass, gRimeCompositionCtor,
        jInput,
        jPreedit,
        jCommitted,
        candidateArray,
        result.hasNextPage ? JNI_TRUE : JNI_FALSE,
        result.hasPrevPage ? JNI_TRUE : JNI_FALSE,
        result.isAsciiMode ? JNI_TRUE : JNI_FALSE);

    env->DeleteLocalRef(jInput);
    env->DeleteLocalRef(jPreedit);
    env->DeleteLocalRef(jCommitted);
    env->DeleteLocalRef(candidateArray);

    return jComposition;
}

// 获取候选词列表
JNIEXPORT jobjectArray JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeGetCandidates(
    JNIEnv* env,
    jobject thiz
) {
    std::vector<std::string> candidates;
    Rime::Instance().getCandidates(candidates);
    
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray(candidates.size(), stringClass, nullptr);
    
    for (size_t i = 0; i < candidates.size(); ++i) {
        jstring str = env->NewStringUTF(candidates[i].c_str());
        env->SetObjectArrayElement(result, i, str);
        env->DeleteLocalRef(str);
    }
    
    return result;
}

// 获取候选词列表（包含编码注释）
JNIEXPORT jobjectArray JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeGetCandidatesWithComments(
    JNIEnv* env,
    jobject thiz
) {
    std::vector<std::pair<std::string, std::string>> candidates;
    Rime::Instance().getCandidatesWithComments(candidates);
    
    jclass stringClass = env->FindClass("java/lang/String");
    jclass stringArrayClass = env->FindClass("[Ljava/lang/String;");
    
    jobjectArray result = env->NewObjectArray(candidates.size(), stringArrayClass, nullptr);
    
    for (size_t i = 0; i < candidates.size(); ++i) {
        jobjectArray pair = env->NewObjectArray(2, stringClass, nullptr);
        jstring text = env->NewStringUTF(candidates[i].first.c_str());
        jstring comment = env->NewStringUTF(candidates[i].second.c_str());
        env->SetObjectArrayElement(pair, 0, text);
        env->SetObjectArrayElement(pair, 1, comment);
        env->SetObjectArrayElement(result, i, pair);
        env->DeleteLocalRef(text);
        env->DeleteLocalRef(comment);
        env->DeleteLocalRef(pair);
    }
    
    return result;
}

// 获取输入文本
JNIEXPORT jstring JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeGetInput(
    JNIEnv* env,
    jobject thiz
) {
    return env->NewStringUTF(Rime::Instance().getInput());
}

// 选择候选词
JNIEXPORT jboolean JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeSelectCandidate(
    JNIEnv* env,
    jobject thiz,
    jint index
) {
    return Rime::Instance().selectCandidate(index) ? JNI_TRUE : JNI_FALSE;
}

// 获取全量候选列表（跨页遍历，含编码注释），maxCount 为收集上限
JNIEXPORT jobjectArray JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeGetAllCandidates(
    JNIEnv* env,
    jobject thiz,
    jint maxCount
) {
    std::vector<std::pair<std::string, std::string>> candidates;
    Rime::Instance().getAllCandidates(candidates, static_cast<size_t>(maxCount));

    jclass stringClass = env->FindClass("java/lang/String");
    jclass stringArrayClass = env->FindClass("[Ljava/lang/String;");

    jobjectArray result = env->NewObjectArray(candidates.size(), stringArrayClass, nullptr);

    for (size_t i = 0; i < candidates.size(); ++i) {
        jobjectArray pair = env->NewObjectArray(2, stringClass, nullptr);
        jstring text = env->NewStringUTF(candidates[i].first.c_str());
        jstring comment = env->NewStringUTF(candidates[i].second.c_str());
        env->SetObjectArrayElement(pair, 0, text);
        env->SetObjectArrayElement(pair, 1, comment);
        env->SetObjectArrayElement(result, i, pair);
        env->DeleteLocalRef(text);
        env->DeleteLocalRef(comment);
        env->DeleteLocalRef(pair);
    }

    return result;
}

JNIEXPORT jobjectArray JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeGetCandidateBatch(
    JNIEnv* env,
    jobject thiz,
    jint offset,
    jint maxCount
) {
    std::vector<std::pair<std::string, std::string>> candidates;
    Rime::Instance().getAllCandidates(candidates, static_cast<size_t>(maxCount), offset);

    jclass stringClass = env->FindClass("java/lang/String");
    jclass stringArrayClass = env->FindClass("[Ljava/lang/String;");

    jobjectArray result = env->NewObjectArray(candidates.size(), stringArrayClass, nullptr);

    for (size_t i = 0; i < candidates.size(); ++i) {
        jobjectArray pair = env->NewObjectArray(2, stringClass, nullptr);
        jstring text = env->NewStringUTF(candidates[i].first.c_str());
        jstring comment = env->NewStringUTF(candidates[i].second.c_str());
        env->SetObjectArrayElement(pair, 0, text);
        env->SetObjectArrayElement(pair, 1, comment);
        env->SetObjectArrayElement(result, i, pair);
        env->DeleteLocalRef(text);
        env->DeleteLocalRef(comment);
        env->DeleteLocalRef(pair);
    }

    return result;
}

JNIEXPORT jobjectArray JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeInspectCandidates(JNIEnv* env, jobject, jint limit) {
    auto rows = Rime::Instance().inspectCandidates(static_cast<size_t>(std::max(0, std::min(500, (int)limit))));
    auto strings = env->FindClass("java/lang/String");
    auto arrays = env->FindClass("[Ljava/lang/String;");
    auto result = env->NewObjectArray(rows.size(), arrays, nullptr);
    for (size_t i = 0; i < rows.size(); ++i) {
        auto row = env->NewObjectArray(rows[i].size(), strings, nullptr);
        for (size_t j = 0; j < rows[i].size(); ++j) {
            auto value = env->NewStringUTF(rows[i][j].c_str());
            env->SetObjectArrayElement(row, j, value);
            env->DeleteLocalRef(value);
        }
        env->SetObjectArrayElement(result, i, row);
        env->DeleteLocalRef(row);
    }
    return result;
}

// 按候选列表全局索引选词（跨页，供展开页本地分页点选）
JNIEXPORT jboolean JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeSelectCandidateByGlobalIndex(
    JNIEnv* env,
    jobject thiz,
    jint index
) {
    return Rime::Instance().selectCandidateByGlobalIndex(index) ? JNI_TRUE : JNI_FALSE;
}

// 删除当前页候选（长按候选栏删除自造词）：标准 C API
// delete_candidate_on_current_page，index 为当前页内索引。
JNIEXPORT jboolean JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeDeleteCandidateOnCurrentPage(
    JNIEnv* env,
    jobject thiz,
    jint index
) {
    return Rime::Instance().deleteCandidateOnCurrentPage(index) ? JNI_TRUE : JNI_FALSE;
}

// 按候选列表全局索引删除（跨页，供候选展开页长按删除自造词）
JNIEXPORT jboolean JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeDeleteCandidateByGlobalIndex(
    JNIEnv* env,
    jobject thiz,
    jint index
) {
    return Rime::Instance().deleteCandidateByGlobalIndex(index) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeForgetCandidate(
    JNIEnv*, jobject, jint index, jboolean global) {
    return Rime::Instance().forgetCandidate(index, global);
}

// 旧版本 26 键长按“屏蔽”的候选（cyime-hidden-candidates.txt），只供设置页恢复。
JNIEXPORT jobjectArray JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeGetHiddenCandidates(JNIEnv* env, jobject) {
    const auto words = cyime::HiddenCandidates();
    auto strings = env->FindClass("java/lang/String");
    auto result = env->NewObjectArray(words.size(), strings, nullptr);
    for (size_t i = 0; i < words.size(); ++i) {
        auto text = env->NewStringUTF(words[i].c_str());
        env->SetObjectArrayElement(result, i, text);
        env->DeleteLocalRef(text);
    }
    return result;
}

JNIEXPORT jboolean JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeRestoreHiddenCandidate(JNIEnv* env, jobject, jstring text) {
    if (!text) return JNI_FALSE;
    const char* chars = env->GetStringUTFChars(text, nullptr);
    const bool result = cyime::RestoreCandidate(chars);
    env->ReleaseStringUTFChars(text, chars);
    return result ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeRestoreSuppressedCandidate(
    JNIEnv* env, jobject, jstring text) {
    if (!text) return JNI_FALSE;
    const char* chars = env->GetStringUTFChars(text, nullptr);
    const bool result = Rime::Instance().restoreSuppressedCandidate(chars);
    env->ReleaseStringUTFChars(text, chars);
    return result ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jobjectArray JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeGetSuppressedCandidates(JNIEnv* env, jobject) {
    const auto words = rime::GetT9SuppressionStore()->List("t9_pinyin");
    auto strings = env->FindClass("java/lang/String");
    auto result = env->NewObjectArray(words.size(), strings, nullptr);
    for (size_t i = 0; i < words.size(); ++i) {
        auto text = env->NewStringUTF(words[i].c_str());
        env->SetObjectArrayElement(result, i, text);
        env->DeleteLocalRef(text);
    }
    env->DeleteLocalRef(strings);
    return result;
}

JNIEXPORT jstring JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeT9ScoringStatus(JNIEnv* env, jobject) {
    return env->NewStringUTF(rime::T9ScoringStatus().c_str());
}

// 翻页 - 下一页
JNIEXPORT jboolean JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativePageDown(
    JNIEnv* env,
    jobject thiz
) {
    return Rime::Instance().pageDown() ? JNI_TRUE : JNI_FALSE;
}

// 翻页 - 上一页
JNIEXPORT jboolean JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativePageUp(
    JNIEnv* env,
    jobject thiz
) {
    return Rime::Instance().pageUp() ? JNI_TRUE : JNI_FALSE;
}

// 是否有下一页
JNIEXPORT jboolean JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeHasNextPage(
    JNIEnv* env,
    jobject thiz
) {
    return Rime::Instance().hasNextPage() ? JNI_TRUE : JNI_FALSE;
}

// 是否有上一页
JNIEXPORT jboolean JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeHasPrevPage(
    JNIEnv* env,
    jobject thiz
) {
    return Rime::Instance().hasPrevPage() ? JNI_TRUE : JNI_FALSE;
}

// 提交文本
JNIEXPORT jstring JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeCommit(
    JNIEnv* env,
    jobject thiz
) {
    std::string text = Rime::Instance().commit();
    return env->NewStringUTF(text.c_str());
}

// 清除组合
JNIEXPORT void JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeClearComposition(
    JNIEnv* env,
    jobject thiz
) {
    Rime::Instance().clearComposition();
}

// 切换中英文模式（ascii_mode）
JNIEXPORT jboolean JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeToggleAsciiMode(
    JNIEnv* env,
    jobject thiz
) {
    return Rime::Instance().toggleAsciiMode() ? JNI_TRUE : JNI_FALSE;
}

// 获取当前是否为英文模式
JNIEXPORT jboolean JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeIsAsciiMode(
    JNIEnv* env,
    jobject thiz
) {
    return Rime::Instance().isAsciiMode() ? JNI_TRUE : JNI_FALSE;
}

// 切换输入方案
JNIEXPORT jboolean JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeSwitchSchema(
    JNIEnv* env,
    jobject thiz,
    jstring schema_id
) {
    const char* schema = env->GetStringUTFChars(schema_id, nullptr);
    if (!schema) return JNI_FALSE;
    bool result = Rime::Instance().switchSchema(schema);
    env->ReleaseStringUTFChars(schema_id, schema);
    return result ? JNI_TRUE : JNI_FALSE;
}

// 确保个人词库源文件存在（pack_name 形如 user_t9 → user_t9.dict.yaml）。
// 与 Kotlin PersonalDictManager.DEFAULT_HEADER 的 dict 头保持一致，
// 供 librime 编译 translator/packs 引用的用户词典。
static void EnsureT9PackDictFile(const std::string& user_data_dir,
                                 const std::string& pack_name) {
    if (pack_name.empty()) return;
    std::string path = user_data_dir + "/" + pack_name + ".dict.yaml";
    FILE* f = fopen(path.c_str(), "r");
    if (f) {
        fclose(f);
        return;
    }
    f = fopen(path.c_str(), "w");
    if (!f) {
        LOGE("T9Patches: failed to create pack dict file '%s'", path.c_str());
        return;
    }
    const std::string header =
        "# Rime dict\n"
        "---\n"
        "name: " + pack_name + "\n"
        "version: '1.0'\n"
        "sort: original\n"
        "use_preset_vocabulary: false\n"
        "...\n";
    fwrite(header.c_str(), 1, header.size(), f);
    fclose(f);
    LOGI("T9Patches: created pack dict file '%s'", path.c_str());
}

// 判断个人词库是否尚未编译（build/<pack_name>.table.bin 缺失）。
// 用于「补丁写入后词库未编译 → 需要部署」的幂等判定：
// 编译完成后 table.bin 存在，后续不再触发部署。
static bool T9PackTableBinMissing(const std::string& user_data_dir,
                                  const std::string& pack_name) {
    if (pack_name.empty()) return false;
    std::string path = user_data_dir + "/build/" + pack_name + ".table.bin";
    FILE* f = fopen(path.c_str(), "r");
    if (f) {
        fclose(f);
        return false;
    }
    return true;
}

// 读取文件全文到 out（文件不存在或读取失败返回 false）。
static bool ReadFileToString(const std::string& path, std::string& out) {
    FILE* f = fopen(path.c_str(), "r");
    if (!f) return false;
    char buf[8192];
    size_t n;
    while ((n = fread(buf, 1, sizeof(buf), f)) > 0) {
        out.append(buf, n);
    }
    fclose(f);
    return true;
}

// 从文本中剔除含任一 needle 的行。
static std::string StripLinesContaining(const std::string& text,
                                        const std::vector<std::string>& needles) {
    std::string result;
    size_t start = 0;
    while (start < text.size()) {
        size_t nl = text.find('\n', start);
        if (nl == std::string::npos) nl = text.size();
        std::string line = text.substr(start, nl - start);
        bool drop = false;
        for (const auto& needle : needles) {
            if (line.find(needle) != std::string::npos) {
                drop = true;
                break;
            }
        }
        if (!drop) {
            result += line;
            result += '\n';
        }
        if (nl == text.size()) break;
        start = nl + 1;
    }
    return result;
}

// A dictionary pack is a Rime resource, not necessarily a generated user_* pack.
// Parse the YAML list before applying the legacy malformed-name migration, so
// ordinary named packs, block lists and an explicit empty list remain untouched.
static std::optional<std::vector<std::string>> ConfiguredDictionaryPacks(
    const std::string& yaml) {
    rime::ConfigData config;
    std::istringstream input(yaml);
    if (!config.LoadFromStream(input)) return std::nullopt;
    auto root = rime::As<rime::ConfigMap>(config.root);
    auto patch = root ? rime::As<rime::ConfigMap>(root->Get("patch")) : nullptr;
    auto packs = patch ? rime::As<rime::ConfigList>(patch->Get("translator/packs")) : nullptr;
    if (!packs) return std::nullopt;
    std::vector<std::string> names;
    for (const auto& item : *packs) {
        auto value = rime::As<rime::ConfigValue>(item);
        if (!value || value->str().empty()) return std::nullopt;
        const auto& name = value->str();
        // Only the known broken legacy user_ token is eligible for migration.
        if (name == "user_" || name.find('"') != std::string::npos) return std::nullopt;
        names.push_back(name);
    }
    return names;
}

// 确保 T9 方案 schema 补丁已注入到 custom.yaml（幂等）。
//   - 判定 T9 方案：schema_id 含 "t9"，或 schema.yaml 的 schema_id 字段含 "t9"。
//   - 补齐缺失组件：t9_processor / t9_filter / t9_date_translator /
//     t9/isDisplayOriginalPreedit / t9/enable_date_translator（schema 已有则尊重）。
//   - 修复畸形 translator/packs（个人词库），合法补丁尊重、缺失交由 PersonalDictManager。
// 返回 true 表示写入后词库/补丁尚未编译，调用方可据此触发部署。
static jboolean DoEnsureT9SchemaPatches(
    JNIEnv* env,
    const char* schema,
    const std::string& user_data_dir,
    const std::string& shared_data_dir) {
    // 读 schema.yaml（先 user_data_dir 后 shared_data_dir，兼容 .schema.yaml/.yaml）。
    std::string schema_content;
    for (const auto& ext : {".schema.yaml", ".yaml"}) {
        if (ReadFileToString(user_data_dir + "/" + schema + ext, schema_content) ||
            ReadFileToString(shared_data_dir + "/" + schema + ext, schema_content)) {
            break;
        }
        schema_content.clear();
    }

    // 判定 T9 方案：JNI schema_id 含 "t9"，或 schema.yaml 的 schema_id 字段含 "t9"。
    bool is_t9_schema = false;
    std::string sid(schema);
    for (auto& c : sid) c = static_cast<char>(tolower(c));
    if (sid.find("t9") != std::string::npos) {
        is_t9_schema = true;
    }
    if (!is_t9_schema && !schema_content.empty()) {
        auto pos = schema_content.find("schema_id:");
        if (pos != std::string::npos) {
            auto line_end = schema_content.find('\n', pos);
            auto line = schema_content.substr(
                pos, line_end == std::string::npos ? line_end : line_end - pos);
            for (auto& c : line) c = static_cast<char>(tolower(c));
            if (line.find("t9") != std::string::npos) {
                is_t9_schema = true;
            }
        }
    }
    if (!is_t9_schema) {
        return JNI_FALSE;
    }
    // 未找到 schema 文件 → 方案可能已卸载，不写补丁。
    if (schema_content.empty()) {
        LOGI("T9Patches: schema file for '%s' not found on disk, skip", schema);
        return JNI_FALSE;
    }

    // 读 custom.yaml 已有补丁。
    std::string custom_path = user_data_dir + "/" + schema + ".custom.yaml";
    std::string existing_content;
    ReadFileToString(custom_path, existing_content);

    // 收集需要追加的补丁行（幂等：schema 或 custom 已含该组件则跳过）。
    std::vector<std::string> patch_lines;

    // 方案自带 preedit 管理型 lua filter（如带声调方案的 super_comment_preedit，
    // HasPreeditLuaFilter 判定）→ 默认 isDisplayOriginalPreedit: true（透传，
    // 让方案 lua 管理 preedit）；否则（t9_pinyin 无声调方案）默认 false。
    // schema/custom 显式声明的 isDisplayOriginalPreedit 始终优先（下方补丁跳过）。
    const bool has_schema_preedit_filter =
        rime::t9_patch_utils::HasPreeditLuaFilter(schema_content) ||
        rime::t9_patch_utils::HasPreeditLuaFilter(existing_content);
    if (schema_content.find("t9_processor") == std::string::npos &&
        existing_content.find("t9_processor") == std::string::npos) {
        patch_lines.push_back("  \"engine/processors/@before 0\": t9_processor");
    }
    if (schema_content.find("t9_filter") == std::string::npos &&
        existing_content.find("t9_filter") == std::string::npos) {
        patch_lines.push_back("  \"engine/filters/@before 0\": t9_filter");
    }
    if (schema_content.find("isDisplayOriginalPreedit") == std::string::npos &&
        existing_content.find("isDisplayOriginalPreedit") == std::string::npos) {
        // 透传 true：避免 t9_filter 转换与方案 lua 的 preedit 管理冲突；
        // 默认 false：t9_filter 管理 preedit（无声调方案）。
        const char* preedit_default = has_schema_preedit_filter ? "true" : "false";
        patch_lines.push_back(
            std::string("  \"t9/isDisplayOriginalPreedit\": ") + preedit_default);
    }
    // t9_user_translator 已废弃（自造词调频/召回统一走 librime userdb，
    // 召回由 script_translator 的 UserDictionary 查询承担）：
    // 不再注入新补丁；老设备 custom.yaml 中的历史注入行必须清除，
    // 否则运行时每次会话都报 "error creating translator" 并跳过该组件。
    const bool has_user_translator_patch =
        existing_content.find("t9_user_translator") != std::string::npos;
    // 旧版 t9_date_translator 注入在 @before 0（曾与 user translator 抢占
    // 首位冲突，迁移到 @after 0）。
    const bool legacy_date_before0 =
        existing_content.find("\"engine/translators/@before 0\": t9_date_translator") != std::string::npos;
    if (schema_content.find("t9_date_translator") == std::string::npos &&
        (existing_content.find("t9_date_translator") == std::string::npos ||
         legacy_date_before0)) {
        patch_lines.push_back("  \"engine/translators/@after 0\": t9_date_translator");
    }
    if (schema_content.find("t9/enable_date_translator") == std::string::npos &&
        existing_content.find("t9/enable_date_translator") == std::string::npos) {
        patch_lines.push_back("  \"t9/enable_date_translator\": true");
    }

    // 清理已废弃的 enable_abbrev_recall 产物
    const bool has_derive = existing_content.find("derive/^([a-z])") != std::string::npos;
    const bool has_abbrev_custom = existing_content.find("enable_abbrev_recall") != std::string::npos;
    bool need_remove_derive = has_derive || has_abbrev_custom;

    // translator/packs 个人词库：合法保留 / 畸形修复 / 缺失交由 PersonalDictManager。
    const std::string packs_name = "user_" + rime::t9_patch_utils::SanitizePackName(schema);
    bool need_packs_patch = false;
    std::string actual_pack_name;  // 实际生效的个人词库名（用于词库编译判定）
    const auto configured_packs = ConfiguredDictionaryPacks(existing_content);
    if (configured_packs) {
        if (!configured_packs->empty()) actual_pack_name = configured_packs->front();
    } else if (!packs_name.empty()) {
        switch (rime::t9_patch_utils::EvaluatePacksState(existing_content, &actual_pack_name)) {
          case rime::t9_patch_utils::PacksState::kKeep:
          case rime::t9_patch_utils::PacksState::kMissing:
            break;
          case rime::t9_patch_utils::PacksState::kRepair:
            existing_content = rime::t9_patch_utils::StripPacksLines(existing_content);
            need_packs_patch = true;
            break;
        }
    }
    if (need_packs_patch) {
        patch_lines.push_back("  \"translator/packs\": [\"" + packs_name + "\"]");
    }

    // 无改动 → 仅判断词库是否仍需编译。
    if (patch_lines.empty() && !need_remove_derive && !has_user_translator_patch) {
        const bool need_deploy = T9PackTableBinMissing(user_data_dir, actual_pack_name);
        return need_deploy ? JNI_TRUE : JNI_FALSE;
    }

    // 清理废弃的 enable_abbrev_recall 产物：移除 derive 简拼行和开关行。
    std::string base = existing_content;
    if (need_remove_derive) {
        base = StripLinesContaining(base, {"derive/^([a-z])", "derive/^([zcs]h)", "enable_abbrev_recall"});
    }
    // 迁移旧版 translators/@before 0 的 date 行（与 user 冲突）
    if (legacy_date_before0) {
        base = StripLinesContaining(base, {"engine/translators/@before 0"});
    }
    // 迁移：清除已废弃 t9_user_translator 的历史注入行（组件已从模块移除）。
    if (has_user_translator_patch) {
        base = StripLinesContaining(base, {"t9_user_translator"});
    }

    // 剥离末尾 "..." 与空白，使追加的 patch 被 RIME 正常解析。
    while (!base.empty() && (base.back() == '\n' || base.back() == '\r' || base.back() == ' ')) {
        base.pop_back();
    }
    if (base.size() >= 3 && base.substr(base.size() - 3) == "...") {
        base.resize(base.size() - 3);
        while (!base.empty() && (base.back() == '\n' || base.back() == '\r' || base.back() == ' ')) {
            base.pop_back();
        }
    }

    std::string patch_content;
    for (const auto& line : patch_lines) {
        patch_content += line;
        patch_content += '\n';
    }

    std::string new_content;
    if (base.empty()) {
        new_content = "patch:\n" + patch_content;
    } else if (base.find("\npatch:") != std::string::npos || base.find("patch:") == 0) {
        new_content = base;
        if (new_content.back() != '\n') new_content += '\n';
        new_content += patch_content;
    } else {
        new_content = base;
        if (new_content.back() != '\n') new_content += '\n';
        new_content += "\n\npatch:\n" + patch_content;
    }

    FILE* f = fopen(custom_path.c_str(), "w");
    if (!f) {
        LOGE("T9Patches: failed to write custom.yaml '%s'", custom_path.c_str());
        return JNI_FALSE;
    }
    fwrite(new_content.c_str(), 1, new_content.size(), f);
    fclose(f);

    // 写入合法 packs 补丁后，确保对应个人词库文件存在（否则 librime 编译时 pack 源缺失）。
    if (need_packs_patch) {
        EnsureT9PackDictFile(user_data_dir, packs_name);
        actual_pack_name = packs_name;
    }

    LOGI("T9Patches: applied '%s' (packs=%s)", schema,
         actual_pack_name.c_str());

    // 返回 true 表示词库/补丁尚未编译，调用方可据此触发部署。
    const bool need_deploy =
        T9PackTableBinMissing(user_data_dir, actual_pack_name);
    return need_deploy ? JNI_TRUE : JNI_FALSE;
}

// 引擎已初始化路径：目录取自 Rime::Instance()
JNIEXPORT jboolean JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeEnsureT9SchemaPatches(
    JNIEnv* env,
    jobject thiz,
    jstring schema_id
) {
    const char* schema = env->GetStringUTFChars(schema_id, nullptr);
    if (!schema) return JNI_FALSE;
    jboolean result = DoEnsureT9SchemaPatches(env, schema,
        Rime::Instance().get_user_data_dir(),
        Rime::Instance().get_shared_data_dir());
    env->ReleaseStringUTFChars(schema_id, schema);
    return result;
}

// 获取可用方案列表
JNIEXPORT jobjectArray JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeGetAvailableSchemas(
    JNIEnv* env,
    jobject thiz
) {
    std::vector<std::pair<std::string, std::string>> schemas;
    Rime::Instance().getAvailableSchemas(schemas);
    
    jclass stringClass = env->FindClass("java/lang/String");
    if (!stringClass) return nullptr;
    
    jobjectArray result = env->NewObjectArray(schemas.size(), stringClass, nullptr);
    if (!result) return nullptr;
    
    for (size_t i = 0; i < schemas.size(); ++i) {
        jstring str = env->NewStringUTF(schemas[i].first.c_str());
        env->SetObjectArrayElement(result, i, str);
        env->DeleteLocalRef(str);
    }
    
    return result;
}

// 读取方案配置列表项（schema + custom.yaml patch 合并后的最终值）
JNIEXPORT jobjectArray JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeGetSchemaList(
    JNIEnv* env,
    jobject thiz,
    jstring schema_id,
    jstring key
) {
    const char* schema = env->GetStringUTFChars(schema_id, nullptr);
    if (!schema) return nullptr;
    const char* key_ptr = env->GetStringUTFChars(key, nullptr);
    if (!key_ptr) {
        env->ReleaseStringUTFChars(schema_id, schema);
        return nullptr;
    }
    std::vector<std::string> items = Rime::Instance().getSchemaList(schema, key_ptr);
    env->ReleaseStringUTFChars(schema_id, schema);
    env->ReleaseStringUTFChars(key, key_ptr);

    jclass stringClass = env->FindClass("java/lang/String");
    if (!stringClass) return nullptr;

    jobjectArray result = env->NewObjectArray(items.size(), stringClass, nullptr);
    if (!result) return nullptr;

    for (size_t i = 0; i < items.size(); ++i) {
        jstring str = env->NewStringUTF(items[i].c_str());
        env->SetObjectArrayElement(result, i, str);
        env->DeleteLocalRef(str);
    }
    return result;
}

// 读取方案配置字符串项（schema + custom.yaml patch 合并后的最终值）
JNIEXPORT jstring JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeGetSchemaString(
    JNIEnv* env,
    jobject thiz,
    jstring schema_id,
    jstring key
) {
    const char* schema = env->GetStringUTFChars(schema_id, nullptr);
    if (!schema) return nullptr;
    const char* key_ptr = env->GetStringUTFChars(key, nullptr);
    if (!key_ptr) {
        env->ReleaseStringUTFChars(schema_id, schema);
        return nullptr;
    }
    std::string value = Rime::Instance().getSchemaString(schema, key_ptr);
    env->ReleaseStringUTFChars(schema_id, schema);
    env->ReleaseStringUTFChars(key, key_ptr);
    return value.empty() ? nullptr : env->NewStringUTF(value.c_str());
}

// 读取 user.yaml 用户状态字符串
JNIEXPORT jstring JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeGetUserConfigString(
    JNIEnv* env,
    jobject thiz,
    jstring key
) {
    const char* key_ptr = env->GetStringUTFChars(key, nullptr);
    if (!key_ptr) return nullptr;
    std::string value = Rime::Instance().getUserConfigString(key_ptr);
    env->ReleaseStringUTFChars(key, key_ptr);
    return value.empty() ? nullptr : env->NewStringUTF(value.c_str());
}

// 读取 user.yaml 用户状态布尔值
JNIEXPORT jboolean JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeGetUserConfigBool(
    JNIEnv* env,
    jobject thiz,
    jstring key
) {
    const char* key_ptr = env->GetStringUTFChars(key, nullptr);
    if (!key_ptr) return JNI_FALSE;
    bool value = Rime::Instance().getUserConfigBool(key_ptr);
    env->ReleaseStringUTFChars(key, key_ptr);
    return value ? JNI_TRUE : JNI_FALSE;
}

// 写 user.yaml 用户状态字符串
JNIEXPORT jboolean JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeSetUserConfigString(
    JNIEnv* env,
    jobject thiz,
    jstring key,
    jstring value
) {
    const char* key_ptr = env->GetStringUTFChars(key, nullptr);
    if (!key_ptr) return JNI_FALSE;
    const char* value_ptr = env->GetStringUTFChars(value, nullptr);
    if (!value_ptr) {
        env->ReleaseStringUTFChars(key, key_ptr);
        return JNI_FALSE;
    }
    bool result = Rime::Instance().setUserConfigString(key_ptr, value_ptr);
    env->ReleaseStringUTFChars(key, key_ptr);
    env->ReleaseStringUTFChars(value, value_ptr);
    return result ? JNI_TRUE : JNI_FALSE;
}

// 写 user.yaml 用户状态布尔值
JNIEXPORT jboolean JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeSetUserConfigBool(
    JNIEnv* env,
    jobject thiz,
    jstring key,
    jboolean value
) {
    const char* key_ptr = env->GetStringUTFChars(key, nullptr);
    if (!key_ptr) return JNI_FALSE;
    bool result = Rime::Instance().setUserConfigBool(key_ptr, value == JNI_TRUE);
    env->ReleaseStringUTFChars(key, key_ptr);
    return result ? JNI_TRUE : JNI_FALSE;
}

// 设置 Rime 选项
JNIEXPORT void JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeSetOption(
    JNIEnv* env,
    jobject thiz,
    jstring option,
    jboolean value
) {
    const char* option_ptr = env->GetStringUTFChars(option, nullptr);
    if (!option_ptr) return;
    Rime::Instance().setOption(option_ptr, value == JNI_TRUE);
    env->ReleaseStringUTFChars(option, option_ptr);
}

// 无痕：不从上屏学习（主翻译器与九键用户词典），已有词库照常使用
JNIEXPORT void JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeSetIncognito(
    JNIEnv* env,
    jobject thiz,
    jboolean on
) {
    Rime::Instance().setIncognito(on == JNI_TRUE);
}

// 读取 Rime 选项
JNIEXPORT void JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeSetNeighborMap(JNIEnv* env, jobject, jstring value) {
    const char* encoded = env->GetStringUTFChars(value, nullptr);
    if (!encoded) return;
    Rime::Instance().setNeighborMap(encoded);
    env->ReleaseStringUTFChars(value, encoded);
}

JNIEXPORT void JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeSetSpatialMap(JNIEnv* env, jobject, jstring value) {
    const char* encoded = value ? env->GetStringUTFChars(value, nullptr) : nullptr;
    Rime::Instance().setSpatialMap(encoded ? encoded : "");
    if (value && encoded) env->ReleaseStringUTFChars(value, encoded);
}

// 组词前已上屏的文字；null 表示未知（不提供上文）。
JNIEXPORT void JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeSetT9LeftContext(JNIEnv* env, jobject, jstring text) {
    std::string value;
    if (text) {
        const char* chars = env->GetStringUTFChars(text, nullptr);
        if (!chars) return;
        value = std::string("\x1e") + chars;
        env->ReleaseStringUTFChars(text, chars);
    }
    Rime::Instance().setT9LeftContext(value);
}

JNIEXPORT jboolean JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeGetOption(
    JNIEnv* env,
    jobject thiz,
    jstring option
) {
    const char* option_ptr = env->GetStringUTFChars(option, nullptr);
    if (!option_ptr) return JNI_FALSE;
    Bool result = Rime::Instance().getOption(option_ptr);
    env->ReleaseStringUTFChars(option, option_ptr);
    return result ? JNI_TRUE : JNI_FALSE;
}

// 销毁引擎
JNIEXPORT void JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeDestroy(
    JNIEnv* env,
    jobject thiz
) {
    LOGI("Destroying Rime engine");
    // Compact T9 数字词典（flush WAL 为 .ldb），确保进程退出时数据持久化。
    rime::T9DigitUserDict::CompactDb();
    Rime::Instance().destroy();
}

// 部署
JNIEXPORT jboolean JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeReloadSessionAfterMaintenance(JNIEnv*, jobject) {
    return Rime::Instance().reloadSessionAfterMaintenance() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeT9RefinementState(JNIEnv*, jobject) {
    return rime::T9SentenceRefinementState();
}
JNIEXPORT jboolean JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeRefineOmission(JNIEnv*, jobject) {
    return Rime::Instance().refineOmission() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeRefineT9Sentences(JNIEnv*, jobject) {
    return Rime::Instance().refineT9Sentences() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeDeploy(
    JNIEnv* env,
    jobject thiz
) {
    LOGI("Deploying Rime engine");
    return Rime::Instance().deploy() ? JNI_TRUE : JNI_FALSE;
}

// 启动维护（词库编译/刷新），返回是否成功启动部署
JNIEXPORT jboolean JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeStartMaintenance(
    JNIEnv* env,
    jobject thiz,
    jboolean full
) {
    Bool result = Rime::Instance().startMaintenance(full == JNI_TRUE);
    return result ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeJoinMaintenance(JNIEnv*, jobject) {
    rime_get_api()->join_maintenance_thread();
}

// 更新 last_build_time 为当前时间，避免下次增量检测误判
JNIEXPORT void JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeUpdateLastBuildTime(
    JNIEnv* env,
    jobject thiz
) {
    Rime::Instance().updateLastBuildTime();
}

// 部署单个方案
JNIEXPORT jboolean JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeDeploySchema(
    JNIEnv* env,
    jobject thiz,
    jstring schema_id
) {
    const char* schema_id_ptr = env->GetStringUTFChars(schema_id, nullptr);
    if (!schema_id_ptr) return JNI_FALSE;
    
    LOGI("Deploying schema: %s", schema_id_ptr);
    
    // 构建 schema 文件路径
    Rime::Instance().deploySchema(schema_id_ptr);
    
    env->ReleaseStringUTFChars(schema_id, schema_id_ptr);
    return JNI_TRUE;
}

// 查询词汇编码
JNIEXPORT jstring JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeLookupText(
    JNIEnv* env,
    jobject thiz,
    jstring text
) {
    const char* text_ptr = env->GetStringUTFChars(text, nullptr);
    std::string code;
    bool found = Rime::Instance().lookupText(text_ptr, code);
    env->ReleaseStringUTFChars(text, text_ptr);
    
    if (found && !code.empty()) {
        return env->NewStringUTF(code.c_str());
    }
    return env->NewStringUTF("");
}

// 设置候选词每页数量
JNIEXPORT void JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeSetPageSize(
    JNIEnv* env,
    jobject thiz,
    jstring schema_id,
    jint page_size
) {
    const char* schema = env->GetStringUTFChars(schema_id, nullptr);
    if (!schema) return;
    Rime::Instance().setPageSize(schema, page_size);
    env->ReleaseStringUTFChars(schema_id, schema);
}

// 检查 Rime 模块是否已注册（用于测试插件集成）
JNIEXPORT jboolean JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeIsModuleRegistered(
    JNIEnv* env,
    jobject thiz,
    jstring module_name
) {
    const char* name = env->GetStringUTFChars(module_name, nullptr);
    if (!name) return JNI_FALSE;
    
    bool found = false;
    RimeApi* api = rime_get_api();
    if (api && RIME_API_AVAILABLE(api, find_module)) {
        RimeModule* m = api->find_module(name);
        found = (m != nullptr);
    } else {
        RimeModule* m = RimeFindModule(name);
        found = (m != nullptr);
    }
    
    LOGI("Module check: '%s' -> %s", name, found ? "FOUND" : "NOT FOUND");
    env->ReleaseStringUTFChars(module_name, name);
    return found ? JNI_TRUE : JNI_FALSE;
  }

// ═══════════════════════════════════════════════════════════
// T9 Processor JNI 接口
// ═══════════════════════════════════════════════════════════

// 右选候选：根据候选拼音与文本长度执行右侧选词（消费计算）。
// 委托给 T9RightCommitHandler 三层消费算法（设计稿 §6.2）。
// 注：传入候选拼音注释（comment）、候选文本与候选词字数，而非索引，避免翻页后索引错位。
// 用户词典调频不在此处进行——由 Kotlin 在 full commit 上屏后经 nativeT9Memorize 单独调用。
JNIEXPORT jboolean JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeT9SelectCandidate(
    JNIEnv* env,
    jobject thiz,
    jstring pinyin,
    jstring text,
    jint text_length
) {
    rime::T9Processor* proc = rime::T9ProcessorRequire();
    if (!proc) {
        LOGE("nativeT9SelectCandidate: no active T9Processor");
        return JNI_FALSE;
    }
    const char* p = env->GetStringUTFChars(pinyin, nullptr);
    if (!p) return JNI_FALSE;
    std::string candidate_text;
    if (text) {
        const char* t = env->GetStringUTFChars(text, nullptr);
        if (t) {
            candidate_text.assign(t);
            env->ReleaseStringUTFChars(text, t);
        }
    }
    bool result = proc->SelectCandidate(std::string(p), candidate_text, text_length);
    env->ReleaseStringUTFChars(pinyin, p);
    return result ? JNI_TRUE : JNI_FALSE;
}

// T9 用户词典写入/回滚公共实现（memorize=true → commits=+1；false → commits=-1）。
// 调频文本与拼音由 Kotlin 在 full commit 上屏后传入（Kotlin 上屏路径为唯一真相源）。
static jboolean T9DictUpdate(JNIEnv* env, jstring text, jstring pinyin, bool memorize) {
    rime::T9Processor* proc = rime::T9ProcessorRequire();
    if (!proc) {
        LOGE("T9DictUpdate: no active T9Processor");
        return JNI_FALSE;
    }
    const char* t = env->GetStringUTFChars(text, nullptr);
    if (!t) return JNI_FALSE;
    std::string p;
    if (pinyin) {
        const char* pp = env->GetStringUTFChars(pinyin, nullptr);
        if (pp) {
            p.assign(pp);
            env->ReleaseStringUTFChars(pinyin, pp);
        }
    }
    bool result = memorize ? proc->MemorizeEntry(std::string(t), p)
                           : proc->ForgetEntry(std::string(t), p);
    env->ReleaseStringUTFChars(text, t);
    return result ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeT9Memorize(
    JNIEnv* env,
    jobject thiz,
    jstring text,
    jstring pinyin
) {
    return T9DictUpdate(env, text, pinyin, true);
}

JNIEXPORT jboolean JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeExportUserDict(JNIEnv* env, jobject, jstring file) {
    const char* text = env->GetStringUTFChars(file, nullptr);
    const std::string copy(text ? text : "");
    if (text) env->ReleaseStringUTFChars(file, text);
    return Rime::Instance().exportUserDict(copy) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeRestoreUserDict(JNIEnv* env, jobject, jstring file) {
    const char* text = env->GetStringUTFChars(file, nullptr);
    const std::string copy(text ? text : "");
    if (text) env->ReleaseStringUTFChars(file, text);
    return Rime::Instance().restoreUserDict(copy);
}

JNIEXPORT jint JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeUnlearn(JNIEnv* env, jobject, jstring rows) {
    const char* text = env->GetStringUTFChars(rows, nullptr);
    const std::string copy(text ? text : "");
    if (text) env->ReleaseStringUTFChars(rows, text);
    return Rime::Instance().unlearn(copy);
}

JNIEXPORT jint JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeLearn(JNIEnv* env, jobject, jstring rows) {
    const char* text = env->GetStringUTFChars(rows, nullptr);
    const std::string copy(text ? text : "");
    if (text) env->ReleaseStringUTFChars(rows, text);
    return Rime::Instance().learn(copy);
}

JNIEXPORT jboolean JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeT9Forget(
    JNIEnv* env,
    jobject thiz,
    jstring text,
    jstring pinyin
) {
    return T9DictUpdate(env, text, pinyin, false);
}

// 拂动固定字母：并入当前数字串并记住该位置的字母；数字串为空返回 false。
JNIEXPORT jboolean JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeT9FixLetter(JNIEnv* env, jobject, jstring letter) {
    rime::T9Processor* proc = rime::T9ProcessorRequire();
    if (!proc || !letter) return JNI_FALSE;
    const char* chars = env->GetStringUTFChars(letter, nullptr);
    if (!chars) return JNI_FALSE;
    const bool result = chars[0] != '\0' && proc->FixLetter(chars[0]);
    env->ReleaseStringUTFChars(letter, chars);
    return result ? JNI_TRUE : JNI_FALSE;
}

// 直接选择拼音（替代 SelectSyllable 的候选索引方式）
JNIEXPORT jboolean JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeT9SelectPinyinDirect(
    JNIEnv* env,
    jobject thiz,
    jstring pinyin,
    jint digit_length
) {
    rime::T9Processor* proc = rime::T9ProcessorRequire();
    if (!proc) return JNI_FALSE;
    const char* p = env->GetStringUTFChars(pinyin, nullptr);
    if (!p) return JNI_FALSE;
    proc->SelectPinyinDirect(std::string(p), digit_length);
    env->ReleaseStringUTFChars(pinyin, p);
    return JNI_TRUE;
}

// 清空 T9Processor 全部状态（buffer + undo + state machine）+ RIME composition。
// mode=0: 仅清 RIME composition（保留 local state）
// mode=1: 清 composition + 重置 local state（clearAll 场景）
JNIEXPORT void JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeT9ClearComposition(
    JNIEnv* env,
    jobject thiz,
    jint mode
) {
    rime::T9CancelSentenceScores();
    rime::T9Processor* proc = rime::T9ProcessorRequire();
    if (!proc) return;
    proc->ClearComposition(mode);
}

// 执行 T9Processor 累积的待发送引擎动作（set_input → compose / clear 等）。
// 异步 flush 模型：T9 处理器在 processKey 内只标记 pending（SendToRime），
// 真正触发引擎的调用延迟到此处执行，由应用层在 processKey 之后的
// 后台线程调用，避免引擎 compose 阻塞 UI 线程。
JNIEXPORT void JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeT9FlushRimeInput(
    JNIEnv* env,
    jobject thiz
) {
    rime::T9Processor* proc = rime::T9ProcessorRequire();
    if (!proc) return;
    proc->FlushRimeInput();
}

// 获取并消费 P1 撤销 RightCommit 的计数。
// 每次 P1 撤销自增 1，Kotlin 层每次查询后自减，避免重复消费。
JNIEXPORT jint JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeT9GetAndConsumeUndoneRightCommitCount(
    JNIEnv* env,
    jobject thiz
) {
    rime::T9Processor* proc = rime::T9ProcessorRequire();
    if (!proc) return 0;
    return proc->GetAndConsumeUndoneRightCommitCount();
}

// 获取 t9_processor 中的剩余数字（partial commit 后重新发送到 RIME）
JNIEXPORT jstring JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeT9GetRemainingDigits(
    JNIEnv* env,
    jobject thiz
) {
    rime::T9Processor* proc = rime::T9ProcessorRequire();
    if (!proc) return env->NewStringUTF("");
    std::string digits = proc->GetRemainingDigits();
    return env->NewStringUTF(digits.c_str());
}

// 获取左侧面板状态（格式：STATE;PINYIN;DIGIT_LEN;SEL_DIGITS;PANEL_DIGITS;LEFT_LOCKED）
JNIEXPORT jstring JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeT9GetLeftPanelState(
    JNIEnv* env,
    jobject thiz
) {
    rime::T9Processor* proc = rime::T9ProcessorRequire();
    if (!proc) return env->NewStringUTF("IDLE;;;;;0");
    std::string state = proc->GetLeftPanelState();
    return env->NewStringUTF(state.c_str());
}

// 获取首音节候选列表（P3 方案 A：替代 Kotlin T9PinyinMap.firstSyllableOptions）
// 返回格式 "pinyin|digitLength" 逗号分隔，如 "ji|2,li|2,j|1,k|1,l|1"
JNIEXPORT jstring JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeT9GetFirstSyllableOptions(
    JNIEnv* env,
    jobject thiz,
    jstring j_digits,
    jint j_max_results
) {
    const char* digits = env->GetStringUTFChars(j_digits, nullptr);
    if (!digits) return env->NewStringUTF("");

    rime::T9Processor* proc = rime::T9ProcessorRequire();
    if (!proc) {
        env->ReleaseStringUTFChars(j_digits, digits);
        return env->NewStringUTF("");
    }

    std::vector<std::string> options;
    proc->GetFirstSyllableOptions(digits, j_max_results, options);
    env->ReleaseStringUTFChars(j_digits, digits);

    // 序列化为 "pinyin|digitLength,pinyin|digitLength,..."
    std::string result;
    for (size_t i = 0; i < options.size(); i++) {
        if (i > 0) result += ",";
        result += options[i];
    }
    return env->NewStringUTF(result.c_str());
}

// Fatal signals remain owned by Android/ART/debuggerd and existing handlers.
// Keep the JNI entry point for compatibility; do not install a crash handler.
// No in-process unwinding or crash-log I/O is attempted here.
JNIEXPORT void JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeInstallSignalHandler(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jstring /*user_data_dir*/
) {
    // Intentionally empty. Collect native diagnostics through Android tooling.
}

} // extern "C"
