#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <cstdlib>
#include <sys/sysinfo.h>
#include <cstdint>
#include <cstring>
#include "whisper.h"
#include "ggml.h"
#include "grammar-parser.h"
#include "common.h"
#include <sstream>
#include <cassert>
#include <cstdio>
#include <fstream>
#include <mutex>
#include <regex>
#include <string>
#include <thread>
#include <vector>
#include <map>
#include <iostream>
#include <sstream>


#define UNUSED(x) (void)(x)
#define TAG "JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)



static inline int min(int a, int b) {
    return (a < b) ? a : b;
}

static inline int max(int a, int b) {
    return (a > b) ? a : b;
}

struct input_stream_context {
    size_t offset;
    JNIEnv* env;
    jobject thiz;
    jobject input_stream;
    jmethodID mid_available;
    jmethodID mid_read;
};

size_t inputStreamRead(void* ctx, void* output, size_t read_size) {
    input_stream_context* is = static_cast<input_stream_context*>(ctx);
    jint avail_size = is->env->CallIntMethod(is->input_stream, is->mid_available);
    jint size_to_copy = (read_size < avail_size) ? static_cast<jint>(read_size) : avail_size;
    jbyteArray byte_array = is->env->NewByteArray(size_to_copy);
    jint n_read = is->env->CallIntMethod(is->input_stream, is->mid_read, byte_array, 0, size_to_copy);

    if (size_to_copy != read_size || size_to_copy != n_read) {
        LOGI("Insufficient Read: Req=%zu, ToCopy=%d, Available=%d", read_size, size_to_copy, n_read);
    }

    jbyte* byte_array_elements = is->env->GetByteArrayElements(byte_array, nullptr);
    std::memcpy(output, byte_array_elements, size_to_copy);
    is->env->ReleaseByteArrayElements(byte_array, byte_array_elements, JNI_ABORT);
    is->env->DeleteLocalRef(byte_array);
    is->offset += size_to_copy;
    return size_to_copy;
}

bool inputStreamEof(void* ctx) {
    input_stream_context* is = static_cast<input_stream_context*>(ctx);
    jint result = is->env->CallIntMethod(is->input_stream, is->mid_available);
    return result <= 0;
}

void inputStreamClose(void* ctx) {}

extern "C" {
    JNIEXPORT jlong JNICALL
    Java_com_whispercpp_whisper_WhisperLib_00024Companion_initContextFromInputStream(
                JNIEnv* env, jobject thiz, jobject input_stream) {
        UNUSED(thiz);
        whisper_context* context = nullptr;
        whisper_model_loader loader = {};
        input_stream_context inp_ctx = {};
        inp_ctx.offset = 0;
        inp_ctx.env = env;
        inp_ctx.thiz = thiz;
        inp_ctx.input_stream = input_stream;
        jclass cls = env->GetObjectClass(input_stream);
        inp_ctx.mid_available = env->GetMethodID(cls, "available", "()I");
        inp_ctx.mid_read = env->GetMethodID(cls, "read", "([BII)I");
        loader.context = &inp_ctx;
        loader.read = inputStreamRead;
        loader.eof = inputStreamEof;
        loader.close = inputStreamClose;
        loader.eof(loader.context);
        context = whisper_init_with_params(&loader, whisper_context_default_params());
        return reinterpret_cast<jlong>(context);
        }
};


static size_t asset_read(void* ctx, void* output, size_t read_size) {
    return AAsset_read(static_cast<AAsset*>(ctx), output, read_size);
}

static bool asset_is_eof(void* ctx) {
    return AAsset_getRemainingLength64(static_cast<AAsset*>(ctx)) <= 0;
}

static void asset_close(void* ctx) {
    AAsset_close(static_cast<AAsset*>(ctx));
}

