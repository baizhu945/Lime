// JNI bridge for CyLM on Android (prototype; not wired into the IME). Kotlin side: CylmNative.kt.
// Build into libcylm.so together with ../native/cylm.cpp compiled with -DCYLM_LIBRARY.
#include <jni.h>

#include <string>
#include <vector>

#include "cylm.h"

namespace {
std::string Utf8(JNIEnv* env, jstring s) {
  if (!s) return {};
  const char* c = env->GetStringUTFChars(s, nullptr);  // modified UTF-8: fine for BMP Han text and ASCII keys
  std::string out(c ? c : "");
  if (c) env->ReleaseStringUTFChars(s, c);
  return out;
}
}  // namespace

extern "C" {
JNIEXPORT jlong JNICALL Java_im_cyime_cylm_CylmNative_open(JNIEnv* env, jclass, jstring lexicon, jstring model,
                                                            jstring model2, jstring params) {
  auto l = Utf8(env, lexicon), m = Utf8(env, model), m2 = Utf8(env, model2), p = Utf8(env, params);
  return reinterpret_cast<jlong>(cylm_open(l.c_str(), m.c_str(), m2.empty() ? nullptr : m2.c_str(), p.c_str()));
}

JNIEXPORT jlong JNICALL Java_im_cyime_cylm_CylmNative_open2(JNIEnv* env, jclass, jstring lexicon, jstring model,
                                                             jstring model2, jstring reranker, jstring vocab, jstring params) {
  auto l = Utf8(env, lexicon), m = Utf8(env, model), m2 = Utf8(env, model2), r = Utf8(env, reranker), v = Utf8(env, vocab),
       p = Utf8(env, params);
  return reinterpret_cast<jlong>(cylm_open2(l.c_str(), m.c_str(), m2.empty() ? nullptr : m2.c_str(),
                                            r.empty() ? nullptr : r.c_str(), v.empty() ? nullptr : v.c_str(), p.c_str()));
}

// Like decode, but each element is "text\u001fpinyin\u001fspans" (see cylm_decode2 in cylm.h).
JNIEXPORT jobjectArray JNICALL Java_im_cyime_cylm_CylmNative_decode2(JNIEnv* env, jclass, jlong session, jstring keys,
                                                                     jint mode, jstring context) {
  auto k = Utf8(env, keys), c = Utf8(env, context);
  std::vector<char> buf(32768);
  int n = cylm_decode2(reinterpret_cast<CylmSession*>(session), k.c_str(), mode, c.c_str(), buf.data(), int(buf.size()));
  jclass str = env->FindClass("java/lang/String");
  if (!str) return nullptr;
  env->DeleteLocalRef(str);
  str = env->FindClass("java/lang/String");
  if (!str) return nullptr;
  jobjectArray out = env->NewObjectArray(n > 0 ? n : 0, str, nullptr);
  if (!out) { env->DeleteLocalRef(str); return nullptr; }
  const char* q = buf.data();
  for (int i = 0; i < n; ++i) {
    jstring s = env->NewStringUTF(q);
    env->SetObjectArrayElement(out, i, s);
    env->DeleteLocalRef(s);
    q += std::char_traits<char>::length(q) + 1;
  }
  return out;
}

JNIEXPORT jlong JNICALL Java_im_cyime_cylm_CylmNative_session(JNIEnv*, jclass, jlong model) {
  return reinterpret_cast<jlong>(cylm_session(reinterpret_cast<CylmModel*>(model)));
}

JNIEXPORT jobjectArray JNICALL Java_im_cyime_cylm_CylmNative_decode(JNIEnv* env, jclass, jlong session, jstring keys,
                                                                    jint mode, jstring context) {
  auto k = Utf8(env, keys), c = Utf8(env, context);
  std::vector<char> buf(16384);
  int n = cylm_decode(reinterpret_cast<CylmSession*>(session), k.c_str(), mode, c.c_str(), buf.data(), int(buf.size()));
  jclass str = env->FindClass("java/lang/String");
  if (!str) return nullptr;
  env->DeleteLocalRef(str);
  str = env->FindClass("java/lang/String");
  if (!str) return nullptr;
  jobjectArray out = env->NewObjectArray(n > 0 ? n : 0, str, nullptr);
  if (!out) { env->DeleteLocalRef(str); return nullptr; }
  const char* p = buf.data();
  for (int i = 0; i < n; ++i) {
    jstring s = env->NewStringUTF(p);
    env->SetObjectArrayElement(out, i, s);
    env->DeleteLocalRef(s);
    p += std::char_traits<char>::length(p) + 1;
  }
  return out;
}

JNIEXPORT void JNICALL Java_im_cyime_cylm_CylmNative_closeSession(JNIEnv*, jclass, jlong session) {
  cylm_close_session(reinterpret_cast<CylmSession*>(session));
}

JNIEXPORT void JNICALL Java_im_cyime_cylm_CylmNative_close(JNIEnv*, jclass, jlong model) {
  cylm_close(reinterpret_cast<CylmModel*>(model));
}
}
