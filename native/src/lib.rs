use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jint, jlong, jstring, JNI_FALSE};
use jni::JNIEnv;
use nobodywho::chat::{ChatBuilder, ChatHandle};
use nobodywho::llm;
use nobodywho::tokenizer::Prompt;
use std::path::Path;
use std::sync::Arc;

/// Helper: convert a JString to a Rust String, returning an error string on failure.
fn jstring_to_string(env: &mut JNIEnv, js: &JString) -> Result<String, String> {
    env.get_string(js)
        .map(|s| s.into())
        .map_err(|e| format!("Failed to read Java string: {e}"))
}

/// Helper: throw a Java RuntimeException and return a default value.
fn throw_and_return<T: Default>(env: &mut JNIEnv, msg: &str) -> T {
    let _ = env.throw_new("java/lang/RuntimeException", msg);
    T::default()
}

// ---------------------------------------------------------------------------
// Model
// ---------------------------------------------------------------------------

/// Load a GGUF model (and optional vision projection model).
/// Returns an opaque handle (pointer to Arc<Model>) as jlong.
#[no_mangle]
pub extern "system" fn Java_com_vagujhelyigergely_calculatorm3_ai_NobodyWhoBridge_loadModel(
    mut env: JNIEnv,
    _class: JClass,
    model_path: JString,
    use_gpu: jboolean,
    mmproj_path: JString,
) -> jlong {
    let model_path = match jstring_to_string(&mut env, &model_path) {
        Ok(s) => s,
        Err(e) => return throw_and_return(&mut env, &e),
    };

    let mmproj: Option<String> = if env.is_same_object(&mmproj_path, JString::default()).unwrap_or(true) {
        None
    } else {
        match jstring_to_string(&mut env, &mmproj_path) {
            Ok(s) if s.is_empty() => None,
            Ok(s) => Some(s),
            Err(e) => return throw_and_return(&mut env, &e),
        }
    };

    let model = match llm::get_model(
        &model_path,
        use_gpu != JNI_FALSE,
        mmproj.as_deref(),
    ) {
        Ok(m) => m,
        Err(e) => return throw_and_return(&mut env, &format!("Failed to load model: {e}")),
    };

    let arc = Arc::new(model);
    let boxed = Box::new(arc);
    Box::into_raw(boxed) as jlong
}

/// Free a model handle previously returned by loadModel.
#[no_mangle]
pub extern "system" fn Java_com_vagujhelyigergely_calculatorm3_ai_NobodyWhoBridge_freeModel(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle != 0 {
        unsafe {
            let _ = Box::from_raw(handle as *mut Arc<llm::Model>);
        }
    }
}

// ---------------------------------------------------------------------------
// Chat
// ---------------------------------------------------------------------------

/// Create a blocking chat handle from a model handle.
/// Returns an opaque handle (pointer to ChatHandle) as jlong.
#[no_mangle]
pub extern "system" fn Java_com_vagujhelyigergely_calculatorm3_ai_NobodyWhoBridge_createChat(
    mut env: JNIEnv,
    _class: JClass,
    model_handle: jlong,
    system_prompt: JString,
    context_size: jint,
) -> jlong {
    if model_handle == 0 {
        return throw_and_return(&mut env, "Invalid model handle (null)");
    }

    let model_arc: &Arc<llm::Model> = unsafe { &*(model_handle as *const Arc<llm::Model>) };

    let sys_prompt: Option<String> =
        if env.is_same_object(&system_prompt, JString::default()).unwrap_or(true) {
            None
        } else {
            match jstring_to_string(&mut env, &system_prompt) {
                Ok(s) if s.is_empty() => None,
                Ok(s) => Some(s),
                Err(e) => return throw_and_return(&mut env, &e),
            }
        };

    let chat = ChatBuilder::new(Arc::clone(model_arc))
        .with_system_prompt(sys_prompt)
        .with_context_size(context_size as u32)
        .build();

    let boxed = Box::new(chat);
    Box::into_raw(boxed) as jlong
}

/// Free a chat handle previously returned by createChat.
#[no_mangle]
pub extern "system" fn Java_com_vagujhelyigergely_calculatorm3_ai_NobodyWhoBridge_freeChat(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle != 0 {
        unsafe {
            let _ = Box::from_raw(handle as *mut ChatHandle);
        }
    }
}

// ---------------------------------------------------------------------------
// Inference
// ---------------------------------------------------------------------------

/// Send a text-only prompt and block until the full response is ready.
#[no_mangle]
pub extern "system" fn Java_com_vagujhelyigergely_calculatorm3_ai_NobodyWhoBridge_ask(
    mut env: JNIEnv,
    _class: JClass,
    chat_handle: jlong,
    text_prompt: JString,
) -> jstring {
    if chat_handle == 0 {
        return throw_and_return(&mut env, "Invalid chat handle (null)");
    }

    let prompt_str = match jstring_to_string(&mut env, &text_prompt) {
        Ok(s) => s,
        Err(e) => return throw_and_return(&mut env, &e),
    };

    let chat: &ChatHandle = unsafe { &*(chat_handle as *const ChatHandle) };

    let mut stream = chat.ask(&*prompt_str);
    match stream.completed() {
        Ok(response) => match env.new_string(&response) {
            Ok(js) => js.into_raw(),
            Err(e) => throw_and_return(&mut env, &format!("Failed to create Java string: {e}")),
        },
        Err(e) => throw_and_return(&mut env, &format!("Inference failed: {e}")),
    }
}

/// Send a multimodal prompt (text + image) and block until the full response is ready.
#[no_mangle]
pub extern "system" fn Java_com_vagujhelyigergely_calculatorm3_ai_NobodyWhoBridge_askWithImage(
    mut env: JNIEnv,
    _class: JClass,
    chat_handle: jlong,
    text_prompt: JString,
    image_path: JString,
) -> jstring {
    if chat_handle == 0 {
        return throw_and_return(&mut env, "Invalid chat handle (null)");
    }

    let prompt_str = match jstring_to_string(&mut env, &text_prompt) {
        Ok(s) => s,
        Err(e) => return throw_and_return(&mut env, &e),
    };

    let img_path = match jstring_to_string(&mut env, &image_path) {
        Ok(s) => s,
        Err(e) => return throw_and_return(&mut env, &e),
    };

    let mut prompt = Prompt::new();
    prompt.push_text(&prompt_str);
    prompt.push_image(Path::new(&img_path));

    let chat: &ChatHandle = unsafe { &*(chat_handle as *const ChatHandle) };

    let mut stream = chat.ask(prompt);
    match stream.completed() {
        Ok(response) => match env.new_string(&response) {
            Ok(js) => js.into_raw(),
            Err(e) => throw_and_return(&mut env, &format!("Failed to create Java string: {e}")),
        },
        Err(e) => throw_and_return(&mut env, &format!("Inference failed: {e}")),
    }
}