static whisper_context* whisper_init_from_asset(
        JNIEnv* env,
        jobject assetManager,
        const char* asset_path) {
    LOGI("Loading model from asset '%s'\n", asset_path);
    AAssetManager* asset_manager = AAssetManager_fromJava(env, assetManager);
    AAsset* asset = AAssetManager_open(asset_manager, asset_path, AASSET_MODE_STREAMING);
    if (!asset) {
        LOGW("Failed to open '%s'\n", asset_path);
        return nullptr;
    }
    whisper_model_loader loader = {
            .context = asset,
            .read = &asset_read,
            .eof = &asset_is_eof,
            .close = &asset_close
    };
    return whisper_init_with_params(&loader, whisper_context_default_params());
}

extern "C" {
JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_initContextFromAsset(
        JNIEnv *env, jobject thiz, jobject assetManager, jstring asset_path_str) {
    UNUSED(thiz);
    whisper_context *context = nullptr;
    const char *asset_path_chars = env->GetStringUTFChars(asset_path_str, nullptr);
    context = whisper_init_from_asset(env, assetManager, asset_path_chars);
    env->ReleaseStringUTFChars(asset_path_str, asset_path_chars);
    return reinterpret_cast<jlong>(context);
}
}

extern "C" {
JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_initContext(
        JNIEnv *env, jobject thiz, jstring model_path_str) {
    UNUSED(thiz);
    whisper_context *context = nullptr;
    const char *model_path_chars = env->GetStringUTFChars(model_path_str, nullptr);
    context = whisper_init_from_file_with_params(model_path_chars,
                                                 whisper_context_default_params());
    env->ReleaseStringUTFChars(model_path_str, model_path_chars);
    return reinterpret_cast<jlong>(context);
}
}

extern "C" {
JNIEXPORT void JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_freeContext(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    UNUSED(env);
    UNUSED(thiz);
    whisper_context *context = reinterpret_cast<whisper_context *>(context_ptr);
    whisper_free(context);
}
}


extern "C" {
JNIEXPORT void JNICALL Java_com_whispercpp_whisper_WhisperLib_00024Companion_fullTranscribe(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint num_threads, jfloatArray audio_data) {
    UNUSED(thiz);
    whisper_context *context = reinterpret_cast<whisper_context *>(context_ptr);
    jfloat *audio_data_arr = env->GetFloatArrayElements(audio_data, nullptr);
    const jsize audio_data_length = env->GetArrayLength(audio_data);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = true;
    params.print_progress = false;
    params.print_timestamps = true;
    params.print_special = false;
    params.translate = false;
    params.language = "en";
    params.n_threads = num_threads; //how many threads can I use on an S23?
    //potentially use an initial prompt for custom vocabularies?
    // initial_prompt: Optional[str]
    //        Optional text to provide as a prompt for the first window. This can be used to provide, or
    //        "prompt-engineer" a context for transcription, e.g. custom vocabularies or proper nouns
    //        to make it more likely to predict those word correctly.
    params.offset_ms = 0;
    params.no_context = true;
    params.single_segment = true; //hard code for true, objc example has it based on a button press
    params.no_timestamps = params.single_segment; //from streaming objc example

    whisper_reset_timings(context);
    LOGI("About to run whisper_full");
    if (whisper_full(context, params, audio_data_arr, audio_data_length) != 0) {
        LOGI("Failed to run the model");
    } else {
        whisper_print_timings(context);
    }

    env->ReleaseFloatArrayElements(audio_data, audio_data_arr, JNI_ABORT);
}

}

extern "C" {
JNIEXPORT void JNICALL Java_com_whispercpp_whisper_WhisperLib_00024Companion_fullStreamTranscribe(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint num_threads, jfloatArray audio_data) {
    UNUSED(thiz);
    whisper_context *context = reinterpret_cast<whisper_context *>(context_ptr);
    jfloat *audio_data_arr = env->GetFloatArrayElements(audio_data, nullptr);
    const jsize audio_data_length = env->GetArrayLength(audio_data);

//    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
        whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_BEAM_SEARCH);

    //    params.print_realtime = true; //documents say to use callback instead

    params.print_progress = false;
    params.print_timestamps = true;
    params.print_special = false;
    params.translate = false;
    params.language = "en";
    params.n_threads = num_threads; //how many threads can I use on an S23?
    params.beam_search.beam_size = 5;
    //potentially use an initial prompt for custom vocabularies?
    // initial_prompt: Optional[str]
    //        Optional text to provide as a prompt for the first window. This can be used to provide, or
    //        "prompt-engineer" a context for transcription, e.g. custom vocabularies or proper nouns
    //        to make it more likely to predict those word correctly.
    params.offset_ms = 0;
    params.audio_ctx = 768; //this reduces how much audio whisper needs to process.  1500 is 30 seconds of audio, 768 is a multiple of 8 which apparently makes math easier
    params.no_context = true;
    params.prompt_tokens = reinterpret_cast<const whisper_token *>("treating patient alpha treating patient bravo treating patient charlie administering 10 mg of fentanyl administering 10 mg of ketamine administering 5 micrograms of TXA administering 3 CCs of saline administering 1 gram of advil");
    params.single_segment = true; //hard code for true, objc example has it based on a button press
    params.no_timestamps = params.single_segment; //from streaming objc example
    params.suppress_non_speech_tokens = true; //get rid of punctuation and other things can find tokens to add in whisper.cpp in the lib
//    params.split_on_word = true;
    //params.grammar_penalty = 100.0f;
//    params.grammar_rules = ;

    whisper_reset_timings(context);
    LOGI("About to run whisper_full");
    if (whisper_full(context, params, audio_data_arr, audio_data_length) != 0) {
        LOGI("Failed to run the model");
    } else {
        whisper_print_timings(context);
    }

    env->ReleaseFloatArrayElements(audio_data, audio_data_arr, JNI_ABORT);
}

}

std::vector<std::string> readAllowedCommands(JNIEnv* env, jstring filePathJStr) {
    std::vector<std::string> allowedCommands;

    // Convert jstring to C-style string
    const char* filePath = env->GetStringUTFChars(filePathJStr, nullptr);

    // Open the file
    std::ifstream ifs(filePath);
    if (!ifs.is_open()) {
        env->ReleaseStringUTFChars(filePathJStr, filePath);
        return allowedCommands; // Return empty vector on failure
    }

    // Read lines and store commands
    std::string line;
    while (std::getline(ifs, line)) {
        line = trim(line);
        if (line.empty()) {
            continue;
        }

        std::transform(line.begin(), line.end(), line.begin(), ::tolower);

        // Add command to vector
        allowedCommands.push_back(std::move(line));
    }

    // Release resources and return
    env->ReleaseStringUTFChars(filePathJStr, filePath);
    return allowedCommands;
}

std::vector<std::string> getWords(const std::string& text) {
    std::vector<std::string> words;
    std::istringstream iss(text);
    std::string word;
    while (iss >> word) {
        words.push_back(word);
    }
    return words;
}
void logGrammarElement(const whisper_grammar_element& elem) {
    switch (elem.type) {
        case WHISPER_GRETYPE_END:
            LOGI("END");
            break;
            // ... handle other element types and log their information ...
        case WHISPER_GRETYPE_CHAR:
            LOGI("CHAR: %c", static_cast<char>(elem.value));
            break;
            // ...
    }
}
extern "C" {
JNIEXPORT jstring JNICALL Java_com_whispercpp_whisper_WhisperLib_00024Companion_testGrammarParser(
        JNIEnv* env, jobject thiz, jstring grammarString) {
    // 1. Log Input
    LOGI("INSIDE GRAMMAR_PARSER_TEST");
    const char* cGrammarString = env->GetStringUTFChars(grammarString, nullptr);
    LOGI("Received grammarString: %s", cGrammarString);
    std::string cppGrammarString(cGrammarString);
    env->ReleaseStringUTFChars(grammarString, cGrammarString);

    // 2. Parse the grammar
    LOGI("Parsing grammar...");
    grammar_parser::parse_state grammar_parsed = grammar_parser::parse(cppGrammarString.c_str());

    // 3. Create a stringstream to capture output
    std::stringstream output;

    // 4. Check Parsing Result and Log Details
    if (grammar_parsed.rules.empty()) {
        LOGI("Grammar parsing FAILED");
        output << "Failed to parse grammar";
    } else {
        LOGI("Grammar parsing SUCCESSFUL");
        output << "Grammar parsed successfully";

        // Optional: Log parsed grammar details for debugging
        LOGI("Parsed %d rules and %d symbols",
             static_cast<int>(grammar_parsed.rules.size()),
             static_cast<int>(grammar_parsed.symbol_ids.size()));

        // You can add more detailed logging about the rules and symbols here if needed
    }

    // 5. Return Result as jstring
    return env->NewStringUTF(output.str().c_str());
}
}
std::string capture_stderr(std::function<void()> func) {
    // Redirect stderr to a stringstream
    std::stringstream buffer;
    std::streambuf* old_cerr = std::cerr.rdbuf(buffer.rdbuf());

    // Call the function that writes to stderr
    func();

    // Restore stderr
    std::cerr.rdbuf(old_cerr);

    // Return the captured output as a string
    return buffer.str();
}
extern "C"{
JNIEXPORT void JNICALL Java_com_whispercpp_whisper_WhisperLib_00024Companion_guidedTranscribe(
        JNIEnv* env, jobject thiz, jlong context_ptr, jint num_threads, jfloatArray audio_data, jstring filePath) {
    UNUSED(thiz);
    whisper_context *context = reinterpret_cast<whisper_context *>(context_ptr);
    const char* cFilePath = env->GetStringUTFChars(filePath, nullptr);
//    const char* grammarContentCStr = env->GetStringUTFChars(filePath, nullptr);
    FILE* file = fopen(cFilePath, "r");
    if (file == NULL) {
        // Handle file opening error
        LOGI("Error opening file: %s", cFilePath);
        env->ReleaseStringUTFChars(filePath, cFilePath);
        return; // Indicate error
    }
    std::string grammarContent;
    char buffer[1024];
    while (fgets(buffer, sizeof(buffer), file) != NULL) {
        grammarContent += buffer;
    }
    fclose(file);
    env->ReleaseStringUTFChars(filePath, cFilePath);char* cGrammarContent = new char[grammarContent.length() + 1];
    strcpy(cGrammarContent, grammarContent.c_str());

//    LOGI("Grammar Content: %s", cGrammarContent);

// Free the buffer
    delete[] cGrammarContent;


//    std::string grammarContent = std::string(grammarContentCStr);
    //LOGI("Grammar content: %s", grammarContent.c_str());
//    env->ReleaseStringUTFChars(filePath, grammarContent);

    grammar_parser::parse_state grammar_parsed = grammar_parser::parse(grammarContent.c_str());
    std::vector<const whisper_grammar_element*> c_rules = grammar_parsed.c_rules();
//
//    for (const auto& rule : grammar_parsed.rules) {
//        for (const auto& elem : rule) {
//            logGrammarElement(elem);
//        }
//    }

    //LOGI("Parsed %d rules.", grammar_parsed.rules.size());


    jfloat *audio_data_arr = env->GetFloatArrayElements(audio_data, nullptr);
    const jsize audio_data_length = env->GetArrayLength(audio_data);

   whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
//    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_BEAM_SEARCH);
//    params.print_realtime = true; //documents say to use callback instead
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.language = "en";
    params.n_threads = num_threads; //how many threads can I use on an S23?
//potentially use an initial prompt for custom vocabularies?
// initial_prompt: Optional[str]
//        Optional text to provide as a prompt for the first window. This can be used to provide, or
//        "prompt-engineer" a context for transcription, e.g. custom vocabularies or proper nouns
//        to make it more likely to predict those word correctly.
    params.offset_ms = 0;
    params.prompt_tokens = reinterpret_cast<const whisper_token *>("enter guided mode treating patient alpha treating patient bravo treating patient charlie administering 10 mg of fentanyl administering 10 mg of ketamine administering 5 micrograms of TXA administering 3 CCs of saline administering 1 gram of advil");
    params.audio_ctx = 768; //this reduces how much audio whisper needs to process.  1500 is 30 seconds of audio, 768 is a multiple of 8 which apparently makes math easier
    params.no_context = true;
    params.single_segment   = true; //hard code for true, objc example has it based on a button press
    params.no_timestamps    = params.single_segment; //from streaming objc example
    params.suppress_non_speech_tokens = true; //get rid of punctuation and other things can find tokens to add in whisper.cpp in the lib
    params.split_on_word = true;
//    params.beam_search.beam_size = 5;
    params.grammar_penalty = 100.0f;

    if (!grammar_parsed.rules.empty()) {
        params.grammar_rules = c_rules.data();
        params.n_grammar_rules = c_rules.size();

        // Handle setting the start rule index
        if (grammar_parsed.symbol_ids.find("root") != grammar_parsed.symbol_ids.end()) {
            params.i_start_rule = grammar_parsed.symbol_ids["root"];
        } else {
            LOGI("Root rule not found in grammar");
        }
    } else {
        LOGI("Failed to parse grammar or grammar is empty");
        // Handle parsing failure
    }
//    params.grammar_rules = ;

    whisper_reset_timings(context);
    LOGI("About to run whisper_full");
    if (whisper_full(context, params, audio_data_arr, audio_data_length) != 0) {
        LOGI("Failed to run the model");
    } else {
        whisper_print_timings(context);
    }

    env->ReleaseFloatArrayElements(audio_data, audio_data_arr, JNI_ABORT);
}
}

//            // this callback is called on each new segment
//            if (!wparams.print_realtime) {
//                wparams.new_segment_callback           = whisper_print_segment_callback;
//                wparams.new_segment_callback_user_data = &user_data;
//            }
//
//            if (wparams.print_progress) {
//                wparams.progress_callback           = whisper_print_progress_callback;
//                wparams.progress_callback_user_data = &user_data;
//            }


extern "C" {
JNIEXPORT jint JNICALL Java_com_whispercpp_whisper_WhisperLib_00024Companion_getTextSegmentCount(
        JNIEnv* env, jobject thiz, jlong context_ptr) {
    UNUSED(env);
    UNUSED(thiz);
    whisper_context* context = reinterpret_cast<whisper_context*>(context_ptr);
    return whisper_full_n_segments(context);
}

JNIEXPORT jstring JNICALL Java_com_whispercpp_whisper_WhisperLib_00024Companion_getTextSegment(
        JNIEnv* env, jobject thiz, jlong context_ptr, jint index) {
    UNUSED(thiz);
    whisper_context* context = reinterpret_cast<whisper_context*>(context_ptr);
    const char* text = whisper_full_get_segment_text(context, index);
    return env->NewStringUTF(text);
}

JNIEXPORT jstring JNICALL Java_com_whispercpp_whisper_WhisperLib_00024Companion_getSystemInfo(
        JNIEnv* env, jobject thiz) {
    UNUSED(thiz);
    const char* sysinfo = whisper_print_system_info();
    return env->NewStringUTF(sysinfo);
}

JNIEXPORT jstring JNICALL Java_com_whispercpp_whisper_WhisperLib_00024Companion_benchMemcpy(
        JNIEnv* env, jobject thiz, jint n_threads) {
    UNUSED(thiz);
    const char* bench_ggml_memcpy = whisper_bench_memcpy_str(n_threads);
    return env->NewStringUTF(bench_ggml_memcpy);
}

JNIEXPORT jstring JNICALL Java_com_whispercpp_whisper_WhisperLib_00024Companion_benchGgmlMulMat(
        JNIEnv* env, jobject thiz, jint n_threads) {
    UNUSED(thiz);
    const char* bench_ggml_mul_mat = whisper_bench_ggml_mul_mat_str(n_threads);
    return env->NewStringUTF(bench_ggml_mul_mat);
}
}
